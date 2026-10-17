package clockwork.bosses;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Library identity is explicit, never an invented template name. This optional
 * channel is negotiated independently; legacy template music stays on data v1. */
public record ClockworkBossLibraryMusicData(String file, String revision, byte[] bytes) implements CustomPacketPayload {
    public static final Type<ClockworkBossLibraryMusicData> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(ClockworkBosses.MOD_ID, "library_music_data_v1"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ClockworkBossLibraryMusicData> CODEC = StreamCodec.of(
            (b, p) -> { b.writeUtf(p.file, 80); b.writeUtf(p.revision, 64); b.writeByteArray(p.bytes); },
            b -> new ClockworkBossLibraryMusicData(b.readUtf(80), b.readUtf(64), b.readByteArray(ClockworkBossMusicData.MAX_BYTES)));
    public ClockworkBossLibraryMusicData {
        if (!ClockworkBossSoundPayload.isValidFileName(file) || revision == null || !revision.matches("[a-f0-9]{64}")
                || bytes == null || bytes.length > ClockworkBossMusicData.MAX_BYTES)
            throw new IllegalArgumentException("Invalid shared music payload");
        bytes = bytes.clone();
    }
    @Override public byte[] bytes() { return bytes.clone(); }
    @Override public Type<ClockworkBossLibraryMusicData> type() { return TYPE; }
    public String key() { return "library::" + file + ":" + revision; }
    public ClockworkBossLibraryMusicData cached() { return new ClockworkBossLibraryMusicData(file, revision, new byte[0]); }
    public static boolean isValidKey(String key) {
        if (key == null || key.length() > 216) return false;
        String[] parts = key.split(":", -1);
        return parts.length == 4 && parts[0].equals("library") && parts[1].isEmpty()
                && ClockworkBossSoundPayload.isValidFileName(parts[2]) && parts[3].matches("[a-f0-9]{64}");
    }
}
