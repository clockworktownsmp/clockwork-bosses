package clockwork.bosses.mixin;

import clockwork.bosses.ClockworkBossPlayerHarmSource;
import clockwork.bosses.ClockworkBossPvp;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.projectile.Projectile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keep protected allies out of native projectile collision selection. Shots
 * pass them instead of bouncing, igniting them or consuming attack effects.
 * Existing native/mod collision vetoes remain vetoes. */
@Mixin(Projectile.class)
public abstract class PvpProjectileMixin {
    @Inject(method = "canHitEntity", at = @At("RETURN"), cancellable = true)
    private void clockworkBosses$protectedCollision(Entity victim, CallbackInfoReturnable<Boolean> callback) {
        if (callback.getReturnValueZ() && ClockworkBossPvp.blocks(victim, (Entity) (Object) this)) callback.setReturnValue(false);
    }
    /** Capture reassignment immediately, including deflection followed by logout. */
    @Inject(method = "setOwner(Lnet/minecraft/world/entity/EntityReference;)V", at = @At("TAIL"))
    private void clockworkBosses$changedOwner(EntityReference<Entity> owner, CallbackInfo callback) {
        ((ClockworkBossPlayerHarmSource) this).clockworkBosses$playerOrigin();
    }
}
