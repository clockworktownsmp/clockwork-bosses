package clockwork.bosses;

import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Encounter music shares the existing arena membership scan, with no new server timer.
 * Only UUIDs and immutable settings survive callbacks; death/unload/exit invalidate listeners.
 * Compressed files and encoded transfers are shared, bounded snapshots read on native I/O workers.
 */
public final class ClockworkBossMusic {
    private static final ClockworkBossMediaCache<Asset> FILES = new ClockworkBossMediaCache<>(4);
    private static final Map<UUID, Encounter> ENCOUNTERS = new HashMap<>();
    private static final Map<UUID, LinkedHashMap<String, Boolean>> SENT = new HashMap<>();
    private ClockworkBossMusic() {}

    /** Compiled once per template load/reload; blank files make music entirely inactive. */
    public record Settings(boolean enabled, String file, float volume, boolean loop, float fadeSeconds, net.minecraft.sounds.SoundSource channel) {
        public static Settings read(JsonObject config) {
            JsonObject music = config.has("music") && config.get("music").isJsonObject()
                    ? config.getAsJsonObject("music") : new JsonObject();
            String file;
            try { file = music.has("file") ? music.get("file").getAsString() : ""; }
            catch (RuntimeException invalid) { file = ""; }
            String channel;
            try { channel = music.has("channel") ? music.get("channel").getAsString() : "music"; }
            catch (RuntimeException invalid) { channel = "music"; }
            return new Settings(flag(music, "enabled", true) && ClockworkBossSoundPayload.isValidFileName(file),
                    file, bounded(music, "volume", 0.75F, 0, 4), flag(music, "loop", true),
                    bounded(music, "fade_out_seconds", 2, 0, 10), ClockworkBossMusicChannels.orDefault(channel));
        }
        private static boolean flag(JsonObject object, String key, boolean fallback) {
            try { return object.has(key) ? object.get(key).getAsBoolean() : fallback; }
            catch (RuntimeException invalid) { return fallback; }
        }
        private static float bounded(JsonObject object, String key, float fallback, float min, float max) {
            try {
                float value = object.has(key) ? object.get(key).getAsFloat() : fallback;
                return Float.isFinite(value) ? Math.clamp(value, min, max) : fallback;
            } catch (RuntimeException invalid) { return fallback; }
        }
    }

    /** Registers negotiated music channels and native teardown hooks; clients cannot request server files. */
    public static void register() {
        PayloadTypeRegistry.clientboundPlay().register(ClockworkBossMusicControl.TYPE, ClockworkBossMusicControl.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ClockworkBossMusicData.TYPE, ClockworkBossMusicData.CODEC);
        ServerPlayerEvents.LEAVE.register(player -> { leave(player); SENT.remove(player.getUUID()); });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> { ENCOUNTERS.clear(); SENT.clear(); FILES.clear(); });
    }

    /** Entry/exit differences borrow the bar's already calculated UUID set, including radius and dimension checks. */
    public static void sync(ClockworkBossState state, ServerLevel level, Set<UUID> viewers) {
        Settings settings = state.music();
        if (!settings.enabled() || settings.volume() <= 0 || state.dormant()) {
            end(state.uuid(), level.getServer());
            return;
        }
        Encounter encounter = ENCOUNTERS.get(state.uuid());
        if (encounter != null && !encounter.settings.equals(settings)) {
            end(state.uuid(), level.getServer());
            encounter = null;
        }
        if (encounter == null) {
            if (viewers.isEmpty()) return;
            encounter = new Encounter(state.uuid(), state.templateName(), settings, level.getServer());
            ENCOUNTERS.put(state.uuid(), encounter);
        }
        Encounter current = encounter;
        for (UUID id : Set.copyOf(current.listeners.keySet())) {
            if (!viewers.contains(id)) stop(current, id, level.getServer());
        }
        for (UUID id : viewers) {
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(id);
            if (player == null || player.hasDisconnected() || !ServerPlayNetworking.canSend(player, ClockworkBossMusicControl.TYPE)
                    || !ServerPlayNetworking.canSend(player, ClockworkBossMusicData.TYPE)
                    || !ServerPlayNetworking.canSend(player, ClockworkBossMediaChunk.TYPE)) continue;
            if (!current.listeners.containsKey(id)) current.listeners.put(id, new Listener());
        }
        if (current.listeners.isEmpty()) {
            ENCOUNTERS.remove(current.boss, current);
            return;
        }
        if (!current.requested && current.listeners.values().stream().anyMatch(listener -> !listener.started)) {
            current.requested = true;
            long generation = FILES.generation();
            String fileKey = current.template + ":" + settings.file();
            var request = FILES.request(fileKey, () -> load(current.template, settings.file()));
            if (request == null) {
                // A full queue is transient; the existing arena scan can retry.
                // A rejected file stays suppressed until reload, without disk/log churn.
                current.requested = FILES.rejected(fileKey);
                return;
            }
            MinecraftServer server = level.getServer();
            request.whenComplete((asset, failure) -> {
                if (!server.isRunning()) return;
                server.execute(() -> {
                    if (failure != null || generation != FILES.generation() || ENCOUNTERS.get(current.boss) != current) return;
                    current.requested = false;
                    for (UUID id : Set.copyOf(current.listeners.keySet())) {
                        ServerPlayer player = server.getPlayerList().getPlayer(id);
                        Listener listener = current.listeners.get(id);
                        if (listener != null && !listener.started && player != null && !player.hasDisconnected()
                                && player.level() == level) send(current, player, asset);
                    }
                });
            });
        }
    }

    /** Start control precedes data. A stop arriving during transfer removes its matching client token. */
    private static void send(Encounter encounter, ServerPlayer player, Asset asset) {
        Listener listener = encounter.listeners.get(player.getUUID());
        UUID token = listener.token;
        listener.started = true;
        String key = asset.data.key();
        ServerPlayNetworking.send(player, new ClockworkBossMusicControl(encounter.boss, token, true, key,
                encounter.settings.volume(), encounter.settings.loop(), encounter.settings.fadeSeconds(), encounter.settings.channel()));
        if (sent(player.getUUID(), key)) {
            ServerPlayNetworking.send(player, asset.data.cached());
            remember(player.getUUID(), key);
        } else {
            boolean queued = ClockworkBossMediaTransfers.send(player, "music:" + encounter.boss, asset.prepared,
                    () -> remember(player.getUUID(), key), () -> {
                        if (!sent(player.getUUID(), key)) return false;
                        ServerPlayNetworking.send(player, asset.data.cached());
                        remember(player.getUUID(), key);
                        return true;
                    }, () -> ENCOUNTERS.get(encounter.boss) == encounter
                            && encounter.listeners.get(player.getUUID()) == listener && !player.hasDisconnected());
            if (!queued) {
                // Do not leave an undelivered track permanently marked started.
                // Cancel the control already sent (which must precede file bytes),
                // then let the existing membership scan retry when space is free.
                listener.started = false;
                ServerPlayNetworking.send(player, new ClockworkBossMusicControl(encounter.boss, token, false, "", 0,
                        false, 0, encounter.settings.channel()));
            }
        }
    }

    /** Death, manual removal, unload and reload all terminate only this encounter's listeners. */
    public static void end(UUID boss, MinecraftServer server) {
        Encounter encounter = ENCOUNTERS.remove(boss);
        if (encounter == null) return;
        for (UUID id : Set.copyOf(encounter.listeners.keySet())) stop(encounter, id, server);
    }
    /** Cleanup callers need only the boss UUID; the bounded runtime record knows its owning server. */
    public static void end(UUID boss) {
        Encounter encounter = ENCOUNTERS.get(boss);
        if (encounter != null) end(boss, encounter.server);
    }
    private static void stop(Encounter encounter, UUID id, MinecraftServer server) {
        Listener listener = encounter.listeners.remove(id);
        ServerPlayer player = server.getPlayerList().getPlayer(id);
        if (listener != null && listener.started && player != null && !player.hasDisconnected()
                && ServerPlayNetworking.canSend(player, ClockworkBossMusicControl.TYPE))
            ServerPlayNetworking.send(player, new ClockworkBossMusicControl(encounter.boss, listener.token, false, "", 0,
                    false, encounter.settings.fadeSeconds(), encounter.settings.channel()));
    }
    /** Respawn/dimension replacement removes the old listener; the existing arena scan may admit the replacement. */
    public static void leave(ServerPlayer player) {
        for (Encounter encounter : ENCOUNTERS.values()) stop(encounter, player.getUUID(), player.level().getServer());
    }
    /** Invalidate old controls/cache snapshots before the manager reconciles the edited template's arena. */
    public static void reload(MinecraftServer server) {
        for (UUID boss : Set.copyOf(ENCOUNTERS.keySet())) end(boss, server);
        SENT.clear();
        FILES.clear();
    }

    /** Shared per-player four-file LRU mirrors the client, avoiding repeated full-track downloads. */
    private static boolean sent(UUID player, String key) {
        var cache = SENT.get(player);
        return cache != null && cache.get(key) != null;
    }
    private static void remember(UUID player, String key) {
        var cache = SENT.computeIfAbsent(player, ignored -> new LinkedHashMap<>(4, 0.75F, true));
        cache.put(key, true);
        while (cache.size() > 4) cache.remove(cache.keySet().iterator().next());
    }

    /** Only a regular direct sounds/ file may load; content, CRC/header limits, bytes and duration are checked off-thread. */
    private static Asset load(String template, String file) throws Exception {
        var directory = ClockworkBossTemplateStore.mediaDirectory(template, "sounds").toRealPath();
        var requested = directory.resolve(file);
        ClockworkBossTemplateStore.checkOwnedPath(requested);
        if (Files.isSymbolicLink(requested)) throw new IOException("Music cannot be a symbolic link");
        var path = requested.toRealPath();
        if (!directory.equals(path.getParent()) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Music must be directly in this boss template's sounds folder");
        long size = Files.size(path);
        if (size < 27 || size > ClockworkBossMusicData.MAX_BYTES) throw new IOException("Music exceeds the 8 MiB limit");
        byte[] bytes;
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(ClockworkBossMusicData.MAX_BYTES + 1);
        }
        if (bytes.length != size || ClockworkBossMusicData.validatedDurationMillis(bytes) <= 0)
            throw new IOException("Music must be a safe mono/stereo OGG Vorbis track of at most ten minutes");
        String revision = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        return new Asset(new ClockworkBossMusicData(template, file, revision, bytes));
    }
    private static final class Asset {
        final ClockworkBossMusicData data;
        final ClockworkBossMediaTransfers.Prepared prepared;
        Asset(ClockworkBossMusicData data) {
            this.data = data;
            // This codec contains no registry references. Encode once on the I/O
            // worker, keeping an eight-megabyte copy off the server tick thread.
            this.prepared = ClockworkBossMediaTransfers.prepare(data, net.minecraft.core.RegistryAccess.EMPTY);
        }
    }
    private static final class Encounter {
        final UUID boss;
        final String template;
        final Settings settings;
        final MinecraftServer server;
        final Map<UUID, Listener> listeners = new HashMap<>();
        boolean requested;
        Encounter(UUID boss, String template, Settings settings, MinecraftServer server) {
            this.boss = boss; this.template = template; this.settings = settings; this.server = server;
        }
    }
    /** Tokens and booleans are small; encounters never retain music files beyond the four-entry cache. */
    private static final class Listener {
        final UUID token = UUID.randomUUID();
        boolean started;
    }
}
