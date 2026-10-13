package clockwork.web;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Isolated regressions for revocation races, bounded permission work and disk failures. */
public final class TighteningChecks {
    private static final AccessSessions.Owner OWNER = new AccessSessions.Owner(UUID.randomUUID(), "fixture_owner");
    private static final byte[] FIRST = "{\"format_version\":1,\"health\":1000,\"addon\":{\"precise\":9007199254740993123456789}}".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SECOND = "{\"format_version\":1,\"health\":1234}".getBytes(StandardCharsets.UTF_8);
    private static void require(boolean ok, String name) {if(!ok)throw new AssertionError(name);}
    private static void denied(Callable<?> action, String name) throws Exception {
        try {action.call();throw new AssertionError(name);} catch(java.io.IOException expected) { }
    }
    public static void main(String[] args) throws Exception {
        var queries=new AtomicInteger();var access=new AccessSessions(owner->{queries.incrementAndGet();return true;});
        var a=access.redeem(access.issue(OWNER));var b=access.redeem(access.issue(OWNER));queries.set(0);
        for(int i=0;i<60;i++)require(access.authenticate(i%2==0?a.token():b.token())!=null,"admitted session");
        for(int i=0;i<1000;i++) {
            try {access.authenticate(a.token());throw new AssertionError("over-budget accepted");}catch(AccessSessions.RateLimited expected) { }
            require(access.authenticate("invalid-"+i)==null,"invalid token");
        }
        require(queries.get()==60,"over-budget/invalid requests never reach Minecraft permission guard");
        require(access.current(a.token())!=null,"logout remains possible at limit");access.logout(a.token());require(access.current(a.token())==null,"logout at limit");
        for(String operation:List.of("revoke","close","logout")) {
            var block=new AtomicBoolean();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
            var racing=new AccessSessions(owner->{if(block.get()){entered.countDown();try {release.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();return false;}}return true;});
            String code=racing.issue(OWNER);var login=operation.equals("logout")?racing.redeem(code):null;block.set(true);
            try(var executor=Executors.newSingleThreadExecutor()) {
                Future<?> result=executor.submit(()->operation.equals("logout")?racing.authenticate(login.token()):racing.redeem(code));
                require(entered.await(5,TimeUnit.SECONDS),"race reached guard");
                if(operation.equals("revoke"))racing.revoke(OWNER.id());else if(operation.equals("close"))racing.clear();else racing.logout(login.token());
                release.countDown();require(result.get(5,TimeUnit.SECONDS)==null,"no access resurrection after "+operation);
            } finally {release.countDown();}
        }
        Path config=Files.createTempDirectory(Path.of("build").toAbsolutePath(),"tightening-");
        var files=new TemplateFiles(config,new TemplateJson());
        try(var socket=new java.net.ServerSocket(0,1,java.net.InetAddress.getLoopbackAddress())) {
            int port=socket.getLocalPort();socket.close();String origin="http://127.0.0.1:"+port;
            try(var server=new TestApiServer("127.0.0.1",port,new TemplateApi(origin,access,files,TestApiServer.ID));var http=java.net.http.HttpClient.newHttpClient()) {
                var get=java.net.http.HttpRequest.newBuilder(java.net.URI.create(origin+"/api/templates")).header("Cookie",TestApiServer.COOKIE+b.token()).build();
                for(int i=0;i<20;i++)require(http.send(get,java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode()==429,"real HTTP flood throttled");
                require(queries.get()==60,"HTTP flood never schedules guard queries");
                var logout=java.net.http.HttpRequest.newBuilder(java.net.URI.create(origin+"/api/logout")).header("Cookie",TestApiServer.COOKIE+b.token()).header("Origin",origin).header("X-Workshop-CSRF",b.session().csrf()).header("Content-Type","application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString("{}")).build();
                require(http.send(logout,java.net.http.HttpResponse.BodyHandlers.discarding()).statusCode()==200,"real logout works while throttled");
                require(queries.get()==60,"logout does not query Minecraft");
            }
        }
        var draft=files.save("sample",FIRST,"none","none");
        require(files.save("sample",FIRST,"none","none").revision().equals(draft.revision()),"idempotent save");
        var list=files.listing();require(files.listing()==list,"hot listing reuses bounded metadata");
        String active=files.publish("sample",draft.revision());require(files.publish("sample",draft.revision()).equals(active),"idempotent publish");
        require(files.draft("sample").baseRevision().equals(active),"draft can be reopened after publish");
        require(files.listing().getFirst().activeRevision().equals(active),"publish invalidates listing");
        var changed=files.save("sample",SECOND,draft.revision(),active);
        Path activeFile=config.resolve("clockwork-bosses/boss-templates/sample/template.json"), backups=config.resolve("clockwork-bosses-web/backups");
        for(int i=0;i<32;i++)Files.writeString(backups.resolve("quota-"+i+".json"),"{}");
        denied(()->files.publish("sample",changed.revision()),"backup quota blocks replacement");
        require(Arrays.equals(Files.readAllBytes(activeFile),FIRST),"quota failure preserved active bytes");
        // A separate store has a non-directory backup target, simulating an IO failure.
        Path brokenConfig=Files.createTempDirectory(Path.of("build").toAbsolutePath(),"broken-backup-");
        var broken=new TemplateFiles(brokenConfig,new TemplateJson());var initial=broken.save("sample",FIRST,"none","none");String initialActive=broken.publish("sample",initial.revision());
        var staged=broken.save("sample",SECOND,initial.revision(),initialActive);Path badBackup=brokenConfig.resolve("clockwork-bosses-web/backups");Files.delete(badBackup);Files.writeString(badBackup,"blocked");
        denied(()->broken.publish("sample",staged.revision()),"failed backup blocks replacement");
        require(Arrays.equals(broken.active("sample").content(),FIRST),"backup IO failure preserved active");
        Path corrupt=config.resolve("clockwork-bosses-web/drafts/corrupt.draft");Files.writeString(corrupt,"content=\\uZZZZ\nbase_revision=none\n");
        denied(()->files.draft("corrupt"),"corrupt properties become controlled IO error");
        Files.writeString(corrupt,"content=\nbase_revision=none\n");denied(()->files.draft("corrupt"),"empty stored draft rejected");
        Files.delete(corrupt);
        var concurrent=files.save("race",FIRST,"none","none");
        try(var pool=Executors.newFixedThreadPool(2)) {
            var gate=new CountDownLatch(1);var successes=new AtomicInteger();var conflicts=new AtomicInteger();
            List<Future<?>> writes=new ArrayList<>();
            for(int i=0;i<2;i++){final byte[] content=i==0?SECOND:"{\"format_version\":1,\"health\":2345}".getBytes(StandardCharsets.UTF_8);writes.add(pool.submit(()->{try{gate.await();files.save("race",content,concurrent.revision(),"none");successes.incrementAndGet();}catch(TemplateFiles.Conflict conflict){conflicts.incrementAndGet();}catch(Exception e){throw new RuntimeException(e);}}));}
            gate.countDown();for(var write:writes)write.get(5,TimeUnit.SECONDS);require(successes.get()==1&&conflicts.get()==1,"concurrent writes have one winner");
        }
        try(var stream=Files.walk(config)){require(stream.noneMatch(p->p.getFileName().toString().endsWith(".tmp")),"no temporary-file leaks");}
        // Failed local create cannot silently clobber an existing directory.
        Files.createDirectory(config.resolve("clockwork-bosses-web/drafts/blocked.draft"));
        denied(()->files.save("blocked",FIRST,"none","none"),"unsafe write target rejected");
        require(Files.isDirectory(config.resolve("clockwork-bosses-web/drafts/blocked.draft")),"failed save preserved target");
        var stopped=new PlayerSkins();stopped.clear();denied(()->stopped.fetch("ClockworkSpirit"),"closed lookup cannot start");
        System.out.println("PASS: permission budgets, revoke/close/logout races, repeat save/publish, refreshed baselines, cached metadata, concurrent writes, failed backups, corrupt drafts, temporary cleanup and closed skin lookup");
    }
}
