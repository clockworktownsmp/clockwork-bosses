package clockwork.bosses.mixin.client;

import clockwork.bosses.client.WardenRenderStateVisuals;
import clockwork.bosses.client.WardenEmissiveMaskTexture;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef;
import net.minecraft.client.renderer.entity.layers.LivingEntityEmissiveLayer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;


/** Applies configured colors to Clockwork Boss emissive layers without changing their models. */
@Mixin(LivingEntityEmissiveLayer.class)
public abstract class LivingEntityEmissiveLayerMixin {

    /**
     * Replaces each source texture before its original buffer provider creates a
     * render type. This keeps the Warden's separate static, pulsing, tendril, and
     * heart textures distinct instead of reusing one mask for every layer.
     */
    @ModifyExpressionValue(
            method = "submit",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/function/Function;apply(Ljava/lang/Object;)Ljava/lang/Object;",
                    ordinal = 0))
    private static Object clockworkBosses$tintSourceTexture(
            Object originalTexture,
            @Local(argsOnly = true) LivingEntityRenderState state,
            @Share("clockworkTintMask") LocalBooleanRef tintedMask,
            @Share("clockworkPreviewFace") LocalBooleanRef previewFace) {
        if (!(originalTexture instanceof Identifier sourceTexture)
                || !(state instanceof WardenRenderStateVisuals visuals)
                || !visuals.clockworkBosses$isClockworkBoss()) {
            return originalTexture;
        }

        Identifier maskTexture = WardenEmissiveMaskTexture.getTexture(sourceTexture, visuals.clockworkBosses$getEyes(),
                visuals.clockworkBosses$getBrows(), visuals.clockworkBosses$isEditorPreview());
        previewFace.set(maskTexture != null && visuals.clockworkBosses$isEditorPreview()
                && sourceTexture.getPath().equals("textures/entity/warden/warden_bioluminescent_layer.png"));
        // Tint only when we supplied a white mask. Foreign layers and failed
        // resource decodes retain their original color, texture and alpha.
        tintedMask.set(maskTexture != null);
        return maskTexture == null ? originalTexture : maskTexture;
    }

    /**
     * Minecraft 26.3 submits emissive models through OrderedSubmitNodeCollector.submitModel.
     * The vanilla Warden textures have cyan RGB baked into their emissive pixels,
     * so multiplying by orange or red cannot produce those hues. Clockwork Bosses use
     * white alpha masks; normal Wardens keep vanilla.
     */
    @ModifyArgs(
            method = "submit",
            require = 1,
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/OrderedSubmitNodeCollector;submitModel("
                            + "Lnet/minecraft/client/model/Model;"
                            + "Ljava/lang/Object;"
                            + "Lcom/mojang/blaze3d/vertex/PoseStack;"
                            + "Lnet/minecraft/client/renderer/rendertype/RenderType;"
                            + "IIILnet/minecraft/client/renderer/texture/UvMapping;"
                            + "I)V"))
    private static void clockworkBosses$tintEffect(Args args,
            @Share("clockworkTintMask") LocalBooleanRef tintedMask,
            @Share("clockworkPreviewFace") LocalBooleanRef previewFace) {
        if (!tintedMask.get()) return;
        Object stateObject = args.get(1);
        if (!(stateObject instanceof LivingEntityRenderState state)) {
            return;
        }
        if (!(state instanceof WardenRenderStateVisuals visuals)) {
            return;
        }
        if (!visuals.clockworkBosses$isClockworkBoss()) {
            return;
        }

        int originalColor = (Integer) args.get(6);
        // Alignment needs a steady, saturated face color. Keep the configured hue,
        // but remove its pastel/white component and use full opacity/brightness.
        // Skip the white phase flash here too; gameplay layers retain their exact
        // configured RGB, native pulse and transition flash.
        int tintColor = previewFace.get()
                ? saturatedPreviewTint(visuals.clockworkBosses$getEffectColor())
                : applyVisualTint(originalColor, state, visuals);
        args.set(6, tintColor);

    }

    /** RGB equivalent of retaining hue while setting saturation and brightness to
     * 100%. Subtracting the minimum removes white; normalizing the remaining maximum
     * to 255 preserves channel ratios. Neutral gray has no hue, so keep its RGB.
     * The mask still supplies the pupil's darker shade; no extra texture is needed. */
    @Unique
    private static int saturatedPreviewTint(int color) {
        int r = channel(color, 16), g = channel(color, 8), b = channel(color, 0);
        int minimum = Math.min(r, Math.min(g, b));
        int range = Math.max(r, Math.max(g, b)) - minimum;
        if (range == 0) return 0xFF000000 | (color & 0x00FFFFFF);
        r = (r - minimum) * 255 / range;
        g = (g - minimum) * 255 / range;
        b = (b - minimum) * 255 / range;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** Applies the effective phase color plus the short phase-change flash. */
    @Unique
    private static int applyVisualTint(
            int originalColor,
            LivingEntityRenderState state,
            WardenRenderStateVisuals visuals) {

        int alpha = (originalColor >>> 24) & 0xFF;
        int effectColor = visuals.clockworkBosses$getEffectColor();
        int tinted = (alpha << 24) | (effectColor & 0x00FFFFFF);

        int startTick = visuals.clockworkBosses$getPhaseChangeTick();
        if (startTick != Integer.MIN_VALUE) {
            float elapsed = state.ageInTicks - startTick;
            if (elapsed >= 0.0F && elapsed < 8.0F) {
                float fade = 1.0F - (elapsed / 8.0F);
                int r = channel(tinted, 16);
                int g = channel(tinted, 8);
                int b = channel(tinted, 0);
                r = (int) (r + (255 - r) * fade);
                g = (int) (g + (255 - g) * fade);
                b = (int) (b + (255 - b) * fade);
                return (alpha << 24) | (r << 16) | (g << 8) | b;
            }
        }

        return tinted;
    }

    /**
     * Extracts one unsigned RGB component for the local white-flash blend, preserving the original layer
     * alpha.
     */
    @Unique
    private static int channel(int argb, int shift) {
        return (argb >>> shift) & 0xFF;
    }
}
