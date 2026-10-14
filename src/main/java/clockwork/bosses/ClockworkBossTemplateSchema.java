package clockwork.bosses;

import clockwork.bosses.api.BossActionRegistry;
import clockwork.bosses.api.BossSetting;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Export adapter, not a second settings catalog. Core fields come from the
 * shared editor rules/defaults; addon fields come from their registered settings.
 * The result is ordinary JSON Schema plus x-clockwork editor annotations.
 * Generating it needs the build classpath, but never launches a game/server. */
public final class ClockworkBossTemplateSchema {
    public static final int SCHEMA_VERSION = 2;
    private static final String PHASE_PATH = "phases.<phase>", CHILD_PATH = PHASE_PATH + ".subphases.<subphase>";
    private static final String PHASE_KEY = "^[a-z0-9][a-z0-9_-]{0,31}$";
    // Private, never mutated: repeated field lookups reuse the form prototypes
    // rather than copying a complete phase for every exported scalar.
    private static final JsonObject PHASE_PROTOTYPE = ClockworkBossEditorSchema.newPhase();
    private static final JsonObject CHILD_PROTOTYPE = ClockworkBossEditorSchema.newSubphase();
    private ClockworkBossTemplateSchema() {}

    /** Export the current startup registry snapshot. A server export includes
     * installed addons; the standalone Gradle task includes core actions only. */
    public static JsonObject create() {
        JsonObject defaults = ClockworkBossTemplateDocument.defaults();
        JsonObject form = ClockworkBossEditorSchema.editable(defaults);
        ClockworkBossTemplateFormat.ROOT_METADATA.forEach(form::remove);
        JsonObject schema = object(form, "");
        schema.addProperty("$schema", "https://json-schema.org/draft/2020-12/schema");
        schema.addProperty("title", "Clockwork Boss template");
        schema.addProperty("description", "Experimental authoring schema. Preserve unknown fields and insertion order; the server validates gameplay and registry references.");
        schema.add("default", defaults);

        JsonObject definitions = new JsonObject();
        definitions.add("phase", object(PHASE_PROTOTYPE, PHASE_PATH));
        JsonObject child = object(CHILD_PROTOTYPE, CHILD_PATH);
        child.getAsJsonObject("properties").add("action_settings", object(new JsonObject(), CHILD_PATH + ".action_settings"));
        // A subphase cannot turn into a second recursive health-phase hierarchy.
        JsonObject forbidden = new JsonObject(); JsonArray alternatives = new JsonArray();
        for (String key : List.of("minimum_health_percent", "subphases")) {
            JsonObject alternative = new JsonObject(); alternative.add("required", strings(key)); alternatives.add(alternative);
        }
        forbidden.add("anyOf", alternatives); child.add("not", forbidden);
        JsonObject actions = BossActionRegistry.descriptors();
        addActions(child, actions);
        definitions.add("subphase", child);
        definitions.add("roar", object(ClockworkBossEditorSchema.newRoar(), PHASE_PATH + ".roars.<roar>"));
        JsonObject reward = object(ClockworkBossLootEntries.newEntry(), "loot.entries.<entry>");
        reward.add("required", strings("type", "item", "min_count", "max_count", "chance"));
        definitions.add("loot_entry", reward);
        schema.add("$defs", definitions);
        JsonObject version = schema.getAsJsonObject("properties").getAsJsonObject(ClockworkBossTemplateFormat.VERSION_FIELD);
        version.addProperty("const", ClockworkBossTemplateFormat.CURRENT_VERSION);
        version.addProperty("readOnly", true); // Omission is still valid for legacy documents.

        JsonObject metadata = new JsonObject();
        metadata.addProperty("schema_version", SCHEMA_VERSION);
        metadata.addProperty("template_format_version", ClockworkBossTemplateFormat.CURRENT_VERSION);
        metadata.addProperty("status", "experimental");
        metadata.addProperty("max_utf8_bytes", ClockworkBossTemplateFormat.MAX_BYTES);
        metadata.addProperty("max_nesting", ClockworkBossTemplateFormat.MAX_NESTING);
        metadata.addProperty("unknown_fields", "preserve");
        metadata.addProperty("unknown_actions", "preserve data; the server requires their addon before running the encounter");
        metadata.addProperty("order", "Preserve object member order, especially phases, subphases, roars and loot entries. Do not sort keys.");
        metadata.addProperty("number_handling", "Preserve unedited number tokens exactly; JavaScript Number can round large addon values.");
        JsonObject numeric = new JsonObject();
        numeric.addProperty("max_text_length", ClockworkBossNumbers.MAX_TEXT);
        numeric.addProperty("max_precision", ClockworkBossNumbers.MAX_PRECISION);
        numeric.addProperty("min_scale", -ClockworkBossNumbers.MAX_SCALE);
        numeric.addProperty("max_scale", ClockworkBossNumbers.MAX_SCALE);
        numeric.addProperty("applies_to", "Declared numeric edits only; preserve opaque imported number tokens unchanged.");
        metadata.add("numeric_input", numeric);
        metadata.addProperty("defaults", "Creation/form defaults are annotations, not instructions to populate missing fields during import/export.");
        metadata.addProperty("omission", "Read each field's omitted annotation separately from default. Preserve missing fields on import; server_default means no portable literal fallback is promised.");
        metadata.addProperty("visibility", "visible_when is presentation only. Resolve selector paths in the current phase/subphase; preserve hidden values on load/save.");
        metadata.add("reserved_root_fields", strings(ClockworkBossTemplateFormat.ROOT_METADATA.stream().sorted().toArray(String[]::new)));
        metadata.add("actions", actions);
        JsonObject aliases = new JsonObject(); aliases.addProperty("attack", "clockwork_bosses:combat"); aliases.addProperty("recovery", "clockwork_bosses:recovery");
        metadata.add("legacy_action_aliases", aliases);
        metadata.add("section_order", strings("skin", "base", "sounds", "dormant_darkness", "activation", "boss_bar", "phases", "loot"));
        // These rules cannot be expressed by simple JSON Schema scalar bounds.
        // A browser can report them; the server remains the final authority.
        metadata.add("runtime_checks", strings("Non-final phase cutoffs descend; the last cutoff is ignored.",
                "Inherited targeting min_attacks must not exceed max_attacks.", "Reward min_count must not exceed max_count; item and loot-table references must exist.",
                "A table-source reward needs a nonempty loot_table when enabled.", "Geometry must fit the actual dimension; box corners must describe a usable area.",
                "Media files must pass server PNG/OGG validation, not just filename validation.", "Missing addon actions cannot run; action_settings must be an object when present."));
        metadata.add("server_only_checks", strings("Installed action addons and mob capabilities.",
                "Actual media files, bounded PNG/OGG contents and filesystem ownership.",
                "Installed item/loot-table registries and datapack context requirements.", "Actual dimension existence, build height and world geometry."));
        schema.add("x-clockwork", metadata);
        return schema;
    }

    /** Deterministic UTF-8 JSON contains no machine paths, live templates, media
     * bytes or executable addon code. Descriptions are data, never browser HTML. */
    public static String write() {
        return new GsonBuilder().serializeNulls().disableHtmlEscaping().setPrettyPrinting().create().toJson(create()) + "\n";
    }

    /** Build tooling entry point. Only the explicitly supplied build output is
     * written; it never initializes the mod or reads server configuration. */
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Supply one schema output file");
        Path output = Path.of(args[0]).toAbsolutePath().normalize();
        Files.createDirectories(output.getParent());
        Files.writeString(output, write(), StandardCharsets.UTF_8);
        System.out.println("Exported Clockwork Boss template schema: " + output.getFileName());
    }

    /** Walk the existing form skeleton; named encounter lists use reusable
     * definitions instead of hardcoding four phases or a fixed child count. */
    private static JsonObject object(JsonObject sample, String path) {
        JsonObject result = new JsonObject(); result.addProperty("type", "object");
        if ((path.startsWith("phases.") && !path.equals(PHASE_PATH) && !path.equals(CHILD_PATH)
                && !path.equals(PHASE_PATH + ".roars.<roar>")
                && !path.endsWith(".action_settings")) || path.equals("dormant_darkness") || path.equals("loot") || path.equals("boss_bar.pvp_protection")
                || path.equals("skin.glowing_eyebrows.events") || path.startsWith("skin.glowing_eyebrows.events."))
            result.add("type", strings("object", "null"));
        result.addProperty("additionalProperties", true); // Addon data is never rejected/pruned merely for being unknown.
        JsonObject properties = new JsonObject();
        for (var entry : sample.entrySet()) {
            String childPath = path.isEmpty() ? entry.getKey() : path + "." + entry.getKey();
            JsonObject definition;
            if (childPath.equals("phases")) definition = catalog("phase", PHASE_KEY, 1, ClockworkBossPhase.MAX_PHASES, false);
            else if (childPath.equals(PHASE_PATH + ".subphases")) definition = catalog("subphase", PHASE_KEY, 0, ClockworkBossSubphases.MAX_SUBPHASES, true);
            else if (childPath.equals(PHASE_PATH + ".roars")) definition = catalog("roar", PHASE_KEY, 0, ClockworkBossSubphases.MAX_SUBPHASES, true);
            else if (childPath.equals("loot.entries")) definition = catalog("loot_entry", "^[a-z0-9_-]{1,48}$", 0, ClockworkBossLootEntries.MAX_ENTRIES, false);
            else if (entry.getValue().isJsonObject()) definition = object(entry.getValue().getAsJsonObject(), childPath);
            else definition = scalar(childPath, entry.getValue());
            definition.addProperty("title", ClockworkBossEditorSchema.label(childPath));
            definition.add("default", entry.getValue().deepCopy());
            String description = ClockworkBossTemplateDocument.description(childPath);
            if (!description.isEmpty()) definition.addProperty("description", description);
            annotate(definition, childPath);
            properties.add(entry.getKey(), definition);
        }
        result.add("properties", properties);
        if (!path.isEmpty()) result.addProperty("description", ClockworkBossTemplateDocument.description(path));
        return result;
    }

    private static JsonObject catalog(String definition, String keyPattern, int minimum, int maximum, boolean nullable) {
        JsonObject result = new JsonObject();
        if (nullable) result.add("type", strings("object", "null")); else result.addProperty("type", "object");
        result.addProperty("minProperties", minimum); result.addProperty("maxProperties", maximum);
        JsonObject names = new JsonObject(); names.addProperty("pattern", keyPattern); result.add("propertyNames", names);
        JsonObject reference = new JsonObject(); reference.addProperty("$ref", "#/$defs/" + definition); result.add("additionalProperties", reference);
        JsonObject metadata = new JsonObject(); metadata.addProperty("ordered", true); result.add("x-clockwork", metadata);
        return result;
    }

    /** Bounds, options and help come from the same Field used by both ends of
     * the in-game editor. Null overrides remain null in the exported defaults. */
    private static JsonObject scalar(String path, JsonElement sample) {
        var field = ClockworkBossEditorSchema.field(path, sample);
        JsonObject result = constraints(field.kind().name(), field.integer(), field.min(), field.max(),
                field.nullable() || path.equals("boss_bar.pvp_protection.enabled"));
        if (field.kind() == ClockworkBossEditorSchema.Kind.TEXT) {
            var rule = ClockworkBossTemplateText.rule(path);
            result.addProperty("maxLength", rule.maxLength());
            if (!rule.pattern().isEmpty()) result.addProperty("pattern", "^(?:" + (rule.allowEmpty() ? "|" : "") + rule.pattern() + ")$");
            if (path.matches("loot\\.entries\\.[^.]+\\.item")) {
                JsonObject air = new JsonObject(); air.addProperty("const", "minecraft:air"); result.add("not", air);
            }
        }
        if (!field.choices().isEmpty() && field.kind() == ClockworkBossEditorSchema.Kind.TEXT
                && !path.equals(CHILD_PATH + ".type")) {
            JsonArray choices = strings(field.choices().toArray(String[]::new));
            if (field.nullable()) choices.add(JsonNull.INSTANCE); result.add("enum", choices);
        }
        if (!field.help().isEmpty()) result.addProperty("description", field.help());
        JsonObject metadata = new JsonObject(); metadata.addProperty("path", path);
        metadata.addProperty("scope", path.startsWith(CHILD_PATH + ".") ? "subphase" : path.startsWith("phases.") ? "phase" : "base");
        String parent = inheritsFrom(path);
        if (!parent.isEmpty()) metadata.addProperty("inherits_from", parent);
        if (path.startsWith("dormant_darkness.")) metadata.addProperty("null_behavior", "Inherit the first phase's compiled darkness field.");
        if (path.startsWith(PHASE_PATH + ".darkness.")) metadata.addProperty("null_behavior", "Inherit legacy base darkness, otherwise the compiled darkness fallback; not dormant_darkness.");
        if (field.kind() == ClockworkBossEditorSchema.Kind.JSON) metadata.addProperty("opaque", true);
        if (path.endsWith(".switch_chance") || path.equals("voice_lines.chance") || path.endsWith(".chance")) metadata.addProperty("display_multiplier", 100);
        // A website can discover which base fields may be overridden without
        // maintaining another list of allowed phase/subphase settings.
        if (!path.startsWith("phases.") && !path.startsWith("dormant_darkness.")) {
            String phaseField = path.startsWith("attacks.") ? path.substring(8) : path;
            if (ClockworkBossEditorSchema.get(PHASE_PROTOTYPE, phaseField) != null)
                metadata.addProperty("phase_override", PHASE_PATH + "." + phaseField);
            if (ClockworkBossEditorSchema.get(CHILD_PROTOTYPE, phaseField) != null)
                metadata.addProperty("subphase_override", CHILD_PATH + "." + phaseField);
        }
        result.add("x-clockwork", metadata);
        return result;
    }

    /** Creation defaults must never substitute for an absent imported field.
     * Export concrete omission values only where a runtime rule is shared or
     * compiled here. Remaining server defaults are labeled explicitly rather
     * than guessing that they match the creation file. No template is modified. */
    private static void annotate(JsonObject definition, String path) {
        JsonObject metadata = definition.has("x-clockwork") ? definition.getAsJsonObject("x-clockwork") : new JsonObject();
        metadata.addProperty("default_role", "creation");
        JsonObject omitted = new JsonObject(); String parent = inheritsFrom(path);
        boolean spatialRange = path.endsWith(".range") && (path.startsWith("sounds.") || path.equals("voice_lines.range")
                || path.equals("attacks.roar.sound.range")
                || path.matches("phases\\.[^.]+\\.(sounds\\.phase_change|roar\\.sound|roars\\.[^.]+\\.sound)\\.range"));
        if (spatialRange) metadata.add("automatic", automaticSoundDistance());
        if (path.startsWith("skin.glowing_eyebrows.events.") && path.endsWith("_rotation")) {
            omitted.addProperty("kind", "inherit");
            omitted.addProperty("from", "skin.glowing_eyebrows." + path.substring(path.lastIndexOf('.') + 1));
            metadata.addProperty("null_behavior", "Keep the base eyebrow rotation.");
        } else if (path.equals("skin.glowing_pupils") || path.startsWith("skin.glowing_pupils.")
                || path.equals("skin.glowing_eyebrows") || path.startsWith("skin.glowing_eyebrows.")) {
            // Optional face settings use the same legacy fallback as their
            // creation values. Pupils additionally retain their Auto sentinel.
            omitted.addProperty("kind", "value"); omitted.add("value", definition.get("default").deepCopy());
        } else if (spatialRange && parent.isEmpty()) {
            // New templates choose Auto; omitted legacy base/voice fields still mean 32.
            omitted.addProperty("kind", "value"); omitted.addProperty("value", ClockworkBossSoundDistance.LEGACY_FALLBACK);
        } else if (path.equals("loot")) {
            omitted.addProperty("kind", "disabled"); omitted.addProperty("description", "Disable template rewards and keep vanilla drops; null has the same meaning.");
        } else if (path.equals("loot.source")) {
            omitted.addProperty("kind", "value"); omitted.addProperty("value", ClockworkBossLoot.DEFAULT_SOURCE);
        } else if (path.equals("boss_bar.pvp_protection")) {
            omitted.addProperty("kind", "disabled"); omitted.addProperty("description", "Absent/null section preserves ordinary PvP; creation defaults enable protection.");
        } else if (path.equals("boss_bar.pvp_protection.enabled")) {
            omitted.addProperty("kind", "value"); omitted.addProperty("value", false);
            metadata.addProperty("null_behavior", "Disable PvP protection; does not inherit a phase.");
            metadata.addProperty("null_label", "Disabled / null");
        } else if (path.equals("boss_bar.player_health") || path.equals("boss_bar.reusable_potion")) {
            omitted.addProperty("kind", "disabled"); omitted.addProperty("description", "Absent sections grant no player support, even though new templates enable it.");
        } else if (path.equals("boss_bar.player_health.enabled") || path.equals("boss_bar.reusable_potion.enabled")) {
            var support = ClockworkBossPlayerSupport.read(new JsonObject());
            omitted.addProperty("kind", "value"); omitted.addProperty("value", path.contains("player_health") ? support.health().enabled() : support.potion().enabled());
        } else if (path.equals(PHASE_PATH + ".subphase_order")) {
            omitted.addProperty("kind", "value"); omitted.addProperty("value", ClockworkBossSubphases.DEFAULT_ORDER.name().toLowerCase(java.util.Locale.ROOT));
            metadata.addProperty("null_behavior", "Use sequential order; does not inherit a parent.");
        } else if (path.equals(PHASE_PATH + ".roar_between_subphases")) {
            omitted.addProperty("kind", "value"); omitted.addProperty("value", ClockworkBossSubphases.DEFAULT_ROAR_BETWEEN);
            metadata.addProperty("null_behavior", "Enable interlude roars when a roar list exists; does not inherit a parent.");
        } else if (path.equals("dormant_darkness")) {
            omitted.addProperty("kind", "inherit"); omitted.addProperty("from", "current health-selected phase darkness");
            omitted.addProperty("description", "Absent/null preserves the legacy underground aura; a present object inherits missing fields from phase 1.");
        } else if (path.startsWith("dormant_darkness.")) {
            omitted.addProperty("kind", "inherit"); omitted.addProperty("from", "first phase's compiled darkness." + path.substring("dormant_darkness.".length()));
        } else if (path.endsWith(".subphases") || path.endsWith(".roars")) {
            omitted.addProperty("kind", "empty"); omitted.add("value", new JsonObject());
        } else if (path.equals(CHILD_PATH + ".action_settings")) {
            omitted.addProperty("kind", "declared_action_defaults");
            omitted.addProperty("description", "Omitted object uses the selected action's defaults; explicit null is invalid. Legacy aliases ignore this object.");
        } else if (!parent.isEmpty()) {
            omitted.addProperty("kind", "inherit"); omitted.addProperty("from", parent);
        } else if (path.equals(CHILD_PATH + ".type")) {
            omitted.addProperty("kind", "value"); omitted.addProperty("value", "attack");
        } else if (path.equals(ClockworkBossTemplateFormat.VERSION_FIELD)) {
            omitted.addProperty("kind", "value"); omitted.addProperty("value", ClockworkBossTemplateFormat.CURRENT_VERSION);
        } else {
            omitted.addProperty("kind", "server_default"); omitted.addProperty("description", "Preserve omission; default is for creating a new template, not for filling an imported one.");
        }
        metadata.add("omitted", omitted);
        var visibility = ClockworkBossTemplateVisibility.rule(path);
        if (visibility != null) metadata.add("visible_when", visibility.descriptor());
        definition.add("x-clockwork", metadata);
    }

    /** Inheritance is a relationship, not a copied value. Registered action
     * settings have their own defaults and do not inherit a parent's action object. */
    private static String inheritsFrom(String path) {
        if (path.startsWith("skin.glowing_eyebrows.events.") && path.endsWith("_rotation"))
            return "skin.glowing_eyebrows." + path.substring(path.lastIndexOf('.') + 1);
        if (path.startsWith(CHILD_PATH + ".")) {
            String field = path.substring(CHILD_PATH.length() + 1);
            return List.of("type", "duration_ticks", "attacks_before_next").contains(field) ? "" : PHASE_PATH + "." + field;
        }
        String roar = PHASE_PATH + ".roars.<roar>.";
        if (path.startsWith(roar)) return PHASE_PATH + ".roar." + path.substring(roar.length());
        if (path.startsWith(PHASE_PATH + ".")) {
            String field = path.substring(PHASE_PATH.length() + 1);
            if (List.of("minimum_health_percent", "subphase_order", "roar_between_subphases").contains(field)) return "";
            return field.startsWith("melee.") || field.startsWith("sonic_boom.") || field.startsWith("roar.") ? "attacks." + field : field;
        }
        return "";
    }

    /** Adapt declared addon fields, retaining their exact defaults/ranges and
     * accepting missing/null scalar values as the API already does at runtime. */
    private static void addActions(JsonObject child, JsonObject catalog) {
        JsonArray conditions = new JsonArray();
        JsonArray choices = strings("attack", "recovery"); catalog.keySet().forEach(choices::add);
        child.getAsJsonObject("properties").getAsJsonObject("type").getAsJsonObject("x-clockwork").add("choices", choices);
        for (var action : catalog.entrySet()) {
            JsonObject settings = object(new JsonObject(), CHILD_PATH + ".action_settings");
            annotate(settings, CHILD_PATH + ".action_settings");
            JsonObject properties = settings.getAsJsonObject("properties");
            for (var entry : action.getValue().getAsJsonObject().getAsJsonObject("settings").entrySet()) {
                BossSetting field = BossSetting.fromDescriptor(entry.getKey(), entry.getValue().getAsJsonObject());
                JsonObject definition = constraints(field.kind().name(), field.integer(), field.min(), field.max(), true);
                definition.addProperty("title", field.label()); definition.addProperty("description", field.help());
                definition.add("default", field.defaultValue().deepCopy());
                if (field.kind() == BossSetting.Kind.TEXT) {
                    definition.addProperty("maxLength", field.maxLength());
                    var rule = ClockworkBossActionEditor.textRule(action.getKey(), entry.getKey());
                    if (rule != null) {
                        definition.addProperty("maxLength", Math.min(field.maxLength(), rule.maxLength()));
                        definition.addProperty("pattern", "^(?:" + (rule.allowEmpty() ? "|" : "") + rule.pattern() + ")$");
                    }
                    if (!field.choices().isEmpty()) { JsonArray options = strings(field.choices().toArray(String[]::new)); options.add(JsonNull.INSTANCE); definition.add("enum", options); }
                }
                JsonObject metadata = new JsonObject(); metadata.addProperty("null_behavior", "Use this action setting's declared default.");
                metadata.addProperty("default_role", "creation_and_omission");
                JsonObject omitted = new JsonObject(); omitted.addProperty("kind", "value"); omitted.add("value", field.defaultValue().deepCopy()); metadata.add("omitted", omitted);
                if (action.getKey().equals(ClockworkBossActions.ROAR.toString()) && entry.getKey().equals("range"))
                    metadata.add("automatic", automaticSoundDistance());
                definition.add("x-clockwork", metadata); properties.add(entry.getKey(), definition);
            }
            JsonObject condition = new JsonObject(), ifPart = new JsonObject(), type = new JsonObject(), ifProperties = new JsonObject();
            type.addProperty("const", action.getKey()); ifProperties.add("type", type); ifPart.add("properties", ifProperties); ifPart.add("required", strings("type"));
            condition.add("if", ifPart);
            JsonObject then = new JsonObject(), thenProperties = new JsonObject(); thenProperties.add("action_settings", settings); then.add("properties", thenProperties);
            condition.add("then", then); conditions.add(condition);
        }
        child.add("allOf", conditions);
    }

    /** Portable Auto contract; world/dimension checks and the final float conversion remain server-side. */
    private static JsonObject automaticSoundDistance() {
        JsonObject rule = new JsonObject();
        rule.addProperty("value", ClockworkBossSoundDistance.AUTOMATIC);
        rule.addProperty("arena", "boss_bar.visibility_area");
        rule.addProperty("radius", "Use the encounter radius, not its diameter.");
        rule.addProperty("box", "Full 3D diagonal using inclusive dimensions max - min + 1.");
        rule.addProperty("fallback", ClockworkBossSoundDistance.LEGACY_FALLBACK);
        rule.addProperty("maximum", ClockworkBossSoundDistance.MAX_RANGE);
        rule.addProperty("resolution", "Resolve from compiled arena geometry when the sound triggers, before packets; no activation geometry or tick work.");
        return rule;
    }

    private static JsonObject constraints(String kind, boolean integer, double min, double max, boolean nullable) {
        JsonObject result = new JsonObject();
        String type = switch (kind) { case "NUMBER" -> integer ? "integer" : "number"; case "BOOLEAN" -> "boolean"; case "TEXT" -> "string"; default -> ""; };
        if (!type.isEmpty()) {
            if (nullable) result.add("type", strings(type, "null")); else result.addProperty("type", type);
        }
        if (kind.equals("NUMBER")) { result.addProperty("minimum", min); result.addProperty("maximum", max); }
        return result;
    }

    private static JsonArray strings(String... values) {
        JsonArray result = new JsonArray(); for (String value : values) result.add(new JsonPrimitive(value)); return result;
    }
}
