package clockwork.bosses.api;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Captured once, just after the encounter-end claim, before action stop or any
 * player-support restoration. ENDED still runs at its existing later boundary.
 * Players include the current viewer membership and any remaining support owners.
 * Missing loaded entities are explicit; zero health is never invented for them.
 * Unloaded means suspension, not death, victory or reward delivery. */
public record BossTerminalSnapshot(BossEncounterSnapshot encounter, BossAction.StopReason reason,
                                   Optional<BossVitals> boss, Map<UUID, BossVitals> players,
                                   Set<UUID> unavailablePlayers) {
    public BossTerminalSnapshot {
        players = Map.copyOf(players);
        unavailablePlayers = Set.copyOf(unavailablePlayers);
    }
}
