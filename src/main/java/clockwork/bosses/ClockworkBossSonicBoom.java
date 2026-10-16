package clockwork.bosses;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.util.Unit;
import net.minecraft.world.entity.EntityAttachment;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Server-side Sonic Boom implementation used only by Clockwork Bosses. */
public final class ClockworkBossSonicBoom {
    private ClockworkBossSonicBoom() {}

    /** New charges use the current phase. An uninterrupted charge retains its
     * complete start-time definition; action cleanup cancels an interrupted one. */
    public static void tick(Warden warden, ClockworkBossState state) {
        if (state.actionRuntime() != null && !state.actionRuntime().allows(ClockworkBossActions.SONIC)) {
            cancelCharge(warden, state); return;
        }
        ServerLevel level = (ServerLevel) warden.level();
        ClockworkBossSettings.Phase phase = state.combatSettings();
        ClockworkBossSonicCharge charge = state.sonicCharge();
        ClockworkBossSettings.SonicBoom settings = charge == null ? phase.sonic() : charge.settings();

        if (!settings.enabled()) {
            cancelCharge(warden, state);
            return;
        }

        LivingEntity target = warden.getTarget();
        // Close targets favor melee only when this phase actually permits melee.
        // Apply the same rule to charge entry and charge cancellation: moving
        // closer must not create a safe zone against a Sonic-Boom-only boss.
        boolean yieldToMelee = (charge == null ? phase.meleeEnabled()
                && (state.actionRuntime() == null || state.actionRuntime().allows(ClockworkBossActions.MELEE)) : charge.yieldsToMelee()) && target != null
                && warden.isWithinMeleeAttackRange(target);

        if (charge != null) {
            if (!validTarget(warden, target, settings)
                    || !target.getUUID().equals(charge.targetUuid())
                    || yieldToMelee
                    || (settings.lineOfSight()
                    && !hasLineOfSight(level, warden, target))) {
                cancelCharge(warden, state);
                return;
            }

            warden.getLookControl().setLookAt(target.position());

            // One world-time deadline replaces a mutable countdown. A switched
            // target cancels the charge so a new player cannot receive an instant hit.
            if (charge.due(level.getGameTime())) {
                fire(warden, state, target);
            }

            return;
        }

        if (warden.getBrain().hasMemoryValue(MemoryModuleType.SONIC_BOOM_COOLDOWN)
                || !validTarget(warden, target, settings)
                || yieldToMelee) {
            return;
        }

        if (settings.lineOfSight()
                && !hasLineOfSight(level, warden, target)) {
            return;
        }

        startCharge(warden, state, target);
    }

    /** Retains native charge feedback; one world-time deadline and target UUID own the custom hit. */
    private static void startCharge(
            Warden warden,
            ClockworkBossState state,
            LivingEntity target) {
        ClockworkBossSettings.SonicBoom settings = state.combatSettings().sonic();
        int chargeTime = settings.chargeTime();

        state.startSonicCharge(target.getUUID(), warden.level().getGameTime(), state.combatSettings());
        if (ClockworkBossDiagnostics.enabled()) ClockworkBossDiagnostics.begin(warden, target, "sonic",
                "damage", settings.damage(), "charge", chargeTime, "cooldown", settings.cooldown(),
                "horizontal_range", settings.horizontalRange(), "vertical_range", settings.verticalRange(),
                "requires_los", settings.lineOfSight(), "recovery_ticks", settings.recoveryTicks());

        warden.getLookControl().setLookAt(target.position());
        // Native keyframes are sampled from one synced captured timeline, rather
        // than event 62's receipt-time animation (which cannot support late tracking).
        ((ClockworkBossMarker) warden).clockworkBosses$beginSonicVisual(warden.level().getGameTime(), chargeTime, settings.recoveryTicks());
        warden.playSound(SoundEvents.WARDEN_SONIC_CHARGE, 3.0F, 1.0F);

        if (chargeTime <= 0) {
            fire(warden, state, target);
        }
    }

    /** Rechecks the target, emits a bounded native beam, and applies native damage plus configured knockback. */
    private static void fire(
            Warden warden,
            ClockworkBossState state,
            LivingEntity target) {
        ServerLevel level = (ServerLevel) warden.level();
        ClockworkBossSonicCharge charge = state.sonicCharge();
        if (charge == null) return;
        ClockworkBossSettings.SonicBoom settings = charge.settings();
        long generation = charge.generation() >= 0 ? charge.generation() : state.subphaseGeneration();

        if (!validTarget(warden, target, settings)) {
            cancelCharge(warden, state);
            return;
        }

        if (settings.lineOfSight()
                && !hasLineOfSight(level, warden, target)) {
            cancelCharge(warden, state);
            return;
        }

        Vec3 source = warden.position().add(
                warden.getAttachments().get(
                        EntityAttachment.WARDEN_CHEST,
                        0,
                        warden.getYRot()
                )
        );
        Vec3 delta = target.getEyePosition().subtract(source);
        Vec3 direction = delta.normalize();
        int steps = Mth.floor(delta.length()) + 7;
        // Vanilla's one-particle-per-block trail is preserved at normal ranges.
        // Extreme configured ranges spread at most 64 samples along the same ray.
        int samples = Math.min(steps - 1, 64);
        for (int i = 1; i <= samples; ++i) {
            double distance = i * (steps - 1.0) / samples;
            Vec3 particlePos = source.add(direction.scale(distance));
            level.sendParticles(
                    net.minecraft.core.particles.ParticleTypes.SONIC_BOOM,
                    particlePos.x,
                    particlePos.y,
                    particlePos.z,
                    1,
                    0.0,
                    0.0,
                    0.0,
                    0.0
            );
        }

        warden.playSound(SoundEvents.WARDEN_SONIC_BOOM, 3.0F, 1.0F);
        ((ClockworkBossMarker) warden).clockworkBosses$finishSonicVisual(level.getGameTime());

        boolean capture = ClockworkBossDiagnostics.enabled();
        float before = target.getHealth();
        Vec3 velocity = capture ? target.getDeltaMovement() : null;
        if (capture) ClockworkBossDiagnostics.event(warden, target, "sonic_native_attempt", "hp_before", before, "damage", settings.damage(), "impact_eligible", true);
        var sourceDamage = level.damageSources().sonicBoom(warden);
        boolean accepted;
        try (var observation = ClockworkBossVitals.action(warden, target, ClockworkBossActions.SONIC, sourceDamage)) {
            accepted = target.hurtServer(level, sourceDamage, (float) settings.damage());
        }
        if (accepted) {
            ClockworkBossManager.onAttackLanded(warden, target, ClockworkBossActions.SONIC, Math.max(0, before - target.getHealth()));
            ClockworkBossManager.onSuccessfulAttack(warden);
            double resistance = Math.max(
                    0.0,
                    target.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE)
            );
            double multiplier = Math.max(0.0, 1.0 - resistance);

            double horizontal = settings.horizontalKnockback()
                    * multiplier;
            double vertical = settings.verticalKnockback()
                    * multiplier;

            target.push(
                    direction.x() * horizontal,
                    direction.y() * vertical,
                    direction.z() * horizontal
            );
        }

        if (capture) ClockworkBossDiagnostics.event(warden, target, "sonic_native_result", "accepted", accepted,
                "hp_before", before, "hp_after", target.getHealth(),
                "velocity_delta_x", target.getDeltaMovement().x - velocity.x,
                "velocity_delta_y", target.getDeltaMovement().y - velocity.y,
                "velocity_delta_z", target.getDeltaMovement().z - velocity.z);
        state.resetSonicBoomCharge();
        // Keep vanilla's brain memory consistent even though the vanilla
        // SonicBoom behavior itself is disabled for this Clockwork Boss.
        warden.getBrain().setMemoryWithExpiry(
                MemoryModuleType.SONIC_BOOM_COOLDOWN,
                Unit.INSTANCE,
                (long) Math.max(settings.cooldown(), settings.recoveryTicks())
        );
        // Rotate only after the beam finishes and its cooldown is installed,
        // so the next player cannot inherit a pending/instant charge.
        ClockworkBossManager.onRangedAttackCompleted(warden, target, settings, generation);
    }

    /** Clears the one target/deadline charge; native cooldown memory remains authoritative. */
    public static void cancelCharge(Warden warden, ClockworkBossState state) {
        if (state.sonicCharge() == null) return; // A finished shot's normal recoil may continue while targets rotate.
        if (ClockworkBossDiagnostics.enabled()) {
            ClockworkBossDiagnostics.event(warden, warden.getTarget(), "sonic_cancelled", "reason", "target_phase_range_visibility_or_state_changed");
            ClockworkBossDiagnostics.end(warden);
        }
        state.resetSonicBoomCharge();
        ((ClockworkBossMarker) warden).clockworkBosses$clearSonicVisual();
    }

    /** Reuses native target eligibility and horizontal/vertical range checks, without selecting targets. */
    private static boolean validTarget(Warden warden, LivingEntity target, ClockworkBossSettings.SonicBoom settings) {
        if (target == null || !target.isAlive()) {
            return false;
        }

        return warden.canTargetEntity(target)
                && warden.closerThan(
                target,
                settings.horizontalRange(),
                settings.verticalRange()
        );
    }

    /**
     * Uses a native COLLIDER raycast because the template promises solid-block
     * obstruction. Native visual line-of-sight uses different block shapes.
     */
    static boolean hasLineOfSight(
            ServerLevel level,
            Warden warden,
            LivingEntity target) {
        BlockHitResult hit = level.clip(
                new ClipContext(
                        warden.getEyePosition(),
                        target.getEyePosition(),
                        ClipContext.Block.COLLIDER,
                        ClipContext.Fluid.NONE,
                        warden
                )
        );

        return hit.getType() == HitResult.Type.MISS;
    }
}
