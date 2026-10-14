package clockwork.bosses.mixin;

import clockwork.bosses.ClockworkBossPvp;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.ServerExplosion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Native blasts compute knockback independently of hurtServer's result. Use
 * their exact damage source to suppress that separate harm for protected PvP,
 * retaining native block interaction and all unrelated explosion behavior. */
@Mixin(ServerExplosion.class)
public abstract class PvpExplosionMixin {
    @WrapOperation(method = "hurtEntities", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/world/level/ExplosionDamageCalculator;getKnockbackMultiplier(Lnet/minecraft/world/entity/Entity;)F"))
    private float clockworkBosses$blastKnockback(ExplosionDamageCalculator calculator, Entity entity, Operation<Float> original) {
        float multiplier = original.call(calculator, entity);
        return entity instanceof LivingEntity living && ClockworkBossPvp.blocks(living, ((ServerExplosion) (Object) this).getDamageSource()) ? 0 : multiplier;
    }
}
