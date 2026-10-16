package clockwork.bosses.mixin;

import clockwork.bosses.ClockworkBossEditorPayload;
import clockwork.bosses.client.ClockworkBossBurrowGuiChecks;
import clockwork.bosses.client.ClockworkBossEditorClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Development-only capture of this fixture's own Save packet. Other sessions
 * are untouched. This deliberately does not claim transport/storage acceptance. */
@Mixin(ClockworkBossEditorClient.class)
public abstract class BurrowEditorSendProbe {
    @Inject(method = "send", at = @At("HEAD"), cancellable = true, remap = false)
    private static void clockworkBosses$captureSave(ClockworkBossEditorPayload payload, CallbackInfo ci) {
        if (ClockworkBossBurrowGuiChecks.capture(payload)) ci.cancel();
    }
}
