package clockwork.bosses.mixin;

import clockwork.bosses.ClockworkBossPvp;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;

/** Fabric supplies effect cancellation and command context, but not the source.
 * This wrapper transports the existing native argument; it does not replace
 * effect application, erase effects or skip other mods' callbacks. */
@Mixin(LivingEntity.class)
public abstract class PvpEffectMixin {
    @WrapMethod(method = "addEffect(Lnet/minecraft/world/effect/MobEffectInstance;Lnet/minecraft/world/entity/Entity;)Z")
    private boolean clockworkBosses$effectSource(MobEffectInstance effect, Entity source, Operation<Boolean> original) {
        // Ordinary mob effects and helpful buffs need no scope or supplier allocation.
        if (!((Object) this instanceof ServerPlayer) || effect.getEffect().value().getCategory() != MobEffectCategory.HARMFUL)
            return original.call(effect, source);
        return ClockworkBossPvp.withEffectSource((LivingEntity) (Object) this, effect, source, () -> original.call(effect, source));
    }
}
