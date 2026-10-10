package clockwork.bosses;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.UUID;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.warden.Warden;

/** Operator-requested compatibility evidence, never an AI decision maker.
 * Server-thread event boundaries append bounded JSON snapshots only while on.
 * No tick listener, entity/player references, background writer or network data
 * is retained. Disabled callers check enabled before creating diagnostic data. */
public final class ClockworkBossDiagnostics {
    private static final int LIMIT = 2048, ATTACK_LIMIT = 128;
    private static final ArrayDeque<JsonObject> EVENTS = new ArrayDeque<>();
    private static final LinkedHashMap<UUID, Long> ATTACKS = new LinkedHashMap<>();
    private static boolean enabled;
    private static long sequence, attackSequence, overwritten;
    private static String salt = "";
    private ClockworkBossDiagnostics() {}
    public static boolean enabled() { return enabled; }

    /** Observers always allow native work. A prior mod veto can prevent Fabric
     * reaching ALLOW_DAMAGE; AFTER_DAMAGE also omits fatal hits. Explicit boss
     * attack results and AFTER_DEATH fill some gaps, never promise attribution. */
    public static void register() {
        // Preserve an active capture during an ordinary/crash shutdown when
        // Minecraft reaches this lifecycle callback. A forced process kill
        // cannot flush memory; operators should also dump after reproduction.
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (enabled) dump(server.createCommandSourceStack());
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> clear());
        ServerPlayerEvents.JOIN.register(player -> {
            if (enabled) entity(player, "player_session_join", "saved_potion_ready_tick", ((ClockworkBossPlayerData) player).clockworkBosses$potionReadyTick());
        });
        ServerPlayerEvents.LEAVE.register(player -> {
            if (enabled) entity(player, "player_session_leave");
        });
        ServerPlayerEvents.COPY_FROM.register((previous, next, alive) -> {
            if (enabled) entity(next, "player_entity_replaced", "alive_copy", alive,
                    "saved_potion_ready_tick", ((ClockworkBossPlayerData) next).clockworkBosses$potionReadyTick());
        });
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((victim, source, amount) -> {
            if (enabled) {
                Warden boss = victim instanceof Warden w && ClockworkBossManager.isClockworkBoss(w) ? w
                        : source.getEntity() instanceof Warden w && ClockworkBossManager.isClockworkBoss(w) ? w : null;
                if (boss != null) event(boss, victim == boss ? source.getEntity() : victim, "damage_callback_attempt",
                        "incoming", amount, "victim_hp", victim.getHealth(), "damage_type", source.typeHolder().unwrapKey().map(k -> k.identifier().toString()).orElse("unregistered"));
            }
            return true;
        });
        ServerLivingEntityEvents.AFTER_DAMAGE.register((victim, source, base, taken, blocked) -> {
            if (!enabled) return;
            Warden boss = victim instanceof Warden w && ClockworkBossManager.isClockworkBoss(w) ? w
                    : source.getEntity() instanceof Warden w && ClockworkBossManager.isClockworkBoss(w) ? w : null;
            if (boss != null) event(boss, victim == boss ? source.getEntity() : victim, "damage_callback_observed",
                    "callback_base", base, "callback_taken", taken, "blocked", blocked, "victim_hp", victim.getHealth());
        });
        ServerLivingEntityEvents.AFTER_DEATH.register((victim, source) -> {
            if (enabled && (victim instanceof ServerPlayer || victim instanceof Warden w && ClockworkBossManager.isClockworkBoss(w))) {
                entity(victim, "death_committed");
                if (victim instanceof Warden boss) end(boss);
            }
        });
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
            if (enabled && entity instanceof Warden boss && ClockworkBossManager.isClockworkBoss(boss)) {
                event(boss, null, "boss_entity_unload"); end(boss);
            }
        });
    }

    /** Each new capture replaces the previous buffer and uses fresh anonymous
     * aliases. Neither account names nor full player UUIDs appear in reports. */
    static void start() {
        clear(); salt = UUID.randomUUID().toString(); enabled = true;
    }
    static void clear() {
        enabled = false; EVENTS.clear(); ATTACKS.clear(); sequence = 0; attackSequence = 0; overwritten = 0; salt = "";
    }
    static JsonArray snapshot() {
        JsonArray entries = new JsonArray();
        for (JsonObject entry : EVENTS) entries.add(entry.deepCopy());
        return entries;
    }
    public static int toggle(CommandSourceStack source, boolean on) {
        if (on) start(); else { enabled = false; ATTACKS.clear(); } // Off freezes evidence until dump/clear.
        source.sendSuccess(() -> Component.literal("Boss diagnostics " + (on ? "enabled; previous capture cleared." : "disabled; capture retained.")), false);
        return 1;
    }
    public static int status(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("Boss diagnostics: " + (enabled ? "ON" : "OFF")
                + ", " + EVENTS.size() + "/" + LIMIT + " events, " + overwritten + " older events overwritten."), false);
        return 1;
    }
    public static int clearCommand(CommandSourceStack source) {
        clear(); return status(source);
    }
    /** An explicit operator dump performs bounded local IO. No template file,
     * audio/skin bytes, credentials, chat, names or connection addresses are read.
     * The runtime mod inventory identifies the exact integration under test. */
    public static int dump(CommandSourceStack source) {
        try {
            JsonObject report = new JsonObject();
            report.addProperty("schema", 1); report.addProperty("captured_at", Instant.now().toString());
            report.addProperty("enabled", enabled); report.addProperty("overwritten_events", overwritten);
            report.addProperty("attribution", "Synchronous observations only; external callbacks may alter HP/velocity. Missing callbacks do not prove no damage. No compatibility guarantee.");
            JsonObject mods = new JsonObject();
            FabricLoader.getInstance().getAllMods().stream().sorted(java.util.Comparator.comparing(m -> m.getMetadata().getId()))
                    .forEach(mod -> mods.addProperty(mod.getMetadata().getId(), mod.getMetadata().getVersion().getFriendlyString()));
            report.add("runtime_mods", mods);
            // Fingerprints identify locally installed integration settings without
            // including their contents (which may contain user-supplied text).
            JsonObject configHashes = new JsonObject();
            for (String relative : java.util.List.of("lithium.properties", "bettercombat/server.json5", "combatroll.json5")) {
                Path config = FabricLoader.getInstance().getConfigDir().resolve(relative);
                if (Files.isRegularFile(config, java.nio.file.LinkOption.NOFOLLOW_LINKS) && Files.size(config) <= 2 * 1024 * 1024)
                    configHashes.addProperty(relative, com.google.common.hash.Hashing.sha256().hashBytes(Files.readAllBytes(config)).toString());
            }
            report.add("known_config_sha256", configHashes); report.add("events", snapshot());
            Path folder = FabricLoader.getInstance().getGameDir().resolve("logs/clockwork-bosses-debug");
            Files.createDirectories(folder);
            Path file = folder.resolve("capture-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 8) + ".json");
            Files.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(report), StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE_NEW);
            source.sendSuccess(() -> Component.literal("Saved diagnostics to logs/clockwork-bosses-debug/" + file.getFileName()), false);
            return 1;
        } catch (java.io.IOException failure) {
            source.sendFailure(Component.literal("Could not write boss diagnostics: " + failure.getClass().getSimpleName()));
            return 0;
        }
    }

    /** One ID per debug attack, bounded even if an encounter never completes.
     * A delayed impact reuses its wind-up ID; completion/cancellation releases it. */
    public static void begin(Warden boss, Entity target, String kind, Object... values) {
        if (!enabled) return;
        if (!ATTACKS.containsKey(boss.getUUID()) && ATTACKS.size() >= ATTACK_LIMIT)
            ATTACKS.remove(ATTACKS.keySet().iterator().next());
        ATTACKS.put(boss.getUUID(), ++attackSequence);
        event(boss, target, kind + "_start", values);
    }
    public static void end(Warden boss) { if (enabled) ATTACKS.remove(boss.getUUID()); }
    public static void event(Warden boss, Entity actor, String kind, Object... values) {
        if (!enabled) return;
        JsonObject entry = base(boss.level().getServer(), boss, kind);
        entry.addProperty("boss", alias(boss.getUUID()));
        if (actor != null) entry.addProperty("actor", alias(actor.getUUID()));
        Long attack = ATTACKS.get(boss.getUUID());
        if (attack != null) entry.addProperty("attack", attack);
        var state = ClockworkBossManager.stateFor(boss);
        if (state != null) {
            entry.addProperty("phase", state.currentPhase() == null ? "unset" : state.currentPhase().key());
            entry.addProperty("generation", state.subphaseGeneration());
            entry.addProperty("subphase", state.subphase() == null ? "none" : state.subphase().key());
            entry.addProperty("roar", state.roarInterlude()); entry.addProperty("recovery", state.recoverySubphase());
            if (state.recoverySubphase()) entry.addProperty("recovery_due_tick", state.recoveryDeadline());
        }
        append(entry, values);
    }
    public static void entity(Entity entity, String kind, Object... values) {
        if (!enabled) return;
        JsonObject entry = base(entity.level().getServer(), entity, kind);
        entry.addProperty("actor", alias(entity.getUUID()));
        append(entry, values);
    }
    /** Support ownership can correlate aliases without keeping live entities. */
    static String alias(UUID uuid) { return com.google.common.hash.Hashing.sha256().hashUnencodedChars(salt + uuid).toString().substring(0, 16); }
    private static JsonObject base(MinecraftServer server, Entity entity, String kind) {
        JsonObject entry = new JsonObject(); entry.addProperty("sequence", ++sequence);
        entry.addProperty("server_tick", server.getTickCount()); entry.addProperty("world_tick", entity.level().getGameTime());
        entry.addProperty("event", kind); return entry;
    }
    private static void append(JsonObject entry, Object... values) {
        // Only fixed developer-provided keys/primitives enter the ring. Bounds
        // prevent malformed diagnostics from growing into a large memory cache.
        for (int i = 0; i + 1 < Math.min(values.length, 32); i += 2) {
            String key = String.valueOf(values[i]); Object value = values[i + 1];
            if (value instanceof Number number) {
                // Bad external data must be evidence, not a Gson export crash.
                if (Double.isFinite(number.doubleValue())) entry.addProperty(key, number);
                else entry.addProperty(key, "non_finite");
            }
            else if (value instanceof Boolean flag) entry.addProperty(key, flag);
            else {
                String text = String.valueOf(value);
                entry.addProperty(key, text.length() <= 512 ? text : text.substring(0, 512));
            }
        }
        if (EVENTS.size() == LIMIT) { EVENTS.removeFirst(); overwritten++; }
        EVENTS.addLast(entry);
    }
}
