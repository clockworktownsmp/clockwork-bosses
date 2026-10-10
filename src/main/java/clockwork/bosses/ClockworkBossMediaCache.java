package clockwork.bosses;

import net.minecraft.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Bounded server media snapshots. Files are read on Minecraft's I/O pool, never
 * during an attack or tracking callback. Reload starts a new generation; late
 * jobs cannot repopulate its cache. No executor or repeating task is created.
 */
final class ClockworkBossMediaCache<V> {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClockworkBosses.MOD_ID);
    private final int maxFiles;
    private static final int MAX_JOBS = 4;
    private static final int MAX_PENDING_FILES = 64;
    private final Map<String, V> files = new LinkedHashMap<>(16, 0.75F, true);
    private final Map<String, Boolean> failures = new LinkedHashMap<>(16, 0.75F, true);
    private final Map<String, CompletableFuture<V>> pending = new HashMap<>();
    private final ArrayDeque<Job> waiting = new ArrayDeque<>();
    private long generation;
    // Kept across clears until old jobs finish, so reconnect/reload cannot
    // bypass the work limit by repeatedly emptying the pending map.
    private int runningJobs;

    ClockworkBossMediaCache() { this(16); }

    /** Longer music uses four compressed snapshots; ordinary media keeps its sixteen-file bound. */
    ClockworkBossMediaCache(int maxFiles) {
        if (maxFiles < 1 || maxFiles > 16) throw new IllegalArgumentException("Invalid media cache bound");
        this.maxFiles = maxFiles;
    }

    @FunctionalInterface
    interface Reader<V> { V read() throws Exception; }

    /** Returns null for a failed file or exhausted capacity; duplicate requests share one future. */
    CompletableFuture<V> request(String key, Reader<V> reader) {
        CompletableFuture<V> result;
        RuntimeException unavailable = null;
        synchronized (this) {
            V cached = files.get(key);
            if (cached != null) return CompletableFuture.completedFuture(cached);
            CompletableFuture<V> existing = pending.get(key);
            if (existing != null) return existing;
            if (failures.get(key) != null || pending.size() >= MAX_PENDING_FILES) return null;
            result = new CompletableFuture<>();
            pending.put(key, result);
            Job job = new Job(key, reader, result, generation);
            if (runningJobs < MAX_JOBS) unavailable = start(job);
            else waiting.addLast(job);
        }
        // Even rejected submissions complete outside the cache lock: a waiting
        // consumer may synchronously request another file or acquire other locks.
        if (unavailable != null) result.completeExceptionally(unavailable);
        return result;
    }

    /** Caller holds the cache lock; reserve capacity before submission. Return
     * rejection to the caller so consumer callbacks never execute under this lock. */
    private RuntimeException start(Job job) {
        runningJobs++;
        try {
            Util.ioPool().execute(() -> load(job));
        } catch (RuntimeException unavailable) {
            runningJobs--;
            pending.remove(job.key, job.result);
            return unavailable;
        }
        return null;
    }

    /** Performs bounded file work outside the cache lock; only publishing touches shared state. */
    private void load(Job job) {
        V value = null;
        Throwable failure = null;
        List<RejectedJob> rejected = List.of();
        try {
            value = job.reader.read();
        } catch (Exception problem) {
            failure = problem;
        } finally {
            synchronized (this) {
                runningJobs--;
                pending.remove(job.key, job.result);
                if (generation == job.epoch) {
                    if (failure == null && value != null) {
                        files.put(job.key, value);
                        trim(files);
                    } else {
                        failures.put(job.key, Boolean.TRUE);
                        trim(failures);
                        LOGGER.warn("Could not load Clockwork Boss media '{}': {}. Fix the file and /boss reload.",
                                job.key, failure == null ? "reader returned no data" : failure.getMessage());
                    }
                }
                // Start one queued worker. If shutdown rejects submission, drain
                // rejected jobs so their futures/reservations cannot be stranded.
                // The error list is allocated only on this exceptional path.
                Job next = waiting.pollFirst();
                while (next != null) {
                    RuntimeException unavailable = start(next);
                    if (unavailable == null) break;
                    if (rejected.isEmpty()) rejected = new ArrayList<>();
                    rejected.add(new RejectedJob(next.result, unavailable));
                    next = waiting.pollFirst();
                }
            }
        }
        // Completion happens outside the lock: consumers may queue server work
        // or request another file without creating a lock-order dependency.
        for (RejectedJob rejectedJob : rejected) rejectedJob.result.completeExceptionally(rejectedJob.failure);
        if (failure == null && value != null) job.result.complete(value);
        else job.result.completeExceptionally(failure == null
                ? new IllegalStateException("media reader returned no data") : failure);
    }

    /** Short-lived failure data; never retained by the cache or an executor. */
    private record RejectedJob(CompletableFuture<?> result, RuntimeException failure) {}

    /** Both positive and negative snapshots have fixed count limits. */
    private void trim(Map<String, ?> cache) {
        while (cache.size() > maxFiles) cache.remove(cache.keySet().iterator().next());
    }

    /** Callers use this token to suppress stale delivery after reload or server stop. */
    synchronized long generation() { return generation; }

    /** Separate a cached file rejection from temporary queue exhaustion. Callers
     * may retry capacity in their existing scan without re-reading failed files. */
    synchronized boolean rejected(String key) { return failures.containsKey(key); }

    /** File edits take effect on reload; existing worker reservations are deliberately retained. */
    void clear() {
        List<Job> cancelled;
        synchronized (this) {
            generation++;
            files.clear();
            failures.clear();
            pending.clear();
            cancelled = List.copyOf(waiting);
            waiting.clear();
        }
        for (Job job : cancelled) job.result.completeExceptionally(
                new java.util.concurrent.CancellationException("media generation changed"));
    }

    /** One filename read and its consumers; no timer, entity scan or mod-owned thread. */
    private final class Job {
        final String key;
        final Reader<V> reader;
        final CompletableFuture<V> result;
        final long epoch;

        Job(String key, Reader<V> reader, CompletableFuture<V> result, long epoch) {
            this.key = key;
            this.reader = reader;
            this.result = result;
            this.epoch = epoch;
        }
    }
}
