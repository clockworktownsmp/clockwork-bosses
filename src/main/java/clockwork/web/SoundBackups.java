package clockwork.web;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Required backups for explicit audio replacements. Immutable raw originals,
 * recoverable by their filename, with fixed count/byte limits; never auto-pruned. */
final class SoundBackups {
    static final long MAX_BYTES=64L*1024*1024;
    static final int MAX_FILES=64;
    private final Path root;
    SoundBackups(Path root){this.root=root;}
    Path path(String basename)throws IOException{
        if(basename==null||basename.contains("/")||basename.contains("\\")||!basename.matches("[a-z0-9][a-z0-9_-]{0,63}--(?:sounds|voices)--[a-f0-9-]{36}--[a-z0-9][a-z0-9._-]{0,63}\\.ogg"))throw new IOException("Invalid sound backup name");Path path=root.resolve(basename);TemplateFiles.check(path);return path;
    }
    Path create(String name,String relative,byte[] bytes)throws IOException{
        TemplateFiles.name(name);DraftSounds.relative(relative);TemplateFiles.directory(root);
        int count=0;long total=0;
        try(var entries=Files.list(root)){
            var paths=entries.limit(MAX_FILES+1L).toList();
            for(Path path:paths){TemplateFiles.check(path);if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("Unsafe sound backup entry");count++;total+=Files.size(path);}
        }
        if(count>=MAX_FILES||total+bytes.length>MAX_BYTES)throw new IOException("Sound backup limit reached (64 files / 64 MiB). Archive backups before replacing more clips.");
        String kind=relative.startsWith("sounds/voice-lines/")?"voices":"sounds",filename=relative.substring(relative.lastIndexOf('/')+1);
        Path target=root.resolve(name+"--"+kind+"--"+UUID.randomUUID()+"--"+filename);TemplateFiles.atomic(target,bytes,false,()->{});return target;
    }
    static void rollback(Path path,byte[] original){if(path==null)return;try{if(Arrays.equals(TemplateFiles.read(path,TemplateFiles.AUDIO_MAX_BYTES),original))Files.delete(path);}catch(IOException cleanup){org.slf4j.LoggerFactory.getLogger("clockwork_bosses_web").warn("Sound backup rollback cleanup incomplete; check filesystem permissions");}}
}
