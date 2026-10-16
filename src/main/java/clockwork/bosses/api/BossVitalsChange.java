package clockwork.bosses.api;

import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;
import net.minecraft.resources.Identifier;

/** One observed native setHealth/setAbsorptionAmount call on the server thread.
 * Capture is immediately before that setter and in its finally block, including
 * an unchanged/clamped heal (overheal). A rejected hurt/heal that never calls a
 * setter produces no mutation event. This is not nominal attack damage.
 *
 * Before/after include nested calls. The signed exclusive changes subtract
 * observed nested mutations of this same entity; sum those changes, not the
 * inclusive differences. Positive means gained HP/absorption. An incomplete
 * nested observation is marked, never presented as an exact exclusive amount.
 * requestedHealing is the heal call's input, not a measured gain, and occurs
 * only on health writes; multiple writes in a modded heal are not extra doses.
 *
 * Membership/phase/subphase are sampled before the mutation. Membership includes
 * actual viewers and the manager's cached viewer diff, not a fresh geometry query. A known source boss
 * is included too. At most 64 encounters are copied, with truncation explicit.
 * Unknown damage/action attribution stays empty; no source is inferred from a
 * current target. These experimental notifications are synchronous observers.
 * Callback-created mutations are excluded from notification to prevent feedback.
 * Their work remains on the native tick; this API schedules no worker or timer. */
public record BossVitalsChange(BossVitals before, BossVitals after, Field field, Cause cause,
                               Optional<Damage> damage, Optional<Action> action,
                               OptionalDouble requestedHealing, List<Moment> encounters,
                               boolean encountersTruncated, double exclusiveHealthChange,
                               double exclusiveAbsorptionChange, boolean nestedComplete,
                               boolean completedNormally) {
    public BossVitalsChange { encounters = List.copyOf(encounters); }
    /** Inclusive observed differences; do not sum these across nested events. */
    public double netHealthChange() { return (double) after.health() - before.health(); }
    public double netAbsorptionChange() { return (double) after.absorption() - before.absorption(); }
    public enum Field { HEALTH, ABSORPTION }
    public enum Cause { DAMAGE, HEAL, FIGHT_POTION, UNKNOWN }
    /** DamageSource's native message id and entity identities, copied by value. */
    public record Damage(String type, Optional<UUID> attacker, Optional<UUID> directEntity) {}
    /** Supplied only by the core-owned native call, never guessed from phase. */
    public record Action(UUID bossId, Identifier actionId) {}
    public record Moment(UUID bossId, String template, String name, String dimension,
                         String phase, String subphase, long gameTime) {}
}
