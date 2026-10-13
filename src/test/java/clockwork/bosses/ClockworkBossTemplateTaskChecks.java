package clockwork.bosses;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/** Deterministic file-worker capacity/cleanup checks, without a Minecraft world. */
public final class ClockworkBossTemplateTaskChecks {
    public static void main(String[] args) throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var worker = new ClockworkBossTemplateTasks.Worker();
        var running = worker.submit(() -> { entered.countDown(); release.await(); return 1; });
        if (!entered.await(5, TimeUnit.SECONDS)) throw new AssertionError("File worker did not start");
        var queued = new ArrayList<java.util.concurrent.CompletableFuture<Integer>>();
        for (int index = 0; index < 32; index++) queued.add(worker.submit(() -> 2));
        try { worker.submit(() -> 3); throw new AssertionError("Unbounded queue accepted"); }
        catch (RejectedExecutionException expected) { }
        worker.close(); release.countDown();
        if (!worker.closed() || queued.stream().anyMatch(future -> !future.isCancelled())) throw new AssertionError("Queued file jobs survived shutdown");
        try { running.get(5, TimeUnit.SECONDS); } catch (java.util.concurrent.ExecutionException interrupted) { }
        try { worker.submit(() -> 4); throw new AssertionError("Closed worker accepted work"); } catch (RejectedExecutionException expected) { }
        try (var next = new ClockworkBossTemplateTasks.Worker()) {
            String thread = next.submit(() -> Thread.currentThread().getName()).get(5, TimeUnit.SECONDS);
            if (!thread.equals("clockwork-template-files")) throw new AssertionError("Disk work ran on caller");
        }
        var expected = new AssertionError("isolated reader failure"); var notified = new CountDownLatch(1);
        var previousHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> {
            if (failure == expected) notified.countDown();
            else if (previousHandler != null) previousHandler.uncaughtException(thread, failure);
            else failure.printStackTrace();
        });
        try (var errors = new ClockworkBossTemplateTasks.Worker()) {
            var failed = errors.submit(() -> { throw expected; });
            try { failed.get(5, TimeUnit.SECONDS); throw new AssertionError("Error reader succeeded"); }
            catch (java.util.concurrent.ExecutionException failure) { if (failure.getCause() != expected) throw failure; }
            if (!notified.await(5, TimeUnit.SECONDS) || errors.submit(() -> 7).get(5, TimeUnit.SECONDS) != 7)
                throw new AssertionError("Error semantics or worker recovery lost");
        } finally { Thread.setDefaultUncaughtExceptionHandler(previousHandler); }
        System.out.println("PASS: bounded file queue, rejection, cancellation, restart, worker ownership and settled Error futures");
    }
}
