package clockwork.bosses.mixin;

import clockwork.bosses.ClockworkBossManager;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.ai.behavior.warden.Roar;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.warden.Warden;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Observe the native roar, not a replacement AI. Ordinary Wardens and normal
 * anger roars use every original call; only a configured child owns this token. */
@Mixin(Roar.class)
public abstract class RoarMixin {
    @Unique private long clockworkBosses$generation = -1;
    @Unique private boolean clockworkBosses$wasCooling;
    @Unique private long clockworkBosses$remainingCooldown;
    @Inject(method = "start(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/monster/warden/Warden;J)V", at = @At("TAIL"))
    private void clockworkBosses$started(ServerLevel level, Warden boss, long now, CallbackInfo ci) {
        clockworkBosses$generation = ClockworkBossManager.roarInterludeToken(boss);
        if (clockworkBosses$generation >= 0 && clockwork.bosses.ClockworkBossDiagnostics.enabled())
            clockwork.bosses.ClockworkBossDiagnostics.event(boss, boss.getTarget(), "roar_native_start", "captured_generation", clockworkBosses$generation);
    }
    @Inject(method = "canStillUse(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/monster/warden/Warden;J)Z", at = @At("HEAD"), cancellable = true)
    private void clockworkBosses$interrupted(ServerLevel level, Warden boss, long now, CallbackInfoReturnable<Boolean> cir) {
        if (clockworkBosses$generation >= 0 && clockworkBosses$generation != ClockworkBossManager.roarInterludeToken(boss)) cir.setReturnValue(false);
    }
    @WrapOperation(method = "tick(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/monster/warden/Warden;J)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/monster/warden/Warden;playSound(Lnet/minecraft/sounds/SoundEvent;FF)V"))
    private void clockworkBosses$sound(Warden boss, SoundEvent sound, float volume, float pitch, Operation<Void> original) {
        // Replace the native audio cue (about 25 ticks into the animation), not
        // its duration. Native roar memories make this a once-only event.
        if (clockworkBosses$generation >= 0 && clockworkBosses$generation == ClockworkBossManager.roarInterludeToken(boss)
                && ClockworkBossManager.playRoarInterludeSound(boss)) return;
        original.call(boss, sound, volume, pitch);
    }
    @Inject(method = "stop(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/monster/warden/Warden;J)V", at = @At("HEAD"))
    private void clockworkBosses$retainCooldown(ServerLevel level, Warden boss, long now, CallbackInfo ci) {
        if (clockworkBosses$generation < 0) return;
        var brain = boss.getBrain();
        clockworkBosses$remainingCooldown = brain.getTimeUntilExpiry(MemoryModuleType.SONIC_BOOM_COOLDOWN);
        clockworkBosses$wasCooling = brain.hasMemoryValue(MemoryModuleType.SONIC_BOOM_COOLDOWN);
    }
    @Inject(method = "stop(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/monster/warden/Warden;J)V", at = @At("TAIL"))
    private void clockworkBosses$finished(ServerLevel level, Warden boss, long now, CallbackInfo ci) {
        long token = clockworkBosses$generation; clockworkBosses$generation = -1;
        if (token >= 0 && clockwork.bosses.ClockworkBossDiagnostics.enabled())
            clockwork.bosses.ClockworkBossDiagnostics.event(boss, boss.getTarget(), "roar_native_stop", "captured_generation", token, "remaining_sonic_cooldown", clockworkBosses$remainingCooldown);
        // Native stop uses a method-reference target assignment and installs a
        // fresh 200-tick cooldown. Retain the naturally elapsed prior cooldown.
        if (token >= 0) {
            if (clockworkBosses$wasCooling) boss.getBrain().setMemoryWithExpiry(MemoryModuleType.SONIC_BOOM_COOLDOWN,
                    net.minecraft.util.Unit.INSTANCE, clockworkBosses$remainingCooldown);
            else boss.getBrain().eraseMemory(MemoryModuleType.SONIC_BOOM_COOLDOWN);
        }
        ClockworkBossManager.onRoarInterludeCompleted(boss, token);
    }
}
