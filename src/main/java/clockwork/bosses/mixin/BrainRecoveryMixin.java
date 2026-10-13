package clockwork.bosses.mixin;

import clockwork.bosses.ClockworkBossManager;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.monster.warden.Warden;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Pause behavior execution while a marked boss's registered action owns it. Native
 * Brain.tick still expires memories and updates sensors, so attack cooldowns
 * naturally elapse. All other mobs and ordinary Wardens use the original code. */
@Mixin(Brain.class)
public abstract class BrainRecoveryMixin {
    // Guard the two calls in Brain.tick, rather than injecting into their
    // implementations: Lithium replaces startEachNonRunningBehavior. Its
    // optimized implementations remain intact for every entity without an action pause.
    @WrapWithCondition(method = "tick", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/world/entity/ai/Brain;startEachNonRunningBehavior(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/LivingEntity;)V"))
    private boolean clockworkBosses$startBehaviors(Brain<?> brain, ServerLevel level, LivingEntity entity) {
        return !(entity instanceof Warden boss && ClockworkBossManager.isRecovering(boss));
    }
    @WrapWithCondition(method = "tick", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/world/entity/ai/Brain;tickEachRunningBehavior(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/LivingEntity;)V"))
    private boolean clockworkBosses$tickBehaviors(Brain<?> brain, ServerLevel level, LivingEntity entity) {
        return !(entity instanceof Warden boss && ClockworkBossManager.isRecovering(boss));
    }
}
