package clockwork.bosses.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Development-only access to native collision/hit methods; no combat substitute. */
@Mixin(Projectile.class)
public interface PvpProjectileProbe {
    @Invoker("canHitEntity") boolean clockworkTest$canHit(Entity entity);
    @Invoker("onHit") void clockworkTest$hit(HitResult hit);
}
