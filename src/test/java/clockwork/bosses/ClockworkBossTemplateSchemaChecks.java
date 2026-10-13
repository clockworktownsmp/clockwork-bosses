package clockwork.bosses;

import clockwork.bosses.api.BossAction;
import clockwork.bosses.api.BossActionRegistry;
import clockwork.bosses.api.BossActionType;
import clockwork.bosses.api.BossSetting;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import net.minecraft.resources.Identifier;

/** Offline template contract tests: exercise actual production readers, writers
 * and schema adapters. No game, network, world or server configuration is opened. */
public final class ClockworkBossTemplateSchemaChecks {
    private static int assertions;
    private static final List<String> PRESETS = List.of("1_player.json", "2_players.json", "3_players.json", "4_players.json",
            "5_players.json", "6_players.json", "boss-sounds-example/template.json");
    private ClockworkBossTemplateSchemaChecks() {}

    public static int run() throws Exception {
        assertions = 0;
        checkVersionsAndBounds();
        checkPreservation();
        checkBundledTemplates();
        checkCoreSchema();
        checkAddonSchema();
        checkReviewGaps();
        checkPortableCodec();
        return assertions;
    }

    /** Optional standards-validator fixtures. All outputs are disposable build
     * data, including the test-only addon catalog; no production presets change. */
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Supply one fixture output directory");
        int count = run();
        Path folder = Path.of(args[0]); Files.createDirectories(folder);
        Files.writeString(folder.resolve("schema.json"), ClockworkBossTemplateSchema.write(), StandardCharsets.UTF_8);
        fixture(folder, "valid/default.json", ClockworkBossTemplateDocument.defaults());
        for (String file : PRESETS) try (var stream = ClockworkBossTemplateSchemaChecks.class.getResourceAsStream("/presets/" + file)) {
            fixture(folder, "valid/" + file.replace('/', '-'), ClockworkBossTemplateDocument.read(new InputStreamReader(stream, StandardCharsets.UTF_8)));
        }
        JsonObject addon = ClockworkBossTemplateDocument.defaults();
        JsonObject child = ClockworkBossEditorSchema.newSubphase(); child.addProperty("type", "schema_fixture:wave");
        child.add("action_settings", JsonParser.parseString("{\"power\":11,\"warn\":null,\"style\":\"hard\",\"opaque\":{\"id\":9007199254740993}}"));
        addon.getAsJsonObject("phases").getAsJsonObject("annoyed").getAsJsonObject("subphases").add("wave", child);
        fixture(folder, "valid/addon.json", addon);
        child.getAsJsonObject("action_settings").addProperty("power", 12); fixture(folder, "invalid/addon-range.json", addon);
        child.add("action_settings", JsonNull.INSTANCE); fixture(folder, "invalid/action-settings-null.json", addon);
        JsonObject wrongType = ClockworkBossTemplateDocument.defaults(); wrongType.addProperty("health", "1000"); fixture(folder, "invalid/health-type.json", wrongType);
        wrongType.addProperty("health", 1_000_001); fixture(folder, "invalid/health-range.json", wrongType);
        JsonObject badFile = ClockworkBossTemplateDocument.defaults(); badFile.getAsJsonObject("skin").addProperty("file", "../escape.png"); fixture(folder, "invalid/skin-path.json", badFile);
        JsonObject tooMany = ClockworkBossTemplateDocument.defaults(); JsonObject phases = new JsonObject();
        for (int index = 0; index < 17; index++) phases.add("phase_" + index, ClockworkBossEditorSchema.newPhase());
        tooMany.add("phases", phases); fixture(folder, "invalid/phase-count.json", tooMany);
        JsonObject nullable = ClockworkBossTemplateDocument.defaults(); nullable.add("loot", JsonNull.INSTANCE);
        JsonObject phase = nullable.getAsJsonObject("phases").getAsJsonObject("annoyed");
        phase.add("subphase_order", JsonNull.INSTANCE); phase.add("roar_between_subphases", JsonNull.INSTANCE);
        fixture(folder, "valid/runtime-nulls.json", nullable);
        fixture(folder, "valid/omitted-legacy.json", JsonParser.parseString("{\"loot\":{\"loot_table\":\"minecraft:entities/warden\"}}").getAsJsonObject());
        JsonObject roar = ClockworkBossTemplateDocument.defaults(), roarChild = ClockworkBossEditorSchema.newSubphase();
        roarChild.addProperty("type", ClockworkBossActions.ROAR.toString()); JsonObject roarSettings = new JsonObject();
        roarSettings.addProperty("file", "phase_roar.ogg"); roarChild.add("action_settings", roarSettings);
        roar.getAsJsonObject("phases").getAsJsonObject("annoyed").getAsJsonObject("subphases").add("roar", roarChild);
        fixture(folder, "valid/roar-file.json", roar);
        roarSettings.addProperty("file", ""); fixture(folder, "valid/roar-file-empty.json", roar);
        roarSettings.add("file", JsonNull.INSTANCE); fixture(folder, "valid/roar-file-null.json", roar);
        int filename = 0;
        for (String invalid : List.of("../escape.ogg", "Roar.ogg", "phase 2.ogg", "roar.wav", "a".repeat(65) + ".ogg")) {
            roarSettings.addProperty("file", invalid); fixture(folder, "invalid/roar-file-" + filename++ + ".json", roar);
        }
        System.out.println("Template contract checks passed: " + count + " assertions; standards-validator fixtures exported.");
    }

    private static void fixture(Path folder, String name, JsonObject document) throws Exception {
        Path file = folder.resolve(name); Files.createDirectories(file.getParent());
        Files.writeString(file, ClockworkBossTemplateFormat.writeJson(document), StandardCharsets.UTF_8);
    }

    private static void checkVersionsAndBounds() throws Exception {
        JsonObject legacy = read("{\"health\":1000}");
        check(!legacy.has("format_version"), "read does not rewrite a legacy file");
        check(ClockworkBossTemplateFormat.version(legacy) == 1, "unversioned templates identify as revision 1");
        JsonObject saved = read(ClockworkBossTemplateFormat.writeJson(legacy));
        check(saved.get("format_version").getAsInt() == 1 && !legacy.has("format_version"), "save labels version without mutating source");
        for (String invalid : List.of("null", "true", "\"1\"", "0", "2", "1.1", "1e-2000000000")) {
            String input = "{\"format_version\":" + invalid + "}";
            rejects(() -> read(input), "unsupported/malformed version rejected on read");
            rejects(() -> ClockworkBossTemplateFormat.writeJson(JsonParser.parseString(input).getAsJsonObject()), "unsupported version never downgraded on export");
        }
        for (String duplicate : List.of("{\"health\":1,\"health\":2}", "{\"x\":{\"value\":null,\"value\":3}}",
                "{\"x\":[{\"k\":true,\"k\":false}]}", "{\"format_version\":2,\"format_version\":1}"))
            rejects(() -> read(duplicate), "duplicates cannot silently discard earlier data");
        rejects(() -> read("{} {}"), "second root rejected");
        rejects(() -> read("[]"), "nonobject root rejected");
        rejects(() -> read("//" + "x".repeat(ClockworkBossTemplateFormat.MAX_BYTES) + "\n{}"), "comments count toward streaming size bound");
        rejects(() -> read("{\"x\":" + "[".repeat(40) + "0" + "]".repeat(40) + "}"), "arrays cannot bypass nesting bound");
        JsonObject large = new JsonObject(); large.addProperty("addon", "♥".repeat(180_000));
        rejects(() -> read(large.toString()), "streaming import bounds actual UTF-8 bytes");
        rejects(() -> ClockworkBossTemplateFormat.writeJson(large), "export bounds UTF-8 bytes, not just characters");
    }

    /** Tree data, not comment placement, is the preservation contract. In
     * particular, nested keys resembling editor metadata belong to the addon. */
    private static void checkPreservation() throws Exception {
        JsonObject data = ClockworkBossTemplateDocument.defaults();
        JsonObject opaque = read("{\"_documentation\":\"addon note\",\"_action_catalog\":{\"foreign\":true},"
                + "\"_action_types\":null,\"_phase_order\":[\"z\",\"a\"],\"number\":9007199254740993,"
                + "\"exponent\":1e-2000000000,\"explicit_null\":null,\"array\":[1,null,{\"_documentation\":false}],"
                + "\"text\":\"a \\\"quote\\\", a \\\\ slash, // text and ♥\"}");
        data.add("foreign_addon", opaque);
        JsonObject child = ClockworkBossEditorSchema.newSubphase(); child.addProperty("type", "missing_addon:wave");
        child.add("action_settings", opaque.deepCopy());
        data.getAsJsonObject("phases").getAsJsonObject("annoyed").getAsJsonObject("subphases").add("wave", child);
        var order = data.getAsJsonObject("phases").keySet().stream().toList();
        for (String text : List.of(ClockworkBossTemplateDocument.write(data), ClockworkBossTemplateFormat.writeJson(data))) {
            JsonObject loaded = read(text);
            check(loaded.toString().equals(data.toString()), "all unknown data and number tokens survive both writers");
            check(loaded.getAsJsonObject("phases").keySet().stream().toList().equals(order), "phase order survives round trip");
            check(loaded.getAsJsonObject("foreign_addon").toString().equals(opaque.toString()), "nested reserved-like names survive exactly");
            check(read(ClockworkBossTemplateDocument.write(loaded)).toString().equals(data.toString()), "repeated saves are stable");
        }
        JsonObject draft = ClockworkBossEditorSchema.editable(data);
        check(draft.getAsJsonObject("foreign_addon").toString().equals(opaque.toString()), "editor backfill preserves opaque extensions");
        check(ClockworkBossEditorSchema.get(draft, "phases.annoyed.subphases.wave.action_settings").toString().equals(opaque.toString()), "missing addon does not prune its settings");
        rejects(() -> ClockworkBossActionEditor.validate(draft), "preservation does not authorize running a missing addon");
        JsonObject transport = data.deepCopy(); transport.addProperty("_documentation", "root notes");
        transport.add("_action_catalog", new JsonObject()); transport.addProperty("_phase_order", "transport only");
        check(read(ClockworkBossTemplateDocument.write(transport)).toString().equals(data.toString()), "only documented root metadata is stripped");
        JsonObject numericNames = read("{\"phases\":{\"10\":{},\"2\":{},\"1\":{}}}");
        check(read(ClockworkBossTemplateFormat.writeJson(numericNames)).getAsJsonObject("phases").keySet().stream().toList().equals(List.of("10", "2", "1")), "integer-looking keys retain encounter order");

        // A normal GUI scalar edit must leave unrecognized payloads untouched.
        JsonObject withoutMissingAction = data.deepCopy(); withoutMissingAction.getAsJsonObject("phases").getAsJsonObject("annoyed").getAsJsonObject("subphases").remove("wave");
        JsonObject expected = ClockworkBossEditorSchema.editable(withoutMissingAction), candidate = expected.deepCopy();
        candidate.addProperty("health", 1200);
        ClockworkBossEditorSchema.validate(expected, candidate);
        check(candidate.getAsJsonObject("foreign_addon").toString().equals(opaque.toString()), "unrelated GUI edit preserves extreme unknown numbers");
    }

    private static void checkBundledTemplates() throws Exception {
        for (String file : PRESETS) {
            try (var stream = ClockworkBossTemplateSchemaChecks.class.getResourceAsStream("/presets/" + file)) {
                check(stream != null, "preset exists: " + file);
                JsonObject source = ClockworkBossTemplateDocument.read(new InputStreamReader(stream, StandardCharsets.UTF_8));
                check(source.get("format_version").getAsInt() == 1, "bundled preset explicitly versioned: " + file);
                for (String output : List.of(ClockworkBossTemplateDocument.write(source), ClockworkBossTemplateFormat.writeJson(source)))
                    check(read(output).toString().equals(source.toString()), "preset data/order round trip: " + file);
            }
        }
    }

    /** Recursively compare exported core bounds/options against the real editor
     * definitions. Adding a field to the form automatically adds schema coverage. */
    private static void checkCoreSchema() {
        JsonObject schema = ClockworkBossTemplateSchema.create();
        check(schema.toString().equals(ClockworkBossTemplateSchema.create().toString()), "export is deterministic");
        check(schema.get("$schema").getAsString().equals("https://json-schema.org/draft/2020-12/schema"), "standard schema dialect");
        check(schema.getAsJsonObject("x-clockwork").getAsJsonObject("numeric_input").get("max_precision").getAsInt() == ClockworkBossNumbers.MAX_PRECISION,
                "portable numeric input limits share the real parser constants");
        check(schema.getAsJsonObject("properties").getAsJsonObject("format_version").get("const").getAsInt() == 1, "explicit version constrained");
        JsonObject form = ClockworkBossEditorSchema.editable(ClockworkBossTemplateDocument.defaults());
        ClockworkBossTemplateFormat.ROOT_METADATA.forEach(form::remove);
        checkFields(form, schema, "");
        JsonObject definitions = schema.getAsJsonObject("$defs");
        checkFields(ClockworkBossEditorSchema.newPhase(), definitions.getAsJsonObject("phase"), "phases.<phase>");
        checkFields(ClockworkBossEditorSchema.newSubphase(), definitions.getAsJsonObject("subphase"), "phases.<phase>.subphases.<subphase>");
        checkFields(ClockworkBossEditorSchema.newRoar(), definitions.getAsJsonObject("roar"), "phases.<phase>.roars.<roar>");
        checkFields(ClockworkBossLootEntries.newEntry(), definitions.getAsJsonObject("loot_entry"), "loot.entries.<entry>");
        JsonObject phaseMelee = definitions.getAsJsonObject("phase").getAsJsonObject("properties").getAsJsonObject("melee").getAsJsonObject("properties").getAsJsonObject("damage");
        check(phaseMelee.getAsJsonObject("x-clockwork").get("inherits_from").getAsString().equals("attacks.melee.damage"), "phase inherits base attack");
        JsonObject childMelee = definitions.getAsJsonObject("subphase").getAsJsonObject("properties").getAsJsonObject("melee").getAsJsonObject("properties").getAsJsonObject("damage");
        check(childMelee.getAsJsonObject("x-clockwork").get("inherits_from").getAsString().equals("phases.<phase>.melee.damage"), "subphase inherits parent");
        check(schema.getAsJsonObject("x-clockwork").getAsJsonObject("actions").has("clockwork_bosses:burrow"), "core actions exported through addon catalog");
        check(!ClockworkBossTemplateSchema.write().contains("_documentation\":"), "private comment catalog not exported as template data");
    }

    private static void checkFields(JsonObject sample, JsonObject schema, String path) {
        check(schema.getAsJsonObject("properties").keySet().containsAll(sample.keySet()), "all declared fields exported: " + path);
        for (var entry : sample.entrySet()) {
            String key = path.isEmpty() ? entry.getKey() : path + "." + entry.getKey();
            JsonObject field = schema.getAsJsonObject("properties").getAsJsonObject(entry.getKey());
            if (key.equals("phases") || key.endsWith(".subphases") || key.endsWith(".roars") || key.equals("loot.entries")) {
                check(field.has("maxProperties") && field.getAsJsonObject("x-clockwork").get("ordered").getAsBoolean(), "ordered catalogs bounded: " + key);
                continue;
            }
            if (entry.getValue().isJsonObject()) { checkFields(entry.getValue().getAsJsonObject(), field, key); continue; }
            var shared = ClockworkBossEditorSchema.field(key, entry.getValue());
            check(field.get("default").toString().equals(entry.getValue().toString()), "defaults identical: " + key);
            if (shared.kind() == ClockworkBossEditorSchema.Kind.NUMBER) {
                check(field.get("minimum").getAsDouble() == shared.min() && field.get("maximum").getAsDouble() == shared.max(), "number limits identical: " + key);
            }
            if (shared.kind() == ClockworkBossEditorSchema.Kind.TEXT && !shared.choices().isEmpty() && !key.endsWith(".type") && !key.contains(".action_settings."))
                check(field.getAsJsonArray("enum").size() == shared.choices().size() + (shared.nullable() ? 1 : 0), "options identical: " + key);
        }
    }

    /** Regression coverage for creation vs omission, conditional controls,
     * native-compatible nulls and the built-in roar's shared filename rule. */
    private static void checkReviewGaps() throws Exception {
        JsonObject schema = ClockworkBossTemplateSchema.create();
        var lootSource = schema.getAsJsonObject("properties").getAsJsonObject("loot").getAsJsonObject("properties").getAsJsonObject("source");
        check(lootSource.get("default").getAsString().equals("entries"), "new loot source remains entries");
        check(lootSource.getAsJsonObject("x-clockwork").getAsJsonObject("omitted").get("value").getAsString().equals(ClockworkBossLoot.read(JsonParser.parseString("{\"loot\":{}}").getAsJsonObject()).source()), "omitted loot source is runtime table, not creation entries");
        JsonObject document = ClockworkBossTemplateDocument.defaults();
        document.add("loot", JsonNull.INSTANCE);
        check(ClockworkBossLoot.read(document).equals(ClockworkBossLoot.DISABLED), "null loot disables template rewards");
        JsonObject phase = document.getAsJsonObject("phases").getAsJsonObject("annoyed");
        phase.add("subphase_order", JsonNull.INSTANCE); phase.add("roar_between_subphases", JsonNull.INSTANCE);
        phase.getAsJsonObject("subphases").add("pause", ClockworkBossEditorSchema.newSubphase());
        var sequence = ClockworkBossSubphases.read(document, ClockworkBossSettings.read(document)).get(ClockworkBossPhase.ANNOYED);
        check(sequence.order() == ClockworkBossSubphases.DEFAULT_ORDER && sequence.roarBetween(), "null sequence controls compile to runtime defaults");
        for (String key : List.of("subphase_order", "roar_between_subphases")) {
            String path = "phases.annoyed." + key;
            check(ClockworkBossEditorSchema.field(path, phase.get(key)).nullable(), "editor agrees with supported null: " + key);
            check(schema.getAsJsonObject("$defs").getAsJsonObject("phase").getAsJsonObject("properties").getAsJsonObject(key).getAsJsonArray("type").toString().contains("null"), "schema agrees with supported null: " + key);
        }
        check(read(ClockworkBossTemplateDocument.write(document)).toString().equals(document.toString()), "writer preserves all runtime-supported nulls");
        String root = "phases.annoyed.subphases.pause";
        for (String action : List.of("attack", "recovery", "clockwork_bosses:combat", "clockwork_bosses:melee", "clockwork_bosses:sonic_boom", "clockwork_bosses:roar", "clockwork_bosses:burrow", "unknown:action")) {
            check(visible(root + ".attacks_before_next", action) == action.equals("attack"), "legacy attack budget visibility: " + action);
            check(visible(root + ".duration_ticks", action) == action.equals("recovery"), "legacy duration visibility: " + action);
            check(visible(root + ".action_settings", action) == !List.of("attack", "recovery").contains(action), "registered action settings visibility: " + action);
            check(visible(root + ".melee.damage", action) == List.of("attack", "clockwork_bosses:combat", "clockwork_bosses:melee").contains(action), "melee override visibility: " + action);
            check(visible(root + ".sonic_boom.damage", action) == List.of("attack", "clockwork_bosses:combat", "clockwork_bosses:sonic_boom").contains(action), "sonic override visibility: " + action);
        }
        JsonObject childSchema = schema.getAsJsonObject("$defs").getAsJsonObject("subphase");
        var durationRule = childSchema.getAsJsonObject("properties").getAsJsonObject("duration_ticks").getAsJsonObject("x-clockwork").getAsJsonObject("visible_when");
        check(durationRule.toString().equals(ClockworkBossTemplateVisibility.rule("phases.<phase>.subphases.<subphase>.duration_ticks").descriptor().toString()), "export uses the actual GUI visibility rule");
        // Hiding combat values never rewrites them, including after an action
        // changes to recovery. Saving/reading still keeps their exact values.
        var child = phase.getAsJsonObject("subphases").getAsJsonObject("pause");
        child.addProperty("type", "recovery"); child.getAsJsonObject("melee").addProperty("damage", 57);
        check(read(ClockworkBossTemplateFormat.writeJson(document)).getAsJsonObject("phases").getAsJsonObject("annoyed").getAsJsonObject("subphases").getAsJsonObject("pause").getAsJsonObject("melee").get("damage").getAsInt() == 57, "hidden combat values survive round trips");
        child.addProperty("type", ClockworkBossActions.ROAR.toString()); JsonObject sound = new JsonObject(); child.add("action_settings", sound);
        String filePath = root + ".action_settings.file";
        for (String good : List.of("", "roar.ogg", "phase_2.ogg", "a".repeat(64) + ".ogg")) {
            sound.addProperty("file", good); ClockworkBossActionEditor.validate(document);
            check(ClockworkBossEditorSchema.parse(filePath, good, new com.google.gson.JsonPrimitive(""), document).getAsString().equals(good), "GUI accepts valid roar basename: " + good);
        }
        for (String bad : List.of("../escape.ogg", "Roar.ogg", "phase 2.ogg", "roar.wav", "a".repeat(65) + ".ogg")) {
            sound.addProperty("file", bad);
            rejects(() -> ClockworkBossActionEditor.validate(document), "action save rejects invalid roar filename");
            rejects(() -> ClockworkBossEditorSchema.parse(filePath, bad, new com.google.gson.JsonPrimitive(""), document), "GUI rejects invalid roar filename");
        }
        sound.add("file", JsonNull.INSTANCE); ClockworkBossActionEditor.validate(document);
        check(ClockworkBossSubphases.read(document, ClockworkBossSettings.read(document)).get(ClockworkBossPhase.ANNOYED).steps().getFirst().actionSettings().text("file").isEmpty(), "null roar filename uses native roar");
    }
    private static boolean visible(String path, String action) {
        var rule = ClockworkBossTemplateVisibility.rule(path);
        return rule == null || rule.matches((selector, fallback) -> action);
    }

    /** An addon uses exactly the same public registration/settings API as core
     * actions. Export must discover it without another editor or exporter list. */
    private static void checkAddonSchema() {
        var id = Identifier.fromNamespaceAndPath("schema_fixture", "wave");
        var fields = List.of(BossSetting.number("power", "Wave power", "Damage for this test action.", 7, 1, 11, true),
                BossSetting.bool("warn", "Warning", "Show its warning.", true),
                BossSetting.text("style", "Style", "Choose a wave style.", "soft", List.of("soft", "hard"), 8));
        var registrar = BossActionRegistry.registrar("schema_fixture");
        try { registrar.register(new BossActionType(id, "Fixture wave", "Schema test only.", fields, Set.of(), () -> context -> BossAction.Result.COMPLETE)); }
        finally { registrar.close(); }
        JsonObject schema = ClockworkBossTemplateSchema.create();
        check(schema.getAsJsonObject("x-clockwork").getAsJsonObject("actions").has(id.toString()), "installed addon discovered");
        JsonObject conditions = schema.getAsJsonObject("$defs").getAsJsonObject("subphase");
        var condition = conditions.getAsJsonArray("allOf").asList().stream().map(JsonElement::getAsJsonObject)
                .filter(item -> item.getAsJsonObject("if").getAsJsonObject("properties").getAsJsonObject("type").get("const").getAsString().equals(id.toString())).findFirst().orElseThrow();
        JsonObject settings = condition.getAsJsonObject("then").getAsJsonObject("properties").getAsJsonObject("action_settings").getAsJsonObject("properties");
        check(settings.getAsJsonObject("power").get("maximum").getAsInt() == 11 && settings.getAsJsonObject("power").get("default").getAsInt() == 7, "addon bounds/default reused");
        check(settings.getAsJsonObject("style").get("maxLength").getAsInt() == 8 && settings.getAsJsonObject("style").getAsJsonArray("enum").contains(JsonNull.INSTANCE), "addon choices/null defaults exported");
        JsonObject config = ClockworkBossTemplateDocument.defaults();
        var child = ClockworkBossEditorSchema.newSubphase(); child.addProperty("type", id.toString());
        JsonObject input = new JsonObject(); input.add("power", JsonNull.INSTANCE); input.addProperty("unknown", "preserve me"); child.add("action_settings", input);
        config.getAsJsonObject("phases").getAsJsonObject("annoyed").getAsJsonObject("subphases").add("wave", child);
        JsonObject draft = ClockworkBossEditorSchema.editable(config);
        String power = "phases.annoyed.subphases.wave.action_settings.power";
        check(ClockworkBossEditorSchema.field(power, JsonNull.INSTANCE, draft).nullable(), "GUI supports API default marker");
        check(ClockworkBossEditorSchema.parse(power, "<inherit>", JsonNull.INSTANCE, draft).isJsonNull(), "GUI default marker remains null");
        ClockworkBossActionEditor.validate(draft);
        check(ClockworkBossEditorSchema.get(draft, "phases.annoyed.subphases.wave.action_settings.unknown").getAsString().equals("preserve me"), "registered addon preserves undeclared values");
        child.add("action_settings", JsonNull.INSTANCE);
        rejects(() -> ClockworkBossActionEditor.validate(config), "present action_settings cannot be null");
        child.remove("action_settings"); ClockworkBossActionEditor.validate(config);
        check(true, "omitted action_settings uses declared defaults");
    }

    /** Prove the format boundary can run with only its classes, Gson and the
     * JDK. Minecraft/Fabric classes are deliberately absent from this loader. */
    private static void checkPortableCodec() throws Exception {
        URL classes = ClockworkBossTemplateFormat.class.getProtectionDomain().getCodeSource().getLocation();
        URL gson = Gson.class.getProtectionDomain().getCodeSource().getLocation();
        try (URLClassLoader loader = new URLClassLoader(new URL[]{classes, gson}, ClassLoader.getPlatformClassLoader())) {
            Class<?> format = loader.loadClass("clockwork.bosses.ClockworkBossTemplateFormat");
            Object document = format.getMethod("read", java.io.Reader.class).invoke(null, new StringReader("{\"addon\":{\"id\":9007199254740993}}"));
            String output = (String) format.getMethod("writeJson", loader.loadClass("com.google.gson.JsonObject")).invoke(null, document);
            check(output.contains("9007199254740993") && output.contains("\"format_version\":1"), "portable codec needs no Minecraft/Fabric class");
            try { loader.loadClass("net.minecraft.server.MinecraftServer"); throw new AssertionError("Minecraft unexpectedly available"); }
            catch (ClassNotFoundException expected) { check(true, "isolated format classpath excludes Minecraft"); }
        }
    }

    private static JsonObject read(String text) throws Exception { return ClockworkBossTemplateDocument.read(new StringReader(text)); }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message); assertions++;
    }
    @FunctionalInterface private interface Operation { void run() throws Exception; }
    private static void rejects(Operation operation, String message) {
        try { operation.run(); } catch (Exception expected) { assertions++; return; }
        throw new AssertionError(message);
    }
}
