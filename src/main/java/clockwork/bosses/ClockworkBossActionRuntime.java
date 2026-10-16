package clockwork.bosses;

import clockwork.bosses.api.BossAction;
import clockwork.bosses.api.BossActionContext;
import clockwork.bosses.api.BossActionRegistry;
import clockwork.bosses.api.BossActionSettings;
import clockwork.bosses.api.BossActionType;
import clockwork.bosses.api.BossEncounterSnapshot;
import clockwork.bosses.api.BossMobController;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.warden.Warden;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** One action owner attached to loaded encounter state. There is no global action
 * queue, extra tick listener, or delayed task retaining a world after unload. */
public final class ClockworkBossActionRuntime implements BossActionContext {
    private static final Logger LOGGER = LoggerFactory.getLogger("clockwork_bosses/actions");
    private Warden boss;
    private ClockworkBossState state;
    private BossAction action;
    private final BossActionType type;
    private final BossActionSettings settings;
    private final long generation, started;
    // A thread ID enforces the live-context contract without retaining a server
    // or world after cleanup. Immutable settings can still be copied to a worker.
    private final long serverThreadId = Thread.currentThread().threadId();
    private final List<Runnable> cleanup = new ArrayList<>();
    private BossEncounterSnapshot lastSnapshot;
    private boolean closed;
    private boolean pauseNative;
    private final ClockworkBossWardenController controller = new ClockworkBossWardenController(this);
    private ClockworkBossActionRuntime(Warden boss, ClockworkBossState state, BossActionType type, BossActionSettings settings) {
        this.boss = boss; this.state = state; this.type = type; this.settings = settings;
        this.generation = state.subphaseGeneration(); this.started = boss.level().getGameTime();
    }
    /** Establish ownership before start so a reentrant death/removal can close a
     * partially acquired scope. A failed generation stays closed until transition/reload. */
    public static void ensure(Warden boss, ClockworkBossState state) {
        if (!boss.level().getServer().isSameThread()) throw new IllegalStateException("Actions require the server thread");
        if (state.actionsStopping() || state.encounterEnded() || state.dormant() || state.emerging() || !boss.isAlive() || boss.isRemoved() || ClockworkBossManager.stateFor(boss) != state) return;
        state.initializeSubphase(boss.getRandom()); // Pick a random first child once, including chunk rehydration.
        var previous = state.actionRuntime();
        if (previous != null && previous.generation == state.subphaseGeneration()) return;
        if (previous != null) previous.stop(BossAction.StopReason.SUBPHASE_CHANGED);
        if (state.encounterEnded() || ClockworkBossManager.stateFor(boss) != state || !boss.isAlive() || boss.isRemoved()
                || previous != null && state.actionRuntime() != previous) return;
        var step = state.subphase();
        BossActionType type = BossActionRegistry.require(state.roarInterlude() ? ClockworkBossActions.ROAR
                : step == null ? ClockworkBossActions.COMBAT : step.actionType().id());
        BossActionSettings settings = state.roarInterlude() ? state.roarInterludeSettings()
                : step == null ? BossActionSettings.compile(type.settings(), new com.google.gson.JsonObject()) : step.actionSettings();
        ClockworkBossManager.applySubphaseAttributeValues(boss, state);
        if (ClockworkBossManager.stateFor(boss) != state || state.encounterEnded() || !boss.isAlive() || boss.isRemoved()) return;
        var runtime = new ClockworkBossActionRuntime(boss, state, type, settings);
        state.setActionRuntime(runtime);
        try {
            if (!ClockworkBossActions.WARDEN_CAPABILITIES.containsAll(type.requiredCapabilities()))
                throw new IllegalArgumentException("Warden does not support " + type.id());
            var action = java.util.Objects.requireNonNull(type.factory().get(), "Action factory returned null");
            if (!runtime.active()) return; // Factory callbacks may have removed the owner.
            runtime.action = action;
            runtime.pauseNative = action.pausesNativeBehaviors();
            if (!runtime.active()) return;
            if (runtime.pauseNative) runtime.pauseNativeBehaviors();
            if (!runtime.active()) return;
            action.start(runtime);
            if (runtime.active() && type.id().equals(ClockworkBossActions.RECOVERY)) state.beginRecovery(runtime.gameTime());
        } catch (RuntimeException | LinkageError | AssertionError failure) { runtime.fail(failure); }
    }
    public static void tick(Warden boss, ClockworkBossState state) {
        ensure(boss, state); var runtime = state.actionRuntime();
        if (runtime == null || !runtime.active()) return;
        try {
            runtime.controller.tick();
            if (!runtime.active()) return;
            // The controller services permitted native attacks for every action,
            // including addons. Actions need no access to the Warden's private
            // pending-hit/sonic implementation to allow combat alongside them.
            if (!runtime.pauseNative) {
                boolean meleeBusy = runtime.allows(ClockworkBossActions.MELEE) && ClockworkBossMelee.tick(boss, state);
                if (runtime.active() && !meleeBusy && runtime.allows(ClockworkBossActions.SONIC)) ClockworkBossSonicBoom.tick(boss, state);
            }
            if (!runtime.active()) return;
            BossAction.Result result = java.util.Objects.requireNonNull(runtime.action.tick(runtime), "Action tick returned null");
            if (result == BossAction.Result.COMPLETE && runtime.active()) {
                runtime.stop(BossAction.StopReason.COMPLETED);
                ClockworkBossManager.onActionCompleted(boss, state, runtime.generation);
            }
        } catch (RuntimeException | LinkageError | AssertionError failure) { runtime.fail(failure); }
    }
    private void fail(Throwable failure) {
        LOGGER.error("Boss action {} failed; this execution is stopped", type.id(), failure);
        stop(BossAction.StopReason.FAILED);
    }
    /** Close first, release every resource even when a callback fails, and clear
     * entity/world/state references before returning. Reentrant stops do nothing. */
    public void stop(BossAction.StopReason reason) {
        checkThread();
        if (closed) return;
        closed = true;
        ClockworkBossState owner = state;
        owner.beginActionStop();
        try {
            // Snapshot construction belongs inside the protected scope too:
            // an unexpected snapshot failure must not skip resource release.
            try { lastSnapshot = encounter(); }
            catch (RuntimeException | LinkageError | AssertionError failure) { LOGGER.error("Boss action {} snapshot capture failed", type.id(), failure); }
            // Native pending work belongs to this generation, even if an addon
            // permitted it. Release it before invoking addon cleanup callbacks.
            try { cancelNativeCharges(); }
            catch (RuntimeException | LinkageError | AssertionError failure) { LOGGER.error("Boss action {} native attack cleanup failed", type.id(), failure); }
            try { controller.stop(reason == BossAction.StopReason.COMPLETED); }
            catch (RuntimeException | LinkageError | AssertionError failure) { LOGGER.error("Boss action {} native animation cleanup failed", type.id(), failure); }
            if (action != null) try { action.stop(this, reason); }
            catch (RuntimeException | LinkageError | AssertionError failure) { LOGGER.error("Boss action {} stop failed", type.id(), failure); }
        } finally {
            try {
                for (int index = cleanup.size() - 1; index >= 0; index--) {
                    try { cleanup.get(index).run(); }
                    catch (RuntimeException | LinkageError | AssertionError failure) { LOGGER.error("Boss action {} resource cleanup failed", type.id(), failure); }
                }
            } finally {
                // Even a fatal VM error must not keep our live references in a
                // context retained by somebody else's addon.
                cleanup.clear(); action = null; boss = null; state = null;
                owner.endActionStop();
            }
        }
    }
    boolean closed() { return closed; }
    long lastGameTime() { return boss == null ? lastSnapshot == null ? started : lastSnapshot.gameTime() : boss.level().getGameTime(); }
    public boolean active() {
        checkThread();
        return !closed && state != null && !state.encounterEnded() && state.actionRuntime() == this && ClockworkBossManager.stateFor(boss) == state
                && state.subphaseGeneration() == generation && boss.isAlive() && !boss.isRemoved();
    }
    public boolean allows(Identifier attack) {
        // Factory/permission callbacks can reenter native hooks before the
        // action has been attached. Reject that query without failing the owner.
        if (!active() || action == null) return false;
        try { return action.allowsNativeAttack(attack); }
        catch (RuntimeException | LinkageError | AssertionError failure) { fail(failure); return false; }
    }
    public boolean pausesNativeBehaviors() {
        return closed || pauseNative;
    }
    boolean ownsRoar() { return controller.ownsRoar(); }
    boolean nativeRoarFinished() { return controller.roarFinished(); }
    void finishRoar() { controller.finishRoar(); }
    clockwork.bosses.api.BossRoarSound roarSound() { return controller.roarSound(); }
    Warden boss() { if (boss == null) throw new IllegalStateException("Action is closed"); return boss; }
    ClockworkBossState state() { if (state == null) throw new IllegalStateException("Action is closed"); return state; }
    void cancelNativeCharges() {
        try {
            if (state.pendingMelee() != null && state.pendingMelee().generation() == generation) ClockworkBossMelee.cancel(boss, state);
        } finally {
            if (state != null && state.sonicCharge() != null && state.sonicCharge().generation() == generation) ClockworkBossSonicBoom.cancelCharge(boss, state);
        }
    }
    void pauseNativeBehaviors() {
        cancelNativeCharges();
        if (!active()) return;
        boss.getBrain().stopAll(level(), boss);
        if (!active()) return; // A native stop callback may remove this encounter.
        boss.getNavigation().stop();
        boss.getBrain().eraseMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.WALK_TARGET);
    }
    void checkThread() {
        if (Thread.currentThread().threadId() != serverThreadId) throw new IllegalStateException("Action context requires the server thread");
    }
    void checkActive() { checkThread(); if (!active()) throw new IllegalStateException("Action has been interrupted"); }
    @Override public BossMobController controller() {
        checkThread(); return controller;
    }
    @Override public BossActionSettings settings() { return settings; }
    @Override public BossEncounterSnapshot encounter() {
        checkThread();
        return state == null ? lastSnapshot : ClockworkBossManager.encounterSnapshot(boss, state);
    }
    @Override public ServerLevel level() { checkThread(); return (ServerLevel) boss().level(); }
    @Override public long gameTime() { return level().getGameTime(); }
    @Override public long elapsedTicks() { return Math.max(0, gameTime() - started); }
    @Override public Optional<LivingEntity> target() {
        checkActive(); LivingEntity target = boss.getTarget();
        return target != null && target.isAlive() && !target.isRemoved() && target.level() == level() && boss.canTargetEntity(target)
                && (!(target instanceof ServerPlayer player) || isParticipant(player)) ? Optional.of(target) : Optional.empty();
    }
    @Override public List<ServerPlayer> participants() {
        checkActive(); List<ServerPlayer> players = new ArrayList<>();
        for (var id : state.bossBarPlayerIds()) {
            var player = level().getServer().getPlayerList().getPlayer(id);
            if (player != null && isParticipant(player)) players.add(player);
        }
        return List.copyOf(players);
    }
    @Override public boolean isParticipant(ServerPlayer player) {
        checkActive();
        return player.level() == level() && player.isAlive() && !player.isSpectator() && !player.hasDisconnected()
                && boss.canTargetEntity(player) && ClockworkBossManager.isPlayerInArena(state.uuid(), player);
    }
    @Override public void onCleanup(Runnable callback) {
        checkActive(); if (cleanup.size() >= 64) throw new IllegalStateException("Action exceeds 64 owned resources");
        cleanup.add(java.util.Objects.requireNonNull(callback));
    }
    @Override public void ownEntity(Entity entity) {
        checkActive();
        if (entity == boss || entity instanceof net.minecraft.world.entity.player.Player || entity.level() != level())
            throw new IllegalArgumentException("Own only temporary non-player entities in this encounter's world");
        if (entity instanceof ClockworkBossMarker marker && marker.clockworkBosses$isClockworkBoss())
            throw new IllegalArgumentException("An action cannot own another boss");
        var marker = (ClockworkBossTemporaryEntity) entity;
        if (marker.clockworkBosses$isTemporary()) throw new IllegalArgumentException("Entity already belongs to an action");
        var id = entity.getUUID(); ServerLevel world = level();
        onCleanup(() -> { var owned = world.getEntity(id); if (owned == entity && !owned.isRemoved()) owned.discard(); });
        marker.clockworkBosses$setTemporary();
    }
    @Override public boolean damage(ServerPlayer target, DamageSource source, float amount) {
        checkActive();
        if (!Float.isFinite(amount) || amount < 0 || amount > 1_000_000 || !isParticipant(target)) return false;
        Warden attacker = boss; ClockworkBossState owner = state; float before = target.getHealth();
        boolean accepted;
        try (var observation = ClockworkBossVitals.action(attacker, target, type.id(), source)) {
            accepted = target.hurtServer(level(), source, amount);
        }
        if (accepted) {
            ClockworkBossManager.onAttackLanded(attacker, target, type.id(), Math.max(0, before - target.getHealth()));
            if (active()) ClockworkBossManager.onSuccessfulAttack(attacker);
        }
        if (active()) ClockworkBossTargets.afterAttack(attacker, owner, target);
        return accepted;
    }
}
