package clockwork.bosses.mixin;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Invoke the actual private native sweep; the production mod has no sweep mixin. */
@Mixin(Player.class)
public interface PvpSweepProbe {
    @Invoker("doSweepAttack") void clockworkTest$sweep(Entity primary, float damage, DamageSource source, float strength);
    @Invoker("canCriticalAttack") boolean clockworkTest$canCrit(Entity target);
}
