package clockwork.bosses;

import com.google.gson.JsonObject;

/** Dark rectangles inside each eye. Offsets move with the eye; -1 follows its inner/bottom edge.
 * One native integer carries both positions, independent sizes and visibility, with no polling. */
public record ClockworkBossPupils(boolean enabled, int leftX, int leftY, int rightX, int rightY, int width, int height) {
    public static final ClockworkBossPupils DEFAULT = new ClockworkBossPupils(true, -1, -1, -1, -1, 1, 1);

    public ClockworkBossPupils {
        leftX = Math.clamp(leftX, -1, 7); leftY = Math.clamp(leftY, -1, 7);
        rightX = Math.clamp(rightX, -1, 7); rightY = Math.clamp(rightY, -1, 7);
        width = Math.clamp(width, 1, 8); height = Math.clamp(height, 1, 8);
    }

    // Four bits per offset reserve zero for Auto; three bits per dimension store pixels minus one.
    public int packed() {
        return (leftX + 1) | (leftY + 1) << 4 | (rightX + 1) << 8 | (rightY + 1) << 12
                | (width - 1) << 16 | (height - 1) << 19 | (enabled ? 1 << 22 : 0);
    }
    public static ClockworkBossPupils unpack(int packed) {
        return new ClockworkBossPupils((packed & (1 << 22)) != 0, (packed & 15) - 1, (packed >>> 4 & 15) - 1,
                (packed >>> 8 & 15) - 1, (packed >>> 12 & 15) - 1, (packed >>> 16 & 7) + 1, (packed >>> 19 & 7) + 1);
    }

    /** Oversized pupils shrink to fit; position then clamps against that actual rectangle. */
    public int widthIn(ClockworkBossEyes eyes) { return Math.min(width, eyes.width()); }
    public int heightIn(ClockworkBossEyes eyes) { return Math.min(height, eyes.height()); }
    public int xIn(ClockworkBossEyes eyes, boolean right) {
        int max = eyes.width() - widthIn(eyes), value = right ? rightX : leftX;
        if (value == -1) return right ? 0 : max;
        return Math.clamp(value, 0, max);
    }
    public int yIn(ClockworkBossEyes eyes, boolean right) {
        int max = eyes.height() - heightIn(eyes), value = right ? rightY : leftY;
        return value == -1 ? max : Math.clamp(value, 0, max);
    }
    /** Legacy Auto leaves one-pixel-wide/high eyes white. Explicit positioning/sizing can fill them. */
    public boolean visibleIn(ClockworkBossEyes eyes, boolean right) {
        boolean legacyAuto = (right ? rightX : leftX) == -1 && (right ? rightY : leftY) == -1 && width == 1 && height == 1;
        return enabled && (!legacyAuto || eyes.width() >= 2 && eyes.height() >= 2);
    }

    /** Missing/invalid settings retain the original one-pixel pupil, including scaled eye placement. */
    public static ClockworkBossPupils read(JsonObject config) {
        var section = ClockworkBossEditorSchema.get(config, "skin.glowing_pupils");
        JsonObject pupils = section != null && section.isJsonObject() ? section.getAsJsonObject() : new JsonObject();
        boolean enabled = true;
        try { enabled = pupils.get("enabled").getAsBoolean(); } catch (RuntimeException invalid) { /* Missing: visible. */ }
        return new ClockworkBossPupils(enabled, coordinate(pupils, "left_x"), coordinate(pupils, "left_y"),
                coordinate(pupils, "right_x"), coordinate(pupils, "right_y"), pixels(pupils, "scale_x"), pixels(pupils, "scale_y"));
    }
    private static int coordinate(JsonObject pupils, String key) {
        try {
            double value = pupils.get(key).getAsDouble();
            return Double.isFinite(value) && value == Math.rint(value) ? (int) Math.clamp(value, -1, 7) : -1;
        } catch (RuntimeException invalid) { return -1; }
    }
    private static int pixels(JsonObject pupils, String key) {
        try {
            double scale = pupils.get(key).getAsDouble();
            return Double.isFinite(scale) ? (int) Math.round(Math.clamp(scale, 0.5, 8)) : 1;
        } catch (RuntimeException invalid) { return 1; }
    }
}
