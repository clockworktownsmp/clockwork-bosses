package clockwork.bosses.api;

/** Immutable optional cue for a native roar. Blank filename keeps its vanilla
 * sound. Custom OGGs use the current template's sounds directory and the core's
 * existing validated media delivery; no new addon packet or file access is needed.
 * Range 0 resolves the arena radius/box diagonal when triggered; positive ranges
 * are manual distances, capped at 512 blocks (playback has a one-block minimum). */
public record BossRoarSound(String file, double volume, double pitch, double range) {
    public static final BossRoarSound VANILLA = new BossRoarSound("", 1, 1, 32);
    public BossRoarSound {
        if (file == null || !file.isEmpty() && !clockwork.bosses.ClockworkBossSoundPayload.isValidFileName(file)
                || !Double.isFinite(volume) || volume < 0 || volume > 4
                || !Double.isFinite(pitch) || pitch < .5 || pitch > 2
                || !Double.isFinite(range) || range < 0 || range > clockwork.bosses.ClockworkBossSoundDistance.MAX_RANGE)
            throw new IllegalArgumentException("Invalid roar sound: use a lowercase OGG filename, volume 0–4, pitch 0.5–2, range 0–512 (0 = automatic arena distance)");
    }
}
