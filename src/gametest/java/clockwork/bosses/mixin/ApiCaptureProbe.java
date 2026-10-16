package clockwork.bosses.mixin;

import clockwork.bosses.ClockworkBossApiGameTests;
import clockwork.bosses.ClockworkBossManager;
import java.util.UUID;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Test-only early capture fault, before any frame allocation. */
@Mixin(value = ClockworkBossManager.class, remap = false)
public abstract class ApiCaptureProbe {
    @Inject(method = "observesVitals", at = @At("HEAD"))
    private static void clockworkBosses$captureFailure(LivingEntity entity, UUID sourceBoss, CallbackInfoReturnable<Boolean> ci) {
        if (entity.getUUID().equals(ClockworkBossApiGameTests.captureFailure)) throw new IllegalStateException("intentional early capture fault");
    }
}
