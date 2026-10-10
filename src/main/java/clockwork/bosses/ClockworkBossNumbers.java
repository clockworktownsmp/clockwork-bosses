package clockwork.bosses;

import java.math.BigDecimal;

/** Bounded decimal input for editor fields and reward quantities/probabilities.
 * A short exponent such as 1e-2000000000 must not expand into gigabytes of text.
 * Normal decimal/scientific notation remains exact; no rounding changes saved values. */
public final class ClockworkBossNumbers {
    private static final int MAX_TEXT = 128;
    private static final int MAX_PRECISION = 64;
    private static final int MAX_SCALE = 64;

    private ClockworkBossNumbers() {}

    /** Bound text before parsing and exponent/precision before arithmetic or plain formatting. */
    public static BigDecimal decimal(String text) {
        if (text.length() > MAX_TEXT) throw new NumberFormatException("Number is too long");
        BigDecimal value = new BigDecimal(text.trim());
        if (value.precision() > MAX_PRECISION || value.scale() < -MAX_SCALE || value.scale() > MAX_SCALE)
            throw new NumberFormatException("Number has excessive precision or exponent");
        return value;
    }

    /** Existing files may contain extreme exponents. Display them compactly without expanding
     * or silently rewriting them; edited values still pass the bounded parser before saving. */
    public static String display(BigDecimal value) {
        if (value.signum() == 0) return "0";
        if (value.precision() > MAX_PRECISION || value.scale() < -MAX_SCALE || value.scale() > MAX_SCALE)
            return value.toString();
        return value.stripTrailingZeros().toPlainString();
    }
}
