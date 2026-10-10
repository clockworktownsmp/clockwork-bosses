package clockwork.bosses;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import java.util.UUID;

/** Small ordered start/stop messages; a membership token prevents late file transfers starting dead encounters. */
public record ClockworkBossMusicControl(UUID boss, UUID token, boolean start, String key, float volume,
                                       boolean loop, float fadeSeconds, net.minecraft.sounds.SoundSource channel) implements CustomPacketPayload {
    public static final Type<ClockworkBossMusicControl> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(ClockworkBosses.MOD_ID, "boss_music_control_v2"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ClockworkBossMusicControl> CODEC = StreamCodec.of(
            (b, p) -> { b.writeUUID(p.boss); b.writeUUID(p.token); b.writeBoolean(p.start); b.writeUtf(p.key, 216);
                b.writeFloat(p.volume); b.writeBoolean(p.loop); b.writeFloat(p.fadeSeconds); b.writeUtf(p.channel.getName(), 16); },
            b -> new ClockworkBossMusicControl(b.readUUID(), b.readUUID(), b.readBoolean(), b.readUtf(216),
                    b.readFloat(), b.readBoolean(), b.readFloat(), ClockworkBossMusicChannels.require(b.readUtf(16))));
    public ClockworkBossMusicControl {
        if (boss == null || token == null || key == null || channel == null || key.length() > 216
                || (start && !ClockworkBossMusicData.isValidKey(key))
                || !Float.isFinite(volume) || !Float.isFinite(fadeSeconds)
                || volume < 0 || volume > 4 || fadeSeconds < 0 || fadeSeconds > 10)
            throw new IllegalArgumentException("Invalid boss music control");
    }
    @Override public Type<ClockworkBossMusicControl> type() { return TYPE; }
}
