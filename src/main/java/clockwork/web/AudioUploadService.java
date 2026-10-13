package clockwork.web;

import com.sun.net.httpserver.HttpExchange;
import java.io.*;
import java.util.concurrent.Semaphore;

/** Admission/body/decode boundary. One upload owns at most 8 MiB compressed
 * bytes and bounded decoder state. An owner/rate/CSRF check precedes this class;
 * revocation is rechecked after reading and just before the manifest commits. */
final class AudioUploadService {
    static com.google.gson.JsonObject capabilities(){var result=new com.google.gson.JsonObject();result.addProperty("enabled",true);result.addProperty("codec","vorbis");result.addProperty("max_file_bytes",UploadedAudio.MUSIC_MAX_BYTES);result.addProperty("max_clip_bytes",UploadedAudio.CLIP_MAX_BYTES);result.addProperty("max_clip_seconds",UploadedAudio.CLIP_MAX_SECONDS);result.addProperty("max_music_seconds",UploadedAudio.MAX_SECONDS);result.addProperty("max_draft_bytes",PresetMedia.MAX_TOTAL_BYTES);result.addProperty("max_storage_bytes",MediaQuota.MAX_STORAGE);result.addProperty("max_files",PresetMedia.MAX_FILES);result.addProperty("uploads_per_minute",12);return result;}
    private final Semaphore slot=new Semaphore(1);
    static final class Busy extends IOException {Busy(){super("Another sound upload is in progress; retry when it finishes");}}
    TemplateFiles.Snapshot upload(HttpExchange exchange,TemplateFiles files,String name,String kind,String filename,String revision,String fileRevision,TemplateFiles.CommitCheck authorized)throws IOException{
        DraftSounds.relative(kind,filename);TemplateFiles.revision(revision);TemplateFiles.revision(fileRevision);
        if(!slot.tryAcquire())throw new Busy();
        try{
            String type=exchange.getRequestHeaders().getFirst("Content-Type");
            if(type==null||!type.equalsIgnoreCase("audio/ogg"))throw new IOException("Upload raw OGG audio with Content-Type audio/ogg");
            var purposes=exchange.getRequestHeaders().get("X-Sound-Purpose");if(purposes!=null&&purposes.size()!=1)throw new IOException("One sound purpose is required");String purpose=purposes==null?"clip":purposes.getFirst();
            if(!purpose.equals("clip")&&!purpose.equals("music")||kind.equals("voices")&&purpose.equals("music"))throw new IOException("Choose sound effect, voice line, or music");boolean music=purpose.equals("music");
            String declared=exchange.getRequestHeaders().getFirst("Content-Length");long length;
            try{length=Long.parseLong(declared);}catch(RuntimeException bad){throw new IOException("Sound Content-Length is required");}
            if(length<=0||length>(music?UploadedAudio.MUSIC_MAX_BYTES:UploadedAudio.CLIP_MAX_BYTES))throw new IOException(music?"Music must be below 8 MiB":"Sound effects and voice lines must be below 1 MiB");
            files.checkUpload(name,kind,filename,revision,fileRevision);
            byte[] bytes=exchange.getRequestBody().readNBytes(TemplateFiles.AUDIO_MAX_BYTES+1);
            if(bytes.length!=length)throw new IOException("Sound upload was interrupted or has an invalid length; no sound was saved");
            authorized.check();UploadedAudio.validate(bytes,music);authorized.check();
            return files.uploadSound(name,kind,filename,revision,fileRevision,bytes,authorized);
        }finally{slot.release();}
    }
}
