package clockwork.bosses;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * The template file format is JSON with // comments, accepted by Gson's native
 * comment-aware reader. Gameplay receives a JSON tree, never documentation.
 * The bundled default supplies comments for both newly created and saved files.
 * Unknown gameplay fields survive saves; comments are regenerated from this
 * catalog rather than trying to preserve textual edits around changed values.
 */
public final class ClockworkBossTemplateDocument {
    // Null is meaningful: phase sound/scale overrides inherit the base value.
    // HTML escaping is unnecessary in a local config and would obscure examples.
    private static final Gson JSON = new GsonBuilder().serializeNulls()
            .disableHtmlEscaping().create();
    private static final JsonObject DEFAULTS = readDefaults();

    private ClockworkBossTemplateDocument() {}

    /** Returns an independent template tree so editing one boss cannot alter defaults. */
    public static JsonObject defaults() {
        JsonObject copy = DEFAULTS.deepCopy();
        copy.remove("_documentation");
        return copy;
    }

    /** Shares the same field help with the in-game editor and generated file comments. */
    public static String description(String path) {
        return description(DEFAULTS.getAsJsonObject("_documentation"), path);
    }

    /** Uses Gson for comments and syntax; rejects extra roots and excessive nesting. */
    public static JsonObject read(Reader input) throws IOException {
        JsonReader reader = new JsonReader(input);
        reader.setStrictness(Strictness.LENIENT);
        reader.setNestingLimit(32);
        JsonElement value = JsonParser.parseReader(reader);
        if (!value.isJsonObject() || reader.peek() != JsonToken.END_DOCUMENT) {
            throw new IOException("Template must contain exactly one JSON object");
        }
        return value.getAsJsonObject();
    }

    /** Groups field notes above their section, leaving its values together for editing. */
    public static String write(JsonObject config) {
        JsonObject documentation = DEFAULTS.getAsJsonObject("_documentation").deepCopy();
        if (config.has("_documentation") && config.get("_documentation").isJsonObject()) {
            config.getAsJsonObject("_documentation").entrySet()
                    .forEach(entry -> documentation.add(entry.getKey(), entry.getValue()));
        }
        StringBuilder output = new StringBuilder("// Clockwork Boss template: JSON with comments. 20 game ticks = 1 second.\n"
                + "// Null phase overrides inherit base values; subphase overrides inherit their parent phase.\n"
                + "// Empty sound filenames disable events; an empty roar filename uses the vanilla roar.\n"
                + "// /boss reload applies edited settings to loaded bosses.\n");
        appendObject(output, config, documentation, "", 0);
        return output.append('\n').toString();
    }

    /** Walks objects only; arrays and scalar values are delegated unchanged to Gson. */
    private static void appendObject(StringBuilder output, JsonObject object,
                                     JsonObject docs, String path, int depth) {
        if (depth > 32) throw new IllegalArgumentException("Template nesting exceeds 32 levels");
        output.append("{\n");
        var fields = object.entrySet().stream()
                .filter(entry -> !entry.getKey().equals("_documentation")).toList();
        for (int index = 0; index < fields.size(); index++) {
            Map.Entry<String, JsonElement> entry = fields.get(index);
            String fieldPath = path.isEmpty() ? entry.getKey() : path + "." + entry.getKey();
            String indent = "  ".repeat(depth + 1);
            if (entry.getValue().isJsonObject()) {
                // A section's purpose and scalar field notes belong before its
                // opening key, never between editable scalar values inside it.
                appendSectionNotes(output, docs, entry.getValue().getAsJsonObject(), fieldPath, indent);
            } else if (path.isEmpty()
                    && (index == 0 || fields.get(index - 1).getValue().isJsonObject())) {
                // Root attributes have no enclosing section. Document their
                // contiguous group once, above its first value (usually health).
                for (int next = index; next < fields.size()
                        && !fields.get(next).getValue().isJsonObject(); next++) {
                    var scalar = fields.get(next);
                    String note = description(docs, scalar.getKey());
                    if (!note.isEmpty()) appendComment(output, indent, scalar.getKey() + ": " + note);
                }
            }
            output.append(indent).append(JSON.toJson(entry.getKey())).append(": ");
            if (entry.getValue().isJsonObject()) {
                appendObject(output, entry.getValue().getAsJsonObject(), docs, fieldPath, depth + 1);
            } else {
                output.append(JSON.toJson(entry.getValue()));
            }
            if (index + 1 < fields.size()) output.append(',');
            output.append('\n');
        }
        output.append("  ".repeat(depth)).append('}');
    }

    /** Nested objects get their own note block; direct fields stay in the parent's block. */
    private static void appendSectionNotes(StringBuilder output, JsonObject docs,
                                           JsonObject section, String path, String indent) {
        appendComment(output, indent, description(docs, path));
        for (var field : section.entrySet()) {
            if (field.getKey().equals("_documentation") || field.getValue().isJsonObject()) continue;
            String note = description(docs, path + "." + field.getKey());
            if (!note.isEmpty()) appendComment(output, indent, field.getKey() + ": " + note);
        }
    }

    /** Wraps prose without changing values; every line is explicitly a // comment. */
    private static void appendComment(StringBuilder output, String indent, String note) {
        if (note.isEmpty()) return;
        String remaining = note.replace('\r', ' ').replace('\n', ' ');
        while (remaining.length() > 100) {
            int split = remaining.lastIndexOf(' ', 100);
            if (split <= 0) split = 100;
            output.append(indent).append("// ").append(remaining, 0, split).append('\n');
            remaining = remaining.substring(split).stripLeading();
        }
        output.append(indent).append("// ").append(remaining).append('\n');
    }

    /** Four phase objects share the same field descriptions through a catalog wildcard. */
    private static String description(JsonObject docs, String path) {
        String wildcard = path.replaceFirst("^phases\\.[^.]+(?=\\.|$)",
                "phases.<phase>").replaceFirst("\\.subphases\\.[^.]+(?=\\.|$)", ".subphases.<subphase>")
                .replaceFirst("\\.roars\\.[^.]+(?=\\.|$)", ".roars.<roar>")
                .replaceFirst("^loot\\.entries\\.[^.]+(?=\\.|$)", "loot.entries.<entry>");
        JsonElement value = docs.has(path) ? docs.get(path) : docs.get(wildcard);
        if (value == null && wildcard.contains(".subphases.<subphase>.")) {
            String inherited = wildcard.replace(".subphases.<subphase>", "");
            JsonElement note = docs.get(inherited);
            if (note == null && inherited.startsWith("phases.<phase>.roar")) note = docs.get("attacks." + inherited.substring("phases.<phase>.".length()));
            if (note != null && note.isJsonPrimitive()) return note.getAsString().replace("inherits base", "inherits parent phase");
        }
        if (value == null && wildcard.contains(".roars.<roar>.")) {
            JsonElement note = docs.get(wildcard.replace(".roars.<roar>", ".roar"));
            if (note != null && note.isJsonPrimitive()) return note.getAsString()
                    .replace("child/phase null inherits the parent sound", "null inherits phase/base roar defaults");
        }
        if (value == null && wildcard.startsWith("phases.<phase>.warden_effects.phase_change.")) {
            // Optional nested flash/burst overrides use the same bounds as the
            // shared fields. Their enclosing section explains inheritance.
            JsonElement shared = docs.get(wildcard.substring("phases.<phase>.".length()));
            if (shared != null && shared.isJsonPrimitive()) {
                return shared.getAsString();
            }
        }
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    /** Loads the single authoritative defaults/catalog in UTF-8 from inside the mod jar. */
    private static JsonObject readDefaults() {
        try (var input = ClockworkBossTemplateDocument.class.getResourceAsStream("/default-clockwork-boss.json")) {
            if (input == null) throw new IOException("Missing default-clockwork-boss.json");
            try (Reader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                return read(reader);
            }
        } catch (IOException | RuntimeException failure) {
            throw new IllegalStateException("Cannot read bundled Clockwork Boss template", failure);
        }
    }
}
