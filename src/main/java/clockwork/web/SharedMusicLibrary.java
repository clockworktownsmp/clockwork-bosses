package clockwork.web;

import clockwork.bosses.ClockworkBossMusicData;
import clockwork.bosses.ClockworkBossSoundPayload;
import clockwork.bosses.ClockworkBossTemplateFormat;
import com.google.gson.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

/** Server-owned music, independent of template media snapshots. File workers own
 * every method that touches disk. The native writer monitor serializes library
 * mutations with native/Workshop template publication; live Minecraft objects
 * never escape the separately supplied, main-thread reference snapshot. */
public final class SharedMusicLibrary {
    public static final int MAX_FILES = 64, MAX_REFERENCE_FILES = 768, MAX_RUNNING = 256;
    public static final int MAX_REFERENCES = 1024, MAX_LISTED_TRACKS = 1088;
    public static final long MAX_BYTES = 64L * 1024 * 1024, MAX_REFERENCE_BYTES = 16L * 1024 * 1024;
    private static final AtomicLong PUBLICATION = new AtomicLong();
    private static final List<String> SOURCES = List.of("active", "drafts", "presets", "running");
    private static final Map<String, String> BUNDLED = Map.of(
            "boss1.ogg", "0073610006a2dc0914469e4460f53c47bce8576d53198dd98997e8f3d7609a07",
            "boss2.ogg", "1f498c058329537ba455e3ac6d7bc0b82b3c55f9630252d4a4c6007024d78dc6");
    @FunctionalInterface public interface Check { void check() throws IOException; }
    @FunctionalInterface public interface RunningReferences { Map<String, List<String>> capture() throws IOException; }
    private final Path root, active, drafts, presets;
    private final Object writer;
    private final SoundBackups backups;
    private final MediaQuota quota;
    private final Map<String, UploadedAudio.Details> validated = new LinkedHashMap<>();
    private final Map<String, CachedTrack> metadata = new HashMap<>();
    private volatile RunningReferences running = Map::of;

    public SharedMusicLibrary(Path config, Object writer) {
        Path configRoot = config.toAbsolutePath().normalize();
        Path core = configRoot.resolve("clockwork-bosses"), web = configRoot.resolve("clockwork-bosses-web");
        root = core.resolve("music"); active = core.resolve("boss-templates"); presets = core.resolve("boss-presets");
        drafts = web.resolve("drafts"); this.writer = Objects.requireNonNull(writer);
        Path backupRoot = core.resolve("music-backups"); backups = new SoundBackups(backupRoot);
        quota = new MediaQuota(active, drafts, web.resolve("backups"), web.resolve("sound-backups"),
                web.resolve("sound-publish-journals"), root, backupRoot);
    }

    /** Set by the Minecraft adapter. The callback returns immutable strings only
     * and must capture them on the owning server thread with a bounded wait. */
    public void runningReferences(RunningReferences provider) { running = Objects.requireNonNull(provider); }
    public static long publicationEpoch() { return PUBLICATION.get(); }

    public static JsonObject capabilities() {
        JsonObject r = new JsonObject(); r.addProperty("enabled", true); r.addProperty("version", 1);
        r.addProperty("codec", "vorbis"); r.addProperty("max_file_bytes", UploadedAudio.MUSIC_MAX_BYTES);
        r.addProperty("max_music_seconds", 600); r.addProperty("max_files", MAX_FILES);
        r.addProperty("max_library_bytes", MAX_BYTES); r.addProperty("max_storage_bytes", MediaQuota.MAX_STORAGE);
        r.addProperty("uploads_per_minute", 12); r.addProperty("max_reference_files", MAX_REFERENCE_FILES);
        r.addProperty("max_running_references", MAX_RUNNING); r.addProperty("max_references", MAX_REFERENCES);
        r.addProperty("max_reference_bytes", MAX_REFERENCE_BYTES); r.addProperty("max_listed_tracks", MAX_LISTED_TRACKS);
        JsonArray sources = new JsonArray(); SOURCES.forEach(sources::add); r.add("references", sources);
        r.addProperty("mutations", "owner"); r.addProperty("atomic_replacement", true); return r;
    }

    public static void filename(String name) throws IOException {
        if (!ClockworkBossSoundPayload.isValidFileName(name)
                || name.substring(0, name.indexOf('.')).matches("con|prn|aux|nul|com[1-9]|lpt[1-9]"))
            throw new IOException("Use a path-free lowercase OGG basename, without reserved device names");
    }

    /** Full bounded Vorbis decode is reused for seeding, uploads, previews and
     * gameplay loads. Listing metadata alone is never decode acceptance. */
    public byte[] readValidated(String name) throws IOException {
        filename(name); Path path = root.resolve(name); TemplateFiles.check(path);
        if (!Files.exists(path, NOFOLLOW_LINKS)) throw new NoSuchFileException(name);
        byte[] bytes = TemplateFiles.read(path, UploadedAudio.MUSIC_MAX_BYTES);
        validate(name, bytes); return bytes;
    }

    private UploadedAudio.Details validate(String name, byte[] bytes) throws IOException {
        String key = name + ':' + AccessSessions.digest(bytes);
        synchronized (validated) { var known = validated.get(key); if (known != null) return known; }
        var details = UploadedAudio.validate(bytes, true);
        synchronized (validated) {
            validated.put(key, details);
            while (validated.size() > MAX_FILES) validated.remove(validated.keySet().iterator().next());
        }
        return details;
    }

    public void requireReference(JsonObject document) throws IOException {
        referenceCheck(document).check();
    }

    /** Decode once before staging, then cheaply bind the dependency's exact
     * bytes again at publication. No missing reference is converted or copied. */
    public Check referenceCheck(JsonObject document) throws IOException {
        String name = libraryReference(document);
        if (name == null) return () -> {};
        String revision = AccessSessions.digest(readValidated(name));
        return () -> {
            if (!revision.equals(AccessSessions.digest(TemplateFiles.read(root.resolve(name), UploadedAudio.MUSIC_MAX_BYTES))))
                throw new TemplateFiles.Conflict("Shared music dependency changed; reload and review before publishing");
        };
    }

    /** Missing/null source means the original template-local behavior. Invalid
     * selectors fail closed here; they are also rejected by the common schema. */
    public static String libraryReference(JsonObject document) throws IOException {
        JsonElement section = document.get("music");
        if (section == null || section.isJsonNull()) return null;
        if (!section.isJsonObject()) throw new IOException("music must be an object");
        JsonObject music = section.getAsJsonObject(); JsonElement selected = music.get("source");
        if (selected == null || selected.isJsonNull()) return null;
        if (!selected.isJsonPrimitive() || !selected.getAsJsonPrimitive().isString()
                || !List.of("template", "library").contains(selected.getAsString()))
            throw new IOException("music.source must be template or library");
        if (!selected.getAsString().equals("library")) return null;
        JsonElement file = music.get("file");
        if (file == null || file.isJsonNull() || file.isJsonPrimitive() && file.getAsString().isEmpty()) return null;
        if (!file.isJsonPrimitive() || !file.getAsJsonPrimitive().isString()) throw new IOException("music.file must be an OGG basename");
        filename(file.getAsString()); return file.getAsString();
    }

    /** Only absent bundled names are materialized. Publication is no-replace,
     * so an administrator file appearing during decode also wins. */
    public int seedBundled() throws IOException {
        synchronized (writer) {
            TemplateFiles.directory(root); int created = 0;
            for (var entry : new TreeMap<>(BUNDLED).entrySet()) {
                Path target = root.resolve(entry.getKey()); TemplateFiles.check(target);
                if (Files.exists(target, NOFOLLOW_LINKS)) continue;
                byte[] bytes;
                try (InputStream in = SharedMusicLibrary.class.getResourceAsStream("/music/" + entry.getKey())) {
                    if (in == null) throw new IOException("Bundled music is missing from the mod jar: " + entry.getKey());
                    bytes = in.readNBytes(UploadedAudio.MUSIC_MAX_BYTES + 1);
                }
                if (!AccessSessions.digest(bytes).equals(entry.getValue())) throw new IOException("Bundled music digest mismatch");
                validate(entry.getKey(), bytes);
                Catalog before = catalog();
                if (before.tracks.size() >= MAX_FILES || before.bytes + bytes.length > MAX_BYTES)
                    throw new IOException("Shared music library exceeds 64 files / 64 MiB");
                quota.reserve(bytes.length);
                try { TemplateFiles.atomic(target, bytes, false, () -> {}); created++; PUBLICATION.incrementAndGet(); }
                catch (TemplateFiles.Conflict appeared) { /* Preserve the administrator's concurrent file. */ }
            }
            return created;
        }
    }

    public JsonObject listing() throws IOException {
        synchronized (writer) { return listing(catalog(false), references()); }
    }

    /** Native editors receive filename metadata only, without exposing owner
     * draft/reference names. Missing dependencies remain visible, never fallback. */
    public JsonObject editorCatalog() throws IOException {
        synchronized (writer) {
            Catalog catalog = catalog(false); JsonObject r = new JsonObject(); JsonArray tracks = new JsonArray();
            catalog.tracks.values().forEach(t -> tracks.add(t.describe())); r.add("tracks", tracks);
            r.addProperty("revision", catalog.revision); return r;
        }
    }

    public JsonObject put(String name, byte[] bytes, String expected, String fileRevision,
                          String impact, boolean overwrite, Check authorized) throws IOException {
        filename(name); TemplateFiles.revision(expected); TemplateFiles.revision(fileRevision); TemplateFiles.revision(impact);
        var details = validate(name, bytes);
        synchronized (writer) {
            authorized.check(); Catalog before = catalog(); References refs = references();
            verify(before, refs, name, expected, fileRevision, impact);
            Track previous = before.tracks.get(name);
            if (previous != null && !overwrite) throw new IOException("Confirm shared music replacement and its reference impact");
            if (previous == null && before.tracks.size() >= MAX_FILES) throw new IOException("Shared music library has 64 tracks");
            if (before.bytes - (previous == null ? 0 : previous.bytes) + bytes.length > MAX_BYTES)
                throw new IOException("Shared music library exceeds 64 MiB");
            byte[] original = previous == null ? null : TemplateFiles.read(root.resolve(name), UploadedAudio.MUSIC_MAX_BYTES);
            quota.reserve(bytes.length + (original == null ? 0 : original.length));
            if (original != null) backups.create("library", "sounds/" + name, original);
            SortedMap<String, Track> projected = new TreeMap<>(before.tracks);
            projected.put(name, new Track(name, AccessSessions.digest(bytes), bytes.length,
                    (long) (details.seconds() * 1000), details.channels(), details.rate(), ""));
            JsonObject result = receipt(previous == null ? "created" : "replaced", name, original != null, fileRevision, refs, describeCatalog(projected));
            TemplateFiles.atomicStaged(root.resolve(name), bytes, original != null, ownedStage -> {
                authorized.check(); verify(catalog(true, ownedStage), references(), name, expected, fileRevision, impact);
            });
            PUBLICATION.incrementAndGet();
            metadata.remove(name); return result;
        }
    }

    /** Deletion is an explicit global operation, with the same complete impact
     * guard and immutable raw backup as replacement. Atomic rename withdraws the
     * directory entry; cleanup failure cannot turn success into a false retry. */
    public JsonObject delete(String name, String expected, String fileRevision, String impact, Check authorized) throws IOException {
        filename(name); TemplateFiles.revision(expected); TemplateFiles.revision(fileRevision); TemplateFiles.revision(impact);
        synchronized (writer) {
            authorized.check(); Catalog before = catalog(); References refs = references();
            verify(before, refs, name, expected, fileRevision, impact);
            if (refs.count(name) != 0) throw new TemplateFiles.Conflict("Shared music is in use; remove all saved/running references before deleting");
            if (!before.tracks.containsKey(name)) throw new TemplateFiles.Conflict("Shared music is already missing");
            byte[] original = TemplateFiles.read(root.resolve(name), UploadedAudio.MUSIC_MAX_BYTES); quota.reserve(original.length);
            backups.create("library", "sounds/" + name, original);
            SortedMap<String, Track> projected = new TreeMap<>(before.tracks); projected.remove(name);
            JsonObject result = receipt("deleted", name, true, fileRevision, refs, describeCatalog(projected));
            authorized.check(); verify(catalog(), references(), name, expected, fileRevision, impact);
            Path retired = root.resolve(".retired-" + UUID.randomUUID() + ".tmp"); TemplateFiles.check(retired);
            Files.move(root.resolve(name), retired, StandardCopyOption.ATOMIC_MOVE);
            PUBLICATION.incrementAndGet();
            try { Files.delete(retired); }
            catch (IOException cleanup) { org.slf4j.LoggerFactory.getLogger("clockwork_bosses_web").warn("Shared music retired-file cleanup failed; check library folder permissions"); }
            metadata.remove(name); return result;
        }
    }

    private static void verify(Catalog catalog, References refs, String name, String expected, String fileRevision, String impact) throws IOException {
        String actual = catalog.tracks.containsKey(name) ? catalog.tracks.get(name).revision : "none";
        if (!catalog.revision.equals(expected) || !actual.equals(fileRevision) || !refs.revision.equals(impact))
            throw new TemplateFiles.Conflict("Shared music or its reference impact changed; reload and review before changing it");
    }
    private JsonObject receipt(String action, String name, boolean backup, String old, References confirmed, Catalog after) {
        JsonObject r = new JsonObject();
        r.addProperty("action", action); r.addProperty("filename", name); r.addProperty("revision", after.revision);
        r.addProperty("impact_revision", confirmed.revision); r.addProperty("track_revision", after.tracks.containsKey(name) ? after.tracks.get(name).revision : "none");
        r.addProperty("backup_created", backup); r.add("backed_up_revision", backup ? new JsonPrimitive(old) : JsonNull.INSTANCE);
        r.add("references", confirmed.describe(name)); r.addProperty("reload_required", true); return r;
    }
    private JsonObject listing(Catalog catalog, References refs) throws IOException {
        TreeSet<String> names = new TreeSet<>(catalog.tracks.keySet()); names.addAll(refs.tracks.keySet());
        if (names.size() > MAX_LISTED_TRACKS) throw new IOException("Shared music listing exceeds the complete reference bound");
        JsonArray tracks = new JsonArray();
        for (String name : names) {
            Track track = catalog.tracks.get(name); JsonObject item;
            if (track == null) { item = new Track(name, "none", 0, 0, null, null, "Shared OGG is missing from this server").describe(); }
            else item = track.describe();
            item.add("references", refs.describe(name)); tracks.add(item);
        }
        JsonObject r = new JsonObject(); r.addProperty("revision", catalog.revision); r.addProperty("impact_revision", refs.revision);
        r.add("tracks", tracks); r.addProperty("storage_bytes", quota.used()); r.addProperty("total_bytes", catalog.bytes);
        r.addProperty("count", catalog.tracks.size()); r.add("limits", capabilities()); return r;
    }

    private record Track(String name, String revision, long bytes, long duration, Integer channels, Integer rate, String diagnostic) {
        JsonObject describe() {
            JsonObject r = new JsonObject(); r.addProperty("filename", name); r.addProperty("revision", revision); r.addProperty("bytes", bytes);
            r.add("duration_seconds", duration > 0 ? new JsonPrimitive(duration / 1000.0) : JsonNull.INSTANCE);
            r.add("channels", channels == null ? JsonNull.INSTANCE : new JsonPrimitive(channels));
            r.add("sample_rate", rate == null ? JsonNull.INSTANCE : new JsonPrimitive(rate));
            r.addProperty("available", duration > 0); r.addProperty("diagnostic", diagnostic); r.addProperty("built_in", BUNDLED.containsKey(name)); return r;
        }
    }
    private record Catalog(SortedMap<String, Track> tracks, String revision, long bytes) {}
    private record Stamp(long bytes, java.nio.file.attribute.FileTime modified, Object key) {}
    private record CachedTrack(Stamp stamp, Track track) {}
    private Catalog catalog() throws IOException { return catalog(true); }
    private Catalog catalog(boolean fresh) throws IOException { return catalog(fresh, null); }
    private Catalog catalog(boolean fresh, Path ownedStage) throws IOException {
        TemplateFiles.check(root); SortedMap<String, Track> tracks = new TreeMap<>(); long total = 0;
        if (!Files.exists(root, NOFOLLOW_LINKS)) return new Catalog(tracks, AccessSessions.digest(new byte[0]), 0);
        for (Path path : entries(root, MAX_FILES + (ownedStage == null ? 0 : 1))) {
            if (path.equals(ownedStage)) continue;
            String name = path.getFileName().toString(); filename(name);
            var attributes = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class, NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.size() > UploadedAudio.MUSIC_MAX_BYTES) throw new IOException("Unsafe or oversized shared music file");
            Stamp stamp = new Stamp(attributes.size(), attributes.lastModifiedTime(), attributes.fileKey());
            CachedTrack known = metadata.get(name);
            if (!fresh && known != null && known.stamp.equals(stamp)) {
                tracks.put(name, known.track); total += known.track.bytes;
                if (total > MAX_BYTES) throw new IOException("Shared music library exceeds 64 MiB");
                continue;
            }
            byte[] bytes = TemplateFiles.read(path, UploadedAudio.MUSIC_MAX_BYTES); total += bytes.length;
            if (total > MAX_BYTES) throw new IOException("Shared music library exceeds 64 MiB");
            String hash = AccessSessions.digest(bytes); long duration = ClockworkBossMusicData.validatedDurationMillis(bytes);
            Integer channels = null, rate = null;
            if (duration > 0) { int header = 27 + Byte.toUnsignedInt(bytes[26]); channels = Byte.toUnsignedInt(bytes[header + 11]); rate = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(header + 12); }
            String diagnostic = duration > 0 ? "" : "Invalid bounded OGG metadata; full validation is required before playback/publication";
            // The legacy template reader accepts rates up to 192 kHz. Shared
            // uploads/playback use the existing full validator's 96 kHz bound;
            // listing must describe that same contract without a full decode.
            if (rate != null && (rate < 8000 || rate > UploadedAudio.MAX_RATE || channels < 1 || channels > 2)) {
                duration = 0; channels = null; rate = null; diagnostic = "Shared music requires mono/stereo Vorbis at 8–96 kHz";
            }
            Track track = new Track(name, hash, bytes.length, duration, channels, rate,
                    diagnostic);
            tracks.put(name, track); metadata.put(name, new CachedTrack(stamp, track));
        }
        if (tracks.size() > MAX_FILES) throw new IOException("Shared music library exceeds 64 tracks");
        metadata.keySet().retainAll(tracks.keySet());
        return describeCatalog(tracks);
    }
    private static Catalog describeCatalog(SortedMap<String, Track> tracks) {
        StringBuilder identity = new StringBuilder(); tracks.forEach((name, track) -> identity.append(name).append('\0').append(track.revision).append('\0').append(track.bytes).append('\n'));
        return new Catalog(tracks, AccessSessions.digest(identity.toString().getBytes(StandardCharsets.UTF_8)), tracks.values().stream().mapToLong(Track::bytes).sum());
    }
    private static List<Path> entries(Path directory, int maximum) throws IOException {
        TemplateFiles.check(directory); if (!Files.exists(directory, NOFOLLOW_LINKS)) return List.of();
        if (!Files.isDirectory(directory, NOFOLLOW_LINKS)) throw new IOException("Unsafe shared music/reference directory");
        try (var entries = Files.list(directory)) {
            List<Path> result = entries.limit(maximum + 1L).sorted().toList();
            if (result.size() > maximum) throw new IOException("Shared music/reference directory exceeds its bounded scan limit");
            for (Path path : result) TemplateFiles.check(path); return result;
        }
    }

    private static final class References {
        final Map<String, Map<String, SortedSet<String>>> tracks = new TreeMap<>();
        final StringBuilder identity = new StringBuilder(); int files, count; long bytes; String revision;
        void add(String name, String source, String owner) throws IOException {
            filename(name); var references = tracks.computeIfAbsent(name, ignored -> new TreeMap<>());
            if (references.computeIfAbsent(source, ignored -> new TreeSet<>()).add(owner) && ++count > MAX_REFERENCES)
                throw new IOException("Shared music references exceed the complete 1024-reference bound");
        }
        int count(String name) { return tracks.getOrDefault(name, Map.of()).values().stream().mapToInt(Set::size).sum(); }
        JsonObject describe(String name) {
            JsonObject r = new JsonObject(); for (String source : SOURCES) {
                JsonArray names = new JsonArray(); tracks.getOrDefault(name, Map.of()).getOrDefault(source, Collections.emptySortedSet()).forEach(names::add); r.add(source, names);
            }
            r.addProperty("count", count(name)); r.addProperty("truncated", false); return r;
        }
    }
    private References references() throws IOException {
        References refs = new References(); scan(refs, active, "active", false); scan(refs, drafts, "drafts", true); scan(refs, presets, "presets", false);
        Map<String, List<String>> snapshot = running.capture(); int count = 0;
        for (String file : new TreeSet<>(snapshot.keySet())) for (String owner : new TreeSet<>(snapshot.get(file))) {
            if (++count > MAX_RUNNING || owner == null || owner.length() > 128) throw new IOException("Loaded music reference snapshot exceeds its bound");
            refs.add(file, "running", owner); refs.identity.append("running\0").append(file).append('\0').append(owner).append('\n');
        }
        refs.revision = AccessSessions.digest(refs.identity.toString().getBytes(StandardCharsets.UTF_8)); return refs;
    }
    private void scan(References refs, Path directory, String source, boolean draft) throws IOException {
        int count = 0;
        for (Path entry : entries(directory, 1024)) {
            String name = entry.getFileName().toString(); Path path;
            if (draft) {
                if (!name.endsWith(".draft")) continue; name = name.substring(0, name.length() - 6); path = entry;
            } else { if (!Files.isDirectory(entry, NOFOLLOW_LINKS)) continue; path = entry.resolve("template.json"); }
            try { TemplateFiles.name(name); } catch (IOException invalidName) { continue; }
            TemplateFiles.check(path); if (!Files.exists(path, NOFOLLOW_LINKS)) continue;
            if (++count > 256 || ++refs.files > MAX_REFERENCE_FILES) throw new IOException("Shared music reference files exceed the complete scan bound");
            byte[] envelope = TemplateFiles.read(path, ClockworkBossTemplateFormat.MAX_BYTES * (draft ? 2 : 1));
            refs.bytes += envelope.length; if (refs.bytes > MAX_REFERENCE_BYTES) throw new IOException("Shared music reference JSON exceeds 16 MiB");
            byte[] content = envelope;
            if (draft) {
                Properties props = new Properties(); props.load(new ByteArrayInputStream(envelope));
                try { content = Base64.getDecoder().decode(props.getProperty("content", "")); }
                catch (IllegalArgumentException invalid) { throw new IOException("Cannot verify stored draft music references", invalid); }
                if (content.length > ClockworkBossTemplateFormat.MAX_BYTES) throw new IOException("Draft music reference JSON exceeds its bound");
                if (props.getProperty("media_bundle", "").equals("1")) {
                    Path bundled = directory.resolve(name).resolve(AccessSessions.digest(envelope)).resolve("template.json");
                    if (!Arrays.equals(content, TemplateFiles.read(bundled, ClockworkBossTemplateFormat.MAX_BYTES)))
                        throw new IOException("Draft music reference snapshot is missing or changed");
                }
            }
            JsonObject document;
            try (var reader = new StringReader(new String(content, StandardCharsets.UTF_8))) { document = ClockworkBossTemplateFormat.read(reader); }
            String file = libraryReference(document);
            refs.identity.append(source).append('\0').append(name).append('\0').append(AccessSessions.digest(envelope)).append('\n');
            if (file != null) refs.add(file, source, name);
        }
    }
}
