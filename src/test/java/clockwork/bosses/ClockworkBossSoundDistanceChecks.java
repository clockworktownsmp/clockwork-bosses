package clockwork.bosses;

import clockwork.bosses.api.BossActionRegistry;
import clockwork.bosses.api.BossActionSettings;
import clockwork.bosses.api.BossRoarSound;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Offline regression checks for spatial sound settings. No world, connection,
 * file transfer or Minecraft process is created. Use production compilers and
 * packet guards so these checks cover the actual template-to-playback contract. */
final class ClockworkBossSoundDistanceChecks {
    private static int assertions;
    private ClockworkBossSoundDistanceChecks() {}

    static int run() throws Exception {
        assertions = 0;
        checkCreationAndOmission();
        checkGeometry();
        checkPackets();
        checkRoarsAndSchema();
        return assertions;
    }

    /** New files opt into Auto; old omitted/null fields keep the old 32-block
     * behavior. Virtual editor backfills must not mutate the imported document. */
    private static void checkCreationAndOmission() throws Exception {
        JsonObject defaults = ClockworkBossTemplateDocument.defaults();
        JsonObject draft = ClockworkBossEditorSchema.editable(defaults);
        for (String path : ClockworkBossSoundDistance.BASE_RANGE_PATHS) {
            check(ClockworkBossEditorSchema.get(defaults, path).getAsDouble() == 0, "creation Auto: " + path);
            check(ClockworkBossEditorSchema.get(draft, path).getAsDouble() == 0, "editor preserves explicit Auto: " + path);
        }
        for (String preset : List.of("1_player.json", "2_players.json", "3_players.json", "4_players.json",
                "5_players.json", "6_players.json", "boss-sounds-example/template.json")) {
            try (var input = ClockworkBossSoundDistanceChecks.class.getResourceAsStream("/presets/" + preset)) {
                check(input != null, "bundled preset exists: " + preset);
                JsonObject template = ClockworkBossTemplateDocument.read(new InputStreamReader(input, StandardCharsets.UTF_8));
                for (String path : ClockworkBossSoundDistance.BASE_RANGE_PATHS)
                    check(ClockworkBossEditorSchema.get(template, path).getAsDouble() == 0, "preset Auto: " + preset + ": " + path);
            }
        }
        JsonObject old = defaults.deepCopy();
        for (String path : ClockworkBossSoundDistance.BASE_RANGE_PATHS) {
            int dot = path.lastIndexOf('.');
            ClockworkBossEditorSchema.get(old, path.substring(0, dot)).getAsJsonObject().remove("range");
        }
        String original = old.toString();
        JsonObject virtual = ClockworkBossEditorSchema.editable(old);
        for (String path : ClockworkBossSoundDistance.BASE_RANGE_PATHS)
            check(ClockworkBossEditorSchema.get(virtual, path).getAsDouble() == 32, "legacy virtual 32: " + path);
        check(old.toString().equals(original), "opening legacy file does not add defaults to stored data");
        check(ClockworkBossSettings.read(old).voiceLines().range() == 32, "omitted voice range remains 32");
        for (String path : ClockworkBossSoundDistance.BASE_RANGE_PATHS) ClockworkBossEditorSchema.put(old, path, JsonNull.INSTANCE);
        virtual = ClockworkBossEditorSchema.editable(old);
        for (String path : ClockworkBossSoundDistance.BASE_RANGE_PATHS)
            check(ClockworkBossEditorSchema.get(virtual, path).getAsDouble() == 32, "null legacy base uses 32: " + path);
        check(ClockworkBossSettings.read(old).voiceLines().range() == 32, "null voice range remains 32");
        for (double value : new double[]{0, 32.5, 128, 300, 512}) {
            defaults.getAsJsonObject("voice_lines").addProperty("range", value);
            check(ClockworkBossSettings.read(defaults).voiceLines().range() == value, "voice range compiler retains " + value);
        }
        for (String path : ClockworkBossSoundDistance.BASE_RANGE_PATHS) {
            var field = ClockworkBossEditorSchema.field(path, new JsonPrimitive(0));
            check(field.min() == 0 && field.max() == 512 && !field.integer(), "shared bounds: " + path);
            for (String valid : List.of("0", "0.5", "32.5", "300", "512"))
                check(ClockworkBossEditorSchema.parse(path, valid, new JsonPrimitive(0)).getAsDouble() == Double.parseDouble(valid), "manual/Auto input accepted");
            for (String invalid : List.of("-0.00000000000000001", "512.000000000000001", "NaN", "Infinity"))
                rejects(() -> ClockworkBossEditorSchema.parse(path, invalid, new JsonPrimitive(0)), "exact audio bound rejected");
        }
        check(ClockworkBossEditorSchema.parse("phases.annoyed.sounds.phase_change.range", "<inherit>", JsonNull.INSTANCE).isJsonNull(), "phase null still inherits");
    }

    /** Native saved boxes already contain inclusive max + 1. A one-block box
     * therefore has sqrt(3) diagonal; a legal 256-cube fits below the 512 cap. */
    private static void checkGeometry() {
        JsonObject config = new JsonObject();
        check(ClockworkBossSoundDistance.resolve(0, null) == 32, "no state uses legacy fallback");
        check(ClockworkBossSoundDistance.resolve(0, ClockworkBossManager.readAreas(config)) == 32, "no arena uses fallback");
        config.add("activation", JsonParser.parseString("{\"type\":\"player_enter_radius\",\"radius\":256}"));
        check(ClockworkBossSoundDistance.resolve(0, ClockworkBossManager.readAreas(config)) == 32, "activation radius cannot substitute for arena");
        JsonObject bossBar = new JsonObject(); config.add("boss_bar", bossBar);
        JsonObject arena = JsonParser.parseString("{\"shape\":\"box\",\"dimension\":\"minecraft:overworld\",\"min\":{\"x\":10,\"y\":-5,\"z\":20},\"max\":{\"x\":10,\"y\":-5,\"z\":20}}").getAsJsonObject();
        bossBar.add("visibility_area", arena);
        distance(config, Math.sqrt(3), "inclusive single block diagonal");
        arena.getAsJsonObject("min").addProperty("x", 265);
        arena.getAsJsonObject("max").addProperty("y", 250);
        arena.getAsJsonObject("max").addProperty("z", 275);
        distance(config, Math.sqrt(3 * 256.0 * 256.0), "reversed corner and maximum legal box");
        arena.getAsJsonObject("min").addProperty("x", 266);
        check(ClockworkBossSoundDistance.resolve(0, ClockworkBossManager.readAreas(config)) == 32, "oversized box fallback");
        arena.getAsJsonObject("min").addProperty("x", 10); arena.getAsJsonObject("max").remove("z");
        check(ClockworkBossSoundDistance.resolve(0, ClockworkBossManager.readAreas(config)) == 32, "partial box fallback");
        arena.addProperty("dimension", "Bad dimension!");
        check(ClockworkBossSoundDistance.resolve(0, ClockworkBossManager.readAreas(config)) == 32, "invalid dimension fallback");
        arena = new JsonObject(); arena.addProperty("shape", "radius"); bossBar.add("visibility_area", arena);
        for (double radius : new double[]{1, 32.1, 128, 200, 256}) {
            arena.addProperty("radius", radius);
            distance(config, radius, "radius is not diameter");
        }
        var areas = ClockworkBossManager.readAreas(config);
        for (double manual : new double[]{1, 32.5, 128, 300, 512})
            check(ClockworkBossSoundDistance.resolve(manual, areas) == manual, "manual distance independent of arena");
        check(ClockworkBossSoundDistance.resolve(.5, areas) == 1, "legacy playback minimum retained");
        check(ClockworkBossSoundDistance.resolve(Double.NaN, areas) == 32, "nonfinite input safe fallback");
        check(ClockworkBossSoundDistance.playback(900) == 512, "final playback cap");
    }
    private static void distance(JsonObject config, double expected, String message) {
        float actual = ClockworkBossSoundDistance.resolve(0, ClockworkBossManager.readAreas(config));
        check(actual >= expected && actual - expected <= Math.ulp(actual), message + ": float boundary rounds outward");
        check(actual >= 1 && actual <= 512, message + ": packet bounds");
    }

    /** The wire stays positive and bounded. Empty bytes describe an already
     * cached asset; these checks never create or send a media transfer. */
    private static void checkPackets() {
        String revision = "0".repeat(64);
        for (float value : new float[]{1, 128, 300, 512}) {
            var packet = new ClockworkBossSoundPayload("sample.ogg", revision, new byte[0], 0, 0, 0, 1, 1, value);
            check(packet.range() == value, "packet preserves resolved range");
        }
        check(new ClockworkBossSoundPayload("sample.ogg", revision, new byte[0], 0, 0, 0, 1, 1, 0).range() == 1, "raw Auto cannot travel as zero playback distance");
        check(new ClockworkBossSoundPayload("sample.ogg", revision, new byte[0], 0, 0, 0, 1, 1, 900).range() == 512, "packet caps adversarial input");
        rejects(() -> new ClockworkBossSoundPayload("sample.ogg", revision, new byte[0], 0, 0, 0, 1, 1, Float.NaN), "packet rejects nonfinite range");
    }

    /** Legacy roar overlays retain null inheritance. Registered roar actions
     * use their declared Auto default; export describes both rules separately. */
    private static void checkRoarsAndSchema() {
        JsonObject schema = ClockworkBossTemplateSchema.create();
        for (String path : ClockworkBossSoundDistance.BASE_RANGE_PATHS) {
            JsonObject field = definition(schema, path);
            check(field.get("default").getAsDouble() == 0 && field.get("minimum").getAsDouble() == 0 && field.get("maximum").getAsDouble() == 512, "exported Auto bounds: " + path);
            var metadata = field.getAsJsonObject("x-clockwork");
            check(metadata.getAsJsonObject("omitted").get("value").getAsDouble() == 32, "export distinguishes legacy omission: " + path);
            check(metadata.getAsJsonObject("automatic").get("maximum").getAsInt() == 512, "exported Auto contract");
        }
        var phaseRange = definition(schema.getAsJsonObject("$defs").getAsJsonObject("phase"), "sounds.phase_change.range");
        check(phaseRange.getAsJsonObject("x-clockwork").getAsJsonObject("omitted").get("kind").getAsString().equals("inherit"), "phase schema retains inheritance");
        var roarType = BossActionRegistry.require(ClockworkBossActions.ROAR);
        var range = roarType.settings().stream().filter(field -> field.key().equals("range")).findFirst().orElseThrow();
        check(range.defaultValue().getAsDouble() == 0 && range.min() == 0 && range.max() == 512, "registered roar descriptor Auto/512");
        check(BossActionSettings.compile(roarType.settings(), new JsonObject()).number("range") == 0, "registered roar omission uses Auto");
        var actionInput = new JsonObject(); actionInput.add("range", JsonNull.INSTANCE);
        check(BossActionSettings.compile(roarType.settings(), actionInput).number("range") == 0, "registered roar scalar null uses Auto");
        for (double value : new double[]{0, .5, 32, 300, 512}) check(new BossRoarSound("roar.ogg", 1, 1, value).range() == value, "roar API accepts range");
        for (double invalid : new double[]{-1, 513, Double.NaN, Double.POSITIVE_INFINITY})
            rejects(() -> new BossRoarSound("roar.ogg", 1, 1, invalid), "roar API rejects invalid range");
        JsonObject config = ClockworkBossTemplateDocument.defaults();
        var phase = config.getAsJsonObject("phases").getAsJsonObject("annoyed");
        phase.getAsJsonObject("subphases").add("combat", ClockworkBossEditorSchema.newSubphase());
        var roar = ClockworkBossEditorSchema.newRoar(); phase.getAsJsonObject("roars").add("interlude", roar);
        var baseSound = config.getAsJsonObject("attacks").getAsJsonObject("roar").getAsJsonObject("sound");
        baseSound.addProperty("range", 32);
        check(roarRange(config) == 32, "legacy roar inherits manual base");
        phase.getAsJsonObject("roar").getAsJsonObject("sound").addProperty("range", 0);
        check(roarRange(config) == 0, "legacy roar phase explicitly selects Auto");
        roar.getAsJsonObject("sound").addProperty("range", 300);
        check(roarRange(config) == 300, "legacy roar entry overrides phase");
        roar.getAsJsonObject("sound").add("range", JsonNull.INSTANCE);
        check(roarRange(config) == 0, "null roar entry inherits explicit phase Auto");
        baseSound.remove("range"); phase.getAsJsonObject("roar").getAsJsonObject("sound").remove("range");
        check(roarRange(config) == 32, "legacy roar omission everywhere keeps 32");
    }
    private static double roarRange(JsonObject config) {
        return ClockworkBossSubphases.read(config, ClockworkBossSettings.read(config)).get(ClockworkBossPhase.ANNOYED).roars().getFirst().number("range");
    }
    private static JsonObject definition(JsonObject schema, String path) {
        for (String key : path.split("\\.")) schema = schema.getAsJsonObject("properties").getAsJsonObject(key);
        return schema;
    }
    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
    private static void rejects(Runnable action, String message) {
        try { action.run(); }
        catch (IllegalArgumentException expected) { assertions++; return; }
        throw new AssertionError("Expected rejection: " + message);
    }
}
