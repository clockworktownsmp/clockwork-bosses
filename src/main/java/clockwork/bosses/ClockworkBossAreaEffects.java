package clockwork.bosses;

import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.entity.event.v1.effect.ServerMobEffectEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.Holder;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Grants native infinite effects to active encounter viewers. The manager calls
 * this class only on entry, exit, reload and cleanup; there is no effect timer or
 * extra area query. Native Fabric effect events handle competing potion effects.
 * All state belongs to the server thread and is released with player membership.
 */
public final class ClockworkBossAreaEffects {
    // A fixed whitelist avoids arbitrary registry lookups and deliberately excludes Haste.
    private enum Type {
        SPEED("speed", MobEffects.SPEED),
        JUMP_BOOST("jump_boost", MobEffects.JUMP_BOOST),
        REGENERATION("regeneration", MobEffects.REGENERATION),
        STRENGTH("strength", MobEffects.STRENGTH),
        RESISTANCE("resistance", MobEffects.RESISTANCE);

        final String key;
        final Holder<MobEffect> effect;

        Type(String key, Holder<MobEffect> effect) {
            this.key = key;
            this.effect = effect;
        }

        /** Saved vanilla tags identify our effects after a crash without custom player NBT hooks. */
        String tag(int amplifier) {
            return "ClockworkBosses_AreaEffect_" + key + "_" + amplifier;
        }
    }

    private static final Type[] TYPES = Type.values();
    private static final Map<UUID, Membership> PLAYERS = new HashMap<>();
    // Suppress only our exact player/effect operation. A different effect or
    // player changed inside another mod's callback must still be observed.
    private static Mutation mutation;
    private record Mutation(ServerPlayer player, Holder<MobEffect> effect) {}

    private ClockworkBossAreaEffects() {}

    /** Registers effect and player lifecycle events once; none introduces a repeating task. */
    public static void registerLifecycle() {
        ServerPlayerEvents.JOIN.register(ClockworkBossAreaEffects::clearSavedGrants);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            for (UUID playerId : Set.copyOf(PLAYERS.keySet())) releasePlayer(playerId);
            PLAYERS.clear();
            mutation = null;
        });
        // LEAVE fires before native player saving, unlike cleanup after the player has been saved.
        ServerPlayerEvents.LEAVE.register(player -> releasePlayer(player.getUUID()));
        ServerPlayerEvents.COPY_FROM.register((oldPlayer, newPlayer, alive) -> {
            releasePlayer(oldPlayer.getUUID());
            clearSavedGrants(newPlayer);
        });
        ServerMobEffectEvents.BEFORE_ADD.register((incoming, entity, context) -> {
            if (!(entity instanceof ServerPlayer player) || isOwnMutation(player, incoming.getEffect())) return;
            Membership member = PLAYERS.get(player.getUUID());
            if (member == null) return;
            for (Type type : TYPES) {
                if (type.effect.equals(incoming.getEffect())) {
                    // An external effect gets its own native duration and hidden-effect chain.
                    // Removing our grant first prevents infinite buffs from swallowing potion timers.
                    if (member.owned.containsKey(type) && removeOwned(member, type)) {
                        scheduleReconcile(member, type);
                    }
                    break;
                }
            }
        });
        ServerMobEffectEvents.AFTER_REMOVE.register((removed, entity, context) -> {
            if (!(entity instanceof ServerPlayer player) || isOwnMutation(player, removed.getEffect())) return;
            Membership member = PLAYERS.get(player.getUUID());
            if (member == null) return;
            for (Type type : TYPES) {
                if (type.effect.equals(removed.getEffect())) {
                    scheduleReconcile(member, type);
                    break;
                }
            }
        });
    }

    /** Native callbacks are synchronous on the server thread; nested operations restore the previous scope. */
    private static boolean isOwnMutation(ServerPlayer player, Holder<MobEffect> effect) {
        return mutation != null && mutation.player() == player && mutation.effect().equals(effect);
    }

    /** Immutable levels compiled on load/reload; old templates without the section grant nothing. */
    public static final class Settings {
        private final Map<Type, Integer> amplifiers;

        private Settings(Map<Type, Integer> amplifiers) {
            this.amplifiers = Map.copyOf(amplifiers);
        }

        /** Human level I is native amplifier zero; finite integral levels are bounded to I-V. */
        public static Settings read(JsonObject template) {
            JsonObject root = object(object(template, "boss_bar"), "area_effects");
            EnumMap<Type, Integer> values = new EnumMap<>(Type.class);
            if (enabled(root)) {
                for (Type type : TYPES) {
                    JsonObject config = object(root, type.key);
                    if (!enabled(config)) continue;
                    int level = 1;
                    try {
                        double value = config.get("level").getAsDouble();
                        if (Double.isFinite(value) && value == Math.rint(value)) {
                            level = (int) Math.max(1, Math.min(5, value));
                        }
                    } catch (RuntimeException invalid) {
                        // Missing, null and malformed values use the safe level-I default.
                    }
                    values.put(type, level - 1);
                }
            }
            return new Settings(values);
        }

        private static JsonObject object(JsonObject parent, String key) {
            return parent.has(key) && parent.get(key).isJsonObject()
                    ? parent.getAsJsonObject(key) : new JsonObject();
        }

        /** Accept actual JSON booleans only; an invalid opt-in must not silently enable buffs. */
        private static boolean enabled(JsonObject config) {
            return config.has("enabled") && config.get("enabled").isJsonPrimitive()
                    && config.getAsJsonPrimitive("enabled").isBoolean()
                    && config.get("enabled").getAsBoolean();
        }
    }

    /** Adds or updates one boss contribution; strongest configured level wins in overlapping areas. */
    public static void enter(UUID bossId, ServerPlayer player, Settings settings) {
        if (settings.amplifiers.isEmpty()) {
            leave(bossId, player.getUUID());
            return;
        }
        Membership member = PLAYERS.get(player.getUUID());
        if (member != null && member.player != player) {
            // Native respawn replaces the entity even though its UUID stays the same.
            releasePlayer(player.getUUID());
            member = null;
        }
        if (member == null) {
            member = new Membership(player);
            PLAYERS.put(player.getUUID(), member);
        }
        member.bosses.put(bossId, settings);
        reconcileAll(member);
    }

    /** Removes just this encounter's request; another active boss can keep the same grant alive. */
    public static void leave(UUID bossId, UUID playerId) {
        Membership member = PLAYERS.get(playerId);
        if (member == null || member.bosses.remove(bossId) == null) return;
        reconcileAll(member);
        if (member.bosses.isEmpty()) PLAYERS.remove(playerId, member);
    }

    /** Rare boss cleanup walks only tracked arena participants, never the server's entire player list. */
    public static void releaseBoss(UUID bossId) {
        // Native add/remove hooks may synchronously change membership in another
        // mod. A snapshot prevents those callbacks invalidating our iterator.
        for (Membership member : java.util.List.copyOf(PLAYERS.values())) {
            if (member.bosses.remove(bossId) == null) continue;
            reconcileAll(member);
            if (member.bosses.isEmpty()) PLAYERS.remove(member.player.getUUID(), member);
        }
    }

    /** Disconnect/death/respawn releases player references and cancels pending grants by identity. */
    public static void releasePlayer(UUID playerId) {
        Membership member = PLAYERS.remove(playerId);
        if (member == null) return;
        member.bosses.clear();
        reconcileAll(member);
    }

    /** Checks the five fixed effects only on actual membership/configuration changes. */
    private static void reconcileAll(Membership member) {
        for (Type type : TYPES) reconcile(member, type);
    }

    /** Resolves contributions and uses vanilla effect APIs only when the owned strength changes. */
    private static void reconcile(Membership member, Type type) {
        int requested = -1;
        for (Settings settings : member.bosses.values()) {
            requested = Math.max(requested, settings.amplifiers.getOrDefault(type, -1));
        }
        MobEffectInstance owned = member.owned.get(type);
        MobEffectInstance current = member.player.getEffect(type.effect);
        if (owned != null && current == owned && owned.getAmplifier() == requested) return;
        if (!removeOwned(member, type)) return;
        if (requested < 0 || !member.player.isAlive() || member.player.isSpectator()) return;
        // Existing potion/mod effects take precedence. AFTER_REMOVE resumes our grant on expiry.
        if (member.player.getEffect(type.effect) != null) return;
        MobEffectInstance grant = new MobEffectInstance(type.effect,
                MobEffectInstance.INFINITE_DURATION, requested, true, false, true);
        String marker = type.tag(requested);
        // The native tag limit can be reached by other mods; never create an unmarked infinite grant.
        if (!member.player.addTag(marker)) return;
        Mutation previous = mutation;
        mutation = new Mutation(member.player, type.effect);
        try {
            if (member.player.addEffect(grant) && member.player.getEffect(type.effect) == grant) {
                member.owned.put(type, grant);
                if (ClockworkBossDiagnostics.enabled()) ClockworkBossDiagnostics.entity(member.player, "area_effect_granted", "effect", type.key, "amplifier", requested);
            } else {
                member.player.removeTag(marker);
            }
        } finally {
            mutation = previous;
        }
    }

    /** Identity protects externally replaced effects; our grants never hide preexisting potion effects. */
    private static boolean removeOwned(Membership member, Type type) {
        MobEffectInstance owned = member.owned.get(type);
        if (owned == null) return true;
        Mutation previous = mutation;
        mutation = new Mutation(member.player, type.effect);
        try {
            if (member.player.getEffect(type.effect) == owned) member.player.removeEffect(type.effect);
            // Keep recovery metadata if another mod vetoed native removal of our infinite effect.
            if (member.player.getEffect(type.effect) != owned) {
                if (ClockworkBossDiagnostics.enabled()) ClockworkBossDiagnostics.entity(member.player, "owned_effect_removed", "effect", type.key, "external_effect_present", member.player.getEffect(type.effect) != null);
                member.owned.remove(type);
                member.player.removeTag(type.tag(owned.getAmplifier()));
                return true;
            }
            return false;
        } finally {
            mutation = previous;
        }
    }

    /**
     * Native effect removal can occur inside Minecraft's effect iteration. Queue
     * one ordinary server task per player/type to avoid modifying that iterator.
     * This is a one-time event response, not a tick callback or scheduled scan.
     */
    private static void scheduleReconcile(Membership member, Type type) {
        if (!member.pending.add(type)) return;
        UUID playerId = member.player.getUUID();
        var server = member.player.level().getServer();
        server.schedule(new TickTask(server.getTickCount(), () -> {
            member.pending.remove(type);
            if (PLAYERS.get(playerId) == member) reconcile(member, type);
        }));
    }

    /** Removes only tagged, matching grants saved by a previous crashed session or respawn copy. */
    private static void clearSavedGrants(ServerPlayer player) {
        for (Type type : TYPES) {
            Mutation previous = mutation;
            mutation = new Mutation(player, type.effect);
            try {
                for (int amplifier = 0; amplifier < 5; amplifier++) {
                    String marker = type.tag(amplifier);
                    if (!player.entityTags().contains(marker)) continue;
                    MobEffectInstance effect = player.getEffect(type.effect);
                    if (effect != null && effect.isInfiniteDuration() && effect.getAmplifier() == amplifier
                            && effect.isAmbient() && !effect.isVisible() && effect.showIcon()) {
                        player.removeEffect(type.effect);
                        if (player.getEffect(type.effect) == effect) continue;
                    }
                    player.removeTag(marker);
                }
            } finally {
                mutation = previous;
            }
        }
    }

    /** Strong player references exist only while requests remain; lifecycle hooks explicitly release them. */
    private static final class Membership {
        final ServerPlayer player;
        final Map<UUID, Settings> bosses = new HashMap<>();
        final Map<Type, MobEffectInstance> owned = new EnumMap<>(Type.class);
        final Set<Type> pending = EnumSet.noneOf(Type.class);

        Membership(ServerPlayer player) {
            this.player = player;
        }
    }

    static void collectPlayers(UUID boss, Map<UUID, ServerPlayer> players) {
        for (var member : PLAYERS.values()) if (member.bosses.containsKey(boss)) players.putIfAbsent(member.player.getUUID(), member.player);
    }
    /** Inspect actual matching tagged effects even after a vetoed release dropped
     * its request. Identity protects external replacements during normal cleanup;
     * recovery markers additionally reveal orphaned native grants here. */
    static clockwork.bosses.api.BossSupportSnapshot.Effects snapshot(ServerPlayer player) {
        var member = PLAYERS.get(player.getUUID());
        if (member != null && member.player != player) member = null;
        var owners = member == null ? Set.<UUID>of() : Set.copyOf(member.bosses.keySet());
        var amplifiers = new HashMap<String, Integer>();
        var markers = new java.util.HashSet<String>();
        for (Type type : TYPES) {
            var effect = player.getEffect(type.effect);
            for (int amplifier = 0; amplifier < 5; amplifier++) {
                String marker = type.tag(amplifier);
                if (!player.entityTags().contains(marker)) continue;
                markers.add(marker);
                if (effect != null && effect.getAmplifier() == amplifier && effect.isInfiniteDuration()
                        && effect.isAmbient() && !effect.isVisible() && effect.showIcon()) amplifiers.put(type.key, amplifier);
            }
            if (member != null && effect != null && member.owned.get(type) == effect) amplifiers.put(type.key, effect.getAmplifier());
        }
        return new clockwork.bosses.api.BossSupportSnapshot.Effects(owners, amplifiers, markers);
    }
}
