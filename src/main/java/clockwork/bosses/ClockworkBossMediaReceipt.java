package clockwork.bosses;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.UUID;

/** Acknowledges one received chunk; an unguessable transfer id cannot request a server file. */
public record ClockworkBossMediaReceipt(UUID transfer, int receivedThrough) implements CustomPacketPayload {
    public static final Type<ClockworkBossMediaReceipt> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(ClockworkBosses.MOD_ID, "media_receipt_v1"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ClockworkBossMediaReceipt> CODEC = StreamCodec.of(
            (buffer, value) -> {
                buffer.writeUUID(value.transfer);
                buffer.writeVarInt(value.receivedThrough);
            }, buffer -> new ClockworkBossMediaReceipt(buffer.readUUID(), buffer.readVarInt()));

    public ClockworkBossMediaReceipt {
        if (transfer == null || receivedThrough <= 0 || receivedThrough > ClockworkBossMediaChunk.MAX_MUSIC_TRANSFER_BYTES) {
            throw new IllegalArgumentException("Invalid Clockwork Boss media receipt");
        }
    }

    @Override public Type<ClockworkBossMediaReceipt> type() { return TYPE; }
}
