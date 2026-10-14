package clockwork.bosses;

import java.util.UUID;

/** Internal server provenance for native projectiles, clouds and primed TNT.
 * This is not an addon registration API or synchronized entity-data extension. */
public interface ClockworkBossPlayerHarmSource {
    /** A known player UUID is returned only while it matches native ownership. */
    UUID clockworkBosses$playerOrigin();
}
