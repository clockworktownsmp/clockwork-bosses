package clockwork.bosses;

import com.google.gson.JsonObject;

/** Template-local, independently enabled player health and potion policies.
 * Compile on load/reload; arena transitions and native events read primitives. */
public record ClockworkBossPlayerSupport(Health health, Potion potion) {
    public record Health(boolean enabled, double minimumMaxHealth, boolean healOnFirstEntry) {}
    public record Potion(boolean enabled, float healHp, int cooldownTicks, int hunger, float saturation) {}

    /** Missing sections grant nothing to older templates. New template files
     * contain explicit defaults; the beacon-effect master switch is separate. */
    public static ClockworkBossPlayerSupport read(JsonObject template) {
        JsonObject bar = object(template, "boss_bar"), health = object(bar, "player_health"), potion = object(bar, "reusable_potion");
        return new ClockworkBossPlayerSupport(
                new Health(enabled(health), number(health, "minimum_max_health", 100, 1, 1000), bool(health, "heal_on_first_entry", true)),
                new Potion(enabled(potion), (float) number(potion, "heal_hp", 20, 0, 1000),
                        (int) Math.ceil(number(potion, "cooldown_seconds", 15, .05, 600) * 20),
                        (int) number(potion, "hunger_restored", 20, 0, 20),
                        (float) number(potion, "saturation_restored", 20, 0, 20)));
    }
    private static JsonObject object(JsonObject root, String key) {
        return root.has(key) && root.get(key).isJsonObject() ? root.getAsJsonObject(key) : new JsonObject();
    }
    private static boolean enabled(JsonObject object) { return bool(object, "enabled", false); }
    private static boolean bool(JsonObject object, String key, boolean fallback) {
        return object.has(key) && object.get(key).isJsonPrimitive() && object.getAsJsonPrimitive(key).isBoolean()
                ? object.get(key).getAsBoolean() : fallback;
    }
    private static double number(JsonObject object, String key, double fallback, double min, double max) {
        try { double value = object.get(key).getAsDouble(); return Double.isFinite(value) ? Math.clamp(value, min, max) : fallback; }
        catch (RuntimeException invalid) { return fallback; }
    }
}
