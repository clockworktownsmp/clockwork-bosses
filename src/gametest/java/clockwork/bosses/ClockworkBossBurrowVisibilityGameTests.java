package clockwork.bosses;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.phys.Vec3;

/** Disposable native tests shared by the unchanged 0.2.0 and dev test harnesses.
 * Server invisibility and animation ownership are recorded independently. */
public final class ClockworkBossBurrowVisibilityGameTests implements net.fabricmc.api.ModInitializer {
    private static final Path RECEIPT = Path.of("burrow-visibility-native.jsonl");

    @Override public void onInitialize() {
        if (!Boolean.getBoolean("clockwork.bosses.burrow.tests")) return;
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server -> {
            try {
                if (server.getTickCount() % 20 == 0) Files.writeString(Path.of("burrow-heartbeat.txt"), Integer.toString(server.getTickCount()));
                if (Files.exists(Path.of("burrow-stop.request"))) server.halt(false);
            } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
        });
    }

    @GameTest public void burrow_pose_transition_releases_old_animation(GameTestHelper helper) {
        Warden boss = helper.spawn(EntityTypes.WARDEN, new Vec3(2, 2, 2));
        Warden ordinary = helper.spawn(EntityTypes.WARDEN, new Vec3(4, 2, 2));
        boss.setNoAi(true); ordinary.setNoAi(true);
        ((ClockworkBossMarker) boss).clockworkBosses$setClockworkBoss(true);
        try {
            boss.setInvisible(false);
            boss.setPose(Pose.DIGGING);
            helper.assertTrue(boss.diggingAnimationState.isStarted(), "native digging starts normally");
            boss.setInvisible(true);
            record("pose", "buried", boss);
            helper.assertTrue(boss.isInvisible(), "underground server flag remains hidden");
            boss.setInvisible(false);
            boss.setPose(Pose.EMERGING);
            record("pose", "emerging", boss);
            helper.assertTrue(!boss.isInvisible(), "server invisibility is already restored");
            helper.assertTrue(!boss.diggingAnimationState.isStarted(),
                    "obsolete native digging animation must stop when emergence begins");
            helper.assertTrue(boss.emergeAnimationState.isStarted(), "full native emergence starts");
            boss.setPose(Pose.STANDING);
            helper.assertTrue(!boss.emergeAnimationState.isStarted(), "emergence stops on return to standing");
            boss.setPose(Pose.DIGGING); boss.setPose(Pose.STANDING);
            helper.assertTrue(!boss.diggingAnimationState.isStarted() && !boss.isInvisible(),
                    "interruption before burial releases animation without hiding the boss");

            // The patch must not change vanilla's one-way dig/despawn lifecycle.
            ordinary.setPose(Pose.DIGGING); ordinary.setPose(Pose.EMERGING);
            helper.assertTrue(ordinary.diggingAnimationState.isStarted(), "ordinary Warden is unchanged");
            helper.succeed();
        } finally { boss.discard(); ordinary.discard(); }
    }

    @GameTest(maxTicks = 700)
    public void burrow_real_action_repeats_and_attacks_visibly(GameTestHelper helper) throws Exception {
        Warden boss = fixture(helper, "cycle");
        var victim = helper.spawn(EntityTypes.COW, new Vec3(3, 2, 2));
        victim.setNoAi(true); victim.setNoGravity(true);
        victim.getAttribute(Attributes.MAX_HEALTH).setBaseValue(1000); victim.setHealth(1000);
        boss.setAttackTarget(victim);
        var state = ClockworkBossManager.stateFor(boss);
        int[] attacks = {0}; boolean[] sawBuried = {false}, sawEmerging = {false}, done = {false};
        helper.onEachTick(() -> {
            if (done[0]) return;
            try {
                if (boss.isInvisible()) {
                    sawBuried[0] = true;
                    helper.assertTrue(boss.hasPose(Pose.DIGGING), "server hiding belongs only to buried digging");
                    helper.assertTrue(boss.diggingAnimationState.isStarted(), "digging animation remains during burial");
                }
                if (boss.hasPose(Pose.EMERGING) && !sawEmerging[0]) {
                    sawEmerging[0] = true;
                    record("cycle", "emerging_" + attacks[0], boss);
                    helper.assertTrue(sawBuried[0] && !boss.isInvisible(), "emergence restores visibility after actual burial");
                    helper.assertTrue(!boss.diggingAnimationState.isStarted() && boss.emergeAnimationState.isStarted(),
                            "only the full emergence animation remains");
                }
                if (state.subphase() == null || !state.subphase().key().equals("attack")) return;
                record("cycle", "attack_" + attacks[0], boss);
                helper.assertTrue(sawBuried[0] && sawEmerging[0], "real action crossed burial and emergence");
                helper.assertTrue(!boss.isInvisible() && boss.hasPose(Pose.STANDING)
                        && !boss.diggingAnimationState.isStarted() && !boss.emergeAnimationState.isStarted(),
                        "next attacking subphase is visible with no stale underground animation");
                helper.assertTrue(((ClockworkBossMarker) boss).clockworkBosses$getBurrowCheckpoint() == null
                        && boss.isNoGravity(), "normal completion releases checkpoint and restores prior gravity");
                float before = victim.getHealth();
                victim.snapTo(boss.position().add(1, 0, 0)); victim.setInvulnerableTime(0);
                boss.getSensing().tick();
                boss.doHurtTarget(helper.getLevel(), victim);
                helper.assertTrue(victim.getHealth() < before, "native next-subphase melee actually lands");
                attacks[0]++; sawBuried[0] = false; sawEmerging[0] = false;
                if (attacks[0] == 3) {
                    done[0] = true; boss.discard(); victim.discard(); helper.succeed();
                }
            } catch (RuntimeException | Error failure) {
                done[0] = true; boss.discard(); victim.discard(); throw failure;
            }
        });
    }

    @GameTest(maxTicks = 100)
    public void burrow_interruptions_restore_flags_and_pose(GameTestHelper helper) throws Exception {
        List<Warden> bosses = new ArrayList<>();
        for (String name : List.of("phase", "reload", "remove", "unload_callback", "failed_stop", "prehidden"))
            bosses.add(fixture(helper, name, name.equals("prehidden")));
        boolean[] done = {false};
        helper.onEachTick(() -> {
            if (done[0] || bosses.stream().anyMatch(b -> !b.isInvisible())) return;
            done[0] = true;
            try {
                Warden phase = bosses.get(0);
                phase.setHealth(400); ClockworkBossManager.tickClockworkBossLogic(phase);
                Warden reload = bosses.get(1);
                helper.assertTrue(ClockworkBossManager.stateFor(reload).setConfig(base()), "reload replaces action config");
                ClockworkBossManager.prepareCombatPhase(reload);
                bosses.get(2).discard();
                // This invokes the native event receiver, not a physical chunk-unload test.
                net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents.ENTITY_UNLOAD.invoker()
                        .onUnload(bosses.get(3), helper.getLevel());
                ClockworkBossManager.stateFor(bosses.get(4)).actionRuntime()
                        .stop(clockwork.bosses.api.BossAction.StopReason.FAILED);
                ClockworkBossManager.stateFor(bosses.get(5)).setConfig(base());
                for (int i = 0; i < bosses.size(); i++) {
                    Warden boss = bosses.get(i); record("interrupt", Integer.toString(i), boss);
                    helper.assertTrue(boss.isInvisible() == (i == 5) && boss.hasPose(Pose.STANDING) && boss.isNoGravity()
                            && !boss.diggingAnimationState.isStarted() && !boss.emergeAnimationState.isStarted(),
                            "interruption restores server flags and obsolete pose animations: case=" + i);
                    helper.assertTrue(((ClockworkBossMarker) boss).clockworkBosses$getBurrowCheckpoint() == null,
                            "interruption releases the recovery journal");
                }
                helper.succeed();
            } finally { bosses.forEach(Warden::discard); }
        });
    }

    private static Warden fixture(GameTestHelper helper, String name) throws Exception {
        return fixture(helper, name, false);
    }
    private static Warden fixture(GameTestHelper helper, String name, boolean initiallyInvisible) throws Exception {
        // The shared combat helper bypasses activation and leaves dormant invisibility
        // set. Establish the actual visible pre-action state before starting burrow.
        Warden boss = ClockworkBossCombatGameTests.boss(helper, base(), "burrow_visibility_" + name);
        boss.setInvisible(initiallyInvisible); boss.setNoGravity(true);
        var config = base();
        var first = config.getAsJsonObject("phases").entrySet().iterator().next().getValue().getAsJsonObject();
        first.addProperty("roar_between_subphases", false);
        first.add("subphases", JsonParser.parseString("""
                {"dig":{"type":"clockwork_bosses:burrow","action_settings":{"underground_ticks":5,"depth":5}},
                 "attack":{"type":"clockwork_bosses:melee","action_settings":{"attacks_before_next":1}}}
                """));
        helper.assertTrue(ClockworkBossManager.stateFor(boss).setConfig(config), "fixture reload starts configured sequence");
        ClockworkBossManager.prepareCombatPhase(boss);
        return boss;
    }
    private static JsonObject base() {
        var config = ClockworkBossCombatGameTests.config(1000);
        for (var phase : config.getAsJsonObject("phases").entrySet()) {
            var settings = phase.getValue().getAsJsonObject();
            settings.getAsJsonObject("melee").addProperty("enabled", true);
            settings.getAsJsonObject("melee").addProperty("damage", 2);
            settings.getAsJsonObject("melee").addProperty("charge_time_ticks", 0);
            settings.getAsJsonObject("melee").addProperty("knockback", 0);
            settings.getAsJsonObject("sonic_boom").addProperty("enabled", false);
        }
        return config;
    }
    private static void record(String test, String stage, Warden boss) {
        JsonObject row = new JsonObject();
        row.addProperty("test", test); row.addProperty("stage", stage);
        row.addProperty("game_time", boss.level().getGameTime()); row.addProperty("pose", boss.getPose().name());
        row.addProperty("server_invisible", boss.isInvisible());
        row.addProperty("digging_started", boss.diggingAnimationState.isStarted());
        row.addProperty("emerging_started", boss.emergeAnimationState.isStarted());
        row.addProperty("removed", boss.isRemoved()); row.addProperty("no_gravity", boss.isNoGravity());
        try { Files.writeString(RECEIPT, row + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
        catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
    }
}
