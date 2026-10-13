package clockwork.web;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;

/** Real HTTP reads, links and cancellation against disposable files only. */
public final class AudioChecks {
    private static void require(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    private static HttpResponse<byte[]> request(HttpClient client, String origin, String cookie, String method, String path, Map<String,String> headers) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create(origin+path)).timeout(Duration.ofSeconds(5));
        if (cookie!=null) builder.header("Cookie",cookie);
        headers.forEach(builder::header);
        return client.send(builder.method(method,HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofByteArray());
    }
    static void directoryLink(Path link, Path target) throws Exception {
        try { Files.createSymbolicLink(link,target); }
        catch (FileSystemException denied) {
            if (!System.getProperty("os.name").startsWith("Windows")) throw denied;
            // Windows directory junctions do not require granting symlink privileges.
            var process=new ProcessBuilder("cmd.exe","/d","/c","mklink","/J",link.toString(),target.toString()).redirectErrorStream(true).start();
            if (!process.waitFor(5,java.util.concurrent.TimeUnit.SECONDS)) {process.destroyForcibly();throw new AssertionError("junction creation timed out");}
            require(process.exitValue()==0,"create disposable junction: "+new String(process.getInputStream().readNBytes(1024),StandardCharsets.UTF_8));
        }
    }
    public static void main(String[] args) throws Exception {
        Path root=Files.createTempDirectory(Path.of("build").toAbsolutePath(),"audio-check-"), config=root.resolve("config");
        var now=new AtomicLong(System.currentTimeMillis());
        Clock clock=new Clock(){public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return Instant.ofEpochMilli(now.get());}};
        var allowed=new AtomicBoolean(true); var rejectRecheck=new AtomicBoolean(); var checks=new AtomicInteger();
        var access=new AccessSessions(owner->allowed.get()&&(!rejectRecheck.get()||checks.incrementAndGet()<2),clock);
        var files=new TemplateFiles(config,new TemplateJson());
        Path base=config.resolve("clockwork-bosses/boss-templates"), sounds=base.resolve("test/sounds");
        Files.createDirectories(sounds.resolve("voice-lines"));
        byte[] ogg=new byte[58];System.arraycopy("OggS".getBytes(StandardCharsets.US_ASCII),0,ogg,0,4);ogg[5]=2;ogg[26]=1;ogg[27]=30;ogg[28]=1;System.arraycopy("vorbis".getBytes(StandardCharsets.US_ASCII),0,ogg,29,6);
        Files.write(sounds.resolve("spawn.ogg"),ogg);byte[] voice=ogg.clone();voice[57]=43;Files.write(sounds.resolve("voice-lines/line.ogg"),voice);
        byte[] video=ogg.clone();video[28]=(byte)0x80;System.arraycopy("theora".getBytes(StandardCharsets.US_ASCII),0,video,29,6);Files.write(sounds.resolve("video.ogg"),video);
        Files.writeString(sounds.resolve("text.ogg"),"private non-OGG bytes");Files.createDirectory(sounds.resolve("folder.ogg"));
        Files.writeString(sounds.resolve("private.json"),"private config");
        Path oversized=sounds.resolve("large.ogg");
        try(var channel=java.nio.channels.FileChannel.open(oversized,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) {
            channel.write(java.nio.ByteBuffer.wrap(ogg));channel.position(TemplateFiles.AUDIO_MAX_BYTES);channel.write(java.nio.ByteBuffer.wrap(new byte[1]));
        }
        Path outside=root.resolve("outside");Files.createDirectories(outside.resolve("sounds"));Files.write(outside.resolve("spawn.ogg"),ogg);Files.write(outside.resolve("sounds/spawn.ogg"),ogg);
        directoryLink(base.resolve("template_link"),outside);
        Files.createDirectories(base.resolve("parent_link"));directoryLink(base.resolve("parent_link/sounds"),outside);
        Files.createDirectories(base.resolve("voice_link/sounds"));directoryLink(base.resolve("voice_link/sounds/voice-lines"),outside);
        boolean leafLink=true;
        try { Files.createSymbolicLink(sounds.resolve("linked.ogg"),outside.resolve("spawn.ogg")); }
        catch (FileSystemException denied) {leafLink=false;System.out.println("NOTE: Windows refused creating a file symlink; real linked-folder/junction escape tests still run.");}
        int port;try(var socket=new java.net.ServerSocket(0,1,InetAddress.getLoopbackAddress())) {port=socket.getLocalPort();}
        String origin="http://127.0.0.1:"+port;
        try(var server=new TestApiServer("127.0.0.1",port,new TemplateApi(origin,access,files,TestApiServer.ID));var client=HttpClient.newHttpClient()) {
            String path="/api/audio/test/sounds/spawn.ogg";
            require(request(client,origin,null,"GET",path,Map.of()).statusCode()==401,"anonymous audio denied");
            require(request(client,origin,null,"HEAD",path,Map.of()).statusCode()==401,"anonymous HEAD denied");
            require(request(client,origin,TestApiServer.COOKIE+"invalid","GET",path,Map.of()).statusCode()==401,"invalid credential denied");
            var owner=new AccessSessions.Owner(UUID.randomUUID(),"audio_owner");var login=access.redeem(access.issue(owner));String cookie=TestApiServer.COOKIE+login.token();
            var reply=request(client,origin,cookie,"GET",path,Map.of());
            require(reply.statusCode()==200&&Arrays.equals(reply.body(),ogg),"exact authenticated OGG bytes");
            require(reply.headers().firstValue("Content-Type").orElse("").equals("audio/ogg"),"audio MIME");
            require(reply.headers().firstValue("Cache-Control").orElse("").equals("no-store")&&reply.headers().firstValue("X-Content-Type-Options").orElse("").equals("nosniff"),"private audio response");
            require(reply.headers().firstValue("Content-Disposition").orElse("").equals("inline; filename=\"spawn.ogg\""),"safe inline filename");
            require(reply.headers().firstValue("Access-Control-Allow-Origin").isEmpty(),"no audio CORS grant");
            var head=request(client,origin,cookie,"HEAD",path,Map.of());require(head.statusCode()==200&&head.body().length==0&&head.headers().firstValue("Content-Length").equals(reply.headers().firstValue("Content-Length")),"HEAD exact length without body");
            require(Arrays.equals(request(client,origin,cookie,"GET","/api/audio/test/voices/line.ogg",Map.of()).body(),voice),"fixed voice-lines folder");
            require(request(client,origin,cookie,"GET","/api/audio/test/sounds/line.ogg",Map.of()).statusCode()==404,"voice file not aliased into sounds");
            for(String bad:List.of("/api/audio/%2e%2e/sounds/spawn.ogg","/api/audio/test/sounds/%2e%2e%2fspawn.ogg","/api/audio/test/sounds/%5cspawn.ogg","/api/audio/test/sounds/spawn.ogg%00","/api/audio/test/sounds/private.json","/api/audio/test/sounds/UPPER.ogg","/api/audio/test/sounds/con.ogg","/api/audio/test/other/spawn.ogg","/api/audio/test/sounds/text.ogg","/api/audio/test/sounds/video.ogg","/api/audio/test/sounds/folder.ogg","/api/audio/test/sounds/large.ogg","/api/audio/template_link/sounds/spawn.ogg","/api/audio/parent_link/sounds/spawn.ogg","/api/audio/voice_link/voices/spawn.ogg")) {
                var denied=request(client,origin,cookie,"GET",bad,Map.of());require(denied.statusCode()==400,"unsafe audio denied: "+bad);
                require(!new String(denied.body(),StandardCharsets.UTF_8).contains(root.toString()),"no private filesystem path in errors");
            }
            if(leafLink)require(request(client,origin,cookie,"GET","/api/audio/test/sounds/linked.ogg",Map.of()).statusCode()==400,"file symlink denied");
            require(request(client,origin,cookie,"GET","/api/audio/test/sounds/sub/spawn.ogg",Map.of()).statusCode()==405,"nested audio path rejected");
            var missing=request(client,origin,cookie,"GET","/api/audio/test/sounds/missing.ogg",Map.of());require(missing.statusCode()==404,"missing audio status");
            require(new String(missing.body(),StandardCharsets.UTF_8).contains("missing"),"missing file explanation");
            require(request(client,origin,cookie,"GET",path,Map.of("Origin","https://evil.example")).statusCode()==403,"foreign origin denied");
            try(var socket=new java.net.Socket("127.0.0.1",port)) {
                socket.setSoTimeout(3000);socket.getOutputStream().write(("GET "+path+" HTTP/1.1\r\nHost: evil.example\r\nCookie: "+cookie+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                require(new String(socket.getInputStream().readAllBytes(),StandardCharsets.US_ASCII).startsWith("HTTP/1.1 403"),"spoofed Host denied");
            }
            for(String method:List.of("PUT","POST","DELETE"))require(request(client,origin,cookie,method,path,Map.of("Origin",origin,"X-Workshop-CSRF",login.session().csrf())).statusCode()==405,"read-only audio: "+method);
            require(request(client,origin,null,"GET","/sounds/spawn.ogg",Map.of()).statusCode()==404,"no public audio route");
            Path renamed=sounds.resolve("renamed.ogg");Files.move(sounds.resolve("spawn.ogg"),renamed);Files.move(renamed,sounds.resolve("spawn.ogg"));
            // Abort a large response and confirm its worker and file handle recover.
            try(var channel=java.nio.channels.FileChannel.open(oversized,StandardOpenOption.WRITE)) {channel.truncate(TemplateFiles.AUDIO_MAX_BYTES);}
            var stream=client.send(HttpRequest.newBuilder(URI.create(origin+"/api/audio/test/sounds/large.ogg")).header("Cookie",cookie).timeout(Duration.ofSeconds(5)).build(),HttpResponse.BodyHandlers.ofInputStream());
            require(stream.statusCode()==200,"bounded large audio read");stream.body().read();stream.body().close();
            require(request(client,origin,null,"GET","/api/status",Map.of()).statusCode()==200,"HTTP recovers after canceled playback download");
            rejectRecheck.set(true);checks.set(0);require(request(client,origin,cookie,"GET",path,Map.of()).statusCode()==401,"revoked during read denied before audio release");rejectRecheck.set(false);
            login=access.redeem(access.issue(owner));cookie=TestApiServer.COOKIE+login.token();
            allowed.set(false);require(request(client,origin,cookie,"GET",path,Map.of()).statusCode()==401,"live permission revocation denied");allowed.set(true);
            login=access.redeem(access.issue(owner));cookie=TestApiServer.COOKIE+login.token();
            require(request(client,origin,cookie,"POST","/api/logout",Map.of("Origin",origin,"X-Workshop-CSRF",login.session().csrf())).statusCode()==200,"audio owner logout");
            require(request(client,origin,cookie,"GET",path,Map.of()).statusCode()==401,"logged out audio denied");
            login=access.redeem(access.issue(owner));cookie=TestApiServer.COOKIE+login.token();now.addAndGet(1_800_001);
            require(request(client,origin,cookie,"GET",path,Map.of()).statusCode()==401,"expired audio session denied");
        }
        try(var rebound=new java.net.ServerSocket(port,1,InetAddress.getLoopbackAddress())) {require(rebound.isBound(),"audio listener released");}
        // Executor termination is signaled just before the final Thread.run exits.
        // Give that exit a bounded grace period, then inspect any surviving stack.
        long deadline=System.nanoTime()+500_000_000L;
        while(System.nanoTime()<deadline && Thread.getAllStackTraces().keySet().stream().anyMatch(t->t.isAlive()&&t.getName().equals("clockwork-test-api"))) Thread.sleep(10);
        var surviving=Thread.getAllStackTraces().entrySet().stream().filter(e->e.getKey().isAlive()&&e.getKey().getName().equals("clockwork-test-api")).toList();
        require(surviving.isEmpty(),"audio workers stopped: "+surviving.stream().map(e->Arrays.toString(e.getValue())).toList());
        System.out.println("PASS: authenticated OGG/HEAD, fixed sound/voice folders, traversal/link/device/type/size rejection, origin/Host, missing files, read-only methods, canceled transfer, revocation/logout/expiry and cleanup");
    }
}
