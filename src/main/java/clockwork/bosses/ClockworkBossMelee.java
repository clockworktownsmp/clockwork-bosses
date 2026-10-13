package clockwork.bosses;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.warden.Warden;
import java.util.UUID;

/** A delayed native hit, not a targeting/navigation AI. One captured UUID and
 * game-time deadline per boss are serviced by its existing AI callback. */
public final class ClockworkBossMelee {
    public record Pending(UUID target, long dueTick, long generation, ClockworkBossSettings.Phase settings) {}
    private ClockworkBossMelee() {}

    /** Called only after native melee eligibility/cooldown gates. True means the
     * original hit is postponed; native doHurtTarget performs the eventual hit. */
    public static boolean postpone(Warden boss, Entity target, ClockworkBossSettings.Phase settings) {
        var state = ClockworkBossManager.stateFor(boss);
        if (state == null || state.resolvingMelee()) return false;
        if (state.pendingMelee() != null) return true;
        if (settings.meleeChargeTicks() == 0) return false;
        if (!(target instanceof LivingEntity)) return false;
        long now = boss.level().getGameTime();
        long due = now > Long.MAX_VALUE - settings.meleeChargeTicks() ? Long.MAX_VALUE : now + settings.meleeChargeTicks();
        // Native melee takes precedence at close range. Release an old beam
        // now, because the pending swing temporarily owns the shared AI callback.
        ClockworkBossSonicBoom.cancelCharge(boss, state);
        state.setPendingMelee(new Pending(target.getUUID(), due, state.subphaseGeneration(), settings));
        if (ClockworkBossDiagnostics.enabled()) ClockworkBossDiagnostics.begin(boss, target, "melee",
                "due_tick", due, "charge", settings.meleeChargeTicks(), "cooldown", settings.meleeCooldown(),
                "recovery_ticks", settings.meleeRecoveryTicks(), "damage_attribute", boss.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE));
        ((ClockworkBossMarker) boss).clockworkBosses$beginMeleeVisual(now, settings.meleeChargeTicks(), settings.meleeRecoveryTicks());
        return true;
    }

    /** Keep the captured deadline while either combatant moves. Target ownership
     * and phase validity are checked throughout; reach and visibility are checked
     * only at impact so a brief separation cannot restart the wind-up. Native
     * navigation continues unchanged. No chunks or entity references are retained. */
    public static boolean tick(Warden boss, ClockworkBossState state) {
        Pending pending = state.pendingMelee();
        if (pending == null) return false;
        ServerLevel level = (ServerLevel) boss.level();
        Entity entity = level.getEntity(pending.target());
        LivingEntity current = boss.getTarget();
        if (pending.generation() != state.subphaseGeneration() || state.dormant() || state.emerging() || state.roarInterlude()
                || !(entity instanceof LivingEntity target) || current != target || !target.isAlive() || target.isRemoved()
                || target instanceof net.minecraft.server.level.ServerPlayer player && player.hasDisconnected()
                || !boss.canTargetEntity(target)) {
            cancel(boss, state); return false;
        }
        boss.getLookControl().setLookAt(target.position());
        if (level.getGameTime() < pending.dueTick()) return true;
        // Commit damage against current positions, never the positions captured
        // at the start. An escaped/hidden opponent receives no hit, and clearing
        // this pending swing prevents it from landing later when they return.
        if (!boss.isWithinMeleeAttackRange(target) || !boss.getSensing().hasLineOfSight(target)) {
            cancel(boss, state); return false;
        }
        if (ClockworkBossDiagnostics.enabled()) ClockworkBossDiagnostics.event(boss, target, "melee_impact_eligible",
                "due_tick", pending.dueTick(), "same_target", true, "range", true, "line_of_sight", true);
        state.setPendingMelee(null); state.setResolvingMeleeSettings(pending.settings());
        try { boss.doHurtTarget(level, target); }
        finally {
            state.setResolvingMeleeSettings(null);
            // Another mod may veto the delayed call before our RETURN hook.
            // Still release the wind-up pose; cancelled calls never gain a count.
            ((ClockworkBossMarker) boss).clockworkBosses$finishMeleeVisual(level.getGameTime());
        }
        return true;
    }
    /** Discard the pending UUID/deadline and its synced wind-up together. */
    public static void cancel(Warden boss, ClockworkBossState state) {
        if (ClockworkBossDiagnostics.enabled() && state.pendingMelee() != null) {
            ClockworkBossDiagnostics.event(boss, boss.getTarget(), "melee_cancelled", "reason", "target_phase_range_visibility_or_state_changed");
            ClockworkBossDiagnostics.end(boss);
        }
        state.setPendingMelee(null);
        ((ClockworkBossMarker) boss).clockworkBosses$clearMeleeVisual();
    }
}
