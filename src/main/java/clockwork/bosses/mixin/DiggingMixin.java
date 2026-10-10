package clockwork.bosses.mixin;

import clockwork.bosses.ClockworkBossManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.warden.Digging;
import net.minecraft.world.entity.monster.warden.Warden;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Prevents Clockwork Boss Wardens from entering the vanilla digging behavior. */
@Mixin(Digging.class)
public abstract class DiggingMixin {

    @Inject(method = "checkExtraStartConditions", at = @At("HEAD"), cancellable = true)
    private void clockworkBosses$preventClockworkBossDigging(
            ServerLevel level,
            Warden warden,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (ClockworkBossManager.isClockworkBoss(warden)) {
            cir.setReturnValue(false);
        }
    }
}
