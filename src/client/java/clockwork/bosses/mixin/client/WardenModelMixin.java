package clockwork.bosses.mixin.client;

import clockwork.bosses.client.WardenRenderStateVisuals;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.animation.KeyframeAnimation;
import net.minecraft.client.model.monster.warden.WardenModel;
import net.minecraft.client.renderer.entity.state.WardenRenderState;
import net.minecraft.world.entity.AnimationState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Sample 26.3's baked melee/Sonic Boom keyframes from captured server time.
 * Only marked bosses use these timelines; ordinary Warden animations stay native. */
@Mixin(WardenModel.class)
public abstract class WardenModelMixin {
    @WrapOperation(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/WardenRenderState;)V",
            at = @At(value = "INVOKE", target =
                    "Lnet/minecraft/client/animation/KeyframeAnimation;apply(Lnet/minecraft/world/entity/AnimationState;F)V"))
    private void clockworkBosses$sampleCharge(KeyframeAnimation animation, AnimationState nativeState, float age,
            Operation<Void> original, WardenRenderState renderState) {
        var visuals = (WardenRenderStateVisuals) renderState;
        if (visuals.clockworkBosses$isClockworkBoss() && nativeState == renderState.attackAnimationState) {
            long time = visuals.clockworkBosses$getMeleeMillis();
            if (time >= 0) animation.apply(time, 1.0F);
            return;
        }
        if (visuals.clockworkBosses$isClockworkBoss() && nativeState == renderState.sonicBoomAnimationState) {
            long time = visuals.clockworkBosses$getSonicMillis();
            if (time >= 0) animation.apply(time, 1.0F);
            return; // Idle/cancelled marked charges must not replay a receipt-time native animation.
        }
        original.call(animation, nativeState, age);
    }
}
