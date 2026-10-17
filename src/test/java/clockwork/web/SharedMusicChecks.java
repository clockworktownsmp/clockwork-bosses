package clockwork.web;

import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;

/** Real supplied tracks through the production bounded decoder, plus isolated
 * filesystem/HTTP contracts. This is not Minecraft/client-audio acceptance. */
public final class SharedMusicChecks {
    private static int assertions;
    private static void check(boolean value, String why) { assertions++; if (!value) throw new AssertionError(why); }
    @FunctionalInterface interface Attempt { void run() throws Exception; }
    private static void refused(Attempt operation, String why) throws Exception {
        try { operation.run(); throw new AssertionError(why); } catch (IOException expected) { assertions++; }
    }
    private static JsonObject track(JsonObject list, String name) {
        for (var item : list.getAsJsonArray("tracks")) if (item.getAsJsonObject().get("filename").getAsString().equals(name)) return item.getAsJsonObject();
        throw new AssertionError("Missing listing entry " + name);
    }
    private static String string(JsonObject object, String name) { return object.get(name).getAsString(); }
    private static JsonObject put(SharedMusicLibrary library, String name, byte[] bytes, boolean overwrite) throws Exception {
        var list = library.listing(); String revision = "none";
        for (var item : list.getAsJsonArray("tracks")) if (string(item.getAsJsonObject(), "filename").equals(name)) revision = string(item.getAsJsonObject(), "revision");
        return library.put(name, bytes, string(list, "revision"), revision, string(list, "impact_revision"), overwrite, () -> {});
    }
    public static void main(String[] args) throws Exception {
        Path report = Path.of(args[0]); Files.createDirectories(report.getParent());
        Path config = Files.createTempDirectory(report.getParent(), "shared-music-");
        TemplateFiles files = new TemplateFiles(config, new TemplateJson()); var library = files.musicLibrary();
        check(library.seedBundled() == 2, "both supplied tracks seeded only once");
        JsonArray supplied = new JsonArray();
        for (String name : List.of("boss1.ogg", "boss2.ogg")) {
            byte[] bytes = library.readValidated(name); var details = UploadedAudio.validate(bytes, true);
            String hash = AccessSessions.digest(bytes), expected = name.equals("boss1.ogg")
                    ? "0073610006a2dc0914469e4460f53c47bce8576d53198dd98997e8f3d7609a07"
                    : "1f498c058329537ba455e3ac6d7bc0b82b3c55f9630252d4a4c6007024d78dc6";
            check(hash.equals(expected), "actual supplied file hash"); check(details.channels() == 2 && details.rate() == 44100, "actual supplied stereo Vorbis sample rate");
            check(Math.abs(details.seconds() - (name.equals("boss1.ogg") ? 121.010 : 136.880)) < .01, "actual bounded full decode duration");
            JsonObject item = new JsonObject(); item.addProperty("filename", name); item.addProperty("sha256", hash);
            item.addProperty("bytes", bytes.length); item.addProperty("channels", details.channels()); item.addProperty("sample_rate", details.rate()); item.addProperty("duration_seconds", details.seconds()); supplied.add(item);
        }
        check(library.seedBundled() == 0, "existing bundled files preserved");
        Path root = config.resolve("clockwork-bosses/music"); byte[] admin = {1, 2, 3}; Files.write(root.resolve("boss1.ogg"), admin);
        check(library.seedBundled() == 0 && Arrays.equals(Files.readAllBytes(root.resolve("boss1.ogg")), admin), "administrator-edited bundled file is not overwritten");
        var corrupt = library.listing(); check(!track(corrupt, "boss1.ogg").get("available").getAsBoolean(), "corrupt existing file remains visible with diagnostic");
        check(!string(track(corrupt, "boss1.ogg"), "revision").equals("none"), "corrupt existing file retains its actual revision");
        byte[] small = SoundUploadChecks.silence(), variant = SoundUploadChecks.variant(small);
        refused(() -> put(library, "boss1.ogg", small, false), "corrupt replacement requires explicit confirmation");
        var replaced = put(library, "boss1.ogg", small, true); check(string(replaced, "action").equals("replaced") && replaced.get("backup_created").getAsBoolean(), "corrupt replacement creates required raw backup");
        try (var backups = Files.list(config.resolve("clockwork-bosses/music-backups"))) {
            check(backups.anyMatch(p -> { try { return Arrays.equals(Files.readAllBytes(p), admin); } catch (IOException failure) { return false; } }), "backup contains byte-exact administrator file");
        }
        for (String bad : List.of("../boss.ogg", "Boss.ogg", "a/b.ogg", "C:boss.ogg", "con.ogg", "aux.track.ogg", "bad.opus", "bad ogg.ogg"))
            refused(() -> library.readValidated(bad), "unsafe music filename rejected");
        refused(() -> library.readValidated("missing.ogg"), "missing shared file never falls back");
        refused(() -> put(library, "invalid.ogg", new byte[]{1, 2, 3}, false), "malformed audio not published");
        refused(() -> put(library, "large.ogg", new byte[UploadedAudio.MUSIC_MAX_BYTES + 1], false), "oversized music not published");
        Path overRate = root.resolve("over_rate.ogg"); Files.write(overRate, sampleRate(small, 192000));
        var invalidRate = track(library.listing(), "over_rate.ogg");
        check(!invalidRate.get("available").getAsBoolean() && invalidRate.get("sample_rate").isJsonNull()
                && invalidRate.get("channels").isJsonNull() && !string(invalidRate, "revision").equals("none"), "administrator track metadata honors actual96k limit while preserving replacement revision");
        refused(() -> library.readValidated("over_rate.ogg"), "full shared decoder rejects192k track"); Files.delete(overRate);
        Path unrelated = root.resolve(".workshop-unrelated.tmp"); Files.write(unrelated, small);
        refused(library::listing, "unrelated staging-looking files are never hidden by the library scan"); Files.delete(unrelated);

        byte[] extreme = "{\"format_version\":1,\"health\":1000,\"music\":{\"source\":\"library\",\"file\":\"absent.ogg\"},\"addon\":{\"raw\":900719925474099312345,\"tiny\":1.2300e-120,\"extreme\":1e-2000000000}}".getBytes(StandardCharsets.UTF_8);
        refused(() -> files.save("legacy_extreme", extreme, "none", "none", "none"), "0.2.1 web exponent inspection policy remains unchanged");
        check(files.draft("legacy_extreme").revision().equals("none"), "legacy web refusal writes no draft");
        var nativeDocument = clockwork.bosses.ClockworkBossTemplateFormat.read(new java.io.StringReader(new String(extreme, StandardCharsets.UTF_8)));
        check(clockwork.bosses.ClockworkBossTemplateFormat.writeJson(nativeDocument).contains("1e-2000000000"), "native template path retains extreme opaque number");
        Path nativeFile = config.resolve("clockwork-bosses/boss-templates/native_extreme/template.json");
        Files.createDirectories(nativeFile.getParent()); Files.write(nativeFile, extreme);
        check(track(library.listing(), "absent.ogg").getAsJsonObject("references").get("count").getAsInt() == 1,
                "complete library impact scan accepts an existing native document with opaque extreme numbers");
        check(Arrays.equals(Files.readAllBytes(nativeFile), extreme), "library reference scan leaves native bytes untouched");
        Files.delete(nativeFile); Files.delete(nativeFile.getParent());
        byte[] reference = new String(extreme, StandardCharsets.UTF_8).replace("1e-2000000000", "1.2345e-120").getBytes(StandardCharsets.UTF_8);
        var draft = files.save("one", reference, "none", "none", "none");
        check(Arrays.equals(files.draft("one").content(), reference), "draft retains missing reference and opaque numeric tokens");
        refused(() -> files.publish("one", draft.revision()), "publish requires actual shared dependency");
        var missing = library.listing(); check(string(track(missing, "absent.ogg"), "revision").equals("none") && !track(missing, "absent.ogg").get("available").getAsBoolean(), "referenced missing track has explicit listing entry");
        var created = put(library, "absent.ogg", small, false); check(string(created, "action").equals("created") && created.getAsJsonObject("references").get("count").getAsInt() == 1, "missing referenced creation binds real impact");
        files.publish("one", draft.revision());
        check(!Files.exists(config.resolve("clockwork-bosses/boss-templates/one/sounds/absent.ogg")), "publish does not duplicate shared track in template storage");
        var used = library.listing(); check(track(used, "absent.ogg").getAsJsonObject("references").get("count").getAsInt() == 2, "active and saved draft are distinct impact references");
        refused(() -> library.delete("absent.ogg", string(used, "revision"), string(track(used, "absent.ogg"), "revision"), string(used, "impact_revision"), () -> {}), "in-use deletion refused");
        library.runningReferences(() -> Map.of("absent.ogg", List.of("one:" + new UUID(0, 1))));
        refused(() -> library.put("absent.ogg", variant, string(used, "revision"), string(track(used, "absent.ogg"), "revision"), string(used, "impact_revision"), true, () -> {}), "new loaded reference invalidates prior confirmation");
        check(track(library.listing(), "absent.ogg").getAsJsonObject("references").get("count").getAsInt() == 3, "loaded reference added on top of saved references");
        library.runningReferences(Map::of);
        var fresh = library.listing(); AtomicInteger captured = new AtomicInteger();
        library.runningReferences(() -> { if (captured.incrementAndGet() > 2) throw new IOException("after-commit snapshot unavailable"); return Map.of(); });
        library.put("absent.ogg", variant, string(fresh, "revision"), string(track(fresh, "absent.ogg"), "revision"), string(fresh, "impact_revision"), true, () -> {});
        check(captured.get() == 2, "receipt is prepared before commit; no fallible post-commit scan"); library.runningReferences(Map::of);
        var revoked = library.listing(); AtomicInteger checks = new AtomicInteger();
        refused(() -> library.put("absent.ogg", small, string(revoked, "revision"), string(track(revoked, "absent.ogg"), "revision"), string(revoked, "impact_revision"), true,
                () -> { if (checks.incrementAndGet() == 2) throw new IOException("owner revoked"); }), "owner revocation immediately before atomic publication");
        check(Arrays.equals(library.readValidated("absent.ogg"), variant), "revoked publication preserves current track");
        try {
            Files.createSymbolicLink(root.resolve("linked.ogg"), root.resolve("absent.ogg"));
            refused(() -> library.readValidated("linked.ogg"), "symbolic leaf rejected"); Files.delete(root.resolve("linked.ogg"));
        } catch (FileSystemException unavailable) { System.out.println("NOT_TESTED: music file symlink creation unavailable on this OS/account"); }
        var unused = library.listing(); library.delete("boss2.ogg", string(unused, "revision"), string(track(unused, "boss2.ogg"), "revision"), string(unused, "impact_revision"), () -> {});
        check(!Files.exists(root.resolve("boss2.ogg")) && library.seedBundled() == 1, "unreferenced built-in delete is restored only on later initialization");
        boundsAndConflicts(report.getParent(), small);
        http(files, library, small);
        JsonObject result = new JsonObject(); result.addProperty("status", "PASS"); result.addProperty("assertions", assertions); result.add("supplied_tracks_full_decode", supplied);
        result.addProperty("fixture", config.toString()); result.addProperty("minecraft_audio_acceptance", "NOT_TESTED_BY_THIS_FIXTURE"); result.addProperty("http_closed", true);
        Files.writeString(report, result.toString() + "\n"); System.out.println("PASS: " + assertions + " shared-library storage/actual-track/HTTP assertions");
    }

    private static byte[] sampleRate(byte[] original, int rate) {
        byte[] bytes = original.clone(); int segments = bytes[26] & 255, header = 27 + segments, length = header;
        for (int i = 0; i < segments; i++) length += bytes[27 + i] & 255;
        var buffer = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN); buffer.putInt(header + 12, rate).putInt(22, 0);
        int crc = 0; for (int i = 0; i < length; i++) { crc ^= (bytes[i] & 255) << 24; for (int j = 0; j < 8; j++) crc = (crc << 1) ^ ((crc < 0) ? 0x04c11db7 : 0); }
        buffer.putInt(22, crc); return bytes;
    }

    private static void boundsAndConflicts(Path parent, byte[] audio) throws Exception {
        Path config = Files.createTempDirectory(parent, "shared-music-bounds-"); var library = new SharedMusicLibrary(config, new Object());
        Path music = config.resolve("clockwork-bosses/music"); Files.createDirectories(music);
        for (int i = 0; i < 64; i++) Files.write(music.resolve("track" + i + ".ogg"), new byte[]{1});
        check(library.listing().get("count").getAsInt() == 64, "64 existing tracks are listed independently of validity");
        refused(() -> put(library, "extra.ogg", audio, false), "65th library track refused");
        // Missing references are not filesystem tracks. Exercise the full public
        // contract bound, including stored drafts and loaded-instance metadata.
        int index = 0;
        for (String source : List.of("active", "drafts", "presets")) for (int i = 0; i < 256; i++) {
            String name = "t" + i, document = "{\"format_version\":1,\"music\":{\"source\":\"library\",\"file\":\"missing" + index++ + ".ogg\"}}";
            if (source.equals("drafts")) {
                Path folder = config.resolve("clockwork-bosses-web/drafts"); Files.createDirectories(folder);
                Properties props = new Properties(); props.setProperty("base_revision", "none"); props.setProperty("content", Base64.getEncoder().encodeToString(document.getBytes(StandardCharsets.UTF_8)));
                try (var out = Files.newOutputStream(folder.resolve(name + ".draft"))) { props.store(out, null); }
            } else {
                Path folder = config.resolve("clockwork-bosses/" + (source.equals("active") ? "boss-templates" : "boss-presets") + '/' + name);
                Files.createDirectories(folder); Files.writeString(folder.resolve("template.json"), document);
            }
        }
        Map<String, List<String>> running = new TreeMap<>();
        for (int i = 0; i < 256; i++) running.put("missing" + index++ + ".ogg", List.of("loaded:" + i));
        library.runningReferences(() -> Map.copyOf(running)); var maximum = library.listing();
        check(maximum.get("count").getAsInt() == 64 && maximum.getAsJsonArray("tracks").size() == 1088, "all 1024 missing references plus64tracks fit declared listing bound");
        running.put("excess.ogg", List.of("loaded:257")); refused(library::listing, "incomplete loaded-reference scan fails closed"); running.remove("excess.ogg");
        Path extra = config.resolve("clockwork-bosses/boss-templates/overflow"); Files.createDirectories(extra); Files.writeString(extra.resolve("template.json"), "{\"format_version\":1}");
        refused(library::listing, "257th active reference file fails closed rather than truncated success");

        Path quotaConfig = Files.createTempDirectory(parent, "shared-music-quota-"); var quotaLibrary = new SharedMusicLibrary(quotaConfig, new Object());
        var empty = quotaLibrary.listing(); Path quota = quotaConfig.resolve("clockwork-bosses/boss-templates/quota.bin"); Files.createDirectories(quota.getParent());
        try (var file = new RandomAccessFile(quota.toFile(), "rw")) { file.setLength(MediaQuota.MAX_STORAGE); }
        refused(() -> quotaLibrary.put("track.ogg", audio, string(empty, "revision"), "none", string(empty, "impact_revision"), false, () -> {}), "global managed quota includes library publication");
        check(!Files.exists(quotaConfig.resolve("clockwork-bosses/music/track.ogg")), "quota failure makes no track"); Files.delete(quota);
        put(quotaLibrary, "track.ogg", audio, false); var before = quotaLibrary.listing();
        AtomicInteger authorization = new AtomicInteger();
        refused(() -> quotaLibrary.put("track.ogg", SoundUploadChecks.variant(audio), string(before, "revision"), string(track(before, "track.ogg"), "revision"), string(before, "impact_revision"), true, () -> {
            if (authorization.incrementAndGet() == 2) {
                Path folder = quotaConfig.resolve("clockwork-bosses/boss-templates/newref"); Files.createDirectories(folder);
                Files.writeString(folder.resolve("template.json"), "{\"format_version\":1,\"music\":{\"source\":\"library\",\"file\":\"track.ogg\"}}");
            }
        }), "new saved reference immediately before atomic commit invalidates acknowledgment");
        check(Arrays.equals(quotaLibrary.readValidated("track.ogg"), audio), "impact conflict preserves original content");
        Path backupRoot = quotaConfig.resolve("clockwork-bosses/music-backups"); Files.createDirectories(backupRoot);
        int have; try (var entries = Files.list(backupRoot)) { have = (int) entries.count(); }
        for (int i = have; i < SoundBackups.MAX_FILES; i++) Files.write(backupRoot.resolve("bound" + i + ".ogg"), new byte[]{1});
        refused(() -> put(quotaLibrary, "track.ogg", SoundUploadChecks.variant(audio), true), "backup exhaustion prevents replacement");
        check(Arrays.equals(quotaLibrary.readValidated("track.ogg"), audio), "failed backup leaves current track intact");
    }

    private static void http(TemplateFiles files, SharedMusicLibrary library, byte[] bytes) throws Exception {
        AtomicBoolean owner = new AtomicBoolean(true); var access = new AccessSessions(who -> owner.get());
        String origin; try (var socket = new java.net.ServerSocket(0, 1, InetAddress.getLoopbackAddress())) { origin = "http://127.0.0.1:" + socket.getLocalPort(); }
        try (var server = new TestApiServer("127.0.0.1", URI.create(origin).getPort(), new TemplateApi(origin, access, files, TestApiServer.ID)); var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()) {
            final String base = origin;
            class Requests {
                String cookie = "", csrf = "";
                HttpResponse<byte[]> send(String method, String path, byte[] body, Map<String, String> headers) throws Exception {
                    var request = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(20));
                    if (!cookie.isEmpty()) request.header("Cookie", cookie);
                    if (method.equals("PUT") || method.equals("POST")) {
                        request.header("Origin", headers.getOrDefault("Origin", base));
                        request.header("Content-Type", method.equals("PUT") ? "audio/ogg" : "application/json");
                        request.header("X-Workshop-CSRF", headers.getOrDefault("X-Workshop-CSRF", csrf));
                    }
                    headers.forEach((key, value) -> { if (!key.equals("Origin") && !key.equals("X-Workshop-CSRF")) request.header(key, value); });
                    return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body)).build(), HttpResponse.BodyHandlers.ofByteArray());
                }
            }
            var r = new Requests(); check(r.send("GET", "/api/music-library", null, Map.of()).statusCode() == 401, "anonymous library denied");
            var status = JsonParser.parseString(new String(r.send("GET", "/api/status", null, Map.of()).body(), StandardCharsets.UTF_8)).getAsJsonObject();
            check(status.getAsJsonObject("shared_music_library").get("version").getAsInt() == 1, "actual advertised library contract");
            String code = access.issue(new AccessSessions.Owner(UUID.randomUUID(), "library_owner"));
            var login = r.send("POST", "/api/login", ("{\"code\":\"" + code + "\"}").getBytes(StandardCharsets.UTF_8), Map.of());
            check(login.statusCode() == 200, "verified owner pairing"); r.cookie = login.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0];
            r.csrf = JsonParser.parseString(new String(login.body(), StandardCharsets.UTF_8)).getAsJsonObject().get("csrf").getAsString();
            check(r.send("GET", "/api/music-library/audio/boss2.ogg", null, Map.of()).statusCode() == 200, "actual supplied-track authenticated preview");
            var head = r.send("HEAD", "/api/music-library/audio/boss2.ogg", null, Map.of()); check(head.statusCode() == 200 && head.body().length == 0, "private HEAD metadata without body");
            check(r.send("GET", "/api/music-library/audio/missing.ogg", null, Map.of()).statusCode() == 404, "explicit missing shared diagnostic");
            var list = library.listing(); var headers = Map.of("If-Match", "\"" + string(list, "revision") + "\"", "X-Music-Revision", "none", "X-Music-Impact", string(list, "impact_revision"));
            var badCsrf = new HashMap<>(headers); badCsrf.put("X-Workshop-CSRF", "bad");
            check(r.send("PUT", "/api/music-library/http.ogg", bytes, badCsrf).statusCode() == 403, "library upload CSRF enforced");
            var badOrigin = new HashMap<>(headers); badOrigin.put("Origin", "https://other.example");
            check(r.send("PUT", "/api/music-library/http.ogg", bytes, badOrigin).statusCode() == 403, "exact-origin enforced");
            var upload = r.send("PUT", "/api/music-library/http.ogg", bytes, headers); check(upload.statusCode() == 200, "owner-only raw library upload through actual API");
            check(r.send("PUT", "/api/music-library/other.ogg", bytes, headers).statusCode() == 409, "stale catalog denied");
            owner.set(false); check(r.send("GET", "/api/music-library", null, Map.of()).statusCode() == 401, "owner revocation invalidates library access");
        }
        check(RelayClient.routeAllowed("GET", "/api/music-library") && RelayClient.routeAllowed("PUT", "/api/music-library/http.ogg")
                && RelayClient.routeAllowed("HEAD", "/api/music-library/audio/http.ogg") && RelayClient.routeAllowed("POST", "/api/music-library/http.ogg/delete"), "explicit relay library route allowlist");
        check(!RelayClient.routeAllowed("DELETE", "/api/music-library/http.ogg") && !RelayClient.routeAllowed("GET", "/api/music-library/audio/../x.ogg"), "relay rejects arbitrary methods/paths");
    }
}
