package clockwork.bosses.mixin;

import clockwork.bosses.ClockworkBossMarker;
import clockwork.bosses.ClockworkBossRewards;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.warden.Warden;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** One narrow native loot invocation wrapper. Every other entity and unmarked Warden passes through. */
@Mixin(LivingEntity.class)
public abstract class BossLootMixin {
    @WrapOperation(method = "dropAllDeathLoot", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;dropFromLootTable(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/damagesource/DamageSource;Z)V"))
    private void clockworkBosses$lootGate(LivingEntity entity, ServerLevel level, DamageSource source, boolean playerKill, Operation<Void> original) {
        if (!(entity instanceof Warden boss) || !((ClockworkBossMarker) boss).clockworkBosses$isClockworkBoss()
                || ClockworkBossRewards.prepareDeath(boss, level, source, playerKill)) original.call(entity, level, source, playerKill);
    }

}
