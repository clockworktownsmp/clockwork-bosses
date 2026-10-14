package clockwork.web;

/** Run with the immutable released jar first on the classpath. This proves its
 * actual API still works without new metadata; the issuer/HTTP server and data
 * are disposable test fixtures, not an installed Minecraft server. */
public final class LegacyChannelJarChecks {
    public static void main(String[] args) throws Exception {
        var location = TemplateApi.class.getProtectionDomain().getCodeSource().getLocation().toString();
        if (!location.endsWith("clockwork_bosses-0.2.0.jar")) throw new AssertionError("Released implementation was not loaded");
        try (var input = TemplateApi.class.getResourceAsStream("/fabric.mod.json")) {
            var info = com.google.gson.JsonParser.parseString(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            if (!info.get("version").getAsString().equals("0.2.0")) throw new AssertionError("Wrong released metadata");
        }
        var root = java.nio.file.Files.createTempDirectory(java.nio.file.Path.of("build").toAbsolutePath(), "released-status-");
        int port;
        try (var socket = new java.net.ServerSocket(0)) { port = socket.getLocalPort(); }
        String origin = "http://127.0.0.1:" + port;
        var api = new TemplateApi(origin, new AccessSessions(owner -> true), new TemplateFiles(root, new TemplateJson()), TestApiServer.ID);
        try (var server = new TestApiServer("127.0.0.1", port, api); var client = java.net.http.HttpClient.newHttpClient()) {
            var response = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(origin + "/api/status")).GET().build(), java.net.http.HttpResponse.BodyHandlers.ofByteArray());
            var status = TemplateJson.parse(response.body());
            if (response.statusCode() != 200 || status.has("mod_version") || status.has("mod_channel") || status.has("workshop_compatibility"))
                throw new AssertionError("Released status was changed or required new metadata");
        }
        ApiChecks.main(args);
        System.out.println("PASS: immutable released 0.2.0 API executes its v1 owner/CSRF/save/download/publish contract without new metadata");
    }
}
