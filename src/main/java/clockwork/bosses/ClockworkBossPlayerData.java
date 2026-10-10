package clockwork.bosses;

/** One server-owned game-time deadline survives reconnect, restart and respawn.
 * Native player saving owns it; there is no offline-player map or timer task. */
public interface ClockworkBossPlayerData {
    long clockworkBosses$potionReadyTick();
    void clockworkBosses$setPotionReadyTick(long tick);
}
