package clockwork.bosses;

import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.LinkOption;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/**
 * Reads and writes per-template JSON and maintains command suggestions. The
 * Clockwork Bosses config root is shared with skin/sound loading so every file
 * feature uses one folder layout. Pre-release folders are not migrated.
 */
public final class ClockworkBossTemplateStore {
    // Bound disk reads even if an administrator accidentally selects a huge file.
    private static final long MAX_TEMPLATE_BYTES = 512 * 1024;
    private static volatile List<String> cachedTemplates = List.of();
    private static volatile List<String> cachedPresets = List.of();
    private static final Path CONFIG_ROOT = FabricLoader.getInstance()
            .getConfigDir()
            .resolve("clockwork-bosses");
    private static final Path DIRECTORY = CONFIG_ROOT.resolve("boss-templates");
    private static final Path PRESET_DIRECTORY = CONFIG_ROOT.resolve("boss-presets");
    // Balance presets contain no sample audio. One separate example owns all clips.
    private static final String SOUND_EXAMPLE = "boss-sounds-example";
    private static final List<String> BUNDLED_PRESETS = List.of(
            "1_player", "2_players", "3_players", "4_players", "5_players", "6_players", SOUND_EXAMPLE);
    private static final List<String> EXAMPLE_AUDIO = List.of(
            "spawn.ogg", "death.ogg", "phase2.ogg", "phase3.ogg", "phase4.ogg",
            "voice-lines/line1.ogg", "voice-lines/line2.ogg", "voice-lines/line3.ogg", "voice-lines/line4.ogg");

    private ClockworkBossTemplateStore() {}

    /** Creates only the active template root; individual templates own their media folders. */
    public static void ensureDirectory() throws Exception {
        createOwnedDirectory(DIRECTORY);
    }

    /**
     * Seeds optional examples into boss-presets during initialization.
     * Administrators activate an example by copying it into boss-templates and reloading.
     * The template cache scans only boss-templates; copying an entire preset
     * folder activates it without an enabled flag or special extension. Existing preset files take
     * precedence, preserving administrator edits across restarts.
     */
    public static synchronized void installBundledPresets() throws Exception {
        ensureDirectory();
        createOwnedDirectory(PRESET_DIRECTORY);
        for (String name : BUNDLED_PRESETS) {
            Path folder = PRESET_DIRECTORY.resolve(name);
            Path destination = folder.resolve("template.json");
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) continue;
            createTemplateFolders(folder);
            String resource = name.equals(SOUND_EXAMPLE)
                    ? "/presets/" + SOUND_EXAMPLE + "/template.json" : "/presets/" + name + ".json";
            try (var input = ClockworkBossTemplateStore.class.getResourceAsStream(resource)) {
                if (input == null) throw new IllegalStateException("Missing bundled boss preset: " + name);
                // Use the same bounded reader and comment-aware parser as user
                // templates, then regenerate comments from the current catalog.
                byte[] bytes = input.readNBytes((int) MAX_TEMPLATE_BYTES + 1);
                if (bytes.length > MAX_TEMPLATE_BYTES) {
                    throw new IllegalArgumentException("Bundled boss preset exceeds 512 KiB: " + name);
                }
                try (Reader reader = new InputStreamReader(
                        new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
                    writeFile(destination, ClockworkBossTemplateDocument.read(reader), false);
                } catch (FileAlreadyExistsException concurrentCreate) {
                    // The no-replacement save also protects a file created by an
                    // administrator between the initial check and final rename.
                }
            }
        }
        installExampleAudio();
    }

    /** Seeds one optional audio example; never copies its clips to balance presets or active bosses. */
    private static void installExampleAudio() throws IOException {
        Path folder = PRESET_DIRECTORY.resolve(SOUND_EXAMPLE);
        createTemplateFolders(folder);
        for (String relative : EXAMPLE_AUDIO) {
            // Paths come only from this fixed bundled list, never from a packet or config field.
            Path destination = folder.resolve("sounds").resolve(relative);
            checkOwnedPath(destination);
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) continue;
            String resource = "/presets/" + SOUND_EXAMPLE + "/sounds/" + relative;
            try (var input = ClockworkBossTemplateStore.class.getResourceAsStream(resource)) {
                if (input == null) throw new IOException("Missing bundled sound example: " + relative);
                byte[] bytes = input.readNBytes(ClockworkBossSoundPayload.MAX_OGG_BYTES + 1);
                if (bytes.length > ClockworkBossSoundPayload.MAX_OGG_BYTES) throw new IOException("Bundled audio exceeds limit");
                Path temporary = Files.createTempFile(destination.getParent(), ".audio-", ".tmp");
                try {
                    Files.write(temporary, bytes);
                    try {
                        Files.move(temporary, destination);
                    } catch (FileAlreadyExistsException concurrentCreate) {
                        // Preserve an administrator's file added while the example was being extracted.
                    }
                } finally {
                    Files.deleteIfExists(temporary);
                }
            }
        }
    }

    /**
     * Scans only template folders containing template.json in boss-templates for sorted command suggestions.
     * boss-presets is never scanned for gameplay or suggestions;
     * no JSON parse or scan is needed on each suggestion request.
     */
    public static void refreshCache() throws Exception {
        ensureDirectory();
        try (var stream = Files.list(DIRECTORY)) {
            cachedTemplates = stream
                    .filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> !Files.isSymbolicLink(path))
                    .map(Path::getFileName)
                    .map(Path::toString)
                    .filter(ClockworkBossTemplateNames::isValid)
                    .filter(ClockworkBossTemplateStore::exists)
                    .sorted(Comparator.naturalOrder())
                    .toList();
        }
        createOwnedDirectory(PRESET_DIRECTORY);
        try (var stream = Files.list(PRESET_DIRECTORY)) {
            cachedPresets = stream
                    .filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .map(path -> path.getFileName().toString())
                    .filter(ClockworkBossTemplateNames::isValid)
                    .filter(name -> regularOwnedFile(PRESET_DIRECTORY.resolve(name).resolve("template.json")))
                    .sorted().toList();
        }
    }

    /** A snapshot supplies preset completion without a directory scan while the player types. */
    public static List<String> presets() { return cachedPresets; }

    /** Checks parents as well as the leaf so a linked preset cannot redirect creation. */
    private static boolean regularOwnedFile(Path path) {
        try {
            checkOwnedPath(path);
            return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException unsafe) {
            return false;
        }
    }

    /** Checks a validated name and a regular nonsymlink file, without parsing its contents. */
    public static boolean exists(String templateName) {
        if (!ClockworkBossTemplateNames.isValid(templateName)) return false;
        try {
            checkOwnedPath(pathFor(templateName));
            return Files.isRegularFile(pathFor(templateName), LinkOption.NOFOLLOW_LINKS);
        } catch (IOException unsafePath) {
            return false;
        }
    }

    /** Validates a single template name before resolving it beneath the owned configuration directory. */
    public static Path pathFor(String templateName) {
        validateName(templateName);
        return DIRECTORY.resolve(templateName).resolve("template.json");
    }

    /**
     * Reads at most 512 KiB and parses the comment-aware document with a depth limit; malformed documents
     * fail before spawning.
     */
    public static JsonObject load(String templateName) throws Exception {
        validateName(templateName);
        return readDocument(pathFor(templateName));
    }

    /** Shared bounded reader for active templates and operator-selected presets. */
    private static JsonObject readDocument(Path path) throws Exception {
        return parseDocument(readDocumentBytes(path));
    }

    /** One bounded snapshot supplies both editor content and its conflict-detection revision. */
    private static byte[] readDocumentBytes(Path path) throws Exception {
        checkOwnedPath(path);
        if (!Files.isRegularFile(path) || Files.isSymbolicLink(path)) {
            throw new IllegalArgumentException("Template document does not exist: " + path.getParent().getFileName());
        }

        if (Files.size(path) > MAX_TEMPLATE_BYTES) {
            throw new IllegalArgumentException("Template exceeds the 512 KiB size limit");
        }
        // Bound the actual bytes too: a file may grow after the size check.
        byte[] bytes;
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes((int) MAX_TEMPLATE_BYTES + 1);
        }
        if (bytes.length > MAX_TEMPLATE_BYTES) throw new IllegalArgumentException("Template exceeds 512 KiB");
        return bytes;
    }

    private static JsonObject parseDocument(byte[] bytes) throws Exception {
        try (Reader reader = new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
            return ClockworkBossTemplateDocument.read(reader);
        }
    }

    /** The editor can read presets, but its delete command always uses the active-template root. */
    public record EditorDocument(JsonObject config, String revision, JsonObject stored) {}

    public static synchronized EditorDocument loadForEditor(String name, boolean preset) throws Exception {
        validateName(name);
        Path path = preset ? PRESET_DIRECTORY.resolve(name).resolve("template.json") : pathFor(name);
        byte[] bytes = readDocumentBytes(path);
        String revision = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        JsonObject stored = parseDocument(bytes);
        return new EditorDocument(ClockworkBossEditorSchema.editable(stored), revision, stored);
    }

    /** Editor custom preview reads only a validated PNG directly inside the selected file's own skins folder. */
    public static byte[] loadEditorSkin(String name, boolean preset, String fileName) throws Exception {
        validateName(name);
        if (!CustomWardenSkinPayload.isValidFileName(fileName)) throw new IOException("Use a lowercase PNG filename");
        Path folder = (preset ? PRESET_DIRECTORY.resolve(name) : pathFor(name).getParent()).resolve("skins");
        Path file = folder.resolve(fileName);
        checkOwnedPath(file);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Skin file does not exist in this template's skins folder");
        byte[] bytes;
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) { bytes = input.readNBytes(CustomWardenSkinPayload.MAX_PNG_BYTES + 1); }
        if (bytes.length > CustomWardenSkinPayload.MAX_PNG_BYTES || !CustomWardenSkinPayload.hasWardenAtlasHeader(bytes)) throw new IOException("Custom skin must be a 128x128 PNG, at most 512 KiB");
        return bytes;
    }

    /** Apply only submitted scalar changes, rejecting stale sessions and arbitrary paths/structures. */
    public static synchronized EditorDocument saveFromEditor(String name, boolean preset, String revision,
                                                              JsonObject patch, ClockworkBossLoot.Catalog lootCatalog) throws Exception {
        EditorDocument current = loadForEditor(name, preset);
        if (!current.revision().equals(revision)) throw new IOException("This file changed outside the editor. Close and reopen it before saving.");
        if (patch.size() == 0 || patch.size() > 512) throw new IOException("Save must contain 1–512 changed fields");
        JsonObject expected = current.config().deepCopy();
        ClockworkBossEditorSchema.applyOrders(expected, patch);
        JsonObject updated = expected.deepCopy();
        for (var entry : patch.entrySet()) {
            if (ClockworkBossEditorSchema.orderField(entry.getKey())) continue;
            if (entry.getKey().length() > 256 || ClockworkBossEditorSchema.get(updated, entry.getKey()) == null) throw new IOException("Unknown editor field");
            ClockworkBossEditorSchema.put(updated, entry.getKey(), entry.getValue());
        }
        ClockworkBossEditorSchema.validate(expected, updated);
        if (updated.toString().length() > ClockworkBossEditorPayload.MAX_JSON_CHARS) throw new IOException("Document is too large for the GUI");
        JsonObject persisted = current.stored().deepCopy();
        if ((!persisted.has("phases") || !persisted.get("phases").isJsonObject())
                && patch.keySet().stream().anyMatch(key -> key.startsWith("phases.")
                || ClockworkBossEditorSchema.orderField(key) && !key.equals("_loot_entry_order")))
            persisted.add("phases", current.config().get("phases").deepCopy());
        ClockworkBossEditorSchema.applyOrders(persisted, patch);
        for (var entry : patch.entrySet()) if (!ClockworkBossEditorSchema.orderField(entry.getKey())) ClockworkBossEditorSchema.putCreating(persisted, entry.getKey(), entry.getValue());
        lootCatalog.validate(persisted); // No backup/publication occurs if a reward reference is invalid.
        var compiled = ClockworkBossSettings.read(persisted);
        ClockworkBossSubphases.read(persisted, compiled); // Validate actual child behavior before committing the file.
        Path path = preset ? PRESET_DIRECTORY.resolve(name).resolve("template.json") : pathFor(name);
        createTemplateFolders(path.getParent());
        // One rolling backup retains the prior file without growing disk use on every GUI save.
        Path backup = path.resolveSibling("template.json.gui-backup");
        checkOwnedPath(backup);
        Files.copy(path, backup, StandardCopyOption.REPLACE_EXISTING);
        // Recheck immediately before publication to catch hosting-panel edits during validation/backup.
        if (!loadForEditor(name, preset).revision().equals(revision)) throw new IOException("This file changed while saving. Reopen the editor.");
        writeFile(path, persisted, true);
        refreshCache();
        return loadForEditor(name, preset);
    }

    /** Remove a template from the live list by archiving its whole folder; media and presets are preserved. */
    public static synchronized Path deleteTemplate(String name) throws Exception {
        validateName(name);
        Path source = pathFor(name).getParent();
        readDocumentBytes(source.resolve("template.json"));
        Path archiveRoot = CONFIG_ROOT.resolve("deleted-templates");
        createOwnedDirectory(archiveRoot);
        Path destination = archiveRoot.resolve(name + "-" + java.util.UUID.randomUUID());
        checkOwnedPath(source);
        checkOwnedPath(destination);
        Files.move(source, destination); // No recursive deletion, and no replacement of any existing folder.
        refreshCache();
        return destination;
    }

    /** Makes a fresh independent default tree and writes without replacing an existing template. */
    public static synchronized JsonObject create(String templateName) throws Exception {
        validateName(templateName);
        ensureDirectory();

        Path path = pathFor(templateName);
        checkOwnedPath(path);
        if (Files.exists(path)) {
            throw new IllegalArgumentException("Template already exists: " + templateName);
        }

        createTemplateFolders(path.getParent());
        JsonObject copy = ClockworkBossTemplateDocument.defaults();
        writeFile(path, copy, false);

        refreshCache();
        return copy;
    }

    /**
     * Worker-only preset creation: stage a complete folder before publishing it.
     * Errors leave no visible partial template and never replace an existing folder.
     * Copy only direct validated media in the three supported categories; cap both
     * file count and total bytes so a large preset cannot queue unlimited disk work.
     */
    public static void createFromPreset(String presetName, String templateName) throws Exception {
        validateName(presetName);
        validateName(templateName);
        ensureDirectory();
        Path preset = PRESET_DIRECTORY.resolve(presetName);
        JsonObject config = readDocument(preset.resolve("template.json"));
        Path destination = pathFor(templateName).getParent();
        checkOwnedPath(destination);
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("Template folder already exists: " + templateName);
        }
        Path stage = Files.createTempDirectory(DIRECTORY, ".preset-");
        boolean published = false;
        try {
            createTemplateFolders(stage);
            writeFile(stage.resolve("template.json"), config, false);
            int count = 0;
            long totalBytes = 0;
            for (String category : List.of("skins", "sounds", "sounds/voice-lines")) {
                Path source = preset.resolve(category);
                checkOwnedPath(source);
                if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) continue;
                if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid preset media folder");
                try (var entries = Files.list(source)) {
                    // One fixed nested voice folder is processed separately above.
                    var iterator = entries.iterator();
                    int inspected = 0;
                    while (iterator.hasNext()) {
                        if (++inspected > 256) throw new IOException("Too many preset directory entries");
                        Path file = iterator.next();
                        checkOwnedPath(file);
                        if (category.equals("sounds") && file.getFileName().toString().equals("voice-lines")) continue;
                        boolean skin = category.equals("skins");
                        String name = file.getFileName().toString();
                        boolean music = category.equals("sounds")
                                && name.equals(ClockworkBossMusic.Settings.read(config).file());
                        boolean validName = skin ? CustomWardenSkinPayload.isValidFileName(name)
                                : ClockworkBossSoundPayload.isValidFileName(name);
                        if (!validName || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                            throw new IOException("Unsupported preset media entry: " + name);
                        }
                        if (++count > 96) throw new IOException("Preset exceeds 96 media files");
                        int limit = skin ? CustomWardenSkinPayload.MAX_PNG_BYTES
                                : music ? ClockworkBossMusicData.MAX_BYTES : ClockworkBossSoundPayload.MAX_OGG_BYTES;
                        if (Files.size(file) > limit) throw new IOException("Preset media exceeds size limit: " + name);
                        byte[] bytes;
                        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                            bytes = input.readNBytes(limit + 1);
                        }
                        totalBytes += bytes.length;
                        if (bytes.length == 0 || bytes.length > limit || totalBytes > 32L * 1024 * 1024) {
                            throw new IOException("Preset media exceeds file or 32 MiB total limit");
                        }
                        // Keep the same pre-decoder guards as ordinary network delivery.
                        if (skin ? !CustomWardenSkinPayload.hasWardenAtlasHeader(bytes)
                                : (music ? ClockworkBossMusicData.validatedDurationMillis(bytes)
                                : ClockworkBossSoundPayload.validatedDurationMillis(bytes)) <= 0) {
                            throw new IOException("Invalid preset PNG/OGG: " + name);
                        }
                        Files.write(stage.resolve(category).resolve(name), bytes);
                    }
                }
            }
            // Hold the store lock only for publication/suggestions. Copying up
            // to 32 MiB must not block a server-thread setspawn/save operation.
            synchronized (ClockworkBossTemplateStore.class) {
                Files.move(stage, destination); // No replacement if another creator wins.
                published = true;
                refreshCache();
            }
        } finally {
            if (!published) {
                // Only this freshly created, bounded staging folder is removed.
                checkOwnedPath(stage);
                try (var entries = Files.walk(stage)) {
                    for (Path path : entries.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
                }
            }
        }
    }

    /**
     * Saves a template JSON document to its template file and refreshes the
     * cached template-name list used by command autocomplete.
     */
    public static synchronized void save(String templateName, JsonObject config) throws Exception {
        validateName(templateName);
        ensureDirectory();

        createTemplateFolders(pathFor(templateName).getParent());
        writeFile(pathFor(templateName), config, true);

        refreshCache();
    }

    /** Autocomplete reads a snapshot only; no folder checks or other filesystem work. */
    public static List<String> list() {
        return cachedTemplates;
    }

    /**
     * Writes a complete UTF-8 sibling file before replacing a template. A failed
     * write leaves the original intact. Atomic replacement is preferred; file
     * providers such as mounted SFTP may only support an ordinary rename.
     */
    private static void writeFile(Path destination, JsonObject config, boolean replaceExisting) throws Exception {
        checkOwnedPath(destination);
        if (Files.isSymbolicLink(destination)) {
            throw new IllegalArgumentException("Template cannot be a symbolic link");
        }
        byte[] content = ClockworkBossTemplateDocument.write(config).getBytes(StandardCharsets.UTF_8);
        if (content.length > MAX_TEMPLATE_BYTES) {
            throw new IllegalArgumentException("Template exceeds the 512 KiB size limit");
        }
        // Stage beside the actual destination, including optional preset files,
        // so a mounted folder uses its own file provider for the final rename.
        Path temporary = Files.createTempFile(destination.getParent(), ".template-", ".tmp");
        try {
            Files.write(temporary, content);
            if (replaceExisting) {
                try {
                    Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            } else {
                // No REPLACE_EXISTING: an external create must never be overwritten.
                Files.move(temporary, destination);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** Each template owns its media; callers supply only a fixed category, never an arbitrary path. */
    public static Path mediaDirectory(String templateName, String category) throws IOException {
        validateName(templateName);
        if (!category.equals("skins") && !category.equals("sounds") && !category.equals("voice-lines")) {
            throw new IllegalArgumentException("Unknown template media category");
        }
        Path folder = pathFor(templateName).getParent();
        Path media = category.equals("voice-lines")
                ? folder.resolve("sounds").resolve("voice-lines") : folder.resolve(category);
        checkOwnedPath(media);
        return media;
    }

    /** Creates matching active/preset layouts without shared root media folders. */
    private static void createTemplateFolders(Path folder) throws IOException {
        createOwnedDirectory(folder);
        createOwnedDirectory(folder.resolve("skins"));
        createOwnedDirectory(folder.resolve("sounds"));
        createOwnedDirectory(folder.resolve("sounds").resolve("voice-lines"));
    }

    /** Check before and after creation so an existing linked parent cannot redirect writes. */
    private static void createOwnedDirectory(Path directory) throws IOException {
        checkOwnedPath(directory);
        Files.createDirectories(directory);
        checkOwnedPath(directory);
    }

    /** Reject links in every owned path component, including media parents, before reading/writing. */
    public static void checkOwnedPath(Path path) throws IOException {
        Path root = CONFIG_ROOT.toAbsolutePath().normalize();
        Path target = path.toAbsolutePath().normalize();
        if (!target.startsWith(root)) throw new IOException("Path is outside Clockwork Bosses config");
        Path component = root;
        if (Files.isSymbolicLink(component)) throw new IOException("Config root must not be a symbolic link");
        for (Path part : root.relativize(target)) {
            component = component.resolve(part);
            if (Files.isSymbolicLink(component)) throw new IOException("Config paths must not contain symbolic links");
        }
    }

    /** Validates before constructing a path, including exists/autocomplete callers. */
    private static void validateName(String templateName) {
        if (!ClockworkBossTemplateNames.isValid(templateName)) {
            throw new IllegalArgumentException(
                    "Invalid template name. Use 1-64 lowercase letters, numbers, '.', '_' or '-'; no spaces or capitals. Start with a letter, number, '_' or '-'.");
        }
    }
}
