package clockwork.bosses;

import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Receipt-driven media flow control. Each connected player has at most one
 * 64 KiB window in flight, 64 waiting file identities and 8 MiB of waiting bytes. Native game packets can
 * run between chunks; no tick callback, timeout loop or custom executor is used.
 * All bookkeeping runs on the server thread. Clients can acknowledge bytes only,
 * never select a filename, allocate a larger transfer, or open a server path.
 */
public final class ClockworkBossMediaTransfers {
    private static final int MAX_WAITING_FILES = 64;
    private static final int MAX_WAITING_BYTES = 8 * 1024 * 1024;
    private static final int WINDOW_BYTES = 4 * ClockworkBossMediaChunk.CHUNK_BYTES;
    private static final Map<UUID, PlayerTransfers> PLAYERS = new HashMap<>();

    private ClockworkBossMediaTransfers() {}

    /** Register shared codecs before either side registers its play handlers. */
    public static void register() {
        PayloadTypeRegistry.clientboundPlay().register(ClockworkBossMediaChunk.TYPE, ClockworkBossMediaChunk.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ClockworkBossMediaReceipt.TYPE, ClockworkBossMediaReceipt.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ClockworkBossMediaReceipt.TYPE,
                (receipt, context) -> acknowledge(context.player(), receipt));
        ServerPlayerEvents.LEAVE.register(player -> PLAYERS.remove(player.getUUID()));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> PLAYERS.clear());
    }

    /** Encode once for all recipients; ordinary payload codecs still own file/field validation. */
    public static Prepared prepare(CustomPacketPayload payload, RegistryAccess registries) {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), registries);
        try {
            int kind;
            if (payload instanceof CustomWardenSkinPayload skin) {
                kind = ClockworkBossMediaChunk.SKIN;
                CustomWardenSkinPayload.CODEC.encode(buffer, skin);
            } else if (payload instanceof ClockworkBossSoundPayload sound) {
                kind = ClockworkBossMediaChunk.SOUND;
                ClockworkBossSoundPayload.CODEC.encode(buffer, sound);
            } else if (payload instanceof ClockworkBossMusicData music) {
                kind = ClockworkBossMediaChunk.MUSIC;
                ClockworkBossMusicData.CODEC.encode(buffer, music);
            } else {
                throw new IllegalArgumentException("Unsupported Clockwork Boss media payload");
            }
            if (buffer.readableBytes() > ClockworkBossMediaChunk.limit(kind)) {
                throw new IllegalArgumentException("Clockwork Boss media exceeds its wire-size limit");
            }
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.readBytes(bytes);
            return new Prepared(kind, bytes);
        } finally {
            buffer.release();
        }
    }

    /** Coalesces waiting repeats to their latest playback; completion means the final chunk was sent. */
    public static boolean send(ServerPlayer player, String key, Prepared media, Runnable delivered) {
        return send(player, key, media, delivered, () -> false, () -> true);
    }

    /** Audio can replay from an existing client cache or drop a stale queued event before transferring bytes. */
    public static boolean send(ServerPlayer player, String key, Prepared media, Runnable delivered,
                               java.util.function.BooleanSupplier replayCached,
                               java.util.function.BooleanSupplier relevant) {
        // Channel negotiation skips clients which do not implement this transport.
        if (!ServerPlayNetworking.canSend(player, ClockworkBossMediaChunk.TYPE)) return false;
        PlayerTransfers state = PLAYERS.get(player.getUUID());
        if (state == null || state.player != player) {
            state = new PlayerTransfers(player);
            PLAYERS.put(player.getUUID(), state);
        }
        // Repeated tracking events for the same immutable atlas can share the
        // transfer already underway. A reload creates a different Prepared value.
        if (media.kind == ClockworkBossMediaChunk.SKIN && state.current != null
                && state.current.media == media) return true;
        Delivery previous = state.waiting.get(key);
        int previousBytes = previous == null ? 0 : previous.media.bytes.length;
        int needed = state.waitingBytes - previousBytes + media.bytes.length;
        // A slow player cannot retain unbounded old file revisions. Prioritize
        // skin correctness over optional queued audio when the byte budget fills.
        if (media.kind == ClockworkBossMediaChunk.SKIN && (needed > MAX_WAITING_BYTES
                || previous == null && state.waiting.size() >= MAX_WAITING_FILES)) {
            var queued = state.waiting.entrySet().iterator();
            while (queued.hasNext() && (needed > MAX_WAITING_BYTES
                    || previous == null && state.waiting.size() >= MAX_WAITING_FILES)) {
                var entry = queued.next();
                if (!entry.getKey().equals(key) && entry.getValue().media.kind != ClockworkBossMediaChunk.SKIN) {
                    int size = entry.getValue().media.bytes.length;
                    state.waitingBytes -= size;
                    needed -= size;
                    queued.remove();
                }
            }
        }
        if (needed > MAX_WAITING_BYTES
                || previous == null && state.waiting.size() >= MAX_WAITING_FILES) return false;
        state.waiting.put(key, new Delivery(media, delivered, replayCached, relevant));
        state.waitingBytes = needed;
        startNext(state);
        return true;
    }

    /** Reload drops obsolete waiting events, preserving the in-flight chunk's flow-control reservation. */
    public static void invalidateWaiting() {
        for (PlayerTransfers state : PLAYERS.values()) {
            state.waiting.clear();
            state.waitingBytes = 0;
        }
    }

    /** A cumulative, sent chunk boundary advances one player; duplicates/foreign tokens do nothing. */
    private static void acknowledge(ServerPlayer player, ClockworkBossMediaReceipt receipt) {
        PlayerTransfers state = PLAYERS.get(player.getUUID());
        if (state == null || state.player != player || state.current == null
                || !state.token.equals(receipt.transfer())) return;
        int through = receipt.receivedThrough();
        int total = state.current.media.bytes.length;
        if (through <= state.acknowledgedThrough || through > state.sentThrough
                || through != total && through % ClockworkBossMediaChunk.CHUNK_BYTES != 0) return;
        state.acknowledgedThrough = through;
        if (through == total) {
            state.current = null;
            startNext(state);
        } else {
            fillWindow(state);
        }
    }

    /** A waiting file starts only when the previous file's final chunk has been acknowledged. */
    private static void startNext(PlayerTransfers state) {
        if (state.current != null) return;
        while (!state.waiting.isEmpty()) {
            var first = state.waiting.entrySet().iterator();
            Delivery next = first.next().getValue();
            first.remove();
            state.waitingBytes -= next.media.bytes.length;
            if (!next.relevant.getAsBoolean() || next.replayCached.getAsBoolean()) continue;
            // Once transmission starts, origin/world relevance checks are no
            // longer needed. Do not retain their captured world references.
            state.current = new Delivery(next.media, next.delivered, null, null);
            state.token = UUID.randomUUID();
            state.sentThrough = 0;
            state.acknowledgedThrough = 0;
            fillWindow(state);
            return;
        }
    }

    /** Four small chunks tolerate latency while keeping a strict 64 KiB send window. */
    private static void fillWindow(PlayerTransfers state) {
        Prepared media = state.current.media;
        while (state.sentThrough < media.bytes.length
                && state.sentThrough - state.acknowledgedThrough < WINDOW_BYTES) {
            int start = state.sentThrough;
            int end = Math.min(media.bytes.length, start + ClockworkBossMediaChunk.CHUNK_BYTES);
            ServerPlayNetworking.send(state.player, new ClockworkBossMediaChunk(state.token, media.kind,
                    start, media.bytes.length, Arrays.copyOfRange(media.bytes, start, end)));
            state.sentThrough = end;
            // Cache order follows actual final-chunk sends, not queue insertion.
            if (end == media.bytes.length) state.current.delivered.run();
        }
    }

    /** Private immutable wire bytes can be shared by all recipients without per-player file copies. */
    public static final class Prepared {
        private final int kind;
        private final byte[] bytes;
        private Prepared(int kind, byte[] bytes) { this.kind = kind; this.bytes = bytes; }
    }

    private record Delivery(Prepared media, Runnable delivered,
                            java.util.function.BooleanSupplier replayCached,
                            java.util.function.BooleanSupplier relevant) {}

    /** Player references and queued file bytes are released on native LEAVE/shutdown. */
    private static final class PlayerTransfers {
        final ServerPlayer player;
        final LinkedHashMap<String, Delivery> waiting = new LinkedHashMap<>();
        Delivery current;
        UUID token;
        int sentThrough;
        int acknowledgedThrough;
        int waitingBytes;

        PlayerTransfers(ServerPlayer player) { this.player = player; }
    }
}
