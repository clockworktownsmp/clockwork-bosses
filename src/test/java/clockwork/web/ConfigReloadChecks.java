package clockwork.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

public final class ConfigReloadChecks {
    private static final String ORIGIN = "https://workshop.clockworkboss.com";
    private static final AccessSessions.Owner OWNER = new AccessSessions.Owner(UUID.randomUUID(), "isolated_owner");
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static void settings(Path file, boolean enabled, boolean access, String origin) throws IOException {
        Files.writeString(file, "enabled=" + enabled + "\ntemplate_access_enabled=" + access + "\nrelay_url=" + origin + "\n");
    }
    static WorkshopRuntime.Result reload(WorkshopRuntime runtime) throws Exception { return runtime.reload(false).get(5, TimeUnit.SECONDS); }
    static IOException failure(WorkshopRuntime runtime) throws Exception {
        try { reload(runtime); throw new AssertionError("Reload should fail"); }
        catch (ExecutionException error) { check(error.getCause() instanceof IOException, "Controlled reload error"); return (IOException) error.getCause(); }
    }
    static Map<String,String> storedFiles(Path config) throws IOException {
        Map<String,String> files = new TreeMap<>();
        try (var paths = Files.walk(config)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                if (!path.equals(config.resolve(WorkshopSettings.FILE_NAME))) files.put(config.relativize(path).toString(), AccessSessions.digest(Files.readAllBytes(path)));
            }
        }
        return files;
    }
    static final class Connection implements WorkshopRuntime.Prepared {
        final TemplateApi api; Consumer<TemplateApi> callback;
        boolean closed, failStart; final AtomicInteger starts = new AtomicInteger();
        Connection(TemplateApi api) { this.api = api; }
        @Override public void start(Consumer<TemplateApi> callback) {
            this.callback = callback;
            if (failStart) throw new IllegalStateException("simulated start failure");
            starts.incrementAndGet(); callback.accept(api);
        }
        @Override public void close() { closed = true; api.close(); }
    }
    public static void main(String[] args) throws Exception {
        Path config = Files.createTempDirectory(Path.of("build").toAbsolutePath(), "config-reload-");
        Path file = config.resolve(WorkshopSettings.FILE_NAME);
        WorkshopSettings defaults = WorkshopSettings.load(file, true);
        check(!defaults.enabled() && !defaults.templateAccess() && defaults.origin() == null, "First startup remains opt-in");
        check(Files.readString(file).contains("/boss workshop reload"), "Default config documents reload");
        for (String invalid : List.of("enabled=perhaps", "template_access_enabled=True", "enabled=true\ntemplate_access_enabled=true\nrelay_url=http://example.org", "enabled=true\ntemplate_access_enabled=true\nrelay_url=https://user:secret@example.org", "relay_url=\\uZZZZ", "#".repeat(WorkshopSettings.MAX_BYTES + 1))) {
            Files.writeString(file, invalid);
            try { WorkshopSettings.load(file, false); throw new AssertionError("Invalid settings accepted"); }
            catch (IOException expected) { check(!expected.getMessage().contains("secret"), "Errors do not echo config values"); }
        }
        Files.delete(file);
        try { WorkshopSettings.load(file, false); throw new AssertionError("Missing reload file recreated"); }
        catch (IOException expected) { check(!Files.exists(file), "Missing reload cannot create defaults"); }
        Files.writeString(file, "\ufeffenabled=false\ntemplate_access_enabled=true\n", StandardCharsets.UTF_8);
        check(!WorkshopSettings.load(file, false).active(), "UTF-8 BOM cannot override the disabled flag");
        Files.writeString(file, "enabled=false\ntemplate_access_enabled=true\n", StandardCharsets.UTF_16);
        try { WorkshopSettings.load(file, false); throw new AssertionError("Unsupported UTF-16 accepted"); } catch (IOException expected) { }
        settings(file, true, true, ORIGIN);
        var files = new TemplateFiles(config, new TemplateJson());
        String json = "{\"format_version\":1,\"health\":1000,\"addon\":{\"number\":900719925474099312345,\"value\":1.2300e-120}}";
        var draft = files.save("reload_fixture", json.getBytes(StandardCharsets.UTF_8), "none", "none");
        String activeRevision = files.publish("reload_fixture", draft.revision());
        draft = files.save("reload_fixture", json.replace("1000", "1234").getBytes(StandardCharsets.UTF_8), draft.revision(), activeRevision);
        activeRevision = files.publish("reload_fixture", draft.revision());
        files.save("reload_fixture", json.replace("1000", "1235").getBytes(StandardCharsets.UTF_8), draft.revision(), activeRevision);
        Path audio = config.resolve("clockwork-bosses/boss-templates/reload_fixture/sounds");
        Files.createDirectories(audio); Files.write(audio.resolve("fixture.ogg"), new byte[]{79,103,103,83,0,1,2,3});
        var visible = new AtomicReference<TemplateApi>();
        var connections = new CopyOnWriteArrayList<Connection>();
        var rejectPreparation = new AtomicBoolean(); var rejectStart = new AtomicBoolean();
        WorkshopRuntime.Factory factory = next -> {
            check(Thread.currentThread().getName().equals("clockwork-workshop-config"), "Preparation runs off the caller/tick thread");
            if (rejectPreparation.get()) throw new IOException("simulated unavailable storage");
            var identity = RelayIdentity.load(config);
            var api = new TemplateApi(next.origin().toString(), new AccessSessions(owner -> true), new TemplateFiles(config, new TemplateJson()), identity.id());
            var connection = new Connection(api); connection.failStart = rejectStart.get(); connections.add(connection); return connection;
        };
        try (var runtime = new WorkshopRuntime(file, () -> true, factory, visible::set)) {
            check(reload(runtime).changed(), "Initial config connects");
            Connection first = connections.getFirst(); TemplateApi original = visible.get();
            var login = original.access().redeem(original.access().issue(OWNER)); String link = original.access().issue(OWNER);
            Map<String,String> before = storedFiles(config);
            Files.writeString(file, "# comments and unrelated settings do not reconnect\nenabled= true \ntemplate_access_enabled=true\nrelay_url=" + ORIGIN + "\nold_web_port=27036\n");
            check(!reload(runtime).changed() && visible.get() == original && connections.size() == 1, "Unchanged config preserves API and connection");
            check(original.access().authenticate(login.token()) != null && original.access().redeem(link) != null, "Unchanged reload retains actual session and pending link");
            Files.writeString(file, "enabled=true\ntemplate_access_enabled=invalid\n");
            failure(runtime);
            check(visible.get() == original && !first.closed && original.access().current(login.token()) != null, "Invalid config retains working settings and session");
            Files.delete(file); failure(runtime);
            check(visible.get() == original && !Files.exists(file), "Missing config retains connection without resetting settings");
            settings(file, true, true, "https://example.org"); rejectPreparation.set(true); failure(runtime);
            check(visible.get() == original && !first.closed && original.access().current(login.token()) != null, "Preparation failure preserves old access");
            rejectPreparation.set(false); check(reload(runtime).changed(), "Changed origin replaces connection");
            TemplateApi replacement = visible.get();
            check(first.closed && original.access().current(login.token()) == null, "Changed origin clears old session");
            first.callback.accept(original); first.callback.accept(null);
            check(visible.get() == replacement, "Late old ready/disconnected callbacks cannot overwrite new API");
            var replacementLogin = replacement.access().redeem(replacement.access().issue(OWNER)); String replacementLink = replacement.access().issue(OWNER);
            settings(file, false, true, "not needed while disabled"); check(reload(runtime).changed(), "Relay flag disables runtime");
            check(visible.get() == null && connections.get(1).closed && replacement.access().current(replacementLogin.token()) == null && replacement.access().redeem(replacementLink) == null, "Disable revokes links/sessions");
            settings(file, true, false, "not needed while disabled");
            check(!reload(runtime).changed() && connections.size() == 2, "Inactive flag changes do not start a relay");
            settings(file, true, true, ORIGIN); reload(runtime);
            check(visible.get().workshopUrl().equals(original.workshopUrl()), "Re-enable retains persistent server identity");
            settings(file, true, true, "https://example.org"); rejectStart.set(true);
            check(failure(runtime) instanceof WorkshopRuntime.StartFailed && connections.getLast().closed && visible.get() == null, "Start failure closes candidate and reports revocation");
            rejectStart.set(false); reload(runtime);
            check(visible.get() != null, "Retry after start failure reconnects");
            check(before.equals(storedFiles(config)), "Reload leaves key, active JSON, draft JSON, backups and audio byte-for-byte unchanged");
        }
        check(visible.get() == null && connections.getLast().closed, "Shutdown closes current API");
        settings(file, true, true, ORIGIN);
        try (var offline = new WorkshopRuntime(file, () -> false, factory, visible::set)) {
            int count = connections.size(); check(failure(offline).getMessage().contains("online-mode"), "Offline-mode enabling is refused");
            check(connections.size() == count, "Offline mode never prepares/connects relay");
            settings(file, false, false, ORIGIN); reload(offline);
            settings(file, true, true, ORIGIN); failure(offline);
            check(visible.get() == null, "Offline-mode reload cannot authorize access");
        }
        busyAndStop(config, file);
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (Thread.getAllStackTraces().keySet().stream().anyMatch(t -> t.isAlive() && t.getName().equals("clockwork-workshop-config")) && System.nanoTime() < until) Thread.sleep(10);
        check(Thread.getAllStackTraces().keySet().stream().noneMatch(t -> t.isAlive() && t.getName().equals("clockwork-workshop-config")), "Configuration workers terminate after close");
        System.out.println("PASS: bounded config validation, async one-at-a-time reload, unchanged/invalid session retention, changed-setting revocation, stale callback rejection, offline-mode refusal, exact file preservation, busy/stop race and worker cleanup");
    }
    private static void busyAndStop(Path config, Path file) throws Exception {
        settings(file, true, true, ORIGIN);
        var entered = new CountDownLatch(1); var interrupted = new CountDownLatch(1); var release = new CountDownLatch(1);
        var candidate = new AtomicReference<Connection>(); var visible = new AtomicReference<TemplateApi>();
        var runtime = new WorkshopRuntime(file, () -> true, next -> {
            entered.countDown();
            while (true) { try { release.await(); break; } catch (InterruptedException stopping) { interrupted.countDown(); } }
            var identity = RelayIdentity.load(config);
            var connection = new Connection(new TemplateApi(ORIGIN, new AccessSessions(owner -> true), new TemplateFiles(config, new TemplateJson()), identity.id()));
            candidate.set(connection); return connection;
        }, visible::set);
        var pending = runtime.reload(false); check(entered.await(3, TimeUnit.SECONDS), "Preparation entered");
        for (int i = 0; i < 100; i++) {
            try { runtime.reload(false); throw new AssertionError("Queued concurrent reload"); }
            catch (IOException busy) { check(busy.getMessage().contains("already running"), "Concurrent reload reports busy"); }
        }
        Thread stop = new Thread(runtime::close); stop.start();
        check(interrupted.await(3, TimeUnit.SECONDS), "Shutdown cancels preparation"); release.countDown(); stop.join(3000);
        check(!stop.isAlive() && pending.isCompletedExceptionally(), "Shutdown settles reload without waiting indefinitely");
        check(candidate.get() != null && candidate.get().closed && candidate.get().starts.get() == 0 && visible.get() == null, "Late preparation is closed without starting a relay after stop");
        try { runtime.reload(false); throw new AssertionError("Reload accepted after stop"); }
        catch (IOException expected) { check(expected.getMessage().contains("stopping"), "Stopped runtime refuses reload"); }
    }
}
