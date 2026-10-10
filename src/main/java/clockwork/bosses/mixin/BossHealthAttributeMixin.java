package clockwork.bosses.mixin;

import clockwork.bosses.ClockworkBossHealth;
import clockwork.bosses.ClockworkBossHealthAttribute;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/** Extends only a marked boss's owned health instance; the shared vanilla attribute range stays unchanged. */
@Mixin(AttributeInstance.class)
public abstract class BossHealthAttributeMixin implements ClockworkBossHealthAttribute {
    @Unique private boolean clockworkBosses$bossHealth;
    @Shadow protected abstract void setDirty();

    @Override public void clockworkBosses$setBossHealth(boolean enabled) {
        if (clockworkBosses$bossHealth == enabled) return;
        clockworkBosses$bossHealth = enabled;
        // Native calculation caching, modifier math, persistence and sync stay in use.
        setDirty();
    }

    @WrapOperation(method = "calculateValue", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/ai/attributes/Attribute;sanitizeValue(D)D"))
    private double clockworkBosses$healthRange(Attribute attribute, double computed, Operation<Double> original) {
        return clockworkBosses$bossHealth ? ClockworkBossHealth.sanitize(computed) : original.call(attribute, computed);
    }
}
