package clockwork.bosses;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable behavior sequences compiled on load/reload. A child inherits its
 * health phase, which already inherits the template; children never select by
 * health or recursively contain another sequence. No entity/timer is retained. */
public final class ClockworkBossSubphases {
    public static final int MAX_SUBPHASES = 16;
    public enum Order { SEQUENTIAL, RANDOM }
    public static final Order DEFAULT_ORDER = Order.SEQUENTIAL;
    public static final boolean DEFAULT_ROAR_BETWEEN = true;
    public record Attributes(double speed, double resistance, double followRange, double damage, double knockback) {}
    public enum Type { ATTACK, RECOVERY, ACTION }
    public record Step(String key, Type type, int durationTicks, int attacks, ClockworkBossSettings.Phase behavior, Attributes attributes,
                       clockwork.bosses.api.BossActionType actionType, clockwork.bosses.api.BossActionSettings actionSettings) {}
    /** Two independent ordered catalogs. Random attack order is shuffled by
     * each boss; random roar selection is an independent pick per transition. */
    public record Sequence(Order order, List<Step> steps, boolean roarBetween, List<clockwork.bosses.api.BossActionSettings> roars) {}
    private ClockworkBossSubphases() {}

    /** Build bounded snapshots. Null child fields inherit their parent, never
     * the previous child. Main-phase visuals, health, scale and aura stay owned
     * by the parent; only existing combat/targeting fields can be overridden. */
    public static Map<ClockworkBossPhase, Sequence> read(JsonObject config, ClockworkBossSettings settings) {
        Map<ClockworkBossPhase, Sequence> sequences = new LinkedHashMap<>();
        for (var phase : settings.thresholds().phases()) {
            JsonObject parent = object(config, "phases", phase.key());
            JsonObject children = catalog(parent, "subphases");
            JsonObject roarEntries = catalog(parent, "roars");
            Order order;
            try { order = !parent.has("subphase_order") || parent.get("subphase_order").isJsonNull() ? DEFAULT_ORDER
                    : Order.valueOf(parent.get("subphase_order").getAsString().toUpperCase(java.util.Locale.ROOT)); }
            catch (RuntimeException invalid) { throw new IllegalArgumentException("Subphase order must be sequential or random"); }
            JsonElement interludes = parent.get("roar_between_subphases");
            if (interludes != null && !interludes.isJsonNull()
                    && (!interludes.isJsonPrimitive() || !interludes.getAsJsonPrimitive().isBoolean()))
                throw new IllegalArgumentException("roar_between_subphases must be true or false");
            boolean roarBetween = interludes == null || interludes.isJsonNull() ? DEFAULT_ROAR_BETWEEN : interludes.getAsBoolean();
            List<Step> steps = new ArrayList<>();
            for (var entry : children.entrySet()) {
                new ClockworkBossPhase(entry.getKey()); // Same bounded safe key vocabulary.
                if (!entry.getValue().isJsonObject()) throw new IllegalArgumentException("Subphase settings must be objects");
                JsonObject child = entry.getValue().getAsJsonObject();
                if (child.has("minimum_health_percent") || child.has("subphases"))
                    throw new IllegalArgumentException("Subphases cannot contain health cutoffs or nested subphases");
                JsonElement rawType = child.get("type");
                if (rawType != null && (!rawType.isJsonPrimitive() || !rawType.getAsJsonPrimitive().isString()))
                    throw new IllegalArgumentException("Action type must be a registered text ID");
                String selected = rawType == null ? "attack" : rawType.getAsString();
                var actionType = clockwork.bosses.api.BossActionRegistry.require(ClockworkBossActions.resolve(selected));
                if (!ClockworkBossActions.WARDEN_CAPABILITIES.containsAll(actionType.requiredCapabilities()))
                    throw new IllegalArgumentException("This Warden cannot perform " + actionType.id());
                Type type = actionType.id().equals(ClockworkBossActions.RECOVERY) ? Type.RECOVERY
                        : List.of(ClockworkBossActions.COMBAT, ClockworkBossActions.MELEE, ClockworkBossActions.SONIC).contains(actionType.id()) ? Type.ATTACK : Type.ACTION;
                JsonElement rawSettings = child.get("action_settings");
                if (rawSettings != null && !rawSettings.isJsonObject())
                    throw new IllegalArgumentException("Action settings must be an object");
                JsonObject actionInput = selected.equals("attack") || selected.equals("recovery")
                        ? new JsonObject() : object(child, "action_settings").deepCopy();
                // Legacy files keep their old controls. Registered core actions compile
                // those values through exactly the same declared setting schema as addons.
                if (selected.equals("attack") && !actionInput.has("attacks_before_next"))
                    actionInput.addProperty("attacks_before_next", integer(child, "attacks_before_next", 3, 1, 1000));
                if (selected.equals("recovery") && !actionInput.has("duration_ticks"))
                    actionInput.addProperty("duration_ticks", integer(child, "duration_ticks", 60, 1, 12000));
                var actionSettings = clockwork.bosses.api.BossActionSettings.compile(actionType.settings(), actionInput);
                int duration = type == Type.RECOVERY ? actionSettings.integer("duration_ticks") : 60;
                int attacks = type == Type.ATTACK ? actionSettings.integer("attacks_before_next") : 3;
                if (actionType.id().equals(ClockworkBossActions.ROAR)) {
                    String file = actionSettings.text("file");
                    if (!file.isEmpty() && !ClockworkBossSoundPayload.isValidFileName(file)) throw new IllegalArgumentException("Use a lowercase OGG filename for roar");
                }
                JsonObject effective = new JsonObject();
                for (var field : parent.entrySet()) if (!List.of("subphases", "roars").contains(field.getKey())) effective.add(field.getKey(), field.getValue().deepCopy());
                for (String field : List.of("movement_speed", "knockback_resistance", "follow_range", "melee", "sonic_boom", "targeting")) {
                    if (type != Type.ATTACK) break; // Recovery never applies its hidden combat controls.
                    JsonElement value = child.get(field);
                    if (value == null || value.isJsonNull()) continue;
                    if (value.isJsonObject()) {
                        JsonObject merged = object(effective, field).deepCopy();
                        overlay(merged, value.getAsJsonObject()); effective.add(field, merged);
                    } else effective.add(field, value.deepCopy());
                }
                // Reuse the production field compiler with one parent identity,
                // without copying unrelated phases or altering the original tree.
                JsonObject policy = new JsonObject();
                for (var base : config.entrySet()) if (!base.getKey().equals("phases") && !base.getKey().equals("_documentation"))
                    policy.add(base.getKey(), base.getValue());
                JsonObject single = new JsonObject(); single.add(phase.key(), effective); policy.add("phases", single);
                var behavior = ClockworkBossSettings.read(policy).phase(phase);
                JsonObject melee = object(effective, "melee"), baseMelee = object(config, "attacks", "melee");
                Attributes attributes = new Attributes(number(effective, config, "movement_speed", .3, 0, 1),
                        number(effective, config, "knockback_resistance", 1, 0, 1), number(effective, config, "follow_range", 0, 0, 256),
                        number(melee, baseMelee, "damage", 30, 0, 1_000_000), number(melee, baseMelee, "knockback", 1.5, 0, 100));
                steps.add(new Step(entry.getKey(), type, duration, attacks, behavior, attributes, actionType, actionSettings));
            }
            List<clockwork.bosses.api.BossActionSettings> roars = new ArrayList<>();
            var roarType = clockwork.bosses.api.BossActionRegistry.require(ClockworkBossActions.ROAR);
            for (var entry : roarEntries.entrySet()) {
                new ClockworkBossPhase(entry.getKey());
                if (!entry.getValue().isJsonObject()) throw new IllegalArgumentException("Roar settings must be objects");
                JsonObject sound = object(config, "attacks", "roar", "sound").deepCopy();
                overlay(sound, object(parent, "roar", "sound"));
                overlay(sound, object(entry.getValue().getAsJsonObject(), "sound"));
                // New action declarations default to Auto. Legacy inherited roar lists
                // with no range anywhere retain their former 32-block fallback.
                if (!sound.has("range") || sound.get("range").isJsonNull())
                    sound.addProperty("range", ClockworkBossSoundDistance.LEGACY_FALLBACK);
                var roarSettings = clockwork.bosses.api.BossActionSettings.compile(roarType.settings(), sound);
                new clockwork.bosses.api.BossRoarSound(roarSettings.text("file"), roarSettings.number("volume"),
                        roarSettings.number("pitch"), roarSettings.number("range"));
                roars.add(roarSettings); // Validate once before publishing; no JSON merge/parse at transitions.
            }
            if (!steps.isEmpty()) sequences.put(phase, new Sequence(order, List.copyOf(steps), roarBetween, List.copyOf(roars)));
        }
        return Map.copyOf(sequences);
    }

    /** Missing catalogs disable the feature. Invalid/oversized catalogs fail
     * before publication; both edit operations and manual files keep the bound. */
    private static JsonObject catalog(JsonObject parent, String key) {
        JsonElement raw = parent.get(key);
        if (raw == null || raw.isJsonNull()) return new JsonObject();
        if (!raw.isJsonObject() || raw.getAsJsonObject().size() > MAX_SUBPHASES)
            throw new IllegalArgumentException("Use 0–16 entries in " + key);
        return raw.getAsJsonObject();
    }

    /** Null scalars inherit; nested objects inherit each field independently. */
    private static void overlay(JsonObject target, JsonObject source) {
        for (var entry : source.entrySet()) {
            if (entry.getValue().isJsonNull()) continue;
            if (entry.getValue().isJsonObject()) {
                JsonObject nested = object(target, entry.getKey()).deepCopy();
                overlay(nested, entry.getValue().getAsJsonObject()); target.add(entry.getKey(), nested);
            } else target.add(entry.getKey(), entry.getValue().deepCopy());
        }
    }
    private static JsonObject object(JsonObject root, String... path) {
        for (String key : path) {
            if (!root.has(key) || !root.get(key).isJsonObject()) return new JsonObject();
            root = root.getAsJsonObject(key);
        }
        return root;
    }
    private static double value(JsonObject source, String key, double fallback) {
        try { double value = source.get(key).getAsDouble(); return Double.isFinite(value) ? value : fallback; }
        catch (RuntimeException invalid) { return fallback; }
    }
    private static double number(JsonObject source, JsonObject base, String key, double fallback, double min, double max) {
        return Math.clamp(value(source, key, value(base, key, fallback)), min, max);
    }
    private static int integer(JsonObject source, String key, int fallback, int min, int max) {
        double value = value(source, key, fallback);
        if (value != Math.rint(value) || value < min || value > max) throw new IllegalArgumentException(key + " must be a whole number " + min + "–" + max);
        return (int) value;
    }
}
