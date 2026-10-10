package clockwork.bosses;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** One timestamp per connected recipient, shared across all encounters; no scheduled cleanup task. */
public final class ClockworkBossTitleThrottle {
    private record Stamp(int tick, int cooldown, boolean targeted) {}
    private final Map<UUID, Stamp> lastTitles = new HashMap<>();

    /** Unsigned tick differences retain a short cooldown across the native server tick counter's wrap. */
    public boolean acquire(UUID player, int now, int cooldown) {
        return acquire(player, now, cooldown, false);
    }

    /** Becoming the target may replace a spectator warning about somebody else.
     * Actual target warnings still share a cooldown across bosses, preventing
     * several simultaneous encounters from repeatedly replacing the same HUD. */
    public boolean acquireTarget(UUID player, int now, int cooldown) {
        return acquire(player, now, cooldown, true);
    }
    private boolean acquire(UUID player, int now, int cooldown, boolean targeted) {
        Stamp previous = lastTitles.get(player);
        if (previous != null && !(targeted && !previous.targeted())
                && Integer.toUnsignedLong(now - previous.tick()) < Math.max(cooldown, previous.cooldown())) return false;
        lastTitles.put(player, new Stamp(now, cooldown, targeted));
        return true;
    }
    public void leave(UUID player) { lastTitles.remove(player); }
    public void clear() { lastTitles.clear(); }
}
