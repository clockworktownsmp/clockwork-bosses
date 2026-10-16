package clockwork.bosses.mixin;

import clockwork.bosses.ClockworkBossVitals;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;

/** Players override actuallyHurt; the inherited setters still supply the exact
 * write boundary, while this scope supplies only genuinely known damage source. */
@Mixin(Player.class)
public abstract class PlayerVitalsMixin {
    @WrapMethod(method = "actuallyHurt")
    private void clockworkBosses$damage(ServerLevel level, DamageSource source, float amount, Operation<Void> original) {
        try (var scope = ClockworkBossVitals.damage((LivingEntity) (Object) this, source)) { original.call(level, source, amount); }
    }
}
