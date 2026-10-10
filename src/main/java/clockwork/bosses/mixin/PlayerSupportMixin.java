package clockwork.bosses.mixin;

import clockwork.bosses.ClockworkBossFightPotion;
import clockwork.bosses.ClockworkBossDiagnostics;
import clockwork.bosses.ClockworkBossPlayerHealth;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Native event boundaries only: no replacement player tick, healing or item AI. */
@Mixin(LivingEntity.class)
public abstract class PlayerSupportMixin {
    /** Observe native use boundaries: combat/roll mods may stop a drink before
     * completion. These callbacks never approve, cancel or complete a use. */
    @Inject(method = "startUsingItem", at = @At("TAIL"))
    private void clockworkBosses$observeDrinkStart(net.minecraft.world.InteractionHand hand, CallbackInfo ci) {
        if (ClockworkBossDiagnostics.enabled() && (Object) this instanceof ServerPlayer player
                && player.isUsingItem() && ClockworkBossFightPotion.isPotion(player.getUseItem()))
            ClockworkBossDiagnostics.entity(player, "potion_use_start", "hand", hand.name(), "remaining_ticks", player.getUseItemRemainingTicks());
    }
    @Inject(method = "stopUsingItem", at = @At("HEAD"))
    private void clockworkBosses$observeDrinkStop(CallbackInfo ci) {
        if (ClockworkBossDiagnostics.enabled() && (Object) this instanceof ServerPlayer player
                && ClockworkBossFightPotion.isPotion(player.getUseItem()))
            ClockworkBossDiagnostics.entity(player, "potion_use_stop", "remaining_ticks", player.getUseItemRemainingTicks());
    }
    @Inject(method = "onAttributeUpdated", at = @At("HEAD"))
    private void clockworkBosses$healthChanged(Holder<Attribute> attribute, CallbackInfo ci) {
        if (attribute.equals(Attributes.MAX_HEALTH) && (Object) this instanceof ServerPlayer player)
            ClockworkBossPlayerHealth.attributeChanged(player);
    }
    /** Keep the standard potion drink duration/animation/sounds. Only the marked
     * arena bottle replaces completion, so it cannot shrink or leave glass. */
    @Inject(method = "completeUsingItem", at = @At("HEAD"), cancellable = true)
    private void clockworkBosses$finishPotion(CallbackInfo ci) {
        if ((Object) this instanceof Player player && ClockworkBossFightPotion.isPotion(player.getUseItem())) {
            if (player instanceof ServerPlayer serverPlayer) ClockworkBossFightPotion.finish(serverPlayer);
            player.stopUsingItem(); ci.cancel();
        }
    }
}
