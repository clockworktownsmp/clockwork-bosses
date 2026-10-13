package clockwork.web;

import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Packaging/adapter contracts independent of a running server. Native command
 * and entity behavior are checked separately by the disposable GameTest mod. */
public final class WorkshopAdapterChecks {
    private static int assertions;
    static void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        try (var input = WorkshopAdapterChecks.class.getResourceAsStream("/fabric.mod.json")) {
            var metadata = JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            check(metadata.get("id").getAsString().equals("clockwork_bosses"), "One retained core mod ID");
            check(metadata.get("version").getAsString().matches("\\d+\\.\\d+\\.\\d+(?:-[0-9A-Za-z.-]+)?"), "Semantic version format");
            var entrypoints = metadata.getAsJsonObject("entrypoints");
            check(entrypoints.getAsJsonArray("server").size() == 1
                    && entrypoints.getAsJsonArray("server").get(0).getAsString().equals(ClockworkBossWorkshop.class.getName()), "Dedicated-server module only");
            check(!entrypoints.getAsJsonArray("main").toString().contains("clockwork.web")
                    && !entrypoints.getAsJsonArray("client").toString().contains("clockwork.web"), "No workshop client/common initialization");
            check(metadata.getAsJsonObject("breaks").get(ClockworkBossWorkshop.LEGACY_MOD_ID).getAsString().equals("*"), "Loader rejects old companion before either initializer");
            check(!metadata.has("provides"), "No obsolete mod identity alias");
        }
        ClockworkBossWorkshop.rejectLegacy(false);
        try { ClockworkBossWorkshop.rejectLegacy(true); throw new AssertionError("Old companion accepted"); }
        catch (IllegalStateException expected) {
            check(expected.getMessage().contains("Remove the old") && expected.getMessage().contains("keep its config/data"), "Clear non-destructive upgrade guidance");
        }
        // Reproduce the race between a native save and web publication. The
        // publisher must wait outside Minecraft, then notice the new revision.
        Path config = Files.createTempDirectory(Path.of("build").toAbsolutePath(), "merged-writers-");
        Object lock = new Object(); var files = new TemplateFiles(config, new TemplateJson(), lock);
        var first = files.save("race", "{\"format_version\":1,\"health\":1000}".getBytes(StandardCharsets.UTF_8), "none", "none");
        String active = files.publish("race", first.revision());
        var edited = files.save("race", "{\"format_version\":1,\"health\":2000}".getBytes(StandardCharsets.UTF_8), first.revision(), active);
        try (var worker = Executors.newSingleThreadExecutor()) {
            java.util.concurrent.Future<String> pending;
            synchronized (lock) {
                pending = worker.submit(() -> files.publish("race", edited.revision()));
                // A latch in the native writer holds the publication gate. The
                // file remains the first revision until the native edit wins.
                check(!pending.isDone(), "Publication is asynchronous while another writer owns the lock");
                Files.writeString(config.resolve("clockwork-bosses/boss-templates/race/template.json"), "{\"format_version\":1,\"health\":3000}");
            }
            try { pending.get(5, TimeUnit.SECONDS); throw new AssertionError("Stale draft overwrote native edit"); }
            catch (java.util.concurrent.ExecutionException expected) { check(expected.getCause() instanceof TemplateFiles.Conflict, "Revision checked after shared gate acquired"); }
        }
        check(new String(files.active("race").content(), StandardCharsets.UTF_8).contains("3000"), "Native winner preserved");
        var owner = new AccessSessions.Owner(java.util.UUID.randomUUID(), "outage_owner");
        var api = new TemplateApi("https://example.invalid", new AccessSessions(who -> true), files, "a".repeat(64));
        try {
            var adapter = new ClockworkBossWorkshop();
            var retained = ClockworkBossWorkshop.class.getDeclaredField("accessApi"); retained.setAccessible(true); retained.set(adapter, api);
            // api (transport readiness) remains null, reproducing a disconnect.
            var session = api.access().redeem(api.access().issue(owner)); String link = api.access().issue(owner);
            adapter.revoke(owner.id());
            check(api.access().authenticate(session.token()) == null && api.access().redeem(link) == null,
                    "Command logout revokes retained session and unused link during a transport outage");
        } finally { api.close(); }
        System.out.println("PASS: merged metadata, legacy guard and shared-writer conflict checks (" + assertions + " assertions)");
    }
}
