package clockwork.web;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Opt-in registry capture. Native tasks read bounded batches; the config worker
 * owns waiting, accumulation, sorting and hashing. There is no tick listener or
 * registry access on request workers. Cancellation leaves no continuing task. */
final class WorkshopItemSnapshot {
    static final int BATCH_ITEMS = 64;
    private static final long BATCH_BUDGET_NANOS = TimeUnit.MILLISECONDS.toNanos(2);
    private static final long TOTAL_BUDGET_NANOS = TimeUnit.SECONDS.toNanos(30);
    private record Batch(int next, List<ItemCatalog.Entry> entries) {}
    private WorkshopItemSnapshot() {}

    static ItemCatalog capture(MinecraftServer server) throws IOException {
        if (server.isSameThread()) throw new IOException("Item capture preparation must run off the server thread");
        long deadline = System.nanoTime() + TOTAL_BUDGET_NANOS;
        int size = await(server.submit(() -> BuiltInRegistries.ITEM.size()), deadline);
        // Check before allocating stacks/names, allowing the native AIR entry.
        if (size > ItemCatalog.MAX_ITEMS + 1) throw new IOException("Item registry exceeds catalog limit");
        List<ItemCatalog.Entry> entries = new ArrayList<>(Math.min(size, BATCH_ITEMS));
        int next = 0;
        while (next < size) {
            int from = next;
            Batch batch = await(server.submit(() -> read(server, from, size)), deadline);
            if (batch.next() <= next) throw new IOException("Item registry snapshot was interrupted");
            entries.addAll(batch.entries()); next = batch.next();
        }
        return new ItemCatalog(entries);
    }
    private static Batch read(MinecraftServer server, int from, int size) {
        if (!server.isSameThread() || server.isStopped()) return new Batch(from, List.of());
        List<ItemCatalog.Entry> entries = new ArrayList<>(BATCH_ITEMS);
        long start = System.nanoTime(); int next = from;
        while (next < size && next - from < BATCH_ITEMS) {
            var item = BuiltInRegistries.ITEM.byId(next++);
            if (item != null && item != Items.AIR) entries.add(new ItemCatalog.Entry(
                    BuiltInRegistries.ITEM.getKey(item).toString(), new ItemStack(item).getHoverName().getString()));
            // Soft time budget: an individual third-party name getter cannot be preempted.
            if (System.nanoTime() - start >= BATCH_BUDGET_NANOS) break;
        }
        return new Batch(next, List.copyOf(entries));
    }
    private static <T> T await(CompletableFuture<T> pending, long deadline) throws IOException {
        long remaining = Math.min(TimeUnit.SECONDS.toNanos(2), deadline - System.nanoTime());
        if (remaining <= 0) { pending.cancel(false); throw new IOException("Item snapshot timed out"); }
        try { return pending.get(remaining, TimeUnit.NANOSECONDS); }
        catch (Exception unavailable) {
            pending.cancel(false);
            if (unavailable instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IOException("Item snapshot could not complete");
        }
    }
}
