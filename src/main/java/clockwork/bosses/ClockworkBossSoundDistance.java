package clockwork.bosses;

import net.minecraft.world.phys.AABB;
import java.util.List;

/** Shared spatial audio limits. Zero is a template setting, never a playback-packet distance. */
public final class ClockworkBossSoundDistance {
    public static final double AUTOMATIC = 0;
    public static final float MAX_RANGE = 512;
    public static final float LEGACY_FALLBACK = 32;
    /** Creation defaults differ from omission in older templates. Share these
     * paths with the editor so opening an old file cannot silently select Auto. */
    public static final List<String> BASE_RANGE_PATHS = List.of("sounds.spawn.range", "sounds.phase_change.range",
            "sounds.death.range", "voice_lines.range", "attacks.roar.sound.range");
    private ClockworkBossSoundDistance() {}

    /** Resolve from compiled arena geometry on a sound event, not a tick or world scan.
     * Native AABBs already include max block + 1; their full diagonal counts inclusive dimensions.
     * Activation geometry is separate. Missing/invalid arena geometry keeps the old 32-block fallback. */
    public static float resolve(double configured, ClockworkBossState.Areas areas) {
        if (!Double.isFinite(configured)) return LEGACY_FALLBACK;
        if (configured != AUTOMATIC) return playback(configured);
        if (areas == null || !areas.visibilityConfigured()) return LEGACY_FALLBACK;
        if (areas.visibilityUsesRadius()) return automatic(areas.visibilityRadius());
        // Fixed boxes require a dimension, as in the encounter's audience logic.
        AABB box = areas.visibilityDimension() == null ? null : areas.visibilityBox();
        if (box == null) return LEGACY_FALLBACK;
        double x = box.maxX - box.minX, y = box.maxY - box.minY, z = box.maxZ - box.minZ;
        return automatic(Math.hypot(Math.hypot(x, y), z));
    }

    /** Bound native recipient queries/client attenuation; sub-block manual values retain the old minimum. */
    public static float playback(double resolved) {
        return Double.isFinite(resolved) ? (float) Math.clamp(resolved, 1, MAX_RANGE) : LEGACY_FALLBACK;
    }
    /** A float packet must not round Auto down and exclude a point on the computed boundary. */
    private static float automatic(double distance) {
        if (!Double.isFinite(distance)) return LEGACY_FALLBACK;
        double bounded = Math.clamp(distance, 1, MAX_RANGE);
        float result = (float) bounded;
        return result < bounded ? Math.nextUp(result) : result;
    }
}
