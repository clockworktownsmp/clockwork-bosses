package clockwork.bosses.mixin;

import clockwork.bosses.ClockworkBossVitals;
import clockwork.bosses.api.BossVitalsChange;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;

/** Wrap original native operations unchanged. Finally closes observations even
 * if another mixin throws; no replacement damage/healing or tick scan exists. */
@Mixin(LivingEntity.class)
public abstract class VitalsMixin {
    @WrapMethod(method = "setHealth")
    private void clockworkBosses$health(float value, Operation<Void> original) {
        var frame = ClockworkBossVitals.begin((LivingEntity) (Object) this, BossVitalsChange.Field.HEALTH);
        boolean completed = false;
        try { original.call(value); completed = true; } finally { ClockworkBossVitals.end(frame, completed); }
    }
    @WrapMethod(method = "setAbsorptionAmount")
    private void clockworkBosses$absorption(float value, Operation<Void> original) {
        var frame = ClockworkBossVitals.begin((LivingEntity) (Object) this, BossVitalsChange.Field.ABSORPTION);
        boolean completed = false;
        try { original.call(value); completed = true; } finally { ClockworkBossVitals.end(frame, completed); }
    }
    @WrapMethod(method = "actuallyHurt")
    private void clockworkBosses$damage(ServerLevel level, DamageSource source, float amount, Operation<Void> original) {
        try (var scope = ClockworkBossVitals.damage((LivingEntity) (Object) this, source)) { original.call(level, source, amount); }
    }
    @WrapMethod(method = "heal")
    private void clockworkBosses$heal(float amount, Operation<Void> original) {
        try (var scope = ClockworkBossVitals.healing((LivingEntity) (Object) this, amount)) { original.call(amount); }
    }
}
