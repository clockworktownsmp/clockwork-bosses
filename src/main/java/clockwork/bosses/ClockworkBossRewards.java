package clockwork.bosses;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.monster.warden.Warden;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Separate reward service: native item loot is the first reward type; encounter AI does not issue rewards. */
public final class ClockworkBossRewards {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClockworkBosses.MOD_ID);
    private ClockworkBossRewards() {}

    /** Capture native kill-credit eligibility during the ordinary loot gate; pay only after death commits. */
    public record DeathReward(ClockworkBossLoot loot, DamageSource source, boolean killedByPlayer) {}

    public static void register() {
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof Warden boss && ((ClockworkBossMarker) boss).clockworkBosses$isClockworkBoss())
                payCommittedDeath(boss);
        });
    }

    /** Called only from the native shouldDropLoot branch, which already honors doMobLoot and death vetoes.
     * Keep retains the existing vanilla invocation. Replace skips just its loot table, leaving XP,
     * equipment, custom death hooks and other mods' independent rewards under their normal control. */
    public static boolean prepareDeath(Warden boss, ServerLevel level, DamageSource source, boolean killedByPlayer) {
        var state = ClockworkBossManager.stateFor(boss);
        if (state == null || !state.loot().enabled()) return true;
        var data = (ClockworkBossRewardData) boss;
        if (source.is(DamageTypes.GENERIC_KILL) || data.clockworkBosses$rewardClaimed()) return false;
        ClockworkBossLoot loot = state.loot();
        if (!loot.available(level.getServer())) {
            // A datapack reload can remove a previously valid table. Fail open for vanilla drops and
            // log once at death, rather than silently deleting the normal reward or crashing the server.
            LOGGER.warn("Boss '{}' rewards are missing or incompatible: {}; keeping vanilla drops", state.name(), loot.description());
            return true;
        }
        data.clockworkBosses$prepareReward(new DeathReward(loot, source, killedByPlayer));
        return loot.keepVanilla();
    }

    /** Claim before invoking native loot: reentrant/duplicate death callbacks cannot roll again.
     * The native overload supplies origin, boss, damage source, direct attacker, player and luck.
     * No-player/environmental deaths also pay once; player-only conditions remain native table rules. */
    public static void payCommittedDeath(Warden boss) {
        var data = (ClockworkBossRewardData) boss;
        DeathReward reward = data.clockworkBosses$takeReward();
        if (reward == null || boss.isAlive() || !(boss.level() instanceof ServerLevel level) || !data.clockworkBosses$claimReward()) return;
        try {
            if (reward.loot().source().equals("entries"))
                ClockworkBossLootEntries.drop(reward.loot().entries(), boss, level, reward.source(), reward.killedByPlayer());
            else boss.dropFromLootTable(level, reward.source(), reward.killedByPlayer(), reward.loot().table());
        } catch (RuntimeException failure) {
            // Do not retry a partial roll: items may already have spawned. Retrying would duplicate them.
            LOGGER.error("Could not finish boss loot roll for {}", reward.loot().description(), failure);
        }
    }
}
