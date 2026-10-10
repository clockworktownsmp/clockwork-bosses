package clockwork.bosses;

/** Entity-owned death handoff and durable claim. No global queue can retain unloaded bosses. */
public interface ClockworkBossRewardData {
    void clockworkBosses$prepareReward(ClockworkBossRewards.DeathReward reward);
    ClockworkBossRewards.DeathReward clockworkBosses$takeReward();
    boolean clockworkBosses$claimReward();
    boolean clockworkBosses$rewardClaimed();
}
