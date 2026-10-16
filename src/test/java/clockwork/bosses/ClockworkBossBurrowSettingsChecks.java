package clockwork.bosses;

import clockwork.bosses.api.BossActionRegistry;
import clockwork.bosses.api.BossActionSettings;
import clockwork.bosses.api.BossSetting;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;

/** Shared core/compiler/Workshop schema contract for the three emergence settings. */
final class ClockworkBossBurrowSettingsChecks {
    private static int checks;
    static int run() throws Exception {
        var fields = BossActionRegistry.require(ClockworkBossActions.BURROW).settings();
        var damage = fields.stream().filter(f -> f.key().equals("emerge_damage")).findFirst().orElseThrow();
        var radius = fields.stream().filter(f -> f.key().equals("emerge_radius")).findFirst().orElseThrow();
        var particles = fields.stream().filter(f -> f.key().equals("emerge_particles")).findFirst().orElseThrow();
        check(damage.kind() == BossSetting.Kind.NUMBER && !damage.integer() && damage.min() == 0 && damage.max() == 1_000_000, "damage descriptor");
        check(radius.kind() == BossSetting.Kind.NUMBER && !radius.integer() && radius.min() == 0 && radius.max() == 64, "radius descriptor");
        check(particles.kind() == BossSetting.Kind.BOOLEAN, "particle toggle descriptor");
        for (boolean explicitNull : new boolean[]{false, true}) {
            var input = new JsonObject();
            if (explicitNull) for (var field : fields) input.add(field.key(), JsonNull.INSTANCE);
            String before = input.toString();
            var compiled = BossActionSettings.compile(fields, input);
            check(compiled.number("emerge_damage") == 20 && compiled.number("emerge_radius") == 10
                    && compiled.bool("emerge_particles"), "omitted/null runtime defaults");
            check(input.toString().equals(before), "runtime compilation does not materialize stored defaults");
        }
        var explicit = new JsonObject(); explicit.addProperty("emerge_damage", 0); explicit.addProperty("emerge_radius", 0);
        explicit.addProperty("emerge_particles", false);
        var disabled = BossActionSettings.compile(fields, explicit);
        check(disabled.number("emerge_damage") == 0 && disabled.number("emerge_radius") == 0 && !disabled.bool("emerge_particles"), "explicit disabled values retained");
        explicit.addProperty("emerge_damage", .25); explicit.addProperty("emerge_radius", 3.5);
        var fractional = BossActionSettings.compile(fields, explicit);
        check(fractional.number("emerge_damage") == .25 && fractional.number("emerge_radius") == 3.5, "fractional units do not quantize");
        for (var pair : new Object[][]{{damage, "-0.00001"}, {damage, "1000000.00001"}, {radius, "-1"}, {radius, "64.00001"},
                {damage, "1e2000000000"}, {radius, "1e-2000000000"}, {damage, "\"20\""}, {particles, "1"}}) {
            try { ((BossSetting) pair[0]).parse(JsonParser.parseString((String) pair[1])); throw new AssertionError("Invalid setting accepted"); }
            catch (IllegalArgumentException expected) { checks++; }
        }
        var validator = new clockwork.web.TemplateJson(ClockworkBossTemplateSchema.write().getBytes(StandardCharsets.UTF_8));
        String prefix = "{\"format_version\":1,\"phases\":{\"annoyed\":{\"subphases\":{\"dig\":{\"type\":\"clockwork_bosses:burrow\",\"action_settings\":";
        for (String settings : new String[]{"{}", "{\"emerge_damage\":null,\"emerge_radius\":null,\"emerge_particles\":null}",
                "{\"emerge_damage\":0.25,\"emerge_radius\":3.5,\"emerge_particles\":false,\"addon_number\":900719925474099312345}"}) {
            byte[] bytes = (prefix + settings + "}}}}}").getBytes(StandardCharsets.UTF_8);
            validator.inspect(bytes, true); checks++;
        }
        for (String settings : new String[]{"{\"emerge_damage\":-1}", "{\"emerge_radius\":64.01}", "{\"emerge_particles\":\"true\"}"}) {
            try { validator.inspect((prefix + settings + "}}}}}").getBytes(StandardCharsets.UTF_8), true); throw new AssertionError("Invalid shared schema input accepted"); }
            catch (java.io.IOException expected) { checks++; }
        }
        check(ClockworkBossBurrowEmergence.DURATION_TICKS == 34 && ClockworkBossBurrowEmergence.millis(100, 134, .5F) == 5000,
                "surface endpoint holds without playing the remaining tail");
        return checks;
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); checks++; }
}
