package clockwork.bosses;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Compressed music travels only through receipt-controlled chunks, never as one oversized native packet. */
public record ClockworkBossMusicData(String template, String file,
                                    String revision, byte[] bytes) implements CustomPacketPayload {
    public static final int MAX_BYTES = 8 * 1024 * 1024 - 1024;
    public static final int MAX_SECONDS = 600;
    public static final Type<ClockworkBossMusicData> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(ClockworkBosses.MOD_ID, "boss_music_data_v1"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ClockworkBossMusicData> CODEC = StreamCodec.of(
            (b, p) -> { b.writeUtf(p.template, 64);
                b.writeUtf(p.file, 80); b.writeUtf(p.revision, 64); b.writeByteArray(p.bytes); },
            b -> new ClockworkBossMusicData(b.readUtf(64),
                    b.readUtf(80), b.readUtf(64), b.readByteArray(MAX_BYTES)));
    public ClockworkBossMusicData {
        if (!ClockworkBossTemplateNames.isValid(template)
                || !ClockworkBossSoundPayload.isValidFileName(file) || revision == null
                || !revision.matches("[0-9a-f]{64}") || bytes == null || bytes.length > MAX_BYTES)
            throw new IllegalArgumentException("Invalid boss music data");
        bytes = bytes.clone();
    }
    @Override public byte[] bytes() { return bytes.clone(); }
    @Override public Type<ClockworkBossMusicData> type() { return TYPE; }
    public String key() { return template + ":" + file + ":" + revision; }
    public ClockworkBossMusicData cached() { return new ClockworkBossMusicData(template, file, revision, new byte[0]); }
    /** Identity contains only validated identifiers and a digest, never a client filesystem path. */
    public static boolean isValidKey(String key) {
        if (key == null || key.length() > 216) return false;
        if (ClockworkBossLibraryMusicData.isValidKey(key)) return true;
        String[] parts = key.split(":", -1);
        return parts.length == 3 && ClockworkBossTemplateNames.isValid(parts[0])
                && ClockworkBossSoundPayload.isValidFileName(parts[1]) && parts[2].matches("[0-9a-f]{64}");
    }
    /** The ordinary effect-sound thirty-second limit is deliberately unchanged. */
    public static long validatedDurationMillis(byte[] bytes) {
        return ClockworkBossSoundPayload.validatedDurationMillis(bytes, MAX_BYTES, MAX_SECONDS);
    }
}
