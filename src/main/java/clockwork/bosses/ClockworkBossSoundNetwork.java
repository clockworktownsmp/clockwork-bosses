package clockwork.bosses;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.List;
import java.nio.file.LinkOption;

/** Reads bounded OGG files from server config and sends them only to nearby clients when played. */
public final class ClockworkBossSoundNetwork {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClockworkBosses.MOD_ID);
    private static final int MAX_CACHED_FILES = 16;
    private static final int MAX_VOICE_FILES = 64;
    // Startup/reload snapshot: attacks never scan a directory. Invalid selected
    // files are removed until reload, avoiding repeated disk errors/log spam.
    private static final Map<String, List<String>> VOICE_FILES = new HashMap<>();
    private static final Map<String, Object> VOICE_PENDING = new HashMap<>();
    private static net.minecraft.server.MinecraftServer mediaServer;
    private static final ClockworkBossMediaCache<CachedSound> SOUND_FILES = new ClockworkBossMediaCache<>();
    private static final Map<UUID, LinkedHashMap<String, Boolean>> SENT_TO_PLAYERS = new HashMap<>();

    private ClockworkBossSoundNetwork() {}

    /** Registers the server-to-client codec and clears only per-session file-transfer state. */
    public static void register() {
        PayloadTypeRegistry.clientboundPlay().register(
                ClockworkBossSoundPayload.TYPE,
                ClockworkBossSoundPayload.CODEC);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            synchronized (SENT_TO_PLAYERS) {
                SENT_TO_PLAYERS.remove(handler.player.getUUID());
            }
        });
        ServerLifecycleEvents.SERVER_STARTING.register(server -> mediaServer = server);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> { mediaServer = null; clearCaches(); });
        // Entity-load callbacks can prepare catalogs before SERVER_STARTED.
        // Shutdown already clears them; clearing again at startup would lose
        // those loaded bosses' voice lists. Each template prepares on registration.
    }

    /** Forces changed server files to be re-read after /boss reload. */
    public static void refreshFileCache() {
        synchronized (ClockworkBossSoundNetwork.class) {
            SOUND_FILES.clear();
            ClockworkBossMediaTransfers.invalidateWaiting();
            VOICE_FILES.clear();
            VOICE_PENDING.clear();
        }
    }

    /** No per-attack filesystem work when the voice folder has no usable names. */
    public static boolean hasVoiceLines(String templateName) {
        return !VOICE_FILES.getOrDefault(templateName, List.of()).isEmpty();
    }

    /** The manager releases a catalog when its last loaded boss leaves; audio caches stay bounded separately. */
    public static void releaseTemplate(String templateName) {
        VOICE_FILES.remove(templateName);
        VOICE_PENDING.remove(templateName);
    }

    /** Warm the small event-file set when a template is loaded, before its first activation/attack. */
    public static void prepareTemplate(String templateName, com.google.gson.JsonObject template) {
        // Only loaded templates acquire catalogs; reload/shutdown clears them.
        if (mediaServer != null && !VOICE_FILES.containsKey(templateName) && !VOICE_PENDING.containsKey(templateName)) {
            Object token = new Object(); VOICE_PENDING.put(templateName, token);
            // Capture only a name for the worker. Ownership tokens suppress an
            // old result after reload, last-boss unload or server shutdown.
            boolean queued = ClockworkBossTemplateTasks.submit(mediaServer, () -> readVoiceFiles(templateName), (files, failure) -> {
                if (VOICE_PENDING.remove(templateName, token)) VOICE_FILES.put(templateName, failure == null ? files : List.of());
            });
            if (!queued) VOICE_PENDING.remove(templateName, token);
        }
        var names = new java.util.LinkedHashSet<String>();
        var sounds = child(template, "sounds");
        for (String event : List.of("spawn", "death", "phase_change")) {
            addFile(names, child(sounds, event));
        }
        addFile(names, child(child(child(template, "attacks"), "roar"), "sound"));
        var phases = child(template, "phases");
        for (ClockworkBossPhase phase : ClockworkBossPhase.Thresholds.read(template).phases()) {
            var parent = child(phases, phase.key());
            addFile(names, child(child(parent, "sounds"), "phase_change"));
            addFile(names, child(child(parent, "roar"), "sound"));
            // Inherited files are already collected above. Visit only the
            // bounded explicit overrides, once on load/reload, never on attack.
            child(parent, "roars").entrySet().stream().limit(ClockworkBossSubphases.MAX_SUBPHASES)
                    .filter(entry -> entry.getValue().isJsonObject())
                    .forEach(entry -> addFile(names, child(entry.getValue().getAsJsonObject(), "sound")));
            // Registered core roar actions use their own declared settings.
            // Prefetch their explicit cue through the same bounded media cache.
            child(parent, "subphases").entrySet().stream().limit(ClockworkBossSubphases.MAX_SUBPHASES)
                    .filter(entry -> entry.getValue().isJsonObject())
                    .map(entry -> entry.getValue().getAsJsonObject())
                    .filter(child -> child.has("type") && child.get("type").isJsonPrimitive()
                            && ClockworkBossActions.ROAR.toString().equals(child.get("type").getAsString()))
                    .forEach(child -> addFile(names, child(child, "action_settings")));
        }
        for (String name : names) SOUND_FILES.request(ClockworkBossSoundPayload.soundIdentity(templateName, name, false),
                () -> loadSound(templateName, name, false));
    }

    /** Missing/null subsection means there is no additional event file to prefetch. */
    private static com.google.gson.JsonObject child(com.google.gson.JsonObject parent, String key) {
        return parent.has(key) && parent.get(key).isJsonObject()
                ? parent.getAsJsonObject(key) : new com.google.gson.JsonObject();
    }

    /** Prefetch only validated basenames, leaving actual decoding to the shared validated reader. */
    private static void addFile(java.util.Set<String> names, com.google.gson.JsonObject settings) {
        if (!settings.has("file") || !settings.get("file").isJsonPrimitive()) return;
        String name = settings.get("file").getAsString().trim();
        if (ClockworkBossSoundPayload.isValidFileName(name)) names.add(name);
    }

    /** Bounded snapshot of regular, direct OGG files; folder links and file links are not followed. */
    private static List<String> readVoiceFiles(String templateName) {
        try {
            Path directory = ClockworkBossTemplateStore.mediaDirectory(templateName, "voice-lines");
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return List.of();
            try (var files = Files.list(directory)) {
                List<String> names = files.limit(4096)
                        .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                        .map(path -> path.getFileName().toString()).toList();
                List<String> usable = names.stream().filter(ClockworkBossSoundPayload::isValidFileName)
                        .sorted().limit(MAX_VOICE_FILES).toList();
                List<String> invalid = names.stream()
                        .filter(name -> name.toLowerCase(java.util.Locale.ROOT).endsWith(".ogg"))
                        .filter(name -> !ClockworkBossSoundPayload.isValidFileName(name)).limit(5).toList();
                if (!invalid.isEmpty()) LOGGER.warn("Ignoring invalid voice filenames {} (up to five shown); "
                        + "use lowercase ASCII letters, numbers, dots, underscores or hyphens; no spaces, capitals or paths", invalid);
                return usable;
            }
        } catch (IOException | java.io.UncheckedIOException failure) {
            LOGGER.warn("Could not read voice folder for template '{}': {}", templateName, failure.getMessage());
        }
        return List.of();
    }

    /** Loads a random clip off-thread; the server callback reports the resulting playback lock length. */
    public static void playVoiceLine(Warden warden, float volume, float pitch, float range,
                                    java.util.function.LongConsumer completed) {
        String templateName = ((ClockworkBossMarker) (Object) warden).clockworkBosses$getTemplate();
        List<String> voiceFiles = VOICE_FILES.getOrDefault(templateName, List.of());
        if (voiceFiles.isEmpty() || !(warden.level() instanceof ServerLevel level)) {
            completed.accept(0);
            return;
        }
        String fileName = voiceFiles.get(warden.getRandom().nextInt(voiceFiles.size()));
        long epoch = SOUND_FILES.generation();
        var request = SOUND_FILES.request(ClockworkBossSoundPayload.soundIdentity(templateName, fileName, true),
                () -> loadSound(templateName, fileName, true));
        if (request == null) {
            completed.accept(0);
            return;
        }
        var server = level.getServer();
        request.whenComplete((sound, failure) -> {
            if (!server.isRunning()) return;
            server.execute(() -> {
                if (SOUND_FILES.generation() != epoch) return;
                ClockworkBossState state = ClockworkBossManager.stateFor(warden);
                if (failure != null) VOICE_FILES.computeIfPresent(templateName,
                        (key, names) -> names.stream().filter(name -> !name.equals(fileName)).toList());
                if (failure != null || warden.isRemoved() || !warden.isAlive()
                        || state == null || state.dormant() || state.emerging()
                        || warden.level() != level) {
                    completed.accept(0);
                    return;
                }
                int recipients = sendSound(source(warden), fileName, sound, volume, pitch, range, true);
                // Clients independently reserve actual decode/playback, so a
                // slow transfer cannot overlap voices after this server gate ends.
                completed.accept(recipients == 0 ? 0
                        : (long) Math.ceil(sound.durationMillis() / pitch) + 250L);
            });
        });
    }

    /** Sends bytes on cache miss, then small playback metadata while that revision remains cached. */
    public static void play(
            Warden warden,
            String fileName,
            float volume,
            float pitch,
            float range
    ) {
        if (!(warden.level() instanceof ServerLevel)) {
            return;
        }
        if (!ClockworkBossSoundPayload.isValidFileName(fileName)) {
            LOGGER.warn("Invalid Clockwork Boss sound filename '{}'; use one .ogg filename with lowercase ASCII letters, "
                            + "numbers, dots, underscores or hyphens; no spaces, capitals or directory paths",
                    fileName);
            return;
        }

        SoundOrigin origin = source(warden);
        var server = origin.level().getServer();
        long epoch = SOUND_FILES.generation();
        var request = SOUND_FILES.request(ClockworkBossSoundPayload.soundIdentity(origin.templateName(), fileName, false),
                () -> loadSound(origin.templateName(), fileName, false));
        if (request == null) return;
        request.whenComplete((sound, failure) -> {
            if (!server.isRunning()) return;
            server.execute(() -> {
                if (failure == null && SOUND_FILES.generation() == epoch
                        && server.getLevel(origin.level().dimension()) == origin.level()) {
                    sendSound(origin, fileName, sound, volume, pitch, range, false);
                }
            });
        });
    }

    /** Death events remain spatially valid after native entity removal; no entity is retained for ordinary audio. */
    private static SoundOrigin source(Warden warden) {
        return new SoundOrigin((ServerLevel) warden.level(), warden.getUUID(),
                ((ClockworkBossMarker) (Object) warden).clockworkBosses$getTemplate(),
                warden.getX(), warden.getY() + warden.getBbHeight() * 0.5, warden.getZ());
    }

    private record SoundOrigin(ServerLevel level, UUID bossId, String templateName, double x, double y, double z) {}

    /** One bounded native spatial query per played event, with shared audio cache delivery. */
    private static int sendSound(SoundOrigin origin, String fileName, CachedSound sound,
                                  float volume, float pitch, float range, boolean voiceLine) {
        // Defense before the native spatial query as well as the packet constructor.
        // Manager resolves Auto; this boundary accepts only finite, bounded playback distances.
        if (!Float.isFinite(range)) return 0;
        range = ClockworkBossSoundDistance.playback(range);
        ServerLevel level = origin.level();
        int recipients = 0;
        double x = origin.x();
        double y = origin.y();
        double z = origin.z();
        // Other mods/commands can move entities beyond ordinary world bounds.
        // Unsupported audio positions must not throw from a server callback.
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || Math.abs(x) > 30_000_000 || Math.abs(y) > 30_000_000 || Math.abs(z) > 30_000_000) return 0;
        double maxDistanceSquared = (double) range * range;
        String cacheKey = ClockworkBossSoundPayload.soundIdentity(origin.templateName(), fileName, voiceLine) + ":" + sound.revision();
        ClockworkBossSoundPayload fullPayload = null;
        ClockworkBossSoundPayload cachedPayload = null;
        ClockworkBossMediaTransfers.Prepared prepared = null;
        long freshUntil = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(8);
        for (ServerPlayer player : level.getEntitiesOfClass(ServerPlayer.class,
                new AABB(x - range, y - range, z - range, x + range, y + range, z + range),
                candidate -> candidate.distanceToSqr(x, y, z) <= maxDistanceSquared)) {
            if (!ServerPlayNetworking.canSend(player, ClockworkBossSoundPayload.TYPE)
                    || !ServerPlayNetworking.canSend(player, ClockworkBossMediaChunk.TYPE)) {
                continue;
            }

            // Build at most one full and one metadata-only immutable payload
            // per event, instead of cloning up to 1 MiB for each recipient.
            ClockworkBossSoundPayload payload;
            if (wasSent(player.getUUID(), cacheKey)) {
                if (cachedPayload == null) cachedPayload = new ClockworkBossSoundPayload(origin.templateName(), fileName,
                        sound.revision(), new byte[0], x, y, z, volume, pitch, range, origin.bossId(), voiceLine);
                payload = cachedPayload;
            } else {
                if (fullPayload == null) fullPayload = new ClockworkBossSoundPayload(origin.templateName(), fileName,
                        sound.revision(), sound.oggBytes(), x, y, z, volume, pitch, range, origin.bossId(), voiceLine);
                payload = fullPayload;
            }
            if (payload == cachedPayload) {
                // Cached playback remains a tiny native packet and follows the
                // same send-order LRU as a completed full-file transfer.
                ServerPlayNetworking.send(player, payload);
                rememberSent(player.getUUID(), cacheKey);
                recipients++;
            } else {
                if (prepared == null) prepared = ClockworkBossMediaTransfers.prepare(payload, level.registryAccess());
                ClockworkBossSoundPayload replay = payload.playbackOnly();
                // One waiting voice per boss: a slow link keeps only its latest
                // request. Ordinary events coalesce by file/revision.
                String deliveryKey = voiceLine ? "voice:" + origin.bossId()
                        : "sound:" + ClockworkBossSoundPayload.soundIdentity(origin.templateName(), fileName, false);
                boolean accepted = ClockworkBossMediaTransfers.send(player, deliveryKey, prepared,
                        () -> rememberSent(player.getUUID(), cacheKey), () -> {
                            if (!wasSent(player.getUUID(), cacheKey)) return false;
                            ServerPlayNetworking.send(player, replay);
                            rememberSent(player.getUUID(), cacheKey);
                            return true;
                        }, () -> System.nanoTime() - freshUntil < 0
                                && (!voiceLine || isLiveVoiceSource(origin)));
                if (accepted) recipients++;
            }
        }
        return recipients;
    }

    /** Do not begin a queued voice after its boss died, unloaded or returned to dormancy. */
    private static boolean isLiveVoiceSource(SoundOrigin origin) {
        var entity = origin.level().getEntity(origin.bossId());
        if (!(entity instanceof Warden warden) || !warden.isAlive() || warden.isRemoved()) return false;
        ClockworkBossState state = ClockworkBossManager.stateFor(warden);
        return state != null && !state.dormant() && !state.emerging();
    }

    /** Resolves only a direct regular file in the chosen fixed folder, sharing all OGG safeguards. */
    private static CachedSound loadSound(String templateName, String fileName, boolean voiceLine) throws IOException {
        Path sourceDirectory = ClockworkBossTemplateStore.mediaDirectory(templateName,
                voiceLine ? "voice-lines" : "sounds");
        Path directory = sourceDirectory.toRealPath();
        Path requested = directory.resolve(fileName);
        ClockworkBossTemplateStore.checkOwnedPath(requested);
        if (Files.isSymbolicLink(requested)) throw new IOException("sound file must not be a symbolic link");
        final Path file;
        try {
            file = requested.toRealPath();
        } catch (java.nio.file.NoSuchFileException missing) {
            throw new IOException("file not found in " + sourceDirectory + "; filename case must match exactly",
                    missing);
        }
        if (!directory.equals(file.getParent()) || !Files.isRegularFile(file)) {
            throw new IOException("file must be directly inside " + sourceDirectory);
        }

        long size = Files.size(file);
        if (size < 27 || size > ClockworkBossSoundPayload.MAX_OGG_BYTES) {
            throw new IOException("OGG file must be between 27 bytes and 1 MiB minus 1 KiB packet overhead");
        }

        byte[] bytes;
        try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(ClockworkBossSoundPayload.MAX_OGG_BYTES + 1);
        }
        if (bytes.length != size || bytes.length > ClockworkBossSoundPayload.MAX_OGG_BYTES) {
            throw new IOException("file changed or exceeded the size limit while being read");
        }
        long duration = ClockworkBossSoundPayload.validatedDurationMillis(bytes);
        if (duration <= 0) {
            throw new IOException("file must be a valid, nonempty mono/stereo OGG Vorbis stream of at most 30 seconds");
        }

        return new CachedSound(bytes, sha256(bytes), duration);
    }

    /** Access-order tracking mirrors the bounded client cache, so old entries can be resent safely. */
    private static boolean wasSent(UUID playerId, String cacheKey) {
        synchronized (SENT_TO_PLAYERS) {
            LinkedHashMap<String, Boolean> sent = SENT_TO_PLAYERS.get(playerId);
            return sent != null && sent.get(cacheKey) != null;
        }
    }

    /** Maintains a per-connected-player access-order cache matching the client's sixteen-file retention bound. */
    private static void rememberSent(UUID playerId, String cacheKey) {
        synchronized (SENT_TO_PLAYERS) {
            LinkedHashMap<String, Boolean> sent = SENT_TO_PLAYERS.computeIfAbsent(
                    playerId, ignored -> new LinkedHashMap<>(16, 0.75F, true));
            // Only actual delivery replaces an older revision. A queued new
            // file has not changed the client's cache yet.
            String prefix = cacheKey.substring(0, cacheKey.lastIndexOf(':') + 1);
            sent.keySet().removeIf(key -> key.startsWith(prefix) && !key.equals(cacheKey));
            sent.put(cacheKey, Boolean.TRUE);
            while (sent.size() > MAX_CACHED_FILES) {
                String eldest = sent.keySet().iterator().next();
                sent.remove(eldest);
            }
        }
    }

    /** Hashes validated file bytes as a revision; clients verify the same hash before decoding. */
    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /**
     * Drops disk snapshots and player delivery histories on shutdown; disconnect separately clears that
     * player's history.
     */
    private static void clearCaches() {
        synchronized (ClockworkBossSoundNetwork.class) {
            SOUND_FILES.clear();
            VOICE_FILES.clear();
            VOICE_PENDING.clear();
        }
        synchronized (SENT_TO_PLAYERS) {
            SENT_TO_PLAYERS.clear();
        }
    }

    // Cache arrays stay private; the outgoing payload makes its defensive copy.
    private record CachedSound(byte[] oggBytes, String revision,
                               long durationMillis) {}
}
