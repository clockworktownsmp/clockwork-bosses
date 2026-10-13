package clockwork.web;

/**
 * Allocation guard for Minecraft's JOrbis decoder, not a replacement decoder.
 * Follows the Xiph Vorbis I header layout, inspected alongside bundled JOrbis
 * 0.0.17. Size/CRC checks alone cannot bound header-declared arrays, codebooks
 * or residue lookup tables. Ordinary mono/stereo clips fit this conservative
 * profile; oversized metadata or unusually complex setups are rejected.
 */
final class VorbisSetup {
    static final int MAX_HEADER_BYTES = 128 * 1024;
    private static final long MAX_SETUP_CELLS = 1_000_000;

    private VorbisSetup() {}

    static void check(byte[] packet, int channels) throws java.io.IOException { check(packet, channels, 8192); }
    static void check(byte[] packet, int channels, int maximumBlock) throws java.io.IOException {
        if (!setup(packet, packet.length, channels, maximumBlock)) throw new java.io.IOException("Malformed or excessively complex Vorbis setup");
    }

    /** Validate lengths before JOrbis allocates vendor/comment arrays from 32-bit file values. */
    static boolean comment(byte[] packet, int size) {
        try {
            Bits bits = header(packet, size, 3);
            skipText(bits, 65_536);
            long comments = Integer.toUnsignedLong(bits.read(32));
            require(comments <= 256);
            for (int index = 0; index < comments; index++) skipText(bits, 16_384);
            return bits.read(1) == 1 && bits.remaining() < 8;
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    /** Walk setup fields without allocating its advertised tables; only small fixed-count arrays are used. */
    static boolean setup(byte[] packet, int size, int channels, int maximumBlock) {
        try {
            require((channels == 1 || channels == 2) && maximumBlock >= 64 && maximumBlock <= 8192
                    && Integer.bitCount(maximumBlock) == 1);
            Bits bits = header(packet, size, 5);
            int books = bits.read(8) + 1;
            int[] dimensions = new int[books];
            int[] entries = new int[books];
            int[] lookupTypes = new int[books];
            long cells = 0;
            for (int book = 0; book < books; book++) {
                require(bits.read(24) == 0x564342);
                dimensions[book] = bits.read(16);
                entries[book] = bits.read(24);
                require(dimensions[book] >= 1 && dimensions[book] <= 32
                        && entries[book] >= 1 && entries[book] <= 65_536);
                cells += (long) dimensions[book] * entries[book];
                require(cells <= MAX_SETUP_CELLS);
                readCodeLengths(bits, entries[book]);
                int lookup = lookupTypes[book] = bits.read(4);
                require(lookup <= 2);
                if (lookup != 0) {
                    bits.skip(64); // Packed minimum/delta floats have fixed width.
                    int valueBits = bits.read(4) + 1;
                    bits.skip(1);
                    long values = lookup == 1 ? lookupValues(entries[book], dimensions[book])
                            : (long) entries[book] * dimensions[book];
                    bits.skip(values * valueBits);
                }
            }

            int times = bits.read(6) + 1;
            for (int time = 0; time < times; time++) require(bits.read(16) == 0);
            int floors = bits.read(6) + 1;
            for (int floor = 0; floor < floors; floor++) readFloor(bits, books);

            int residues = bits.read(6) + 1;
            long[] residueCells = new long[residues];
            for (int residue = 0; residue < residues; residue++) {
                int type = bits.read(16);
                require(type <= 2);
                int begin = bits.read(24);
                int end = bits.read(24);
                require(begin <= end && end <= maximumBlock / 2 * (type == 2 ? channels : 1));
                bits.skip(24); // Partition size is encoded minus one; cannot be zero.
                int classifications = bits.read(6) + 1;
                int classbook = index(bits, 8, books);
                long values = boundedPower(classifications, dimensions[classbook], entries[classbook]);
                require(values <= entries[classbook]);
                residueCells[residue] = values * dimensions[classbook];
                int[] cascade = new int[classifications];
                int stageBooks = 0;
                for (int part = 0; part < classifications; part++) {
                    int low = bits.read(3);
                    int high = bits.read(1) == 1 ? bits.read(5) : 0;
                    cascade[part] = (high << 3) | low;
                    stageBooks += Integer.bitCount(cascade[part]);
                }
                // Bundled JOrbis stores these indices in a fixed 256-entry array.
                require(stageBooks <= 256);
                for (int part : cascade) {
                    for (int stage = 0; stage < 8; stage++) {
                        if ((part & (1 << stage)) != 0) require(lookupTypes[index(bits, 8, books)] != 0);
                    }
                }
            }

            int mappings = bits.read(6) + 1;
            long[] mappingCells = new long[mappings];
            for (int mapping = 0; mapping < mappings; mapping++) {
                require(bits.read(16) == 0);
                int submaps = bits.read(1) == 1 ? bits.read(4) + 1 : 1;
                require(submaps <= channels);
                if (bits.read(1) == 1) {
                    int coupling = bits.read(8) + 1;
                    require(channels == 2 && coupling == 1);
                    int magnitude = bits.read(1);
                    int angle = bits.read(1);
                    require(magnitude != angle);
                }
                require(bits.read(2) == 0);
                if (submaps > 1) {
                    for (int channel = 0; channel < channels; channel++) index(bits, 4, submaps);
                }
                for (int submap = 0; submap < submaps; submap++) {
                    index(bits, 8, times);
                    index(bits, 8, floors);
                    mappingCells[mapping] += residueCells[index(bits, 8, residues)];
                }
            }

            int modes = bits.read(6) + 1;
            require(modes <= 8);
            long modeCells = 0;
            for (int mode = 0; mode < modes; mode++) {
                bits.skip(1);
                require(bits.read(16) == 0 && bits.read(16) == 0);
                modeCells += mappingCells[index(bits, 8, mappings)];
                // JOrbis constructs a residue table for each mode/submap.
                require(modeCells <= MAX_SETUP_CELLS);
            }
            return bits.read(1) == 1 && bits.remaining() < 8;
        } catch (IllegalArgumentException malformed) {
            return false;
        }
    }

    /** Consume Huffman lengths with a fixed 32-step ordered limit and reject oversubscribed trees. */
    private static void readCodeLengths(Bits bits, int entries) {
        int[] lengths = new int[33];
        if (bits.read(1) == 1) {
            int length = bits.read(5) + 1;
            int assigned = 0;
            while (assigned < entries) {
                require(length <= 32);
                int count = bits.read(32 - Integer.numberOfLeadingZeros(entries - assigned));
                require(count <= entries - assigned);
                lengths[length++] += count;
                assigned += count;
            }
        } else {
            boolean sparse = bits.read(1) == 1;
            for (int entry = 0; entry < entries; entry++) {
                if (!sparse || bits.read(1) == 1) lengths[bits.read(5) + 1]++;
            }
        }
        long coverage = 0;
        for (int length = 1; length <= 32; length++) coverage += (long) lengths[length] << (32 - length);
        require(coverage > 0 && coverage <= (1L << 32));
    }

    /** Consume floor definitions, honoring the decoder's fixed 65-position floor-1 storage. */
    private static void readFloor(Bits bits, int books) {
        int type = bits.read(16);
        if (type == 0) {
            int order = bits.read(8);
            int rate = bits.read(16);
            int barkMap = bits.read(16);
            int amplitudeBits = bits.read(6);
            bits.skip(8);
            require(order >= 1 && order <= 64 && rate > 0 && barkMap > 0
                    && barkMap <= 8192 && amplitudeBits <= 31);
            int floorBooks = bits.read(4) + 1;
            for (int book = 0; book < floorBooks; book++) index(bits, 8, books);
        } else {
            require(type == 1);
            int partitions = bits.read(5);
            int[] classes = new int[partitions];
            int[] dimensions = new int[16];
            int highest = -1;
            for (int part = 0; part < partitions; part++) highest = Math.max(highest, classes[part] = bits.read(4));
            for (int group = 0; group <= highest; group++) {
                dimensions[group] = bits.read(3) + 1;
                int subclasses = bits.read(2);
                if (subclasses > 0) index(bits, 8, books);
                for (int sub = 0; sub < (1 << subclasses); sub++) require(bits.read(8) - 1 < books);
            }
            bits.skip(2);
            int rangeBits = bits.read(4);
            int posts = 2;
            for (int group : classes) posts += dimensions[group];
            require(posts <= 65);
            bits.skip((long) (posts - 2) * rangeBits);
        }
    }

    /** Integer root avoids floating-point rounding changing how many lookup bits are consumed. */
    private static int lookupValues(int entries, int dimensions) {
        int low = 1;
        int high = entries;
        while (low < high) {
            int middle = low + (high - low + 1) / 2;
            if (boundedPower(middle, dimensions, entries) <= entries) low = middle;
            else high = middle - 1;
        }
        return low;
    }

    /** Never multiplies above its small limit; hostile exponent fields cannot overflow or allocate. */
    private static long boundedPower(int base, int exponent, long limit) {
        long value = 1;
        for (int step = 0; step < exponent; step++) {
            if (value > limit / base) return limit + 1;
            value *= base;
        }
        return value;
    }

    /** Reject references outside an already bounded header table before the decoder indexes it. */
    private static int index(Bits bits, int width, int count) {
        int index = bits.read(width);
        require(index < count);
        return index;
    }

    /** Unsigned text lengths must fit both the metadata policy and the remaining packet. */
    private static void skipText(Bits bits, int limit) {
        long length = Integer.toUnsignedLong(bits.read(32));
        require(length <= limit);
        bits.skip(length * 8);
    }

    /** All header reads use little-endian bit order specified by Vorbis I. */
    private static Bits header(byte[] packet, int size, int type) {
        require(packet != null && size >= 7 && size <= packet.length && size <= MAX_HEADER_BYTES && packet[0] == type
                && packet[1] == 'v' && packet[2] == 'o' && packet[3] == 'r'
                && packet[4] == 'b' && packet[5] == 'i' && packet[6] == 's');
        return new Bits(packet, size, 56);
    }

    /** Fail this file through one cheap exception path; callers turn malformed headers into rejection. */
    private static void require(boolean valid) {
        if (!valid) throw new IllegalArgumentException("unsafe or malformed Vorbis header");
    }

    /** Reads only bounded bits; no allocation uses an unvalidated bitstream value. */
    private static final class Bits {
        final byte[] bytes;
        final int limit;
        int position;

        Bits(byte[] bytes, int size, int position) {
            this.bytes = bytes;
            this.limit = size * 8;
            this.position = position;
        }

        int read(int count) {
            require(count >= 0 && count <= 32 && count <= remaining());
            int result = 0;
            for (int bit = 0; bit < count; bit++, position++) {
                result |= ((bytes[position >>> 3] >>> (position & 7)) & 1) << bit;
            }
            return result;
        }

        void skip(long count) {
            require(count >= 0 && count <= remaining());
            position += (int) count;
        }

        int remaining() { return limit - position; }
    }
}
