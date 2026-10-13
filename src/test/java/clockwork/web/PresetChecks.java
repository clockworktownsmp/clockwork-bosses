package clockwork.web;

import com.google.gson.JsonParser;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Synthetic media checks the storage boundary; real bundled clips are checked
 * separately on the disposable server/browser. Never a production identity issuer. */
public final class PresetChecks {
    private static final String JSON = "{\"format_version\":1,\"health\":1000,\"sounds\":{\"spawn\":{\"file\":\"spawn.ogg\"}},\"addon\":{\"precise\":900719925474099312345,\"tiny\":1.2300e-120}}";
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static byte[] ogg(int length) {
        byte[] bytes = new byte[length]; System.arraycopy("OggS".getBytes(StandardCharsets.US_ASCII), 0, bytes, 0, 4);
        bytes[5] = 2; bytes[26] = 1; bytes[27] = 30; bytes[28] = 1;
        System.arraycopy("vorbis".getBytes(StandardCharsets.US_ASCII), 0, bytes, 29, 6); return bytes;
    }
    static Path preset(Path config, String name) throws IOException {
        Path folder = config.resolve("clockwork-bosses/boss-presets/" + name);
        for (String path : PresetMedia.FOLDERS) Files.createDirectories(folder.resolve(path));
        Files.writeString(folder.resolve("template.json"), JSON); return folder;
    }
    static Path active(Path config, String name) { return config.resolve("clockwork-bosses/boss-templates/" + name); }
    static long stages(Path config) throws IOException { try (var list = Files.list(active(config, ""))) { return list.filter(path -> path.getFileName().toString().startsWith(".workshop-preset-")).count(); } }
    static void rejects(TemplateFiles files, Path config, String name, String source) throws Exception {
        try { var draft = files.save(name, JSON.getBytes(StandardCharsets.UTF_8), "none", "none", source); files.publish(name, draft.revision()); throw new AssertionError("Unsafe media saved/published"); }
        catch (IOException expected) { }
        check(!Files.exists(active(config, name)) && stages(config) == 0, "Failure leaves no visible template or staging files");
    }
    public static void main(String[] args) throws Exception {
        Path config = Files.createTempDirectory(Path.of("build").toAbsolutePath(), "preset-check-");
        var files = new TemplateFiles(config, new TemplateJson());
        Path source = preset(config, "boss-sounds-example"); byte[] sound = ogg(64), voice = ogg(96);
        Files.write(source.resolve("sounds/spawn.ogg"), sound); Files.write(source.resolve("sounds/voice-lines/line1.ogg"), voice);
        byte[] skin = new byte[33]; ByteBuffer.wrap(skin).putLong(0x89504e470d0a1a0aL).putInt(13).putInt(0x49484452).putInt(128).putInt(128);
        Files.write(source.resolve("skins/test.png"), skin); Files.writeString(source.resolve("sounds/private.txt"), "must not be copied");
        var saved = files.save("renamed", JSON.getBytes(StandardCharsets.UTF_8), "none", "none", "boss-sounds-example");
        check(!Files.exists(active(config, "renamed")), "Saving a draft does not create active folders/media");
        var restarted = new TemplateFiles(config, new TemplateJson());
        check(restarted.draft("renamed").preset().equals("boss-sounds-example") && Arrays.equals(restarted.draft("renamed").content(), JSON.getBytes(StandardCharsets.UTF_8)), "Source metadata persists separately from exact JSON across restart");
        String revision = files.publish("renamed", saved.revision()); Path target = active(config, "renamed");
        for (String folder : PresetMedia.FOLDERS) check(Files.isDirectory(target.resolve(folder)), "All standard media folders created");
        check(Arrays.equals(Files.readAllBytes(target.resolve("sounds/spawn.ogg")), sound) && Arrays.equals(Files.readAllBytes(target.resolve("sounds/voice-lines/line1.ogg")), voice) && Arrays.equals(Files.readAllBytes(target.resolve("skins/test.png")), skin), "Preset sound, voice and skin copies are exact");
        check(!Files.exists(target.resolve("sounds/private.txt")), "Unapproved file types stay out of published folder");
        check(Arrays.equals(Files.readAllBytes(target.resolve("template.json")), JSON.getBytes(StandardCharsets.UTF_8)), "Edited template bytes are published without preset JSON substitution");
        check(files.sounds("renamed").sounds().equals(List.of("spawn.ogg")) && files.sounds("renamed").voices().equals(List.of("line1.ogg")), "Copied clips appear in sound lists");
        check(Arrays.equals(files.audio("renamed", "sounds", "spawn.ogg"), sound), "Copied media is readable by authenticated audio endpoint boundary");
        check(files.publish("renamed", saved.revision()).equals(revision) && stages(config) == 0, "Repeat publish is idempotent and staging is cleaned");
        byte[] custom = ogg(80); Files.write(target.resolve("sounds/spawn.ogg"), custom);
        files.sounds("renamed");
        Files.delete(target.resolve("sounds/voice-lines/line1.ogg"));
        check(files.sounds("renamed").voices().contains("line1.ogg"), "Test populated old sound-list cache");
        files.publish("renamed", saved.revision());
        check(Arrays.equals(Files.readAllBytes(target.resolve("sounds/spawn.ogg")), custom) && Arrays.equals(Files.readAllBytes(target.resolve("sounds/voice-lines/line1.ogg")), voice), "Republish repairs missing files while preserving existing custom clips");
        Files.write(source.resolve("sounds/death.ogg"), sound); files.publish("renamed", saved.revision());
        check(files.sounds("renamed").sounds().equals(List.of("spawn.ogg")), "Publishing uses the saved media snapshot rather than later preset edits");
        var edited = files.save("renamed", JSON.replace("1000", "1234").getBytes(StandardCharsets.UTF_8), saved.revision(), revision);
        check(edited.preset().equals("boss-sounds-example"), "Legacy save without metadata retains the existing draft source");
        files.publish("renamed", edited.revision());
        try (var backups = Files.list(config.resolve("clockwork-bosses-web/backups"))) {
            var paths = backups.toList(); check(paths.size() == 1 && Files.readString(paths.getFirst()).equals(JSON), "JSON replacement creates one exact backup");
        }
        files.publish("renamed", edited.revision());
        try (var backups = Files.list(config.resolve("clockwork-bosses-web/backups"))) { check(backups.count() == 1, "Repeat publish creates no duplicate backup"); }
        check(Arrays.equals(Files.readAllBytes(source.resolve("sounds/spawn.ogg")), sound), "Source media is untouched");
        var cleared = files.save("renamed", edited.content(), edited.revision(), files.active("renamed").revision(), "none");
        check(cleared.preset().isEmpty() && !cleared.revision().equals(edited.revision()), "Explicit import/default source clears metadata even when JSON bytes are unchanged");
        Path balance = preset(config, "1_player");
        var balanceDraft = files.save("balance", JSON.getBytes(StandardCharsets.UTF_8), "none", "none", "1_player"); files.publish("balance", balanceDraft.revision());
        check(Files.isDirectory(active(config, "balance").resolve("sounds/voice-lines")) && files.sounds("balance").sounds().isEmpty(), "Audio-free balance presets still create standard folders");
        var ordinary = files.save("ordinary", JSON.getBytes(StandardCharsets.UTF_8), "none", "none"); files.publish("ordinary", ordinary.revision());
        check(Files.isDirectory(active(config, "ordinary").resolve("skins")), "JSON imports/defaults create standard folders too");
        Files.writeString(balance.resolve("sounds/bad.ogg"), "not ogg"); rejects(files, config, "corrupt", "1_player"); Files.delete(balance.resolve("sounds/bad.ogg"));
        Files.write(balance.resolve("sounds/con.ogg"), sound); rejects(files, config, "device", "1_player"); Files.delete(balance.resolve("sounds/con.ogg"));
        Files.write(balance.resolve("skins/wrong.png"), sound); rejects(files, config, "bad_png", "1_player"); Files.delete(balance.resolve("skins/wrong.png"));
        rejects(files, config, "missing", "2_players");
        var revoked = files.save("revoked_commit", JSON.getBytes(StandardCharsets.UTF_8), "none", "none", "boss-sounds-example");
        try { files.publish("revoked_commit", revoked.revision(), () -> { throw new IOException("Access revoked before commit"); }); throw new AssertionError("Revoked publication committed"); }
        catch (IOException expected) { check(!Files.exists(active(config, "revoked_commit")) && stages(config) == 0, "Late access/revision guard aborts before exposing copied template"); }
        for (String bad : List.of("../boss-sounds-example", "/tmp", "file:///private", "not-a-preset")) {
            try { files.save("traversal", JSON.getBytes(StandardCharsets.UTF_8), "none", "none", bad); throw new AssertionError("Arbitrary preset path accepted"); }
            catch (IOException expected) { check(files.draft("traversal").content() == null, "Invalid source creates no draft"); }
        }
        Path tooMany = preset(config, "3_players");
        for (int i = 0; i < 97; i++) Files.write(tooMany.resolve("sounds/clip" + i + ".ogg"), sound);
        rejects(files, config, "too_many", "3_players");
        Path tooBig = preset(config, "4_players"); Files.write(tooBig.resolve("sounds/big.ogg"), ogg(TemplateFiles.AUDIO_MAX_BYTES + 1));
        rejects(files, config, "too_big", "4_players"); Files.delete(tooBig.resolve("sounds/big.ogg"));
        for (int i = 0; i < 5; i++) Files.write(tooBig.resolve("sounds/clip" + i + ".ogg"), ogg(TemplateFiles.AUDIO_MAX_BYTES));
        rejects(files, config, "too_large_total", "4_players");
        rollbackChecks(config, source, sound, voice);
        linkedFolderCheck(config, files);
        apiChecks(config);
        check(stages(config) == 0, "All stages cleaned after success/failure");
        System.out.println("PASS: preset source persistence, complete folder publication, exact sound/voice/skin/JSON bytes, safe missing-file repair, existing media preservation, backup/idempotency, cache refresh, source/path/link/type/count/size rejection, rollback, authenticated API/CSRF and metadata round-trip");
    }
    private static void rollbackChecks(Path config, Path source, byte[] sound, byte[] voice) throws Exception {
        Path root = active(config, ""), presets = config.resolve("clockwork-bosses/boss-presets");
        Path existing = active(config, "existing"); Files.createDirectories(existing.resolve("sounds"));
        Files.writeString(existing.resolve("template.json"), JSON); Files.write(existing.resolve("sounds/spawn.ogg"), voice);
        try (var media = PresetMedia.prepare(root, presets, "boss-sounds-example")) {
            media.installMissing(existing);
            check(Files.exists(existing.resolve("sounds/voice-lines/line1.ogg")), "Missing media installed before JSON commit");
            try { TemplateFiles.atomic(existing.resolve("template.json"), "{}".getBytes(StandardCharsets.UTF_8), true, () -> { throw new TemplateFiles.Conflict("simulated concurrent edit"); }); }
            catch (TemplateFiles.Conflict expected) { }
            // Leaving without commit simulates the same failed JSON/revision path.
        }
        check(Files.readString(existing.resolve("template.json")).equals(JSON) && Arrays.equals(Files.readAllBytes(existing.resolve("sounds/spawn.ogg")), voice), "Failed JSON commit preserves old JSON and custom clip");
        check(!Files.exists(existing.resolve("sounds/death.ogg")) && !Files.exists(existing.resolve("sounds/voice-lines")) && !Files.exists(existing.resolve("skins")), "Failed existing-folder publication rolls back only added media/folders");
        try (var media = PresetMedia.prepare(root, presets, "boss-sounds-example")) { media.installMissing(existing); Thread.currentThread().interrupt(); }
        check(Thread.interrupted() && !Files.exists(existing.resolve("sounds/death.ogg")) && !Files.exists(existing.resolve("sounds/voice-lines")), "Canceled merge rolls back additions and preserves interruption");
        Path concurrent = active(config, "concurrent");
        try (var media = PresetMedia.prepare(root, presets, "boss-sounds-example")) {
            try { media.publishNew(concurrent, JSON.getBytes(StandardCharsets.UTF_8), () -> { Files.createDirectory(concurrent); Files.writeString(concurrent.resolve("owner.txt"), "keep"); }); throw new AssertionError("Concurrent folder replaced"); }
            catch (TemplateFiles.Conflict expected) { }
        }
        check(Files.readString(concurrent.resolve("owner.txt")).equals("keep") && !Files.exists(concurrent.resolve("template.json")), "Concurrent destination is never replaced");
    }
    private static void linkedFolderCheck(Path config, TemplateFiles files) throws Exception {
        Path link = config.resolve("clockwork-bosses/boss-presets/5_players");
        Path outside = Files.createTempDirectory(config.getParent(), "preset-outside-"); Files.writeString(outside.resolve("template.json"), JSON);
        if (System.getProperty("os.name").startsWith("Windows")) {
            Process child = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", "$ErrorActionPreference='Stop'; New-Item -ItemType Junction -Path '" + link.toString().replace("'", "''") + "' -Target '" + outside.toString().replace("'", "''") + "' | Out-Null").redirectErrorStream(true).start();
            child.getInputStream().readAllBytes(); check(child.waitFor() == 0, "Test junction created");
        } else Files.createSymbolicLink(link, outside);
        try { rejects(files, config, "linked", "5_players"); check(Files.readString(outside.resolve("template.json")).equals(JSON), "Linked source cannot modify external folder"); }
        finally { Files.delete(link); }
    }
    private static void apiChecks(Path config) throws Exception {
        var denyAtCommit = new java.util.concurrent.atomic.AtomicBoolean(); var checks = new java.util.concurrent.atomic.AtomicInteger();
        var access = new AccessSessions(owner -> !denyAtCommit.get() || checks.incrementAndGet() < 3); var files = new TemplateFiles(config, new TemplateJson());
        int port; try (var reserved = new java.net.ServerSocket(0, 1, InetAddress.getLoopbackAddress())) { port = reserved.getLocalPort(); }
        String origin = "http://127.0.0.1:" + port;
        try (var server = new TestApiServer("127.0.0.1", port, new TemplateApi(origin, access, files, TestApiServer.ID)); var client = HttpClient.newHttpClient()) {
            var owner = new AccessSessions.Owner(UUID.randomUUID(), "preset_owner"); var login = access.redeem(access.issue(owner));
            Map<String,String> writes = new HashMap<>(Map.of("Cookie", TestApiServer.COOKIE + login.token(), "Origin", origin, "Content-Type", "application/json", "X-Workshop-CSRF", login.session().csrf(), "If-Match", "\"none\"", "X-Active-Revision", "none", "X-Workshop-Preset", "boss-sounds-example"));
            var anonymous = new HashMap<>(writes); anonymous.remove("Cookie");
            check(http(client, origin, "PUT", "/api/drafts/api_preset", JSON, anonymous).statusCode() == 401, "Anonymous media draft denied");
            var noCsrf = new HashMap<>(writes); noCsrf.remove("X-Workshop-CSRF");
            check(http(client, origin, "PUT", "/api/drafts/api_preset", JSON, noCsrf).statusCode() == 403, "Preset draft still requires CSRF");
            var status = http(client, origin, "GET", "/api/status", null, Map.of());
            check(JsonParser.parseString(new String(status.body(), StandardCharsets.UTF_8)).getAsJsonObject().get("preset_media").getAsBoolean(), "API advertises media support for browser compatibility");
            var saved = http(client, origin, "PUT", "/api/drafts/api_preset", JSON, writes); check(saved.statusCode() == 200, "Authenticated source save");
            String revision = JsonParser.parseString(new String(saved.body(), StandardCharsets.UTF_8)).getAsJsonObject().get("revision").getAsString();
            var loaded = http(client, origin, "GET", "/api/drafts/api_preset", null, Map.of("Cookie", TestApiServer.COOKIE + login.token()));
            check(loaded.headers().firstValue("X-Workshop-Preset").orElseThrow().equals("boss-sounds-example") && Arrays.equals(loaded.body(), JSON.getBytes(StandardCharsets.UTF_8)), "Loaded draft returns source metadata separately from exact JSON");
            var publish = Map.of("Cookie", TestApiServer.COOKIE + login.token(), "Origin", origin, "Content-Type", "application/json", "X-Workshop-CSRF", login.session().csrf(), "If-Match", "\"" + revision + "\"");
            check(http(client, origin, "POST", "/api/publish/api_preset", "{}", publish).statusCode() == 200 && Files.exists(active(config, "api_preset").resolve("sounds/voice-lines/line1.ogg")), "Authenticated publish materializes preset media");
            writes.put("X-Workshop-Preset", "../private");
            check(http(client, origin, "PUT", "/api/drafts/bad_api_source", JSON, writes).statusCode() == 400 && files.draft("bad_api_source").content() == null, "API rejects path-valued metadata before writing");
            writes.put("X-Workshop-Preset", "boss-sounds-example");
            var revokedDraft = http(client, origin, "PUT", "/api/drafts/api_revoked", JSON, writes);
            String revokedRevision = JsonParser.parseString(new String(revokedDraft.body(), StandardCharsets.UTF_8)).getAsJsonObject().get("revision").getAsString();
            var revokedPublish = new HashMap<>(publish); revokedPublish.put("If-Match", "\"" + revokedRevision + "\"");
            checks.set(0); denyAtCommit.set(true);
            check(http(client, origin, "POST", "/api/publish/api_revoked", "{}", revokedPublish).statusCode() == 401 && !Files.exists(active(config, "api_revoked")) && stages(config) == 0, "Owner revocation after media staging returns 401 and exposes no active template");
        }
    }
    private static HttpResponse<byte[]> http(HttpClient client, String origin, String method, String path, String body, Map<String,String> headers) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(origin + path)).timeout(java.time.Duration.ofSeconds(10)); headers.forEach(request::header);
        return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofByteArray());
    }
}
