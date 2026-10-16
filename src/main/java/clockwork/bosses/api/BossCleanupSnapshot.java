package clockwork.bosses.api;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Result of the existing end path, published after ENDED (or from finally if
 * that path throws). Before/after bracket support release, after action stop.
 * After is captured before PLAYER_LEFT/ENDED listeners; delivery follows ENDED.
 * completedNormally means that end path returned, not that restoration matched
 * expectations. Inspect actual ownership/grants/vitals to diagnose restoration.
 * Capture failures are explicit and cannot turn into an all-clear receipt.
 * This is not a reward receipt. Core stores no history or live player references. */
public record BossCleanupSnapshot(BossEncounterSnapshot encounter, BossAction.StopReason reason,
                                  boolean encounterClosed, boolean actionClosed, boolean viewersReleased,
                                  boolean completedNormally, Map<UUID, Player> players,
                                  Set<UUID> unavailablePlayers) {
    public BossCleanupSnapshot { players = Map.copyOf(players); unavailablePlayers = Set.copyOf(unavailablePlayers); }
    public record Player(Optional<BossSupportSnapshot> before, Optional<BossSupportSnapshot> after) {}
}
