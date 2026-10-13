package clockwork.web;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.*;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.HashSet;

/** Strict, bounded JSON inspection; saved bytes never pass through serialization.
 * Drafts may be unfinished. Publishing additionally checks the bundled core schema.
 * Unknown addon properties are preserved, not guessed or executed.
 */
public final class TemplateJson {
    public static final int MAX_BYTES = 512 * 1024;
    private final JsonObject schema;
    private final java.util.Map<String, java.util.regex.Pattern> patterns = new java.util.concurrent.ConcurrentHashMap<>();
    public TemplateJson() throws IOException {
        try (var input = TemplateJson.class.getResourceAsStream("/template-schema.json")) {
            if (input == null) throw new IOException("Missing schema");
            schema = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
    /** The merged server uses the current immutable core/addon export. The
     * default constructor retains the offline bundled-schema test/API contract. */
    public TemplateJson(byte[] schemaBytes) throws IOException {
        schema = parse(schemaBytes);
    }
    public static JsonObject parse(byte[] bytes) throws IOException {
        return parse(bytes, MAX_BYTES);
    }
    // Relay envelopes include base64; template inspection retains its 512 KiB limit.
    static JsonObject parse(byte[] bytes, int limit) throws IOException {
        if (bytes.length == 0 || bytes.length > limit) throw new IOException("JSON exceeds size limit");
        String text;
        try { text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException error) { throw new IOException("Use UTF-8 JSON", error); }
        try (var reader = new JsonReader(new StringReader(text))) {
            reader.setStrictness(Strictness.STRICT);
            JsonElement root = read(reader, 0, new int[]{0});
            if (reader.peek() != JsonToken.END_DOCUMENT || !root.isJsonObject()) throw new IOException("Expected one JSON object");
            return root.getAsJsonObject();
        } catch (RuntimeException error) { throw new IOException("Invalid JSON", error); }
    }
    private static JsonElement read(JsonReader reader, int depth, int[] count) throws IOException {
        if (depth > 32 || ++count[0] > 40_000) throw new IOException("JSON is too deeply nested or complex");
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                reader.beginObject(); var object = new JsonObject(); var keys = new HashSet<String>();
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    if (!keys.add(key)) throw new IOException("Duplicate JSON property");
                    object.add(key, read(reader, depth + 1, count));
                }
                reader.endObject(); yield object;
            }
            case BEGIN_ARRAY -> {
                reader.beginArray(); var array = new JsonArray();
                while (reader.hasNext()) array.add(read(reader, depth + 1, count));
                reader.endArray(); yield array;
            }
            case STRING -> new JsonPrimitive(reader.nextString());
            case NUMBER -> {
                String token = reader.nextString();
                // Limit exponents before BigDecimal/schema comparisons can allocate.
                if (token.length() > 128 || token.matches(".*[eE][+-]?\\d{5,}.*")) throw new IOException("Number exceeds inspection limits");
                yield JsonParser.parseString(token);
            }
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> { reader.nextNull(); yield JsonNull.INSTANCE; }
            default -> throw new IOException("Invalid JSON value");
        };
    }
    public void inspect(byte[] bytes, boolean publishing) throws IOException {
        JsonObject root = parse(bytes);
        if (!root.has("format_version") || !root.get("format_version").isJsonPrimitive() || !root.getAsJsonPrimitive("format_version").isNumber() || root.get("format_version").getAsBigDecimal().compareTo(BigDecimal.ONE) != 0) throw new IOException("Template format_version must be 1");
        if (!publishing) return;
        validate(root, schema, "$", 0);
        JsonObject phases = object(root, "phases");
        BigDecimal previous = BigDecimal.valueOf(101);
        if (phases != null) for (var phase : phases.entrySet()) {
            JsonObject value = phase.getValue().getAsJsonObject();
            JsonElement cutoff = value.get("minimum_health_percent");
            if (cutoff != null && !cutoff.isJsonNull()) {
                BigDecimal next = cutoff.getAsBigDecimal();
                if (next.compareTo(previous) >= 0) throw new IOException("Phase health cutoffs must descend");
                previous = next;
            }
            checkPairs(value);
        }
        checkPairs(root);
        JsonObject loot = object(root, "loot");
        if (loot != null && loot.has("enabled") && loot.get("enabled").getAsBoolean() && (!loot.has("source") || loot.get("source").getAsString().equals("table")) && (!loot.has("loot_table") || loot.get("loot_table").getAsString().isEmpty())) throw new IOException("Enabled table rewards need loot_table");
    }
    private void checkPairs(JsonObject root) throws IOException {
        JsonObject targeting = object(root, "targeting");
        if (targeting != null) pair(targeting, "min_attacks", "max_attacks");
        JsonObject loot = object(root, "loot"); var entries = loot == null ? null : object(loot, "entries");
        if (entries != null) for (var entry : entries.entrySet()) pair(entry.getValue().getAsJsonObject(), "min_count", "max_count");
    }
    private static void pair(JsonObject object, String min, String max) throws IOException {
        if (object.has(min) && object.has(max) && !object.get(min).isJsonNull() && !object.get(max).isJsonNull() && object.get(min).getAsBigDecimal().compareTo(object.get(max).getAsBigDecimal()) > 0) throw new IOException(min + " exceeds " + max);
    }
    private static JsonObject object(JsonObject root, String key) { return root.has(key) && root.get(key).isJsonObject() ? root.getAsJsonObject(key) : null; }
    private void validate(JsonElement value, JsonObject definition, String path, int depth) throws IOException {
        if (depth > 64) throw new IOException("Schema recursion limit");
        if (definition.has("$ref")) {
            String reference = definition.get("$ref").getAsString();
            if (!reference.startsWith("#/$defs/")) throw new IOException("Unsupported bundled schema reference");
            validate(value, schema.getAsJsonObject("$defs").getAsJsonObject(reference.substring(8)), path, depth + 1);
            // The export's referenced definitions have no sibling constraints.
            return;
        }
        if (definition.has("type")) {
            JsonElement types = definition.get("type"); boolean accepted = false;
            if (types.isJsonArray()) for (var type : types.getAsJsonArray()) accepted |= typeMatches(value, type.getAsString());
            else accepted = typeMatches(value, types.getAsString());
            if (!accepted) throw new IOException(path + ": wrong value type");
        }
        if (definition.has("const") && !value.equals(definition.get("const"))) throw new IOException(path + ": unsupported constant");
        if (definition.has("enum")) {
            boolean found = false; for (var option : definition.getAsJsonArray("enum")) found |= value.equals(option);
            if (!found) throw new IOException(path + ": unsupported option");
        }
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
            BigDecimal number = value.getAsBigDecimal();
            if (definition.has("minimum") && number.compareTo(definition.get("minimum").getAsBigDecimal()) < 0 || definition.has("maximum") && number.compareTo(definition.get("maximum").getAsBigDecimal()) > 0) throw new IOException(path + ": number out of range");
        }
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            String text = value.getAsString(); int length = text.codePointCount(0, text.length());
            if (definition.has("maxLength") && length > definition.get("maxLength").getAsInt() || definition.has("minLength") && length < definition.get("minLength").getAsInt()) throw new IOException(path + ": string length out of range");
            if (definition.has("pattern") && !patterns.computeIfAbsent(definition.get("pattern").getAsString(), java.util.regex.Pattern::compile).matcher(text).find()) throw new IOException(path + ": invalid string format");
        }
        if (value.isJsonObject()) {
            var object = value.getAsJsonObject();
            if (definition.has("maxProperties") && object.size() > definition.get("maxProperties").getAsInt() || definition.has("minProperties") && object.size() < definition.get("minProperties").getAsInt()) throw new IOException(path + ": wrong property count");
            if (definition.has("required")) for (var key : definition.getAsJsonArray("required")) if (!object.has(key.getAsString())) throw new IOException(path + ": missing " + key.getAsString());
            for (var entry : object.entrySet()) {
                if (definition.has("propertyNames")) validate(new JsonPrimitive(entry.getKey()), definition.getAsJsonObject("propertyNames"), path, depth + 1);
                JsonObject props = object(definition, "properties");
                if (props != null && props.has(entry.getKey())) validate(entry.getValue(), props.getAsJsonObject(entry.getKey()), path + "." + entry.getKey(), depth + 1);
                else if (definition.has("additionalProperties") && definition.get("additionalProperties").isJsonObject()) validate(entry.getValue(), definition.getAsJsonObject("additionalProperties"), path + "." + entry.getKey(), depth + 1);
                else if (definition.has("additionalProperties") && definition.get("additionalProperties").isJsonPrimitive() && !definition.get("additionalProperties").getAsBoolean()) throw new IOException(path + ": unsupported property");
            }
        }
        if (value.isJsonArray()) {
            var array = value.getAsJsonArray();
            if (definition.has("maxItems") && array.size() > definition.get("maxItems").getAsInt() || definition.has("minItems") && array.size() < definition.get("minItems").getAsInt()) throw new IOException(path + ": wrong array length");
            if (definition.has("items")) for (var child : array) validate(child, definition.getAsJsonObject("items"), path, depth + 1);
        }
        if (definition.has("not") && matches(value, definition.getAsJsonObject("not"), path, depth)) throw new IOException(path + ": forbidden combination");
        if (definition.has("anyOf")) {
            boolean valid = false; for (var branch : definition.getAsJsonArray("anyOf")) valid |= matches(value, branch.getAsJsonObject(), path, depth);
            if (!valid) throw new IOException(path + ": unsupported combination");
        }
        if (definition.has("allOf")) for (var branch : definition.getAsJsonArray("allOf")) validate(value, branch.getAsJsonObject(), path, depth + 1);
        if (definition.has("if")) {
            String branch = matches(value, definition.getAsJsonObject("if"), path, depth) ? "then" : "else";
            if (definition.has(branch)) validate(value, definition.getAsJsonObject(branch), path, depth + 1);
        }
    }
    private boolean matches(JsonElement value, JsonObject definition, String path, int depth) { try { validate(value, definition, path, depth + 1); return true; } catch (IOException failure) { return false; } }
    private static boolean typeMatches(JsonElement value, String type) {
        return switch (type) {
            case "object" -> value.isJsonObject(); case "array" -> value.isJsonArray(); case "null" -> value.isJsonNull();
            case "string" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isString();
            case "boolean" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean();
            case "number" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber();
            case "integer" -> value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber() && value.getAsBigDecimal().stripTrailingZeros().scale() <= 0;
            default -> false;
        };
    }
}
