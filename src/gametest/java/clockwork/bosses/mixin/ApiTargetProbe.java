package clockwork.bosses.mixin;

import clockwork.bosses.ClockworkBossApiGameTests;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.warden.Warden;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Test mod only: prove a foreign native veto cannot yield ACCEPTED. */
@Mixin(Warden.class)
public abstract class ApiTargetProbe {
    @Inject(method = "setAttackTarget", at = @At("HEAD"), cancellable = true)
    private void clockworkBosses$fixtureVeto(LivingEntity target, CallbackInfo ci) {
        if (((Warden) (Object) this).getUUID().equals(ClockworkBossApiGameTests.vetoBoss)) ci.cancel();
    }
}
