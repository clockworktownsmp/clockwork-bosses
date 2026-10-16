package clockwork.bosses;

import clockwork.bosses.api.BossEncounterEvents;
import clockwork.bosses.api.BossVitals;
import clockwork.bosses.api.BossVitalsChange;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.warden.Warden;

/** Native observation only. No tick listener, queue, history or extra mutation.
 * Temporary server-thread frames are removed in finally paths. Snapshots retain
 * values only. Ordinary entities/clients/zero listeners acquire no state. */
public final class ClockworkBossVitals {
    private static final int MAX_DEPTH = 64;
    private static final ThreadLocal<State> LOCAL = new ThreadLocal<>();
    private static final Scope NONE = new Scope(null, 0, null, null);
    private ClockworkBossVitals() {}
    private static final class State {
        final ArrayDeque<Frame> frames = new ArrayDeque<>();
        Context context; ActionCall action;
        int contextDepth, actionDepth, unknownDepth, delivering;
    }
    private record Context(UUID entity, UUID relatedBoss, BossVitalsChange.Cause cause, Optional<BossVitalsChange.Damage> damage,
                           Optional<BossVitalsChange.Action> action, OptionalDouble requested) {}
    private static final class ActionCall {
        final UUID boss, target; final Identifier action; final DamageSource expected;
        boolean claimed;
        ActionCall(Warden boss, LivingEntity target, Identifier action, DamageSource expected) {
            this.boss = boss.getUUID(); this.target = target.getUUID(); this.action = action; this.expected = expected;
        }
    }
    public record Moments(List<BossVitalsChange.Moment> values, boolean truncated) {
        public Moments { values = List.copyOf(values); }
    }
    private static final Moments EMPTY = new Moments(List.of(), false);
    /** Opaque temporary frame, never exposed through the addon event API. */
    public static final class Frame {
        final LivingEntity entity;
        final BossVitals before; final BossVitalsChange.Field field; final Context context; final Moments moments;
        final boolean emit; double nestedHealth, nestedAbsorption; boolean complete;
        Frame(LivingEntity entity, BossVitalsChange.Field field, Context context, Moments moments, boolean emit, boolean complete) {
            this.entity = entity; this.before = snapshot(entity); this.field = field; this.context = context;
            this.moments = moments; this.emit = emit; this.complete = complete;
        }
    }
    /** All scopes are try-with-resources. Closing clears the previous context;
     * none outlives the native operation, including vetoes and thrown callbacks. */
    public static final class Scope implements AutoCloseable {
        private final State state; private final int kind;
        private final Context previousContext; private final ActionCall previousAction;
        private boolean closed;
        private Scope(State state, int kind, Context context, ActionCall action) {
            this.state = state; this.kind = kind; previousContext = context; previousAction = action;
        }
        @Override public void close() {
            if (state == null || closed) return;
            closed = true;
            if (kind == 1) { state.context = previousContext; state.contextDepth--; }
            else if (kind == 2) { state.action = previousAction; state.actionDepth--; }
            else state.unknownDepth--;
            release(state);
        }
    }
    static BossVitals snapshot(LivingEntity entity) {
        return new BossVitals(entity.getUUID(), entity.getHealth(), entity.getMaxHealth(), entity.getAbsorptionAmount(), entity.isAlive(), entity.isRemoved());
    }
    private static boolean enabled(LivingEntity entity) {
        return BossEncounterEvents.hasVitalsListeners() && entity.level() instanceof ServerLevel level && level.getServer().isSameThread();
    }
    private static State state() {
        var state = LOCAL.get();
        if (state == null) { state = new State(); LOCAL.set(state); }
        return state;
    }
    private static void release(State state) {
        if (state.contextDepth == 0 && state.actionDepth == 0 && state.unknownDepth == 0 && state.delivering == 0 && state.frames.isEmpty()) LOCAL.remove();
    }
    private static Scope push(State state, Context context) {
        if (state.contextDepth >= MAX_DEPTH) { state.unknownDepth++; return new Scope(state, 3, null, null); }
        var scope = new Scope(state, 1, state.context, null);
        state.context = context; state.contextDepth++;
        return scope;
    }
    private static boolean owned(LivingEntity entity, UUID source) {
        if (ClockworkBossManager.observesVitals(entity, source)) return true;
        return related(entity, source) != null;
    }
    /** Encounter relationship is independent of damage/heal attribution. A heal
     * inside a damage context can precede every setter frame for this entity. */
    private static UUID related(LivingEntity entity, UUID source) {
        if (ClockworkBossManager.observesEncounter(source)) return source;
        var state = LOCAL.get();
        if (state == null) return null;
        var context = state.context;
        if (state.unknownDepth == 0 && context != null && context.entity.equals(entity.getUUID())
                && ClockworkBossManager.observesEncounter(context.relatedBoss)) return context.relatedBoss;
        for (var parent : state.frames) if (parent.entity == entity) {
            for (var moment : parent.moments.values()) if (ClockworkBossManager.observesEncounter(moment.bossId())) return moment.bossId();
            break;
        }
        return null;
    }
    /** Source context does not assume that an accepted hit actually changed HP. */
    public static Scope damage(LivingEntity entity, DamageSource source) {
        try {
            if (!enabled(entity)) return NONE;
            var attacker = source.getEntity();
            UUID sourceId = attacker == null ? null : attacker.getUUID();
            if (!owned(entity, sourceId)) return NONE;
            var state = state();
            Optional<BossVitalsChange.Action> action = Optional.empty();
            var call = state.action;
            if (state.unknownDepth == 0 && call != null && !call.claimed && call.target.equals(entity.getUUID())
                    && (call.expected == null ? call.boss.equals(sourceId) : call.expected == source)) {
                call.claimed = true;
                action = Optional.of(new BossVitalsChange.Action(call.boss, call.action));
            }
            var direct = source.getDirectEntity();
            var damage = new BossVitalsChange.Damage(source.getMsgId(), Optional.ofNullable(sourceId),
                    direct == null ? Optional.empty() : Optional.of(direct.getUUID()));
            return push(state, new Context(entity.getUUID(), related(entity, sourceId), BossVitalsChange.Cause.DAMAGE, Optional.of(damage), action, OptionalDouble.empty()));
        } catch (RuntimeException | LinkageError | AssertionError failure) { return failedScope(failure); }
    }
    public static Scope healing(LivingEntity entity, float requested) {
        try {
            if (!enabled(entity) || !owned(entity, null)) return NONE;
            var state = state(); var previous = state.context;
            boolean potion = state.unknownDepth == 0 && previous != null && previous.entity.equals(entity.getUUID())
                    && previous.cause == BossVitalsChange.Cause.FIGHT_POTION && previous.requested.isEmpty();
            return push(state, new Context(entity.getUUID(), related(entity, null), potion ? BossVitalsChange.Cause.FIGHT_POTION : BossVitalsChange.Cause.HEAL,
                    Optional.empty(), Optional.empty(), OptionalDouble.of(requested)));
        } catch (RuntimeException | LinkageError | AssertionError failure) { return failedScope(failure); }
    }
    static Scope potion(LivingEntity entity) {
        try {
            if (!enabled(entity) || !ClockworkBossManager.observesVitals(entity, null)) return NONE;
            return push(state(), new Context(entity.getUUID(), related(entity, null), BossVitalsChange.Cause.FIGHT_POTION,
                    Optional.empty(), Optional.empty(), OptionalDouble.empty()));
        } catch (RuntimeException | LinkageError | AssertionError failure) { return failedScope(failure); }
    }
    public static Scope action(Warden boss, LivingEntity target, Identifier action, DamageSource expected) {
        try {
            if (!enabled(boss) || !ClockworkBossManager.observesVitals(boss, null) || target == null) return NONE;
            var state = state();
            if (state.actionDepth >= MAX_DEPTH) { state.unknownDepth++; return new Scope(state, 3, null, null); }
            var scope = new Scope(state, 2, null, state.action);
            state.action = new ActionCall(boss, target, action, expected); state.actionDepth++;
            return scope;
        } catch (RuntimeException | LinkageError | AssertionError failure) { return failedScope(failure); }
    }
    private static Scope failedScope(Throwable failure) {
        var state = LOCAL.get();
        if (state != null) {
            for (var frame : state.frames) frame.complete = false;
            release(state);
        }
        ClockworkBossEncounterObservers.failed(failure);
        return NONE;
    }
    public static Frame begin(LivingEntity entity, BossVitalsChange.Field field) {
        State state = null;
        try {
            if (!enabled(entity)) return null;
            state = LOCAL.get();
            Context context = state == null || state.unknownDepth != 0 ? null : state.context;
            if (context != null && !context.entity.equals(entity.getUUID())) context = null;
            // A direct nested setter without its own hurt/heal scope has unknown
            // attribution. Do not inherit the outer hit merely because callbacks
            // are still inside actuallyHurt; explicit nested hurt/heal scopes differ.
            Frame nearest = null;
            if (state != null) for (var parentFrame : state.frames) if (parentFrame.entity == entity) { nearest = parentFrame; break; }
            if (nearest != null && nearest.context == context) context = null;
            UUID source = context == null ? null : context.relatedBoss;
            boolean relevant = ClockworkBossManager.observesVitals(entity, source);
            // Preserve a legitimate nested event's encounter relationship while
            // leaving its source/action unknown. Re-sample the live phase now.
            if (!relevant && nearest != null) for (var moment : nearest.moments.values()) {
                if (ClockworkBossManager.observesVitals(entity, moment.bossId())) { source = moment.bossId(); relevant = true; break; }
            }
            boolean parent = nearest != null;
            if (!relevant && !parent || state != null && state.delivering != 0 && !parent) return null;
            state = state == null ? state() : state;
            if (state.frames.size() >= MAX_DEPTH) {
                for (var frame : state.frames) if (frame.entity == entity) frame.complete = false;
                return null;
            }
            boolean emit = relevant && state.delivering == 0;
            var frame = new Frame(entity, field, context, emit ? ClockworkBossManager.vitalsMoments(entity, source) : EMPTY,
                    emit, state.unknownDepth == 0);
            state.frames.push(frame);
            return frame;
        } catch (RuntimeException | LinkageError | AssertionError failure) {
            if (state == null) state = LOCAL.get();
            if (state != null) {
                for (var frame : state.frames) if (frame.entity == entity) frame.complete = false;
                release(state);
            }
            ClockworkBossEncounterObservers.failed(failure); return null;
        }
    }
    public static void end(Frame frame, boolean completedNormally) {
        if (frame == null) return;
        var state = LOCAL.get();
        try {
            if (state == null || state.frames.peek() != frame) throw new IllegalStateException("Unbalanced native vitals observation");
            state.frames.pop();
            var after = snapshot(frame.entity);
            double health = (double) after.health() - frame.before.health();
            double absorption = (double) after.absorption() - frame.before.absorption();
            for (var parent : state.frames) if (parent.entity == frame.entity) {
                parent.nestedHealth += health; parent.nestedAbsorption += absorption; parent.complete &= frame.complete; break;
            }
            if (frame.emit) {
                var context = frame.context;
                var change = new BossVitalsChange(frame.before, after, frame.field,
                        context == null ? BossVitalsChange.Cause.UNKNOWN : context.cause,
                        context == null ? Optional.empty() : context.damage,
                        context == null ? Optional.empty() : context.action,
                        context == null || frame.field != BossVitalsChange.Field.HEALTH ? OptionalDouble.empty() : context.requested,
                        frame.moments.values(), frame.moments.truncated(), health - frame.nestedHealth,
                        absorption - frame.nestedAbsorption, frame.complete, completedNormally);
                deliver(() -> BossEncounterEvents.VITALS_CHANGED.invoker().onEvent(change));
            }
        } catch (RuntimeException | LinkageError | AssertionError failure) {
            if (state != null) {
                state.frames.remove(frame);
                for (var parent : state.frames) if (parent.entity == frame.entity) parent.complete = false;
            }
            ClockworkBossEncounterObservers.failed(failure);
        } finally { if (state != null) release(state); }
    }
    /** Suppress notification feedback, not native mutation. A callback's nested
     * writes still subtract from any suspended parent measurement. */
    static void deliver(Runnable callback) {
        var state = state(); state.delivering++;
        try { callback.run(); } finally { state.delivering--; release(state); }
    }
    static boolean hasTemporaryState() { return LOCAL.get() != null; }
}
