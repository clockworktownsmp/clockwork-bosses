package clockwork.bosses;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/** Carries a server-selected OGG sound and its playback position/settings to nearby clients. */
public record ClockworkBossSoundPayload(
        String templateName,
        String fileName,
        String revision,
        byte[] oggBytes,
        double x,
        double y,
        double z,
        float volume,
        float pitch,
        float range,
        UUID bossId,
        boolean voiceLine
) implements CustomPacketPayload {
    // Vanilla clientbound custom payloads are limited to 1 MiB in 26.3.
    // Reserve space for filename/revision, position/settings, UUID and voice category.
    public static final int MAX_OGG_BYTES = 1024 * 1024 - 1024;
    private static final UUID UNGROUPED_SOUND = new UUID(0L, 0L);
    private static final int[] OGG_CRC_TABLE = createOggCrcTable();
    private static final int MAX_OGG_PAGES = 4096;
    // Use lowercase ASCII basenames without whitespace: letters, digits, dots, underscores
    // and hyphens only. Directory separators, colons and leading dots are also
    // rejected; the server confines regular files to its fixed sound folder.
    private static final java.util.regex.Pattern FILE_NAME =
            java.util.regex.Pattern.compile("[a-z0-9][a-z0-9._\\-]{0,63}\\.ogg");
    private static final java.util.regex.Pattern REVISION =
            java.util.regex.Pattern.compile("[0-9a-f]{64}");
    private static final double MAX_SOUND_COORDINATE = 30_000_000.0;
    public static final Type<ClockworkBossSoundPayload> TYPE = new Type<>(
            // Version the changed wire layout. Native channel negotiation keeps
            // an older client from decoding the new fields with its old codec.
            Identifier.fromNamespaceAndPath(ClockworkBosses.MOD_ID, "boss_sound_v3"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ClockworkBossSoundPayload> CODEC =
            StreamCodec.of(
                    (buffer, payload) -> {
                        buffer.writeUtf(payload.templateName, 64);
                        buffer.writeUtf(payload.fileName, 80);
                        buffer.writeUtf(payload.revision, 64);
                        buffer.writeByteArray(payload.oggBytes);
                        buffer.writeDouble(payload.x);
                        buffer.writeDouble(payload.y);
                        buffer.writeDouble(payload.z);
                        buffer.writeFloat(payload.volume);
                        buffer.writeFloat(payload.pitch);
                        buffer.writeFloat(payload.range);
                        buffer.writeUUID(payload.bossId);
                        buffer.writeBoolean(payload.voiceLine);
                    },
                    buffer -> new ClockworkBossSoundPayload(
                            buffer.readUtf(64),
                            buffer.readUtf(80),
                            buffer.readUtf(64),
                            buffer.readByteArray(MAX_OGG_BYTES),
                            buffer.readDouble(),
                            buffer.readDouble(),
                            buffer.readDouble(),
                            buffer.readFloat(),
                            buffer.readFloat(),
                            buffer.readFloat(),
                            buffer.readUUID(),
                            buffer.readBoolean()));

    /** Ordinary event sounds use the same codec but do not claim a voice playback slot. */
    public ClockworkBossSoundPayload(String fileName, String revision, byte[] oggBytes,
                                 double x, double y, double z, float volume, float pitch, float range) {
        this("default", fileName, revision, oggBytes, x, y, z, volume, pitch, range, UNGROUPED_SOUND, false);
    }

    public ClockworkBossSoundPayload {
        if (!ClockworkBossTemplateNames.isValid(templateName) || !isValidFileName(fileName) || revision == null || !REVISION.matcher(revision).matches() || bossId == null) {
            throw new IllegalArgumentException("Invalid Clockwork Boss sound identity");
        }
        if (oggBytes == null || oggBytes.length > MAX_OGG_BYTES) {
            throw new IllegalArgumentException("Clockwork Boss OGG payload size is invalid");
        }
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || Math.abs(x) > MAX_SOUND_COORDINATE
                || Math.abs(y) > MAX_SOUND_COORDINATE
                || Math.abs(z) > MAX_SOUND_COORDINATE
                || !Float.isFinite(volume) || !Float.isFinite(pitch) || !Float.isFinite(range)) {
            throw new IllegalArgumentException("Clockwork Boss sound position or playback values are invalid");
        }
        oggBytes = oggBytes.clone();
        volume = Math.max(0.0F, Math.min(4.0F, volume));
        pitch = Math.max(0.5F, Math.min(2.0F, pitch));
        range = Math.max(1.0F, Math.min(128.0F, range));
    }

    @Override
    public byte[] oggBytes() {
        return oggBytes.clone();
    }

    @Override
    public Type<ClockworkBossSoundPayload> type() {
        return TYPE;
    }

    /** Small callback descriptor; queued playback never retains a second complete compressed file. */
    public ClockworkBossSoundPayload playbackOnly() {
        return new ClockworkBossSoundPayload(templateName, fileName, revision, new byte[0], x, y, z,
                volume, pitch, range, bossId, voiceLine);
    }

    /** Accept only a single OGG filename, never a client-visible or server filesystem path. */
    public static boolean isValidFileName(String fileName) {
        return fileName != null
                && FILE_NAME.matcher(fileName).matches();
    }

    /** Template and category isolate equal basenames used by different bosses or events. */
    public static String soundIdentity(String templateName, String fileName, boolean voiceLine) {
        return templateName + ":" + (voiceLine ? "voice:" : "event:") + fileName;
    }

    /** Verifies that the bytes received for a cached revision match its content hash. */
    public static boolean matchesRevision(byte[] bytes, String revision) {
        if (bytes == null || revision == null || !REVISION.matcher(revision).matches()) {
            return false;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return MessageDigest.isEqual(
                    HexFormat.of().formatHex(digest).getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                    revision.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /**
     * Checks OGG bounds, sequence, CRCs and Vorbis header allocation limits.
     * This does not decode audio: Minecraft's decoder still reads audio packets
     * under the client decoded-size/duration limits.
     */
    public static boolean hasSafeVorbisContainer(byte[] bytes) {
        return validatedDurationMillis(bytes) >= 0;
    }

    /**
     * Validates the container before reading duration from its final PCM sample
     * count and identification sample rate. No audio decoder runs on the server.
     * Returns -1 for invalid/overlong data, 0 for a header-only empty stream.
     */
    public static long validatedDurationMillis(byte[] bytes) {
        return validatedDurationMillis(bytes, MAX_OGG_BYTES, 30);
    }

    /** Music shares the same container/header checks, with separate finite byte/time ceilings. */
    static long validatedDurationMillis(byte[] bytes, int maxBytes, int maxSeconds) {
        if (bytes == null || bytes.length < 27 || bytes.length > maxBytes) {
            return -1;
        }

        long finalSamples = -1;
        int offset = 0;
        int pageCount = 0;
        long serialNumber = -1L;
        long expectedSequence = 0L;
        boolean firstPacketComplete = false;
        boolean previousPageContinuesPacket = false;
        boolean sawEndOfStream = false;
        byte[] identificationHeader = new byte[30];
        int identificationHeaderLength = 0;
        // Reassemble only the two metadata headers, capped independently of
        // file size. Audio packets remain entirely Minecraft decoder-owned.
        byte[] metadataHeader = new byte[ClockworkBossVorbisHeaders.MAX_HEADER_BYTES];
        int metadataLength = 0;
        int metadataPackets = 0;

        while (offset < bytes.length) {
            if (++pageCount > MAX_OGG_PAGES || bytes.length - offset < 27
                    || !hasCapturePattern(bytes, offset)
                    || bytes[offset + 4] != 0) {
                return -1;
            }

            int headerType = bytes[offset + 5] & 0xFF;
            if ((headerType & ~0x07) != 0 || sawEndOfStream) {
                return -1;
            }
            boolean continuation = (headerType & 0x01) != 0;
            boolean beginning = (headerType & 0x02) != 0;
            boolean end = (headerType & 0x04) != 0;
            if ((pageCount == 1 && (continuation || !beginning))
                    || (pageCount > 1 && (beginning || continuation != previousPageContinuesPacket))) {
                return -1;
            }

            long pageSerial = readUnsignedIntLittleEndian(bytes, offset + 14);
            long sequence = readUnsignedIntLittleEndian(bytes, offset + 18);
            if (pageCount == 1) {
                serialNumber = pageSerial;
            }
            if (pageSerial != serialNumber || sequence != expectedSequence) {
                return -1;
            }
            expectedSequence = (expectedSequence + 1L) & 0xFFFF_FFFFL;

            int segmentCount = bytes[offset + 26] & 0xFF;
            int segmentTableEnd = offset + 27 + segmentCount;
            if (segmentTableEnd > bytes.length) {
                return -1;
            }

            int bodyLength = 0;
            for (int i = offset + 27; i < segmentTableEnd; i++) {
                bodyLength += bytes[i] & 0xFF;
            }
            int pageEnd = segmentTableEnd + bodyLength;
            if (pageEnd < segmentTableEnd || pageEnd > bytes.length
                    || oggPageChecksum(bytes, offset, pageEnd) != readIntLittleEndian(bytes, offset + 22)) {
                return -1;
            }

            int bodyOffset = segmentTableEnd;
            for (int i = offset + 27; i < segmentTableEnd; i++) {
                int segmentLength = bytes[i] & 0xFF;
                if (!firstPacketComplete) {
                    if (identificationHeaderLength + segmentLength > identificationHeader.length) {
                        return -1;
                    }
                    System.arraycopy(bytes, bodyOffset, identificationHeader,
                            identificationHeaderLength, segmentLength);
                    identificationHeaderLength += segmentLength;
                    if (segmentLength < 255) {
                        if (identificationHeaderLength != identificationHeader.length
                                || !isValidVorbisIdentificationHeader(identificationHeader)) {
                            return -1;
                        }
                        firstPacketComplete = true;
                    }
                } else if (metadataPackets < 2) {
                    if (metadataLength > metadataHeader.length - segmentLength) return -1;
                    System.arraycopy(bytes, bodyOffset, metadataHeader, metadataLength, segmentLength);
                    metadataLength += segmentLength;
                    if (segmentLength < 255) {
                        boolean safe = metadataPackets == 0
                                ? ClockworkBossVorbisHeaders.comment(metadataHeader, metadataLength)
                                : ClockworkBossVorbisHeaders.setup(metadataHeader, metadataLength,
                                        identificationHeader[11] & 0xFF,
                                        1 << ((identificationHeader[28] & 0xFF) >>> 4));
                        if (!safe) return -1;
                        metadataPackets++;
                        metadataLength = 0;
                    }
                }
                bodyOffset += segmentLength;
            }

            previousPageContinuesPacket = segmentCount > 0
                    && (bytes[segmentTableEnd - 1] & 0xFF) == 255;
            if (end) {
                if (!firstPacketComplete || previousPageContinuesPacket || pageEnd != bytes.length) {
                    return -1;
                }
                finalSamples = readUnsignedIntLittleEndian(bytes, offset + 6)
                        | (readUnsignedIntLittleEndian(bytes, offset + 10) << 32);
                sawEndOfStream = true;
            }
            offset = pageEnd;
        }

        if (pageCount == 0 || !firstPacketComplete || !sawEndOfStream || finalSamples < 0) return -1;
        if (finalSamples > 0 && metadataPackets != 2) return -1;
        long sampleRate = readUnsignedIntLittleEndian(identificationHeader, 12);
        // Bound the multiplication and server lock before converting samples to time.
        if (finalSamples > sampleRate * maxSeconds) return -1;
        return (finalSamples * 1000L + sampleRate - 1L) / sampleRate;
    }

    /** Checks the OggS page marker within already validated byte bounds. */
    private static boolean hasCapturePattern(byte[] bytes, int offset) {
        return bytes[offset] == 'O'
                && bytes[offset + 1] == 'g'
                && bytes[offset + 2] == 'g'
                && bytes[offset + 3] == 'S';
    }

    /**
     * Rejects unsupported codecs, invalid framing and unsafe channel/rate/block-size settings before
     * native decoding.
     */
    private static boolean isValidVorbisIdentificationHeader(byte[] header) {
        long version = readUnsignedIntLittleEndian(header, 7);
        int channels = header[11] & 0xFF;
        long sampleRate = readUnsignedIntLittleEndian(header, 12);
        int blockSizes = header[28] & 0xFF;
        int smallBlock = blockSizes & 0x0F;
        int largeBlock = blockSizes >>> 4;
        return header[0] == 1
                && header[1] == 'v' && header[2] == 'o' && header[3] == 'r'
                && header[4] == 'b' && header[5] == 'i' && header[6] == 's'
                && version == 0L
                && (channels == 1 || channels == 2)
                && sampleRate >= 8_000L && sampleRate <= 192_000L
                && smallBlock >= 6 && largeBlock <= 13 && smallBlock <= largeBlock
                && (header[29] & 0xFF) == 1;
    }

    /** CRC includes the whole bounded page with its checksum field treated as zero, per OGG. */
    private static int oggPageChecksum(byte[] bytes, int start, int end) {
        int checksum = 0;
        for (int i = start; i < end; i++) {
            int value = i >= start + 22 && i < start + 26 ? 0 : bytes[i] & 0xFF;
            checksum = (checksum << 8) ^ OGG_CRC_TABLE[((checksum >>> 24) ^ value) & 0xFF];
        }
        return checksum;
    }

    /** OGG uses a non-reflected CRC, so java.util.zip.CRC32 is not compatible. */
    private static int[] createOggCrcTable() {
        int[] table = new int[256];
        for (int value = 0; value < table.length; value++) {
            int crc = value << 24;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 0x8000_0000) != 0 ? (crc << 1) ^ 0x04C1_1DB7 : crc << 1;
            }
            table[value] = crc;
        }
        return table;
    }

    /** Reads an OGG unsigned 32-bit field without sign extension, after page/header bounds checks. */
    private static long readUnsignedIntLittleEndian(byte[] bytes, int offset) {
        return Integer.toUnsignedLong(readIntLittleEndian(bytes, offset));
    }

    /** Decodes a validated OGG 32-bit little-endian field without allocating a temporary buffer. */
    private static int readIntLittleEndian(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF)
                | ((bytes[offset + 1] & 0xFF) << 8)
                | ((bytes[offset + 2] & 0xFF) << 16)
                | ((bytes[offset + 3] & 0xFF) << 24);
    }
}
