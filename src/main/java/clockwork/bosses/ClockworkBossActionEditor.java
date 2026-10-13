package clockwork.bosses;

import clockwork.bosses.api.BossActionRegistry;
import clockwork.bosses.api.BossSetting;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/** Shared, data-only action form rules. The server supplies the catalog to a
 * screen; clients do not load addon factories. Only trusted server definitions
 * may create setting fields during a bounded action-type change operation. */
public final class ClockworkBossActionEditor {
    public static final String CATALOG = "_action_catalog", TYPES = "_action_types";
    private ClockworkBossActionEditor() {}
    public static boolean typePath(String path) { return path.matches("phases\\.[^.]+\\.subphases\\.[^.]+\\.type"); }
    private static String childPath(String path) {
        int start = path.indexOf(".subphases.");
        int end = start < 0 ? -1 : path.indexOf('.', start + 11);
        return end < 0 ? null : path.substring(0, end);
    }
    private static JsonObject catalog(JsonObject root) {
        JsonElement value = root.get(CATALOG);
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : BossActionRegistry.descriptors();
    }
    private static JsonObject definition(JsonObject root, String type) {
        JsonElement descriptor = catalog(root).get(ClockworkBossActions.resolve(type).toString());
        return descriptor != null && descriptor.isJsonObject() ? descriptor.getAsJsonObject() : null;
    }
    public static List<String> choices(JsonObject root, String current) {
        List<String> choices = new ArrayList<>(catalog(root).keySet());
        if (current.equals("attack") || current.equals("recovery")) {
            choices.remove(ClockworkBossActions.resolve(current).toString());
            choices.addFirst(current); // Show one readable choice for the current legacy alias.
        }
        return List.copyOf(choices);
    }
    /** Virtual metadata never enters durable template JSON. Older legacy children
     * keep their root controls until an owner explicitly selects a registered action. */
    public static void prepare(JsonObject root) {
        root.add(CATALOG, BossActionRegistry.descriptors());
        var phases = root.getAsJsonObject("phases");
        if (phases == null) return;
        for (var phase : phases.entrySet()) {
            var children = phase.getValue().getAsJsonObject().getAsJsonObject("subphases");
            if (children == null) continue;
            for (var entry : children.entrySet()) {
                var child = entry.getValue().getAsJsonObject();
                String type = child.has("type") ? child.get("type").getAsString() : "attack";
                if (type.equals("attack") || type.equals("recovery")) continue;
                JsonObject defaults = defaults(root, type);
                if (!child.has("action_settings")) child.add("action_settings", defaults);
                else if (child.get("action_settings").isJsonObject()) {
                    JsonObject settings = child.getAsJsonObject("action_settings");
                    defaults.entrySet().forEach(field -> { if (!settings.has(field.getKey())) settings.add(field.getKey(), field.getValue()); });
                }
            }
        }
    }
    public static JsonObject defaults(JsonObject root, String type) {
        JsonObject result = new JsonObject(), action = definition(root, type);
        if (action != null) action.getAsJsonObject("settings").entrySet().forEach(entry ->
                result.add(entry.getKey(), entry.getValue().getAsJsonObject().get("default").deepCopy()));
        return result;
    }
    /** Preserve the child's existing phase/combat overrides. Selecting a different
     * mechanic explicitly replaces its settings with that mechanic's defaults. */
    public static void changeType(JsonObject root, String childPath, String type) {
        if (!typePath(childPath + ".type")) throw new IllegalArgumentException("Invalid action selection path");
        JsonElement raw = ClockworkBossEditorSchema.get(root, childPath);
        if (raw == null || !raw.isJsonObject()) throw new IllegalArgumentException("Unknown action subphase");
        JsonObject child = raw.getAsJsonObject();
        if (definition(root, type) == null) throw new IllegalArgumentException("Action addon is missing: " + type);
        String previous = child.has("type") ? child.get("type").getAsString() : "attack";
        if (previous.equals(type)) return;
        child.addProperty("type", type);
        if (type.equals("attack") || type.equals("recovery")) child.remove("action_settings");
        else child.add("action_settings", defaults(root, type));
    }
    public static void applyTypes(JsonObject root, JsonObject patch) {
        JsonElement raw = patch.get(TYPES); if (raw == null) return;
        if (!raw.isJsonObject() || raw.getAsJsonObject().size() > 256) throw new IllegalArgumentException("Invalid action selection operation");
        for (var entry : raw.getAsJsonObject().entrySet()) {
            if (!entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isString())
                throw new IllegalArgumentException("Action ID must be text");
            changeType(root, entry.getKey(), entry.getValue().getAsString());
        }
    }
    public static String label(JsonObject root, String path, String fallback) {
        BossSetting field = setting(root, path);
        return field == null ? fallback : field.label();
    }
    public static String actionLabel(JsonObject root, String type) {
        JsonObject action = definition(root, type);
        return action == null ? type + " (addon missing)" : action.get("label").getAsString();
    }
    public static BossSetting setting(JsonObject root, String path) {
        String child = childPath(path);
        if (child == null || !path.startsWith(child + ".action_settings.")) return null;
        var selected = ClockworkBossEditorSchema.get(root, child + ".type");
        if (selected == null || !selected.isJsonPrimitive()) return null;
        if (selected.getAsString().equals("attack") || selected.getAsString().equals("recovery")) return null;
        JsonObject action = definition(root, selected.getAsString());
        String key = path.substring((child + ".action_settings.").length());
        if (action == null || !action.getAsJsonObject("settings").has(key)) return null;
        return BossSetting.fromDescriptor(key, action.getAsJsonObject("settings").getAsJsonObject(key));
    }
    /** Only the built-in roar promises an OGG basename. Addon text fields keep
     * their declared rules; a field merely named file is not assumed to be audio. */
    public static ClockworkBossTemplateText.Rule textRule(String action, String key) {
        return action.equals(ClockworkBossActions.ROAR.toString()) && key.equals("file")
                ? ClockworkBossTemplateText.rule("sounds.roar.file") : null;
    }
    public static void validateText(JsonObject root, String path, String text) {
        String child = childPath(path);
        if (child == null) return;
        var type = ClockworkBossEditorSchema.get(root, child + ".type");
        if (type == null || !path.startsWith(child + ".action_settings.")) return;
        var rule = textRule(type.getAsString(), path.substring((child + ".action_settings.").length()));
        if (rule != null) rule.validate(text);
    }
    public static ClockworkBossEditorSchema.Field field(JsonObject root, String path, JsonElement current) {
        if (typePath(path)) return new ClockworkBossEditorSchema.Field(ClockworkBossEditorSchema.Kind.TEXT,
                choices(root, current.getAsString()), false, false, 0, 0, "Choose a registered action. Addons supply their own settings below.");
        BossSetting field = setting(root, path);
        if (field == null) return null;
        var kind = ClockworkBossEditorSchema.Kind.valueOf(field.kind().name());
        return new ClockworkBossEditorSchema.Field(kind, kind == ClockworkBossEditorSchema.Kind.BOOLEAN ? List.of("true", "false") : field.choices(),
                true, field.integer(), field.min(), field.max(), field.help());
    }
    /** Validate declared scalars even when unchanged in a save. Unknown extension
     * keys are preserved but cannot become editable executable parameters. */
    public static void validate(JsonObject root) {
        var phases = root.getAsJsonObject("phases"); if (phases == null) return;
        for (var phase : phases.entrySet()) {
            var children = phase.getValue().getAsJsonObject().getAsJsonObject("subphases");
            if (children == null) continue;
            for (var entry : children.entrySet()) {
                JsonObject child = entry.getValue().getAsJsonObject();
                String type = child.get("type").getAsString();
                if (definition(root, type) == null) throw new IllegalArgumentException("Action addon is missing: " + type);
                if (type.equals("attack") || type.equals("recovery")) continue; // Their legacy root fields are validated separately.
                JsonObject action = definition(root, type);
                JsonElement settings = child.get("action_settings");
                if (settings != null && !settings.isJsonObject()) throw new IllegalArgumentException("Action settings must be an object");
                for (var field : action.getAsJsonObject("settings").entrySet()) {
                    Object compiled = BossSetting.fromDescriptor(field.getKey(), field.getValue().getAsJsonObject()).parse(
                            settings == null ? null : settings.getAsJsonObject().get(field.getKey()));
                    var textRule = textRule(type, field.getKey());
                    if (textRule != null) textRule.validate((String) compiled);
                }
            }
        }
    }
}
