package clockwork.bosses;

/** One atomic synchronized swing timeline. Reuse native 26.3 attack keyframes
 * (about 333 ms) with the impact pose at 133 ms; durations describe wind-up and
 * recovery independently. Shared game time supports late tracking clients. */
public record ClockworkBossMeleeVisual(long startTick, int chargeTicks, int recoveryTicks, long hitTick) {
    public ClockworkBossMeleeVisual {
        if (startTick < 0 || chargeTicks < 0 || chargeTicks > 12000 || recoveryTicks < 0 || recoveryTicks > 12000
                || hitTick < -1 || hitTick >= 0 && hitTick < startTick) throw new IllegalArgumentException("Invalid swing timing");
    }
    public String encode() { return startTick + ":" + chargeTicks + ":" + recoveryTicks + ":" + hitTick; }
    public ClockworkBossMeleeVisual hit(long now) { return new ClockworkBossMeleeVisual(startTick, chargeTicks, recoveryTicks, now); }
    public static ClockworkBossMeleeVisual decode(String text) {
        if (text == null || text.length() > 80 || text.isEmpty()) return null;
        try {
            String[] parts = text.split(":", -1); if (parts.length != 4) return null;
            return new ClockworkBossMeleeVisual(Long.parseLong(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Long.parseLong(parts[3]));
        } catch (IllegalArgumentException invalid) { return null; }
    }
    public long millis(long now, float partial) {
        double fraction = Float.isFinite(partial) ? Math.clamp(partial, 0, 1) : 0;
        if (hitTick < 0) return chargeTicks == 0 ? 133 : (long) (Math.clamp((Math.max(0, now - startTick) + fraction) / chargeTicks, 0, 1) * 133);
        double elapsed = Math.max(0, now - hitTick) + fraction;
        if (recoveryTicks == 0 || elapsed >= recoveryTicks) return -1;
        return 133 + (long) (elapsed / recoveryTicks * 200);
    }
}
