package clockwork.bosses;

/** Implemented only by attribute instances; ownership is a boolean, never an entity reference or global map. */
public interface ClockworkBossHealthAttribute {
    /** Enable the boss health range on that boss's MAX_HEALTH instance, and invalidate its native cached value. */
    void clockworkBosses$setBossHealth(boolean enabled);
}
