package clockwork.bosses;

import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.level.GameType;

/** Native server regressions for saved aura settings. Embedded players exercise
 * effect application and timing, not client rendering or the live server.
 * Separate native environments prevent other fixtures' manager resets, and
 * overlapping large-radius auras, from invalidating these long timing checks. */
public class ClockworkBossDarknessGameTests {
    /** Underground edits must not replace explicit phase-one values. The second
     * configuration uses the owner's reported screenshot values. */
    @GameTest(environment = "clockwork_bosses_regression:darkness_instant")
    public void phase_one_uses_its_own_settings(GameTestHelper helper) throws Exception {
        try (var fixture = new AuraFixture(helper, "active_settings")) {
            fixture.apply();
            helper.assertTrue(fixture.effect().getDuration() == 40,
                    "phase one uses its 40-tick center duration, not dormant's 100");
            long now = helper.getLevel().getGameTime();
            helper.assertTrue(fixture.state.darknessNextTicks().get(fixture.player().getUUID()) == now + 200,
                    "phase-one default interval is 200 ticks");
            fixture.screenshotValues();
            fixture.reloadSnapshot();
            fixture.apply();
            helper.assertTrue(fixture.effect().getDuration() == 150,
                    "saved phase-one override applies its 150-tick center duration");
            helper.assertTrue(fixture.state.darknessNextTicks().get(fixture.player().getUUID()) == now + 100
                            && fixture.state.settings().phase(ClockworkBossPhase.ANNOYED).darkness().maxRadius() == 100,
                    "saved radius and center interval replace defaults");
            helper.succeed();
        }
    }

    /** Advance the real effect clock once per test tick. Default short duration
     * must expire, stay absent through the gap, then return on its deadline. */
    @GameTest(environment = "clockwork_bosses_regression:darkness_default", maxTicks = 225)
    public void phase_one_default_obeys_duration_and_interval(GameTestHelper helper) throws Exception {
        var fixture = new AuraFixture(helper, "default_timing");
        fixture.apply();
        for (int delay = 1; delay <= 200; delay++) {
            int elapsed = delay;
            helper.runAfterDelay(delay, () -> {
                try {
                    fixture.advance();
                    if (elapsed == 39) helper.assertTrue(fixture.effect() != null, "default aura lasts 40 ticks");
                    if (elapsed == 40 || elapsed == 199)
                        helper.assertTrue(fixture.effect() == null, "default aura has the configured gap at tick " + elapsed);
                    if (elapsed == 200) {
                        helper.assertTrue(fixture.effect() != null && fixture.effect().getDuration() == 40,
                                "default aura reapplies after 200 ticks: " + fixture.details());
                        fixture.close();
                        helper.succeed();
                    }
                } catch (RuntimeException | Error failure) { fixture.close(); throw failure; }
            });
        }
    }

    /** The screenshot's center duration exceeds its interval. Its remaining
     * half-duration allows the existing native effect to refresh continuously. */
    @GameTest(environment = "clockwork_bosses_regression:darkness_screenshot", maxTicks = 225)
    public void phase_one_screenshot_values_refresh_continuously(GameTestHelper helper) throws Exception {
        var fixture = new AuraFixture(helper, "screenshot_timing");
        fixture.screenshotValues(); fixture.reloadSnapshot(); fixture.apply();
        for (int delay = 1; delay <= 200; delay++) {
            int elapsed = delay;
            helper.runAfterDelay(delay, () -> {
                try {
                    fixture.advance();
                    helper.assertTrue(fixture.effect() != null, "center darkness remains present at tick " + elapsed);
                    if (elapsed == 100 || elapsed == 200)
                        helper.assertTrue(fixture.effect().getDuration() == 150, "center refresh uses 150 ticks: " + fixture.details());
                    if (elapsed == 200) { fixture.close(); helper.succeed(); }
                } catch (RuntimeException | Error failure) { fixture.close(); throw failure; }
            });
        }
    }

    /** Production's dormant watcher is driven by the native server tick, rather
     * than calling the active-AI hook on a boss whose AI is intentionally off. */
    @GameTest(environment = "clockwork_bosses_regression:darkness_dormant", maxTicks = 25)
    public void dormant_watcher_uses_separate_settings(GameTestHelper helper) throws Exception {
        var fixture = new AuraFixture(helper, "dormant_settings");
        fixture.state.setDormant(true);
        ((ClockworkBossMarker) fixture.boss).clockworkBosses$setDormant(true);
        helper.runAfterDelay(10, () -> {
            try {
                helper.assertTrue(fixture.state.dormant(), "unconfigured activation does not start an encounter");
                helper.assertTrue(fixture.effect() != null && fixture.effect().getDuration() == 100,
                        "existing dormant watcher applies its independent 100-tick center duration: " + fixture.details());
                helper.succeed();
            } finally { fixture.close(); }
        });
    }

    /** A reload invalidates old deadlines. Disable stops new applications while
     * preserving already granted native effects for their normal expiration. */
    @GameTest(environment = "clockwork_bosses_regression:darkness_instant")
    public void darkness_reload_resets_deadlines_and_disable_preserves_effect(GameTestHelper helper) throws Exception {
        try (var fixture = new AuraFixture(helper, "reload_settings")) {
            fixture.apply();
            fixture.state.darknessNextTicks().put(fixture.player().getUUID(), helper.getLevel().getGameTime() + 12_000);
            fixture.state.setNextDarknessScanTick(helper.getLevel().getGameTime() + 12_000);
            fixture.screenshotValues(); fixture.reloadSnapshot(); fixture.apply();
            helper.assertTrue(fixture.effect().getDuration() == 150,
                    "configuration transition clears long old deadlines before applying new settings");
            fixture.phaseAura().addProperty("enabled", false);
            fixture.reloadSnapshot(); fixture.apply();
            helper.assertTrue(fixture.effect().getDuration() == 150 && fixture.state.darknessNextTicks().isEmpty(),
                    "disabled aura stops refreshes without deleting an existing effect");
            fixture.player().removeEffect(MobEffects.DARKNESS); fixture.apply();
            helper.assertTrue(fixture.effect() == null, "disabled aura cannot reapply darkness");
            helper.succeed();
        }
    }

    /** Geometry includes height; spectators leave the deadline map, and stronger
     * Darkness from another source must not be replaced by this aura. */
    @GameTest(environment = "clockwork_bosses_regression:darkness_instant")
    public void darkness_range_spectator_and_effect_priority(GameTestHelper helper) throws Exception {
        try (var fixture = new AuraFixture(helper, "range_priority")) {
            fixture.phaseAura().addProperty("max_radius", 4); fixture.reloadSnapshot();
            fixture.player().snapTo(fixture.boss.position().add(0, 3, 0)); fixture.apply();
            helper.assertTrue(fixture.effect() != null && fixture.effect().getDuration() == 25,
                    "3D distance blends edge/center duration");
            fixture.player().removeEffect(MobEffects.DARKNESS);
            fixture.player().snapTo(fixture.boss.position().add(0, 5, 0));
            fixture.state.setNextDarknessScanTick(0); fixture.apply();
            helper.assertTrue(fixture.effect() == null && fixture.state.darknessNextTicks().isEmpty(),
                    "above-radius player receives no aura and is pruned");
            fixture.player().snapTo(fixture.boss.position()); fixture.player().setGameMode(GameType.SPECTATOR);
            fixture.state.setNextDarknessScanTick(0); fixture.apply();
            helper.assertTrue(fixture.effect() == null, "spectator is excluded");
            fixture.player().setGameMode(GameType.SURVIVAL);
            fixture.player().addEffect(new MobEffectInstance(MobEffects.DARKNESS, 400, 2), fixture.boss);
            fixture.state.setNextDarknessScanTick(0); fixture.apply();
            helper.assertTrue(fixture.effect().getAmplifier() == 2 && fixture.effect().getDuration() == 400,
                    "stronger native effect is preserved");
            helper.succeed();
        }
    }

    /** Each fixture owns only its boss/player. AI is paused so timing assertions
     * explicitly drive the production aura hook; the dormant test uses its real
     * watcher. No manual player ticks enter the distributable mod. */
    private static final class AuraFixture implements AutoCloseable {
        final JsonObject config;
        final Warden boss;
        final ClockworkBossState state;
        final ClockworkBossCombatGameTests.PlayerFixture connection;
        private boolean closed;

        AuraFixture(GameTestHelper helper, String suffix) throws Exception {
            config = ClockworkBossCombatGameTests.config(1000);
            var bar = config.getAsJsonObject("boss_bar");
            bar.getAsJsonObject("player_health").addProperty("enabled", false);
            bar.getAsJsonObject("reusable_potion").addProperty("enabled", false);
            bar.getAsJsonObject("area_effects").addProperty("enabled", false);
            config.getAsJsonObject("voice_lines").addProperty("enabled", false);
            for (var phase : config.getAsJsonObject("phases").entrySet()) {
                phase.getValue().getAsJsonObject().getAsJsonObject("melee").addProperty("enabled", false);
                phase.getValue().getAsJsonObject().getAsJsonObject("sonic_boom").addProperty("enabled", false);
            }
            var dormant = config.getAsJsonObject("dormant_darkness");
            dormant.addProperty("center_duration_ticks", 100);
            dormant.addProperty("edge_duration_ticks", 60);
            dormant.addProperty("center_interval_ticks", 200);
            dormant.addProperty("edge_interval_ticks", 400);
            dormant.addProperty("max_radius", 50);
            boss = ClockworkBossCombatGameTests.boss(helper, config.deepCopy(), "darkness_" + suffix, false, true);
            boss.setNoAi(true); boss.setNoGravity(true);
            state = ClockworkBossManager.stateFor(boss);
            connection = new ClockworkBossCombatGameTests.PlayerFixture(helper, boss.position());
            player().setNoGravity(true);
            reloadSnapshot();
        }

        net.minecraft.server.level.ServerPlayer player() { return connection.player; }
        MobEffectInstance effect() { return player().getEffect(MobEffects.DARKNESS); }
        JsonObject phaseAura() { return config.getAsJsonObject("phases").getAsJsonObject("annoyed").getAsJsonObject("darkness"); }
        void screenshotValues() {
            var aura = phaseAura();
            aura.addProperty("max_radius", 100);
            aura.addProperty("edge_interval_ticks", 150); aura.addProperty("center_interval_ticks", 100);
            aura.addProperty("edge_duration_ticks", 100); aura.addProperty("center_duration_ticks", 150);
        }
        void reloadSnapshot() {
            if (!state.setConfig(config.deepCopy()) || !state.setCurrentPhase(null))
                throw new IllegalStateException("Fixture configuration transition was interrupted");
            ClockworkBossManager.prepareCombatPhase(boss);
        }
        void apply() { ClockworkBossManager.tickClockworkBossLogic(boss); }
        void advance() {
            player().doTick();
            player().snapTo(boss.position());
            apply();
        }
        String details() {
            return "effect=" + effect() + " gameTime=" + boss.level().getGameTime()
                    + " due=" + state.darknessNextTicks().get(player().getUUID())
                    + " scan=" + state.nextDarknessScanTick() + " phase=" + state.currentPhase()
                    + " dormant=" + state.dormant() + " removed=" + boss.isRemoved()
                    + " distance=" + player().distanceTo(boss);
        }
        @Override public void close() {
            if (closed) return;
            closed = true;
            boss.discard(); connection.close();
        }
    }
}
