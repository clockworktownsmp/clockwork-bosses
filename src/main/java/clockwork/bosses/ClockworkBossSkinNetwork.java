package clockwork.bosses;

import net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.warden.Warden;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/** Sends server-owned custom Warden atlases only to clients tracking a boss that uses them. */
public final class ClockworkBossSkinNetwork {
    private static final ClockworkBossMediaCache<CachedSkin> SKIN_FILES = new ClockworkBossMediaCache<>();
    // Coalesce pending deliveries and same-tick duplicates, without assuming
    // that a client permanently retains a previously transferred atlas.
    private static final Map<java.util.UUID, Map<String, Object>> PENDING_DELIVERIES = new HashMap<>();
    private static final Map<java.util.UUID, LinkedHashMap<String, Long>> SENT_TICKS = new HashMap<>();

    private ClockworkBossSkinNetwork() {}

    /** Registers the shared packet codec and sends files when an entity enters client tracking range. */
    public static void register() {
        PayloadTypeRegistry.clientboundPlay().register(
                CustomWardenSkinPayload.TYPE,
                CustomWardenSkinPayload.CODEC);

        EntityTrackingEvents.START_TRACKING.register(ClockworkBossSkinNetwork::onStartTracking);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            // The event can arrive on a network thread; bookkeeping is server-owned.
            java.util.UUID uuid = handler.player.getUUID();
            server.execute(() -> {
                if (server.getPlayerList().getPlayer(uuid) != handler.player
                        && server.getPlayerList().getPlayer(uuid) != null) return;
                PENDING_DELIVERIES.remove(uuid);
                SENT_TICKS.remove(uuid);
            });
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> clearCaches());
    }

    /**
     * Sends only a marked boss's custom atlas when native Fabric tracking starts; no second tracking
     * registry is maintained.
     */
    private static void onStartTracking(Entity entity, ServerPlayer player) {
        if (!(entity instanceof Warden warden)) return;
        ClockworkBossMarker marker = (ClockworkBossMarker) (Object) warden;
        if (!marker.clockworkBosses$isClockworkBoss()) return;

        sendCustomSkin(marker, player);
    }

    /** Re-sends changed template skins to players already tracking a boss after /boss reload. */
    public static void refreshTrackedSkin(Warden warden) {
        ClockworkBossMarker marker = (ClockworkBossMarker) (Object) warden;
        if (!marker.clockworkBosses$isClockworkBoss()
                || !"custom".equals(marker.clockworkBosses$getSkinType())) {
            return;
        }
        // Fabric already owns the authoritative tracking set; no second registry
        // or STOP_TRACKING bookkeeping is necessary here.
        for (ServerPlayer player : PlayerLookup.tracking(warden)) {
            sendCustomSkin(marker, player);
        }
    }

    /** Invalidates disk snapshots on reload, including edits preserving timestamps. */
    public static void refreshFileCache() {
        SKIN_FILES.clear();
        ClockworkBossMediaTransfers.invalidateWaiting();
        PENDING_DELIVERIES.clear();
        SENT_TICKS.clear();
    }

    /**
     * Validates the filename and client capability, then sends bounded bytes; re-entry repairs client
     * cache eviction.
     */
    private static void sendCustomSkin(ClockworkBossMarker marker, ServerPlayer player) {
        if (!"custom".equals(marker.clockworkBosses$getSkinType())) return;
        String fileName = marker.clockworkBosses$getCustomSkinFile();
        String templateName = marker.clockworkBosses$getTemplate();
        if (!ClockworkBossTemplateNames.isValid(templateName)) return;
        String identity = CustomWardenSkinPayload.skinIdentity(templateName, fileName);
        if (!CustomWardenSkinPayload.isValidFileName(fileName)
                || !ServerPlayNetworking.canSend(player, CustomWardenSkinPayload.TYPE)) {
            return;
        }

        var server = player.level().getServer();
        java.util.UUID uuid = player.getUUID();
        var sent = SENT_TICKS.get(uuid);
        if (sent != null && java.util.Objects.equals(sent.get(identity), (long) server.getTickCount())) return;
        var deliveries = PENDING_DELIVERIES.computeIfAbsent(uuid, ignored -> new HashMap<>());
        if (deliveries.containsKey(identity)) return;
        long epoch = SKIN_FILES.generation();
        var request = SKIN_FILES.request(identity, () -> loadServerSkin(templateName, fileName));
        if (request == null) {
            if (deliveries.isEmpty()) PENDING_DELIVERIES.remove(uuid, deliveries);
            return;
        }
        Object delivery = new Object();
        deliveries.put(identity, delivery);
        request.whenComplete((skin, failure) -> {
            if (!server.isRunning()) return;
            server.execute(() -> {
                if (PENDING_DELIVERIES.get(uuid) == deliveries) {
                    deliveries.remove(identity, delivery);
                    if (deliveries.isEmpty()) PENDING_DELIVERIES.remove(uuid, deliveries);
                }
                if (failure != null || SKIN_FILES.generation() != epoch
                        || server.getPlayerList().getPlayer(uuid) != player
                        || !ServerPlayNetworking.canSend(player, CustomWardenSkinPayload.TYPE)) return;
                // Re-entry still repairs client eviction. Deduplicate only this
                // server tick, so simultaneous bosses sharing an atlas send it once.
                var history = SENT_TICKS.computeIfAbsent(uuid,
                        ignored -> new LinkedHashMap<>(16, 0.75F, true));
                long now = server.getTickCount();
                if (java.util.Objects.equals(history.get(identity), now)) return;
                // Share immutable encoded bytes across recipients. Each player
                // acknowledges small chunks rather than receiving one large PNG.
                ClockworkBossMediaTransfers.send(player, "skin:" + identity,
                        skin.transfer(player.level().registryAccess()), () -> {
                            if (SKIN_FILES.generation() != epoch) return;
                            history.put(identity, (long) server.getTickCount());
                            while (history.size() > 16) history.remove(history.keySet().iterator().next());
                        });
            });
        });
    }

    /** Worker-only read from a direct regular file; the shared cache owns its validated snapshot. */
    private static CachedSkin loadServerSkin(String templateName, String fileName) throws IOException {
        Path skinsDirectory = ClockworkBossTemplateStore.mediaDirectory(templateName, "skins").toRealPath();
        Path requested = skinsDirectory.resolve(fileName);
        ClockworkBossTemplateStore.checkOwnedPath(requested);
        if (Files.isSymbolicLink(requested)) throw new IOException("skin file must not be a symbolic link");
        Path file = requested.toRealPath();
        if (!file.startsWith(skinsDirectory) || !skinsDirectory.equals(file.getParent())) {
            throw new IOException("file must be directly inside the server skins folder");
        }
        if (!Files.isRegularFile(file)) {
            throw new IOException("file does not exist");
        }

        long fileSize = Files.size(file);
        if (fileSize <= 0 || fileSize > CustomWardenSkinPayload.MAX_PNG_BYTES) {
            throw new IOException("file must be no larger than 512 KiB");
        }

        byte[] bytes;
        try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(CustomWardenSkinPayload.MAX_PNG_BYTES + 1);
        }
        if (bytes.length != fileSize || bytes.length > CustomWardenSkinPayload.MAX_PNG_BYTES) {
            throw new IOException("file changed or exceeded the size limit while being read");
        }
        if (!CustomWardenSkinPayload.hasWardenAtlasHeader(bytes)) {
            throw new IOException("file is not a 128x128 PNG Warden atlas");
        }

        return new CachedSkin(new CustomWardenSkinPayload(templateName, fileName, bytes));
    }

    /** Server stop releases compressed file snapshots; Fabric owns player tracking. */
    private static void clearCaches() { refreshFileCache(); }

    /** Worker publishes the payload; only server callbacks create/read its shared wire representation. */
    private static final class CachedSkin {
        private final CustomWardenSkinPayload payload;
        private ClockworkBossMediaTransfers.Prepared transfer;

        CachedSkin(CustomWardenSkinPayload payload) { this.payload = payload; }

        ClockworkBossMediaTransfers.Prepared transfer(net.minecraft.core.RegistryAccess registries) {
            if (transfer == null) transfer = ClockworkBossMediaTransfers.prepare(payload, registries);
            return transfer;
        }
    }
}
