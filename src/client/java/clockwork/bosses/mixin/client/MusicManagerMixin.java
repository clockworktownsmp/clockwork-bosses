package clockwork.bosses.mixin.client;

import clockwork.bosses.client.ClockworkBossMusicClient;
import net.minecraft.client.sounds.MusicManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hold vanilla's ambient-music scheduler only while a real encounter stream is playing, including its fade. */
@Mixin(MusicManager.class)
public abstract class MusicManagerMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void clockworkBosses$encounterMusic(CallbackInfo ci) {
        if (ClockworkBossMusicClient.isPlaying()) ci.cancel();
    }
}
