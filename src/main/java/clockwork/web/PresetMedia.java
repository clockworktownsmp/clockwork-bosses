package clockwork.web;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

/** Copies only allowed template media on a file worker. A bounded private stage
 * precedes a draft/publish commit. This copier preserves existing active media;
 * confirmed uploaded replacements are owned by PublishedSounds. */
final class PresetMedia implements AutoCloseable {
    static final Set<String> PRESETS = Set.of("1_player", "2_players", "3_players", "4_players", "5_players", "6_players", "boss-sounds-example");
    static final List<String> FOLDERS = List.of("skins", "sounds", "sounds/voice-lines");
    static final int MAX_FILES = 96, MAX_TOTAL_BYTES = 32 * 1024 * 1024, PNG_MAX_BYTES = 512 * 1024;
    private static final Pattern FILE = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}\\.(?:ogg|png)");
    private static final Pattern DEVICE = Pattern.compile("con|prn|aux|nul|com[1-9]|lpt[1-9]");
    private record Media(String relative, String digest, int limit) { }
    private final Path stage;
    private final List<Media> media = new ArrayList<>();
    private final Map<Path,Media> added = new LinkedHashMap<>();
    private final List<Path> createdFolders = new ArrayList<>();
    private boolean committed;

    static String preset(String value) throws IOException {
        if (value == null || value.equals("none") || value.isEmpty()) return "";
        if (!PRESETS.contains(value)) throw new IOException("Choose a supported built-in preset; paths are not allowed");
        return value;
    }
    static PresetMedia prepare(Path active, Path presets, String preset) throws IOException {
        preset = preset(preset);
        return prepareFrom(active, preset.isEmpty() ? null : presets.resolve(preset));
    }
    static PresetMedia prepareFrom(Path active, Path source) throws IOException {
        TemplateFiles.check(active);
        var prepared = new PresetMedia(Files.createTempDirectory(active, ".workshop-preset-"));
        try {
            prepared.folders(prepared.stage, false);
            if (source != null) {
                TemplateFiles.check(source.resolve("template.json"));
                if (!Files.isRegularFile(source.resolve("template.json"), NOFOLLOW_LINKS)) throw new IOException("Selected media source is missing; install/update the core mod or reload the saved template");
                int count = 0; long total = 0;
                for (String category : FOLDERS) {
                    Path directory = source.resolve(category); TemplateFiles.check(directory);
                    if (!Files.exists(directory, NOFOLLOW_LINKS)) continue;
                    if (!Files.isDirectory(directory, NOFOLLOW_LINKS)) throw new IOException("Invalid preset media folder");
                    try (var entries = Files.list(directory)) {
                        var paths = entries.limit(257).sorted().toList();
                        if (paths.size() > 256) throw new IOException("Preset media folder exceeds 256 entries");
                        for (Path file : paths) {
                            TemplateFiles.check(file);
                            String name = file.getFileName().toString();
                            if (category.equals("sounds") && name.equals("voice-lines")) continue;
                            boolean skin = category.equals("skins");
                            if (!FILE.matcher(name).matches() || !name.endsWith(skin ? ".png" : ".ogg")) continue;
                            if (DEVICE.matcher(name.split("\\.", 2)[0]).matches() || !Files.isRegularFile(file, NOFOLLOW_LINKS)) throw new IOException("Unsupported preset media entry");
                            if (++count > MAX_FILES) throw new IOException("Preset exceeds 96 media files");
                            int limit = skin ? PNG_MAX_BYTES : TemplateFiles.AUDIO_MAX_BYTES;
                            byte[] bytes = TemplateFiles.read(file, limit); total += bytes.length;
                            if (bytes.length == 0 || total > MAX_TOTAL_BYTES) throw new IOException("Preset media exceeds the 32 MiB total limit");
                            if (!(skin ? png(bytes) : TemplateFiles.oggAudio(bytes))) throw new IOException("Preset contains unsupported PNG/OGG media");
                            String relative = category + "/" + name;
                            TemplateFiles.atomic(prepared.stage.resolve(relative), bytes, false, () -> {});
                            prepared.media.add(new Media(relative, AccessSessions.digest(bytes), limit));
                        }
                    }
                }
            }
            return prepared;
        } catch (IOException | RuntimeException error) { prepared.close(); throw error; }
    }
    private PresetMedia(Path stage) { this.stage = stage; }
    String digest(String relative){return media.stream().filter(file->file.relative().equals(relative)).map(Media::digest).findFirst().orElse("none");}
    Map<String,String> digests(){Map<String,String> result=new TreeMap<>();for(Media file:media)result.put(file.relative(),file.digest());return result;}
    byte[] audioBytes(String relative)throws IOException{DraftSounds.relative(relative);return TemplateFiles.read(stage.resolve(relative),TemplateFiles.AUDIO_MAX_BYTES);}
    long bytes() throws IOException {long total=0;for(Media file:media)total+=Files.size(stage.resolve(file.relative()));return total;}
    static long measure(Path source)throws IOException{
        if(source==null)return 0;long total=0;int count=0;
        for(String relative:FOLDERS){Path folder=source.resolve(relative);TemplateFiles.check(folder);if(!Files.exists(folder,NOFOLLOW_LINKS))continue;
            try(var entries=Files.list(folder)){var paths=entries.limit(257).toList();if(paths.size()>256)throw new IOException("Media folder exceeds scan limit");
                for(Path file:paths){String name=file.getFileName().toString();if(relative.equals("sounds")&&name.equals("voice-lines"))continue;if(!FILE.matcher(name).matches()||!name.endsWith(relative.equals("skins")?".png":".ogg"))continue;TemplateFiles.check(file);if(!Files.isRegularFile(file,NOFOLLOW_LINKS))throw new IOException("Unsafe media entry");total+=Files.size(file);if(++count>MAX_FILES||total>MAX_TOTAL_BYTES)throw new IOException("Draft media exceeds 96 files or 32 MiB");}
            }
        }return total;
    }
    void replaceAudio(String relative,byte[] bytes)throws IOException{
        DraftSounds.relative(relative);Path target=stage.resolve(relative);TemplateFiles.check(target);
        boolean existed=Files.exists(target,NOFOLLOW_LINKS);long old=existed?Files.size(target):0;
        if((!existed&&media.size()>=MAX_FILES)||bytes()+bytes.length-old>MAX_TOTAL_BYTES)throw new IOException("Draft media exceeds 96 files or 32 MiB");
        TemplateFiles.atomic(target,bytes,existed,()->{});media.removeIf(file->file.relative().equals(relative));media.add(new Media(relative,AccessSessions.digest(bytes),TemplateFiles.AUDIO_MAX_BYTES));
    }
    void carryUploads(Path source,DraftSounds sounds)throws IOException{
        for(var entry:sounds.uploaded().entrySet()){
            byte[] bytes=TemplateFiles.read(source.resolve(entry.getKey()),TemplateFiles.AUDIO_MAX_BYTES);
            if(!AccessSessions.digest(bytes).equals(entry.getValue()))throw new TemplateFiles.Conflict("Uploaded source sound changed; reload the draft");replaceAudio(entry.getKey(),bytes);
        }
    }
    private static boolean png(byte[] bytes) {
        if (bytes.length < 33) return false;
        ByteBuffer header = ByteBuffer.wrap(bytes);
        return header.getLong(0) == 0x89504e470d0a1a0aL && header.getInt(8) == 13 && header.getInt(12) == 0x49484452 && header.getInt(16) == 128 && header.getInt(20) == 128;
    }
    void publishNew(Path folder, byte[] json, TemplateFiles.CommitCheck recheck) throws IOException {
        installNew(folder, json, recheck); commit();
    }
    /** The draft manifest commits separately. Track the moved bundle so failure
     * rolls back only our own unchanged files, including its template JSON. */
    void installNew(Path folder, byte[] json, TemplateFiles.CommitCheck recheck) throws IOException {
        TemplateFiles.atomic(stage.resolve("template.json"), json, false, () -> {});
        TemplateFiles.check(folder); recheck.check();
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Workshop publish stopped before commit");
        try {
            Files.move(stage, folder);
            createdFolders.add(folder);
            for (String relative : FOLDERS) createdFolders.add(folder.resolve(relative));
            for (Media file : media) added.put(folder.resolve(file.relative()), file);
            added.put(folder.resolve("template.json"), new Media("template.json", AccessSessions.digest(json), TemplateJson.MAX_BYTES));
        }
        catch (FileAlreadyExistsException conflict) { throw new TemplateFiles.Conflict("Template folder appeared concurrently; no replacement was made"); }
    }
    void validateTarget(Path folder) throws IOException {
        for (String relative : FOLDERS) {
            Path directory = folder.resolve(relative); TemplateFiles.check(directory);
            if (Files.exists(directory, NOFOLLOW_LINKS) && !Files.isDirectory(directory, NOFOLLOW_LINKS)) throw new IOException("Invalid destination media folder");
        }
        for (Media file : media) {
            Path target = folder.resolve(file.relative()); TemplateFiles.check(target);
            if (Files.exists(target, NOFOLLOW_LINKS) && !Files.isRegularFile(target, NOFOLLOW_LINKS)) throw new IOException("Invalid destination media file");
        }
    }
    void installMissing(Path folder) throws IOException {
        validateTarget(folder); folders(folder, true);
        for (Media file : media) {
            Path target = folder.resolve(file.relative()); TemplateFiles.check(target);
            if (Files.exists(target, NOFOLLOW_LINKS)) continue; // Preserve administrator media.
            byte[] bytes = TemplateFiles.read(stage.resolve(file.relative()), file.limit());
            TemplateFiles.atomic(target, bytes, false, () -> {}); added.put(target, file);
        }
    }
    void commit() { committed = true; }
    private void folders(Path folder, boolean track) throws IOException {
        for (String relative : FOLDERS) {
            Path path = folder.resolve(relative);
            boolean existed = Files.exists(path, NOFOLLOW_LINKS);
            TemplateFiles.directory(path);
            if (track && !existed) createdFolders.add(path);
        }
    }
    @Override public void close() {
        // Cancellation must not prevent the bounded rollback reads themselves.
        // Restore the flag for the caller after cleaning up our own additions.
        boolean interrupted = Thread.interrupted();
        try {
        if (!committed) {
            for (var entry : added.entrySet()) try {
                Path target = entry.getKey(); Media file = entry.getValue();
                if (Files.exists(target, NOFOLLOW_LINKS) && AccessSessions.digest(TemplateFiles.read(target, file.limit())).equals(file.digest())) Files.delete(target);
            } catch (IOException cleanup) { cleanupWarning(); }
            for (Path folder : createdFolders.reversed()) try { TemplateFiles.check(folder); Files.deleteIfExists(folder); }
            catch (DirectoryNotEmptyException changed) { /* Preserve externally added files. */ }
            catch (IOException cleanup) { cleanupWarning(); }
        }
        // This is the unique stage we created beneath the validated active root.
        // Walk without following links; recheck every path before deleting it.
        try {
            TemplateFiles.check(stage);
            if (Files.exists(stage, NOFOLLOW_LINKS)) try (var paths = Files.walk(stage)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) { TemplateFiles.check(path); Files.deleteIfExists(path); }
            }
        } catch (IOException cleanup) { cleanupWarning(); }
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
    private static void cleanupWarning() { org.slf4j.LoggerFactory.getLogger("clockwork_bosses_web").warn("Workshop preset staging/rollback cleanup incomplete; check filesystem permissions"); }
    /** Retire only our fixed snapshot layout. Unexpected files/links are kept;
     * the caller bounds retained revisions rather than deleting arbitrary data. */
    static void retire(Path folder) {
        boolean interrupted = Thread.interrupted();
        try {
            TemplateFiles.check(folder);
            if (!Files.isDirectory(folder, NOFOLLOW_LINKS)) return;
            List<Path> paths;
            try (var stream = Files.walk(folder, 4)) { paths = stream.limit(MAX_FILES + 6L).toList(); }
            if (paths.size() > MAX_FILES + 5) return;
            for (Path path : paths) {
                TemplateFiles.check(path);
                String relative = folder.relativize(path).toString().replace('\\', '/');
                if (relative.isEmpty() || FOLDERS.contains(relative)) continue;
                if (!relative.equals("template.json") && !relative.matches("(?:skins/[a-z0-9][a-z0-9._-]{0,63}\\.png|sounds/(?:voice-lines/)?[a-z0-9][a-z0-9._-]{0,63}\\.ogg)")) return;
                if (!Files.isRegularFile(path, NOFOLLOW_LINKS)) return;
            }
            for (Path path : paths.stream().sorted(Comparator.reverseOrder()).toList()) { TemplateFiles.check(path); Files.deleteIfExists(path); }
        } catch (IOException cleanup) { cleanupWarning(); }
        finally { if (interrupted) Thread.currentThread().interrupt(); }
    }
}
