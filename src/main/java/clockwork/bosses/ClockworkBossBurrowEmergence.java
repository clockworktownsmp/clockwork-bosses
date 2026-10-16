package clockwork.bosses;

/** Internal timing for returning from burrow, separate from initial activation.
 * Both native leg position/rotation channels are planted at 5.00 clip seconds;
 * cut the remaining head/body/arm tail while preserving the climb to that point.
 * Clients sample the same captured game time, including when tracking starts late. */
public final class ClockworkBossBurrowEmergence {
    private static final int SPEED = 3;
    public static final long SURFACE_MILLIS = 5_000;
    public static final int DURATION_TICKS = (int) ((SURFACE_MILLIS + 50 * SPEED - 1) / (50 * SPEED));

    private ClockworkBossBurrowEmergence() {}

    /** Negative means ordinary native playback. Hold the final accelerated frame
     * until pose/cleanup metadata arrives; never fall back to an unfinished clip. */
    public static long millis(long startTick, long now, float partialTick) {
        if (startTick < 0) return -1;
        if (now < startTick) return 0;
        float fraction = Float.isFinite(partialTick) ? Math.clamp(partialTick, 0, 1) : 0;
        double elapsed = Math.min(now - startTick, (long) DURATION_TICKS) + fraction;
        return Math.min(SURFACE_MILLIS, (long) (elapsed * 50 * SPEED));
    }
}
