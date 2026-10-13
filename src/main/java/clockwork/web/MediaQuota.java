package clockwork.web;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** Stat-only, bounded accounting on file workers. Includes immutable drafts,
 * active templates, JSON/audio backups and private stages, never worlds or
 * unrelated mods. External administrator files are counted, never removed. */
final class MediaQuota {
    static final long MAX_STORAGE=256L*1024*1024;
    private final List<Path> roots;
    MediaQuota(Path...roots){this.roots=List.of(roots);}
    long used()throws IOException{
        long total=0;int count=0;
        for(Path root:roots){TemplateFiles.check(root);if(!Files.exists(root,LinkOption.NOFOLLOW_LINKS))continue;
            try(var paths=Files.walk(root,6)){
                var iterator=paths.iterator();while(iterator.hasNext()){
                    Path path=iterator.next();if(++count>16384)throw new IOException("Managed storage exceeds the 16384-entry scan limit; archive unused drafts/backups");TemplateFiles.check(path);
                    var attrs=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                    if(attrs.isRegularFile())total=Math.addExact(total,attrs.size());else if(!attrs.isDirectory())throw new IOException("Unsafe managed storage entry");
                    if(attrs.isDirectory()&&root.relativize(path).getNameCount()>=6)throw new IOException("Unexpected nested managed storage folder");
                }
            }
        }
        return total;
    }
    void reserve(long additional)throws IOException{if(additional<0||used()>MAX_STORAGE-additional)throw new IOException("Managed storage limit reached (256 MiB). Archive unused drafts or backups before uploading/saving.");}
}
