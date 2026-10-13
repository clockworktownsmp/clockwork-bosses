package clockwork.web;

import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Provenance in the existing atomic draft envelope, not in public config JSON.
 * Uploaded hashes bind files to a reviewed generation; base hashes detect edits
 * to active clips before publication. Copying a draft never drops its uploads. */
final class DraftSounds {
    private final Map<String,String> uploaded, base;
    DraftSounds(Map<String,String> uploaded,Map<String,String> base){this.uploaded=Map.copyOf(uploaded);this.base=Map.copyOf(base);}
    Map<String,String> uploaded(){return uploaded;}
    String baseline(String relative){return base.getOrDefault(relative,"none");}
    DraftSounds with(String relative,String hash){var next=new TreeMap<>(uploaded);next.put(relative,hash);return new DraftSounds(next,base);}
    DraftSounds rebased(Map<String,String> next){return new DraftSounds(uploaded,next);}
    boolean publishedChanges(Map<String,String> active){return uploaded.entrySet().stream().anyMatch(file->file.getValue().equals(active.get(file.getKey()))&&!file.getValue().equals(baseline(file.getKey())));}
    DraftSounds acceptPublished(Map<String,String> active){var next=new TreeMap<>(base);uploaded.forEach((relative,hash)->{if(hash.equals(active.get(relative)))next.put(relative,hash);});return new DraftSounds(uploaded,next);}
    static DraftSounds read(Properties props)throws IOException{return new DraftSounds(map(props.getProperty("sound_uploads","{}"),false),map(props.getProperty("sound_bases","{}"),false));}
    void write(Properties props){props.setProperty("sound_media_version","1");props.setProperty("sound_uploads",json(uploaded));props.setProperty("sound_bases",json(base));}
    private static String json(Map<String,String> values){JsonObject object=new JsonObject();new TreeMap<>(values).forEach(object::addProperty);return object.toString();}
    private static Map<String,String> map(String text,boolean allowNone)throws IOException{
        if(text.length()>32768)throw new IOException("Draft sound metadata exceeds limit");
        try{JsonObject object=JsonParser.parseString(text).getAsJsonObject();if(object.size()>PresetMedia.MAX_FILES)throw new IOException("Too many draft sound files");var result=new TreeMap<String,String>();
            for(var entry:object.entrySet()){relative(entry.getKey());if(!entry.getValue().isJsonPrimitive()||!entry.getValue().getAsJsonPrimitive().isString())throw new IOException("Invalid draft sound revision");String value=entry.getValue().getAsString();TemplateFiles.revision(value);if(value.equals("none")&&!allowNone)throw new IOException("Invalid draft sound revision");result.put(entry.getKey(),value);}return result;
        }catch(RuntimeException malformed){throw new IOException("Invalid draft sound metadata",malformed);}
    }
    static String relative(String kind,String filename)throws IOException{
        if(!kind.equals("sounds")&&!kind.equals("voices"))throw new IOException("Choose sounds or voices");
        filename(filename);return(kind.equals("voices")?"sounds/voice-lines/":"sounds/")+filename;
    }
    static void relative(String value)throws IOException{
        String prefix=value.startsWith("sounds/voice-lines/")?"sounds/voice-lines/":"sounds/";
        if(!value.startsWith(prefix))throw new IOException("Invalid sound folder");filename(value.substring(prefix.length()));
    }
    static void filename(String value)throws IOException{
        if(value==null||!value.matches("[a-z0-9][a-z0-9._-]{0,63}\\.ogg")||value.split("\\.",2)[0].matches("con|prn|aux|nul|com[1-9]|lpt[1-9]"))throw new IOException("Use a lowercase OGG filename; paths, spaces and device names are not allowed");
    }
    void verify(PresetMedia prepared)throws IOException{for(var entry:uploaded.entrySet())if(!entry.getValue().equals(prepared.digest(entry.getKey())))throw new TemplateFiles.Conflict("An uploaded draft sound changed or is missing. Reload and review the draft.");}
    static Map<String,String> hashes(Path root)throws IOException{
        var result=new TreeMap<String,String>();long total=0;
        for(String folder:List.of("sounds","sounds/voice-lines")){
            Path directory=root.resolve(folder);TemplateFiles.check(directory);if(!Files.exists(directory,LinkOption.NOFOLLOW_LINKS))continue;
            if(!Files.isDirectory(directory,LinkOption.NOFOLLOW_LINKS))throw new IOException("Invalid sound folder");
            try(var entries=Files.list(directory)){
                var paths=entries.limit(257).toList();if(paths.size()>256)throw new IOException("Sound folder exceeds scan limit");
                for(Path path:paths){String name=path.getFileName().toString();if(folder.equals("sounds")&&name.equals("voice-lines"))continue;if(!name.endsWith(".ogg"))continue;try{filename(name);}catch(IOException unsupported){continue;}TemplateFiles.check(path);if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))continue;byte[] bytes=TemplateFiles.read(path,TemplateFiles.AUDIO_MAX_BYTES);total+=bytes.length;if(total>PresetMedia.MAX_TOTAL_BYTES||result.size()>=PresetMedia.MAX_FILES)throw new IOException("Active sound storage exceeds the draft limits");result.put(folder+"/"+name,AccessSessions.digest(bytes));}
            }
        }
        return result;
    }
}
