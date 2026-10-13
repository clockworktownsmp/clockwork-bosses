package clockwork.web;

import java.net.URI;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;

/** Test-only issuer, excluded from the release jar. Production links require Minecraft. */
public final class RelayBrowserFixture {
    public static void main(String[] args) throws Exception {
        URI origin = RelayClient.origin(args[0],true);
        Path config=Files.createTempDirectory(Path.of("build").toAbsolutePath(),"relay-browser-");
        if(args.length>2) {
            Path resources=Path.of(args[2]);
            for(String preset:PresetMedia.PRESETS) {
                Path folder=config.resolve("clockwork-bosses/boss-presets").resolve(preset);
                for(String category:PresetMedia.FOLDERS)Files.createDirectories(folder.resolve(category));
                Files.copy(resources.resolve(preset.equals("boss-sounds-example")?preset+"/template.json":preset+".json"),folder.resolve("template.json"));
            }
            for(String relative:List.of("spawn.ogg","death.ogg","phase2.ogg","phase3.ogg","phase4.ogg","voice-lines/line1.ogg","voice-lines/line2.ogg","voice-lines/line3.ogg","voice-lines/line4.ogg"))
                Files.copy(resources.resolve("boss-sounds-example/sounds/"+relative),config.resolve("clockwork-bosses/boss-presets/boss-sounds-example/sounds/"+relative));
        }
        var files=new TemplateFiles(config,new TemplateJson());
        String json="{\"format_version\":1,\"health\":1000,\"sounds\":{\"spawn\":{\"file\":\"spawn.ogg\",\"volume\":0.25,\"pitch\":1.5},\"death\":{\"file\":\"missing.ogg\",\"volume\":0.75,\"pitch\":1}},\"music\":{\"file\":\"music.ogg\",\"volume\":0.4},\"voice_lines\":{\"volume\":0.6},\"addon_audio\":{\"precise\":900719925474099312345,\"tiny\":1.2300e-120}}";
        var draft=files.save("relay_audio",json.getBytes(StandardCharsets.UTF_8),"none","none");files.publish("relay_audio",draft.revision());
        Path sounds=config.resolve("clockwork-bosses/boss-templates/relay_audio/sounds");Files.createDirectories(sounds.resolve("voice-lines"));
        String audioPath=args.length>1?args[1]:System.getenv("CLOCKWORK_TEST_OGG");
        if(audioPath==null)throw new IllegalArgumentException("Set CLOCKWORK_TEST_OGG or pass an Ogg Vorbis/Opus file as the second argument for browser playback tests");
        byte[] audio=Files.readAllBytes(Path.of(audioPath));
        for(String name:List.of("spawn.ogg","music.ogg","voice-lines/line.ogg"))Files.write(sounds.resolve(name),audio);
        var identity=RelayIdentity.load(config);var current=new AtomicReference<TemplateApi>();var allowed=new AtomicBoolean(true);
        var factory=(RelayClient.ApiFactory)()->new TemplateApi(origin.toString(),new AccessSessions(owner->allowed.get()),files,identity.id());
        var owner=new AccessSessions.Owner(UUID.randomUUID(),"isolated_fixture");
        RelayClient relay=new RelayClient(origin,identity,factory,current::set);
        try(var scanner=new Scanner(System.in)){
            RelayIntegrationChecks.await(()->current.get()!=null);printLink(current.get(),owner);
            System.out.println("Fixture files: "+config);
            while(scanner.hasNextLine()){
                String command=scanner.nextLine().trim();if(command.equals("stop"))break;
                if(command.equals("link")){if(current.get()==null)System.out.println("Disconnected");else printLink(current.get(),owner);}
                if(command.equals("revoke")){allowed.set(false);System.out.println("Fixture owner revoked.");}
                if(command.equals("drop")){relay.close();System.out.println("Fixture disconnected; old links and sessions invalidated.");}
                if(command.equals("reconnect")){relay.close();relay=new RelayClient(origin,identity,factory,current::set);RelayIntegrationChecks.await(()->current.get()!=null);printLink(current.get(),owner);}
            }
        }finally{relay.close();}
        System.out.println("Isolated relay browser fixture stopped.");
    }
    private static void printLink(TemplateApi api,AccessSessions.Owner owner){System.out.println("Isolated fixture login: "+api.workshopUrl()+"#login="+api.access().issue(owner));}
}
