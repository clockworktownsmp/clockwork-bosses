package clockwork.bosses.mixin;

import clockwork.bosses.ClockworkBossMobSounds;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;

/** Catch native ambient, hurt, death, steps, roar and attack sounds through their shared entity method. */
@Mixin(Entity.class)
public abstract class BossMobSoundMixin {
    @WrapMethod(method = "playSound(Lnet/minecraft/sounds/SoundEvent;FF)V")
    private void clockworkBosses$mobVolume(SoundEvent sound, float volume, float pitch, Operation<Void> original) {
        float adjusted = ClockworkBossMobSounds.volume((Entity) (Object) this, sound, volume);
        // Muted boss events need no sound packet/channel. Leave unrelated zero-volume calls unchanged.
        if (adjusted == 0 && volume != 0) return;
        original.call(sound, adjusted, pitch);
    }
}
