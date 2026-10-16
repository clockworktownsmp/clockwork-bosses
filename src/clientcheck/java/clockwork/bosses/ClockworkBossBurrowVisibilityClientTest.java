package clockwork.bosses;

import clockwork.bosses.client.WardenRenderStateVisuals;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.monster.warden.WardenModel;
import net.minecraft.client.renderer.entity.WardenRenderer;
import net.minecraft.client.renderer.entity.state.WardenRenderState;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.monster.warden.Warden;

/** Actual transformed client pose callback, renderer extraction and baked native
 * model. A detached entity avoids claiming multiplayer packet/pixel coverage. */
public final class ClockworkBossBurrowVisibilityClientTest implements ClientModInitializer, FabricClientGameTest {
    private static int comparisons;
    @Override public void onInitializeClient() {
        if (!Boolean.getBoolean("clockwork.bosses.burrow.tests")) return;
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            try {
                if (Files.exists(Path.of("burrow-stop.request"))) client.stop();
                if (client.level == null || client.level.getGameTime() % 20 == 0)
                    Files.writeString(Path.of("burrow-heartbeat.txt"), Long.toString(System.nanoTime()));
            } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
        });
    }
    @Override public void runTest(ClientGameTestContext context) {
        try (var world = context.worldBuilder().create()) {
            context.waitFor(client -> client.level != null && client.player != null);
            context.runOnClient(this::check);
            context.runOnClient(client -> Files.writeString(Path.of("burrow-client-result.txt"),
                    "PASS:3cycles; native pose/render/model; comparisons=" + comparisons
                            + "; ordinary opt-out retained; detached entity, no packet/pixel claim\n"));
        } catch (Exception | AssertionError failure) {
            try { Files.writeString(Path.of("burrow-client-result.txt"), "FAIL:" + failure + "\n"); }
            catch (java.io.IOException writing) { failure.addSuppressed(writing); }
            throw new AssertionError("Burrow client visibility regression", failure);
        }
    }
    private void check(Minecraft client) {
        Warden boss = new Warden(EntityTypes.WARDEN, client.level);
        // Detached entities bypass normal level insertion, which assigns native IDs.
        boss.setId(Integer.MAX_VALUE - 1);
        ((ClockworkBossMarker) boss).clockworkBosses$setClockworkBoss(true);
        WardenRenderer renderer = (WardenRenderer) client.getEntityRenderDispatcher().getRenderer(boss);
        for (int cycle = 0; cycle < 3; cycle++) {
            boss.setPose(Pose.DIGGING); boss.tickCount += 45; boss.setInvisible(true);
            WardenRenderState buried = new WardenRenderState(); renderer.extractRenderState(boss, buried, 0);
            require(boss.diggingAnimationState.isStarted() && buried.isInvisible,
                    "digging remains active and hidden underground");
            boss.setInvisible(false); boss.setPose(Pose.EMERGING);
            require(boss.emergeAnimationState.getTimeInMillis(boss.tickCount) == 0,
                    "each emergence starts a fresh full native animation");
            boss.tickCount += 20;
            compare(client, renderer, boss, "emerging_" + cycle, false);
            require(boss.emergeAnimationState.isStarted(), "full emergence is preserved");
            boss.tickCount += 140; boss.setPose(Pose.STANDING);
            boss.attackAnimationState.start(boss.tickCount); boss.tickCount += 8;
            compare(client, renderer, boss, "attacking_" + cycle, true);
            require(!boss.isInvisible(), "next attack remains visible");
        }
        // A non-boss retains vanilla behavior; its persistent digging transform
        // explicitly demonstrates why an unchanged vanilla client is not fixed.
        Warden ordinary = new Warden(EntityTypes.WARDEN, client.level);
        ordinary.setId(Integer.MAX_VALUE - 2);
        ordinary.setPose(Pose.DIGGING); ordinary.tickCount += 45;
        ordinary.setPose(Pose.EMERGING); ordinary.tickCount += 140; ordinary.setPose(Pose.STANDING);
        require(ordinary.diggingAnimationState.isStarted(), "ordinary vanilla behavior is unchanged");
        WardenRenderState vanilla = new WardenRenderState(); renderer.extractRenderState(ordinary, vanilla, 0);
        ModelPart nativeRoot = client.getEntityModels().bakeLayer(ModelLayers.WARDEN);
        new WardenModel(nativeRoot).setupAnim(vanilla);
        float nativeY = nativeRoot.getChild("bone").y;
        vanilla.diggingAnimationState.stop(); vanilla.emergeAnimationState.stop();
        ModelPart clearedRoot = client.getEntityModels().bakeLayer(ModelLayers.WARDEN);
        new WardenModel(clearedRoot).setupAnim(vanilla);
        float nativeDifference = maximumDifference(nativeRoot, clearedRoot);
        require(nativeDifference > 1,
                "unchanged native control retains the underground model transform");
        try { Files.writeString(Path.of("burrow-client-model.jsonl"),
                "{\"stage\":\"unchanged_vanilla_control\",\"native_bone_y\":" + nativeY
                        + ",\"cleared_bone_y\":" + clearedRoot.getChild("bone").y
                        + ",\"max_transform_difference\":" + nativeDifference
                        + ",\"scope\":\"native opt-out proxy, not an unmodified-client network test\"}\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
        catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
        boss.setPose(Pose.DIGGING); boss.tickCount += 45; boss.setPose(Pose.STANDING);
        compare(client, renderer, boss, "interrupted_to_standing", true);
    }
    private static float maximumDifference(ModelPart actual, ModelPart expected) {
        var actualParts = actual.getAllParts(); var expectedParts = expected.getAllParts();
        float maximum = 0;
        for (int i = 0; i < actualParts.size(); i++) {
            float[] a = values(actualParts.get(i)), b = values(expectedParts.get(i));
            for (int j = 0; j < a.length; j++) maximum = Math.max(maximum, Math.abs(a[j] - b[j]));
        }
        return maximum;
    }
    private static void compare(Minecraft client, WardenRenderer renderer, Warden boss, String stage, boolean standing) {
        WardenRenderState actual = new WardenRenderState(), expected = new WardenRenderState();
        renderer.extractRenderState(boss, actual, 0); renderer.extractRenderState(boss, expected, 0);
        // The independent reference represents this same pose without the obsolete
        // preceding dig. The actual state is never manually corrected by the test.
        expected.diggingAnimationState.stop();
        if (standing) expected.emergeAnimationState.stop();
        if (standing) {
            ((WardenRenderStateVisuals) actual).clockworkBosses$setMeleeMillis(400);
            ((WardenRenderStateVisuals) expected).clockworkBosses$setMeleeMillis(400);
        }
        ModelPart actualRoot = client.getEntityModels().bakeLayer(ModelLayers.WARDEN);
        ModelPart expectedRoot = client.getEntityModels().bakeLayer(ModelLayers.WARDEN);
        new WardenModel(actualRoot).setupAnim(actual); new WardenModel(expectedRoot).setupAnim(expected);
        JsonObject row = new JsonObject(); row.addProperty("stage", stage);
        row.addProperty("pose", boss.getPose().name()); row.addProperty("invisible", actual.isInvisible);
        row.addProperty("digging_started", actual.diggingAnimationState.isStarted());
        row.addProperty("emerging_started", actual.emergeAnimationState.isStarted());
        row.addProperty("bone_y", actualRoot.getChild("bone").y);
        row.addProperty("expected_bone_y", expectedRoot.getChild("bone").y);
        try { Files.writeString(Path.of("burrow-client-model.jsonl"), row + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
        catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
        var actualParts = actualRoot.getAllParts(); var expectedParts = expectedRoot.getAllParts();
        for (int i = 0; i < actualParts.size(); i++) {
            float[] a = values(actualParts.get(i)), b = values(expectedParts.get(i));
            for (int j = 0; j < a.length; j++) {
                comparisons++;
                require(Math.abs(a[j] - b[j]) < .002F,
                        stage + ": stale dig/emerge model transform part=" + i + " component=" + j + " actual=" + a[j] + " expected=" + b[j]);
            }
        }
        require(!actual.isInvisible && !actual.diggingAnimationState.isStarted()
                && (!standing || !actual.emergeAnimationState.isStarted()), stage + ": pose ownership is released");
    }
    private static float[] values(ModelPart p) { return new float[]{p.x,p.y,p.z,p.xRot,p.yRot,p.zRot,p.xScale,p.yScale,p.zScale}; }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
