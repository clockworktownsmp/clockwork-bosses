package clockwork.web;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import com.google.gson.*;

/** Generated silence only. Real HTTP, bounded decoder, draft snapshots, owned
 * rollback/recovery and hostile filesystem checks use disposable storage. */
public final class SoundUploadChecks {
    static final byte[] JSON="{\"format_version\":1,\"health\":1000,\"addon\":{\"exact\":900719925474099312345,\"tiny\":1.2300e-120}}".getBytes(StandardCharsets.UTF_8);
    static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
    interface Attempt{void run()throws Exception;}
    static void denied(Attempt action,String why)throws Exception{try{action.run();throw new AssertionError(why);}catch(IOException expected){}}
    public static byte[] silence()throws IOException{try(var input=SoundUploadChecks.class.getResourceAsStream("/silent-vorbis.b64")){return Base64.getMimeDecoder().decode(input.readAllBytes());}}
    static byte[] largeSilence()throws IOException{
        byte[] source=silence();int offset=0;for(int p=0;p<3;p++){int count=source[offset+26]&255,length=27+count;for(int i=0;i<count;i++)length+=source[offset+27+i]&255;offset+=length;}
        var out=new ByteArrayOutputStream();out.write(source,0,offset);int shortBlock=1<<(source[28+28]&15);
        for(int seq=3;seq<603;seq++){byte[] page=new byte[27+5+1024];System.arraycopy("OggS".getBytes(),0,page,0,4);page[5]=(byte)(seq==602?4:0);var buffer=ByteBuffer.wrap(page).order(ByteOrder.LITTLE_ENDIAN);buffer.putLong(6,seq==602?599L*shortBlock/2:0).putInt(14,ByteBuffer.wrap(source).order(ByteOrder.LITTLE_ENDIAN).getInt(14)).putInt(18,seq);page[26]=5;Arrays.fill(page,27,31,(byte)255);page[31]=4;int crc=0;for(byte value:page){crc^=(value&255)<<24;for(int j=0;j<8;j++)crc=(crc<<1)^((crc<0)?0x04c11db7:0);}buffer.putInt(22,crc);out.write(page);}
        return out.toByteArray();
    }
    static byte[] variant(byte[] original){byte[] bytes=original.clone();for(int p=0;p<bytes.length;){int count=bytes[p+26]&255,length=27+count;for(int i=0;i<count;i++)length+=bytes[p+27+i]&255;ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(p+14,ByteBuffer.wrap(original).order(ByteOrder.LITTLE_ENDIAN).getInt(p+14)+1).putInt(p+22,0);int crc=0;for(int i=p;i<p+length;i++){crc^=(bytes[i]&255)<<24;for(int j=0;j<8;j++)crc=(crc<<1)^((crc<0)?0x04c11db7:0);}ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(p+22,crc);p+=length;}return bytes;}
    public static void main(String[] args)throws Exception{
        byte[] a=silence(),b=variant(a);check(UploadedAudio.validate(a).seconds()>0,"generated silence fully decodes");UploadedAudio.validate(b);UploadedAudio.validate(a,true);UploadedAudio.validate(largeSilence());
        for(byte[] invalid:List.of(new byte[0],"OggS fake audio".getBytes(),Arrays.copyOf(a,a.length-1),Arrays.copyOf(a,58))){denied(()->UploadedAudio.validate(invalid),"invalid/truncated OGG accepted");}
        byte[] corrupt=a.clone();corrupt[corrupt.length-1]^=3;denied(()->UploadedAudio.validate(corrupt),"bad page checksum accepted");
        byte[] extra=Arrays.copyOf(a,a.length+1);denied(()->UploadedAudio.validate(extra),"trailing bytes accepted");
        denied(()->UploadedAudio.validate(new byte[UploadedAudio.CLIP_MAX_BYTES+1]),"clip size bound");denied(()->UploadedAudio.validate(new byte[UploadedAudio.MUSIC_MAX_BYTES+1],true),"music size bound");
        // A valid marker with an excessive codebook entry count cannot allocate
        // attacker-sized decoder arrays. This preflight runs before JOrbis.
        byte[] bomb=new byte[32];bomb[0]=5;System.arraycopy("vorbis".getBytes(),0,bomb,1,6);bomb[8]=0x42;bomb[9]=0x43;bomb[10]=0x56;bomb[11]=1;bomb[13]=(byte)255;bomb[14]=(byte)255;bomb[15]=(byte)255;denied(()->VorbisSetup.check(bomb,2),"codebook allocation bomb accepted");
        VorbisSetup.check(residueSetup(1,2),1);denied(()->VorbisSetup.check(residueSetup(32,64),1),"exponential residue lookup allocation accepted");
        Thread.currentThread().interrupt();try{denied(()->UploadedAudio.validate(a),"interrupted validator accepted");}finally{Thread.interrupted();}
        Path config=Files.createTempDirectory(Path.of("build").toAbsolutePath(),"sound-upload-check-");TemplateFiles files=new TemplateFiles(config,new TemplateJson());
        var draft=files.save("test",JSON,"none","none");draft=files.uploadSound("test","sounds","spawn.ogg",draft.revision(),"none",a,()->{});draft=files.uploadSound("test","voices","line.ogg",draft.revision(),"none",a,()->{});
        check(files.active("test").content()==null,"draft uploads leave active folder absent");check(files.sounds("drafts","test").uploadedSounds().equals(List.of("spawn.ogg")),"uploaded source labels");
        byte[] edit=new String(JSON,StandardCharsets.UTF_8).replace("1000","1001").getBytes(StandardCharsets.UTF_8);draft=files.save("test",edit,draft.revision(),"none","none");check(Arrays.equals(files.audio("drafts","test","voices","line.ogg"),a),"Save/autosave preserves uploaded voices");
        var clone=files.save("copy",edit,"none","none","none","test:"+draft.revision(),()->{});files.publish("copy",clone.revision());check(Arrays.equals(files.audio("copy","sounds","spawn.ogg"),a),"renamed draft retains uploads and publishes");
        files.publish("test",draft.revision());check(Arrays.equals(files.active("test").content(),edit),"publish retains exact addon JSON tokens");
        var activeClone=files.save("copy_active",edit,"none","none","none","active:test:"+files.active("test").revision(),()->{});files.publish("copy_active",activeClone.revision());check(Arrays.equals(files.audio("copy_active","voices","line.ogg"),a),"renaming an active template retains published uploads");
        String activeRevision=files.active("test").revision();draft=files.save("test",edit,draft.revision(),activeRevision);draft=files.uploadSound("test","sounds","spawn.ogg",draft.revision(),AccessSessions.digest(a),b,()->{});
        check(Arrays.equals(files.audio("test","sounds","spawn.ogg"),a),"draft overwrite cannot change active playback");
        String revision=draft.revision();denied(()->files.uploadSound("test","sounds","spawn.ogg",revision,AccessSessions.digest(a),a,()->{}),"stale sound hash accepted");
        AtomicInteger calls=new AtomicInteger();denied(()->files.publish("test",revision,()->{if(calls.incrementAndGet()==2)throw new IOException("permission revoked before config commit");}),"revoked publication accepted");check(Arrays.equals(files.audio("test","sounds","spawn.ogg"),a)&&files.active("test").revision().equals(activeRevision),"failed publish restores media and config");
        files.publish("test",revision);check(Arrays.equals(files.audio("test","sounds","spawn.ogg"),b),"same-JSON publication replaces confirmed audio");files.publish("test",revision);check(Arrays.equals(files.audio("test","sounds","spawn.ogg"),b),"lost reply publish is idempotent");
        Path backupRoot=config.resolve("clockwork-bosses-web/sound-backups");try(var backups=Files.list(backupRoot)){check(backups.anyMatch(path->{try{return Arrays.equals(Files.readAllBytes(path),a);}catch(IOException failed){return false;}}),"original sound backup retained");}
        draft=files.save("test",edit,files.draft("test").revision(),files.active("test").revision());String stoppedRevision=draft.revision();calls.set(0);
        denied(()->files.uploadSound("test","voices","new.ogg",stoppedRevision,"none",a,()->{if(calls.incrementAndGet()==2)throw new IOException("interrupted manifest commit");}),"failed sound manifest accepted");check(files.draft("test").revision().equals(stoppedRevision)&&!files.sounds("drafts","test").voices().contains("new.ogg"),"interrupted upload rolls back new snapshot");
        recovery(config,files,a,b,edit);permissions(files,a);quotas(config,files,a);
        try(var paths=Files.walk(config)){check(paths.noneMatch(path->path.getFileName().toString().startsWith(".workshop-")),"no private temporary file or staging survives");}
        System.out.println("PASS: full Vorbis decode/CRC/EOS, invalid/oversized audio and allocation guards; draft-only sounds/voices; exact JSON, save/rename preservation, overwrite backups, stale hashes, permission rollback, same-config publish, journal recovery, authenticated HTTP/CSRF/revocation/rate limits, path/junction confinement, total quota and interruption cleanup");
    }
    static byte[] residueSetup(int dimension,int classifications){
        class Bits{byte[] data=new byte[1024];int position;void put(int value,int count){for(int i=0;i<count;i++,position++)if(((value>>>i)&1)!=0)data[position/8]|=(byte)(1<<(position%8));}byte[] finish(){return Arrays.copyOf(data,(position+7)/8);}}
        Bits bits=new Bits();bits.put(5,8);for(byte value:"vorbis".getBytes(StandardCharsets.US_ASCII))bits.put(value,8);
        bits.put(0,8);bits.put(0x564342,24);bits.put(dimension,16);bits.put(2,24);bits.put(0,1);bits.put(0,1);bits.put(0,5);bits.put(0,5);bits.put(0,4); // one two-entry phrasebook
        bits.put(0,6);bits.put(0,16);bits.put(0,6);bits.put(1,16);bits.put(0,5);bits.put(0,2);bits.put(0,4); // time and floor
        bits.put(0,6);bits.put(0,16);bits.put(0,24);bits.put(64,24);bits.put(0,24);bits.put(classifications-1,6);bits.put(0,8);
        for(int i=0;i<classifications;i++)bits.put(0,4); // no stage books
        bits.put(0,6);bits.put(0,16);bits.put(0,1);bits.put(0,1);bits.put(0,2);bits.put(0,8);bits.put(0,8);bits.put(0,8); // mapping
        bits.put(0,6);bits.put(0,1);bits.put(0,16);bits.put(0,16);bits.put(0,8);bits.put(1,1);return bits.finish();
    }
    static void recovery(Path config,TemplateFiles files,byte[] a,byte[] b,byte[] json)throws Exception{
        var saved=files.save("test",json,files.draft("test").revision(),files.active("test").revision());saved=files.uploadSound("test","sounds","spawn.ogg",saved.revision(),AccessSessions.digest(b),a,()->{});
        Path active=config.resolve("clockwork-bosses/boss-templates"),journals=config.resolve("clockwork-bosses-web/sound-publish-journals");Properties props=new Properties();
        // Close the crash fixture's read handle before the next upload atomically
        // replaces this draft. Windows correctly refuses replacing an open file.
        try(var input=Files.newInputStream(config.resolve("clockwork-bosses-web/drafts/test.draft"))){props.load(input);}
        try(var media=PresetMedia.prepareFrom(active,files.bundle("test",saved.revision()))){var transaction=PublishedSounds.prepare(active,journals,"test",json,json,media,DraftSounds.read(props),new SoundBackups(config.resolve("clockwork-bosses-web/sound-backups")));transaction.install();media.commit();} // simulate process loss before journal commit
        check(Arrays.equals(files.audio("test","sounds","spawn.ogg"),a),"crash fixture wrote uncommitted replacement");var recovered=new TemplateFiles(config,new TemplateJson());check(Arrays.equals(recovered.audio("test","sounds","spawn.ogg"),b),"startup restores previous audio from journal");
        Path sound=active.resolve("test/sounds/spawn.ogg");Files.write(sound,variant(b));String expected=saved.revision();denied(()->recovered.publish("test",expected),"external active sound edit overwritten");Files.write(sound,b);
        Path bundle=files.bundle("test",saved.revision()),folder=bundle.resolve("sounds/voice-lines"),moved=folder.resolveSibling("kept-voices");Files.move(folder,moved);AudioChecks.directoryLink(folder,moved);
        try{denied(()->files.checkUpload("test","voices","escape.ogg",expected,"none"),"linked upload folder accepted");}finally{Files.delete(folder);Files.move(moved,folder);}
    }
    static void quotas(Path config,TemplateFiles files,byte[] a)throws Exception{
        var saved=files.draft("test");Path extra=config.resolve("clockwork-bosses-web/backups/quota-test.bin");try(var out=java.nio.channels.FileChannel.open(extra,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){out.position(MediaQuota.MAX_STORAGE);out.write(ByteBuffer.wrap(new byte[]{0}));}
        try{denied(()->files.uploadSound("test","voices","quota.ogg",saved.revision(),"none",a,()->{}),"managed storage quota not enforced");check(files.draft("test").revision().equals(saved.revision()),"quota rejection preserves snapshot");}finally{Files.delete(extra);}
    }
    static void permissions(TemplateFiles files,byte[] audio)throws Exception{
        var allowed=new AtomicBoolean(true);var access=new AccessSessions(owner->allowed.get());int port;try(var socket=new ServerSocket(0,1,InetAddress.getLoopbackAddress())){port=socket.getLocalPort();}String origin="http://127.0.0.1:"+port;
        var api=new TemplateApi(origin,access,files,TestApiServer.ID);try(var server=new TestApiServer("127.0.0.1",port,api);var client=HttpClient.newHttpClient()){
            String path="/api/draft-audio/test/voices/http.ogg";var login=access.redeem(access.issue(new AccessSessions.Owner(UUID.randomUUID(),"upload_owner")));Map<String,String> headers=new HashMap<>();headers.put("Origin",origin);headers.put("Content-Type","audio/ogg");headers.put("If-Match","\""+files.draft("test").revision()+"\"");headers.put("X-Sound-Revision","none");
            check(http(client,origin,path,audio,headers).statusCode()==401,"anonymous upload denied");headers.put("Cookie",TestApiServer.COOKIE+login.token());check(http(client,origin,path,audio,headers).statusCode()==403,"missing CSRF denied");headers.put("X-Workshop-CSRF",login.session().csrf());
            var response=http(client,origin,path,audio,headers);check(response.statusCode()==200,"authenticated OGG upload: "+new String(response.body()));headers.put("If-Match","\""+files.draft("test").revision()+"\"");headers.put("X-Sound-Revision",AccessSessions.digest(audio));check(http(client,origin,path,audio,headers).statusCode()==400,"overwrite confirmation required");headers.put("X-Sound-Overwrite","1");check(http(client,origin,path,audio,headers).statusCode()==200,"confirmed idempotent overwrite");
            for(String bad:List.of("%2e%2e.ogg","%2fescape.ogg","CON.ogg","con.ogg","x.mp3")){check(http(client,origin,"/api/draft-audio/test/voices/"+bad,audio,headers).statusCode()==400,"hostile filename rejected");}
            headers.put("X-Sound-Revision","none");for(int i=0;i<2;i++)check(http(client,origin,"/api/draft-audio/test/voices/invalid.ogg",new byte[]{1,2,3},headers).statusCode()==400,"extension-only upload denied");
            allowed.set(false);check(http(client,origin,path,audio,headers).statusCode()==401,"current server permission rechecked on upload");allowed.set(true);var next=access.redeem(access.issue(new AccessSessions.Owner(UUID.randomUUID(),"rate_owner")));headers.put("Cookie",TestApiServer.COOKIE+next.token());headers.put("X-Workshop-CSRF",next.session().csrf());for(int i=0;i<15;i++){response=http(client,origin,path,audio,headers);if(response.statusCode()==429)break;}check(response.statusCode()==429,"upload rate bounded across owners");
        }
    }
    static HttpResponse<byte[]> http(HttpClient client,String origin,String path,byte[] bytes,Map<String,String> headers)throws Exception{var builder=HttpRequest.newBuilder(URI.create(origin+path)).timeout(java.time.Duration.ofSeconds(15));headers.forEach(builder::header);return client.send(builder.PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(),HttpResponse.BodyHandlers.ofByteArray());}
}
