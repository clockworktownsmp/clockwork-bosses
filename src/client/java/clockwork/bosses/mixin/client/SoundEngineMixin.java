package clockwork.bosses.mixin.client;

import clockwork.bosses.client.ClockworkBossSoundClient;
import clockwork.bosses.client.ClockworkBossMusicClient;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.sounds.SoundSource;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mirrors native lifecycle events for the small set of server-file channels.
 * Minecraft owns the channel pool, but these clips have no resource-pack
 * SoundInstance, so they need explicit buffer ownership and category updates.
 */
@Mixin(SoundEngine.class)
public abstract class SoundEngineMixin {
    /** Release uploaded buffers before vanilla shuts down or recreates its device. */
    @Inject(method = "stopAll", at = @At("HEAD"))
    private void clockworkBosses$stopServerClips(CallbackInfo ci) {
        ClockworkBossMusicClient.stopPlayback();
        ClockworkBossSoundClient.stopPlayback();
    }

    /** /stopsound without an event name also stops our clips in the selected Hostile category. */
    @Inject(method = "stop(Lnet/minecraft/resources/Identifier;Lnet/minecraft/sounds/SoundSource;)V",
            at = @At("HEAD"))
    private void clockworkBosses$stopHostileServerClips(Identifier event, SoundSource source, CallbackInfo ci) {
        if (event == null) ClockworkBossMusicClient.stopRequested(source);
        if (event == null && (source == null || source == SoundSource.HOSTILE)) {
            ClockworkBossSoundClient.stopPlayback();
        }
    }

    /** Pause the clip's cleanup deadline as well as its native audio channel. */
    @Inject(method = "pauseAllExcept", at = @At("HEAD"))
    private void clockworkBosses$pauseServerClips(SoundSource[] exceptions, CallbackInfo ci) {
        for (SoundSource source : exceptions) {
            if (source == SoundSource.HOSTILE) return;
        }
        ClockworkBossSoundClient.pausePlayback();
    }

    /** Native resume already resumes every pooled channel; restore our deadlines. */
    @Inject(method = "resume", at = @At("TAIL"))
    private void clockworkBosses$resumeServerClips(CallbackInfo ci) {
        ClockworkBossMusicClient.resume();
        ClockworkBossSoundClient.resumePlayback();
    }

    /** Respect live slider and category-gain changes without a mod tick callback. */
    @Inject(method = "refreshCategoryVolume", at = @At("TAIL"))
    private void clockworkBosses$updateServerClipVolume(SoundSource source, CallbackInfo ci) {
        ClockworkBossMusicClient.volume((SoundEngineAccessor) this);
        if (source == SoundSource.MASTER || source == SoundSource.HOSTILE) {
            ClockworkBossSoundClient.refreshVolume((SoundEngineAccessor) this);
        }
    }

    /** Borrow the existing native audio tick for one music stream and its fade; no added game watcher. */
    @Inject(method = "tick", at = @At("TAIL"))
    private void clockworkBosses$musicTick(boolean paused, CallbackInfo ci) {
        ClockworkBossMusicClient.tick((SoundEngineAccessor) this);
    }

    /** Native menu exemptions apply to the configured music category; pause owned streams and fades together. */
    @Inject(method = "pauseAllExcept", at = @At("HEAD"))
    private void clockworkBosses$pauseMusic(SoundSource[] exceptions, CallbackInfo ci) {
        ClockworkBossMusicClient.pause(exceptions);
    }
}
