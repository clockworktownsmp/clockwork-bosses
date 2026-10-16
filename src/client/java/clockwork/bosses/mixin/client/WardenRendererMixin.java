package clockwork.bosses.mixin.client;

import clockwork.bosses.client.WardenRenderStateVisuals;
import clockwork.bosses.client.WardenSkinTextureCache;

import clockwork.bosses.ClockworkBossMarker;
import net.minecraft.client.renderer.entity.WardenRenderer;
import net.minecraft.client.renderer.entity.state.WardenRenderState;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.monster.warden.Warden;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;


/** Copies server-synchronised Clockwork Boss visual state into the render state. */
@Mixin(WardenRenderer.class)
public abstract class WardenRendererMixin {

    /**
     * Copies synchronized marker/skin/color/flash data into the native render snapshot; rendering never
     * reads server manager state.
     */
    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void clockworkBosses$extractVisualState(
            Warden entity,
            WardenRenderState state,
            float partialTicks,
            CallbackInfo ci) {

        ClockworkBossMarker marker = (ClockworkBossMarker) (Object) entity;
        WardenRenderStateVisuals visuals = (WardenRenderStateVisuals) (Object) state;
        int effectColor = marker.clockworkBosses$getEffectColor();
        visuals.clockworkBosses$setClockworkBoss(
                marker.clockworkBosses$isClockworkBossSynced());
        visuals.clockworkBosses$setEffectColor(effectColor);
        visuals.clockworkBosses$setEyes(marker.clockworkBosses$getEyes());
        visuals.clockworkBosses$setBrows(marker.clockworkBosses$getBrows());
        visuals.clockworkBosses$setPupils(marker.clockworkBosses$getPupils());
        visuals.clockworkBosses$setEditorPreview(false); // Only the editor may opt its detached snapshot in.
        // Start an eight-tick flash when this client observes a new transition,
        // rather than comparing unrelated server and client entity ages.
        visuals.clockworkBosses$setPhaseChangeTick(marker.clockworkBosses$getLocalFlashStartTick());
        visuals.clockworkBosses$setSkinPlayerName(marker.clockworkBosses$getSkinPlayerName());
        visuals.clockworkBosses$setSkinType(marker.clockworkBosses$getSkinType());
        visuals.clockworkBosses$setCustomSkinFile(marker.clockworkBosses$getCustomSkinFile());
        visuals.clockworkBosses$setTemplate(marker.clockworkBosses$getTemplate());
        // Copy sampled primitives only. Shared native game time accounts for
        // progress before a client begins tracking; metadata is parsed only on change.
        visuals.clockworkBosses$setSonicMillis(marker.clockworkBosses$sonicVisualMillis(entity.level().getGameTime(), partialTicks));
        visuals.clockworkBosses$setMeleeMillis(marker.clockworkBosses$meleeVisualMillis(entity.level().getGameTime(), partialTicks));
        visuals.clockworkBosses$setBurrowEmergenceMillis(entity.hasPose(Pose.EMERGING)
                ? marker.clockworkBosses$burrowEmergenceMillis(entity.level().getGameTime(), partialTicks) : -1);


    }

    @Inject(
            method = "getTextureLocation(Lnet/minecraft/client/renderer/entity/state/WardenRenderState;)"
                    + "Lnet/minecraft/resources/Identifier;",
            at = @At("HEAD"),
            cancellable = true)
    /**
     * Overrides only marked boss base textures; unresolved assets use vanilla until the bounded
     * asynchronous load completes.
     */
    private void clockworkBosses$usePlayerSkin(
            WardenRenderState state,
            CallbackInfoReturnable<net.minecraft.resources.Identifier> cir) {
        WardenRenderStateVisuals visuals = (WardenRenderStateVisuals) (Object) state;
        if (!visuals.clockworkBosses$isClockworkBoss()) return;

        net.minecraft.resources.Identifier skinTexture =
                WardenSkinTextureCache.textureFor(
                        visuals.clockworkBosses$getSkinType(),
                        visuals.clockworkBosses$getSkinPlayerName(),
                        visuals.clockworkBosses$getCustomSkinFile(),
                        visuals.clockworkBosses$getTemplate());
        if (skinTexture != null) {
            cir.setReturnValue(skinTexture);
        }
    }
}
