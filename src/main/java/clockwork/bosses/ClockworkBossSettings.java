package clockwork.bosses;

import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Immutable, validated settings used in active AI steps. Parse JSON once when a
 * boss loads or reloads; subsequent ticks read primitives without allocating
 * JsonObjects, looking up strings, or reparsing phase thresholds.
 */
public record ClockworkBossSettings(ClockworkBossPhase.Thresholds thresholds,
                                Map<ClockworkBossPhase, Phase> phases, VoiceLines voiceLines,
                                Darkness dormantDarkness, float mobSoundVolume) {
    private static final Darkness DEFAULT_DARKNESS = new Darkness(true, 80, 120, 20, 60, 120, 0, 0);
    /** Compiles every phase with its base fallback before publishing one immutable snapshot. */
    public static ClockworkBossSettings read(JsonObject config) {
        Map<ClockworkBossPhase, Phase> phases = new LinkedHashMap<>();
        ClockworkBossPhase.Thresholds thresholds = ClockworkBossPhase.Thresholds.read(config);
        JsonObject baseMelee = object(config, "attacks", "melee");
        JsonObject baseSonic = object(config, "attacks", "sonic_boom");
        JsonObject baseTargeting = object(config, "targeting");
        // This compatibility fallback is still used by saved pre-phase templates.
        // New templates separate underground Darkness from active phase auras.
        Darkness baseDarkness = readDarkness(object(config, "darkness"), DEFAULT_DARKNESS);
        for (ClockworkBossPhase phase : thresholds.phases()) {
            JsonObject source = object(config, "phases", phase.key());
            JsonObject melee = object(source, "melee");
            JsonObject sonic = object(source, "sonic_boom");
            JsonObject darkness = object(source, "darkness");
            phases.put(phase, new Phase(
                    bool(melee, baseMelee, "enabled", true),
                    integer(melee, baseMelee, "cooldown_ticks", 20, 1, 1_000_000),
                    new SonicBoom(bool(sonic, baseSonic, "enabled", true),
                            number(sonic, baseSonic, "damage", 10, 0, 1_000_000),
                            integer(sonic, baseSonic, "cooldown_ticks", 40, 1, 1_000_000),
                            integer(sonic, baseSonic, "charge_time_ticks", 34, 0, 1_000_000),
                            number(sonic, baseSonic, "horizontal_range", 15, 0, 256),
                            number(sonic, baseSonic, "vertical_range", 20, 0, 256),
                            bool(sonic, baseSonic, "requires_line_of_sight", true),
                            number(sonic, baseSonic, "knockback_horizontal", 2.5, 0, 100),
                            number(sonic, baseSonic, "knockback_vertical", 0.5, 0, 100),
                            integer(sonic, baseSonic, "recovery_time_ticks", 26, 0, 12000)),
                    readDarkness(darkness, baseDarkness),
                    readTargeting(object(source, "targeting"), baseTargeting),
                    integer(melee, baseMelee, "charge_time_ticks", 0, 0, 12000),
                    integer(melee, baseMelee, "recovery_time_ticks", 4, 0, 12000)));
        }
        JsonObject voice = object(config, "voice_lines");
        JsonObject empty = new JsonObject();
        return new ClockworkBossSettings(thresholds, Map.copyOf(phases),
                new VoiceLines(bool(voice, empty, "enabled", true),
                        number(voice, empty, "chance", 0.25, 0, 1),
                        (float) number(voice, empty, "volume", 1, 0, 4),
                        (float) number(voice, empty, "pitch", 1, 0.5, 2),
                        (float) number(voice, empty, "range", ClockworkBossSoundDistance.LEGACY_FALLBACK,
                                ClockworkBossSoundDistance.AUTOMATIC, ClockworkBossSoundDistance.MAX_RANGE)),
                // Absent/null retains existing templates' health-selected aura.
                // A present section is independent; its missing fields inherit
                // phase 1 once here, never through JSON work in the watcher.
                config.has("dormant_darkness") && config.get("dormant_darkness").isJsonObject()
                        ? readDarkness(object(config, "dormant_darkness"),
                                phases.get(thresholds.phases().getFirst()).darkness())
                        : null,
                (float) number(object(config, "sounds"), empty, "mob_volume", 0.25, 0, 1));
    }

    /** Returns the compiled attack/aura values for one health-selected phase. */
    public Phase phase(ClockworkBossPhase phase) { return phases.get(phase); }

    /** Selects an already compiled aura; nullable dormant overrides preserve older templates. */
    public Darkness darkness(ClockworkBossPhase phase, boolean dormant) {
        return dormant && dormantDarkness != null ? dormantDarkness : phase(phase).darkness();
    }

    /** Both aura modes share inheritance and bounds; parsing happens only on load/reload. */
    private static Darkness readDarkness(JsonObject source, Darkness fallback) {
        JsonObject empty = new JsonObject();
        return new Darkness(bool(source, empty, "enabled", fallback.enabled()),
                number(source, empty, "max_radius", fallback.maxRadius(), 0, 256),
                integer(source, empty, "edge_interval_ticks", fallback.edgeInterval(), 1, 12_000),
                integer(source, empty, "center_interval_ticks", fallback.centerInterval(), 1, 12_000),
                integer(source, empty, "edge_duration_ticks", fallback.edgeDuration(), 1, 12_000),
                integer(source, empty, "center_duration_ticks", fallback.centerDuration(), 1, 12_000),
                integer(source, empty, "edge_amplifier", fallback.edgeAmplifier(), 0, 4),
                integer(source, empty, "center_amplifier", fallback.centerAmplifier(), 0, 4));
    }

    /** Compile inherited targeting/title controls once; completed attacks never traverse JSON. */
    private static Targeting readTargeting(JsonObject source, JsonObject base) {
        JsonObject announcement = object(source, "announcement");
        JsonObject baseAnnouncement = object(base, "announcement");
        int minimumAttacks = integer(source, base, "min_attacks", 1, 1, 1000);
        int maximumAttacks = Math.max(minimumAttacks, integer(source, base, "max_attacks", 4, 1, 1000));
        return new Targeting(number(source, base, "switch_chance", 0.5, 0, 1),
                mode(source, mode(base, ClockworkBossTargeting.Mode.RANDOM)),
                integer(source, base, "min_target_ticks", 60, 0, 12000), minimumAttacks, maximumAttacks,
                new Announcement(bool(announcement, baseAnnouncement, "enabled", true),
                        titleText(announcement, titleText(baseAnnouncement, "RUN, <player>!")),
                        integer(announcement, baseAnnouncement, "cooldown_ticks", 60, 20, 1200),
                        integer(announcement, baseAnnouncement, "display_ticks", 20, 1, 100)));
    }

    private static ClockworkBossTargeting.Mode mode(JsonObject source, ClockworkBossTargeting.Mode fallback) {
        try { return ClockworkBossTargeting.Mode.valueOf(source.get("mode").getAsString().toUpperCase(Locale.ROOT)); }
        catch (RuntimeException invalid) { return fallback; }
    }

    /** Literal text only, bounded for the shared native title HUD; no JSON formatting or commands. */
    private static String titleText(JsonObject source, String fallback) {
        try {
            String value = source.get("text").getAsString();
            return value.isBlank() || value.length() > 80 || value.chars().anyMatch(Character::isISOControl)
                    ? fallback : value;
        } catch (RuntimeException invalid) { return fallback; }
    }

    /** JSON traversal occurs only during compilation; missing objects use empty fallbacks. */
    private static JsonObject object(JsonObject root, String... path) {
        JsonObject current = root;
        for (String key : path) {
            if (!current.has(key) || !current.get(key).isJsonObject()) return new JsonObject();
            current = current.getAsJsonObject(key);
        }
        return current;
    }

    /** Null and missing overrides inherit; an invalid override falls back to its base value. */
    private static double rawNumber(JsonObject object, String key, double fallback) {
        try {
            double value = object.get(key).getAsDouble();
            return Double.isFinite(value) ? value : fallback;
        } catch (RuntimeException invalid) {
            return fallback;
        }
    }

    /** Finite base and phase values are clamped before they can influence damage or queries. */
    private static double number(JsonObject phase, JsonObject base, String key,
                                 double fallback, double min, double max) {
        double value = rawNumber(phase, key, rawNumber(base, key, fallback));
        return Math.max(min, Math.min(max, value));
    }

    /** Rejects fractional timers instead of silently shortening them by truncation. */
    private static int rawInteger(JsonObject object, String key, int fallback) {
        double value = rawNumber(object, key, fallback);
        return value == Math.rint(value) && value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE
                ? (int) value : fallback;
    }

    /** Resolves integer phase/base inheritance before clamping; fractional timers do not silently truncate. */
    private static int integer(JsonObject phase, JsonObject base, String key,
                               int fallback, int min, int max) {
        int value = rawInteger(phase, key, rawInteger(base, key, fallback));
        return Math.max(min, Math.min(max, value));
    }

    /** Safely reads a boolean or inherits the supplied fallback for missing/null/invalid values. */
    private static boolean rawBoolean(JsonObject object, String key, boolean fallback) {
        try {
            return object.get(key).getAsBoolean();
        } catch (RuntimeException invalid) {
            return fallback;
        }
    }

    /** Applies the per-field phase override before the base boolean, with a final safe default. */
    private static boolean bool(JsonObject phase, JsonObject base, String key, boolean fallback) {
        return rawBoolean(phase, key, rawBoolean(base, key, fallback));
    }

    /** Melee remains native Warden AI; these values only gate its hit/cooldown. */
    public record Phase(boolean meleeEnabled, int meleeCooldown, SonicBoom sonic, Darkness darkness, Targeting targeting,
                        int meleeChargeTicks, int meleeRecoveryTicks) {
        /** Preserve the original fixture/API vocabulary; absent timing means an immediate native hit. */
        public Phase(boolean meleeEnabled, int meleeCooldown, SonicBoom sonic, Darkness darkness, Targeting targeting) {
            this(meleeEnabled, meleeCooldown, sonic, darkness, targeting, 0, 4);
        }
    }

    /** Current phase selects this policy on completion, independently of the shot's captured damage/timing. */
    public record Targeting(double switchChance, ClockworkBossTargeting.Mode mode,
                            int minTargetTicks, int minAttacks, int maxAttacks, Announcement announcement) {}
    /** Title throttling is shared across bosses per recipient; suppressing a title never delays combat. */
    public record Announcement(boolean enabled, String text, int cooldownTicks, int displayTicks) {}

    /** Per-template voice policy compiled once; successful attacks read only these bounded values. */
    public record VoiceLines(boolean enabled, double chance, float volume, float pitch, float range) {}

    /** Cooldown lives in the vanilla brain; charging uses one world-time deadline. */
    public record SonicBoom(boolean enabled, double damage, int cooldown, int chargeTime,
                            double horizontalRange, double verticalRange, boolean lineOfSight,
                            double horizontalKnockback, double verticalKnockback, int recoveryTicks) {}

    /** Edge/center endpoints are linearly interpolated by a player's distance from the boss. */
    public record Darkness(boolean enabled, double maxRadius, int edgeInterval, int centerInterval,
                           int edgeDuration, int centerDuration, int edgeAmplifier, int centerAmplifier) {}
}
