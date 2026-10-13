package clockwork.web;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Real Java websocket client + Cloudflare workerd, with isolated test identities. */
public final class RelayIntegrationChecks {
    static final HttpClient browser = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    static void check(boolean condition, String message) { RelayChecks.check(condition, message); }
    static void await(java.util.function.BooleanSupplier condition) throws Exception { long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20); while (!condition.getAsBoolean()) { if (System.nanoTime() >= end) throw new AssertionError("Relay did not reach expected state"); Thread.sleep(20); } }
    static HttpResponse<byte[]> request(String base, String path, String method, String cookie, String csrf, String body, String revision, String active) throws Exception {
        var uri = URI.create(base + "api/" + path); var builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10));
        if (cookie != null) builder.header("Cookie", cookie);
        if (!method.equals("GET") && !method.equals("HEAD")) builder.header("Origin", uri.getScheme() + "://" + uri.getAuthority()).header("Content-Type", "application/json;charset=utf-8");
        if (csrf != null) builder.header("X-Workshop-CSRF", csrf);
        if (revision != null) builder.header("If-Match", "\"" + revision + "\"");
        if (active != null) builder.header("X-Active-Revision", active);
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return browser.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    }
    static String text(HttpResponse<byte[]> response) { return new String(response.body(), StandardCharsets.UTF_8); }
    public static void main(String[] args) throws Exception {
        URI origin = RelayClient.origin(args[0], true); Path config = Files.createTempDirectory(Path.of("build").toAbsolutePath(), "relay-integration-");
        var identity = RelayIdentity.load(config); var files = new TemplateFiles(config, new TemplateJson());
        var allowed = new AtomicBoolean(true); var api = new AtomicReference<TemplateApi>();
        var factory = (RelayClient.ApiFactory)() -> new TemplateApi(origin.toString(), new AccessSessions(owner -> allowed.get()), files, identity.id());
        RelayClient relay = new RelayClient(origin, identity, factory, api::set);
        try {
            await(() -> api.get() != null); String base = api.get().workshopUrl();
            check(request(base, "templates", "GET", null, null, null, null, null).statusCode() == 401, "Anonymous listing blocked");
            check(request(base, "audio/relay_audio/sounds/spawn.ogg", "GET", null, null, null, null, null).statusCode() == 401, "Anonymous audio blocked");
            var owner = new AccessSessions.Owner(UUID.randomUUID(), "integration_owner"); String code = api.get().access().issue(owner);
            var logged = request(base, "login", "POST", null, null, "{\"code\":\"" + code + "\"}", null, null);
            check(logged.statusCode() == 200, "Minecraft-issued test link redeems over relay");
            String cookie = logged.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0], csrf = TemplateJson.parse(logged.body()).get("csrf").getAsString();
            check(cookie.startsWith((origin.getScheme().equals("https") ? "__Host-" : "") + "clockwork_workshop_" + identity.id() + "="), "Scoped session cookie");
            check(request(base, "login", "POST", null, null, "{\"code\":\"" + code + "\"}", null, null).statusCode() == 401, "One-use link replay blocked");
            String json = "{\"format_version\":1,\"health\":1000,\"addon\":{\"precise\":900719925474099312345,\"tiny\":1.2300e-120,\"padding\":\"" + "x".repeat(400000) + "\"}}";
            check(request(base, "drafts/relay_audio", "PUT", cookie, "bad", json, "none", "none").statusCode() == 403, "CSRF enforced over relay");
            var saved = request(base, "drafts/relay_audio", "PUT", cookie, csrf, json, "none", "none"); check(saved.statusCode() == 200, "Large draft save");
            String draft = TemplateJson.parse(saved.body()).get("revision").getAsString();
            draft=soundUploads(base,files,cookie,csrf,draft);
            var downloaded = request(base, "drafts/relay_audio", "GET", cookie, null, null, null, null);
            check(text(downloaded).equals(json), "Unknown JSON preserved in draft download");
            check(downloaded.headers().firstValue("X-Template-Revision").orElseThrow().equals(draft), "File revision survives CDN content encoding independently of ETag");
            check(request(base, "publish/relay_audio", "POST", cookie, csrf, "{}", draft, null).statusCode() == 200, "Publish");
            check(text(request(base, "active/relay_audio", "GET", cookie, null, null, null, null)).equals(json), "Unknown JSON preserved in active download");
            check(request(base, "drafts/relay_audio", "PUT", cookie, csrf, json, "none", "none").statusCode() == 409, "Stale overwrite rejected");
            Path sounds = config.resolve("clockwork-bosses/boss-templates/relay_audio/sounds"); Files.createDirectories(sounds);
            // Transport fixture only; the browser independently checks real decoding.
            byte[] audio = new byte[4*1024*1024]; System.arraycopy("OggS".getBytes(StandardCharsets.US_ASCII),0,audio,0,4);audio[5]=2;audio[26]=1;audio[27]=30;audio[28]=1;System.arraycopy("vorbis".getBytes(StandardCharsets.US_ASCII),0,audio,29,6);Files.write(sounds.resolve("spawn.ogg"),audio);
            var heard = request(base, "audio/relay_audio/sounds/spawn.ogg", "GET", cookie, null, null, null, null);
            check(heard.statusCode() == 200 && Arrays.equals(heard.body(),audio), "Multi-chunk OGG download; status="+heard.statusCode()+" bytes="+heard.body().length);
            var head = request(base, "audio/relay_audio/sounds/spawn.ogg", "HEAD", cookie, null, null, null, null); check(head.statusCode() == 200 && head.body().length == 0 && head.headers().firstValue("Content-Length").orElseThrow().equals(Integer.toString(audio.length)), "Audio HEAD");
            check(request(base, "audio/relay_audio/sounds/missing.ogg", "GET", cookie, null, null, null, null).statusCode() == 404, "Missing audio");
            TemplateApi runningApi = api.get();
            String pendingCode = runningApi.access().issue(owner);
            // A valid same-identity replacement forces a real transport drop;
            // immediately close the test peer so the running client can recover.
            var replacement = browser.newWebSocketBuilder().header("Authorization","Bearer "+identity.secret()).buildAsync(
                URI.create((origin.getScheme().equals("https")?"wss":"ws")+"://"+origin.getRawAuthority()+"/relay/"+identity.id()+"/connect"),new WebSocket.Listener(){}).get(10,TimeUnit.SECONDS);
            try { await(()->api.get()==null); }
            finally { replacement.sendClose(1000,"Test transport drop").get(5,TimeUnit.SECONDS); }
            await(()->api.get()!=null);check(api.get()==runningApi,"Transport recovery retains only this running companion's API");
            check(request(base,"session","GET",cookie,null,null,null,null).statusCode()==200,"Transient transport drop preserves valid owner session");
            check(request(base,"login","POST",null,null,"{\"code\":\""+pendingCode+"\"}",null,null).statusCode()==200,"Unexpired one-use link survives transient drop");
            System.out.println("PASS: forced transport drop recovered without losing owner session or replaying a mutation.");
            if (args.length > 1) {
                int seconds = Integer.parseInt(args[1]); check(seconds >= 0 && seconds <= 600, "Bounded idle test duration");
                TemplateApi beforeIdle = api.get();
                System.out.println("Waiting " + seconds + " seconds for idle heartbeat/hibernation check.");
                TimeUnit.SECONDS.sleep(seconds);
                check(api.get() == beforeIdle, "Idle/recovery must retain the same running companion API");
                check(request(base,"session","GET",cookie,null,null,null,null).statusCode()==200,"Browser session survives idle heartbeats/hibernation");
                System.out.println("PASS: idle connection and authenticated session retained.");
            }
            var second = RelayIdentity.load(Files.createTempDirectory(config,"identity-")); var other = new AtomicReference<TemplateApi>();
            try (var isolated = new RelayClient(origin, second, () -> new TemplateApi(origin.toString(), new AccessSessions(who -> true), new TemplateFiles(Files.createTempDirectory(config,"other-"),new TemplateJson()),second.id()),other::set)) {
                await(() -> other.get() != null); check(request(other.get().workshopUrl(),"session","GET",cookie,null,null,null,null).statusCode() == 401,"Cross-server cookie cannot authenticate");
            }
            allowed.set(false); check(request(base,"session","GET",cookie,null,null,null,null).statusCode() == 401,"Minecraft owner revocation remains authoritative"); allowed.set(true);
            String oldCode = api.get().access().issue(owner); relay.close(); check(api.get() == null,"Disconnect disables command/login");
            check(request(base,"templates","GET",cookie,null,null,null,null).statusCode() == 503,"Server disconnect shown");
            relay = new RelayClient(origin,identity,factory,api::set);await(()->api.get()!=null);
            check(request(base,"session","GET",cookie,null,null,null,null).statusCode()==401,"Reconnect invalidates prior browser session");
            check(request(base,"login","POST",null,null,"{\"code\":\""+oldCode+"\"}",null,null).statusCode()==401,"Reconnect invalidates old command link");
            var reauth=request(base,"login","POST",null,null,"{\"code\":\""+api.get().access().issue(owner)+"\"}",null,null);
            check(reauth.statusCode()==200,"Fresh link works after reconnect"); String freshCookie=reauth.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0],freshCsrf=TemplateJson.parse(reauth.body()).get("csrf").getAsString();
            check(request(base,"logout","POST",freshCookie,freshCsrf,"{}",null,null).statusCode()==200,"Logout over relay");
            check(request(base,"audio/relay_audio/sounds/spawn.ogg","GET",freshCookie,null,null,null,null).statusCode()==401,"Logged-out audio blocked");
        } finally { relay.close(); browser.shutdownNow(); }
        await(() -> Thread.getAllStackTraces().keySet().stream().noneMatch(thread -> thread.isAlive() && thread.getName().startsWith("clockwork-relay-")));
        System.out.println("PASS: Java outbound websocket + Cloudflare runtime; one-use login, CSRF, exact large addon JSON, publish/download, 4 MiB OGG, missing files, cross-server isolation, revocation, reconnect invalidation, logout and no surviving relay threads");
    }
    static String soundUploads(String base,TemplateFiles files,String cookie,String csrf,String revision)throws Exception{
        byte[] sound=SoundUploadChecks.largeSilence();check(sound.length>TemplateJson.MAX_BYTES,"Sound fixture crosses JSON request limit");String path="draft-audio/relay_audio/sounds/uploaded.ogg";
        check(soundRequest(base,path,null,csrf,revision,"none",sound,false).statusCode()==401,"Unauthenticated streamed sound upload rejected before reading");
        check(soundRequest(base,path,cookie,"bad",revision,"none",sound,false).statusCode()==403,"Streamed upload CSRF enforced");
        var uploaded=soundRequest(base,path,cookie,csrf,revision,"none",sound,false);check(uploaded.statusCode()==200,"Real Java/Cloudflare credited upload: "+text(uploaded));revision=TemplateJson.parse(uploaded.body()).get("revision").getAsString();
        check(files.active("relay_audio").content()==null&&Arrays.equals(files.audio("drafts","relay_audio","sounds","uploaded.ogg"),sound),"Streamed upload creates only draft sound");
        check(soundRequest(base,path,cookie,csrf,revision,AccessSessions.digest(sound),SoundUploadChecks.silence(),false).statusCode()==400,"Relay requires overwrite confirmation");
        uploaded=soundRequest(base,path,cookie,csrf,revision,AccessSessions.digest(sound),SoundUploadChecks.silence(),true);check(uploaded.statusCode()==200,"Confirmed replacement over stream");revision=TemplateJson.parse(uploaded.body()).get("revision").getAsString();
        check(soundRequest(base,"draft-audio/relay_audio/voices/not-audio.ogg",cookie,csrf,revision,"none",new byte[]{1,2,3},false).statusCode()==400,"Actual content rejected over relay");
        check(request(base,"draft-audio/relay_audio","GET",cookie,null,null,null,null).statusCode()==200,"Authenticated upload metadata over relay");
        System.out.println("PASS: credited upload >512 KiB, authentication/CSRF, exact draft bytes, overwrite confirmation, content validation and current revision receipts over real Java/Cloudflare transport");return revision;
    }
    static HttpResponse<byte[]> soundRequest(String base,String path,String cookie,String csrf,String revision,String fileRevision,byte[] sound,boolean overwrite)throws Exception{
        URI uri=URI.create(base+"api/"+path);var builder=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(25)).header("Origin",uri.getScheme()+"://"+uri.getAuthority()).header("Content-Type","audio/ogg").header("X-Sound-Length",Integer.toString(sound.length)).header("X-Sound-Revision",fileRevision).header("If-Match","\""+revision+"\"").header("X-Workshop-CSRF",csrf);if(cookie!=null)builder.header("Cookie",cookie);if(overwrite)builder.header("X-Sound-Overwrite","1");return browser.send(builder.PUT(HttpRequest.BodyPublishers.ofByteArray(sound)).build(),HttpResponse.BodyHandlers.ofByteArray());
    }
}
