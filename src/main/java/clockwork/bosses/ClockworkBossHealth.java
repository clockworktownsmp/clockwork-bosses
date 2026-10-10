package clockwork.bosses;

/** One bound for configured and computed boss health; native HP remains the authoritative health pool. */
public final class ClockworkBossHealth {
    public static final double MAX_HEALTH = 1_000_000.0;

    private ClockworkBossHealth() {}

    /** Clamp native modifier results too; NaN uses the minimum, matching vanilla RangedAttribute behavior. */
    public static double sanitize(double value) {
        return Double.isNaN(value) ? 1.0 : Math.clamp(value, 1.0, MAX_HEALTH);
    }
}
