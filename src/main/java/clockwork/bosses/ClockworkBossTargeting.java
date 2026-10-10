package clockwork.bosses;

/** Small pure targeting rules shared by production and deterministic checks. */
public final class ClockworkBossTargeting {
    private ClockworkBossTargeting() {}
    public enum Mode { NEAREST, RANDOM, FARTHEST }

    /** Exact never/always endpoints; validated settings bound the intermediate probability to 0-1. */
    public static boolean switchOn(double chance, double sample) {
        return chance >= 1 || chance > 0 && sample < chance;
    }

    /** Compare squared distances without roots; equal distances are resolved by UUID in the caller. */
    public static boolean better(Mode mode, double candidate, double previous) {
        return mode == Mode.NEAREST ? candidate < previous : candidate > previous;
    }
}
