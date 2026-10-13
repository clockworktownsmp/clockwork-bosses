package clockwork.web;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** One configuration task at a time. Disk preparation precedes connection replacement.
 * Stale relay callbacks cannot restore access after a reload or server shutdown. */
final class WorkshopRuntime implements AutoCloseable {
    @FunctionalInterface interface SettingsPath { Path resolve() throws IOException; }
    interface Factory { Prepared prepare(WorkshopSettings settings) throws IOException; }
    interface Prepared extends AutoCloseable {
        void start(Consumer<TemplateApi> available);
        @Override void close();
    }
    static final class StartFailed extends IOException {
        StartFailed() { super("Relay could not start; old access was revoked. Check the log and retry /boss workshop reload"); }
    }
    record Result(WorkshopSettings settings, boolean changed) {
        String message() {
            if (!changed && settings.active()) return "Workshop config reloaded; settings are unchanged. Existing links and browser sessions remain valid.";
            if (settings.active()) return "Workshop config reloaded; connecting to the HTTPS relay. Old links and sessions were revoked; use /boss workshop open when connected.";
            String state = settings.enabled() ? "template access is disabled" : "the relay is disabled";
            return "Workshop config reloaded; " + state + (changed ? ". Old links and sessions were revoked." : ".");
        }
    }
    private final SettingsPath path;
    private Path file; // Resolved once, ONLY on the worker. Reload cannot re-import a deleted file.
    private final BooleanSupplier onlineMode;
    private final Factory factory;
    private final Consumer<TemplateApi> available;
    private final ThreadPoolExecutor worker;
    private volatile Thread configThread;
    private WorkshopSettings settings;
    private Prepared connection;
    private CompletableFuture<Result> pending;
    private long generation;
    private boolean closed;

    WorkshopRuntime(Path file, BooleanSupplier onlineMode, Factory factory, Consumer<TemplateApi> available) {
        this(() -> file, onlineMode, factory, available);
    }
    WorkshopRuntime(SettingsPath path, BooleanSupplier onlineMode, Factory factory, Consumer<TemplateApi> available) {
        this.path = path; this.onlineMode = onlineMode; this.factory = factory; this.available = available;
        worker = new ThreadPoolExecutor(1, 1, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1), task -> {
            Thread thread = new Thread(task, "clockwork-workshop-config"); thread.setDaemon(true); configThread = thread; return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
        worker.allowCoreThreadTimeOut(true);
    }
    synchronized CompletableFuture<Result> reload(boolean createDefaults) throws IOException {
        if (closed) throw new IOException("Workshop is stopping");
        if (pending != null) throw new IOException("A workshop config reload is already running");
        var result = new CompletableFuture<Result>(); pending = result;
        try { worker.execute(() -> {
            Result loaded = null; IOException failure = null;
            try { loaded = apply(WorkshopSettings.load(settingsFile(), createDefaults)); }
            catch (IOException error) { failure = error; }
            catch (RuntimeException error) { failure = new IOException("Could not prepare workshop settings; check config and storage permissions"); }
            finally { synchronized (WorkshopRuntime.this) { if (pending == result) pending = null; } }
            if (failure == null) result.complete(loaded); else result.completeExceptionally(failure);
        }); } catch (RejectedExecutionException stopping) { pending = null; throw new IOException("Workshop config worker is stopping or busy"); }
        return result;
    }
    private Path settingsFile() throws IOException {
        if (file != null) return file;
        Path resolved = path.resolve();
        synchronized (this) {
            if (closed || Thread.currentThread().isInterrupted()) throw new IOException("Workshop is stopping");
            if (resolved == null) throw new IOException("Workshop settings path is unavailable");
            file = resolved.toAbsolutePath().normalize();
            return file;
        }
    }
    private Result apply(WorkshopSettings next) throws IOException {
        synchronized (this) {
            if (closed) throw new IOException("Workshop is stopping");
            if (next.active() && !onlineMode.getAsBoolean()) throw new IOException("Workshop requires online-mode=true for verified Minecraft login");
            if (settings != null && settings.sameConnection(next)) { settings = next; return new Result(next, false); }
        }
        Prepared prepared;
        try { prepared = next.active() ? factory.prepare(next) : null; }
        catch (IOException | RuntimeException invalidStorage) { throw new IOException("Could not prepare relay key or template storage; check their paths and permissions"); }
        boolean adopted = false;
        try {
            Prepared previous; long version;
            synchronized (this) {
                if (closed) throw new IOException("Workshop is stopping");
                version = ++generation; available.accept(null);
                previous = connection; connection = null; settings = null;
            }
            // Includes bounded cancellation of in-flight requests. Never hold our
            // monitor while closing: relay callbacks acquire it in the opposite order.
            if (previous != null) previous.close();
            synchronized (this) {
                if (closed) throw new IOException("Workshop is stopping");
                connection = prepared;
                try {
                    if (prepared != null) prepared.start(api -> {
                        synchronized (WorkshopRuntime.this) {
                            if (!closed && generation == version) available.accept(api);
                        }
                    });
                    settings = next; adopted = true;
                } catch (RuntimeException unavailable) {
                    generation++; connection = null; available.accept(null); throw new StartFailed();
                }
            }
            return new Result(next, true);
        } finally { if (!adopted && prepared != null) prepared.close(); }
    }
    @Override public void close() {
        Prepared previous; CompletableFuture<Result> result;
        synchronized (this) {
            if (closed) return;
            closed = true; generation++; available.accept(null);
            previous = connection; connection = null;
            result = pending; pending = null;
        }
        worker.shutdownNow();
        if (result != null) result.completeExceptionally(new IOException("Workshop is stopping"));
        if (previous != null) previous.close();
        // A completion callback may close its own runtime on this worker.
        // Waiting for that same thread causes a needless two-second stall.
        try { if (Thread.currentThread() != configThread) worker.awaitTermination(2, TimeUnit.SECONDS); }
        catch (InterruptedException stopping) { Thread.currentThread().interrupt(); }
    }
}
