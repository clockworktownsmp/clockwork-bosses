package clockwork.web;

import com.google.gson.JsonParser;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;

/** Snapshot transactions and source-aware API checks against disposable storage. */
public final class DraftMediaChecks {
    private static final byte[] JSON = "{\"format_version\":1,\"health\":1000,\"addon\":{\"exact\":900719925474099312345,\"tiny\":1.2300e-120}}".getBytes(StandardCharsets.UTF_8);
    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    private static HttpResponse<byte[]> http(HttpClient client, String origin, String method, String path, byte[] body, Map<String,String> headers) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(origin + path)).timeout(java.time.Duration.ofSeconds(10)); headers.forEach(builder::header);
        return client.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofByteArray());
    }
    public static void main(String[] args) throws Exception {
        Path config = Files.createTempDirectory(Path.of("build").toAbsolutePath(), "draft-media-check-");
        var files = new TemplateFiles(config, new TemplateJson());
        Path preset = PresetChecks.preset(config, "boss-sounds-example");
        byte[] original = PresetChecks.ogg(64), changed = PresetChecks.ogg(96);
        Files.write(preset.resolve("sounds/spawn.ogg"), original); Files.write(preset.resolve("sounds/voice-lines/line.ogg"), original);
        check(files.sounds("presets", "boss-sounds-example").sounds().equals(List.of("spawn.ogg")), "Presets are listed before saving");
        var draft = files.save("new_name", JSON, "none", "none", "boss-sounds-example");
        Path bundle = files.bundle("new_name", draft.revision());
        for (String relative : PresetMedia.FOLDERS) check(Files.isDirectory(bundle.resolve(relative)), "Draft creates each media folder");
        check(Arrays.equals(Files.readAllBytes(bundle.resolve("template.json")), JSON), "Draft bundle stores exact JSON including addon number tokens");
        check(Arrays.equals(Files.readAllBytes(bundle.resolve("sounds/spawn.ogg")), original), "Draft copies sound bytes before publishing");
        check(files.active("new_name").content() == null, "Draft save does not affect active gameplay");
        Files.write(preset.resolve("sounds/spawn.ogg"), changed); Files.delete(preset.resolve("sounds/voice-lines/line.ogg"));
        var restarted = new TemplateFiles(config, new TemplateJson());
        check(Arrays.equals(restarted.audio("drafts", "new_name", "sounds", "spawn.ogg"), original), "Draft playback survives restart independently of preset edits");
        check(Arrays.equals(restarted.audio("presets", "boss-sounds-example", "sounds", "spawn.ogg"), changed), "Preset and draft audio do not alias");
        restarted.publish("new_name", draft.revision());
        check(Arrays.equals(restarted.audio("new_name", "sounds", "spawn.ogg"), original) && restarted.sounds("new_name").voices().equals(List.of("line.ogg")), "Publish uses saved snapshot even when source files change/disappear");
        Files.write(PresetChecks.active(config, "new_name").resolve("sounds/spawn.ogg"), changed);
        check(Arrays.equals(restarted.audio("drafts", "new_name", "sounds", "spawn.ogg"), original), "Active file edits cannot change saved draft playback");
        var activeDraft = restarted.save("new_name", JSON, draft.revision(), restarted.active("new_name").revision(), "none");
        check(Arrays.equals(restarted.audio("drafts", "new_name", "sounds", "spawn.ogg"), changed), "Cleared provenance snapshots active media");
        for (int i = 0; i < 8; i++) {
            byte[] edits = new String(JSON, StandardCharsets.UTF_8).replace("1000", Integer.toString(2000 + i)).getBytes(StandardCharsets.UTF_8);
            activeDraft = restarted.save("new_name", edits, activeDraft.revision(), restarted.active("new_name").revision());
        }
        try (var revisions = Files.list(bundle.getParent())) { check(revisions.count() == 2, "Only current and previous draft bundles retained"); }
        var before = restarted.draft("new_name"); var checks = new AtomicInteger();
        try { restarted.save("new_name", JSON, before.revision(), restarted.active("new_name").revision(), "none", () -> {if (checks.incrementAndGet() == 2) throw new IOException("simulated failure after bundle move");}); throw new AssertionError("Failed manifest commit accepted"); }
        catch (IOException expected) { check(restarted.draft("new_name").revision().equals(before.revision()), "Manifest failure keeps previous reviewed draft"); }
        try (var revisions = Files.list(bundle.getParent())) { check(revisions.count() == 1, "Failed new bundle rolls back; old current snapshot retained"); }
        try { restarted.save("never_saved", JSON, "none", "none", "boss-sounds-example", () -> {throw new IOException("revoked");}); throw new AssertionError("Revoked save accepted"); }
        catch (IOException expected) { check(restarted.draft("never_saved").content() == null && !Files.exists(config.resolve("clockwork-bosses-web/drafts/never_saved")), "Revoked save leaves no draft bundle or parent"); }
        Properties legacy = new Properties(); legacy.setProperty("base_revision", "none"); legacy.setProperty("preset", "boss-sounds-example"); legacy.setProperty("content", Base64.getEncoder().encodeToString(JSON));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); legacy.store(bytes, "legacy draft");
        Files.write(config.resolve("clockwork-bosses-web/drafts/legacy.draft"), bytes.toByteArray());
        var old = restarted.draft("legacy"); check(!old.mediaBundle(), "Legacy JSON drafts remain readable");
        var migrated = restarted.save("legacy", JSON, old.revision(), "none", "boss-sounds-example");
        check(migrated.mediaBundle() && !migrated.revision().equals(old.revision()) && Arrays.equals(restarted.audio("drafts", "legacy", "sounds", "spawn.ogg"), changed), "Saving legacy draft upgrades it to a complete snapshot");
        apiChecks(config, restarted, changed);
        try (var paths = Files.walk(config)) { check(paths.noneMatch(path -> path.getFileName().toString().startsWith(".workshop-preset-")), "No private staging survives commits/failures"); }
        System.out.println("PASS: preset/draft/active source separation, complete draft snapshots, byte-exact JSON/audio, restart/source-edit independence, manifest rollback, revocation, bounded retention, legacy upgrade, authenticated GET/HEAD/CSRF/traversal/read-only APIs");
    }
    private static void apiChecks(Path config, TemplateFiles files, byte[] changed) throws Exception {
        var revoke = new AtomicBoolean(); var checks = new AtomicInteger();
        var access = new AccessSessions(owner -> !revoke.get() || checks.incrementAndGet() < 3);
        int port; try (var reserved = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) { port = reserved.getLocalPort(); }
        String origin = "http://127.0.0.1:" + port;
        try (var server = new TestApiServer("127.0.0.1", port, new TemplateApi(origin, access, files, TestApiServer.ID)); var client = HttpClient.newHttpClient()) {
            var login = access.redeem(access.issue(new AccessSessions.Owner(UUID.randomUUID(), "draft_media_owner")));
            var cookie = Map.of("Cookie", TestApiServer.COOKIE + login.token());
            for (String path : List.of("/api/sounds/presets/boss-sounds-example", "/api/sounds/drafts/legacy", "/api/audio/presets/boss-sounds-example/sounds/spawn.ogg", "/api/audio/drafts/legacy/sounds/spawn.ogg")) check(http(client, origin, "GET", path, null, Map.of()).statusCode() == 401, "All sources require owner login");
            for (String source : List.of("presets/boss-sounds-example", "drafts/legacy", "active/new_name")) {
                var list = http(client, origin, "GET", "/api/sounds/" + source, null, cookie); check(list.statusCode() == 200 && JsonParser.parseString(new String(list.body(), StandardCharsets.UTF_8)).getAsJsonObject().get("source").getAsString().equals(source.split("/")[0]), "Typed list returns requested source");
                var audio = http(client, origin, "GET", "/api/audio/" + source + "/sounds/spawn.ogg", null, cookie); check(audio.statusCode() == 200 && Arrays.equals(audio.body(), changed), "Typed audio exact bytes");
                var head = http(client, origin, "HEAD", "/api/audio/" + source + "/sounds/spawn.ogg", null, cookie); check(head.statusCode() == 200 && head.body().length == 0 && head.headers().firstValue("Content-Length").orElse("").equals(Integer.toString(changed.length)), "Typed HEAD is bodyless with exact length");
                check(http(client, origin, "GET", "/api/audio/" + source + "/sounds/missing.ogg", null, cookie).statusCode() == 404, "Missing files handled for each source");
                var writes = new HashMap<>(cookie); writes.put("Origin", origin); writes.put("X-Workshop-CSRF", login.session().csrf());
                for (String method : List.of("POST", "PUT", "DELETE")) check(http(client, origin, method, "/api/audio/" + source + "/sounds/spawn.ogg", null, writes).statusCode() == 405, "Source audio API is read-only");
            }
            for (String bad : List.of("/api/audio/presets/private/sounds/spawn.ogg", "/api/audio/drafts/%2e%2e/sounds/spawn.ogg", "/api/audio/drafts/legacy/sounds/%2e%2e%2fspawn.ogg", "/api/audio/presets/boss-sounds-example/sounds/con.ogg")) check(http(client, origin, "GET", bad, null, cookie).statusCode() == 400, "Path and source escapes denied");
            check(http(client, origin, "GET", "/api/audio/drafts/legacy/sounds/spawn.ogg", null, Map.of("Cookie", TestApiServer.COOKIE + login.token(), "Origin", "https://evil.example")).statusCode() == 403, "Cross-origin draft playback denied");
            Path sounds = files.bundle("legacy", files.draft("legacy").revision()).resolve("sounds"), retained = sounds.resolveSibling("saved-sounds");
            Files.move(sounds, retained); AudioChecks.directoryLink(sounds, retained);
            try {
                for (String path : List.of("/api/sounds/drafts/legacy", "/api/audio/drafts/legacy/sounds/spawn.ogg")) {
                    var denied = http(client, origin, "GET", path, null, cookie); check(denied.statusCode() == 400 && !new String(denied.body(), StandardCharsets.UTF_8).contains(config.toString()), "Draft folder junction is denied without leaking private paths");
                }
            } finally { Files.delete(sounds); Files.move(retained, sounds); }
            Path linkedPreset = config.resolve("clockwork-bosses/boss-presets/1_player");
            AudioChecks.directoryLink(linkedPreset, files.bundle("legacy", files.draft("legacy").revision()));
            try {
                for (String path : List.of("/api/sounds/presets/1_player", "/api/audio/presets/1_player/sounds/spawn.ogg")) check(http(client, origin, "GET", path, null, cookie).statusCode() == 400, "Preset junction escapes are denied for listing and playback");
            } finally { Files.delete(linkedPreset); }
            var writes = new HashMap<>(cookie); writes.putAll(Map.of("Origin", origin, "Content-Type", "application/json", "X-Workshop-CSRF", login.session().csrf(), "If-Match", "\"none\"", "X-Active-Revision", "none", "X-Workshop-Preset", "boss-sounds-example"));
            var noCsrf = new HashMap<>(writes); noCsrf.remove("X-Workshop-CSRF"); check(http(client, origin, "PUT", "/api/drafts/csrf_denied", JSON, noCsrf).statusCode() == 403, "Copying draft media requires CSRF");
            revoke.set(true); checks.set(0);
            check(http(client, origin, "PUT", "/api/drafts/revoked_save", JSON, writes).statusCode() == 401 && files.draft("revoked_save").content() == null && !Files.exists(config.resolve("clockwork-bosses-web/drafts/revoked_save")), "Revocation during staging aborts save before commit");
        }
    }
}
