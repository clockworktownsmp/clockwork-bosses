package clockwork.bosses.api;

import clockwork.bosses.ClockworkBossNumbers;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.math.BigDecimal;
import java.util.List;

/** A scalar setting declared once by an addon. The server compiler, generated
 * editor controls and file documentation use this same definition. Arrays and
 * arbitrary JSON are intentionally outside the initial editor API. */
public record BossSetting(String key, String label, String help, Kind kind,
                          JsonPrimitive defaultValue, boolean integer,
                          double min, double max, List<String> choices, int maxLength) {
    public enum Kind { NUMBER, BOOLEAN, TEXT }
    public BossSetting {
        if (key == null || !key.matches("[a-z][a-z0-9_]{0,47}") || label == null || label.isBlank()
                || label.length() > 64 || help == null || help.length() > 384 || kind == null || defaultValue == null
                || !Double.isFinite(min) || !Double.isFinite(max) || min > max || maxLength < 1 || maxLength > 512)
            throw new IllegalArgumentException("Invalid action setting definition");
        choices = List.copyOf(choices);
        if (choices.size() > 32 || choices.stream().anyMatch(s -> s == null || s.length() > maxLength))
            throw new IllegalArgumentException("Invalid action choices");
        // integer(key) returns a Java int. Reject definitions that accept values
        // which compile successfully but would fail when the action reads them.
        if (integer && (kind != Kind.NUMBER || min < Integer.MIN_VALUE || max > Integer.MAX_VALUE))
            throw new IllegalArgumentException("Whole-number action settings require 32-bit integer bounds");
        // Primitive defaults cannot expose a mutable JSON object to another boss.
        defaultValue = defaultValue.deepCopy();
    }
    public static BossSetting number(String key, String label, String help, double value, double min, double max, boolean integer) {
        BossSetting field = new BossSetting(key, label, help, Kind.NUMBER, new JsonPrimitive(value), integer, min, max, List.of(), 128);
        field.parse(field.defaultValue()); return field;
    }
    public static BossSetting bool(String key, String label, String help, boolean value) {
        return new BossSetting(key, label, help, Kind.BOOLEAN, new JsonPrimitive(value), false, 0, 1, List.of(), 5);
    }
    public static BossSetting text(String key, String label, String help, String value, List<String> choices, int maxLength) {
        BossSetting field = new BossSetting(key, label, help, Kind.TEXT, new JsonPrimitive(value), false, 0, 0, choices, maxLength);
        field.parse(field.defaultValue()); return field;
    }
    /** Strict native JSON types, bounded decimal parsing and exact inclusive
     * limits prevent the editor and hand-edited files from interpreting values differently. */
    public Object parse(JsonElement input) {
        JsonElement value = input == null || input.isJsonNull() ? defaultValue : input;
        if (!value.isJsonPrimitive()) throw new IllegalArgumentException(label + ": use a scalar value");
        var scalar = value.getAsJsonPrimitive();
        return switch (kind) {
            case BOOLEAN -> {
                if (!scalar.isBoolean()) throw new IllegalArgumentException(label + ": use true or false");
                yield scalar.getAsBoolean();
            }
            case NUMBER -> {
                if (!scalar.isNumber()) throw new IllegalArgumentException(label + ": use a number");
                BigDecimal number;
                try { number = ClockworkBossNumbers.decimal(scalar.getAsString()); }
                catch (NumberFormatException bad) { throw new IllegalArgumentException(label + ": number is too large or invalid"); }
                if (number.compareTo(BigDecimal.valueOf(min)) < 0 || number.compareTo(BigDecimal.valueOf(max)) > 0
                        || integer && number.stripTrailingZeros().scale() > 0)
                    throw new IllegalArgumentException(label + ": " + (integer ? "whole number " : "number ") + min + " to " + max);
                yield number;
            }
            case TEXT -> {
                if (!scalar.isString() || scalar.getAsString().length() > maxLength
                        || !choices.isEmpty() && !choices.contains(scalar.getAsString()))
                    throw new IllegalArgumentException(label + ": invalid text or option");
                yield scalar.getAsString();
            }
        };
    }
    /** Descriptors contain data only; clients never receive addon classes or callbacks. */
    public JsonObject descriptor() {
        JsonObject json = new JsonObject();
        json.addProperty("label", label); json.addProperty("help", help); json.addProperty("kind", kind.name());
        json.add("default", defaultValue.deepCopy()); json.addProperty("integer", integer);
        json.addProperty("min", min); json.addProperty("max", max); json.addProperty("max_length", maxLength);
        var options = new com.google.gson.JsonArray(); choices.forEach(options::add); json.add("choices", options);
        return json;
    }
    public static BossSetting fromDescriptor(String key, JsonObject json) {
        var choices = new java.util.ArrayList<String>();
        json.getAsJsonArray("choices").forEach(value -> choices.add(value.getAsString()));
        BossSetting field = new BossSetting(key, json.get("label").getAsString(), json.get("help").getAsString(),
                Kind.valueOf(json.get("kind").getAsString()), json.get("default").getAsJsonPrimitive(),
                json.get("integer").getAsBoolean(), json.get("min").getAsDouble(), json.get("max").getAsDouble(), choices,
                json.get("max_length").getAsInt());
        field.parse(field.defaultValue()); return field;
    }
}
