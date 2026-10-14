package clockwork.bosses;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Locale;
import net.minecraft.util.RandomSource;

/** Server-owned expression controller for one loaded boss. Combat events select
 * poses; the existing boss watcher expires them. No entity references, tasks,
 * listeners or world scans are owned here. Positions always belong to the base
 * face; rules override only its shape and the two rotations. */
public final class ClockworkBossFaceEvents {
    public enum Attack { MELEE, SONIC_BOOM, ROAR }

    /** An immutable rule compiled at reload. Inherit selects the base shape;
     * null angles retain base rotations, including when choosing randomly. */
    public record Rule(String expression, Integer leftRotation, Integer rightRotation) {
        ClockworkBossBrows pose(Settings settings, RandomSource random) {
            var base = settings.base();
            var shape = expression.equals("random") ? settings.draw(random)
                    : expression.equals("inherit") ? base.expression() : ClockworkBossBrows.Expression.read(expression);
            return new ClockworkBossBrows(base.leftX(), base.leftY(), base.rightX(), base.rightY(), shape,
                    leftRotation == null ? base.leftRotation() : leftRotation,
                    rightRotation == null ? base.rightRotation() : rightRotation);
        }
        static Rule read(JsonObject object) {
            String name = text(object, "expression", "inherit").toLowerCase(Locale.ROOT);
            if (!java.util.List.of("inherit", "angry", "neutral", "curious", "sad", "random").contains(name)) name = "inherit";
            return new Rule(name, rotation(object, "left_rotation"), rotation(object, "right_rotation"));
        }
    }

    /** Small fixed catalog, parsed once. The random pool cannot grow with input
     * size; it contains at most the four supported shapes. Empty means base. */
    public record Settings(ClockworkBossBrows base, boolean enabled, int hitDuration, int hitCooldown,
                           int targetDuration, int targetCooldown, boolean randomBetweenAttacks,
                           java.util.List<ClockworkBossBrows.Expression> pool,
                           Rule melee, Rule sonic, Rule roar, Rule hurt, Rule target) {
        public static Settings read(JsonObject config) {
            var raw = ClockworkBossEditorSchema.get(config, "skin.glowing_eyebrows.events");
            var events = raw != null && raw.isJsonObject() ? raw.getAsJsonObject() : new JsonObject();
            var poolObject = object(events, "random_pool");
            var pool = new ArrayList<ClockworkBossBrows.Expression>(4);
            for (var shape : ClockworkBossBrows.Expression.values())
                if (bool(poolObject, shape.name().toLowerCase(Locale.ROOT), true)) pool.add(shape);
            return new Settings(ClockworkBossBrows.read(config), bool(events, "enabled", false),
                    integer(events, "hit_duration_ticks", 20, 1, 12000), integer(events, "hit_cooldown_ticks", 20, 0, 12000),
                    integer(events, "target_duration_ticks", 20, 1, 12000), integer(events, "target_cooldown_ticks", 20, 0, 12000),
                    bool(events, "random_between_attacks", false), java.util.List.copyOf(pool),
                    Rule.read(object(events, "melee")), Rule.read(object(events, "sonic_boom")), Rule.read(object(events, "roar")),
                    Rule.read(object(events, "hurt")), Rule.read(object(events, "target_change")));
        }
        private ClockworkBossBrows.Expression draw(RandomSource random) {
            return pool.isEmpty() ? base.expression() : pool.get(random.nextInt(pool.size()));
        }
        private Rule attack(Attack type) {
            return switch (type) { case MELEE -> melee; case SONIC_BOOM -> sonic; case ROAR -> roar; };
        }
    }

    // Server-thread-only sequence makes old stop callbacks harmless even when a
    // boss unloads and receives a fresh controller. One primitive, no registry.
    private static long nextToken;
    private Settings settings;
    private int rest, attack, hurt, target;
    private long attackToken, attackUntil, hurtUntil, targetUntil, nextHit, nextTarget;

    /** Reload replaces compiled rules and drops old reactions/recovery poses.
     * Phase changes clear transient poses through the same lifecycle helper. */
    public void configure(Settings settings) { this.settings = settings; clear(); }
    public boolean enabled() { return settings != null && settings.enabled(); }
    public void clear() {
        rest = settings == null ? ClockworkBossBrows.DEFAULT.packed() : settings.base().packed();
        attackToken = 0; attackUntil = hurtUntil = targetUntil = Long.MIN_VALUE;
        nextHit = nextTarget = Long.MIN_VALUE;
    }

    /** Start is driven by native attack/roar start, never a guessed animation
     * callback on the client. A random rule is rolled once for this attack. */
    public long begin(Attack type, long now, int duration, RandomSource random) {
        if (!enabled()) return 0;
        attack = settings.attack(type).pose(settings, random).packed();
        attackUntil = deadline(now, Math.max(10, duration));
        attackToken = ++nextToken;
        return attackToken;
    }
    /** Keep the attack pose through recovery. A late completion cannot extend
     * or clear a newer attack; cancellation uses the same captured token. */
    public void finish(long token, long now, int recovery) {
        if (token != 0 && token == attackToken) attackUntil = deadline(now, Math.max(10, recovery));
    }
    public void cancel(long token) {
        if (token != 0 && token == attackToken) { attackToken = 0; attackUntil = Long.MIN_VALUE; }
    }
    /** Once per completed swing/beam, regardless of hit success. Rejected or
     * cancelled attempts never call this and do not consume a random draw. */
    public void afterAttack(RandomSource random) {
        if (!enabled() || !settings.randomBetweenAttacks()) return;
        var base = settings.base();
        rest = new ClockworkBossBrows(base.leftX(), base.leftY(), base.rightX(), base.rightY(), settings.draw(random),
                base.leftRotation(), base.rightRotation()).packed();
    }
    /** Accepted nonfatal player damage temporarily masks the attack. Cooldown
     * ignores extra hits rather than extending the pose indefinitely. */
    public void hit(long now, RandomSource random) {
        if (!enabled() || now < nextHit) return;
        hurt = settings.hurt().pose(settings, random).packed();
        hurtUntil = deadline(now, settings.hitDuration()); nextHit = deadline(now, settings.hitCooldown());
    }
    /** A real new target gets a brief lower-priority pose. Clearing a target
     * removes that overlay immediately, without rolling or resetting cooldown. */
    public void targetChanged(boolean hasTarget, long now, RandomSource random) {
        if (!hasTarget) { targetUntil = Long.MIN_VALUE; return; }
        if (!enabled() || now < nextTarget) return;
        target = settings.target().pose(settings, random).packed();
        targetUntil = deadline(now, settings.targetDuration()); nextTarget = deadline(now, settings.targetCooldown());
    }
    /** Priority is hurt, attack/roar, target, rest. Expiry is evaluated only by
     * events and the existing ten-tick watcher (up to nine extra game ticks).
     * Finishing a reaction restores a still-active attack, never a stale one. */
    public int packed(long now) {
        if (now >= attackUntil) attackToken = 0;
        if (enabled() && now < hurtUntil) return hurt;
        if (enabled() && attackToken != 0) return attack;
        if (enabled() && now < targetUntil) return target;
        return rest;
    }
    private static long deadline(long now, int ticks) {
        return now > Long.MAX_VALUE - ticks ? Long.MAX_VALUE : now + ticks;
    }
    private static JsonObject object(JsonObject parent, String key) {
        var value = parent.get(key); return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
    }
    private static String text(JsonObject object, String key, String fallback) {
        var value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString() ? value.getAsString() : fallback;
    }
    private static boolean bool(JsonObject object, String key, boolean fallback) {
        var value = object.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() ? value.getAsBoolean() : fallback;
    }
    private static Integer rotation(JsonObject object, String key) {
        try {
            var value = object.get(key);
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return null;
            double number = value.getAsDouble();
            return Double.isFinite(number) && number == Math.rint(number)
                    ? (int) Math.clamp(number, ClockworkBossBrows.MIN_ROTATION, ClockworkBossBrows.MAX_ROTATION) : null;
        } catch (RuntimeException invalid) { return null; }
    }
    private static int integer(JsonObject object, String key, int fallback, int min, int max) {
        try {
            var value = object.get(key);
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) return fallback;
            double number = value.getAsDouble();
            return Double.isFinite(number) && number == Math.rint(number) ? (int) Math.clamp(number, min, max) : fallback;
        } catch (RuntimeException invalid) { return fallback; }
    }
}
