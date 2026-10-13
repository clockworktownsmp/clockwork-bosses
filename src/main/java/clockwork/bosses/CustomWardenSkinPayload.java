package clockwork.bosses;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Carries one server-approved custom Warden atlas to a client that is tracking
 * an Clockwork Boss using it. The payload contains bytes only, never a client path.
 */
public record CustomWardenSkinPayload(String templateName, String fileName, byte[] pngBytes) implements CustomPacketPayload {
    private static final java.util.regex.Pattern FILE_NAME =
            java.util.regex.Pattern.compile(ClockworkBossTemplateText.PNG_FILE);
    public static final int MAX_PNG_BYTES = 512 * 1024;
    public static final Type<CustomWardenSkinPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(ClockworkBosses.MOD_ID, "custom_warden_skin_v2"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CustomWardenSkinPayload> CODEC = StreamCodec.of(
            (buffer, payload) -> {
                buffer.writeUtf(payload.templateName, 64);
                buffer.writeUtf(payload.fileName, 80);
                buffer.writeByteArray(payload.pngBytes);
            },
            buffer -> new CustomWardenSkinPayload(
                    buffer.readUtf(64),
                    buffer.readUtf(80),
                    buffer.readByteArray(MAX_PNG_BYTES)));

    public CustomWardenSkinPayload {
        if (!ClockworkBossTemplateNames.isValid(templateName) || !isValidFileName(fileName)) {
            throw new IllegalArgumentException("Invalid custom Warden skin filename");
        }
        if (pngBytes == null || pngBytes.length == 0 || pngBytes.length > MAX_PNG_BYTES) {
            throw new IllegalArgumentException("Custom Warden skin payload size is invalid");
        }
        // Keep packet contents immutable so the encoder cannot observe later changes.
        pngBytes = pngBytes.clone();
    }

    @Override
    public byte[] pngBytes() {
        return pngBytes.clone();
    }

    @Override
    public Type<CustomWardenSkinPayload> type() {
        return TYPE;
    }

    /** A logical cache identity only; clients never resolve it as a filesystem path. */
    public static String skinIdentity(String templateName, String fileName) {
        return templateName + ":" + fileName;
    }

    /** Filenames are single safe PNG names; no folder component is accepted. */
    public static boolean isValidFileName(String fileName) {
        return fileName != null
                && FILE_NAME.matcher(fileName).matches();
    }

    /** Checks the PNG signature and IHDR dimensions before any decoder is used. */
    public static boolean hasWardenAtlasHeader(byte[] bytes) {
        if (bytes == null || bytes.length < 33 || bytes.length > MAX_PNG_BYTES) {
            return false;
        }
        byte[] signature = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        for (int i = 0; i < signature.length; i++) {
            if (bytes[i] != signature[i]) return false;
        }
        return readInt(bytes, 8) == 13
                && bytes[12] == 'I'
                && bytes[13] == 'H'
                && bytes[14] == 'D'
                && bytes[15] == 'R'
                && readInt(bytes, 16) == 128
                && readInt(bytes, 20) == 128;
    }

    /** Reads a validated PNG big-endian dimension field without allocating an image. */
    private static int readInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 24)
                | ((bytes[offset + 1] & 0xFF) << 16)
                | ((bytes[offset + 2] & 0xFF) << 8)
                | (bytes[offset + 3] & 0xFF);
    }
}
