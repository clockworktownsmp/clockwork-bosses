package clockwork.web;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Pure worker/settings migration checks. No Minecraft, real server mount,
 * Cloudflare deployment, personal credentials or external HTTP service. */
public final class WorkshopMergeChecks {
    static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    interface Attempt { void run() throws Exception; }
    static void denied(Attempt action, String why) throws Exception {
        try { action.run(); throw new AssertionError(why); } catch (IOException expected) { check(!expected.getMessage().contains("test-private-value"), "errors never echo property values"); }
    }
    static Path fixture(String suffix) throws IOException { return Files.createTempDirectory(Path.of("build").toAbsolutePath(), "workshop-merge-" + suffix); }
    static Map<String,String> hashes(Path root) throws IOException {
        Map<String,String> result = new TreeMap<>();
        try (var paths = Files.walk(root)) { for (Path file : paths.filter(Files::isRegularFile).toList()) result.put(root.relativize(file).toString(), AccessSessions.digest(Files.readAllBytes(file))); }
        return result;
    }
    public static void main(String[] args) throws Exception {
        migrate(); precedence(); malformedAndRaces(); linkedPaths(); workerAndShutdown(); randomAndBackoff(); unavailableRelay();
        System.out.println("PASS: byte-exact opt-in settings migration, canonical precedence, legacy defaults, BOM/comments/unknown values, unchanged identity/drafts/media/backups/journals, invalid/oversized/linked/interrupted/concurrent migration, worker-only once-resolved path, missing-reload non-resurrection, one-slot config queue, self-close without timeout, off-command random warm-up, bounded retry delays");
    }
    static void migrate() throws Exception {
        Path root = fixture("preserve-");
        byte[] original = ("\ufeff# retain all comments and CRLF\r\nenabled=true\r\ntemplate_access_enabled=true\r\nrelay_url=https://workshop.clockworkboss.com\r\naddon_setting=test-private-value\r\nold_web_port=27036\r\n").getBytes(StandardCharsets.UTF_8);
        Path legacy = root.resolve(WorkshopSettings.LEGACY_FILE_NAME); Files.write(legacy, original);
        RelayIdentity before = RelayIdentity.load(root);
        for (String relative : List.of("clockwork-bosses-web/drafts/example.draft", "clockwork-bosses-web/drafts/example/" + "a".repeat(64) + "/sounds/voice-lines/voice.ogg", "clockwork-bosses-web/backups/example.json", "clockwork-bosses-web/sound-backups/original.ogg", "clockwork-bosses-web/sound-publish-journals/pending.json", "clockwork-bosses/boss-templates/example/template.json", "clockwork-bosses/boss-presets/boss-sounds-example/sounds/spawn.ogg")) {
            Path path = root.resolve(relative); Files.createDirectories(path.getParent()); Files.writeString(path, "unchanged fixture " + relative);
        }
        Map<String,String> preserved = hashes(root); Path canonical = WorkshopMigration.settingsFile(root);
        check(canonical.equals(root.resolve(WorkshopSettings.FILE_NAME)), "canonical config location");
        check(Arrays.equals(Files.readAllBytes(canonical), original) && Arrays.equals(Files.readAllBytes(legacy), original), "raw source and copy preserved");
        WorkshopSettings settings = WorkshopSettings.load(canonical, false); check(settings.active(), "effective enabled settings retained");
        check(settings.equals(WorkshopSettings.load(WorkshopMigration.settingsFile(root), false)), "repeat/restart migration unchanged");
        for (var entry : preserved.entrySet()) check(entry.getValue().equals(hashes(root).get(entry.getKey())), "saved data unchanged: " + entry.getKey());
        check(before.id().equals(RelayIdentity.load(root).id()), "server address/cookie identity preserved");
        Path omittedRoot = fixture("legacy-default-"); Files.writeString(omittedRoot.resolve(WorkshopSettings.LEGACY_FILE_NAME), "template_access_enabled=true\n");
        Path omitted = WorkshopMigration.settingsFile(omittedRoot); check(WorkshopSettings.load(omitted, false).active() && WorkshopSettings.load(omitted, false).active(), "legacy omitted enabled remains true after restart");
        Path fresh = fixture("fresh-"); Path empty = WorkshopMigration.settingsFile(fresh);
        check(!Files.exists(empty) && !Files.exists(fresh.resolve(WorkshopSettings.LEGACY_FILE_NAME)), "migration does not invent legacy/default files");
        WorkshopSettings defaults = WorkshopSettings.load(empty, true); check(!defaults.enabled() && !defaults.templateAccess() && defaults.origin() == null, "fresh defaults explicitly disable both flags");
        check(!Files.exists(fresh.resolve("clockwork-bosses-web/relay-server.key")), "disabled defaults never create/reset a key");
    }
    static void precedence() throws Exception {
        Path root = fixture("precedence-"); Path canonical = root.resolve(WorkshopSettings.FILE_NAME); Files.createDirectories(canonical.getParent()); byte[] selected = "enabled=false\ntemplate_access_enabled=false\nunknown=canonical\n".getBytes(StandardCharsets.UTF_8); Files.write(canonical, selected);
        Path legacy = root.resolve(WorkshopSettings.LEGACY_FILE_NAME); Files.writeString(legacy, "relay_url=\\uZZZZ\nunknown=test-private-value\n"); byte[] ignored = Files.readAllBytes(legacy);
        check(WorkshopMigration.settingsFile(root).equals(canonical) && !WorkshopSettings.load(canonical, false).active(), "canonical wins without parsing malformed legacy");
        check(Arrays.equals(Files.readAllBytes(canonical), selected) && Arrays.equals(Files.readAllBytes(legacy), ignored), "neither existing file rewritten");
        Files.writeString(canonical, "enabled=bad\n"); denied(() -> WorkshopSettings.load(WorkshopMigration.settingsFile(root), false), "invalid canonical fell back to old access");
    }
    static void malformedAndRaces() throws Exception {
        for (String invalid : List.of("enabled=perhaps", "relay_url=\\uZZZZ", "enabled=true\ntemplate_access_enabled=true\nrelay_url=https://test-private-value@example.org", "#".repeat(WorkshopSettings.MAX_BYTES + 1))) {
            Path root = fixture("invalid-"); Path legacy = root.resolve(WorkshopSettings.LEGACY_FILE_NAME); Files.writeString(legacy, invalid); byte[] before = Files.readAllBytes(legacy);
            denied(() -> WorkshopMigration.settingsFile(root), "invalid legacy accepted"); check(!Files.exists(root.resolve(WorkshopSettings.FILE_NAME)) && Arrays.equals(before, Files.readAllBytes(legacy)), "invalid migration preserves source and absent destination");
        }
        Path utf16 = fixture("utf16-"); Files.writeString(utf16.resolve(WorkshopSettings.LEGACY_FILE_NAME), "enabled=false\n", StandardCharsets.UTF_16); denied(() -> WorkshopMigration.settingsFile(utf16), "UTF16 legacy silently changed");
        Path changed = fixture("changed-"); Path legacy = changed.resolve(WorkshopSettings.LEGACY_FILE_NAME); Files.writeString(legacy, "enabled=false\n");
        denied(() -> WorkshopMigration.settingsFile(changed, () -> Files.writeString(legacy, "enabled=true\n")), "source edit while copying accepted"); check(!Files.exists(changed.resolve(WorkshopSettings.FILE_NAME)), "concurrent source edit leaves destination absent");
        Path appeared = fixture("appeared-"); Files.writeString(appeared.resolve(WorkshopSettings.LEGACY_FILE_NAME), "enabled=true\ntemplate_access_enabled=true\n"); Path destination = appeared.resolve(WorkshopSettings.FILE_NAME);
        WorkshopMigration.settingsFile(appeared, () -> Files.writeString(destination, "enabled=false\ntemplate_access_enabled=false\n")); check(!WorkshopSettings.load(destination, false).active(), "concurrent canonical wins atomically");
        Path stopped = fixture("stopped-"); Files.writeString(stopped.resolve(WorkshopSettings.LEGACY_FILE_NAME), "enabled=false\n");
        denied(() -> WorkshopMigration.settingsFile(stopped, () -> { throw new InterruptedIOException("simulated interrupted commit"); }), "interrupted migration accepted"); check(!Files.exists(stopped.resolve(WorkshopSettings.FILE_NAME)), "interrupted migration leaves no canonical file");
        try (var paths = Files.walk(stopped)) { check(paths.noneMatch(path -> path.getFileName().toString().startsWith(".workshop-")), "migration stage cleaned after interruption"); }
    }
    static void linkedPaths() throws Exception {
        Path root = fixture("links-"); Path outside = fixture("outside-"); Files.createDirectories(root.resolve("clockwork-bosses"));
        Path canonicalParent = root.resolve("clockwork-bosses"), retained = root.resolve("retained"); Files.move(canonicalParent, retained); AudioChecks.directoryLink(canonicalParent, outside);
        try { denied(() -> WorkshopMigration.settingsFile(root), "canonical ancestor junction accepted"); } finally { Files.delete(canonicalParent); Files.move(retained, canonicalParent); }
        Path asFolder = fixture("directory-"); Files.createDirectory(asFolder.resolve(WorkshopSettings.LEGACY_FILE_NAME)); denied(() -> WorkshopMigration.settingsFile(asFolder), "legacy directory accepted");
    }
    static void workerAndShutdown() throws Exception {
        Path root = fixture("worker-"); Files.writeString(root.resolve(WorkshopSettings.LEGACY_FILE_NAME), "enabled=false\ntemplate_access_enabled=false\n");
        AtomicInteger calls = new AtomicInteger(), prepared = new AtomicInteger(); Thread caller = Thread.currentThread();
        try (var runtime = new WorkshopRuntime(() -> { check(Thread.currentThread() != caller && Thread.currentThread().getName().equals("clockwork-workshop-config"), "path migration executes only on worker"); calls.incrementAndGet(); return WorkshopMigration.settingsFile(root); }, () -> true, settings -> { prepared.incrementAndGet(); throw new AssertionError("disabled factory called"); }, api -> {})) {
            runtime.reload(true).get(3, TimeUnit.SECONDS); runtime.reload(false).get(3, TimeUnit.SECONDS); check(calls.get() == 1 && prepared.get() == 0, "one-time resolution, disabled skips all preparation");
            Files.delete(root.resolve(WorkshopSettings.FILE_NAME));
            try { runtime.reload(false).get(3, TimeUnit.SECONDS); throw new AssertionError("deleted canonical recreated on reload"); } catch (ExecutionException expected) { check(expected.getCause() instanceof IOException, "controlled missing reload error"); }
            check(!Files.exists(root.resolve(WorkshopSettings.FILE_NAME)) && calls.get() == 1, "cached path prevents resurrecting old settings on reload");
        }
        Path closeRoot = fixture("self-close-"); CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1); AtomicReference<WorkshopRuntime> ref = new AtomicReference<>();
        WorkshopRuntime runtime = new WorkshopRuntime(() -> { entered.countDown(); try { if (!release.await(3, TimeUnit.SECONDS)) throw new IOException("test gate expired"); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); throw new IOException("stopped"); } return closeRoot.resolve(WorkshopSettings.FILE_NAME); }, () -> true, settings -> { throw new AssertionError("disabled factory called"); }, api -> {}); ref.set(runtime);
        var future = runtime.reload(true); check(entered.await(3, TimeUnit.SECONDS), "worker entered");
        var workerField = WorkshopRuntime.class.getDeclaredField("worker"); workerField.setAccessible(true); ThreadPoolExecutor pool = (ThreadPoolExecutor) workerField.get(runtime); check(pool.getQueue().remainingCapacity() == 1, "physical config queue bounded to one");
        denied(() -> runtime.reload(false), "concurrent reload queued");
        var closed = future.thenApply(value -> { check(Thread.currentThread().getName().equals("clockwork-workshop-config"), "self-close callback executes on worker"); long start = System.nanoTime(); ref.get().close(); return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start); });
        release.countDown(); check(closed.get(3, TimeUnit.SECONDS) < 500, "self-close does not await itself for two seconds"); runtime.close();
    }
    static void randomAndBackoff() {
        class CountingRandom extends SecureRandom { int calls; @Override public void nextBytes(byte[] bytes) { Arrays.fill(bytes, (byte) ++calls); } }
        CountingRandom random = new CountingRandom(); var access = new AccessSessions(owner -> true, Clock.systemUTC(), random); check(random.calls == 1, "random provider warmed in construction before command");
        access.issue(new AccessSessions.Owner(UUID.randomUUID(), "fixture_owner")); check(random.calls == 2, "first command only generates an already-warmed secret"); access.clear();
        int[] delays = {1, 2, 4, 8, 16, 32, 60}; for (int i = 0; i < delays.length; i++) check(RelayClient.retryDelay(i, 0) == delays[i], "bounded exponential retry");
        check(RelayClient.retryDelay(Integer.MAX_VALUE, 1) == 61 && RelayClient.retryDelay(-1, 0) == 1, "retry clamped without overflow");
    }
    static void unavailableRelay() throws Exception {
        Path root = fixture("unavailable-"); RelayIdentity identity = RelayIdentity.load(root); AtomicInteger attempts = new AtomicInteger(); CountDownLatch entered = new CountDownLatch(1);
        RelayClient relay = new RelayClient(RelayClient.origin("http://127.0.0.1:8799", true), identity, () -> {
            check(Thread.currentThread().getName().equals("clockwork-relay-control"), "unavailable relay preparation remains on control worker"); attempts.incrementAndGet(); entered.countDown(); throw new IOException("test-private-value");
        }, api -> {}); // No socket is opened: the simulated storage failure precedes connecting.
        try {
            check(entered.await(3, TimeUnit.SECONDS), "background preparation attempted");
            var field = RelayClient.class.getDeclaredField("schedule"); field.setAccessible(true); var scheduler = (ScheduledThreadPoolExecutor) field.get(relay);
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (scheduler.getQueue().size() < 2 && System.nanoTime() < until) Thread.sleep(5);
            check(scheduler.getQueue().size() <= 2 && scheduler.getRemoveOnCancelPolicy(), "unavailability retains only heartbeat/one retry, canceled timers removed");
            check(attempts.get() < 4, "unavailable relay does not spin retry loop"); relay.close(); check(scheduler.isShutdown() && scheduler.getQueue().isEmpty(), "shutdown cancels pending retry and heartbeat");
        } finally { relay.close(); }
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (Thread.getAllStackTraces().keySet().stream().anyMatch(thread -> thread.isAlive() && thread.getName().startsWith("clockwork-relay-")) && System.nanoTime() < until) Thread.sleep(5);
        check(Thread.getAllStackTraces().keySet().stream().noneMatch(thread -> thread.isAlive() && thread.getName().startsWith("clockwork-relay-")), "no relay worker survives unavailable shutdown");
    }
}
