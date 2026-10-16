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
import net.minecraft.client.animation.definitions.WardenAnimation;
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
    private static int comparisons, guiChecks;
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
            context.waitFor(client -> client.level.getGameTime() >= 120);
            context.runOnClient(this::check);
            context.runOnClient(client -> guiChecks = clockwork.bosses.client.ClockworkBossBurrowGuiChecks.run(client));
            context.runOnClient(client -> Files.writeString(Path.of("burrow-client-result.txt"),
                    "PASS:3cycles; planted-feet cutoff/late tracking/full native initial control; comparisons=" + comparisons
                            + "; gui_checks=" + guiChecks + "; native smash utility/dispatch checked; ordinary opt-out retained; detached entity, no network/storage/pixel claim\n"));
        } catch (Exception | AssertionError failure) {
            try { Files.writeString(Path.of("burrow-client-result.txt"), "FAIL:" + failure + "\n"); }
            catch (java.io.IOException writing) { failure.addSuppressed(writing); }
            throw new AssertionError("Burrow client visibility regression", failure);
        }
    }
    private void check(Minecraft client) {
        checkNativeBurst(client);
        Warden boss = new Warden(EntityTypes.WARDEN, client.level);
        // Detached entities bypass normal level insertion, which assigns native IDs.
        boss.setId(Integer.MAX_VALUE - 1);
        ((ClockworkBossMarker) boss).clockworkBosses$setClockworkBoss(true);
        var marker = (ClockworkBossMarker) boss;
        WardenRenderer renderer = (WardenRenderer) client.getEntityRenderDispatcher().getRenderer(boss);
        require(ClockworkBossBurrowEmergence.DURATION_TICKS == 34, "5000ms planted-feet cutoff rounds up to 34 ticks at 3x");
        require(ClockworkBossBurrowEmergence.millis(-1, 100, 0) == -1
                && ClockworkBossBurrowEmergence.millis(101, 100, .5F) == 0
                && ClockworkBossBurrowEmergence.millis(100, 101, Float.NaN) == 150,
                "absent/future/nonfinite timing stays bounded");
        for (int cycle = 0; cycle < 3; cycle++) {
            boss.setPose(Pose.DIGGING); boss.tickCount += 45; boss.setInvisible(true);
            WardenRenderState buried = new WardenRenderState(); renderer.extractRenderState(boss, buried, 0);
            require(boss.diggingAnimationState.isStarted() && buried.isInvisible,
                    "digging remains active and hidden underground");
            boss.setInvisible(false); boss.setPose(Pose.EMERGING);
            require(boss.emergeAnimationState.getTimeInMillis(boss.tickCount) == 0,
                    "each emergence starts a fresh full native animation");
            for (int elapsed : new int[]{0, 1, 10, 20, 30, 31, 33, 34, 80}) {
                // Server game time, not entity age or receipt time, drives playback.
                marker.clockworkBosses$beginBurrowEmergence(client.level.getGameTime() - elapsed);
                for (float partial : new float[]{0, .5F}) {
                    long expectedMillis = Math.min(5000, (long) ((elapsed + partial) * 150));
                    compareEmergence(client, renderer, boss, "accelerated_" + cycle + "_" + elapsed + "_" + partial,
                            partial, expectedMillis);
                }
            }
            require(boss.emergeAnimationState.isStarted(), "full emergence is preserved");
            marker.clockworkBosses$clearBurrowEmergence(); boss.setPose(Pose.STANDING);
            boss.attackAnimationState.start(boss.tickCount); boss.tickCount += 8;
            compare(client, renderer, boss, "attacking_" + cycle, true);
            require(!boss.isInvisible(), "next attack remains visible");
        }
        // A newly tracked client starts its native state now, but samples 20
        // elapsed server ticks (3000 ms of the native clip), not frame zero.
        boss.tickCount = 3; boss.setPose(Pose.EMERGING);
        marker.clockworkBosses$beginBurrowEmergence(client.level.getGameTime() - 20);
        require(boss.emergeAnimationState.getTimeInMillis(boss.tickCount) == 0, "late tracking native state is fresh");
        compareEmergence(client, renderer, boss, "late_tracking_20", 0, 3000);
        marker.clockworkBosses$clearBurrowEmergence(); boss.setPose(Pose.STANDING);
        boss.setPose(Pose.EMERGING); boss.tickCount += 20;
        compareEmergence(client, renderer, boss, "initial_native_20", 0, 1000);
        boss.tickCount += 114;
        compareEmergence(client, renderer, boss, "initial_native_134", 0, 6700);
        boss.setPose(Pose.STANDING);
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
    private static void checkNativeBurst(Minecraft client) {
        int[] particles = {0};
        var random = net.minecraft.util.RandomSource.create(12345L);
        var stone = net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
        var level = (net.minecraft.world.level.LevelAccessor) java.lang.reflect.Proxy.newProxyInstance(
                ClockworkBossBurrowVisibilityClientTest.class.getClassLoader(), new Class<?>[]{net.minecraft.world.level.LevelAccessor.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getRandom")) return random;
                    if (method.getName().equals("getBlockState")) return stone;
                    if (method.getName().equals("addParticle")) {
                        require(args[0] instanceof net.minecraft.core.particles.BlockParticleOption option
                                && option.getType() == net.minecraft.core.particles.ParticleTypes.DUST_PILLAR
                                && option.getState().equals(stone), "native burst uses sampled ground DUST_PILLAR");
                        for (int index=1;index<args.length;index++) if(args[index] instanceof Double number)
                            require(Double.isFinite(number), "native particle coordinates and velocities finite");
                        particles[0]++; return null;
                    }
                    throw new AssertionError("Unexpected native particle utility operation: " + method);
                });
        net.minecraft.util.ParticleUtils.spawnSmashAttackParticles(level, new net.minecraft.core.BlockPos(0,64,0),750);
        require(particles[0] == 750,"native mace utility creates 750 bounded particles");
        // Exercise the real client event branch separately. The proxy proves
        // option/count semantics; this call proves dispatch does not throw.
        client.level.levelEvent(net.minecraft.world.level.block.LevelEvent.PARTICLES_SMASH_ATTACK, client.player.blockPosition().below(),750);
    }
    private static void compareEmergence(Minecraft client, WardenRenderer renderer, Warden boss,
                                         String stage, float partial, long expectedMillis) {
        WardenRenderState actual = new WardenRenderState(), expected = new WardenRenderState();
        renderer.extractRenderState(boss, actual, partial); renderer.extractRenderState(boss, expected, partial);
        long sampled = ((WardenRenderStateVisuals) actual).clockworkBosses$getBurrowEmergenceMillis();
        require(sampled == (stage.startsWith("initial_") ? -1 : expectedMillis), stage + ": renderer samples shared time");
        require(!actual.diggingAnimationState.isStarted() && actual.emergeAnimationState.isStarted(),
                stage + ": only the current emergence owns a native animation");
        expected.emergeAnimationState.stop();
        ModelPart actualRoot = client.getEntityModels().bakeLayer(ModelLayers.WARDEN);
        ModelPart expectedRoot = client.getEntityModels().bakeLayer(ModelLayers.WARDEN);
        new WardenModel(actualRoot).setupAnim(actual); new WardenModel(expectedRoot).setupAnim(expected);
        // Independent native reference: apply the entire original keyframe clip
        // to the same idle model at an explicitly calculated threefold time.
        WardenAnimation.WARDEN_EMERGE.bake(expectedRoot).apply(expectedMillis, 1F);
        assertParts(actualRoot, expectedRoot, stage);
        try { Files.writeString(Path.of("burrow-client-model.jsonl"),
                "{\"stage\":\"" + stage + "\",\"sample_ms\":" + sampled
                        + ",\"reference_ms\":" + expectedMillis + ",\"max_transform_difference\":"
                        + maximumDifference(actualRoot, expectedRoot) + "}\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
        catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
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
        assertParts(actualRoot, expectedRoot, stage);
        require(!actual.isInvisible && !actual.diggingAnimationState.isStarted()
                && (!standing || !actual.emergeAnimationState.isStarted()), stage + ": pose ownership is released");
    }
    private static void assertParts(ModelPart actualRoot, ModelPart expectedRoot, String stage) {
        var actualParts = actualRoot.getAllParts(); var expectedParts = expectedRoot.getAllParts();
        for (int i = 0; i < actualParts.size(); i++) {
            float[] a = values(actualParts.get(i)), b = values(expectedParts.get(i));
            for (int j = 0; j < a.length; j++) {
                comparisons++;
                require(Math.abs(a[j] - b[j]) < .002F,
                        stage + ": stale dig/emerge model transform part=" + i + " component=" + j + " actual=" + a[j] + " expected=" + b[j]);
            }
        }
    }
    private static float[] values(ModelPart p) { return new float[]{p.x,p.y,p.z,p.xRot,p.yRot,p.zRot,p.xScale,p.yScale,p.zScale}; }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
