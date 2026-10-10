package clockwork.bosses;

import com.google.gson.JsonObject;

/** Positions plus independent X/Y eye dimensions fit in one native metadata integer. */
public record ClockworkBossEyes(int leftX, int leftY, int rightX, int rightY, int width, int height) {
    public static final ClockworkBossEyes DEFAULT = new ClockworkBossEyes(3, 7, 11, 7);
    /** Existing callers keep the original 2x2 eye shape. */
    public ClockworkBossEyes(int leftX, int leftY, int rightX, int rightY) { this(leftX, leftY, rightX, rightY, 2, 2); }
    public ClockworkBossEyes {
        width = Math.clamp(width, 1, 8); height = Math.clamp(height, 1, 8);
        leftX = Math.clamp(leftX, 0, 16 - width); leftY = Math.clamp(leftY, 0, 16 - height);
        rightX = Math.clamp(rightX, 0, 16 - width); rightY = Math.clamp(rightY, 0, 16 - height);
    }
    // Dimensions use three bits each above the sixteen coordinate bits. 0 means one pixel.
    public int packed() { return leftX | leftY << 4 | rightX << 8 | rightY << 12 | (width - 1) << 16 | (height - 1) << 19; }
    public static ClockworkBossEyes unpack(int packed) {
        return new ClockworkBossEyes(packed & 15, packed >>> 4 & 15, packed >>> 8 & 15, packed >>> 12 & 15,
                (packed >>> 16 & 7) + 1, (packed >>> 19 & 7) + 1);
    }
    public static ClockworkBossEyes read(JsonObject config) {
        var section = ClockworkBossEditorSchema.get(config, "skin.glowing_eyes");
        JsonObject eyes = section != null && section.isJsonObject() ? section.getAsJsonObject() : new JsonObject();
        return new ClockworkBossEyes(coordinate(eyes, "left_x", 3), coordinate(eyes, "left_y", 7), coordinate(eyes, "right_x", 11), coordinate(eyes, "right_y", 7),
                pixels(eyes, "scale_x"), pixels(eyes, "scale_y"));
    }
    private static int coordinate(JsonObject eyes, String key, int fallback) {
        try { double value = eyes.get(key).getAsDouble(); return Double.isFinite(value) && value == Math.rint(value) ? (int) Math.clamp(value, 0, 15) : fallback; }
        catch (RuntimeException invalid) { return fallback; }
    }
    /** Nearest-pixel scaling keeps crisp atlas pixels; 1x = two pixels, 0.5x = one, 4x = eight. */
    private static int pixels(JsonObject eyes, String key) {
        try {
            double scale = eyes.get(key).getAsDouble();
            return Double.isFinite(scale) ? (int) Math.round(Math.clamp(scale, 0.5, 4) * 2) : 2;
        } catch (RuntimeException invalid) { return 2; }
    }
}
