package clockwork.bosses;

/** Atomic native STRING metadata for one shot; contains only server game-time primitives, never target/entity references. */
public record ClockworkBossChargeVisual(int sequence, long startTick, int duration, long firedTick, int recoveryTicks) {
    // The native 26.3 Warden keyframes shoot at 1.7 seconds (34 ticks), then recover through 3 seconds.
    public static final long SHOT_MILLIS = 1700;
    public static final long END_MILLIS = 3000;

    public ClockworkBossChargeVisual {
        if (startTick < 0 || duration < 0 || duration > 1_000_000 || recoveryTicks < 0 || recoveryTicks > 12000 || firedTick < -1
                || firedTick >= 0 && firedTick < startTick) throw new IllegalArgumentException("Invalid charge visual timing");
    }
    public ClockworkBossChargeVisual(int sequence, long startTick, int duration, long firedTick) {
        this(sequence, startTick, duration, firedTick, 26);
    }
    public String encode() { return sequence + ":" + startTick + ":" + duration + ":" + firedTick + ":" + recoveryTicks; }

    /** Parse only when metadata changes. Invalid/bounded data disables the override without throwing in the renderer. */
    public static ClockworkBossChargeVisual decode(String value) {
        if (value == null || value.isEmpty() || value.length() > 80) return null;
        try {
            String[] parts = value.split(":", -1);
            if (parts.length != 4 && parts.length != 5) return null;
            return new ClockworkBossChargeVisual(Integer.parseInt(parts[0]), Long.parseLong(parts[1]),
                    Integer.parseInt(parts[2]), Long.parseLong(parts[3]), parts.length == 5 ? Integer.parseInt(parts[4]) : 26);
        } catch (IllegalArgumentException invalid) { return null; }
    }

    /** Map captured wind-up/recovery to native keyframes, independent of later phase edits. */
    public long millis(long now, float partialTick) {
        float fraction = Float.isFinite(partialTick) ? Math.clamp(partialTick, 0, 1) : 0;
        if (firedTick < 0) {
            if (duration == 0) return SHOT_MILLIS;
            double elapsed = elapsed(now, startTick, fraction);
            return (long) (Math.clamp(elapsed / duration, 0, 1) * SHOT_MILLIS);
        }
        double elapsed = elapsed(now, firedTick, fraction);
        if (recoveryTicks == 0 || elapsed >= recoveryTicks) return -1;
        return SHOT_MILLIS + (long) (elapsed / recoveryTicks * (END_MILLIS - SHOT_MILLIS));
    }
    private static double elapsed(long now, long start, float partial) {
        // Both validated starts and normal native times are nonnegative, so subtraction cannot overflow.
        return now < start ? 0 : (double) (now - start) + partial;
    }
    public ClockworkBossChargeVisual fired(long now) { return new ClockworkBossChargeVisual(sequence, startTick, duration, now, recoveryTicks); }
}
