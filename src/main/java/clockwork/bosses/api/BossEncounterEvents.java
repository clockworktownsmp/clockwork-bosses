package clockwork.bosses.api;

import java.util.UUID;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Notifications after committed server changes. Listeners cannot approve damage
 * or rewrite an encounter through return values. A failing addon cannot prevent
 * later listeners or core cleanup. Treat callbacks as synchronous and keep them short.
 * Register once at addon initialization: Fabric retains listeners for the process
 * lifetime. Capture immutable definitions, never a live encounter/world/player. */
public final class BossEncounterEvents {
    private static final Logger LOGGER = LoggerFactory.getLogger("clockwork_bosses/api");
    // One global throttle avoids an unbounded map of failed listeners and log
    // floods if an addon throws on every hit. Fatal VM errors still propagate.
    private static final java.util.concurrent.atomic.AtomicLong NEXT_ERROR = new java.util.concurrent.atomic.AtomicLong();
    private BossEncounterEvents() {}
    @FunctionalInterface public interface Encounter { void onEvent(BossEncounterSnapshot encounter); }
    @FunctionalInterface public interface Membership { void onEvent(BossEncounterSnapshot encounter, UUID player); }
    @FunctionalInterface public interface Phase { void onEvent(BossEncounterSnapshot encounter, String previous, String current); }
    @FunctionalInterface public interface Hit { void onEvent(BossEncounterSnapshot encounter, Identifier action, UUID target, float damage); }
    @FunctionalInterface public interface End { void onEvent(BossEncounterSnapshot encounter, BossAction.StopReason reason); }
    @FunctionalInterface public interface Terminal { void onEvent(BossTerminalSnapshot snapshot); }
    @FunctionalInterface public interface Vitals { void onEvent(BossVitalsChange change); }
    @FunctionalInterface public interface Cleanup { void onEvent(BossCleanupSnapshot snapshot); }
    private static volatile boolean terminalListeners, vitalsListeners, cleanupListeners;
    public static boolean hasTerminalListeners() { return terminalListeners; }
    public static boolean hasVitalsListeners() { return vitalsListeners; }
    public static boolean hasCleanupListeners() { return cleanupListeners; }
    /** Once-only pre-restoration capture. An observer cannot veto encounter end. */
    public static final Event<Terminal> TERMINAL = EventFactory.createArrayBacked(Terminal.class, listeners -> {
        terminalListeners = listeners.length != 0;
        return snapshot -> { for (var listener : listeners) safely(() -> listener.onEvent(snapshot)); };
    });
    /** Actual native writes; see BossVitalsChange for nesting and attribution. */
    public static final Event<Vitals> VITALS_CHANGED = EventFactory.createArrayBacked(Vitals.class, listeners -> {
        vitalsListeners = listeners.length != 0;
        return change -> { for (var listener : listeners) safely(() -> listener.onEvent(change)); };
    });
    /** Diagnostic state after the legacy end path; no state is changed by capture. */
    public static final Event<Cleanup> CLEANUP_FINISHED = EventFactory.createArrayBacked(Cleanup.class, listeners -> {
        cleanupListeners = listeners.length != 0;
        return snapshot -> { for (var listener : listeners) safely(() -> listener.onEvent(snapshot)); };
    });
    public static final Event<Encounter> STARTED = EventFactory.createArrayBacked(Encounter.class,
            listeners -> encounter -> { for (var listener : listeners) safely(() -> listener.onEvent(encounter)); });
    public static final Event<Membership> PLAYER_JOINED = EventFactory.createArrayBacked(Membership.class,
            listeners -> (encounter, player) -> { for (var listener : listeners) safely(() -> listener.onEvent(encounter, player)); });
    public static final Event<Membership> PLAYER_LEFT = EventFactory.createArrayBacked(Membership.class,
            listeners -> (encounter, player) -> { for (var listener : listeners) safely(() -> listener.onEvent(encounter, player)); });
    public static final Event<Phase> PHASE_CHANGED = EventFactory.createArrayBacked(Phase.class,
            listeners -> (encounter, previous, current) -> { for (var listener : listeners) safely(() -> listener.onEvent(encounter, previous, current)); });
    public static final Event<Hit> ATTACK_LANDED = EventFactory.createArrayBacked(Hit.class,
            listeners -> (encounter, action, target, damage) -> { for (var listener : listeners) safely(() -> listener.onEvent(encounter, action, target, damage)); });
    /** DIED denotes committed death; UNLOADED is suspension, not a death/reward event. */
    public static final Event<End> ENDED = EventFactory.createArrayBacked(End.class,
            listeners -> (encounter, reason) -> { for (var listener : listeners) safely(() -> listener.onEvent(encounter, reason)); });
    private static void safely(Runnable callback) {
        try { callback.run(); } catch (RuntimeException | LinkageError | AssertionError failure) {
            long now = System.nanoTime(), next = NEXT_ERROR.get();
            if ((next == 0 || now - next >= 0) && NEXT_ERROR.compareAndSet(next, now + 60_000_000_000L))
                LOGGER.error("Encounter event listener failed; additional failures are muted for one minute", failure);
        }
    }
}
