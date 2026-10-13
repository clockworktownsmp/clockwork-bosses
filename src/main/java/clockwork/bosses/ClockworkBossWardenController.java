package clockwork.bosses;

import clockwork.bosses.api.BossMobController;
import clockwork.bosses.api.BossRoarSound;
import java.util.Set;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Unit;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.monster.warden.WardenAi;
import net.minecraft.world.phys.Vec3;

/** Warden-specific native backend. Actions, including core registrations, call
 * the public controller contract instead of inspecting private encounter state.
 * One animation belongs to one action generation; no timer survives its scope. */
final class ClockworkBossWardenController implements BossMobController {
    private enum Animation { NONE, ROAR, BURROW }
    private final ClockworkBossActionRuntime context;
    private Animation animation = Animation.NONE;
    private BossRoarSound roarSound = BossRoarSound.VANILLA;
    private boolean roarFinished, buried, emerging, burrowFinished;
    private int undergroundTicks;
    private long animationStarted;
    private double requestedDepth;
    private ClockworkBossBurrowCheckpoint checkpoint;
    private UUID burrowTarget;
    ClockworkBossWardenController(ClockworkBossActionRuntime context) { this.context = context; }
    @Override public Warden mob() { context.checkThread(); return context.boss(); }
    @Override public Set<Identifier> capabilities() { return ClockworkBossActions.WARDEN_CAPABILITIES; }
    private void acquire(Animation next) {
        context.checkActive();
        if (animation != Animation.NONE) throw new IllegalStateException("Use one native animation per action execution");
        animation = next; animationStarted = context.gameTime();
    }
    @Override public void startRoar(BossRoarSound sound) {
        context.checkActive();
        if (context.pausesNativeBehaviors()) throw new IllegalStateException("Roar requires native behavior execution");
        roarSound = java.util.Objects.requireNonNull(sound);
        acquire(Animation.ROAR); context.cancelNativeCharges();
    }
    @Override public boolean roarFinished() { context.checkActive(); return roarFinished; }
    boolean ownsRoar() { return animation == Animation.ROAR && context.active(); }
    void finishRoar() { if (ownsRoar()) roarFinished = true; }
    BossRoarSound roarSound() { return roarSound; }
    @Override public void startBurrow(int ticks, double depth) {
        context.checkActive();
        if (!context.pausesNativeBehaviors()) throw new IllegalStateException("Burrow requires paused native behavior");
        if (ticks < 1 || ticks > 12000 || !Double.isFinite(depth) || depth < 1 || depth > 32)
            throw new IllegalArgumentException("Burrow requires 1–12000 underground ticks and depth 1–32");
        acquire(Animation.BURROW); undergroundTicks = ticks; requestedDepth = depth;
        Warden boss = mob(); checkpoint = ClockworkBossBurrowCheckpoint.capture(boss);
        burrowTarget = context.target().filter(ServerPlayer.class::isInstance).map(player -> player.getUUID()).orElse(null);
        // Acquire restoration before mutation; journal it independently if
        // Minecraft saves this entity before unloading the interrupted action.
        context.onCleanup(() -> {
            try { checkpoint.restore(boss); } // Reads the latest committed surface, including successful relocation.
            finally { checkpoint = null; burrowTarget = null; }
        });
        ((ClockworkBossMarker) boss).clockworkBosses$setBurrowCheckpoint(checkpoint);
        boss.setPose(Pose.DIGGING);
    }
    @Override public boolean burrowFinished() { context.checkActive(); return burrowFinished; }
    /** Called only from the owner's existing AI step. Native roar completion is
     * observed by its original hook; burrow uses bounded game-time deadlines. */
    void tick() {
        if (animation == Animation.ROAR) {
            if (!roarFinished) ClockworkBossManager.requestRoarInterlude(context.boss(), context.state());
        } else if (animation == Animation.BURROW && !burrowFinished) {
            Warden boss = context.boss(); long elapsed = Math.max(0, context.gameTime() - animationStarted);
            if (elapsed < 40) return; // Native digging keyframes last forty ticks.
            var origin = checkpoint.origin();
            if (!buried) {
                buried = true;
                double scale = boss.getAttributeValue(Attributes.SCALE);
                scale = Double.isFinite(scale) ? Math.clamp(scale, .25, 4) : 1;
                double depth = Math.min(32, Math.max(requestedDepth, Math.ceil(5 * Math.max(1, scale))));
                double y = Math.max(context.level().getMinY(), origin.y - depth);
                boss.snapTo(origin.x, y, origin.z, boss.getYRot(), boss.getXRot());
                if (!context.active()) return; // Relocation can synchronously unload/remove the owner.
                boss.setDeltaMovement(Vec3.ZERO); boss.resetFallDistance();
                boss.setInvisible(true); boss.setNoGravity(true); boss.setSilent(true);
            }
            long underground = 40L + undergroundTicks;
            if (elapsed < underground) return;
            if (!emerging) {
                ServerPlayer target = emergenceTarget();
                Vec3 destination = target == null ? null
                        : ClockworkBossBurrowDestination.find(boss, context.state(), target, origin);
                if (!context.active()) return; // Native/mod collision callbacks can interrupt the owner.
                // Assign through native targeting and respect other mods' vetoes.
                // An invalidated/replaced target must not produce a surprise relocation.
                if (destination != null && target != boss.getTarget()) {
                    ClockworkBossTargets.assign(boss, context.state(), target);
                    if (!context.active()) return;
                }
                if (destination == null || target != boss.getTarget() || !context.isParticipant(target)) destination = origin;
                // Publish the journal before moving. Chunk-save or interruption
                // during emergence now recovers here instead of jumping back.
                checkpoint = checkpoint.atSurface(destination);
                ((ClockworkBossMarker) boss).clockworkBosses$setBurrowCheckpoint(checkpoint);
                emerging = true; boss.snapTo(destination.x, destination.y, destination.z, boss.getYRot(), boss.getXRot());
                if (!context.active()) return;
                boss.setDeltaMovement(Vec3.ZERO); boss.resetFallDistance();
                if (target != null && target == boss.getTarget() && context.isParticipant(target)) {
                    float facing = (float) Math.toDegrees(Math.atan2(target.getZ() - destination.z, target.getX() - destination.x)) - 90;
                    boss.setYRot(facing); boss.setYBodyRot(facing); boss.setYHeadRot(facing);
                    boss.getLookControl().setLookAt(target);
                }
                boss.setInvisible(checkpoint.invisible()); boss.setSilent(checkpoint.silent()); boss.setPose(Pose.EMERGING);
                if (!context.active()) return;
                boss.getBrain().setMemoryWithExpiry(MemoryModuleType.IS_EMERGING, Unit.INSTANCE, WardenAi.EMERGE_DURATION);
            }
            burrowFinished = elapsed >= underground + WardenAi.EMERGE_DURATION;
        }
    }
    /** Resolve only when emergence is due, using current positions and the
     * existing targeting policy. Keep UUIDs, never a player reference, while waiting. */
    private ServerPlayer emergenceTarget() {
        var current = context.target().orElse(null);
        if (current instanceof ServerPlayer player) return player;
        var captured = burrowTarget == null ? null : context.level().getServer().getPlayerList().getPlayer(burrowTarget);
        if (captured != null && context.isParticipant(captured)) return captured;
        var players = context.participants();
        if (players.isEmpty()) return null;
        var mode = context.state().combatSettings().targeting().mode();
        if (mode == ClockworkBossTargeting.Mode.RANDOM) return players.get(mob().getRandom().nextInt(players.size()));
        ServerPlayer best = players.get(0);
        for (int i = 1; i < players.size(); i++) {
            ServerPlayer candidate = players.get(i);
            double distance = candidate.position().distanceToSqr(checkpoint.origin());
            double previous = best.position().distanceToSqr(checkpoint.origin());
            if (ClockworkBossTargeting.better(mode, distance, previous)
                    || distance == previous && candidate.getUUID().compareTo(best.getUUID()) < 0) best = candidate;
        }
        return best;
    }
    /** Native stop can call back reentrantly. Runtime ownership is already closed
     * before this runs, so an interrupted roar cannot advance another action. */
    void stop(boolean completed) {
        if (animation != Animation.ROAR || completed && roarFinished) return;
        Warden boss = context.boss(); var brain = boss.getBrain();
        try {
            // Native Roar.stop restores its attack target and our existing hook
            // retains the elapsed sonic cooldown. Keep ROAR_TARGET until it ran.
            if (boss.hasPose(Pose.ROARING) || brain.isActive(net.minecraft.world.entity.schedule.Activity.ROAR))
                brain.stopAll(context.level(), boss);
        } finally {
            brain.eraseMemory(MemoryModuleType.ROAR_TARGET);
            if (boss.hasPose(Pose.ROARING)) boss.setPose(Pose.STANDING);
        }
    }
}
