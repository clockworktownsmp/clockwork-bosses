package clockwork.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Pure routing plus real loopback HTTP fixtures. Credentials are synthetic,
 * directories are disposable, and no hosted relay or server setting is touched. */
public final class ChannelChecks {
    private static int count;
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); count++; }
    private interface Checked { void run() throws Exception; }
    private static void denied(Checked work) throws Exception {
        try { work.run(); throw new AssertionError("Expected refusal"); } catch (java.io.IOException expected) { count++; }
    }
    private static WorkshopSettings settings(String suffix, WorkshopRelease.Channel build) throws Exception {
        return WorkshopSettings.decode(("enabled=true\ntemplate_access_enabled=true\n" + suffix).getBytes(StandardCharsets.UTF_8), build);
    }
    private static JsonObject info(String channel, String version, String... required) {
        var result = new JsonObject(); result.addProperty("channel", channel); result.addProperty("web_version", "0.2.1"); result.addProperty("build", "a".repeat(64));
        var versions = new JsonArray(); if (version != null) versions.add(version); result.add("compatible_mod_versions", versions);
        var capabilities = new JsonArray(); for (String name : required) capabilities.add(name); result.add("required_capabilities", capabilities); return result;
    }
    private static WorkshopCompatibility.Response response(JsonObject info) { return new WorkshopCompatibility.Response(200, info.toString().getBytes(StandardCharsets.UTF_8)); }
    private static String state(WorkshopCompatibility compatibility) { var value = new JsonObject(); compatibility.describe(value); return value.getAsJsonObject("workshop_compatibility").get("state").getAsString(); }

    public static void main(String[] args) throws Exception {
        routing(); identities(); compatibility(); http(); api();
        System.out.println("PASS: Workshop channels, exact-origin identities, independent versions/capabilities, bounded metadata, legacy v1 and authenticated read-only/mutation checks (" + count + " assertions)");
    }
    private static void routing() throws Exception {
        var dev = WorkshopRelease.Channel.DEVELOPMENT;
        check(WorkshopRelease.from("0.2.0-beta.99", null).channel() == WorkshopRelease.Channel.STABLE, "legacy beta suffix does not route away from stable");
        check(WorkshopRelease.from("0.2.0", "beta").channel() == WorkshopRelease.Channel.BETA, "explicit channel wins over version spelling");
        denied(() -> WorkshopRelease.from("0.2.0", "automatic"));
        check(settings("", dev).origin().equals(WorkshopRelease.Channel.STABLE.origin), "legacy omitted URL remains stable");
        check(settings("use_build_channel=true\n", dev).origin().equals(dev.origin), "fresh explicit build opt-in routes development");
        var betaBuild = settings("use_build_channel=true\n", WorkshopRelease.Channel.BETA);
        check(betaBuild.origin().equals(URI.create("https://beta.clockworkboss.com")), "explicit beta build uses the beta host");
        check(betaBuild.endpoint().channel() == WorkshopRelease.Channel.BETA && !betaBuild.endpoint().legacyIdentity(), "beta browser/relay endpoint uses an isolated identity");
        check(settings("use_build_channel=true\nrelay_url=https://workshop.clockworkboss.com\n", WorkshopRelease.Channel.BETA).origin().equals(WorkshopRelease.Channel.STABLE.origin), "beta builds still preserve explicitly pinned production URLs");
        check(settings("use_build_channel=true\nrelay_url=https://workshop.clockworkboss.com\n", dev).origin().equals(WorkshopRelease.Channel.STABLE.origin), "existing explicit URL remains pinned");
        check(settings("relay_url=https://self-hosted.example:8443\n", dev).origin().equals(URI.create("https://self-hosted.example:8443")), "custom existing exact origin retained");
        var override = settings("relay_url=https://workshop.clockworkboss.com\ntesting_channel=beta\n", dev);
        check(override.origin().equals(WorkshopRelease.Channel.BETA.origin) && override.endpoint().testing() && !override.endpoint().legacyIdentity(), "deliberate test override selects separate beta identity");
        var stableTest = settings("testing_channel=stable\n", dev);
        check(!stableTest.sameConnection(settings("relay_url=https://workshop.clockworkboss.com\n", dev)), "test identity change replaces connection even on same origin");
        for (String invalid : List.of("testing_channel=automatic", "testing_channel=Beta", "relay_url=https://user:password@example.org", "relay_url=https://example.org/path", "relay_url=http://example.org", "use_build_channel=maybe"))
            denied(() -> settings(invalid, dev));
    }
    private static void identities() throws Exception {
        Path root = Files.createTempDirectory(Path.of("build").toAbsolutePath(), "channel-identity-");
        var stable = settings("relay_url=https://workshop.clockworkboss.com", WorkshopRelease.Channel.DEVELOPMENT).endpoint();
        var old = RelayIdentity.load(root); Path key = root.resolve("clockwork-bosses-web/relay-server.key"); byte[] before = Files.readAllBytes(key);
        var adopted = RelayIdentity.load(root, stable);
        check(old.id().equals(adopted.id()) && Arrays.equals(before, Files.readAllBytes(key)), "stable upgrade retains exact credential and address");
        check(Files.readString(root.resolve("clockwork-bosses-web/relay-server.origin")).equals(stable.origin() + "\n"), "non-secret exact-origin binding recorded");
        var beta = settings("testing_channel=beta", WorkshopRelease.Channel.STABLE).endpoint();
        var development = settings("testing_channel=development", WorkshopRelease.Channel.STABLE).endpoint();
        var betaIdentity = RelayIdentity.load(root, beta); var devIdentity = RelayIdentity.load(root, development);
        check(!betaIdentity.id().equals(old.id()) && !devIdentity.id().equals(old.id()) && !devIdentity.id().equals(betaIdentity.id()), "test identities independently generated, never production copies");
        check(RelayIdentity.load(root, beta).id().equals(betaIdentity.id()), "same test origin retains its own identity");
        check(Arrays.equals(before, Files.readAllBytes(key)), "test origin does not rewrite or migrate production key");
        denied(() -> RelayIdentity.load(root, settings("relay_url=https://another.example", WorkshopRelease.Channel.STABLE).endpoint()));
        check(Arrays.equals(before, Files.readAllBytes(key)), "cross-host legacy reuse fails without rotating key");
        Path custom = Files.createTempDirectory(Path.of("build").toAbsolutePath(), "channel-custom-");
        var existing = RelayIdentity.load(custom);
        check(RelayIdentity.load(custom, settings("relay_url=https://self-hosted.example", WorkshopRelease.Channel.STABLE).endpoint()).id().equals(existing.id()), "explicit legacy custom origin adopted without resetting address");
        Path redirected = Files.createTempDirectory(Path.of("build").toAbsolutePath(), "channel-binding-");
        Files.createDirectories(redirected.resolve("clockwork-bosses-web"));
        Path outside = Files.createTempDirectory(Path.of("build").toAbsolutePath(), "channel-outside-");
        AudioChecks.directoryLink(redirected.resolve("clockwork-bosses-web/relay-identities"), outside);
        denied(() -> RelayIdentity.load(redirected, beta));
        try (var entries = Files.list(outside)) { check(entries.findAny().isEmpty(), "identity path redirect writes nothing outside"); }
    }
    private static void compatibility() throws Exception {
        var mod = WorkshopRelease.from("0.2.0", "stable"); var caps = TemplateApi.capabilities(true);
        var beta = settings("testing_channel=beta", WorkshopRelease.Channel.DEVELOPMENT).endpoint();
        check(WorkshopCompatibility.assess(info("beta", "0.2.0", "sound_uploads", "item_catalog"), mod, beta, caps).writable(), "web 0.2.1 supports mod 0.2.0 without version equality");
        check(!WorkshopCompatibility.assess(info("beta", "0.2.1"), mod, beta, caps).writable(), "only exact declared mod versions pass");
        check(!WorkshopCompatibility.assess(info("beta", "0.2.0", "unknown_addon_capability"), mod, beta, caps).writable(), "unknown required capability fails");
        check(!WorkshopCompatibility.assess(info("beta", "0.2.0", "enabled"), mod, beta, caps).writable(), "access flag is not a logical feature capability");
        check(!WorkshopCompatibility.assess(info("beta", "0.2.0", "item_catalog"), mod, beta, TemplateApi.capabilities(false)).writable(), "missing required known capability fails");
        denied(() -> WorkshopCompatibility.assess(info("production", "0.2.0"), mod, beta, caps));
        var dev = settings("testing_channel=development", WorkshopRelease.Channel.STABLE).endpoint(); var unreleased = info("dev", null); unreleased.add("web_version", com.google.gson.JsonNull.INSTANCE);
        check(!WorkshopCompatibility.assess(unreleased, mod, dev, caps).writable(), "empty dev version list is not write support");
        AtomicLong clock = new AtomicLong(); AtomicInteger reads = new AtomicInteger(); var data = new AtomicReference<>(response(info("beta", "0.2.0")));
        var compatibility = new WorkshopCompatibility(mod, beta, caps, clock::get); compatibility.source(() -> { reads.incrementAndGet(); return data.get(); });
        compatibility.requireWritable(); compatibility.requireWritable(); check(reads.get() == 1, "cache coalesces repeated checks without tick work");
        data.set(response(info("beta", "other_version"))); clock.addAndGet(TimeUnit.SECONDS.toNanos(31)); denied(compatibility::requireWritable);
        check(state(compatibility).equals("incompatible") && reads.get() == 2, "expired approval is revalidated before another edit");
        compatibility.close(); denied(compatibility::requireCurrent);
        var stable = settings("relay_url=https://workshop.clockworkboss.com", WorkshopRelease.Channel.DEVELOPMENT).endpoint();
        for (var endpoint : List.of(stable, beta, dev)) {
            var missing = new WorkshopCompatibility(mod, endpoint, caps); missing.source(() -> new WorkshopCompatibility.Response(404, new byte[0])); missing.refresh(true);
            check(state(missing).equals(endpoint == stable ? "legacy" : "unavailable"), "only stable absence permits legacy behavior");
            if (endpoint == stable) missing.requireWritable(); else denied(missing::requireWritable);
            var invalid = new WorkshopCompatibility(mod, endpoint, caps); invalid.source(() -> new WorkshopCompatibility.Response(200, "{\"private_fixture\":\"do not echo\"}".getBytes())); invalid.refresh(true);
            check(state(invalid).equals("unavailable"), "malformed-present metadata never downgrades to legacy");
        }
    }
    private static void http() throws Exception {
        AtomicInteger redirected = new AtomicInteger(); var payload = new AtomicReference<>(info("beta", "0.2.0").toString().getBytes(StandardCharsets.UTF_8)); AtomicInteger code = new AtomicInteger(200);
        var server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/build-info.json", exchange -> {
            check(exchange.getRequestHeaders().getFirst("Authorization") == null && exchange.getRequestHeaders().getFirst("Cookie") == null, "public metadata request has no secrets/cookies");
            if (code.get() == 302) exchange.getResponseHeaders().set("Location", "/other");
            byte[] bytes = payload.get(); exchange.sendResponseHeaders(code.get(), bytes.length); try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.createContext("/other", exchange -> { redirected.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close(); }); server.start();
        try (var client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()) {
            URI origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort()); var source = WorkshopCompatibility.http(client, origin);
            check(source.fetch().status() == 200, "actual bounded HTTP metadata fetch");
            code.set(302); check(source.fetch().status() == 302 && redirected.get() == 0, "redirect refused; no alternate endpoint visited");
            code.set(200); payload.set(new byte[WorkshopCompatibility.MAX_BYTES + 1]); denied(source::fetch);
        } finally { server.stop(0); }
    }
    private static void api() throws Exception {
        Path root = Files.createTempDirectory(Path.of("build").toAbsolutePath(), "channel-api-"); var files = new TemplateFiles(root, new TemplateJson());
        files.save("recovery", "{\"format_version\":1}".getBytes(), "none", "none");
        var access = new AccessSessions(owner -> true); var owner = new AccessSessions.Owner(UUID.randomUUID(), "channel_owner");
        int port; try (var socket = new java.net.ServerSocket(0, 1, InetAddress.getLoopbackAddress())) { port = socket.getLocalPort(); }
        String origin = "http://127.0.0.1:" + port; var endpoint = new WorkshopRelease.Endpoint(URI.create(origin), WorkshopRelease.Channel.BETA, true, false, false);
        var compatibility = new WorkshopCompatibility(WorkshopRelease.from("0.2.0", "stable"), endpoint, TemplateApi.capabilities(false));
        var metadata = new AtomicReference<>(response(info("beta", "0.2.0"))); compatibility.source(metadata::get); compatibility.refresh(true);
        var api = new TemplateApi(origin, access, files, TestApiServer.ID, null, compatibility);
        try (var server = new TestApiServer("127.0.0.1", port, api); var client = HttpClient.newHttpClient()) {
            var status = request(client, origin, "GET", "/api/status", null, null, null); var body = TemplateJson.parse(status.body());
            check(body.get("mod_version").getAsString().equals("0.2.0") && body.get("mod_channel").getAsString().equals("stable") && body.get("workshop_channel").getAsString().equals("beta"), "connected version and explicit/different channel fields exposed");
            String link = access.issue(owner); var login = request(client, origin, "POST", "/api/login", null, null, "{\"code\":\"" + link + "\"}");
            String cookie = login.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0], csrf = TemplateJson.parse(login.body()).get("csrf").getAsString();
            metadata.set(response(info("beta", "other_version"))); compatibility.refresh(true);
            check(request(client, origin, "GET", "/api/templates", cookie, null, null).statusCode() == 200, "incompatible connection permits authenticated list");
            check(request(client, origin, "GET", "/api/drafts/recovery", cookie, null, null).statusCode() == 200, "read-only recovery download remains available");
            check(request(client, origin, "PUT", "/api/drafts/new", cookie, csrf, "{}").statusCode() == 409, "save refused before writing");
            check(request(client, origin, "POST", "/api/publish/recovery", cookie, csrf, "{}").statusCode() == 409, "publish refused");
            check(request(client, origin, "PUT", "/api/draft-audio/recovery/sounds/file.ogg", cookie, csrf, "{}").statusCode() == 409, "raw sound mutation refused");
            check(request(client, origin, "PUT", "/api/drafts/new", cookie, "wrong", "{}").statusCode() == 403, "CSRF still runs before compatibility");
            check(!Files.exists(root.resolve("clockwork-bosses/boss-templates/new")), "denied mutation leaves storage unchanged");
            check(request(client, origin, "POST", "/api/logout", cookie, csrf, "{}").statusCode() == 200, "logout remains available despite mismatch");
        }
    }
    private static HttpResponse<byte[]> request(HttpClient client, String origin, String method, String path, String cookie, String csrf, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(origin + path));
        if (cookie != null) builder.header("Cookie", cookie);
        if (!method.equals("GET")) builder.header("Origin", origin).header("Content-Type", "application/json;charset=utf-8");
        if (csrf != null) builder.header("X-Workshop-CSRF", csrf);
        builder.header("If-Match", "\"none\"").header("X-Active-Revision", "none");
        return client.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofByteArray());
    }
}
