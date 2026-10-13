package clockwork.bosses;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.function.BiFunction;

/** Presentation rules shared by the game editor and exported website schema.
 * Hiding a control never deletes or resets its stored value. The selector can
 * read pending GUI input, so changing a dropdown immediately updates the form. */
public final class ClockworkBossTemplateVisibility {
    private static final List<String> COMBAT = List.of("attack", "clockwork_bosses:combat", "clockwork_bosses:melee", "clockwork_bosses:sonic_boom");
    private ClockworkBossTemplateVisibility() {}

    /** One membership test is sufficient for these built-in controls. Paths in
     * the export use the same phase/subphase placeholders as other annotations.
     * Unknown addon settings have no extra restriction; their action definition
     * selects them. This is UI metadata, not permission or save validation. */
    public record Rule(String path, String fallback, List<String> values, boolean exclude) {
        public Rule { values = List.copyOf(values); }
        public boolean matches(BiFunction<String, String, String> selector) {
            return values.contains(selector.apply(path, fallback)) != exclude;
        }
        public JsonObject descriptor() {
            JsonObject result = new JsonObject(); result.addProperty("path", path);
            result.addProperty("fallback", fallback); result.addProperty("match", exclude ? "not_in" : "in");
            JsonArray options = new JsonArray(); values.forEach(options::add); result.add("values", options);
            return result;
        }
    }
    public static Rule rule(String path) {
        if (path.equals("loot.loot_table")) return selected("loot.source", "table", "table");
        if (path.equals("loot.entries") || path.startsWith("loot.entries.")) return selected("loot.source", "table", "entries");
        if (path.matches("phases\\.[^.]+\\.subphases\\.[^.]+\\..+")) {
            int end = path.indexOf('.', path.indexOf(".subphases.") + 11);
            String root = path.substring(0, end), key = path.substring(end + 1), selector = root + ".type";
            if (key.equals("type")) return null;
            if (key.equals("action_settings") || key.startsWith("action_settings."))
                return new Rule(selector, "attack", List.of("attack", "recovery"), true);
            if (key.equals("duration_ticks")) return selected(selector, "attack", "recovery");
            if (key.equals("attacks_before_next")) return selected(selector, "attack", "attack");
            if (key.equals("melee") || key.startsWith("melee."))
                return new Rule(selector, "attack", List.of("attack", "clockwork_bosses:combat", "clockwork_bosses:melee"), false);
            if (key.equals("sonic_boom") || key.startsWith("sonic_boom."))
                return new Rule(selector, "attack", List.of("attack", "clockwork_bosses:combat", "clockwork_bosses:sonic_boom"), false);
            return new Rule(selector, "attack", COMBAT, false);
        }
        if (path.equals("activation.min") || path.startsWith("activation.min.") || path.equals("activation.max") || path.startsWith("activation.max."))
            return selected("activation.type", "player_enter_area", "player_enter_area");
        if (path.equals("activation.radius")) return selected("activation.type", "player_enter_area", "player_enter_radius");
        if (path.equals("activation.position") || path.startsWith("activation.position.")) return selected("activation.type", "player_enter_area", "redstone");
        if (path.equals("boss_bar.visibility_area.min") || path.startsWith("boss_bar.visibility_area.min.")
                || path.equals("boss_bar.visibility_area.max") || path.startsWith("boss_bar.visibility_area.max."))
            return selected("boss_bar.visibility_area.shape", "box", "box");
        if (path.equals("boss_bar.visibility_area.radius")) return selected("boss_bar.visibility_area.shape", "box", "radius");
        return null;
    }
    private static Rule selected(String path, String fallback, String value) { return new Rule(path, fallback, List.of(value), false); }
}
