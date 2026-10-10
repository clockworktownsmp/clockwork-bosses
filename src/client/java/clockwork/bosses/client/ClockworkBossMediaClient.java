package clockwork.bosses.client;

import clockwork.bosses.ClockworkBosses;

import clockwork.bosses.ClockworkBossMediaChunk;
import clockwork.bosses.ClockworkBossMediaReceipt;
import clockwork.bosses.ClockworkBossSoundPayload;
import clockwork.bosses.ClockworkBossMusicData;
import clockwork.bosses.CustomWardenSkinPayload;
import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/** Client-thread assembly of one bounded server file; every accepted chunk advances native receipt flow. */
public final class ClockworkBossMediaClient {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClockworkBosses.MOD_ID);
    private static UUID transfer;
    private static int kind;
    private static byte[] assembled;
    private static int received;

    private ClockworkBossMediaClient() {}

    /** TCP order and exact offsets prevent overlap/replay from allocating multiple assemblies. */
    public static void accept(ClockworkBossMediaChunk chunk, Minecraft client) {
        if (client.level == null || client.getConnection() == null) return;
        if (chunk.offset() == 0) {
            transfer = chunk.transfer();
            kind = chunk.kind();
            assembled = new byte[chunk.total()];
            received = 0;
        }
        if (assembled == null || !chunk.transfer().equals(transfer) || kind != chunk.kind()
                || assembled.length != chunk.total() || received != chunk.offset()) {
            clear();
            LOGGER.warn("Rejected out-of-order Clockwork Boss media transfer");
            return;
        }
        byte[] part = chunk.bytes();
        System.arraycopy(part, 0, assembled, received, part.length);
        received += part.length;
        int acknowledged = received;
        if (received == assembled.length) {
            try {
                decodeComplete(client);
            } catch (RuntimeException invalid) {
                LOGGER.warn("Rejected Clockwork Boss media: {}", invalid.getMessage());
            } finally {
                clear();
            }
        }
        // A receipt contains only transfer id/offset. It cannot ask the server
        // to read any filename, and it is sent after bytes enter the bounded cache.
        if (ClientPlayNetworking.canSend(ClockworkBossMediaReceipt.TYPE)) {
            ClientPlayNetworking.send(new ClockworkBossMediaReceipt(chunk.transfer(), acknowledged));
        }
    }

    /** Only known, bounded production codecs can decode an assembled file. */
    private static void decodeComplete(Minecraft client) {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(
                Unpooled.wrappedBuffer(assembled), client.level.registryAccess());
        try {
            if (kind == ClockworkBossMediaChunk.SKIN) {
                CustomWardenSkinPayload skin = CustomWardenSkinPayload.CODEC.decode(buffer);
                if (buffer.isReadable()) throw new IllegalArgumentException("trailing skin payload bytes");
                WardenSkinTextureCache.acceptCustomSkinPayload(skin.templateName(), skin.fileName(), skin.pngBytes());
            } else if (kind == ClockworkBossMediaChunk.MUSIC) {
                ClockworkBossMusicData music = ClockworkBossMusicData.CODEC.decode(buffer);
                if (buffer.isReadable()) throw new IllegalArgumentException("trailing music payload bytes");
                ClockworkBossMusicClient.accept(music);
            } else {
                ClockworkBossSoundPayload sound = ClockworkBossSoundPayload.CODEC.decode(buffer);
                if (buffer.isReadable()) throw new IllegalArgumentException("trailing sound payload bytes");
                ClockworkBossSoundClient.accept(sound);
            }
        } finally {
            buffer.release();
        }
    }

    /** Session teardown drops the partial buffer; resource reload retains a connected transfer. */
    public static void clear() {
        transfer = null;
        assembled = null;
        received = 0;
    }
}
