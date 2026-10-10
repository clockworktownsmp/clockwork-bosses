package clockwork.bosses;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.UUID;

/** One bounded piece of a server-selected skin/sound; it contains no filesystem path or executable content. */
public record ClockworkBossMediaChunk(UUID transfer, int kind, int offset, int total, byte[] bytes)
        implements CustomPacketPayload {
    public static final int SKIN = 0;
    public static final int SOUND = 1;
    public static final int MUSIC = 2;
    public static final int CHUNK_BYTES = 16 * 1024;
    // Includes the original validated payload's metadata, not just its file bytes.
    public static final int MAX_TRANSFER_BYTES = 1024 * 1024;
    public static final int MAX_MUSIC_TRANSFER_BYTES = 8 * 1024 * 1024;
    public static final Type<ClockworkBossMediaChunk> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(ClockworkBosses.MOD_ID, "media_chunk_v3"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ClockworkBossMediaChunk> CODEC = StreamCodec.of(
            (buffer, value) -> {
                buffer.writeUUID(value.transfer);
                buffer.writeByte(value.kind);
                buffer.writeVarInt(value.offset);
                buffer.writeVarInt(value.total);
                buffer.writeByteArray(value.bytes);
            }, buffer -> new ClockworkBossMediaChunk(buffer.readUUID(), buffer.readUnsignedByte(),
                    buffer.readVarInt(), buffer.readVarInt(), buffer.readByteArray(CHUNK_BYTES)));

    public ClockworkBossMediaChunk {
        if (transfer == null || (kind != SKIN && kind != SOUND && kind != MUSIC) || bytes == null
                || bytes.length == 0 || bytes.length > CHUNK_BYTES || total <= 0
                || total > limit(kind) || offset < 0 || offset > total - bytes.length) {
            throw new IllegalArgumentException("Invalid Clockwork Boss media chunk");
        }
        bytes = bytes.clone();
    }

    @Override public byte[] bytes() { return bytes.clone(); }
    /** Larger assemblies are reserved for streaming music; skin/effect bounds remain unchanged. */
    public static int limit(int kind) { return kind == MUSIC ? MAX_MUSIC_TRANSFER_BYTES : MAX_TRANSFER_BYTES; }
    @Override public Type<ClockworkBossMediaChunk> type() { return TYPE; }
}
