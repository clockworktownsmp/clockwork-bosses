package clockwork.bosses;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Unit;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.warden.AngerLevel;
import net.minecraft.world.entity.monster.warden.Warden;

/** Event-driven opponent rotation; native Warden navigation/attack behavior still owns the next attack. */
public final class ClockworkBossTargets {
    private ClockworkBossTargets() {}

    /** Prefer a different eligible player after a completed attack; a solo opponent is never abandoned. */
    public static void afterAttack(Warden warden, ClockworkBossState state, Entity attacked) {
        afterAttack(warden, state, attacked, null);
    }

    /** A beam uses its captured horizontal/vertical reach. A melee swing uses
     * follow range. Both count completions before testing the shared hold gate. */
    public static void afterAttack(Warden warden, ClockworkBossState state, Entity attacked,
                                   ClockworkBossSettings.SonicBoom shot) {
        if (!(warden.level() instanceof ServerLevel level) || warden.isRemoved() || !warden.isAlive() || warden.isNoAi()
                || state.dormant() || state.emerging() || state.currentPhase() == null) return;
        var policy = state.combatSettings().targeting();
        var current = warden.getTarget();
        var currentId = current == null ? null : current.getUUID();
        var attackedId = attacked == null ? null : attacked.getUUID();
        long now = level.getGameTime();
        state.rememberTarget(currentId, now);
        // Damage callbacks can already have changed the target. Do not rotate
        // that new opponent again or count the previous opponent's hit for them.
        if (currentId == null || !currentId.equals(attackedId)) return;
        int attacks = state.recordCompletedAttack();
        if (ClockworkBossDiagnostics.enabled()) ClockworkBossDiagnostics.event(warden, attacked, "retarget_decision",
                "completed_attacks", attacks, "min_attacks", policy.minAttacks(), "max_attacks", policy.maxAttacks(),
                "min_target_ticks", policy.minTargetTicks(), "switch_chance", policy.switchChance(), "mode", policy.mode().name(),
                "hold_active", state.targetHoldActive(now, policy.minTargetTicks()));
        boolean validCurrent = current.isAlive() && !current.isRemoved() && warden.canTargetEntity(current)
                && (!(current instanceof ServerPlayer player) || !player.hasDisconnected());
        // A killing blow must not hold a dead target or wait for extra attacks.
        if (validCurrent && (state.targetHoldActive(now, policy.minTargetTicks()) || attacks < policy.minAttacks())) {
            if (ClockworkBossDiagnostics.enabled()) ClockworkBossDiagnostics.event(warden, attacked, "retarget_retained", "reason", "hold_or_minimum_attacks");
            return;
        }
        if (validCurrent && attacks < policy.maxAttacks() && !ClockworkBossTargeting.switchOn(policy.switchChance(),
                policy.switchChance() <= 0 || policy.switchChance() >= 1 ? 0 : warden.getRandom().nextDouble())) {
            if (ClockworkBossDiagnostics.enabled()) ClockworkBossDiagnostics.event(warden, attacked, "retarget_retained", "reason", "chance");
            return;
        }
        // Bound even externally modified follow-range attributes. Query native
        // loaded entity sections, not every server player or unloaded chunks.
        double rawRange = warden.getAttributeValue(Attributes.FOLLOW_RANGE);
        double range = shot == null ? (Double.isNaN(rawRange) ? 0 : Math.clamp(rawRange, 0, 256)) : shot.horizontalRange();
        double verticalRange = shot == null ? range : shot.verticalRange();
        if (range <= 0) return;
        var nearby = warden.getBoundingBox().inflate(range, verticalRange, range);
        var area = ClockworkBossManager.visibilityQuery(state, warden);
        if (area == null) {
            // Invalid/mismatched configured regions must not silently broaden
            // the encounter to all nearby players. Only a genuinely unset region falls back.
            if (state.areas().visibilityConfigured()) {
                if (ClockworkBossDiagnostics.enabled()) ClockworkBossDiagnostics.event(warden, attacked, "retarget_retained", "reason", "invalid_or_other_dimension_area");
                return;
            }
            area = nearby;
        }
        if (!area.intersects(nearby)) return;
        area = area.intersect(nearby);
        var candidates = level.getEntitiesOfClass(ServerPlayer.class, area,
                // Identity also excludes a replacement player entity during respawn/dimension changes.
                player -> !player.getUUID().equals(currentId) && !player.hasDisconnected()
                        && warden.canTargetEntity(player) && (shot == null ? player.distanceToSqr(warden) <= range * range
                        : warden.closerThan(player, range, verticalRange))
                        && ClockworkBossManager.insideVisibilityRadius(state, warden, player)
                        // Reject cheap geometry/eligibility failures before a raycast.
                        && (shot == null || !shot.lineOfSight() || ClockworkBossSonicBoom.hasLineOfSight(level, warden, player)));
        if (candidates.isEmpty()) {
            if (ClockworkBossDiagnostics.enabled()) ClockworkBossDiagnostics.event(warden, attacked, "retarget_no_alternative");
            return; // Keep the native current target when there is no alternative.
        }
        ServerPlayer next = candidates.get(0);
        if (policy.mode() == ClockworkBossTargeting.Mode.RANDOM) {
            next = candidates.get(warden.getRandom().nextInt(candidates.size()));
        } else {
            double best = next.distanceToSqr(warden);
            for (int i = 1; i < candidates.size(); i++) {
                ServerPlayer candidate = candidates.get(i);
                double distance = candidate.distanceToSqr(warden);
                if (ClockworkBossTargeting.better(policy.mode(), distance, best)
                        || distance == best && candidate.getUUID().compareTo(next.getUUID()) < 0) {
                    next = candidate;
                    best = distance;
                }
            }
        }
        if (next == warden.getTarget()) return;
        assign(warden, state, next);
    }

    /** Shared native assignment for attack rotation and burrow emergence. Keep
     * anger/cooldown behavior identical and honor other mods' assignment vetoes. */
    static void assign(Warden warden, ClockworkBossState state, ServerPlayer next) {
        long generation = state.subphaseGeneration();
        var brain = warden.getBrain();
        boolean hadCooldown = brain.hasMemoryValue(MemoryModuleType.SONIC_BOOM_COOLDOWN);
        long remainingCooldown = hadCooldown ? brain.getTimeUntilExpiry(MemoryModuleType.SONIC_BOOM_COOLDOWN) : 0;
        // Native Fight activity rejects non-angry targets. Raise the selected
        // player's native anger with headroom for normal decay; native MAX_ANGER
        // remains the cap. This does not create a separate aggro registry.
        int wantedAnger = AngerLevel.ANGRY.getMinimumAnger() * 2;
        int existingAnger = warden.getAngerManagement().getActiveAnger(next);
        if (existingAnger < wantedAnger) warden.increaseAngerAt(next, wantedAnger - existingAnger, false);
        if (!stillOwns(warden, state, generation)) return;
        warden.setAttackTarget(next);
        if (!stillOwns(warden, state, generation)) return;
        if (ClockworkBossDiagnostics.enabled()) ClockworkBossDiagnostics.event(warden, next, "retarget_assignment", "accepted", warden.getTarget() == next);
        // setAttackTarget normally inserts a 200-tick first-acquisition delay.
        // Rotation must preserve the attack's own native/configured cooldown.
        if (hadCooldown) brain.setMemoryWithExpiry(MemoryModuleType.SONIC_BOOM_COOLDOWN, Unit.INSTANCE, remainingCooldown);
        else brain.eraseMemory(MemoryModuleType.SONIC_BOOM_COOLDOWN);
        // Another mod may veto or redirect assignment. Do not aim/navigation-
        // reset toward a player who did not actually become this boss's target.
        if (warden.getTarget() != next) return;
        ClockworkBossSonicBoom.cancelCharge(warden, state);
        brain.eraseMemory(MemoryModuleType.WALK_TARGET);
        warden.getNavigation().stop();
        warden.getLookControl().setLookAt(next);
    }

    /** Native calls may invoke another mod synchronously. A removed owner or
     * changed subphase must not receive this assignment's cooldown/navigation edits. */
    private static boolean stillOwns(Warden warden, ClockworkBossState state, long generation) {
        return ClockworkBossManager.stateFor(warden) == state && !state.encounterEnded()
                && state.subphaseGeneration() == generation && warden.isAlive() && !warden.isRemoved();
    }
}
