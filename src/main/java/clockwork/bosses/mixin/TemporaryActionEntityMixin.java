package clockwork.bosses.mixin;

import clockwork.bosses.ClockworkBossTemporaryEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.level.portal.TeleportTransition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Only entities explicitly adopted by an action change persistence. No tick
 * hook or global entity map is added. Guard both chunk selection and passenger
 * serialization, since Minecraft can save a riding entity through its vehicle. */
@Mixin(Entity.class)
public abstract class TemporaryActionEntityMixin implements ClockworkBossTemporaryEntity {
    @Unique private boolean clockworkBosses$temporary;
    @Override public boolean clockworkBosses$isTemporary() { return clockworkBosses$temporary; }
    @Override public void clockworkBosses$setTemporary() { clockworkBosses$temporary = true; }
    @Inject(method = "shouldBeSaved()Z", at = @At("HEAD"), cancellable = true)
    private void clockworkBosses$skipChunkSave(CallbackInfoReturnable<Boolean> callback) {
        if (clockworkBosses$temporary) callback.setReturnValue(false);
    }
    @Inject(method = "saveAsPassenger(Lnet/minecraft/world/level/storage/ValueOutput;)Z", at = @At("HEAD"), cancellable = true)
    private void clockworkBosses$skipPassengerSave(ValueOutput output, CallbackInfoReturnable<Boolean> callback) {
        if (clockworkBosses$temporary) callback.setReturnValue(false);
    }
    /** Avoid starting portal searches/tickets for a temporary entity which is
     * not allowed to leave its encounter. Other entities retain native rules. */
    @Inject(method = "canUsePortal(Z)Z", at = @At("HEAD"), cancellable = true)
    private void clockworkBosses$skipOwnedPortal(boolean allowPassengers, CallbackInfoReturnable<Boolean> callback) {
        if (clockworkBosses$temporary) callback.setReturnValue(false);
    }
    /** Cross-dimension travel creates a replacement entity via restoreFrom,
     * losing this transient marker and the cleanup scope's original identity.
     * Keep owned entities in their encounter's world. Same-world teleports work. */
    @Inject(method = "teleport(Lnet/minecraft/world/level/portal/TeleportTransition;)Lnet/minecraft/world/entity/Entity;", at = @At("HEAD"), cancellable = true)
    private void clockworkBosses$keepOwnedDimension(TeleportTransition transition, CallbackInfoReturnable<Entity> callback) {
        if (clockworkBosses$temporary && transition.newLevel() != ((Entity) (Object) this).level()) callback.setReturnValue(null);
    }
}
