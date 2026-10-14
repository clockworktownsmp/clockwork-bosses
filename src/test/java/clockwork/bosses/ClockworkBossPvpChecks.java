package clockwork.bosses;

import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Config/editor compatibility without a game or real server files. Native
 * attribution and gameplay belong to ClockworkBossPvpGameTests instead. */
public final class ClockworkBossPvpChecks {
    private static int assertions;
    private ClockworkBossPvpChecks() {}

    public static int run() throws Exception {
        assertions = 0;
        var defaults = ClockworkBossTemplateDocument.defaults();
        check(ClockworkBossPvp.Settings.read(defaults).enabled(), "new templates explicitly protect PvP");
        for (String body : List.of("{}", "{\"boss_bar\":null}", "{\"boss_bar\":{}}",
                "{\"boss_bar\":{\"pvp_protection\":null}}", "{\"boss_bar\":{\"pvp_protection\":{}}}",
                "{\"boss_bar\":{\"pvp_protection\":{\"enabled\":null}}}")) {
            var legacy = JsonParser.parseString(body).getAsJsonObject();
            check(!ClockworkBossPvp.Settings.read(legacy).enabled(), "omitted/null legacy protection stays disabled");
            var editable = ClockworkBossEditorSchema.editable(legacy);
            check(!ClockworkBossPvp.Settings.read(editable).enabled(), "opening the editor preserves legacy behavior");
            check(!ClockworkBossPvp.Settings.read(ClockworkBossTemplateDocument.read(
                    new StringReader(ClockworkBossTemplateDocument.write(legacy)))).enabled(), "commented round trip preserves omission/null");
        }
        var config = defaults.deepCopy(); var section = config.getAsJsonObject("boss_bar").getAsJsonObject("pvp_protection");
        section.addProperty("enabled", false);
        check(!ClockworkBossPvp.Settings.read(config).enabled(), "explicit opt-out");
        for (var value : List.of(new JsonPrimitive("true"), new JsonPrimitive(1), new JsonPrimitive("false"))) {
            section.add("enabled", value);
            check(!ClockworkBossPvp.Settings.read(config).enabled(), "no permissive boolean coercion at runtime");
        }
        section.addProperty("enabled", true); section.add("addon_private", JsonParser.parseString("{\"value\":9007199254740993}"));
        var reopened = ClockworkBossTemplateDocument.read(new StringReader(ClockworkBossTemplateDocument.write(config)));
        check(reopened.getAsJsonObject("boss_bar").equals(config.getAsJsonObject("boss_bar")), "unknown nested settings survive saves");
        var editor = ClockworkBossEditorSchema.editable(defaults);
        var edited = editor.deepCopy(); ClockworkBossEditorSchema.put(edited, "boss_bar.pvp_protection.enabled", new JsonPrimitive(false));
        ClockworkBossEditorSchema.validate(editor, edited);
        check(!ClockworkBossPvp.Settings.read(edited).enabled(), "native editor can switch protection off");
        ClockworkBossEditorSchema.put(edited, "boss_bar.pvp_protection.enabled", new JsonPrimitive("true"));
        try { ClockworkBossEditorSchema.validate(editor, edited); throw new AssertionError("editor accepted a string boolean"); }
        catch (IllegalArgumentException expected) { assertions++; }
        var schema = ClockworkBossTemplateSchema.create().getAsJsonObject("properties").getAsJsonObject("boss_bar")
                .getAsJsonObject("properties").getAsJsonObject("pvp_protection");
        check(schema.get("title").getAsString().equals("PvP protection"), "readable shared section label");
        check(schema.getAsJsonObject("x-clockwork").getAsJsonObject("omitted").get("kind").getAsString().equals("disabled"), "website knows legacy section is disabled");
        var enabled = schema.getAsJsonObject("properties").getAsJsonObject("enabled");
        check(enabled.get("default").getAsBoolean(), "creation default explicitly true");
        check(!enabled.getAsJsonObject("x-clockwork").getAsJsonObject("omitted").get("value").getAsBoolean(), "omitted value explicitly false");
        check(enabled.getAsJsonArray("type").toString().contains("null"), "schema accepts native null opt-out");
        for (String file : List.of("1_player.json", "2_players.json", "3_players.json", "4_players.json", "5_players.json", "6_players.json", "boss-sounds-example/template.json")) {
            try (var stream = ClockworkBossPvpChecks.class.getResourceAsStream("/presets/" + file);
                 var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                check(ClockworkBossPvp.Settings.read(ClockworkBossTemplateDocument.read(reader)).enabled(), "bundled preset opts in: " + file);
            }
        }
        check(ClockworkBossTemplateDocument.description("boss_bar.pvp_protection.enabled").contains("both directions"), "field help explains scope");
        return assertions;
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message); assertions++;
    }
}
