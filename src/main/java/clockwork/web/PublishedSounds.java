package clockwork.web;

import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

/** Journaled publication for existing folders. Uploaded replacements have both
 * an optimistic file baseline and a permanent backup. Config is committed last;
 * interruption restores owned writes, and startup recovers an unfinished journal.
 * Only fixed media paths are ever replayed; changed administrator files are kept
 * and recovery blocks writes until reviewed instead of overwriting them. */
final class PublishedSounds implements AutoCloseable {
    private static final int JOURNAL_LIMIT=2*TemplateJson.MAX_BYTES;
    private final Path folder,journal;
    private final SoundBackups backups;
    private final PresetMedia prepared;
    private final JsonObject state;
    private boolean committed;
    private PublishedSounds(Path folder,Path journal,SoundBackups backups,PresetMedia prepared,JsonObject state){this.folder=folder;this.journal=journal;this.backups=backups;this.prepared=prepared;this.state=state;}
    static PublishedSounds prepare(Path active,Path journals,String name,byte[] before,byte[] after,PresetMedia prepared,DraftSounds sounds,SoundBackups backups)throws IOException{
        Path folder=active.resolve(name);TemplateFiles.check(folder);JsonObject state=new JsonObject();state.addProperty("name",name);state.addProperty("old",before==null?"":Base64.getEncoder().encodeToString(before));state.addProperty("old_revision",before==null?"none":AccessSessions.digest(before));state.addProperty("new_revision",AccessSessions.digest(after));state.addProperty("committed",false);
        JsonObject replacements=new JsonObject(),additions=new JsonObject();JsonArray directories=new JsonArray();state.add("replacements",replacements);state.add("additions",additions);state.add("directories",directories);
        for(String relative:PresetMedia.FOLDERS)if(!Files.exists(folder.resolve(relative),NOFOLLOW_LINKS))directories.add(relative);
        for(var file:prepared.digests().entrySet())if(!Files.exists(folder.resolve(file.getKey()),NOFOLLOW_LINKS))additions.addProperty(file.getKey(),file.getValue());
        List<Path> createdBackups=new ArrayList<>();boolean installed=false;
        try{
            for(var file:sounds.uploaded().entrySet()){
                String relative=file.getKey();Path target=folder.resolve(relative);TemplateFiles.check(target);String previous=digest(target);
                if(previous.equals(file.getValue()))continue;
                if(!previous.equals(sounds.baseline(relative)))throw new TemplateFiles.Conflict("Active sound "+target.getFileName()+" changed since this draft was saved. Load and review the active template before replacing it.");
                if(previous.equals("none"))continue; // Owned by installMissing and additions in the journal.
                byte[] original=TemplateFiles.read(target,TemplateFiles.AUDIO_MAX_BYTES);Path backup=backups.create(name,relative,original);createdBackups.add(backup);
                JsonObject change=new JsonObject();change.addProperty("old",previous);change.addProperty("new",file.getValue());change.addProperty("backup",backup.getFileName().toString());replacements.add(relative,change);
            }
            TemplateFiles.directory(journals);Path journal=journals.resolve(UUID.randomUUID()+".json");TemplateFiles.atomic(journal,bytes(state),false,()->{});installed=true;
            return new PublishedSounds(folder,journal,backups,prepared,state);
        }finally{if(!installed)for(Path backup:createdBackups)try{TemplateFiles.check(backup);Files.delete(backup);}catch(IOException cleanup){warning();}}
    }
    void install()throws IOException{
        for(var entry:state.getAsJsonObject("replacements").entrySet()){
            Path target=folder.resolve(entry.getKey());JsonObject change=entry.getValue().getAsJsonObject();String old=change.get("old").getAsString();
            TemplateFiles.atomic(target,prepared.audioBytes(entry.getKey()),true,()->{if(!digest(target).equals(old))throw new TemplateFiles.Conflict("Active sound changed during publication; no replacement was made");});
        }
    }
    void commit()throws IOException{
        state.addProperty("committed",true);
        try{TemplateFiles.atomic(journal,bytes(state),true,()->{});committed=true;removeJournal();}
        catch(IOException failure){
            // A changed JSON is already a durable commit point. Preserve all
            // media and let restart finish journal cleanup, including lost replies.
            if(!state.get("old_revision").getAsString().equals(state.get("new_revision").getAsString())&&digest(folder.resolve("template.json")).equals(state.get("new_revision").getAsString())){committed=true;warning();}
            else{state.addProperty("committed",false);throw failure;}
        }
    }
    private void removeJournal(){try{TemplateFiles.check(journal);Files.deleteIfExists(journal);}catch(IOException cleanup){warning();}}
    @Override public void close()throws IOException{
        if(committed)return;boolean interrupted=Thread.interrupted();
        try{restore(folder,state,backups);removeJournal();}finally{if(interrupted)Thread.currentThread().interrupt();}
    }
    static void recover(Path active,Path journals,SoundBackups backups)throws IOException{
        TemplateFiles.check(journals);if(!Files.exists(journals,NOFOLLOW_LINKS))return;
        try(var entries=Files.list(journals)){
            var paths=entries.limit(17).toList();if(paths.size()>16)throw new IOException("Too many unfinished sound publications; review their journals");
            for(Path path:paths){
                if(!path.getFileName().toString().matches("[a-f0-9-]{36}\\.json"))throw new IOException("Unexpected sound publication journal");
                JsonObject state=TemplateJson.parse(TemplateFiles.read(path,JOURNAL_LIMIT),JOURNAL_LIMIT);String name=state.get("name").getAsString();TemplateFiles.name(name);Path folder=active.resolve(name);validate(state);
                boolean jsonCommitted=!state.get("old_revision").getAsString().equals(state.get("new_revision").getAsString())&&digest(folder.resolve("template.json")).equals(state.get("new_revision").getAsString());
                if(!state.get("committed").getAsBoolean()&&!jsonCommitted)restore(folder,state,backups);
                TemplateFiles.check(path);Files.delete(path);
            }
        }catch(RuntimeException corrupt){throw new IOException("Invalid sound publication journal; review it before enabling workshop writes",corrupt);}
    }
    private static void validate(JsonObject state)throws IOException{
        TemplateFiles.revision(state.get("old_revision").getAsString());TemplateFiles.revision(state.get("new_revision").getAsString());
        if(state.getAsJsonObject("replacements").size()>PresetMedia.MAX_FILES||state.getAsJsonObject("additions").size()>PresetMedia.MAX_FILES||state.getAsJsonArray("directories").size()>3)throw new IOException("Sound journal exceeds limits");
        for(var entry:state.getAsJsonObject("replacements").entrySet()){DraftSounds.relative(entry.getKey());JsonObject value=entry.getValue().getAsJsonObject();TemplateFiles.revision(value.get("old").getAsString());TemplateFiles.revision(value.get("new").getAsString());}
        for(var entry:state.getAsJsonObject("additions").entrySet()){mediaRelative(entry.getKey());TemplateFiles.revision(entry.getValue().getAsString());}
        for(var directory:state.getAsJsonArray("directories"))if(!PresetMedia.FOLDERS.contains(directory.getAsString()))throw new IOException("Invalid journal folder");
    }
    private static void restore(Path folder,JsonObject state,SoundBackups backups)throws IOException{
        validate(state);TemplateFiles.check(folder);String current=digest(folder.resolve("template.json")),old=state.get("old_revision").getAsString();
        if(!current.equals(old))throw new TemplateFiles.Conflict("Active config changed during interrupted publication; review the recovery journal");
        for(var entry:state.getAsJsonObject("replacements").entrySet()){
            Path target=folder.resolve(entry.getKey());JsonObject change=entry.getValue().getAsJsonObject();String before=change.get("old").getAsString(),after=change.get("new").getAsString(),found=digest(target);
            if(found.equals(before))continue;if(!found.equals(after))throw new TemplateFiles.Conflict("Active sound changed during recovery; administrator data was preserved");
            byte[] bytes=TemplateFiles.read(backups.path(change.get("backup").getAsString()),TemplateFiles.AUDIO_MAX_BYTES);if(!AccessSessions.digest(bytes).equals(before))throw new IOException("Original sound backup changed");
            TemplateFiles.atomic(target,bytes,true,()->{if(!digest(target).equals(after))throw new TemplateFiles.Conflict("Active sound changed during recovery");});
        }
        for(var entry:state.getAsJsonObject("additions").entrySet()){
            Path target=folder.resolve(entry.getKey());String found=digest(target);if(found.equals("none"))continue;if(!found.equals(entry.getValue().getAsString()))throw new TemplateFiles.Conflict("New media changed during recovery; administrator data was preserved");TemplateFiles.check(target);Files.delete(target);
        }
        List<String> folders=new ArrayList<>();state.getAsJsonArray("directories").forEach(value->folders.add(value.getAsString()));
        for(String relative:folders.reversed())try{Path target=folder.resolve(relative);TemplateFiles.check(target);Files.deleteIfExists(target);}catch(DirectoryNotEmptyException retained){}
    }
    private static String digest(Path target)throws IOException{TemplateFiles.check(target);return Files.exists(target,NOFOLLOW_LINKS)?AccessSessions.digest(TemplateFiles.read(target,target.toString().endsWith(".png")?PresetMedia.PNG_MAX_BYTES:target.getFileName().toString().equals("template.json")?TemplateJson.MAX_BYTES:TemplateFiles.AUDIO_MAX_BYTES)):"none";}
    private static void mediaRelative(String relative)throws IOException{if(relative.startsWith("skins/")&&relative.matches("skins/[a-z0-9][a-z0-9._-]{0,63}\\.png"))return;DraftSounds.relative(relative);}
    private static byte[] bytes(JsonObject state)throws IOException{byte[] bytes=state.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);if(bytes.length>JOURNAL_LIMIT)throw new IOException("Sound publication journal exceeds limit");return bytes;}
    private static void warning(){org.slf4j.LoggerFactory.getLogger("clockwork_bosses_web").warn("Sound publication journal/backup cleanup incomplete; check filesystem permissions");}
}
