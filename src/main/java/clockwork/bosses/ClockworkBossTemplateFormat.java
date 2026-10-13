package clockwork.bosses;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.FilterReader;
import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/** Portable template envelope: Gson is its only library dependency. It knows
 * nothing about entities, registries, addon factories, file paths or a server.
 * Parsing preserves JSON data and order; gameplay validation remains separate. */
public final class ClockworkBossTemplateFormat {
    public static final String VERSION_FIELD = "format_version";
    public static final int CURRENT_VERSION = 1;
    public static final int MAX_BYTES = 512 * 1024;
    public static final int MAX_NESTING = 32;
    // These names belong to documentation/editor transport only at the ROOT.
    // Identically named fields inside an addon object are ordinary addon data.
    public static final Set<String> ROOT_METADATA = Set.of("_documentation", "_action_catalog", "_action_types",
            "_phase_order", "_subphase_orders", "_roar_orders", "_loot_entry_order");
    private static final Gson JSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();

    private ClockworkBossTemplateFormat() {}

    /** Accept existing commented files without injecting defaults or rewriting
     * them. Duplicate keys are errors: silently taking the last value loses data.
     * The caller owns/closes the Reader; disk readers also reject oversized files
     * before allocating their input bytes. */
    public static JsonObject read(Reader input) throws IOException {
        JsonReader reader = new JsonReader(new BoundedReader(input));
        reader.setStrictness(Strictness.LENIENT); // Existing JSON-with-comments syntax.
        reader.setNestingLimit(MAX_NESTING);
        JsonElement value = readValue(reader);
        if (!value.isJsonObject() || reader.peek() != JsonToken.END_DOCUMENT)
            throw new IOException("Template must contain exactly one JSON object");
        JsonObject document = value.getAsJsonObject();
        version(document);
        return document;
    }

    /** Missing versions identify legacy revision-1 files. Explicit versions must
     * be supported integers; a future editor must never downgrade an unknown format. */
    public static int version(JsonObject document) {
        JsonElement value = document.get(VERSION_FIELD);
        if (value == null) return CURRENT_VERSION;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException("format_version must be the number " + CURRENT_VERSION);
        BigDecimal number;
        try { number = ClockworkBossNumbers.decimal(value.getAsString()); }
        catch (NumberFormatException invalid) { throw new IllegalArgumentException("Invalid template format_version", invalid); }
        if (number.compareTo(BigDecimal.valueOf(CURRENT_VERSION)) != 0)
            throw new IllegalArgumentException("Unsupported template format_version; this build supports " + CURRENT_VERSION);
        return CURRENT_VERSION;
    }

    /** Copy only the envelope: unknown nested values, explicit nulls and object
     * order survive. No gameplay defaults or addon interpretations are applied.
     * A save marks legacy data with its version; merely reading does not. */
    public static JsonObject forSave(JsonObject document) {
        version(document);
        checkNesting(document, 0); // Before deepCopy: also bounds programmatically supplied trees.
        JsonObject result = new JsonObject();
        if (!document.has(VERSION_FIELD)) result.addProperty(VERSION_FIELD, CURRENT_VERSION);
        for (var entry : document.entrySet()) if (!ROOT_METADATA.contains(entry.getKey()))
            result.add(entry.getKey(), entry.getValue().deepCopy());
        return result;
    }

    /** Plain JSON export for tools that do not understand comments. Numeric
     * tokens never pass through double, so large addon IDs/exponents stay exact. */
    public static String writeJson(JsonObject document) {
        String text = JSON.toJson(forSave(document)) + "\n";
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
            throw new IllegalArgumentException("Template exceeds the 512 KiB size limit");
        return text;
    }

    /** Read containers ourselves to detect duplicates, and let Gson parse each
     * scalar so its normal lossless numeric representation is retained. */
    private static JsonElement readValue(JsonReader reader) throws IOException {
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                JsonObject object = new JsonObject(); reader.beginObject();
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    if (object.has(key)) throw new IOException("Duplicate template field: " + key.substring(0, Math.min(80, key.length())));
                    object.add(key, readValue(reader));
                }
                reader.endObject(); yield object;
            }
            case BEGIN_ARRAY -> {
                JsonArray array = new JsonArray(); reader.beginArray();
                while (reader.hasNext()) array.add(readValue(reader));
                reader.endArray(); yield array;
            }
            case STRING, NUMBER, BOOLEAN, NULL -> JsonParser.parseReader(reader);
            default -> throw new IOException("Expected a template JSON value");
        };
    }

    private static void checkNesting(JsonElement value, int depth) {
        if (depth >= MAX_NESTING && (value.isJsonObject() || value.isJsonArray()))
            throw new IllegalArgumentException("Template nesting exceeds " + MAX_NESTING + " levels");
        if (value.isJsonObject()) value.getAsJsonObject().entrySet().forEach(entry -> checkNesting(entry.getValue(), depth + 1));
        else if (value.isJsonArray()) value.getAsJsonArray().forEach(child -> checkNesting(child, depth + 1));
    }

    /** Cap streaming reads as well as files, so an editor/importer cannot feed
     * an unbounded Reader. Whitespace and comments count toward the limit too. */
    private static final class BoundedReader extends FilterReader {
        private int utf8Bytes;
        private boolean previousHighSurrogate;
        private BoundedReader(Reader input) { super(input); }
        @Override public int read(char[] buffer, int offset, int length) throws IOException {
            int count = super.read(buffer, offset, Math.min(length, Math.max(1, MAX_BYTES - utf8Bytes + 1)));
            for (int index = 0; index < count; index++) countCharacter(buffer[offset + index]);
            return count;
        }
        @Override public int read() throws IOException {
            int value = super.read();
            if (value >= 0) countCharacter((char) value);
            return value;
        }

        /** Count ASCII/Unicode UTF-8 sizes without allocating another copy.
         * A surrogate pair counts 3 + 1 bytes, even across reader chunks;
         * unpaired raw surrogates are conservatively counted as three bytes. */
        private void countCharacter(char value) throws IOException {
            utf8Bytes += value <= 0x7f ? 1 : value <= 0x7ff ? 2
                    : Character.isLowSurrogate(value) && previousHighSurrogate ? 1 : 3;
            previousHighSurrogate = Character.isHighSurrogate(value);
            if (utf8Bytes > MAX_BYTES) throw new IOException("Template input exceeds the 512 KiB UTF-8 limit");
        }
    }
}
