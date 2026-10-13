package clockwork.web;

import com.sun.net.httpserver.Headers;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class RelayChecks {
    static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        identityConfinement();
        Path config = Files.createTempDirectory(Path.of("build").toAbsolutePath(), "relay-check-");
        var identity = RelayIdentity.load(config); var same = RelayIdentity.load(config);
        check(identity.id().equals(same.id()) && identity.secret().equals(same.secret()), "Identity persists across restart");
        check(!identity.id().contains(identity.secret()), "Public id contains no key");
        Path key = config.resolve("clockwork-bosses-web/relay-server.key");
        Files.writeString(key, "x".repeat(100));
        try { RelayIdentity.load(config); throw new AssertionError("Oversized key accepted"); } catch (IOException expected) { }
        Files.writeString(key, identity.secret());
        for (String origin : List.of("http://example.org", "http://127.0.0.1:8790", "https://example.org/", "https://user:secret@example.org", "https://example.org/?x=y", "https://example.org#token", "file:///tmp"))
            try { RelayClient.origin(origin, false); throw new AssertionError("Unsafe relay origin: " + origin); } catch (IOException expected) { }
        check(RelayClient.origin("https://workshop.clockworkboss.com", false).getHost().equals("workshop.clockworkboss.com"), "HTTPS accepted");
        for (String path : List.of("/api/audio/a/sounds/%2e%2e.ogg", "/api/audio/a/sounds/../x.ogg", "/api/audio/a/sounds/x.mp3", "https://evil.org/api/status", "/api/private"))
            check(!RelayClient.routeAllowed("GET", path), "Arbitrary route blocked");
        String json = "{\"format_version\":1,\"health\":1000,\"addon\":{\"precise\":900719925474099312345,\"tiny\":1.2300e-120}}";
        var files = new TemplateFiles(config, new TemplateJson()); var snapshot = files.save("relay_test", json.getBytes(StandardCharsets.UTF_8), "none", "none"); files.publish("relay_test", snapshot.revision());
        var access = new AccessSessions(owner -> true); var api = new TemplateApi("https://workshop.clockworkboss.com", access, files, identity.id());
        var login = access.redeem(access.issue(new AccessSessions.Owner(UUID.randomUUID(), "isolated_owner")));
        var headers = new Headers(); headers.set("Host", "workshop.clockworkboss.com"); headers.set("Cookie", "__Host-clockwork_workshop_" + identity.id() + "=" + login.token());
        var sink = new Capture(); try (var exchange = new RelayExchange("GET", "/api/active/relay_test", headers, new byte[0], sink)) { api.handle(exchange); }
        check(sink.status == 200 && sink.ended && sink.body.toString(StandardCharsets.UTF_8).equals(json), "Exchange preserves exact addon JSON");
        var head = new Capture(); try (var exchange = new RelayExchange("HEAD", "/api/active/relay_test", headers, new byte[0], head)) { api.handle(exchange); }
        check(head.status == 200 && head.ended && head.body.size() == 0 && Integer.toString(json.length()).equals(head.headers.getFirst("Content-Length")), "HEAD has no body");
        check(api.workshopUrl().equals("https://workshop.clockworkboss.com/s/" + identity.id() + "/"), "Scoped command link"); api.close();
        System.out.println("PASS: persistent local relay key, redirected key/ancestor rejection with unchanged outside key, HTTPS-only configuration, typed routes, scoped API cookies, exact addon bytes and HEAD responses");
    }
    /** Real Windows junctions must fail before reading, creating or replacing a
     * credential outside the selected config tree. No credential is printed. */
    static void identityConfinement() throws Exception {
        Path root = Files.createTempDirectory(Path.of("build").toAbsolutePath(), "relay-links-");
        Path outside = Files.createTempDirectory(Path.of("build").toAbsolutePath(), "relay-outside-");
        Path outsideKey = outside.resolve("relay-server.key");
        byte[] preserved = ("a".repeat(43) + "\n").getBytes(StandardCharsets.US_ASCII);
        Files.write(outsideKey, preserved);
        Path linkedKeyDirectory = root.resolve("clockwork-bosses-web");
        directoryRedirect(linkedKeyDirectory, outside);
        try {
            deniedIdentity(root);
            check(Arrays.equals(Files.readAllBytes(outsideKey), preserved), "Outside key was changed through redirected key directory");
            try (var entries = Files.list(outside)) { check(entries.count() == 1, "Redirected key directory gained files"); }
        } finally { Files.delete(linkedKeyDirectory); }
        Path linkedAncestor = root.resolve("redirected-parent");
        directoryRedirect(linkedAncestor, outside);
        try {
            deniedIdentity(linkedAncestor.resolve("new-config"));
            check(!Files.exists(outside.resolve("new-config")), "Config directory was created through redirected ancestor");
            check(Arrays.equals(Files.readAllBytes(outsideKey), preserved), "Outside key changed through redirected ancestor");
        } finally { Files.delete(linkedAncestor); }
    }
    static void deniedIdentity(Path config) throws Exception {
        try { RelayIdentity.load(config); throw new AssertionError("Redirected relay identity path accepted"); }
        catch (IOException expected) { }
    }
    static void directoryRedirect(Path link, Path target) throws Exception {
        if (!System.getProperty("os.name").startsWith("Windows")) { Files.createSymbolicLink(link, target); return; }
        // Force a junction, even on systems that also allow ordinary symlinks.
        var process = new ProcessBuilder("cmd.exe", "/d", "/c", "mklink", "/J", link.toString(), target.toString()).redirectErrorStream(true).start();
        if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) { process.destroyForcibly(); throw new AssertionError("Disposable junction creation timed out"); }
        check(process.exitValue() == 0, "Could not create disposable relay junction: " + new String(process.getInputStream().readNBytes(1024), StandardCharsets.UTF_8));
        check(!Files.isSymbolicLink(link), "Fixture must exercise a Windows junction");
    }
    static final class Capture implements RelayExchange.Sink {
        int status; Headers headers; boolean ended; final ByteArrayOutputStream body = new ByteArrayOutputStream();
        public void begin(int status, Headers headers) { this.status = status; this.headers = headers; }
        public void data(byte[] bytes, int offset, int count) { check(count <= 32768, "Bounded response chunk"); body.write(bytes, offset, count); }
        public void end() { ended = true; }
        public void error() { throw new AssertionError("Unexpected exchange error"); }
    }
}
