package clockwork.bosses;

import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.phys.Vec3;

/** Actual transformed server hooks in disposable worlds. These assertions prove
 * server metadata/event behavior, not client rendering or latency tolerance. */
public final class ClockworkBossFaceGameTests {
    private static JsonObject config() {
        var config = ClockworkBossCombatGameTests.config(1000);
        var brows = config.getAsJsonObject("skin").getAsJsonObject("glowing_eyebrows");
        brows.addProperty("expression", "neutral");
        var events = brows.getAsJsonObject("events"); events.addProperty("enabled", true);
        events.addProperty("hit_duration_ticks",10); events.addProperty("hit_cooldown_ticks",40);
        events.addProperty("target_duration_ticks",5);
        events.getAsJsonObject("melee").addProperty("expression","angry");
        events.getAsJsonObject("sonic_boom").addProperty("expression","curious");
        events.getAsJsonObject("roar").addProperty("expression","sad");
        events.getAsJsonObject("hurt").addProperty("expression","sad");
        events.getAsJsonObject("target_change").addProperty("expression","curious");
        for (var phase : config.getAsJsonObject("phases").entrySet()) {
            var values = phase.getValue().getAsJsonObject();
            values.getAsJsonObject("melee").addProperty("charge_time_ticks",80);
            values.getAsJsonObject("melee").addProperty("recovery_time_ticks",40);
            values.getAsJsonObject("sonic_boom").addProperty("enabled",false);
        }
        return config;
    }
    private static ClockworkBossBrows.Expression shape(Warden boss) {
        return ClockworkBossBrows.unpack(((ClockworkBossMarker)boss).clockworkBosses$getBrows()).expression();
    }
    @GameTest(maxTicks=50, setupTicks=10)
    public void face_hit_overrides_native_melee_then_watcher_restores(GameTestHelper helper) throws Exception {
        var boss = ClockworkBossCombatGameTests.boss(helper,config(),"face_hit");
        boss.setNoGravity(true);
        // Hit from the current opponent: native Warden retaliation may select
        // a different attacker and legitimately cancel the captured old target.
        // Keep that AI behavior separate from this expression-only assertion.
        var player = new ClockworkBossCombatGameTests.PlayerFixture(helper,helper.absoluteVec(new Vec3(3,2,2)));
        player.acknowledgeLoaded(); player.player.setNoGravity(true);
        var victim = player.player;
        try {
        boss.setAttackTarget(victim);
        helper.assertTrue(shape(boss)==ClockworkBossBrows.Expression.CURIOUS,"native target assignment selects target expression");
        boss.doHurtTarget(helper.getLevel(),victim);
        var state = ClockworkBossManager.stateFor(boss); var pending = state.pendingMelee();
        helper.assertTrue(pending!=null && shape(boss)==ClockworkBossBrows.Expression.ANGRY,"native delayed melee starts attack face");
        boss.setInvulnerableTime(0);
        helper.assertTrue(boss.hurtServer(helper.getLevel(),boss.damageSources().playerAttack(player.player),1),"real player hit accepted");
        helper.assertTrue(shape(boss)==ClockworkBossBrows.Expression.SAD,"Fabric accepted damage overrides attack expression");
        helper.assertTrue(state.pendingMelee()==pending,"face reaction does not restart or cancel windup");
        helper.runAfterDelay(25,()-> {
            try {
                helper.assertTrue(shape(boss)==ClockworkBossBrows.Expression.ANGRY,"existing ten-tick watcher restores still-active attack");
                helper.assertTrue(state.pendingMelee()==pending,"expiry retains captured native attack");
                helper.succeed();
            } finally { player.close(); boss.discard(); }
        });
        } catch (Throwable failure) { player.close(); boss.discard(); throw failure; }
    }
    @GameTest public void face_native_visual_tokens_phase_and_reload_cleanup(GameTestHelper helper) throws Exception {
        var config = config(); var boss = ClockworkBossCombatGameTests.boss(helper,config,"face_cleanup");
        var marker = (ClockworkBossMarker)boss; var state = ClockworkBossManager.stateFor(boss);
        try {
            long now = helper.getLevel().getGameTime();
            marker.clockworkBosses$beginMeleeVisual(now,50,20);
            helper.assertTrue(shape(boss)==ClockworkBossBrows.Expression.ANGRY,"real melee visual hook");
            marker.clockworkBosses$beginSonicVisual(now,50,20);
            marker.clockworkBosses$clearMeleeVisual();
            helper.assertTrue(shape(boss)==ClockworkBossBrows.Expression.CURIOUS,"old melee cancellation preserves new sonic pose");
            marker.clockworkBosses$finishSonicVisual(now); marker.clockworkBosses$clearSonicVisual();
            helper.assertTrue(shape(boss)==ClockworkBossBrows.Expression.NEUTRAL,"cancel returns to base");
            long roar = ClockworkBossManager.beginFaceAttack(boss,ClockworkBossFaceEvents.Attack.ROAR,now,84);
            helper.assertTrue(shape(boss)==ClockworkBossBrows.Expression.SAD,"roar lifecycle boundary selects pose");
            ClockworkBossManager.cancelFaceAttack(boss,roar);
            marker.clockworkBosses$beginMeleeVisual(now,50,20);
            boss.setHealth(490); ClockworkBossManager.prepareCombatPhase(boss);
            helper.assertTrue(shape(boss)==ClockworkBossBrows.Expression.NEUTRAL,"health phase clears outgoing pose immediately");
            marker.clockworkBosses$beginSonicVisual(now,50,20);
            helper.assertTrue(state.setConfig(config),"configuration replaces compiled event rules");
            ClockworkBossManager.cancelFaceAttack(boss,roar);
            helper.assertTrue(shape(boss)==ClockworkBossBrows.Expression.NEUTRAL,"reload and stale stop restore base");
            helper.succeed();
        } finally { boss.discard(); }
    }
    @GameTest public void face_disabled_and_environmental_damage_keep_base(GameTestHelper helper) throws Exception {
        var config = config(); var events = config.getAsJsonObject("skin").getAsJsonObject("glowing_eyebrows").getAsJsonObject("events");
        events.addProperty("enabled",false);
        var boss = ClockworkBossCombatGameTests.boss(helper,config,"face_disabled");
        try {
            long now=helper.getLevel().getGameTime();
            ((ClockworkBossMarker)boss).clockworkBosses$beginMeleeVisual(now,20,20);
            helper.assertTrue(shape(boss)==ClockworkBossBrows.Expression.NEUTRAL,"disabled event rules keep chosen static face");
            events.addProperty("enabled",true);
            helper.assertTrue(ClockworkBossManager.stateFor(boss).setConfig(config),"enabled rules reload");
            boss.setInvulnerableTime(0); boss.hurtServer(helper.getLevel(),boss.damageSources().generic(),1);
            helper.assertTrue(shape(boss)==ClockworkBossBrows.Expression.NEUTRAL,"environmental damage does not invoke player-hit expression");
            helper.succeed();
        } finally { boss.discard(); }
    }
}
