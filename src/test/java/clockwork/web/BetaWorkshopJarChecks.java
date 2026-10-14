package clockwork.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.net.URI;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Test-only loopback/storage fixtures run with the exact beta jar first on the
 * classpath. Public website metadata may be supplied as the sole argument.
 * No Minecraft accounts, hosted sessions, server mounts or credentials are used. */
public final class BetaWorkshopJarChecks {
    private static int checks;
    private static void check(boolean value, String reason) {
        if (!value) throw new AssertionError(reason);
        checks++;
    }
    private static int port() throws Exception {
        try (var socket = new ServerSocket(0)) { return socket.getLocalPort(); }
    }
    private static JsonObject body(HttpResponse<byte[]> reply) throws Exception { return TemplateJson.parse(reply.body()); }
    private static Map<String, String> write(String csrf, String revision, String active) {
        return Map.of("X-Workshop-CSRF", csrf, "If-Match", "\"" + revision + "\"", "X-Active-Revision", active);
    }
    private static HttpResponse<byte[]> request(HttpClient client, String origin, String method,
            String path, String cookie, byte[] bytes, Map<String, String> headers) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(origin + path)).timeout(Duration.ofSeconds(10));
        if (cookie != null) request.header("Cookie", cookie);
        if (!method.equals("GET")) request.header("Origin", origin).header("Content-Type", "application/json");
        headers.forEach(request::setHeader);
        return client.send(request.method(method, bytes == null ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofByteArray(bytes)).build(), HttpResponse.BodyHandlers.ofByteArray());
    }
    public static void main(String[] args) throws Exception {
        check(TemplateApi.class.getProtectionDomain().getCodeSource().getLocation().toString()
            .endsWith("clockwork_bosses-0.3.0-beta.4.jar"), "exact packaged beta API must be loaded");
        JsonObject packaged;
        try (var input = TemplateApi.class.getResourceAsStream("/fabric.mod.json")) {
            packaged = TemplateJson.parse(input.readAllBytes());
        }
        var mod = WorkshopRelease.from(packaged.get("version").getAsString(),
            packaged.getAsJsonObject("custom").get("clockwork_bosses:workshop_build_channel").getAsString());
        check(mod.version().equals("0.3.0-beta.4") && mod.channel() == WorkshopRelease.Channel.BETA, "installed version/channel come from actual jar metadata");
        var settings = new Properties(); settings.setProperty("use_build_channel", "true");
        check(WorkshopSettings.select(settings, mod.channel()).origin().equals(URI.create("https://beta.clockworkboss.com")), "browser and server select the public beta origin");
        JsonObject declaration;
        if (args.length == 1) {
            try (var input = Files.newInputStream(Path.of(args[0]))) {
                declaration = TemplateJson.parse(input.readNBytes(WorkshopCompatibility.MAX_BYTES + 1), WorkshopCompatibility.MAX_BYTES);
            }
        } else {
            declaration = new JsonObject(); declaration.addProperty("channel", "beta");
            declaration.addProperty("web_version", "0.2.2"); declaration.addProperty("build", "b".repeat(64));
            var versions = new JsonArray(); versions.add("0.2.0"); versions.add(mod.version());
            declaration.add("compatible_mod_versions", versions); declaration.add("required_capabilities", new JsonArray());
        }
        check(!declaration.get("web_version").getAsString().equals(mod.version()), "independent website/mod versions are exercised");
        Path root = Files.createTempDirectory(Path.of("build").toAbsolutePath(), "beta-release-api-");
        var files = new TemplateFiles(root, new TemplateJson());
        var allowed = new AtomicBoolean(true); var access = new AccessSessions(owner -> allowed.get());
        var owner = new AccessSessions.Owner(UUID.randomUUID(), "beta_release_owner");
        int port = port(); String origin = "http://127.0.0.1:" + port;
        // Only transport is replaced with loopback; the real selected beta host
        // was asserted above. The compatibility/data classes are from the jar.
        var endpoint = new WorkshopRelease.Endpoint(URI.create(origin), WorkshopRelease.Channel.BETA, true, false, false);
        var metadata = new AtomicReference<>(declaration);
        var compatibility = new WorkshopCompatibility(mod, endpoint, TemplateApi.capabilities(false));
        compatibility.source(() -> new WorkshopCompatibility.Response(200, metadata.get().toString().getBytes(StandardCharsets.UTF_8)));
        compatibility.requireWritable(); checks++;
        var api = new TemplateApi(origin, access, files, TestApiServer.ID, null, compatibility);
        try (var server = new TestApiServer("127.0.0.1", port, api); var client = HttpClient.newHttpClient()) {
            var status = body(request(client, origin, "GET", "/api/status", null, null, Map.of()));
            check(status.get("mod_version").getAsString().equals(mod.version()) && status.get("mod_channel").getAsString().equals("beta")
                && status.getAsJsonObject("workshop_compatibility").get("writable").getAsBoolean(), "actual status exposes the connected beta and permits declared writes");
            byte[] json = "{\"format_version\":1,\"health\":1000,\"boss_bar\":{\"pvp_protection\":{\"enabled\":true}},\"unknown_addon\":{\"exact\":900719925474099312345,\"tiny\":1.2300e-120}}".getBytes(StandardCharsets.UTF_8);
            check(request(client, origin, "PUT", "/api/drafts/beta_test", null, json, Map.of()).statusCode() == 401, "compatible website does not bypass login");
            String code = access.issue(owner);
            var login = request(client, origin, "POST", "/api/login", null, ("{\"code\":\"" + code + "\"}").getBytes(StandardCharsets.UTF_8), Map.of());
            check(login.statusCode() == 200, "one-use owner login succeeds");
            String cookie = login.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0];
            String csrf = body(login).get("csrf").getAsString();
            check(request(client, origin, "PUT", "/api/drafts/beta_test", cookie, json, write("wrong", "none", "none")).statusCode() == 403, "CSRF enforced when compatibility permits edits");
            var foreign = new java.util.HashMap<>(write(csrf, "none", "none")); foreign.put("Origin", "https://workshop.clockworkboss.com");
            check(request(client, origin, "PUT", "/api/drafts/beta_test", cookie, json, foreign).statusCode() == 403, "production origin cannot mutate beta");
            var saved = request(client, origin, "PUT", "/api/drafts/beta_test", cookie, json, write(csrf, "none", "none"));
            check(saved.statusCode() == 200, "declared beta version saves a draft");
            String revision = body(saved).get("revision").getAsString();
            check(Arrays.equals(request(client, origin, "GET", "/api/drafts/beta_test", cookie, null, Map.of()).body(), json), "draft preserves exact addon tokens and PvP settings");
            byte[] edited = new String(json, StandardCharsets.UTF_8).replace("1000", "1234").getBytes(StandardCharsets.UTF_8);
            saved = request(client, origin, "PUT", "/api/drafts/beta_test", cookie, edited, write(csrf, revision, "none"));
            check(saved.statusCode() == 200, "subsequent draft edit succeeds"); revision = body(saved).get("revision").getAsString();
            byte[] sound = SoundUploadChecks.silence();
            var uploadHeaders = new java.util.HashMap<>(write(csrf, revision, "none"));
            uploadHeaders.put("Content-Type", "audio/ogg"); uploadHeaders.put("X-Sound-Revision", "none");
            var uploaded = request(client, origin, "PUT", "/api/draft-audio/beta_test/voices/beta-line.ogg", cookie, sound, uploadHeaders);
            check(uploaded.statusCode() == 200, "validated raw OGG upload shares compatible/owner/CSRF gates"); revision = body(uploaded).get("revision").getAsString();
            var published = request(client, origin, "POST", "/api/publish/beta_test", cookie, "{}".getBytes(StandardCharsets.UTF_8), write(csrf, revision, "none"));
            check(published.statusCode() == 200, "declared beta version publishes edited draft and audio");
            check(Arrays.equals(request(client, origin, "GET", "/api/active/beta_test", cookie, null, Map.of()).body(), edited), "active template keeps exact edited data");
            check(Arrays.equals(files.audio("beta_test", "voices", "beta-line.ogg"), sound), "published voice line matches the uploaded bytes");
            int otherPort = port(); String otherOrigin = "http://127.0.0.1:" + otherPort;
            var otherApi = new TemplateApi(otherOrigin, new AccessSessions(other -> true), new TemplateFiles(root.resolve("other"), new TemplateJson()), "1".repeat(64));
            try (var otherServer = new TestApiServer("127.0.0.1", otherPort, otherApi)) {
                check(request(client, otherOrigin, "GET", "/api/templates", cookie, null, Map.of()).statusCode() == 401, "beta session is isolated from another server identity");
            }
            var unsupported = declaration.deepCopy(); var versions = new JsonArray(); versions.add("0.2.0"); unsupported.add("compatible_mod_versions", versions);
            metadata.set(unsupported); compatibility.refresh(true);
            check(request(client, origin, "PUT", "/api/drafts/blocked", cookie, json, write(csrf, "none", "none")).statusCode() == 409, "withdrawn support refuses further writes");
            check(request(client, origin, "POST", "/api/publish/beta_test", cookie, "{}".getBytes(StandardCharsets.UTF_8), write(csrf, revision, "none")).statusCode() == 409, "withdrawn support refuses publication");
            check(request(client, origin, "GET", "/api/active/beta_test", cookie, null, Map.of()).statusCode() == 200, "read-only recovery remains usable");
            check(files.draft("blocked").content() == null, "rejected write leaves no new draft");
            metadata.set(declaration); compatibility.refresh(true); allowed.set(false);
            check(request(client, origin, "PUT", "/api/drafts/revoked", cookie, json, write(csrf, "none", "none")).statusCode() == 401, "compatibility never overrides revoked owner permission");
        }
        System.out.println("PASS: exact beta.4 jar, declared independent website version, owner/CSRF/origin/session isolation, draft edit, raw audio, exact publication and withdrawn-support guards (" + checks + " assertions)");
    }
}
