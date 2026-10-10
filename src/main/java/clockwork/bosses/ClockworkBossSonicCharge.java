package clockwork.bosses;

import java.util.Objects;
import java.util.UUID;

/** One immutable telegraph/hit definition; phase transitions and reload cannot mix old timing with new damage. */
public record ClockworkBossSonicCharge(UUID targetUuid, long fireTick,
                                   ClockworkBossSettings.SonicBoom settings, boolean yieldsToMelee, long generation) {
    public ClockworkBossSonicCharge {
        Objects.requireNonNull(targetUuid);
        Objects.requireNonNull(settings);
        if (!settings.enabled()) throw new IllegalArgumentException("Cannot charge a disabled Sonic Boom");
    }

    /** Capture every beam setting and the close-range rule once when its charge feedback starts. */
    public static ClockworkBossSonicCharge start(UUID target, long now, ClockworkBossSettings.Phase phase) {
        return start(target, now, phase, -1);
    }
    public static ClockworkBossSonicCharge start(UUID target, long now, ClockworkBossSettings.Phase phase, long generation) {
        int delay = phase.sonic().chargeTime();
        // Unusual externally modified world times must not overflow into an immediate hit.
        long deadline = now > Long.MAX_VALUE - delay ? Long.MAX_VALUE : now + delay;
        return new ClockworkBossSonicCharge(target, deadline, phase.sonic(), phase.meleeEnabled(), generation);
    }

    public boolean due(long now) { return now >= fireTick; }
}
