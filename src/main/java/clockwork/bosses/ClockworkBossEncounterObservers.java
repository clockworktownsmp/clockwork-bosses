package clockwork.bosses;

import clockwork.bosses.api.*;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.monster.warden.Warden;
import org.slf4j.LoggerFactory;

/** Rare encounter-boundary capture. This class owns no persistent encounter,
 * player or world state. EndCapture lives only in endEncounter's finally scope;
 * only immutable value records cross the public observer boundary. */
final class ClockworkBossEncounterObservers {
    private static final java.util.concurrent.atomic.AtomicLong NEXT_ERROR = new java.util.concurrent.atomic.AtomicLong();
    private ClockworkBossEncounterObservers() {}
    static void failed(Throwable failure) {
        long now = System.nanoTime(), next = NEXT_ERROR.get();
        if ((next == 0 || now - next >= 0) && NEXT_ERROR.compareAndSet(next, now + 60_000_000_000L))
            LoggerFactory.getLogger("clockwork_bosses/observers").error("Encounter observation failed; further failures muted for one minute", failure);
    }
    static final class EndCapture {
        final BossEncounterSnapshot encounter; final BossAction.StopReason reason;
        final Map<UUID, ServerPlayer> players;
        final Map<UUID, BossSupportSnapshot> before = new HashMap<>(), after = new HashMap<>();
        final Set<UUID> unavailable = new HashSet<>();
        final boolean cleanup;
        EndCapture(BossEncounterSnapshot encounter, BossAction.StopReason reason, Map<UUID, ServerPlayer> players, boolean cleanup) {
            this.encounter = encounter; this.reason = reason; this.players = players; this.cleanup = cleanup;
        }
    }
    static EndCapture begin(Warden boss, ClockworkBossState state, BossAction.StopReason reason) {
        boolean terminal = BossEncounterEvents.hasTerminalListeners(), cleanup = BossEncounterEvents.hasCleanupListeners();
        if (!terminal && !cleanup) return null;
        try {
            var players = new HashMap<UUID, ServerPlayer>();
            for (var player : state.bossBar().getPlayers()) players.put(player.getUUID(), player);
            collect(state.uuid(), players);
            if (boss == null) {
                var runtime = state.actionRuntime();
                if (runtime != null && !runtime.closed()) boss = runtime.boss();
            }
            // Resolve only currently loaded entities. No world/chunk scan and no
            // guessed last health when cleanup has no remaining live boss.
            if (boss == null && !players.isEmpty()) {
                var level = players.values().iterator().next().level().getServer().getLevel(state.dimension());
                if (level != null && level.getEntity(state.uuid()) instanceof Warden loaded) boss = loaded;
            }
            var encounter = ClockworkBossManager.encounterSnapshot(boss, state);
            var capture = new EndCapture(encounter, reason, players, cleanup);
            if (terminal) {
                var vitals = new HashMap<UUID, BossVitals>();
                var unavailable = new HashSet<>(encounter.participants()); unavailable.removeAll(players.keySet());
                for (var entry : players.entrySet()) {
                    try {
                        if (!available(entry.getValue())) unavailable.add(entry.getKey());
                        else vitals.put(entry.getKey(), ClockworkBossVitals.snapshot(entry.getValue()));
                    } catch (RuntimeException | LinkageError | AssertionError failure) { unavailable.add(entry.getKey()); failed(failure); }
                }
                Optional<BossVitals> bossVitals = Optional.empty();
                try { if (boss != null) bossVitals = Optional.of(ClockworkBossVitals.snapshot(boss)); }
                catch (RuntimeException | LinkageError | AssertionError failure) { failed(failure); }
                var snapshot = new BossTerminalSnapshot(encounter, reason, bossVitals, vitals, unavailable);
                ClockworkBossVitals.deliver(() -> BossEncounterEvents.TERMINAL.invoker().onEvent(snapshot));
            }
            return capture;
        } catch (RuntimeException | LinkageError | AssertionError failure) { failed(failure); return null; }
    }
    private static boolean available(ServerPlayer player) { return !player.hasDisconnected() && !player.isRemoved(); }
    private static void collect(UUID boss, Map<UUID, ServerPlayer> players) {
        ClockworkBossAreaEffects.collectPlayers(boss, players);
        ClockworkBossPlayerHealth.collectPlayers(boss, players);
        ClockworkBossFightPotion.collectPlayers(boss, players);
    }
    static void beforeRelease(EndCapture capture) {
        if (capture == null || !capture.cleanup) return;
        try { collect(capture.encounter.bossId(), capture.players); captureSupport(capture, capture.before); }
        catch (RuntimeException | LinkageError | AssertionError failure) { capture.unavailable.addAll(capture.players.keySet()); failed(failure); }
    }
    static void afterRelease(EndCapture capture) {
        if (capture == null || !capture.cleanup) return;
        captureSupport(capture, capture.after);
    }
    private static void captureSupport(EndCapture capture, Map<UUID, BossSupportSnapshot> destination) {
        for (var entry : capture.players.entrySet()) {
            try {
                var player = entry.getValue();
                if (!available(player)) { capture.unavailable.add(entry.getKey()); continue; }
                destination.put(entry.getKey(), new BossSupportSnapshot(ClockworkBossVitals.snapshot(player),
                        ClockworkBossPlayerHealth.snapshot(player, capture.encounter.bossId()),
                        ClockworkBossAreaEffects.snapshot(player), ClockworkBossFightPotion.snapshot(player)));
            } catch (RuntimeException | LinkageError | AssertionError failure) { capture.unavailable.add(entry.getKey()); failed(failure); }
        }
    }
    static void finish(EndCapture capture, ClockworkBossState state, boolean completedNormally) {
        if (capture == null) return;
        try {
            if (!capture.cleanup) return;
            var players = new HashMap<UUID, BossCleanupSnapshot.Player>();
            var ids = new HashSet<>(capture.encounter.participants()); ids.addAll(capture.players.keySet());
            for (var id : ids) {
                var before = Optional.ofNullable(capture.before.get(id)); var after = Optional.ofNullable(capture.after.get(id));
                if (before.isEmpty() || after.isEmpty()) capture.unavailable.add(id);
                players.put(id, new BossCleanupSnapshot.Player(before, after));
            }
            var runtime = state.actionRuntime();
            var snapshot = new BossCleanupSnapshot(capture.encounter, capture.reason, state.encounterEnded(),
                    runtime == null || runtime.closed(), state.bossBarPlayerIds().isEmpty() && state.bossBar().getPlayers().isEmpty(),
                    completedNormally, players, capture.unavailable);
            ClockworkBossVitals.deliver(() -> BossEncounterEvents.CLEANUP_FINISHED.invoker().onEvent(snapshot));
        } catch (RuntimeException | LinkageError | AssertionError failure) { failed(failure); }
        finally { capture.players.clear(); }
    }
}
