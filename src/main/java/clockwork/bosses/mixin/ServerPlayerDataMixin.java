package clockwork.bosses.mixin;

import clockwork.bosses.ClockworkBossPlayerData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Save one native game-time potion deadline, avoiding a retained offline player
 * registry and preventing reconnect/respawn from resetting the cooldown. */
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
}
