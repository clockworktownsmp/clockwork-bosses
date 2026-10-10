package clockwork.bosses.mixin;

import clockwork.bosses.ClockworkBossManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.warden.SonicBoom;
import net.minecraft.world.entity.monster.warden.Warden;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Leaves Sonic Boom scheduling to the Clockwork Boss implementation for marked Wardens. */
@Mixin(SonicBoom.class)
public abstract class SonicBoomMixin {

    @Inject(method = "checkExtraStartConditions", at = @At("HEAD"), cancellable = true)
    private void clockworkBosses$disableVanillaForClockworkBoss(
            ServerLevel level,
            Warden body,
            CallbackInfoReturnable<Boolean> cir) {
        if (ClockworkBossManager.isClockworkBoss(body)) {
            cir.setReturnValue(false);
        }
    }
}
