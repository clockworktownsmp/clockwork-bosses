package clockwork.bosses;

/** Internal marker for explicitly action-owned temporary entities. They are
 * excluded from native saves, so another chunk unloading cannot orphan them. */
public interface ClockworkBossTemporaryEntity {
    boolean clockworkBosses$isTemporary();
    void clockworkBosses$setTemporary();
}
