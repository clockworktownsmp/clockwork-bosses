package clockwork.bosses.api;

import java.util.Set;
import java.util.UUID;

/** Immutable event data. UUIDs and names can safely outlive the callback; no
 * entity, world, player or mutable encounter state is retained by this record. */
public record BossEncounterSnapshot(UUID bossId, String template, String name,
                                    String dimension, String phase, String subphase,
                                    long gameTime, Set<UUID> participants) {
    public BossEncounterSnapshot { participants = Set.copyOf(participants); }
}
