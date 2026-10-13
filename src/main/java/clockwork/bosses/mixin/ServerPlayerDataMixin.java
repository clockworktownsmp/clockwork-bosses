package clockwork.bosses.mixin;

import clockwork.bosses.ClockworkBossPlayerData;
import clockwork.bosses.ClockworkBossFightPotion;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Server-player potion boundaries: persist one native game-time deadline and
 * release temporary inventory before death drops. No player tick or offline
 * player registry; reconnect/respawn cannot reset the cooldown. */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerDataMixin implements ClockworkBossPlayerData {
    @Unique private long clockworkBosses$potionReady;
    @Override public long clockworkBosses$potionReadyTick() { return clockworkBosses$potionReady; }
    @Override public void clockworkBosses$setPotionReadyTick(long tick) { clockworkBosses$potionReady = Math.max(0, tick); }
    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void clockworkBosses$saveCooldown(ValueOutput output, CallbackInfo ci) {
        if (clockworkBosses$potionReady > 0) output.putLong("ClockworkBosses_PotionReady", clockworkBosses$potionReady);
    }
    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void clockworkBosses$loadCooldown(ValueInput input, CallbackInfo ci) {
        clockworkBosses$setPotionReadyTick(input.getLongOr("ClockworkBosses_PotionReady", 0));
    }
    /** Totems/death vetoes run before die(), and a canceled die() never reaches
     * this native call. Universal Graves captures inventory inside death loot,
     * so this boundary precedes its capture without depending on mixin priority
     * or importing that mod. keepInventory uses the same committed death path. */
    @Inject(method = "die", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/server/level/ServerPlayer;dropAllDeathLoot(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/damagesource/DamageSource;)V"))
    private void clockworkBosses$releasePotionBeforeDeathLoot(DamageSource source, CallbackInfo ci) {
        ClockworkBossFightPotion.beforeDeathDrops((ServerPlayer) (Object) this);
    }
}
