package clockwork.bosses.mixin;

import clockwork.bosses.ClockworkBossPlayerHarmSource;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Source changes are events, not cloud polling. Capture the player while
 * available so a lingering potion remains attributable after their logout. */
@Mixin(AreaEffectCloud.class)
public abstract class PvpCloudMixin {
    @Inject(method = "setOwner", at = @At("TAIL"))
    private void clockworkBosses$changedOwner(LivingEntity owner, CallbackInfo callback) {
        ((ClockworkBossPlayerHarmSource) this).clockworkBosses$playerOrigin();
    }
}
