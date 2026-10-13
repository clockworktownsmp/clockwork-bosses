package clockwork.bosses;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Bounded disk work for commands and saved-boss restoration. Workers receive
 * immutable inputs; completion alone touches Minecraft, on its native thread.
 * One worker and 32 waiting jobs prevent operator requests/chunk loads from
 * growing an unlimited disk queue. Shutdown cancels queued jobs and invalidates
 * completions, so an old server cannot create bosses in a later world. */
public final class ClockworkBossTemplateTasks {
    @FunctionalInterface public interface Read<T> { T get() throws Exception; }
    private static volatile Worker current;
    private ClockworkBossTemplateTasks() {}

    static void register() {
        ServerLifecycleEvents.SERVER_STARTING.register(server -> current = new Worker());
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            Worker previous = current; current = null;
            if (previous != null) previous.close();
        });
    }

    /** Returns admission, not file success. The completion reports the actual
     * result later. Permission is rechecked before applying native changes. A
     * file write already admitted may finish after permission revocation, as
     * with the native editor; it never grants further gameplay operations. */
    public static <T> int command(CommandSourceStack source, ClockworkBossPermissions.Action action,
                                  String failureLabel, Read<T> read, java.util.function.Consumer<T> apply) {
        if (!action.require(source)) return 0;
        boolean admitted = submit(source.getServer(), read, (result, failure) -> {
            if (source.getEntity() instanceof ServerPlayer player
                    && source.getServer().getPlayerList().getPlayer(player.getUUID()) != player) return;
            if (!action.require(source)) return;
            if (failure != null) source.sendFailure(Component.literal(failureLabel + reason(failure)));
            else apply.accept(result);
        });
        if (!admitted) source.sendFailure(Component.literal("Template file worker is busy or stopping; try again shortly."));
        return admitted ? 1 : 0;
    }

    static <T> boolean submit(MinecraftServer server, Read<T> read, BiConsumer<T, Throwable> apply) {
        Worker owner = current;
        if (owner == null || owner.closed()) return false;
        CompletableFuture<T> pending;
        try { pending = owner.submit(read); }
        catch (RejectedExecutionException full) { return false; }
        pending.whenComplete((result, failure) -> {
            if (current != owner || owner.closed() || server.isStopped()) return;
            server.execute(() -> {
                if (current == owner && !owner.closed() && !server.isStopped()) apply.accept(result, failure);
            });
        });
        return true;
    }

    static String reason(Throwable failure) {
        while (failure.getCause() != null && failure instanceof java.util.concurrent.CompletionException)
            failure = failure.getCause();
        return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
    }

    /** Small standalone owner also permits deterministic queue/shutdown tests
     * without launching Minecraft. Running disk calls are interrupted on close;
     * there is no shutdown wait on the native server thread. */
    static final class Worker implements AutoCloseable {
        private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(32), task -> {
                    var thread = new Thread(task, "clockwork-template-files"); thread.setDaemon(true); return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        <T> CompletableFuture<T> submit(Read<T> read) {
            var result = new CompletableFuture<T>();
            executor.execute(new Job<>(read, result)); return result;
        }
        boolean closed() { return executor.isShutdown(); }
        @Override public void close() {
            for (Runnable queued : executor.shutdownNow()) ((Job<?>) queued).result.cancel(false);
        }
        private record Job<T>(Read<T> read, CompletableFuture<T> result) implements Runnable {
            @Override public void run() {
                try { result.complete(read.get()); }
                catch (Throwable failure) {
                    // Settle ownership even when an addon/file reader throws an
                    // Error. Preserve fatal/uncaught semantics after notifying
                    // completion; a replacement worker can handle later jobs.
                    result.completeExceptionally(failure);
                    if (failure instanceof Error fatal) throw fatal;
                }
            }
        }
    }
}
