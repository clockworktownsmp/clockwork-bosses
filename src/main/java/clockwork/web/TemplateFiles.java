package clockwork.web;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.Pattern;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;
import static java.nio.file.StandardOpenOption.*;

/** Filesystem boundary, owned by HTTP workers only. A single lock serializes
 * companion writes; revisions also detect ordinary external/server edits.
 * Exact uploaded bytes, including unknown addon numbers/settings, are retained.
 */
public final class TemplateFiles {
    public record Snapshot(byte[] content, String revision, String baseRevision, String preset, boolean mediaBundle) {
        public Snapshot(byte[] content, String revision, String baseRevision, String preset) { this(content, revision, baseRevision, preset, false); }
        public Snapshot(byte[] content, String revision, String baseRevision) { this(content, revision, baseRevision, ""); }
    }
    public record Entry(String name, String activeRevision, String draftRevision, String baseRevision) { }
    public record Sounds(String name, List<String> sounds, List<String> voices, boolean soundsTruncated, boolean voicesTruncated, List<String> uploadedSounds, List<String> uploadedVoices) { }
    public record UploadFile(String kind,String filename,String revision,long bytes,boolean uploaded) { }
    public record UploadListing(String name,String revision,long storageBytes,List<UploadFile> files) { }
    public static final int AUDIO_MAX_BYTES = 8 * 1024 * 1024;
    private record SoundCache(Sounds value, long expires) { }
    private record SoundNames(List<String> names, boolean truncated) { }
    private record Stamp(long size, java.nio.file.attribute.FileTime modified, Object key) { }
    private record Cached(Stamp active, Stamp draft, Entry entry) { }
    public static final class Conflict extends IOException { Conflict(String text) { super(text); } }
    private final Path active, drafts, backups, presets;
    private final TemplateJson validator;
    // A merged adapter supplies the native template writer's monitor. Offline
    // integrations keep their own monitor through the original constructor.
    // Only worker threads acquire this; native ticks never wait for publication.
    private final Object activeWriteLock;
    private final SoundBackups soundBackups;
    private final MediaQuota mediaQuota;
    private final Path soundJournals;
    private final SharedMusicLibrary musicLibrary;
    public SharedMusicLibrary musicLibrary() { return musicLibrary; }
    private final Map<String, Cached> metadata = new HashMap<>();
    private List<Entry> listing = List.of();
    private long listingExpires;
    private final Map<String, SoundCache> soundCache = new LinkedHashMap<>();
    private static final Pattern OGG = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}\\.ogg");
    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    private static final Pattern RESERVED = Pattern.compile("con|prn|aux|nul|com[1-9]|lpt[1-9]");
    public TemplateFiles(Path config, TemplateJson validator) throws IOException {
        this(config, validator, new Object());
    }
    public TemplateFiles(Path config, TemplateJson validator, Object activeWriteLock) throws IOException {
        this.validator = validator;
        this.activeWriteLock = Objects.requireNonNull(activeWriteLock);
        active = config.resolve("clockwork-bosses/boss-templates").toAbsolutePath().normalize();
        drafts = config.resolve("clockwork-bosses-web/drafts").toAbsolutePath().normalize();
        backups = config.resolve("clockwork-bosses-web/backups").toAbsolutePath().normalize();
        presets = config.resolve("clockwork-bosses/boss-presets").toAbsolutePath().normalize();
        Path soundBackupRoot=config.resolve("clockwork-bosses-web/sound-backups").toAbsolutePath().normalize();
        soundJournals=config.resolve("clockwork-bosses-web/sound-publish-journals").toAbsolutePath().normalize();
        musicLibrary = new SharedMusicLibrary(config, activeWriteLock);
        soundBackups=new SoundBackups(soundBackupRoot);mediaQuota=new MediaQuota(active,drafts,backups,soundBackupRoot,soundJournals,
                config.resolve("clockwork-bosses/music"), config.resolve("clockwork-bosses/music-backups"));
        verifyStorage();
    }
    void verifyStorage() throws IOException {
        synchronized (activeWriteLock) {
            directory(active); directory(drafts); directory(backups); PublishedSounds.recover(active,soundJournals,soundBackups);
        }
    }
    public static void name(String name) throws IOException {
        if (!NAME.matcher(name).matches() || RESERVED.matcher(name).matches()) throw new IOException("Use a lowercase template name, 1–64 letters/digits/underscores/hyphens; no reserved device names");
    }
    public static void revision(String revision) throws IOException {
        if (revision == null || !revision.matches("none|[a-f0-9]{64}")) throw new IOException("A template revision is required");
    }
    public synchronized List<String> names() throws IOException {
        check(active); check(drafts);
        TreeSet<String> names = new TreeSet<>();
            for (Path folder : entries(active)) {
                String name = folder.getFileName().toString();
                if (validName(name) && Files.isDirectory(folder, NOFOLLOW_LINKS) && Files.exists(folder.resolve("template.json"), NOFOLLOW_LINKS)) names.add(name);
            }
            for (Path file : entries(drafts)) {
                String name = file.getFileName().toString();
                if (name.endsWith(".draft") && validName(name.substring(0, name.length() - 6))) names.add(name.substring(0, name.length() - 6));
            }
        if (names.size() > 256) throw new IOException("Template listing exceeds the 256-template limit");
        return List.copyOf(names);
    }
    /** Cache display metadata only. Every load/save/publish reads fresh bytes.
     * Fingerprints avoid repeatedly decoding/hashing unchanged template files.
     */
    public synchronized List<Entry> listing() throws IOException {
        check(active); check(drafts);
        if (System.nanoTime() < listingExpires) return listing;
        List<String> names = names(); List<Entry> result = new ArrayList<>();
        metadata.keySet().retainAll(names);
        for (String name : names) {
            Stamp a = stamp(active.resolve(name).resolve("template.json")), d = stamp(drafts.resolve(name + ".draft"));
            Cached cached = metadata.get(name);
            if (cached == null || !Objects.equals(cached.active(), a) || !Objects.equals(cached.draft(), d)) {
                Snapshot current = active(name), draft = draft(name);
                cached = new Cached(a, d, new Entry(name, current.revision(), draft.revision(), draft.baseRevision()));
                metadata.put(name, cached);
            }
            result.add(cached.entry());
        }
        listing = List.copyOf(result); listingExpires = System.nanoTime() + 5_000_000_000L;
        return listing;
    }
    private void invalidate(String name) { listingExpires = 0; metadata.remove(name); soundCache.remove("active:" + name); soundCache.remove("drafts:" + name); }
    /** Filename metadata only; never opens audio bytes or creates media folders. */
    public synchronized Sounds sounds(String name) throws IOException {
        return sounds("active", name);
    }
    public synchronized Sounds sounds(String source, String name) throws IOException {
        Path soundFolder = mediaRoot(source, name).resolve("sounds"), voiceFolder = soundFolder.resolve("voice-lines");
        check(soundFolder); check(voiceFolder);
        String key = source + ":" + name;
        var cached = soundCache.get(key);
        if (cached != null && cached.expires() > System.nanoTime()) return cached.value();
        var sounds = soundNames(soundFolder, 256); var voices = soundNames(voiceFolder, 64);
        Set<String> uploaded=source.equals("drafts")?soundState(name).uploaded().keySet():Set.of();
        var result = new Sounds(name, sounds.names(), voices.names(), sounds.truncated(), voices.truncated(),
            sounds.names().stream().filter(file->uploaded.contains("sounds/"+file)).toList(),voices.names().stream().filter(file->uploaded.contains("sounds/voice-lines/"+file)).toList());
        soundCache.put(key, new SoundCache(result, System.nanoTime() + 5_000_000_000L));
        while (soundCache.size() > 16) soundCache.remove(soundCache.keySet().iterator().next());
        return result;
    }
    private static SoundNames soundNames(Path directory, int limit) throws IOException {
        check(directory);
        if (!Files.exists(directory, NOFOLLOW_LINKS)) return new SoundNames(List.of(), false);
        if (!Files.isDirectory(directory, NOFOLLOW_LINKS)) throw new IOException("Unsafe sound folder");
        List<String> names = new ArrayList<>(); boolean truncated;
        try (var stream = Files.list(directory)) {
            var paths = stream.limit(4097).toList(); truncated = paths.size() > 4096;
            for (var path : paths.subList(0, Math.min(4096, paths.size()))) {
                String name = path.getFileName().toString();
                if (!OGG.matcher(name).matches()) continue;
                var attr = Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW_LINKS);
                if (attr.isRegularFile() && !attr.isSymbolicLink() && !attr.isOther()) names.add(name);
            }
        }
        names.sort(String::compareTo); truncated |= names.size() > limit;
        return new SoundNames(List.copyOf(names.subList(0, Math.min(limit, names.size()))), truncated);
    }
    /** One bounded read on an HTTP worker, without holding the template write lock.
     * Kinds select fixed folders; no caller-supplied path is ever resolved.
     */
    public byte[] audio(String template, String kind, String filename) throws IOException {
        return audio("active", template, kind, filename);
    }
    public byte[] audio(String source, String template, String kind, String filename) throws IOException {
        if (!kind.equals("sounds") && !kind.equals("voices")) throw new IOException("Use sounds or voices for audio previews");
        if (!OGG.matcher(filename).matches() || RESERVED.matcher(filename.split("\\.", 2)[0]).matches()) throw new IOException("Use a lowercase OGG basename; paths and device names are not allowed");
        Path folder = mediaRoot(source, template).resolve("sounds");
        if (kind.equals("voices")) folder = folder.resolve("voice-lines");
        Path path = folder.resolve(filename);
        byte[] bytes;
        try {
            check(path);
            var attr = Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW_LINKS);
            if (!attr.isRegularFile() || attr.isSymbolicLink() || attr.isOther() || attr.size() > AUDIO_MAX_BYTES) throw new IOException("Unsafe or oversized audio");
            // NOFOLLOW also rejects replacing the leaf with a symlink before open.
            try (var channel = Files.newByteChannel(path, Set.of(READ, NOFOLLOW_LINKS)); var input = java.nio.channels.Channels.newInputStream(channel)) {
                check(path);
                bytes = input.readNBytes(AUDIO_MAX_BYTES + 1);
                if (bytes.length > AUDIO_MAX_BYTES) throw new IOException("Audio grew beyond limit");
            }
        } catch (NoSuchFileException missing) { throw new NoSuchFileException(filename); }
        catch (IOException invalid) { throw new IOException("Sound file is unsafe, inaccessible, or exceeds the 8 MiB preview limit", invalid); }
        if (!oggAudio(bytes)) throw new IOException("Sound file is not supported OGG audio (Vorbis or Opus)");
        return bytes;
    }
    private synchronized Path mediaRoot(String source, String name) throws IOException {
        name(name);
        return switch (source) {
            case "active" -> active.resolve(name);
            case "presets" -> {
                if (PresetMedia.preset(name).isEmpty()) throw new IOException("Choose a supported built-in preset");
                yield presets.resolve(name);
            }
            case "drafts" -> {
                Snapshot saved = draft(name);
                if (!saved.mediaBundle()) throw new NoSuchFileException(name);
                yield bundle(name, saved.revision());
            }
            default -> throw new IOException("Choose active, drafts, or presets for sound previews");
        };
    }
    Path bundle(String name, String revision) throws IOException {
        name(name); revision(revision);
        if (revision.equals("none")) throw new IOException("Save a draft first");
        Path folder = drafts.resolve(name).resolve(revision); check(folder); return folder;
    }
    static boolean oggAudio(byte[] bytes) {
        if (bytes.length < 28 || bytes[0] != 'O' || bytes[1] != 'g' || bytes[2] != 'g' || bytes[3] != 'S' || bytes[4] != 0 || (bytes[5] & 2) == 0) return false;
        int segments=Byte.toUnsignedInt(bytes[26]), offset=27+segments, length=0;
        if (segments==0 || offset>bytes.length) return false;
        for(int i=0;i<segments;i++) {int count=Byte.toUnsignedInt(bytes[27+i]);length+=count;if(count<255)break;}
        if (length>bytes.length-offset) return false;
        // Identify audio rather than allowing arbitrary Ogg/video bytes. The
        // browser decoder subsequently validates the complete codec stream.
        return length>=30 && bytes[offset]==1 && new String(bytes,offset+1,6,java.nio.charset.StandardCharsets.US_ASCII).equals("vorbis") ||
            length>=19 && new String(bytes,offset,8,java.nio.charset.StandardCharsets.US_ASCII).equals("OpusHead");
    }
    private static Stamp stamp(Path path) throws IOException {
        check(path);
        if (!Files.exists(path, NOFOLLOW_LINKS)) return null;
        var attr = Files.readAttributes(path, BasicFileAttributes.class, NOFOLLOW_LINKS);
        if (!attr.isRegularFile()) throw new IOException("Unsafe template file");
        return new Stamp(attr.size(), attr.lastModifiedTime(), attr.fileKey());
    }
    private static List<Path> entries(Path path) throws IOException {
        check(path);
        try (var stream = Files.list(path)) {
            var files = stream.limit(1025).toList();
            if (files.size() > 1024) throw new IOException("Template directory exceeds the 1024-entry scan limit");
            return files;
        }
    }
    private boolean validName(String name) { try { name(name); return true; } catch (IOException invalid) { return false; } }
    public synchronized Snapshot active(String name) throws IOException {
        name(name); Path path = active.resolve(name).resolve("template.json");
        if (!Files.exists(path, NOFOLLOW_LINKS)) { check(path.getParent()); return new Snapshot(null, "none", "none"); }
        byte[] bytes = read(path, TemplateJson.MAX_BYTES);
        return new Snapshot(bytes, AccessSessions.digest(bytes), "none");
    }
    public synchronized Snapshot draft(String name) throws IOException {
        name(name); Path path = drafts.resolve(name + ".draft");
        check(path);
        if (!Files.exists(path, NOFOLLOW_LINKS)) return new Snapshot(null, "none", "none");
        byte[] envelope = read(path, TemplateJson.MAX_BYTES * 2);
        try {
            Properties props = new Properties(); props.load(new ByteArrayInputStream(envelope));
            String base = props.getProperty("base_revision"); revision(base);
            byte[] content = Base64.getDecoder().decode(props.getProperty("content", ""));
            validator.inspect(content, false);
            // A successful publish leaves the reviewed draft intact. Reopening
            // that identical draft must use the current active baseline.
            Snapshot current = active(name);
            if (AccessSessions.digest(content).equals(current.revision())) base = current.revision();
            String revision = AccessSessions.digest(envelope), format = props.getProperty("media_bundle", "");
            if (!format.isEmpty() && !format.equals("1")) throw new IOException("Unsupported stored draft media format");
            if (format.equals("1") && !Arrays.equals(read(bundle(name, revision).resolve("template.json"), TemplateJson.MAX_BYTES), content)) throw new IOException("Draft snapshot JSON changed or is missing; reload/review the draft before saving");
            return new Snapshot(content, revision, base, PresetMedia.preset(props.getProperty("preset", "")), format.equals("1"));
        } catch (IllegalArgumentException error) { throw new IOException("Invalid stored draft", error); }
    }
    public synchronized Snapshot save(String name, byte[] content, String expectedDraft, String expectedActive) throws IOException {
        return save(name, content, expectedDraft, expectedActive, null);
    }
    public synchronized Snapshot save(String name, byte[] content, String expectedDraft, String expectedActive, String sourcePreset) throws IOException {
        return save(name, content, expectedDraft, expectedActive, sourcePreset, () -> {});
    }
    synchronized Snapshot save(String name, byte[] content, String expectedDraft, String expectedActive, String sourcePreset, CommitCheck authorized) throws IOException {
        return save(name,content,expectedDraft,expectedActive,sourcePreset,null,authorized);
    }
    synchronized Snapshot save(String name, byte[] content, String expectedDraft, String expectedActive, String sourcePreset, String sourceDraft, CommitCheck authorized) throws IOException {
        name(name); revision(expectedDraft); revision(expectedActive);
        validator.inspect(content, false); // Incomplete known settings can be saved, not published.
        Snapshot before = draft(name), current = active(name);
        Properties oldProps=before.content()!=null?draftProperties(name):new Properties();
        DraftSounds previousSounds=DraftSounds.read(oldProps);Map<String,String> activeSounds=DraftSounds.hashes(active.resolve(name));
        String preset = sourcePreset == null ? before.preset() : PresetMedia.preset(sourcePreset);
        if (!current.revision().equals(expectedActive)) throw new Conflict("Active template changed. Load and review it before saving a new draft.");
        // A lost response/repeat click with identical content is safe to retry.
        if (sourceDraft==null && !previousSounds.publishedChanges(activeSounds) && oldProps.getProperty("sound_media_version","").equals("1") && oldProps.getProperty("base_revision","").equals(expectedActive) && before.mediaBundle() && Arrays.equals(before.content(), content) && before.baseRevision().equals(expectedActive) && before.preset().equals(preset)) { authorized.check(); return before; }
        if (!before.revision().equals(expectedDraft)) throw new Conflict("Draft changed. Reload it before saving.");
        Path target = drafts.resolve(name + ".draft");
        if (before.content() == null && !names().contains(name) && names().size() >= 256) throw new IOException("Template limit reached (256)");
        Snapshot inherited=null;String inheritedName=null;Path inheritedFolder=null;boolean inheritedActive=false;
        if(sourceDraft!=null){String[] source=sourceDraft.split(":",-1);inheritedActive=source.length==3&&source[0].equals("active");if(!inheritedActive&&source.length!=2)throw new IOException("Invalid source draft/template");int index=inheritedActive?1:0;name(source[index]);revision(source[index+1]);inherited=inheritedActive?active(source[index]):draft(source[index]);if(inherited.content()==null||!inheritedActive&&!inherited.mediaBundle()||!inherited.revision().equals(source[index+1]))throw new Conflict("Source draft/template changed; reload it before copying its sounds");inheritedName=source[index];inheritedFolder=inheritedActive?active.resolve(inheritedName):bundle(inheritedName,inherited.revision());if(!inheritedName.equals(name)&&!previousSounds.uploaded().isEmpty())throw new Conflict("Use a new template name to copy another template's media; this destination already has uploaded sounds");}
        DraftSounds sounds=inherited!=null&&!inheritedName.equals(name)?inheritedActive?new DraftSounds(Map.of(),Map.of()):soundState(inheritedName):previousSounds.acceptPublished(activeSounds);
        if(!oldProps.getProperty("sound_media_version","").equals("1")||!oldProps.getProperty("base_revision","").equals(expectedActive)||inherited!=null&&!inheritedName.equals(name))sounds=sounds.rebased(activeSounds);
        Properties props = new Properties(); props.setProperty("base_revision", expectedActive);
        props.setProperty("generation",UUID.randomUUID().toString());
        props.setProperty("content", Base64.getEncoder().encodeToString(content));
        props.setProperty("media_bundle", "1");
        if (!preset.isEmpty()) props.setProperty("preset", preset);
        sounds.write(props);
        ByteArrayOutputStream output = new ByteArrayOutputStream(); props.store(output, "Workshop draft, not active gameplay");
        byte[] envelope = output.toByteArray();
        Path source = inherited!=null&&!inheritedName.equals(name)?inheritedFolder:before.mediaBundle() && before.preset().equals(preset) ? bundle(name, before.revision()) :
            !preset.isEmpty() ? presets.resolve(preset) : current.content() != null ? active.resolve(name) : null;
        mediaQuota.reserve(PresetMedia.measure(source)+(before.mediaBundle()&&!before.preset().equals(preset)?PresetMedia.measure(bundle(name,before.revision())):0)+(long)content.length*2+envelope.length*2L);
        Path parent = drafts.resolve(name); check(parent);
        boolean parentExisted = Files.exists(parent, NOFOLLOW_LINKS);
        directory(parent);
        try {
            pruneBundles(parent, before.revision(), null);
            if (entries(parent).size() >= 4) throw new IOException("Draft snapshot cleanup is blocked; check its folder permissions or unexpected files before saving");
            try (var media = PresetMedia.prepareFrom(drafts, source)) {
                if(before.mediaBundle()&&inherited==null&&!before.preset().equals(preset))media.carryUploads(bundle(name,before.revision()),sounds);
                sounds.verify(media);
                CommitCheck recheck = () -> {
                    authorized.check();
                    if (!draft(name).revision().equals(before.revision()) || !active(name).revision().equals(expectedActive)) throw new Conflict("Template changed during saving; no replacement was made");
                };
                Path snapshotFolder = bundle(name, AccessSessions.digest(envelope));
                media.installNew(snapshotFolder, content, recheck);
                atomic(target, envelope, before.content() != null, recheck);
                media.commit();
                try { pruneBundles(parent, snapshotFolder.getFileName().toString(), before.mediaBundle() ? before.revision() : null); }
                catch (IOException cleanup) { draftCleanupWarning(); } // The manifest has already committed.
            }
        } finally {
            if (!parentExisted) try { check(parent); Files.deleteIfExists(parent); }
            catch (DirectoryNotEmptyException retained) { /* A committed snapshot owns this folder. */ }
            catch (IOException cleanup) { draftCleanupWarning(); } // Never hide the save result or its original error.
        }
        invalidate(name);
        return draft(name);
    }
    private Properties draftProperties(String name)throws IOException{Properties props=new Properties();props.load(new ByteArrayInputStream(read(drafts.resolve(name+".draft"),TemplateJson.MAX_BYTES*2)));return props;}
    private DraftSounds soundState(String name)throws IOException{return DraftSounds.read(draftProperties(name));}
    synchronized UploadListing uploadListing(String name)throws IOException{
        Snapshot saved=draft(name);if(!saved.mediaBundle())throw new IOException("Save or load a draft before uploading sounds");
        Path folder=bundle(name,saved.revision());DraftSounds state=soundState(name);Map<String,String> hashes=DraftSounds.hashes(folder);List<UploadFile> files=new ArrayList<>();
        for(var entry:hashes.entrySet()){String relative=entry.getKey(),kind=relative.startsWith("sounds/voice-lines/")?"voices":"sounds",filename=relative.substring(relative.lastIndexOf('/')+1);files.add(new UploadFile(kind,filename,entry.getValue(),Files.size(folder.resolve(relative)),state.uploaded().containsKey(relative)));}
        return new UploadListing(name,saved.revision(),PresetMedia.measure(folder),List.copyOf(files));
    }
    synchronized void checkUpload(String name,String kind,String filename,String expected,String fileRevision)throws IOException{
        name(name);revision(expected);revision(fileRevision);String relative=DraftSounds.relative(kind,filename);Snapshot saved=draft(name);
        if(!saved.mediaBundle())throw new IOException("Save or load a draft before uploading sounds");
        if(!saved.revision().equals(expected))throw new Conflict("Draft changed. Reload and review it before uploading.");
        Path target=bundle(name,saved.revision()).resolve(relative);check(target);
        String current=Files.exists(target,NOFOLLOW_LINKS)?AccessSessions.digest(read(target,AUDIO_MAX_BYTES)):"none";
        if(!current.equals(fileRevision))throw new Conflict("Draft sound changed. Refresh the sound list and confirm its replacement again.");
        if(Files.exists(target,NOFOLLOW_LINKS)&&!Files.isRegularFile(target,NOFOLLOW_LINKS))throw new IOException("Unsafe draft sound file");
    }
    synchronized Snapshot uploadSound(String name,String kind,String filename,String expected,String fileRevision,byte[] bytes,CommitCheck authorized)throws IOException{
        checkUpload(name,kind,filename,expected,fileRevision);Snapshot before=draft(name);Path source=bundle(name,before.revision());String relative=DraftSounds.relative(kind,filename);
        DraftSounds sounds=soundState(name);Properties props=draftProperties(name);
        props.setProperty("generation",UUID.randomUUID().toString());
        Map<String,String> activeSounds=DraftSounds.hashes(active.resolve(name));sounds=sounds.acceptPublished(activeSounds);
        if(!props.getProperty("sound_media_version","").equals("1")||!props.getProperty("base_revision","").equals(before.baseRevision()))sounds=sounds.rebased(activeSounds);
        sounds.with(relative,AccessSessions.digest(bytes)).write(props);
        ByteArrayOutputStream output=new ByteArrayOutputStream();props.store(output,"Workshop draft with uploaded audio, not active gameplay");byte[] envelope=output.toByteArray();
        Path original=source.resolve(relative);byte[] previous=Files.exists(original,NOFOLLOW_LINKS)?read(original,AUDIO_MAX_BYTES):null;
        if(previous!=null&&Arrays.equals(previous,bytes)){authorized.check();return before;}
        mediaQuota.reserve(PresetMedia.measure(source)+bytes.length+envelope.length*2L+before.content().length*2L+(previous==null?0:previous.length));
        Path backup=null;boolean committed=false;
        try(var media=PresetMedia.prepareFrom(drafts,source)){
            sounds.verify(media);media.replaceAudio(relative,bytes);
            CommitCheck recheck=()->{authorized.check();if(!draft(name).revision().equals(before.revision()))throw new Conflict("Draft changed during sound upload; no replacement was made");};
            if(previous!=null)backup=soundBackups.create(name,relative,previous);
            Path parent=drafts.resolve(name);pruneBundles(parent,before.revision(),null);if(entries(parent).size()>=4)throw new IOException("Draft snapshot cleanup is blocked; check its folder permissions");
            Path next=bundle(name,AccessSessions.digest(envelope));media.installNew(next,before.content(),recheck);atomic(drafts.resolve(name+".draft"),envelope,true,recheck);media.commit();committed=true;
            try{pruneBundles(parent,next.getFileName().toString(),before.revision());}catch(IOException cleanup){draftCleanupWarning();}
        }finally{if(!committed&&backup!=null)SoundBackups.rollback(backup,previous);}
        invalidate(name);return draft(name);
    }
    public synchronized String publish(String name, String expectedDraft) throws IOException {
        return publish(name, expectedDraft, () -> {});
    }
    synchronized String publish(String name, String expectedDraft, CommitCheck authorized) throws IOException {
        synchronized (activeWriteLock) { return publishLocked(name, expectedDraft, authorized); }
    }
    private String publishLocked(String name, String expectedDraft, CommitCheck authorized) throws IOException {
        name(name); revision(expectedDraft); Snapshot draft = draft(name);
        if (draft.content() == null) throw new IOException("Save a draft first");
        if (!draft.revision().equals(expectedDraft)) throw new Conflict("Draft changed. Reload before publishing.");
        validator.inspect(draft.content(), true);
        // The existing 0.2.1 validator above already applied this parser's bounds.
        var musicCheck = musicLibrary.referenceCheck(TemplateJson.parse(draft.content()));
        Snapshot current = active(name);
        String published = AccessSessions.digest(draft.content());
        boolean replacingJson = !published.equals(current.revision());
        if (replacingJson && !current.revision().equals(draft.baseRevision())) throw new Conflict("Active template changed since this draft was saved. Load and review the active version first.");
        Path target = active.resolve(name).resolve("template.json"); check(target.getParent());
        Path source = draft.mediaBundle() ? bundle(name, draft.revision()) : draft.preset().isEmpty() ? null : presets.resolve(draft.preset());
        PublishedSounds.recover(active,soundJournals,soundBackups);
        DraftSounds sounds=draft.mediaBundle()?soundState(name):new DraftSounds(Map.of(),Map.of());
        mediaQuota.reserve(PresetMedia.measure(source)*2+draft.content().length*4L+TemplateJson.MAX_BYTES*2L);
        try (var media = PresetMedia.prepareFrom(active, source)) {
            sounds.verify(media);
            CommitCheck recheck = () -> {
                authorized.check();
                musicCheck.check();
                if (!active(name).revision().equals(current.revision()) || !draft(name).revision().equals(expectedDraft)) throw new Conflict("Template changed during publishing; no replacement was made");
            };
            if (!Files.exists(target.getParent(), NOFOLLOW_LINKS)) {
                media.publishNew(target.getParent(), draft.content(), recheck);
            } else {
                media.validateTarget(target.getParent());
                if (replacingJson && current.content() != null) {
                    if (entries(backups).stream().filter(p -> p.getFileName().toString().endsWith(".json")).count() >= 32) throw new IOException("Backup limit reached (32). Archive backups manually before publishing again.");
                    // Backup success is required BEFORE the active file is replaced.
                    atomic(backups.resolve(name + "-" + UUID.randomUUID() + ".json"), current.content(), false, () -> {});
                }
                if(sounds.uploaded().isEmpty()){
                    recheck.check();media.installMissing(target.getParent());
                    if(replacingJson)atomic(target,draft.content(),current.content()!=null,recheck);else recheck.check();media.commit();
                }else try(var publication=PublishedSounds.prepare(active,soundJournals,name,current.content(),draft.content(),media,sounds,soundBackups)){
                    recheck.check();media.installMissing(target.getParent());publication.install();
                    if(replacingJson)atomic(target,draft.content(),current.content()!=null,recheck);else recheck.check();
                    publication.commit();media.commit();
                }
            }
        }
        invalidate(name);
        return published;
    }
    private static void pruneBundles(Path parent, String keep, String previous) throws IOException {
        for (Path path : entries(parent)) {
            String revision = path.getFileName().toString();
            if (revision.matches("[a-f0-9]{64}") && !revision.equals(keep) && !revision.equals(previous)) PresetMedia.retire(path);
        }
    }
    private static void draftCleanupWarning() { org.slf4j.LoggerFactory.getLogger("clockwork_bosses_web").warn("Workshop draft snapshot cleanup incomplete; check filesystem permissions"); }
    static void directory(Path path) throws IOException { check(path); Files.createDirectories(path); check(path); }
    /** Reject linked/reparse ancestors as well as leaves before each disk operation.
     * Same-privilege local administrators are trusted; OS directory permissions must
     * prevent untrusted processes racing these checks (no portable filesystem CAS).
     */
    static void check(Path path) throws IOException {
        clockwork.bosses.files.ClockworkBossPaths.checkAncestors(path);
    }
    static byte[] read(Path path, int max) throws IOException {
        check(path);
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS) || Files.size(path) > max) throw new IOException("Template file is missing, unsafe or too large");
        try (var channel = Files.newByteChannel(path, Set.of(READ, NOFOLLOW_LINKS)); var input = java.nio.channels.Channels.newInputStream(channel)) {
            byte[] bytes = input.readNBytes(max + 1); if (bytes.length > max) throw new IOException("Template exceeds size limit"); return bytes;
        }
    }
    @FunctionalInterface interface CommitCheck { void check() throws IOException; }
    @FunctionalInterface interface StagedCommitCheck { void check(Path ownedStage) throws IOException; }
    static void atomic(Path target, byte[] bytes, boolean replacing, CommitCheck expected) throws IOException {
        atomicStaged(target, bytes, replacing, ownedStage -> expected.check());
    }
    /** The exact private staging path lets a complete directory revision scan
     * exclude its own unpublished file, without trusting arbitrary temp names. */
    static void atomicStaged(Path target, byte[] bytes, boolean replacing, StagedCommitCheck expected) throws IOException {
        check(target); directory(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), ".workshop-", ".tmp");
        try {
            try (var channel = FileChannel.open(temporary, WRITE, NOFOLLOW_LINKS)) {
                java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true);
            }
            check(target);
            if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Workshop write stopped before commit");
            expected.check(temporary);
            if (replacing) Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            else {
                // Atomic, no-replacement publication. Unlike ATOMIC_MOVE, a hard-link
                // create cannot silently overwrite a concurrent newly created file.
                Files.createLink(target, temporary);
            }
        } catch (FileAlreadyExistsException conflict) { throw new Conflict("File appeared concurrently; no replacement made"); }
        finally {
            // Cleanup must never turn an already committed save into a false
            // failure, nor hide the original write/backup error.
            try { Files.deleteIfExists(temporary); }
            catch (IOException cleanup) { org.slf4j.LoggerFactory.getLogger("clockwork_bosses_web").warn("Workshop temporary-file cleanup failed: {}", temporary, cleanup); }
        }
    }
}
