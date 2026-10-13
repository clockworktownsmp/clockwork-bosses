package clockwork.web;

import com.google.gson.*;
import java.nio.file.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Real HTTP security/storage regressions with a synthetic identity guard.
 * This guard is test-only; production identity issuance remains player-only.
 */
public final class ApiChecks {
    private static String origin, cookie = "", csrf = "";
    private static HttpClient client;
    private static HttpResponse<byte[]> request(String method, String path, String body, Map<String,String> headers) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(origin + path)).timeout(java.time.Duration.ofSeconds(10));
        if (!cookie.isEmpty()) builder.header("Cookie", cookie);
        if (!method.equals("GET") && !method.equals("HEAD")) { builder.header("Origin", origin); builder.header("Content-Type", "application/json;charset=utf-8"); }
        headers.forEach(builder::header);
        return client.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofByteArray());
    }
    private static JsonObject json(HttpResponse<byte[]> response) { return JsonParser.parseString(new String(response.body(), StandardCharsets.UTF_8)).getAsJsonObject(); }
    private static Map<String,String> write(String revision, String active) { return Map.of("X-Workshop-CSRF",csrf,"If-Match","\""+revision+"\"","X-Active-Revision",active); }
    private static void require(boolean ok, String text) { if (!ok) throw new AssertionError(text); }
    public static void main(String[] args) throws Exception {
        Path config = Files.createTempDirectory(Path.of("build").toAbsolutePath(), "api-check-");
        var allowed = new AtomicBoolean(true); var access = new AccessSessions(owner -> allowed.get());
        var owner = new AccessSessions.Owner(UUID.randomUUID(), "test_owner");
        TemplateFiles files = new TemplateFiles(config, new TemplateJson());
        try (var socket = new java.net.ServerSocket(0, 1, InetAddress.getLoopbackAddress())) { origin="http://127.0.0.1:"+socket.getLocalPort(); }
        try (var server = new TestApiServer("127.0.0.1", URI.create(origin).getPort(), new TemplateApi(origin, access, files, TestApiServer.ID)); var http = HttpClient.newHttpClient()) {
            client = http;
            require(request("GET","/api/templates",null,Map.of()).statusCode()==401,"anonymous list denied");
            require(request("GET","/api/sounds/test",null,Map.of()).statusCode()==401,"anonymous sound list denied");
            String code=access.issue(owner);
            var login=request("POST","/api/login","{\"code\":\""+code+"\"}",Map.of());
            require(login.statusCode()==200,"one-use pairing login");
            String setCookie=login.headers().firstValue("Set-Cookie").orElseThrow();
            require(setCookie.contains("HttpOnly")&&setCookie.contains("SameSite=Strict"),"cookie protections");
            cookie=setCookie.split(";")[0]; csrf=json(login).get("csrf").getAsString();
            require(request("POST","/api/login","{\"code\":\""+code+"\"}",Map.of()).statusCode()==401,"link replay denied");
            // Rejected login intentionally clears no valid cookie on replay; this
            // explicit test client keeps its previously issued owner session.
            require(request("PUT","/api/drafts/test","{\"format_version\":1}",Map.of()).statusCode()==403,"missing CSRF denied");
            var foreign=HttpRequest.newBuilder(URI.create(origin+"/api/drafts/test")).header("Cookie",cookie).header("Origin","https://evil.example").header("Content-Type","application/json").header("X-Workshop-CSRF",csrf).PUT(HttpRequest.BodyPublishers.ofString("{}")).build();
            require(client.send(foreign,HttpResponse.BodyHandlers.discarding()).statusCode()==403,"foreign Origin denied");
            String fixture="{\"format_version\":1,\"health\":1000,\"addon\":{\"huge\":9007199254740993123456789,\"precise\":0.12345678901234567890123456789,\"tiny\":1.2300e-120,\"null\":null,\"array\":[{},[],false],\"negative\":-0},\"phases\":{\"test_phase\":{\"minimum_health_percent\":50,\"subphases\":{\"custom\":{\"type\":\"example:unknown\",\"action_settings\":{\"unknown\":42}}}}}}";
            var saved=request("PUT","/api/drafts/test",fixture,write("none","none")); require(saved.statusCode()==200,"draft create "+new String(saved.body()));
            String rev=json(saved).get("revision").getAsString();
            require(!Files.exists(config.resolve("clockwork-bosses/boss-templates/test/template.json")),"draft did not activate");
            var downloaded=request("GET","/api/drafts/test",null,Map.of()); require(Arrays.equals(downloaded.body(),fixture.getBytes(StandardCharsets.UTF_8)),"draft exact bytes");
            var retry=request("PUT","/api/drafts/test",fixture,write("none","none"));
            require(retry.statusCode()==200 && json(retry).get("revision").getAsString().equals(rev),"identical lost-response save retry");
            require(request("PUT","/api/drafts/test",fixture+"\n",write("none","none")).statusCode()==409,"stale different draft rejected");
            require(request("PUT","/api/drafts/%2e%2e",fixture,write("none","none")).statusCode()==400,"encoded traversal rejected");
            require(request("PUT","/api/drafts/con",fixture,write("none","none")).statusCode()==400,"device name rejected");
            require(request("PUT","/api/drafts/duplicate","{\"format_version\":1,\"x\":1,\"x\":2}",write("none","none")).statusCode()==400,"duplicate JSON rejected");
            require(request("PUT","/api/drafts/large"," ".repeat(TemplateJson.MAX_BYTES+1),write("none","none")).statusCode()==400,"oversize upload rejected");
            String deep="{\"format_version\":1,\"x\":"+"[".repeat(34)+"0"+"]".repeat(34)+"}";
            require(request("PUT","/api/drafts/deep",deep,write("none","none")).statusCode()==400,"depth limit");
            var publish=request("POST","/api/publish/test","{}",write(rev,"none")); require(publish.statusCode()==200,"publish valid "+new String(publish.body()));
            String active=json(publish).get("revision").getAsString();
            require(request("POST","/api/publish/test","{}",write(rev,"none")).statusCode()==200,"publish retry succeeds");
            require(request("GET","/api/drafts/test",null,Map.of()).headers().firstValue("X-Active-Revision").orElseThrow().equals(active),"reopened published draft has current baseline");
            require(Arrays.equals(request("GET","/api/active/test",null,Map.of()).body(),fixture.getBytes(StandardCharsets.UTF_8)),"publish exact unknown addon bytes");
            String changed=fixture.replace("\"health\":1000","\"health\":1234");
            saved=request("PUT","/api/drafts/test",changed,write(rev,active)); require(saved.statusCode()==200,"draft update"); rev=json(saved).get("revision").getAsString();
            require(request("POST","/api/publish/test","{}",write(rev,active)).statusCode()==200,"replace active");
            require(request("POST","/api/publish/test","{}",write(rev,active)).statusCode()==200,"replace retry does not consume backup quota");
            try(var stream=Files.list(config.resolve("clockwork-bosses-web/backups"))) { var backups=stream.toList(); require(backups.size()==1&&Arrays.equals(Files.readAllBytes(backups.getFirst()),fixture.getBytes(StandardCharsets.UTF_8)),"old active backup exact"); }
            active=AccessSessions.digest(changed.getBytes(StandardCharsets.UTF_8));
            Path soundFolder=config.resolve("clockwork-bosses/boss-templates/test/sounds"); Files.createDirectories(soundFolder.resolve("voice-lines"));
            Files.writeString(soundFolder.resolve("spawn.ogg"),"not decoded or served");Files.writeString(soundFolder.resolve("z.ogg"),"fixture");
            Files.writeString(soundFolder.resolve("UPPER.ogg"),"ignored");Files.writeString(soundFolder.resolve("private.txt"),"not exposed");
            Files.createDirectory(soundFolder.resolve("folder.ogg"));Files.writeString(soundFolder.resolve("voice-lines/line1.ogg"),"fixture");
            var soundReply=request("GET","/api/sounds/test",null,Map.of());var soundList=json(soundReply);
            require(soundReply.statusCode()==200&&soundList.getAsJsonArray("sounds").toString().equals("[\"spawn.ogg\",\"z.ogg\"]")&&soundList.getAsJsonArray("voices").toString().equals("[\"line1.ogg\"]"),"only direct valid regular OGG filenames in fixed folders");
            require(soundReply.headers().firstValue("Cache-Control").orElseThrow().equals("no-store"),"sound metadata is private");
            require(request("GET","/api/sounds/%2e%2e",null,Map.of()).statusCode()==400,"sound traversal denied");
            require(request("GET","/api/sounds/test/voice-lines",null,Map.of()).statusCode()==405,"arbitrary nested sound paths denied");
            require(request("PUT","/api/sounds/test","{}",write("none","none")).statusCode()==405,"sound upload absent");
            require(request("GET","/api/sounds/new_template",null,Map.of()).statusCode()==200&&!Files.exists(config.resolve("clockwork-bosses/boss-templates/new_template")),"missing sound folders remain absent");
            saved=request("PUT","/api/drafts/test",fixture,write(rev,active)); require(saved.statusCode()==200,"stage conflict test"); rev=json(saved).get("revision").getAsString();
            Files.writeString(config.resolve("clockwork-bosses/boss-templates/test/template.json"),changed+"\n");
            require(request("POST","/api/publish/test","{}",write(rev,active)).statusCode()==409,"external active conflict rejected");
            var incomplete=request("PUT","/api/drafts/incomplete","{\"format_version\":1,\"health\":0}",write("none","none")); require(incomplete.statusCode()==200,"unfinished draft accepted");
            require(request("POST","/api/publish/incomplete","{}",write(json(incomplete).get("revision").getAsString(),"none")).statusCode()==400,"invalid known setting cannot publish");
            String soundFixture="{\"format_version\":1,\"sounds\":{\"spawn\":{\"range\":0},\"phase_change\":{\"range\":512},\"death\":{\"range\":32}},\"voice_lines\":{\"range\":0},\"attacks\":{\"roar\":{\"sound\":{\"range\":0}}},\"phases\":{\"one\":{\"minimum_health_percent\":50,\"sounds\":{\"phase_change\":{\"range\":null}},\"roar\":{\"sound\":{\"range\":0}},\"roars\":{\"custom\":{\"sound\":{\"range\":null}}},\"subphases\":{\"roar_action\":{\"type\":\"clockwork_bosses:roar\",\"action_settings\":{\"range\":0}}}}},\"addon_sound\":{\"range\":900719925474099312345,\"precise\":1.2300e-120}}";
            var soundDraft=request("PUT","/api/drafts/automatic_sound",soundFixture,write("none","none"));
            require(soundDraft.statusCode()==200,"automatic sound draft accepted");
            require(request("POST","/api/publish/automatic_sound","{}",write(json(soundDraft).get("revision").getAsString(),"none")).statusCode()==200,"automatic and explicit sound distances publish, with nullable inheritance");
            require(Arrays.equals(request("GET","/api/active/automatic_sound",null,Map.of()).body(),soundFixture.getBytes(StandardCharsets.UTF_8)),"sound distances and unknown addon bytes survive draft/publish/download exactly");
            for (String invalidRange : List.of("-1","512.01")) {
                String invalidSound="{\"format_version\":1,\"sounds\":{\"spawn\":{\"range\":"+invalidRange+"}}}";
                String invalidName=invalidRange.equals("-1")?"negative_sound":"excessive_sound";
                var invalidDraft=request("PUT","/api/drafts/"+invalidName,invalidSound,write("none","none"));
                require(invalidDraft.statusCode()==200,"unfinished sound draft retained");
                require(request("POST","/api/publish/"+invalidName,"{}",write(json(invalidDraft).get("revision").getAsString(),"none")).statusCode()==400,"invalid sound distance cannot publish: "+invalidRange);
                require(!Files.exists(config.resolve("clockwork-bosses/boss-templates/"+invalidName)),"invalid sound distance never creates an active folder");
            }
            allowed.set(false); require(request("GET","/api/templates",null,Map.of()).statusCode()==401,"owner revoked immediately"); allowed.set(true);
            String lastCode=access.issue(owner); var last=request("POST","/api/login","{\"code\":\""+lastCode+"\"}",Map.of()); cookie=last.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0]; csrf=json(last).get("csrf").getAsString();
            require(request("POST","/api/logout","{}",Map.of("X-Workshop-CSRF",csrf)).statusCode()==200,"logout");
            require(request("GET","/api/templates",null,Map.of()).statusCode()==401,"logged out session denied");
        }
        // Explicit synthetic time checks avoid waiting 30 real minutes.
        final long[] now={0}; java.time.Clock clock=new java.time.Clock(){public java.time.ZoneId getZone(){return java.time.ZoneOffset.UTC;}public java.time.Clock withZone(java.time.ZoneId zone){return this;}public java.time.Instant instant(){return java.time.Instant.ofEpochMilli(now[0]);}};
        var timed=new AccessSessions(ownerValue -> true,clock); String expired=timed.issue(owner); now[0]=120001; require(timed.redeem(expired)==null,"expired link");
        var timedLogin=timed.redeem(timed.issue(owner)); now[0]+=1800001; require(timed.authenticate(timedLogin.token())==null,"expired session");
        for(int i=0;i<20;i++) require(timed.permit("login",20),"login budget"); require(!timed.permit("login",20),"rate limit");
        System.out.println("PASS: authenticated drafts/publish, automatic/explicit sound distances, unknown bytes, backups, conflicts, CSRF/origin, revoked/expired/replayed credentials, quotas and logout");
    }
}
