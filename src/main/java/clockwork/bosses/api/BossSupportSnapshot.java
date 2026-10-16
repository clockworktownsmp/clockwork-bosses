package clockwork.bosses.api;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Read-only support state at an event boundary. Only primitive values/ids are
 * retained. Overlapping encounters can legitimately retain support after one
 * owner leaves. External effects/equipment are never removed for diagnostics. */
public record BossSupportSnapshot(BossVitals vitals, Health health, Effects effects, Potion potion) {
    /** The baseline removes only clockwork_bosses:arena_health on a scratch native
     * attribute. Predicted values exclude the ending owner, preserve other owners,
     * and use the current native modifier math and health-restoration policy.
     * They are predictions before cleanup: external callbacks can change them.
     * Modifiers/baseValue describe the actual MAX_HEALTH attribute at capture. */
    public record Health(Set<UUID> owners, double baseValue, double baselineMaxHealth,
                         double predictedMaxHealth, double predictedHealth,
                         boolean ownedModifierPresent, List<Modifier> modifiers) {
        public Health { owners = Set.copyOf(owners); modifiers = List.copyOf(modifiers); }
    }
    public record Modifier(String id, double amount, String operation) {}
    /** Actual tagged native grants/markers are inspected even if an earlier
     * release lost its membership record or another mod vetoed removal. */
    public record Effects(Set<UUID> owners, Map<String, Integer> ownedAmplifiers, Set<String> markers) {
        public Effects {
            owners = Set.copyOf(owners); ownedAmplifiers = Map.copyOf(ownedAmplifiers); markers = Set.copyOf(markers);
        }
    }
    /** Bottle count covers marked inventory/cursor stacks, not world containers.
     * The shared ready tick is intentionally persistent across encounters and
     * death/rejoin; release does not reset it or claim to erase native cooldowns. */
    public record Potion(Set<UUID> owners, int bottles, boolean drinking, long readyTick) {
        public Potion { owners = Set.copyOf(owners); }
    }
}
