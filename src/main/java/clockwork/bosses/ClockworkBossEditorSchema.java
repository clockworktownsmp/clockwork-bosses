package clockwork.bosses;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.math.BigDecimal;
import java.util.List;

/** Shared client/server editor rules. Gameplay still uses its existing compiled settings. */
public final class ClockworkBossEditorSchema {
    private static final JsonObject DEFAULTS = ClockworkBossTemplateDocument.defaults();
    private ClockworkBossEditorSchema() {}

    public enum Kind { BOOLEAN, NUMBER, TEXT, JSON }

    /** Inclusive bounds are displayed beside inputs and checked again by the server. */
    public record Field(Kind kind, List<String> choices, boolean nullable, boolean integer,
                        double min, double max, String help) {
        public String range() {
            if (kind != Kind.NUMBER) return "";
            return (integer ? "Whole number " : "") + number(min) + " to " + number(max);
        }
    }

    /** Backfill missing settings for editing, but never invent phase entries in an existing phase map. */
    public static JsonObject editable(JsonObject stored) {
        ClockworkBossTemplateFormat.version(stored);
        JsonObject copy = stored.deepCopy();
        copy.remove("_documentation");
        if (!copy.has("phases") || !copy.get("phases").isJsonObject()) {
            JsonObject phases = new JsonObject();
            String[] names = {"annoyed", "agitated", "enraged", "fury"};
            for (int index = 0; index < names.length; index++) {
                JsonObject phase = newPhase(); phase.addProperty("minimum_health_percent", 75 - index * 25); phases.add(names[index], phase);
            }
            copy.add("phases", phases);
        }
        merge(copy, DEFAULTS, "");
        // New files explicitly select Auto (0). Missing/null base ranges in
        // older files mean 32 blocks; display that without changing stored data.
        for (String path : ClockworkBossSoundDistance.BASE_RANGE_PATHS) {
            JsonElement value = get(stored, path);
            if (value == null || value.isJsonNull())
                putCreating(copy, path, new JsonPrimitive(ClockworkBossSoundDistance.LEGACY_FALLBACK));
        }
        // RC7 and older documents have no source selector: retain their datapack semantics,
        // including when an unrelated field is edited. New documents already declare entries.
        if (get(stored, "loot.source") == null) putCreating(copy, "loot.source", new JsonPrimitive("table"));
        var rewards = get(copy, "loot.entries");
        if (rewards != null && rewards.isJsonObject()) for (var entry : rewards.getAsJsonObject().entrySet()) {
            if (!entry.getValue().isJsonObject()) throw new IllegalArgumentException("Reward settings must be objects");
            merge(entry.getValue().getAsJsonObject(), ClockworkBossLootEntries.newEntry(), "reward");
        }
        // Opening an older file must not silently opt players into new health
        // or potion rules. Virtual disabled values match runtime missing fields.
        for (String section : List.of("player_health", "reusable_potion"))
            if (get(stored, "boss_bar." + section + ".enabled") == null)
                putCreating(copy, "boss_bar." + section + ".enabled", new JsonPrimitive(false));
        var storedPvp = get(stored, "boss_bar.pvp_protection.enabled");
        if (storedPvp == null || storedPvp.isJsonNull())
            putCreating(copy, "boss_bar.pvp_protection.enabled", new JsonPrimitive(false));
        // Geometry is optional on disk. Virtual inputs let admins enter it without changing
        // an unset region merely by opening or saving an unrelated editor page.
        for (String root : List.of("activation", "boss_bar.visibility_area")) {
            for (String corner : List.of("min", "max")) for (String axis : List.of("x", "y", "z"))
                if (get(copy, root + "." + corner + "." + axis) == null)
                    putCreating(copy, root + "." + corner + "." + axis, new JsonPrimitive(0));
        }
        for (String axis : List.of("x", "y", "z")) if (get(copy, "activation.position." + axis) == null)
            putCreating(copy, "activation.position." + axis, new JsonPrimitive(0));
        if (get(copy, "activation.dimension") == null) putCreating(copy, "activation.dimension", new JsonPrimitive(""));
        if (get(copy, "activation.shape") == null) putCreating(copy, "activation.shape", new JsonPrimitive("box"));
        if (copy.has("phases") && copy.get("phases").isJsonObject()) {
            var thresholds = ClockworkBossPhase.Thresholds.read(copy);
            int index = 0;
            for (var phase : copy.getAsJsonObject("phases").entrySet()) {
                JsonObject fallback = newPhase();
                fallback.addProperty("minimum_health_percent", index < thresholds.cutoffs().size() ? thresholds.cutoffs().get(index) : 0);
                if (phase.getValue().isJsonObject()) merge(phase.getValue().getAsJsonObject(), fallback, "phase");
                JsonElement children = phase.getValue().getAsJsonObject().get("subphases");
                if (children != null && children.isJsonObject()) for (var child : children.getAsJsonObject().entrySet()) {
                    if (!child.getValue().isJsonObject()) throw new IllegalArgumentException("Subphase settings must be objects");
                    merge(child.getValue().getAsJsonObject(), newSubphase(), "subphase");
                }
                JsonElement roars = phase.getValue().getAsJsonObject().get("roars");
                if (roars != null && roars.isJsonObject()) for (var roar : roars.getAsJsonObject().entrySet()) {
                    if (!roar.getValue().isJsonObject()) throw new IllegalArgumentException("Roar settings must be objects");
                    merge(roar.getValue().getAsJsonObject(), newRoar(), "roar");
                }
                index++;
            }
        }
        ClockworkBossActionEditor.prepare(copy);
        return copy;
    }

    /** New phases inherit base settings until edited; no existing phase's sound filename is duplicated. */
    public static JsonObject newPhase() {
        JsonObject phase = DEFAULTS.getAsJsonObject("phases").getAsJsonObject("annoyed").deepCopy();
        phase.getAsJsonObject("warden_effects").add("phase_change", DEFAULTS.getAsJsonObject("warden_effects").get("phase_change").deepCopy());
        inherit(phase);
        phase.addProperty("minimum_health_percent", 0);
        phase.addProperty("subphase_order", "sequential");
        phase.addProperty("roar_between_subphases", true);
        return phase;
    }
    private static void inherit(JsonObject object) {
        for (var entry : object.entrySet()) {
            if (entry.getValue().isJsonObject()) inherit(entry.getValue().getAsJsonObject());
            else object.add(entry.getKey(), JsonNull.INSTANCE);
        }
    }

    /** Children expose existing combat controls, never a health/visual/aura stage.
     * Empty sequences disable the feature. New children inherit their parent. */
    public static JsonObject newSubphase() {
        JsonObject child = newPhase();
        child.keySet().removeIf(key -> !List.of("movement_speed", "knockback_resistance", "follow_range", "melee", "sonic_boom", "targeting").contains(key));
        JsonObject ordered = new JsonObject();
        ordered.addProperty("type", "attack");
        ordered.addProperty("duration_ticks", 60);
        ordered.addProperty("attacks_before_next", 3);
        child.entrySet().forEach(entry -> ordered.add(entry.getKey(), entry.getValue()));
        return ordered;
    }
    /** A roar entry overrides only audio, inheriting phase/base roar defaults.
     * Native animation duration/attack pausing do not need duplicate settings. */
    public static JsonObject newRoar() {
        return DEFAULTS.getAsJsonObject("phases").getAsJsonObject("annoyed").getAsJsonObject("roar").deepCopy();
    }

    /** Only these bounded structural operations may add/remove/reorder children.
     * Scalar save patches still cannot insert arbitrary objects or field paths. */
    public static boolean orderField(String key) { return List.of("_phase_order", "_subphase_orders", "_roar_orders", "_loot_entry_order", ClockworkBossActionEditor.TYPES).contains(key); }
    public static void applyOrders(JsonObject root, JsonObject patch) {
        applyRewardOrder(root, patch);
        if (patch.has("_phase_order")) root.add("phases", ordered(root.getAsJsonObject("phases"), patch.get("_phase_order"), "phases"));
        applyChildOrders(root, patch, "subphases", "_subphase_orders");
        applyChildOrders(root, patch, "roars", "_roar_orders");
        ClockworkBossActionEditor.applyTypes(root, patch);
    }
    /** Accept a list of stable reward keys, never a client-supplied arbitrary JSON subtree.
     * Existing entries (including extension fields) are copied intact; new ones use trusted defaults. */
    private static void applyRewardOrder(JsonObject root, JsonObject patch) {
        if (!patch.has("_loot_entry_order")) return;
        var order = patch.get("_loot_entry_order");
        if (!order.isJsonArray() || order.getAsJsonArray().size() > ClockworkBossLootEntries.MAX_ENTRIES)
            throw new IllegalArgumentException("Use 0–32 reward entries");
        var old = get(root, "loot.entries");
        var next = new JsonObject();
        for (var value : order.getAsJsonArray()) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("Invalid reward name");
            String key = value.getAsString(); ClockworkBossLootEntries.validateKey(key);
            if (next.has(key)) throw new IllegalArgumentException("Duplicate reward name");
            next.add(key, old != null && old.isJsonObject() && old.getAsJsonObject().has(key)
                    ? old.getAsJsonObject().get(key).deepCopy() : ClockworkBossLootEntries.newEntry());
        }
        putCreating(root, "loot.entries", next);
    }
    private static void applyChildOrders(JsonObject root, JsonObject patch, String catalog, String operation) {
        if (!patch.has(operation)) return;
        JsonElement raw = patch.get(operation);
        if (!raw.isJsonObject() || raw.getAsJsonObject().size() > ClockworkBossPhase.MAX_PHASES) throw new IllegalArgumentException("Invalid subphase orders");
        for (var entry : raw.getAsJsonObject().entrySet()) {
            JsonObject phases = root.getAsJsonObject("phases");
            if (!phases.has(entry.getKey()) || !phases.get(entry.getKey()).isJsonObject()) throw new IllegalArgumentException("Unknown parent phase");
            JsonObject parent = phases.getAsJsonObject(entry.getKey());
            JsonElement prior = parent.get(catalog);
            parent.add(catalog, ordered(prior != null && prior.isJsonObject() ? prior.getAsJsonObject() : new JsonObject(), entry.getValue(), catalog));
        }
    }
    private static JsonObject ordered(JsonObject previous, JsonElement rawOrder, String catalog) {
        boolean child = !catalog.equals("phases");
        if (!rawOrder.isJsonArray() || rawOrder.getAsJsonArray().size() > 16 || !child && rawOrder.getAsJsonArray().isEmpty())
            throw new IllegalArgumentException(child ? "Use 0–16 subphases" : "Use 1–16 phases");
        JsonObject result = new JsonObject();
        for (var value : rawOrder.getAsJsonArray()) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("Invalid phase name");
            String key = value.getAsString(); new ClockworkBossPhase(key);
            if (result.has(key)) throw new IllegalArgumentException("Duplicate phase name");
            result.add(key, previous.has(key) ? previous.get(key).deepCopy() : catalog.equals("roars") ? newRoar() : child ? newSubphase() : newPhase());
        }
        return result;
    }
    /** Derive a small order patch instead of shipping an unrestricted child tree. */
    public static JsonObject orderPatch(JsonObject before, JsonObject after) {
        JsonObject patch = new JsonObject(), children = new JsonObject(), roars = new JsonObject(), actions = new JsonObject();
        var beforeEntries = get(before, "loot.entries"); var afterEntries = get(after, "loot.entries");
        if (afterEntries != null && afterEntries.isJsonObject()
                && (beforeEntries == null || !beforeEntries.isJsonObject()
                || !beforeEntries.getAsJsonObject().keySet().stream().toList().equals(afterEntries.getAsJsonObject().keySet().stream().toList())))
            patch.add("_loot_entry_order", keys(afterEntries.getAsJsonObject()));
        var oldPhases = before.getAsJsonObject("phases"); var newPhases = after.getAsJsonObject("phases");
        if (!oldPhases.keySet().stream().toList().equals(newPhases.keySet().stream().toList())) patch.add("_phase_order", keys(newPhases));
        for (var entry : newPhases.entrySet()) {
            JsonObject next = entry.getValue().getAsJsonObject();
            var old = oldPhases.has(entry.getKey()) ? oldPhases.getAsJsonObject(entry.getKey()) : new JsonObject();
            var oldChildren = old.has("subphases") && old.get("subphases").isJsonObject() ? old.getAsJsonObject("subphases") : new JsonObject();
            var nextChildren = next.getAsJsonObject("subphases");
            for (var child : nextChildren.entrySet()) {
                String selected = child.getValue().getAsJsonObject().get("type").getAsString();
                JsonObject oldChild = oldChildren.has(child.getKey()) ? oldChildren.getAsJsonObject(child.getKey()) : newSubphase();
                String previous = oldChild.has("type") ? oldChild.get("type").getAsString() : "attack";
                if (!previous.equals(selected)) actions.addProperty("phases." + entry.getKey() + ".subphases." + child.getKey(), selected);
            }
            if (!oldChildren.keySet().stream().toList().equals(nextChildren.keySet().stream().toList())) children.add(entry.getKey(), keys(nextChildren));
            var oldRoars = old.has("roars") && old.get("roars").isJsonObject() ? old.getAsJsonObject("roars") : new JsonObject();
            var nextRoars = next.getAsJsonObject("roars");
            if (!oldRoars.keySet().stream().toList().equals(nextRoars.keySet().stream().toList())) roars.add(entry.getKey(), keys(nextRoars));
        }
        if (!children.isEmpty()) patch.add("_subphase_orders", children);
        if (!roars.isEmpty()) patch.add("_roar_orders", roars);
        if (!actions.isEmpty()) patch.add(ClockworkBossActionEditor.TYPES, actions);
        return patch;
    }
    private static com.google.gson.JsonArray keys(JsonObject object) {
        var keys = new com.google.gson.JsonArray(); object.keySet().forEach(keys::add); return keys;
    }
    public static JsonObject comparison(JsonObject original, JsonObject candidate) {
        JsonObject copy = original.deepCopy(); applyOrders(copy, orderPatch(original, candidate)); return copy;
    }

    private static void merge(JsonObject target, JsonObject defaults, String path) {
        if (path.equals("phases")) return; // Existing phase entries receive inheritable fields below, never preset balance values.
        for (var entry : defaults.entrySet()) {
            String key = entry.getKey();
            if (!target.has(key)) {
                if (!path.equals("phases")) target.add(key, entry.getValue().deepCopy());
            } else if (target.get(key).isJsonNull() && entry.getValue().isJsonObject()) {
                target.add(key, entry.getValue().deepCopy());
            } else if (target.get(key).isJsonObject() && entry.getValue().isJsonObject()) {
                merge(target.getAsJsonObject(key), entry.getValue().getAsJsonObject(), path.isEmpty() ? key : path + "." + key);
            }
        }
    }

    /** Resolve phase overrides against their base field so null values retain the correct input type. */
    private static String basePath(String path) {
        String base = path.replaceFirst("^phases\\.[^.]+\\.roars\\.[^.]+\\.", "attacks.roar.")
                .replaceFirst("^phases\\.[^.]+\\.subphases\\.[^.]+\\.", "phases.child.")
                .replaceFirst("^phases\\.[^.]+\\.", "");
        if (!base.equals(path)) {
            if (base.startsWith("melee.") || base.startsWith("sonic_boom.") || base.startsWith("roar.")) return "attacks." + base;
            if (base.startsWith("darkness.")) return "dormant_" + base;
        }
        return base;
    }

    /** Registered action fields use the server-supplied descriptor for this document. */
    public static Field field(String path, JsonElement current, JsonObject document) {
        Field action = ClockworkBossActionEditor.field(document, path, current);
        if (action != null) return action;
        if (path.contains(".action_settings.")) return new Field(Kind.JSON, List.of(), false, false, 0, 0,
                "Unknown extension field is preserved; install its addon to edit declared settings.");
        return field(path, current);
    }

    /** Metadata follows stable field paths, not the order or number of displayed phases. */
    public static Field field(String path, JsonElement current) {
        if (path.equals(ClockworkBossTemplateFormat.VERSION_FIELD))
            return new Field(Kind.NUMBER, List.of(), false, true,
                    ClockworkBossTemplateFormat.CURRENT_VERSION, ClockworkBossTemplateFormat.CURRENT_VERSION,
                    "Template format revision. Written automatically; do not change it manually.");
        String base = basePath(path);
        boolean reward = path.matches("loot\\.entries\\.[^.]+\\.[^.]+");
        String key = base.substring(base.lastIndexOf('.') + 1);
        JsonElement sample = get(DEFAULTS, base);
        if (sample == null || sample.isJsonNull()) sample = current;
        boolean nullable = path.startsWith("phases.") || path.startsWith("dormant_darkness.") || current.isJsonNull();
        if (base.startsWith("skin.glowing_eyebrows.events.")) nullable = true; // Null uses the documented runtime fallback.
        if (path.matches("phases\\.[^.]+\\.subphases\\.[^.]+\\.(attacks_before_next|duration_ticks|type)")) nullable = false;
        List<String> choices = switch (base) {
            case "loot.vanilla_drops" -> List.of("keep", "replace");
            case "skin.type" -> List.of("player", "default", "custom");
            case "skin.glowing_eyebrows.expression" -> List.of("angry", "neutral", "curious", "sad");
            case "activation.type" -> List.of("player_enter_area", "player_enter_radius", "redstone");
            case "activation.shape" -> List.of("box");
            case "boss_bar.visibility_area.shape" -> List.of("box", "radius");
            case "boss_bar.color" -> List.of("PINK", "BLUE", "RED", "GREEN", "YELLOW", "PURPLE", "WHITE");
            case "boss_bar.overlay" -> List.of("PROGRESS", "NOTCHED_6", "NOTCHED_10", "NOTCHED_12", "NOTCHED_20");
            case "targeting.mode" -> List.of("nearest", "random", "farthest");
            case "music.channel" -> ClockworkBossMusicChannels.NAMES;
            default -> List.of();
        };
        if (key.equals("subphase_order")) choices = List.of("sequential", "random");
        if (path.matches("phases\\.[^.]+\\.subphases\\.[^.]+\\.type")) choices = List.of("attack", "recovery");
        if (base.equals("loot.source")) choices = List.of("entries", "table");
        if (base.startsWith("skin.glowing_eyebrows.events.") && key.equals("expression"))
            choices = List.of("inherit", "angry", "neutral", "curious", "sad", "random");
        if (reward && key.equals("type")) choices = List.of("item");
        Kind kind = sample != null && sample.isJsonPrimitive() ? sample.getAsJsonPrimitive().isBoolean() ? Kind.BOOLEAN
                : sample.getAsJsonPrimitive().isNumber() ? Kind.NUMBER : Kind.TEXT : Kind.JSON;
        // Optional geometry and thresholds are absent from some default documents.
        if (List.of("x", "y", "z", "minimum_health_percent", "attacks_before_next").contains(key)) kind = Kind.NUMBER;
        if (base.startsWith("skin.glowing_eyebrows.") && key.endsWith("_rotation")) {
            kind = Kind.NUMBER;
            if (base.startsWith("skin.glowing_eyebrows.events.")) nullable = true;
        }
        if (key.equals("enabled") || key.equals("flash") || key.equals("requires_line_of_sight") || key.equals("roar_between_subphases")) kind = Kind.BOOLEAN;
        if (!choices.isEmpty() || List.of("file", "name", "dimension", "text", "color").contains(key)) kind = Kind.TEXT;
        if (kind == Kind.BOOLEAN) choices = List.of("true", "false");
        boolean integer = key.endsWith("_ticks") || key.endsWith("_amplifier")
                || List.of("count", "level", "x", "y", "z", "min_attacks", "max_attacks", "attacks_before_next").contains(key);
        if (base.startsWith("skin.glowing_eyes.")) integer = !key.startsWith("scale_");
        if (base.startsWith("skin.glowing_eyebrows.")) integer = kind == Kind.NUMBER;
        double min = -30_000_000, max = 30_000_000;
        switch (key) {
            case "left_x", "left_y", "right_x", "right_y" -> { min = 0; max = 15; }
            case "health" -> { min = 1; max = 1_000_000; }
            case "minimum_max_health" -> { min = 1; max = 1000; }
            case "heal_hp" -> { min = 0; max = 1000; }
            case "cooldown_seconds" -> { min = .05; max = 600; }
            case "hunger_restored", "saturation_restored" -> { min = 0; max = 20; }
            case "duration_ticks" -> { min = 1; max = 12000; }
            case "damage" -> { min = 0; max = 1_000_000; }
            case "movement_speed", "knockback_resistance", "chance", "switch_chance" -> { min = 0; max = 1; }
            case "follow_range", "horizontal_range", "vertical_range", "max_radius" -> { min = 0; max = 256; }
            case "radius" -> { min = 1; max = 256; }
            case "scale" -> { min = 0.25; max = 4; }
            case "scale_x", "scale_y" -> { min = 0.5; max = 4; }
            case "volume" -> { min = 0; max = 4; }
            case "mob_volume" -> { min = 0; max = 1; }
            case "pitch" -> { min = 0.5; max = 2; }
            case "range" -> { min = ClockworkBossSoundDistance.AUTOMATIC; max = ClockworkBossSoundDistance.MAX_RANGE; }
            case "fade_out_seconds" -> { min = 0; max = 10; }
            case "minimum_health_percent" -> { min = 0; max = 100; }
            case "knockback", "knockback_horizontal", "knockback_vertical" -> { min = 0; max = 100; }
            case "count" -> { min = 1; max = 128; }
            case "spread" -> { min = 0; max = 4; }
            case "speed" -> { min = 0; max = 2; }
            case "size" -> { min = 0.1; max = 4; }
            case "level" -> { min = 1; max = 5; }
            case "edge_amplifier", "center_amplifier" -> { min = 0; max = 4; }
            case "charge_time_ticks" -> { min = 0; max = base.startsWith("attacks.melee.") ? 12000 : 1_000_000; }
            case "recovery_time_ticks" -> { min = 0; max = 12000; }
            case "cooldown_ticks" -> { min = base.contains("announcement") ? 20 : 1; max = base.contains("announcement") ? 1200 : 1_000_000; }
            case "display_ticks" -> { min = 1; max = 100; }
            case "min_target_ticks" -> { min = 0; max = 12000; }
            case "min_attacks", "max_attacks", "attacks_before_next" -> { min = 1; max = 1000; }
            case "edge_interval_ticks", "center_interval_ticks", "edge_duration_ticks", "center_duration_ticks" -> { min = 1; max = 12000; }
            default -> { }
        }
        String help = ClockworkBossTemplateDocument.description(path);
        if (key.equals("hunger_restored")) integer = true;
        if (base.startsWith("skin.glowing_eyebrows.") && kind == Kind.NUMBER) {
            if (key.endsWith("_rotation")) { min = ClockworkBossBrows.MIN_ROTATION; max = ClockworkBossBrows.MAX_ROTATION; }
            else if (key.endsWith("_ticks")) { min = key.endsWith("cooldown_ticks") ? 0 : 1; max = 12000; }
            else { min = 0; max = 12; }
        }
        if (base.startsWith("skin.glowing_pupils.") && kind == Kind.NUMBER) {
            integer = !key.startsWith("scale_");
            min = integer ? -1 : 0.5; max = integer ? 7 : 8;
        }
        if (help.isEmpty()) help = ClockworkBossTemplateDocument.description(base);
        if (reward) {
            nullable = false;
            if (key.equals("min_count") || key.equals("max_count")) { kind = Kind.NUMBER; integer = true; min = 1; max = ClockworkBossLootEntries.MAX_COUNT; }
            if (key.equals("chance")) { kind = Kind.NUMBER; integer = false; min = 0; max = 1; }
            if (key.equals("item") || key.equals("type")) kind = Kind.TEXT;
        }
        return new Field(kind, choices, nullable, integer, min, max, help);
    }

    /** Parse a draft without permissive numeric coercion; a blank inheritable input means JSON null. */
    public static JsonElement parse(String path, String text, JsonElement original) {
        return parse(path, text, original, null);
    }
    public static JsonElement parse(String path, String text, JsonElement original, JsonObject document) {
        Field field = document == null ? field(path, original) : field(path, original, document);
        var setting = document == null ? null : ClockworkBossActionEditor.setting(document, path);
        if (setting != null) {
            // API null means this setting's declared default, not a parent
            // phase's action_settings. Match the schema and runtime compiler.
            if (text.equals("<inherit>") || setting.kind() != clockwork.bosses.api.BossSetting.Kind.TEXT && text.isBlank())
                return JsonNull.INSTANCE;
            JsonPrimitive scalar = switch (setting.kind()) {
                case TEXT -> new JsonPrimitive(text);
                case BOOLEAN -> {
                    if (!text.equals("true") && !text.equals("false")) throw new IllegalArgumentException("Choose true or false");
                    yield new JsonPrimitive(Boolean.parseBoolean(text));
                }
                case NUMBER -> {
                    try { yield new JsonPrimitive(ClockworkBossNumbers.decimal(text)); }
                    catch (NumberFormatException invalid) { throw new IllegalArgumentException(field.range()); }
                }
            };
            setting.parse(scalar);
            if (setting.kind() == clockwork.bosses.api.BossSetting.Kind.TEXT)
                ClockworkBossActionEditor.validateText(document, path, text);
            return scalar;
        }
        if (field.nullable && text.equals("<inherit>")) return JsonNull.INSTANCE;
        if (field.nullable && field.kind != Kind.TEXT && text.isBlank()) return JsonNull.INSTANCE;
        if (!field.choices.isEmpty() && !field.choices.contains(text)) throw new IllegalArgumentException("Choose one of " + String.join(", ", field.choices));
        return switch (field.kind) {
            case BOOLEAN -> new JsonPrimitive(Boolean.parseBoolean(text));
            case NUMBER -> {
                BigDecimal value;
                try { value = ClockworkBossNumbers.decimal(text); }
                catch (NumberFormatException invalid) { throw new IllegalArgumentException(field.range()); }
                // Compare exact decimals: double rounding must not accept a value just outside a limit.
                if (value.compareTo(BigDecimal.valueOf(field.min)) < 0 || value.compareTo(BigDecimal.valueOf(field.max)) > 0
                        || (field.integer && value.stripTrailingZeros().scale() > 0)) throw new IllegalArgumentException(field.range());
                yield new JsonPrimitive(value);
            }
            case TEXT -> {
                ClockworkBossTemplateText.rule(path).validate(text);
                if (path.equals("loot.loot_table") && !text.isEmpty()) ClockworkBossLoot.parseTable(text);
                if (path.matches("loot\\.entries\\.[^.]+\\.item")) ClockworkBossLootEntries.parseItem(text);
                yield new JsonPrimitive(text);
            }
            // Unknown arrays/structures remain intact; the UI does not expose an arbitrary JSON editor.
            case JSON -> original.deepCopy();
        };
    }

    /** Validate scalar edits while disallowing hidden field additions/removals and changed object structure. */
    public static void validate(JsonObject expected, JsonObject candidate) {
        ClockworkBossTemplateFormat.version(candidate);
        compare(expected, candidate, "", 0, new int[]{0}, candidate);
        ClockworkBossActionEditor.validate(candidate);
        ClockworkBossLoot.read(candidate); // Cross-field quantity limits apply even if each scalar is in range.
        validateAttackLimits(candidate, "targeting");
        JsonElement phases = candidate.get("phases");
        if (phases != null && phases.isJsonObject()) {
            double previous = 100;
            var keys = phases.getAsJsonObject().keySet().stream().toList();
            if (keys.isEmpty() || keys.size() > ClockworkBossPhase.MAX_PHASES) throw new IllegalArgumentException("Use 1–16 phases");
            for (String key : keys) {
                new ClockworkBossPhase(key);
                validateAttackLimits(candidate, "phases." + key + ".targeting");
                JsonObject children = candidate.getAsJsonObject("phases").getAsJsonObject(key).getAsJsonObject("subphases");
                if (children.size() > 16) throw new IllegalArgumentException("Use 0–16 subphases");
                for (String child : children.keySet()) {
                    new ClockworkBossPhase(child);
                    validateAttackLimits(candidate, "phases." + key + ".subphases." + child + ".targeting");
                }
                JsonObject roars = candidate.getAsJsonObject("phases").getAsJsonObject(key).getAsJsonObject("roars");
                if (roars.size() > 16) throw new IllegalArgumentException("Use 0–16 roars");
                for (String roar : roars.keySet()) new ClockworkBossPhase(roar);
            }
            for (int index = 0; index < keys.size() - 1; index++) {
                String key = keys.get(index);
                new ClockworkBossPhase(key);
                JsonElement value = get(candidate, "phases." + key + ".minimum_health_percent");
                if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("Set a leaving health cutoff for " + key);
                double cutoff = value.getAsDouble();
                if (!Double.isFinite(cutoff) || cutoff < 0 || cutoff > previous) throw new IllegalArgumentException("Phase cutoffs must descend from first to last (0–100)");
                previous = cutoff;
            }
        }
    }

    /** Common labels for the in-game editor and exported schema. Special page
     * titles can still describe a field in context without duplicating its limits. */
    public static String label(String path) {
        if (path.equals("boss_bar.pvp_protection")) return "PvP protection";
        String[] words = path.substring(path.lastIndexOf('.') + 1).replace('_', ' ').replace('-', ' ').split(" ");
        StringBuilder result = new StringBuilder();
        for (String word : words) if (!word.isEmpty()) {
            if (!result.isEmpty()) result.append(' ');
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return result.toString();
    }

    /** Validate inherited attack limits together, so a valid scalar edit cannot
     * silently create a maximum below the minimum in another phase. */
    private static void validateAttackLimits(JsonObject config, String path) {
        int minimum = inheritedAttackLimit(config, path, "min_attacks", 1);
        int maximum = inheritedAttackLimit(config, path, "max_attacks", 4);
        if (maximum < minimum) throw new IllegalArgumentException(path + ": Max attacks must be at least Min attacks");
    }
    private static int inheritedAttackLimit(JsonObject config, String path, String key, int fallback) {
        JsonElement value = get(config, path + "." + key);
        if ((value == null || value.isJsonNull()) && path.matches("phases\\.[^.]+\\.subphases\\.[^.]+\\.targeting"))
            value = get(config, path.replaceFirst("^(phases\\.[^.]+)\\.subphases\\.[^.]+", "$1") + "." + key);
        if (value == null || value.isJsonNull()) value = get(config, "targeting." + key);
        return value == null || value.isJsonNull() ? fallback : value.getAsInt();
    }

    private static void compare(JsonElement expected, JsonElement candidate, String path, int depth, int[] count, JsonObject document) {
        if (path.equals(ClockworkBossActionEditor.CATALOG)) {
            if (!expected.equals(candidate)) throw new IllegalArgumentException("Action catalog is read-only");
            return;
        }
        if (depth > 24 || ++count[0] > 4096) throw new IllegalArgumentException("Document is too complex for this editor");
        if (expected.isJsonObject()) {
            if (!candidate.isJsonObject() || !expected.getAsJsonObject().keySet().equals(candidate.getAsJsonObject().keySet())) throw new IllegalArgumentException("Document structure changed; reopen the editor");
            for (var entry : expected.getAsJsonObject().entrySet()) compare(entry.getValue(), candidate.getAsJsonObject().get(entry.getKey()), path.isEmpty() ? entry.getKey() : path + "." + entry.getKey(), depth + 1, count, document);
        } else if (!expected.equals(candidate)) {
            if (candidate.isJsonNull()) {
                if (!field(path, expected, document).nullable) throw new IllegalArgumentException(path + ": this value cannot inherit");
            } else {
                Field field = field(path, expected, document);
                if (!candidate.isJsonPrimitive() || field.kind == Kind.JSON) throw new IllegalArgumentException(path + ": unsupported change");
                if (field.kind == Kind.NUMBER && !candidate.getAsJsonPrimitive().isNumber()
                        || field.kind == Kind.BOOLEAN && !candidate.getAsJsonPrimitive().isBoolean()
                        || field.kind == Kind.TEXT && !candidate.getAsJsonPrimitive().isString()) throw new IllegalArgumentException(path + ": wrong value type");
                try { parse(path, candidate.getAsString(), expected, document); }
                catch (IllegalArgumentException invalid) { throw new IllegalArgumentException(path + ": " + invalid.getMessage()); }
            }
        }
    }

    public static JsonElement get(JsonObject root, String path) {
        JsonElement value = root;
        for (String key : path.split("\\.")) {
            if (!value.isJsonObject()) return null;
            value = value.getAsJsonObject().get(key);
            if (value == null) return null;
        }
        return value;
    }

    public static void put(JsonObject root, String path, JsonElement value) {
        String[] keys = path.split("\\.");
        JsonObject object = root;
        for (int index = 0; index < keys.length - 1; index++) object = object.getAsJsonObject(keys[index]);
        object.add(keys[keys.length - 1], value);
    }

    /** Materialize only an explicitly edited setting; missing untouched fields keep their gameplay inheritance. */
    public static void putCreating(JsonObject root, String path, JsonElement value) {
        String[] keys = path.split("\\.");
        JsonObject object = root;
        for (int index = 0; index < keys.length - 1; index++) {
            if (!object.has(keys[index]) || !object.get(keys[index]).isJsonObject()) object.add(keys[index], new JsonObject());
            object = object.getAsJsonObject(keys[index]);
        }
        object.add(keys[keys.length - 1], value.deepCopy());
    }

    public static String value(JsonElement value) { return value.isJsonNull() ? "<inherit>" : value.isJsonPrimitive() ? value.getAsString() : value.toString(); }
    private static String number(double value) { return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString(); }
}
