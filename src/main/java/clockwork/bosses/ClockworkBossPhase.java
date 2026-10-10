package clockwork.bosses;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Stable config-key identity; phase order and cutoffs are compiled per template, not fixed in an enum. */
public record ClockworkBossPhase(String key) {
    public static final int MAX_PHASES = 16;
    // Existing names remain stable identities for older templates and combat fixtures.
    public static final ClockworkBossPhase ANNOYED = new ClockworkBossPhase("annoyed");
    public static final ClockworkBossPhase AGITATED = new ClockworkBossPhase("agitated");
    public static final ClockworkBossPhase ENRAGED = new ClockworkBossPhase("enraged");
    public static final ClockworkBossPhase FURY = new ClockworkBossPhase("fury");
    public ClockworkBossPhase {
        if (key == null || !key.matches("[a-z0-9][a-z0-9_-]{0,31}")) throw new IllegalArgumentException("Invalid phase name");
    }
    public String name() { return key.toUpperCase(Locale.ROOT); }
    public static ClockworkBossPhase[] values() { return new ClockworkBossPhase[]{ANNOYED, AGITATED, ENRAGED, FURY}; }

    private static ClockworkBossPhase identity(String key) {
        return switch (key) { case "annoyed" -> ANNOYED; case "agitated" -> AGITATED;
            case "enraged" -> ENRAGED; case "fury" -> FURY; default -> new ClockworkBossPhase(key); };
    }

    /** Allocation-free health selection over a maximum of sixteen precompiled stages. Equality leaves a phase. */
    public static ClockworkBossPhase fromHealth(double health, double maxHealth, Thresholds cutoffs) {
        double percent = maxHealth <= 0 ? 0 : health / maxHealth * 100;
        for (int index = 0; index < cutoffs.cutoffs.size(); index++) {
            if (percent > cutoffs.cutoffs.get(index)) return cutoffs.phases.get(index);
        }
        return cutoffs.phases.getLast();
    }

    /** JSON insertion order is fight order; the final phase has no leaving cutoff. */
    public record Thresholds(List<ClockworkBossPhase> phases, List<Double> cutoffs) {
        public Thresholds {
            phases = List.copyOf(phases); cutoffs = List.copyOf(cutoffs);
            if (phases.isEmpty() || phases.size() > MAX_PHASES || cutoffs.size() != phases.size() - 1)
                throw new IllegalArgumentException("Use 1–16 phases with one cutoff per non-final phase");
        }
        /** Compatibility constructor retains the original four-stage fixture vocabulary. */
        public Thresholds(double annoyed, double agitated, double enraged) {
            this(List.of(ANNOYED, AGITATED, ENRAGED, FURY), List.of(annoyed, agitated, enraged));
        }
        public double annoyed() { return cutoff(ANNOYED); }
        public double agitated() { return cutoff(AGITATED); }
        public double enraged() { return cutoff(ENRAGED); }
        private double cutoff(ClockworkBossPhase phase) {
            int index = phases.indexOf(phase);
            return index >= 0 && index < cutoffs.size() ? cutoffs.get(index) : 0;
        }

        /** Compile once on spawn/load/reload; malformed cutoffs safely inherit an evenly spaced fallback. */
        public static Thresholds read(JsonObject config) {
            if (!config.has("phases") || !config.get("phases").isJsonObject()) return new Thresholds(75, 50, 25);
            JsonObject source = config.getAsJsonObject("phases");
            if (source.size() < 1 || source.size() > MAX_PHASES) throw new IllegalArgumentException("A template needs 1–16 phases");
            List<ClockworkBossPhase> phases = new ArrayList<>();
            for (var entry : source.entrySet()) {
                if (!entry.getValue().isJsonObject()) throw new IllegalArgumentException("Phase settings must be objects");
                phases.add(identity(entry.getKey()));
            }
            List<Double> cutoffs = new ArrayList<>();
            double previous = 100;
            for (int index = 0; index < phases.size() - 1; index++) {
                double fallback = 100.0 * (phases.size() - index - 1) / phases.size();
                double cutoff = fallback;
                try { cutoff = source.getAsJsonObject(phases.get(index).key).get("minimum_health_percent").getAsDouble(); }
                catch (RuntimeException invalid) { }
                if (!Double.isFinite(cutoff)) cutoff = fallback;
                cutoff = Math.clamp(cutoff, 0, previous);
                cutoffs.add(cutoff); previous = cutoff;
            }
            return new Thresholds(phases, cutoffs);
        }
    }
}
