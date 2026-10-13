package clockwork.bosses.api;

/** One server-owned action instance per execution. Factories must return a fresh
 * instance: fields belong to this boss, never another boss or a static timer.
 * All callbacks run on the server thread. Tick reuses the existing boss AI step. */
public interface BossAction {
    enum Result { RUNNING, COMPLETE }
    enum StopReason { COMPLETED, PHASE_CHANGED, SUBPHASE_CHANGED, RELOADED, DIED, REMOVED, UNLOADED, SERVER_STOPPED, FAILED }

    /** Acquire resources here; register cleanup before a later operation can fail. */
    default void start(BossActionContext context) {}
    /** Return COMPLETE once. Do not schedule a second repeating task for the action. */
    Result tick(BossActionContext context);
    /** Called once, including after a partially failed start. Cleanup scopes close
     * even when this method throws. COMPLETED also requires releasing owned resources. */
    default void stop(BossActionContext context, StopReason reason) {}
    /** Cheap, side-effect-free permission queried by native attack hooks and the
     * controller's pending-melee/sonic service. Return false to reject combat.
     * Coexistence also requires pausesNativeBehaviors() == false; the core still
     * enforces phase settings, target validity and cooldowns. */
    default boolean allowsNativeAttack(net.minecraft.resources.Identifier attack) { return false; }
    /** Sampled once before start. Native sensors/memory expiry keep running while
     * behavior execution pauses. Return false for an action allowing native combat. */
    default boolean pausesNativeBehaviors() { return true; }
}
