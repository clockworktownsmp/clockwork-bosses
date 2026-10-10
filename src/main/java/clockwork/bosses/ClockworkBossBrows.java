package clockwork.bosses;

import com.google.gson.JsonObject;

/** Top-left coordinates for two slanted 4x4 eyebrow masks, packed into one native metadata integer. */
public record ClockworkBossBrows(int leftX, int leftY, int rightX, int rightY) {
    public static final ClockworkBossBrows DEFAULT = new ClockworkBossBrows(2, 2, 10, 2);
    public ClockworkBossBrows {
        leftX = Math.clamp(leftX, 0, 12); leftY = Math.clamp(leftY, 0, 12);
        rightX = Math.clamp(rightX, 0, 12); rightY = Math.clamp(rightY, 0, 12);
    }
    public int packed() { return leftX | leftY << 4 | rightX << 8 | rightY << 12; }
    public static ClockworkBossBrows unpack(int packed) { return new ClockworkBossBrows(packed & 15, packed >>> 4 & 15, packed >>> 8 & 15, packed >>> 12 & 15); }
    /** Missing settings retain the existing slant and original eyebrow locations. */
    public static ClockworkBossBrows read(JsonObject config) {
        var section = ClockworkBossEditorSchema.get(config, "skin.glowing_eyebrows");
        JsonObject brows = section != null && section.isJsonObject() ? section.getAsJsonObject() : new JsonObject();
        return new ClockworkBossBrows(coordinate(brows, "left_x", 2), coordinate(brows, "left_y", 2),
                coordinate(brows, "right_x", 10), coordinate(brows, "right_y", 2));
    }
    private static int coordinate(JsonObject brows, String key, int fallback) {
        try {
            double value = brows.get(key).getAsDouble();
            return Double.isFinite(value) && value == Math.rint(value) ? (int) Math.clamp(value, 0, 12) : fallback;
        } catch (RuntimeException invalid) { return fallback; }
    }
}
