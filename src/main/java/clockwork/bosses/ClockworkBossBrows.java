package clockwork.bosses;

import com.google.gson.JsonObject;
import java.util.Arrays;
import java.util.Locale;

/** One static expression with independent positions and rotations. The existing
 * native integer carries the whole pose; no extra entity metadata is appended. */
public record ClockworkBossBrows(int leftX, int leftY, int rightX, int rightY,
                                Expression expression, int leftRotation, int rightRotation) {
    // Order is part of the two-bit wire layout: angry must remain zero.
    // Adding/reordering shapes requires a deliberate metadata-format change.
    public enum Expression {
        ANGRY, NEUTRAL, CURIOUS, SAD;

        /** Unknown/missing runtime values safely retain the original angry face. */
        public static Expression read(String value) {
            try { return valueOf(value.toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException | NullPointerException invalid) { return ANGRY; }
        }
    }
    private static final Expression[] EXPRESSIONS = Expression.values();
    public static final int MIN_ROTATION = -45, MAX_ROTATION = 45;
    public static final ClockworkBossBrows DEFAULT = new ClockworkBossBrows(2, 2, 10, 2);

    /** Retain the former constructor for existing callers and legacy settings. */
    public ClockworkBossBrows(int leftX, int leftY, int rightX, int rightY) {
        this(leftX, leftY, rightX, rightY, Expression.ANGRY, 0, 0);
    }
    public ClockworkBossBrows {
        leftX = Math.clamp(leftX, 0, 12); leftY = Math.clamp(leftY, 0, 12);
        rightX = Math.clamp(rightX, 0, 12); rightY = Math.clamp(rightY, 0, 12);
        if (expression == null) expression = Expression.ANGRY;
        leftRotation = Math.clamp(leftRotation, MIN_ROTATION, MAX_ROTATION);
        rightRotation = Math.clamp(rightRotation, MIN_ROTATION, MAX_ROTATION);
    }

    /** Low 16 bits retain the old four positions exactly. The upper bits hold
     * expression (2) and rotations (7 each). Rotation code zero means zero
     * degrees, so old metadata keeps the exact old shape. Signed integers are
     * valid native metadata values; unsigned shifts decode the high angle. */
    public int packed() {
        return leftX | leftY << 4 | rightX << 8 | rightY << 12
                | expression.ordinal() << 16 | rotationCode(leftRotation) << 18 | rotationCode(rightRotation) << 25;
    }
    public static ClockworkBossBrows unpack(int packed) {
        return new ClockworkBossBrows(packed & 15, packed >>> 4 & 15, packed >>> 8 & 15, packed >>> 12 & 15,
                EXPRESSIONS[packed >>> 16 & 3], rotationDegrees(packed >>> 18 & 127), rotationDegrees(packed >>> 25 & 127));
    }
    private static int rotationCode(int degrees) { return degrees >= 0 ? degrees : MAX_ROTATION - degrees; }
    private static int rotationDegrees(int code) { return code <= MAX_ROTATION ? code : code <= 90 ? MAX_ROTATION - code : 0; }

    /** Rasterize only on face-texture cache misses, never per tick. Inverse
     * nearest-neighbor sampling keeps pixel edges crisp, without holes from
     * forward rounding. Rotation is clockwise around each 4x4 mask's center.
     * Every output is clipped to the 16x16 face before touching the atlas. */
    public int[] facePixels(boolean right) {
        int anchorX = right ? rightX : leftX, anchorY = right ? rightY : leftY;
        double angle = Math.toRadians(right ? rightRotation : leftRotation);
        double cosine = Math.cos(angle), sine = Math.sin(angle);
        int[] pixels = new int[36]; int count = 0; // A rotated 4x4 mask fits inside this 6x6 search window.
        for (int y = Math.max(0, anchorY - 1); y < Math.min(16, anchorY + 5); y++) {
            for (int x = Math.max(0, anchorX - 1); x < Math.min(16, anchorX + 5); x++) {
                double dx = x - anchorX - 1.5, dy = y - anchorY - 1.5;
                int sourceX = (int) Math.floor(cosine * dx + sine * dy + 2);
                int sourceY = (int) Math.floor(-sine * dx + cosine * dy + 2);
                if (sourceX >= 0 && sourceX < 4 && sourceY >= 0 && sourceY < 4 && filled(right, sourceX, sourceY))
                    pixels[count++] = x | y << 4;
            }
        }
        return Arrays.copyOf(pixels, count);
    }

    /** Presets change the source shape, not the owner's saved positions.
     * Curious raises an arched left brow above a flatter right brow. */
    private boolean filled(boolean right, int x, int y) {
        boolean diagonal = x / 2 == y / 2;
        return switch (expression) {
            case ANGRY -> right ? !diagonal : diagonal;
            case SAD -> right ? diagonal : !diagonal;
            case NEUTRAL -> y == 1 || y == 2;
            case CURIOUS -> right ? y == 2 || y == 3
                    : (y == 0 && (x == 1 || x == 2)) || y == 1 || (y == 2 && (x == 0 || x == 3));
        };
    }

    /** Missing settings retain the existing slant, zero rotation and locations. */
    public static ClockworkBossBrows read(JsonObject config) {
        var section = ClockworkBossEditorSchema.get(config, "skin.glowing_eyebrows");
        JsonObject brows = section != null && section.isJsonObject() ? section.getAsJsonObject() : new JsonObject();
        String expression;
        try { expression = brows.get("expression").getAsString(); } catch (RuntimeException invalid) { expression = "angry"; }
        return new ClockworkBossBrows(integer(brows, "left_x", 2, 0, 12), integer(brows, "left_y", 2, 0, 12),
                integer(brows, "right_x", 10, 0, 12), integer(brows, "right_y", 2, 0, 12), Expression.read(expression),
                integer(brows, "left_rotation", 0, MIN_ROTATION, MAX_ROTATION), integer(brows, "right_rotation", 0, MIN_ROTATION, MAX_ROTATION));
    }
    private static int integer(JsonObject brows, String key, int fallback, int min, int max) {
        try {
            double value = brows.get(key).getAsDouble();
            return Double.isFinite(value) && value == Math.rint(value) ? (int) Math.clamp(value, min, max) : fallback;
        } catch (RuntimeException invalid) { return fallback; }
    }
}
