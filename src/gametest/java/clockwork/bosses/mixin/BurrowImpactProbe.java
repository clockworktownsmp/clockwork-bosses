package clockwork.bosses.mixin;

import clockwork.bosses.ClockworkBossBurrowImpactGameTests;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Test mod only: observes the real native level-event dispatch, without
 * replacing particles or adding a hook to the shipped mod. */
@Mixin(ServerLevel.class)
public abstract class BurrowImpactProbe {
    @Inject(method = "levelEvent", at = @At("HEAD"))
    private void clockworkBosses$observeBurst(Entity source, int event, BlockPos position, int data, CallbackInfo ci) {
        ClockworkBossBurrowImpactGameTests.observeBurst((ServerLevel) (Object) this, event, position, data);
    }
}
