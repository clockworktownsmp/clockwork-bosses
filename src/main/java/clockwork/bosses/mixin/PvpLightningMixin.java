package clockwork.bosses.mixin;

import clockwork.bosses.ClockworkBossPvp;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LightningBolt;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Native thunderHit ignites before attempting unattributed lightning damage.
 * Its existing bolt argument retains Channeling's player cause; guard this
 * exact operation before either side effect. Natural lightning still proceeds. */
@Mixin(Entity.class)
public abstract class PvpLightningMixin {
    @Inject(method = "thunderHit", at = @At("HEAD"), cancellable = true)
    private void clockworkBosses$playerLightning(ServerLevel level, LightningBolt bolt, CallbackInfo callback) {
        if (ClockworkBossPvp.blocks((Entity) (Object) this, bolt)) callback.cancel();
    }
}
