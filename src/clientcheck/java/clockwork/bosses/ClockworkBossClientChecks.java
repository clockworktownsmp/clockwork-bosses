package clockwork.bosses;

import clockwork.bosses.client.WardenRenderStateVisuals;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.monster.warden.WardenModel;
import net.minecraft.client.renderer.entity.state.WardenRenderState;

/** Actual transformed 26.3 client/model checks; this separate development mod never enters the universal jar. */
public final class ClockworkBossClientChecks implements ClientModInitializer {
    private static int comparisons;
    private static int maskChecks;
    private static int faceChecks;
    private static boolean started, done;
    private static long deadline;
    @Override public void onInitializeClient() {
        if (System.getProperty("fabric.client.gametest") != null) return; // World-backed GUI runner owns shutdown.
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            started = true;
            deadline = System.nanoTime() + 60_000_000_000L;
        });
        // Development-only readiness check: Minecraft's first resource reload
        // finishes after CLIENT_STARTED. This callback is absent from release jars.
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!started || done) return;
            try { client.getEntityModels().bakeLayer(ModelLayers.WARDEN); }
            catch (IllegalArgumentException loading) { if (System.nanoTime() < deadline) return; }
            done = true;
            try {
                check(client);
                System.out.println("CLOCKWORK_CLIENT_CHECK_PASS: " + comparisons + " native model component comparisons, " + maskChecks + " emissive pixels, " + faceChecks + " face mask assertions");
                java.nio.file.Files.writeString(java.nio.file.Path.of("client-check-result.txt"), "PASS:" + comparisons + ":masks=" + maskChecks + ":faces=" + faceChecks);
            } catch (Throwable failure) {
                failure.printStackTrace();
                System.err.println("CLOCKWORK_CLIENT_CHECK_FAIL");
                try { java.nio.file.Files.writeString(java.nio.file.Path.of("client-check-result.txt"), "FAIL:" + failure); }
                catch (java.io.IOException reporting) { reporting.printStackTrace(); }
            }
            client.stop();
        });
    }

    static void check(Minecraft client) {
        checkEmissiveMasks(client);
        checkFaceMasks(client);
        for (int duration : new int[]{1, 2, 34, 68, 200}) {
            for (float fraction : new float[]{0, .25F, .5F, 1}) {
                long whole = (long) (duration * fraction);
                float partial = duration * fraction - whole;
                long millis = new ClockworkBossChargeVisual(1, 100, duration, -1).millis(100 + whole, partial);
                compare(client, millis, true, false);
            }
        }
        // Instant windup, normal recoil, cancellation with a lingering native
        // animation event, and ordinary-Warden opt-out exercise the wrapper too.
        compare(client, new ClockworkBossChargeVisual(2, 100, 0, 100).millis(100, 0), true, false);
        compare(client, new ClockworkBossChargeVisual(2, 100, 68, 168).millis(174, .5F), true, false);
        compare(client, -1, true, false);
        compare(client, -1, true, true); // A separate native melee animation must remain unchanged.
        compare(client, 850, false, false);
    }

    /** The tendril layer shares vanilla's complete base atlas. Its tint mask
     * must exclude body UVs even if another renderer supplies a full model. */
    private static void checkEmissiveMasks(Minecraft client) {
        for (String name : new String[]{"warden.png", "warden_heart.png", "warden_pulsating_spots_1.png", "warden_pulsating_spots_2.png"}) {
            var sourceId = net.minecraft.resources.Identifier.withDefaultNamespace("textures/entity/warden/" + name);
            var maskId = clockwork.bosses.client.WardenEmissiveMaskTexture.getTexture(sourceId);
            if (maskId == null || !(client.getTextureManager().getTexture(maskId) instanceof net.minecraft.client.renderer.texture.DynamicTexture texture))
                throw new AssertionError("Missing native emissive mask: " + name);
            var mask = texture.getPixels();
            try (var input = client.getResourceManager().open(sourceId);
                 var source = com.mojang.blaze3d.platform.NativeImage.read(input)) {
                if (mask == null || mask.getWidth() != source.getWidth() || mask.getHeight() != source.getHeight())
                    throw new AssertionError("Emissive atlas dimensions: " + name);
                int retained = 0;
                for (int y = 0; y < mask.getHeight(); y++) for (int x = 0; x < mask.getWidth(); x++) {
                    // Native 26.3 zero-depth tendril boxes: front/back UVs at
                    // (58,0)..(90,16) and (52,32)..(84,48) in a 128x128 atlas.
                    int u = x * 128 / mask.getWidth(), v = y * 128 / mask.getHeight();
                    boolean allowed = !name.equals("warden.png") || u >= 58 && u < 90 && v < 16
                            || u >= 52 && u < 84 && v >= 32 && v < 48;
                    int alpha = allowed ? source.getPixel(x, y) >>> 24 : 0;
                    int actual = mask.getPixel(x, y);
                    maskChecks++;
                    if (actual >>> 24 != alpha || alpha != 0 && (actual & 0xFFFFFF) != 0xFFFFFF)
                        throw new AssertionError("Emissive coverage escaped its glow layer: " + name + " at " + x + "," + y);
                    if (alpha != 0) retained++;
                }
                if (retained == 0) throw new AssertionError("Native glow was erased: " + name);
            } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
        }
    }

    /** Native resource/texture-manager path, for both bright editor and live
     * masks. Independent expected atlas writes catch clipping, pupil overlap,
     * signed-ID/cache collisions and unintended changes outside the face. */
    private static void checkFaceMasks(Minecraft client) {
        var sourceId = net.minecraft.resources.Identifier.withDefaultNamespace("textures/entity/warden/warden_bioluminescent_layer.png");
        var eyes = ClockworkBossEyes.DEFAULT;
        var pupils = ClockworkBossPupils.DEFAULT;
        try (var input = client.getResourceManager().open(sourceId);
             var source = com.mojang.blaze3d.platform.NativeImage.read(input)) {
            for (var expression : ClockworkBossBrows.Expression.values()) for (int angle : new int[]{-45,0,45})
                for (boolean preview : new boolean[]{false,true}) {
                    var brows = new ClockworkBossBrows(2,2,10,2,expression,angle,-angle);
                    var id = clockwork.bosses.client.WardenEmissiveMaskTexture.getTexture(sourceId,eyes.packed(),brows.packed(),pupils.packed(),preview);
                    if (id == null || !(client.getTextureManager().getTexture(id) instanceof net.minecraft.client.renderer.texture.DynamicTexture texture))
                        throw new AssertionError("Missing face texture: " + brows);
                    if (!id.equals(clockwork.bosses.client.WardenEmissiveMaskTexture.getTexture(sourceId,eyes.packed(),brows.packed(),pupils.packed(),preview))
                            || client.getTextureManager().getTexture(id) != texture) throw new AssertionError("Face cache miss/collision on unchanged pose");
                    int[] expected = new int[256]; java.util.Arrays.fill(expected,0x00FFFFFF);
                    for (boolean right : new boolean[]{false,true}) for (int pixel : brows.facePixels(right)) expected[pixel] = preview ? 0xFFFFFFFF : 0x90FFFFFF;
                    for (int eye = 0; eye < 2; eye++) {
                        int x = eye == 0 ? 3 : 11;
                        for (int dy=0;dy<2;dy++) for (int dx=0;dx<2;dx++)
                            expected[(7+dy)*16+x+dx] = dy==1 && dx==(eye==0?1:0) ? 0xFF666666 : 0xFFFFFFFF;
                    }
                    var pixels = texture.getPixels();
                    for (int y=0;y<128;y++) for (int x=0;x<128;x++) {
                        int value = x>=10 && x<26 && y>=42 && y<58 ? expected[(y-42)*16+x-10]
                                : (source.getPixel(x,y)&0xFF000000)|0x00FFFFFF;
                        faceChecks++;
                        if (pixels.getPixel(x,y)!=value) throw new AssertionError("Face atlas mismatch: " + expression + "/" + angle + "/" + preview + " at " + x + "," + y);
                    }
                }
            // Resource reload/disconnect uses this ownership cleanup. Rebuilding
            // the same path must create a fresh texture, not return disposed data.
            var oldId = clockwork.bosses.client.WardenEmissiveMaskTexture.getTexture(sourceId,eyes.packed(),ClockworkBossBrows.DEFAULT.packed(),pupils.packed(),true);
            var oldTexture = client.getTextureManager().getTexture(oldId);
            clockwork.bosses.client.WardenEmissiveMaskTexture.clear();
            var newId = clockwork.bosses.client.WardenEmissiveMaskTexture.getTexture(sourceId,eyes.packed(),ClockworkBossBrows.DEFAULT.packed(),pupils.packed(),true);
            if (!oldId.equals(newId) || client.getTextureManager().getTexture(newId)==oldTexture) throw new AssertionError("Face clear did not rebuild owned texture");
            faceChecks++;
        } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
    }

    private static void compare(Minecraft client, long millis, boolean marked, boolean melee) {
        ModelPart expectedRoot = client.getEntityModels().bakeLayer(ModelLayers.WARDEN);
        ModelPart actualRoot = client.getEntityModels().bakeLayer(ModelLayers.WARDEN);
        var expected = new WardenRenderState();
        var actual = new WardenRenderState();
        float age = millis < 0 ? 17 : millis / 50F;
        expected.ageInTicks = actual.ageInTicks = age;
        if (millis >= 0) expected.sonicBoomAnimationState.start(0);
        actual.sonicBoomAnimationState.start(0); // The marked cancel must suppress this stale event.
        if (melee) { expected.attackAnimationState.start(0); actual.attackAnimationState.start(0); }
        var visuals = (WardenRenderStateVisuals) actual;
        visuals.clockworkBosses$setClockworkBoss(marked);
        // An ordinary Warden must ignore a deliberately different custom time.
        visuals.clockworkBosses$setSonicMillis(marked ? millis : 1700);
        new WardenModel(expectedRoot).setupAnim(expected);
        new WardenModel(actualRoot).setupAnim(actual);
        var expectedParts = expectedRoot.getAllParts();
        var actualParts = actualRoot.getAllParts();
        if (expectedParts.size() != actualParts.size()) throw new AssertionError("Native model part count");
        for (int i = 0; i < expectedParts.size(); i++) {
            float[] a = components(expectedParts.get(i)), b = components(actualParts.get(i));
            for (int j = 0; j < a.length; j++) {
                comparisons++;
                if (Math.abs(a[j] - b[j]) > .002F) throw new AssertionError("Native keyframe mismatch at " + millis + ": part " + i + ", component " + j);
            }
        }
    }
    private static float[] components(ModelPart p) { return new float[]{p.x, p.y, p.z, p.xRot, p.yRot, p.zRot, p.xScale, p.yScale, p.zScale}; }
}
