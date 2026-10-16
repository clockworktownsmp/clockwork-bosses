package clockwork.bosses.api;

import java.util.UUID;

/** Immutable native values, in HP (two HP per heart). No entity is retained.
 * Alive/removed describe this instant, not an inferred fight outcome. */
public record BossVitals(UUID entityId, float health, float maxHealth, float absorption,
                         boolean alive, boolean removed) {}
