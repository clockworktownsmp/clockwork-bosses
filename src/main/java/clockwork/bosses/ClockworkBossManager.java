package clockwork.bosses;

import clockwork.bosses.ClockworkBossPermissions.Action;
import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.util.Unit;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.monster.warden.WardenAi;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Owns server-side Clockwork Boss instances and encounter state. Vanilla Warden AI
 * remains responsible for initial acquisition and movement; completed attacks rotate opponents. The manager handles
 * template-driven attributes, activation, phase changes, effects, and boss bars.
 * A lightweight watcher exists only while at least one Clockwork Boss is loaded. It
 * checks dormant entities every ten ticks because their AI is disabled, while
 * active-entity work is entered from the Warden AI mixin.
 */
public final class ClockworkBossManager {
    private static final int WATCHER_INTERVAL_TICKS = 10;
    private static final int MAX_BLOCK_COORDINATE = 30_000_000;
    private static final int MAX_SELECTION_AXIS_BLOCKS = 256;
    private static final double MAX_CONFIGURED_HEALTH = ClockworkBossHealth.MAX_HEALTH;
    private static final double MAX_CONFIGURED_DAMAGE = 1_000_000.0;
    private static final double MAX_CONFIGURED_KNOCKBACK = 100.0;
    private static final double MAX_CONFIGURED_FOLLOW_RANGE = 256.0;
    private static final double MIN_CONFIGURED_SCALE = 0.25;
    private static final double MAX_CONFIGURED_SCALE = 4.0;
    private static final Logger LOGGER = LoggerFactory.getLogger(ClockworkBosses.MOD_ID);
    private static final Map<UUID, ClockworkBossState> ACTIVE = new HashMap<>();
    private static final Set<UUID> FAILED_REHYDRATION = new HashSet<>();
    private record Restoration(Warden entity, boolean noAi) {}
    private static final Map<UUID, Restoration> RESTORING = new HashMap<>();
    private static BossWatcher bossWatcher;
    // One operator-initiated copy at a time, including across world restarts;
    // borrowing Minecraft's I/O pool must not allow an unbounded work queue.
    private static final java.util.concurrent.atomic.AtomicBoolean PRESET_COPY_PENDING =
            new java.util.concurrent.atomic.AtomicBoolean();

    private ClockworkBossManager() {}

    /** Command-thread guard prevents removing a config still referenced by a loaded encounter. */
    public static boolean usesTemplate(String name) {
        return ACTIVE.values().stream().anyMatch(state -> state.templateName().equals(name))
                || RESTORING.values().stream().anyMatch(pending -> name.equals(((ClockworkBossMarker) pending.entity()).clockworkBosses$getTemplate()));
    }

    /**
     * Registers native load/unload/death/disconnect hooks once; the empty-server tick dispatcher does no
     * encounter work.
     */
    public static void registerLifecycle() {
        // Claim/capture terminal encounters before the individual support services
        // perform their redundant server-stop release. ENDED remains after release.
        ServerLifecycleEvents.SERVER_STOPPING.register(ClockworkBossManager::clearAll);
        ClockworkBossAreaEffects.registerLifecycle();
        ClockworkBossPlayerHealth.registerLifecycle();
        ClockworkBossFightPotion.registerLifecycle();
        ServerEntityEvents.ENTITY_LOAD.register(ClockworkBossManager::onEntityLoad);
        ServerEntityEvents.ENTITY_UNLOAD.register(ClockworkBossManager::onEntityUnload);

        ServerTickEvents.END_SERVER_TICK.register(ClockworkBossManager::tickServer);
        // Fabric reports accepted, nonfatal server damage. Player ownership also
        // covers projectiles; vetoed attempts, environmental damage and death do
        // not create a reaction. This observer never changes damage or AI.
        ServerLivingEntityEvents.AFTER_DAMAGE.register((victim, source, base, taken, blocked) -> {
            if (taken > 0 && !blocked && victim instanceof Warden boss && boss.isAlive()
                    && source.getEntity() instanceof ServerPlayer) {
                var state = activeFaceState(boss);
                if (state != null) {
                    state.face().hit(boss.level().getGameTime(), boss.getRandom());
                    syncFace(boss, state);
                }
            }
        });
        // Fabric fires this after death is committed; sound and support cleanup
        // remain separate from the reward service and its exactly-once claim.
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof Warden warden && isClockworkBoss(warden)) {
                ((ClockworkBossMarker) warden).clockworkBosses$clearSonicVisual();
                playDeathSound(warden);
                ClockworkBossState state = ACTIVE.get(warden.getUUID());
                if (state != null) {
                    endEncounter(warden, state, clockwork.bosses.api.BossAction.StopReason.DIED);
                    ClockworkBossMusic.end(state.uuid());
                    releasePlayerSupport(state.uuid());
                    state.bossBar().removeAllPlayers();
                    state.setBossBarPlayerIds(Set.of());
                }
            } else if (entity instanceof ServerPlayer player) {
                releasePlayerMembership(player);
            }
        });
        // Native LEAVE runs before saving player data. COPY_FROM also handles
        // dimension/respawn paths that replace the entity without disconnecting.
        ServerPlayerEvents.LEAVE.register(ClockworkBossManager::releasePlayerMembership);
        ServerPlayerEvents.COPY_FROM.register((oldPlayer, newPlayer, alive) -> {
            releasePlayerMembership(oldPlayer);
        });
    }

    /** Releases old player references and UUID membership so the next existing scan can admit a new entity. */
    private static void releasePlayerMembership(ServerPlayer player) {
        ClockworkBossAnnouncements.leave(player);
        ClockworkBossMusic.leave(player);
        UUID playerId = player.getUUID();
        ClockworkBossAreaEffects.releasePlayer(playerId);
        ClockworkBossPlayerHealth.releasePlayer(playerId);
        ClockworkBossFightPotion.releasePlayer(playerId);
        for (ClockworkBossState state : List.copyOf(ACTIVE.values())) {
            if (ACTIVE.get(state.uuid()) != state) continue;
            state.darknessNextTicks().remove(playerId);
            if (!state.bossBarPlayerIds().contains(playerId)) continue;
            state.bossBar().removePlayer(player);
            Set<UUID> remaining = new HashSet<>(state.bossBarPlayerIds());
            remaining.remove(playerId);
            state.setBossBarPlayerIds(remaining);
            clockwork.bosses.api.BossEncounterEvents.PLAYER_LEFT.invoker().onEvent(
                    encounterSnapshot(findWardenByState(player.level().getServer(), state), state), playerId);
        }
    }

    /**
     * Restores runtime state from a loaded marked Warden and applies its health-selected phase in the
     * current dimension.
     */
    private static void onEntityLoad(Entity entity, ServerLevel level) {
        if (!(entity instanceof Warden warden) || !isClockworkBoss(warden)) return;

        ClockworkBossState state = registerIfNeeded(warden);
        if (state == null) return;
        if (!state.dimension().equals(level.dimension())) {
            state.setDimension(level.dimension());
        }
        applyPhaseIfChanged(state, warden);
    }

    /**
     * Immediately releases the unloaded entity's bar, deadlines and failed-load marker; the last unload
     * drops the watcher.
     */
    private static void onEntityUnload(Entity entity, ServerLevel level) {
        if (entity instanceof Warden warden && isClockworkBoss(warden)) {
            ((ClockworkBossMarker) warden).clockworkBosses$clearSonicVisual();
            UUID uuid = warden.getUUID();
            Restoration pending = RESTORING.remove(uuid);
            if (pending != null) warden.setNoAi(pending.noAi());
            FAILED_REHYDRATION.remove(uuid);
            ClockworkBossState state = ACTIVE.remove(uuid);
            if (state != null) {
                endEncounter(warden, state, clockwork.bosses.api.BossAction.StopReason.UNLOADED);
                cleanupState(state);
            }
            stopWatcherIfUnused();
        }
    }

    /** Checks already loaded entities after server startup; later chunk loads use the same registration path. */
    public static void rehydrate(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof Warden warden && isClockworkBoss(warden)) {
                    ClockworkBossState state = registerIfNeeded(warden);
                    if (state != null) {
                        if (!state.dimension().equals(level.dimension())) {
                            state.setDimension(level.dimension());
                        }
                        applyPhaseIfChanged(state, warden);
                    }
                }
            }
        }
    }

    /**
     * Delegates to the optional watcher only; Fabric keeps this callback registered even when no bosses
     * exist.
     */
    private static void tickServer(MinecraftServer server) {
        // Fabric events cannot unregister a callback after registration. Keep
        // this dispatcher allocation-free and do no manager work without a watcher.
        BossWatcher watcher = bossWatcher;
        if (watcher != null) {
            watcher.tick(server);
        }
    }

    /** Runs only while at least one loaded Clockwork Boss needs periodic management. */
    private static final class BossWatcher {
        private void tick(MinecraftServer server) {
            if (ACTIVE.isEmpty()
                    || server.getTickCount() % WATCHER_INTERVAL_TICKS != 0) {
                return;
            }

            // Effect callbacks in other mods can synchronously spawn/remove
            // bosses. Snapshot once per existing scan, then verify ownership.
            for (ClockworkBossState state : List.copyOf(ACTIVE.values())) {
                if (ACTIVE.get(state.uuid()) != state) continue;
                Warden warden = findWardenByState(server, state);

                if (warden == null || warden.isRemoved() || !isClockworkBoss(warden)) {
                    ACTIVE.remove(state.uuid(), state);
                    cleanupState(state);
                    continue;
                }

                ServerLevel level = (ServerLevel) warden.level();
                if (!state.dimension().equals(level.dimension())) {
                    state.setDimension(level.dimension());
                }

                // Reuse this loaded-entity visit for short reaction/recovery
                // expiry. No added listener, scan, JSON parsing or new packet
                // when the selected pose has not actually changed.
                if (state.face().enabled()) syncFace(warden, state);

                // Dormant Wardens are managed here because no-AI Wardens do not run their
                // normal customServerAiStep hook. Activation therefore stays independent
                // from the Warden brain and remains cheap.
                if (state.dormant()) {
                    applyPhaseIfChanged(state, warden);
                    checkActivation(state, warden);
                    if (state.dormant()) {
                        applyDarknessIfDue(state, level, warden, level.getGameTime());
                    }
                    continue;
                }

                updateBossBar(server, state, warden);
            }

            stopWatcherIfUnused();
        }
    }

    /**
     * Runs after native Warden AI to finish emergence, select phases and process cached attacks/aura for
     * this boss.
     */
    public static void tickClockworkBossLogic(Warden warden) {
        if (!isClockworkBoss(warden) || !(warden.level() instanceof ServerLevel level)) return;

        ClockworkBossState state = registerIfNeeded(warden);
        if (state == null) return;

        if (!state.dimension().equals(level.dimension())) {
            state.setDimension(level.dimension());
        }

        applyPhaseIfChanged(state, warden);

        // Dormant and emerging Wardens should not attack or apply encounter effects.
        if (state.dormant()) return;
        if (state.emerging()) {
            if (!warden.getBrain().hasMemoryValue(MemoryModuleType.IS_EMERGING)) {
                finishEmergence(state, warden);
            }
            return;
        }

        applyDarknessIfDue(state, level, warden, level.getGameTime());
        ClockworkBossActionRuntime.tick(warden, state);
    }

    /**
     * Creates one commented default document and reports file errors through the command response.
     * Command feedback uses native text styling: green confirms success, aqua identifies names,
     * and gold calls attention to instructions. Minecraft's sendFailure already displays errors red.
     */
    public static int createTemplate(CommandSourceStack source, String templateName) {
        return ClockworkBossTemplateTasks.command(source, Action.CREATE, "Could not create template: ", () -> {
            ClockworkBossTemplateStore.create(templateName); return templateName;
        }, created -> {
            source.sendSuccess(() -> Component.literal(
                    "Created Clockwork Boss template: ").withStyle(ChatFormatting.GREEN)
                    .append(Component.literal(templateName).withStyle(ChatFormatting.AQUA))
                    .append(Component.literal("\nEdit it in config/clockwork-bosses/boss-templates/")
                            .withStyle(ChatFormatting.GOLD)), true);
        });
    }

    /** Copies config/media on native I/O workers, then reports the result once on the server thread. */
    public static int createTemplateFromPreset(CommandSourceStack source, String presetName, String templateName) {
        if (!Action.CREATE.require(source)) return 0;
        if (!ClockworkBossTemplateNames.isValid(presetName) || !ClockworkBossTemplateNames.isValid(templateName)) {
            source.sendFailure(Component.literal("Use valid lowercase preset and template names; no spaces or capitals."));
            return 0;
        }
        if (!PRESET_COPY_PENDING.compareAndSet(false, true)) {
            source.sendFailure(Component.literal("A preset is already being copied; try again when it finishes."));
            return 0;
        }
        var server = source.getServer();
        try {
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    ClockworkBossTemplateStore.createFromPreset(presetName, templateName);
                } catch (Exception failure) {
                    throw new java.util.concurrent.CompletionException(failure);
                }
            }, net.minecraft.util.Util.ioPool()).whenComplete((ignored, failure) -> {
                PRESET_COPY_PENDING.set(false);
                if (!server.isRunning()) return;
                server.execute(() -> {
                    if (failure == null) source.sendSuccess(() -> Component.literal(
                            "Created template ").withStyle(ChatFormatting.GREEN)
                            .append(Component.literal(templateName).withStyle(ChatFormatting.AQUA))
                            .append(Component.literal(" from preset "))
                            .append(Component.literal(presetName).withStyle(ChatFormatting.AQUA))
                            .append(Component.literal(". Config, sounds, voice lines and skins copied.")), true);
                    else source.sendFailure(Component.literal("Could not create template: "
                            + (failure.getCause() == null ? failure.getMessage() : failure.getCause().getMessage())));
                });
            });
        } catch (RuntimeException unavailable) {
            PRESET_COPY_PENDING.set(false);
            source.sendFailure(Component.literal("Could not schedule preset creation: " + unavailable.getMessage()));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Creating template ")
                .append(Component.literal(templateName).withStyle(ChatFormatting.AQUA))
                .append(Component.literal(" from preset "))
                .append(Component.literal(presetName).withStyle(ChatFormatting.AQUA))
                .append(Component.literal("…")), false);
        return 1;
    }

    /** Stores this command source's block position and dimension on one template. */
    public static int setSpawn(CommandSourceStack source, String templateName) {
        BlockPos pos = BlockPos.containing(source.getPosition());
        String dimension = source.getLevel().dimension().identifier().toString();
        return ClockworkBossTemplateTasks.command(source, Action.SETSPAWN, "Could not save spawn position: ", () -> {
          ClockworkBossTemplateStore.edit(templateName, config -> {
            JsonObject spawn = new JsonObject();
            spawn.addProperty("set", true);
            spawn.addProperty("x", pos.getX());
            spawn.addProperty("y", pos.getY());
            spawn.addProperty("z", pos.getZ());
            spawn.addProperty("dimension", dimension);
            config.add("spawn", spawn);
          });
          return templateName;
        }, saved -> {
            source.sendSuccess(() -> Component.literal(
                    "Spawn position for template \"").withStyle(ChatFormatting.GREEN)
                    .append(Component.literal(templateName).withStyle(ChatFormatting.AQUA))
                    .append(Component.literal("\" set to "
                            + pos.getX() + ", " + pos.getY() + ", " + pos.getZ()
                            + " in " + dimension)), true);
        });
    }

    /** Player commands load on the bounded file worker, then recheck identity,
     * permissions, loot registries and deletion before creating any entity. */
    public static int spawnAsync(CommandSourceStack source, String templateName, String instanceName) {
        long deletion = ClockworkBossTemplateEditor.deletionEpoch();
        return ClockworkBossTemplateTasks.command(source, Action.SPAWN, "Could not load boss template: ",
                () -> ClockworkBossTemplateStore.load(templateName.trim()),
                config -> {
                    if (deletion != ClockworkBossTemplateEditor.deletionEpoch()) {
                        source.sendFailure(Component.literal("A template was deleted while loading. Retry the spawn command.")); return;
                    }
                    spawnLoaded(source, templateName, instanceName, config);
                });
    }

    /** Synchronous compatibility helper for existing integrations/fixtures.
     * Commands use spawnAsync; callers with preloaded data can use spawnLoaded
     * on the server thread without doing any disk work. */
    public static int spawn(CommandSourceStack source, String templateName, String instanceName) {
        if (!Action.SPAWN.require(source)) return 0;
        try { return spawnLoaded(source, templateName, instanceName, ClockworkBossTemplateStore.load(templateName.trim())); }
        catch (Exception failure) { source.sendFailure(Component.literal("Could not load boss template: " + failure.getMessage())); return 0; }
    }

    /**
     * Creates a marked dormant vanilla Warden with template attributes; any failed initialization discards
     * its entity and runtime state.
     */
    public static int spawnLoaded(CommandSourceStack source, String templateName, String instanceName, JsonObject config) {
        if (!Action.SPAWN.require(source)) return 0;
        if (ClockworkBossTemplateEditor.isDeleting(templateName)) {
            source.sendFailure(Component.literal("This template is being deleted."));
            return 0;
        }
        templateName = templateName.trim();
        instanceName = instanceName.trim();

        if (templateName.isEmpty() || instanceName.isEmpty()) {
            source.sendFailure(Component.literal("Template and Clockwork Boss name cannot be empty."));
            return 0;
        }

        if (findByName(source.getServer(), instanceName) != null) {
            source.sendFailure(Component.literal(
                    "An Clockwork Boss named \"" + instanceName + "\" already exists."));
            return 0;
        }

        try {
            ClockworkBossLoot.validate(source.getServer(), config);
        } catch (Exception e) {
            source.sendFailure(Component.literal(
                    "Could not load template \"" + templateName + "\": " + e.getMessage()));
            return 0;
        }

        final SpawnLocation spawnLocation;
        try {
            spawnLocation = getConfiguredSpawnOrSourcePosition(source, config);
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.literal("Could not resolve template spawn: " + e.getMessage()));
            return 0;
        }

        ServerLevel level = spawnLocation.level();
        BlockPos emergencePos = spawnLocation.position();

        Warden spawnedWarden = null;
        try {
            // Create the Warden without EntityType.spawn(). This prevents the vanilla
            // triggered-spawn path from starting an emergence before our activation logic.
            Warden warden = EntityTypes.WARDEN.create(level, EntitySpawnReason.TRIGGERED);
            if (warden == null) {
                source.sendFailure(Component.literal("Minecraft failed to create the Warden."));
                return 0;
            }
            spawnedWarden = warden;

            warden.snapTo(
                    emergencePos.getX() + 0.5,
                    emergencePos.getY(),
                    emergencePos.getZ() + 0.5,
                    level.getRandom().nextFloat() * 360.0F,
                    0.0F
            );

            ClockworkBossMarker marker = (ClockworkBossMarker) (Object) warden;
            marker.clockworkBosses$setClockworkBoss(true);
            marker.clockworkBosses$setName(instanceName);
            marker.clockworkBosses$setTemplate(templateName);
            configureSkin(marker, config);
            LOGGER.info(
                    "Clockwork Boss skin configured: template={} name={} type={} player={} customFile={}",
                    templateName,
                    instanceName,
                    marker.clockworkBosses$getSkinType(),
                    marker.clockworkBosses$getSkinPlayerName(),
                    marker.clockworkBosses$getCustomSkinFile()
            );
            marker.clockworkBosses$setDormant(true);
            marker.clockworkBosses$setEmergencePos(emergencePos);

            // The Warden is allowed to exist inside the hidden holding chamber safely.
            warden.setNoAi(true);
            warden.setNoGravity(true);
            warden.setPermanentlyInvulnerable(true);
            warden.setInvisible(true);
            warden.setSilent(true);
            warden.setPersistenceRequired();

            applyBaseAttributes(warden, config, true);
            ClockworkBossState state = createState(
                    warden, instanceName, templateName, config, true, emergencePos);
            ACTIVE.put(warden.getUUID(), state);
            ensureWatcher();
            // Assign the initial scale and holding position before clients can
            // track the entity. Phase initialization itself remains silent.
            applyPhaseIfChanged(state, warden);
            if (!level.addFreshEntity(warden)) {
                ACTIVE.remove(warden.getUUID(), state);
                cleanupState(state);
                warden.discard();
                stopWatcherIfUnused();
                source.sendFailure(Component.literal("Minecraft could not add the Clockwork Boss to the world."));
                return 0;
            }

            // Seed the activation baseline immediately so spawning while already
            // inside a trigger area does not activate the boss.
            initializeActivationBaseline(state, warden);

            final String finalInstanceName = instanceName;
            final String finalTemplateName = templateName;
            source.sendSuccess(() -> Component.literal(
                    "Spawned dormant Clockwork Boss \"").withStyle(ChatFormatting.GREEN)
                    .append(Component.literal(finalInstanceName).withStyle(ChatFormatting.AQUA))
                    .append(Component.literal("\" from template \""))
                    .append(Component.literal(finalTemplateName).withStyle(ChatFormatting.AQUA))
                    .append(Component.literal("\".")), true);
            return 1;
        } catch (RuntimeException e) {
            if (spawnedWarden != null) {
                ClockworkBossState state = ACTIVE.remove(spawnedWarden.getUUID());
                if (state != null) cleanupState(state);
                spawnedWarden.discard();
                stopWatcherIfUnused();
            }
            source.sendFailure(Component.literal(
                    "Clockwork Boss spawn failed: " + e.getClass().getSimpleName()
                            + " - " + String.valueOf(e.getMessage())));
            LOGGER.error("Clockwork Boss spawn failed for template {}", templateName, e);
            return 0;
        }
    }

    /** Manually activates a dormant Clockwork Boss. */
    public static int activate(CommandSourceStack source, String instanceName) {
        if (!Action.ACTIVATE.require(source)) return 0;
        ClockworkBossState state = findStateByName(source.getServer(), instanceName);
        if (state == null) {
            source.sendFailure(Component.literal(
                    "No Clockwork Boss named \"" + instanceName + "\" was found."));
            return 0;
        }

        Warden warden = findWardenByState(source.getServer(), state);
        if (warden == null) {
            source.sendFailure(Component.literal("That Clockwork Boss is currently unloaded."));
            return 0;
        }

        if (!state.dormant()) {
            source.sendFailure(Component.literal(
                    "Clockwork Boss \"" + state.name() + "\" is already active."));
            return 0;
        }

        activateWarden(state, warden);
        source.sendSuccess(() -> Component.literal(
                "Activated Clockwork Boss \"").withStyle(ChatFormatting.GREEN)
                .append(Component.literal(state.name()).withStyle(ChatFormatting.AQUA))
                .append(Component.literal("\".")), true);
        return 1;
    }

    /**
     * Finds a loaded boss by runtime state or saved marker, then discards it and releases owned encounter
     * resources.
     */
    public static int remove(CommandSourceStack source, String instanceName) {
        if (!Action.REMOVE.require(source)) return 0;
        ClockworkBossState state = findStateByName(source.getServer(), instanceName);
        Warden warden = state == null
                ? findLoadedWardenByName(source.getServer(), instanceName)
                : findWardenByState(source.getServer(), state);

        if (warden == null && state != null) {
            source.sendFailure(Component.literal(
                    "Clockwork Boss \"" + state.name()
                            + "\" is unloaded. Load its chunk before removing it."));
            return 0;
        }
        if (warden == null) {
            source.sendFailure(Component.literal(
                    "No Clockwork Boss named \"" + instanceName + "\" was found."));
            return 0;
        }

        String removedName = ((ClockworkBossMarker) (Object) warden).clockworkBosses$getName();
        warden.discard();
        UUID uuid = warden.getUUID();
        FAILED_REHYDRATION.remove(uuid);
        if (state != null) {
            cleanupState(state);
            ACTIVE.remove(state.uuid());
        }
        // A marked loaded entity can lack runtime state after a failed load.
        // Release its grants by UUID anyway; copied bottles without a remaining
        // grant are cleaned once, including players outside the old arena.
        releasePlayerSupport(uuid);
        ClockworkBossFightPotion.clearOrphanedBottles(source.getServer());
        stopWatcherIfUnused();

        source.sendSuccess(() -> Component.literal(
                "Clockwork Boss \"").withStyle(ChatFormatting.GREEN)
                .append(Component.literal(removedName).withStyle(ChatFormatting.AQUA))
                .append(Component.literal("\" removed.")), true);
        return 1;
    }

    /**
     * Discards loaded marked Wardens across dimensions, including failed template loads; it never forces
     * unloaded chunks to load.
     */
    public static int removeAll(CommandSourceStack source) {
        if (!Action.REMOVE_ALL.require(source)) return 0;
        Set<UUID> removedIds = new HashSet<>();
        for (ServerLevel level : source.getServer().getAllLevels()) {
            List<Entity> loadedEntities = new ArrayList<>();
            level.getAllEntities().forEach(loadedEntities::add);
            for (Entity entity : loadedEntities) {
                if (entity instanceof Warden warden && isClockworkBoss(warden)
                        && removedIds.add(warden.getUUID())) {
                    warden.discard();
                }
            }
        }

        int count = removedIds.size();
        for (ClockworkBossState state : List.copyOf(ACTIVE.values())) {
            cleanupState(state);
        }
        ACTIVE.clear();
        FAILED_REHYDRATION.removeAll(removedIds);
        stopWatcherIfUnused();

        final int removedCount = count;
        source.sendSuccess(() -> Component.literal(
                "Removed " + removedCount + " loaded Clockwork Boss(s).").withStyle(ChatFormatting.GREEN), true);
        return removedCount;
    }

    /** Lists loaded encounter states and current health without changing entity AI or loading more chunks. */
    public static int listActive(CommandSourceStack source) {
        if (!Action.LIST.require(source)) return 0;
        rehydrate(source.getServer());
        if (ACTIVE.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No active Clockwork Bosses.").withStyle(ChatFormatting.GRAY), false);
            return 1;
        }

        source.sendSuccess(() -> Component.literal("Clockwork Bosses:").withStyle(ChatFormatting.GOLD), false);
        for (ClockworkBossState state : ACTIVE.values()) {
            Warden warden = findWardenByState(source.getServer(), state);
            String health = warden == null
                    ? "unloaded"
                    : String.format("%.0f/%.0f HP", warden.getHealth(), warden.getMaxHealth());
            source.sendSuccess(() -> Component.literal("- ")
                    .append(Component.literal(state.name()).withStyle(ChatFormatting.AQUA))
                    .append(Component.literal(" [" + state.templateName() + "]").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(" - "))
                    .append(Component.literal(state.dormant() ? "DORMANT" : state.emerging() ? "EMERGING" : health)
                            .withStyle(state.dormant() ? ChatFormatting.GRAY
                                    : state.emerging() ? ChatFormatting.YELLOW : ChatFormatting.GREEN)), false);
        }
        return 1;
    }

    /**
     * Reports the cached template-name list; disk scans happen during create/save/reload instead of
     * autocomplete.
     */
    public static int listTemplates(CommandSourceStack source) {
        if (!Action.TEMPLATE_LIST.require(source)) return 0;
        try {
            var templates = ClockworkBossTemplateStore.list();
            if (templates.isEmpty()) {
                source.sendSuccess(() -> Component.literal("No Clockwork Boss templates exist.").withStyle(ChatFormatting.GRAY), false);
                return 1;
            }

            source.sendSuccess(() -> Component.literal("Clockwork Boss templates:").withStyle(ChatFormatting.GOLD), false);
            for (String template : templates) {
                source.sendSuccess(() -> Component.literal("- ")
                        .append(Component.literal(template).withStyle(ChatFormatting.AQUA)), false);
            }
            return templates.size();
        } catch (Exception e) {
            source.sendFailure(Component.literal(
                    "Could not list templates: " + e.getMessage()));
            return 0;
        }
    }

    /**
     * Reloads each used template once, reapplies loaded boss settings, and invalidates server media caches
     * for connected trackers.
     */
    private record ReloadFiles(Map<String, JsonObject> templates, Map<String, String> failures) {}

    /** Capture used names on Minecraft's thread; the worker scans suggestions
     * and reads each document once. Apply only after returning to that thread. */
    public static int reloadAsync(CommandSourceStack source) {
        List<String> names = ACTIVE.values().stream().map(ClockworkBossState::templateName).distinct().toList();
        return ClockworkBossTemplateTasks.command(source, Action.RELOAD, "Could not reload templates: ",
                () -> readReloadFiles(names), files -> applyReload(source, files));
    }

    /** Existing synchronous fixture/integration helper. Live commands and GUI
     * use reloadAsync; this retains the old return/result contract. */
    public static int reload(CommandSourceStack source) {
        if (!Action.RELOAD.require(source)) return 0;
        try { return applyReload(source, readReloadFiles(ACTIVE.values().stream()
                .map(ClockworkBossState::templateName).distinct().toList())); }
        catch (Exception failure) { source.sendFailure(Component.literal("Could not reload templates: " + failure.getMessage())); return 0; }
    }

    private static ReloadFiles readReloadFiles(List<String> names) throws Exception {
        ClockworkBossTemplateStore.refreshCache();
        Map<String, JsonObject> templates = new HashMap<>(); Map<String, String> failures = new HashMap<>();
        for (String name : names) {
            try { templates.put(name, ClockworkBossTemplateStore.load(name)); }
            catch (Exception failure) { failures.put(name, ClockworkBossTemplateTasks.reason(failure)); }
        }
        return new ReloadFiles(Map.copyOf(templates), Map.copyOf(failures));
    }

    /** Native-only application owns registry checks, media invalidation and
     * encounter interruption. Failed documents keep the previous fight config. */
    private static int applyReload(CommandSourceStack source, ReloadFiles files) {
        if (!Action.RELOAD.require(source)) return 0;
        ClockworkBossMusic.reload(source.getServer());
        ClockworkBossSkinNetwork.refreshFileCache();
        ClockworkBossSoundNetwork.refreshFileCache();
        FAILED_REHYDRATION.clear();
        rehydrate(source.getServer());

        int updated = 0;
        // Bosses using one template share this read-only snapshot during reload;
        // ten instances of the same boss do not cause ten disk parses.
        Set<String> validated = new HashSet<>();
        for (ClockworkBossState state : List.copyOf(ACTIVE.values())) {
            if (ACTIVE.get(state.uuid()) != state) continue;
            try {
                JsonObject config = files.templates().get(state.templateName());
                if (config == null) {
                    String error = files.failures().get(state.templateName());
                    if (error != null) source.sendFailure(Component.literal("Could not reload template \""
                            + state.templateName() + "\": " + error));
                    continue;
                }
                if (!validated.contains(state.templateName())) {
                    ClockworkBossLoot.validate(source.getServer(), config);
                    validated.add(state.templateName());
                }
                if (!state.setConfig(config) || ACTIVE.get(state.uuid()) != state) continue;
                ClockworkBossSoundNetwork.prepareTemplate(state.templateName(), config);
                Warden warden = findWardenByState(source.getServer(), state);
                if (warden != null) {
                    configureSkin((ClockworkBossMarker) (Object) warden, config);
                    ClockworkBossSkinNetwork.refreshTrackedSkin(warden);
                    applyBaseAttributes(warden, config, false);
                    if (!state.setCurrentPhase(null) || ACTIVE.get(state.uuid()) != state) continue;
                    applyPhaseIfChanged(state, warden);
                    ensureBossBarConfiguration(state);
                    updateBossBar(source.getServer(), state, warden);
                    // Reload is a configuration event, so existing viewers must
                    // receive changed levels even when their UUID membership is unchanged.
                    for (ServerPlayer player : Set.copyOf(state.bossBar().getPlayers())) {
                        enterPlayerSupport(state, warden, player);
                    }
                    updated++;
                }
            } catch (Exception e) {
                source.sendFailure(Component.literal(
                        "Could not reload template \"" + state.templateName()
                                + "\": " + e.getMessage()));
            }
        }

        final int finalUpdated = updated;

        source.sendSuccess(() -> Component.literal(
                "Reloaded Clockwork Boss templates for " + finalUpdated + " loaded Clockwork Boss(es).")
                .withStyle(ChatFormatting.GREEN), true);
        return 1;
    }

    /** Reads the explicit encounter marker; ordinary Wardens never qualify through skin, name or tint alone. */
    public static boolean isClockworkBoss(Warden warden) {
        return ((ClockworkBossMarker) (Object) warden)
                .clockworkBosses$isClockworkBoss();
    }

    /** Bounds area selections so user-authored boxes cannot trigger huge entity queries. */
    static boolean isAreaSizeAllowed(BlockPos first, BlockPos second) {
        return axisLength(first.getX(), second.getX()) <= MAX_SELECTION_AXIS_BLOCKS
                && axisLength(first.getY(), second.getY()) <= MAX_SELECTION_AXIS_BLOCKS
                && axisLength(first.getZ(), second.getZ()) <= MAX_SELECTION_AXIS_BLOCKS;
    }

    /** Computes inclusive selection length using long arithmetic so extreme coordinates cannot overflow. */
    private static long axisLength(int first, int second) {
        return Math.abs((long) first - second) + 1;
    }

    /**
     * Commit health-selected phase attributes before native combat reads them.
     * Called before Brain.tick and again before a direct melee attempt. Missing
     * or locked encounter state rejects a marked boss hit instead of mixing defaults.
     */
    public static ClockworkBossSettings.Phase prepareCombatPhase(Warden warden) {
        if (!isClockworkBoss(warden) || !(warden.level() instanceof ServerLevel)) return null;
        ClockworkBossState state = registerIfNeeded(warden);
        if (state == null) return null;
        if (state.actionsStopping()) return null; // No native combat during outgoing restoration.
        applyPhaseIfChanged(state, warden);
        if (state.dormant() || state.emerging() || !warden.isAlive()) return null;
        // An initially dormant or rehydrated boss may already have its parent
        // phase while no action exists. Commit its first child's attributes as
        // well as selecting the action before native combat can read damage.
        if (state.actionRuntime() == null) applySubphaseAttributes(warden, state);
        else ClockworkBossActionRuntime.ensure(warden, state);
        if (ACTIVE.get(state.uuid()) != state || state.encounterEnded() || warden.isRemoved()) return null;
        if (isRoarAction(state)) { requestRoarInterlude(warden, state); return null; }
        if (state.actionRuntime() == null || state.actionRuntime().pausesNativeBehaviors()) return null;
        return state.combatSettings();
    }

    /** Read-only public event data is built at transitions, not every AI tick. */
    public static clockwork.bosses.api.BossEncounterSnapshot encounterSnapshot(Warden boss, ClockworkBossState state) {
        var step = state.subphase();
        return new clockwork.bosses.api.BossEncounterSnapshot(state.uuid(), state.templateName(), state.name(),
                state.dimension().identifier().toString(), state.currentPhase() == null ? "" : state.currentPhase().key(),
                step == null ? "" : step.key(), boss != null ? boss.level().getGameTime()
                : state.actionRuntime() == null ? 0 : state.actionRuntime().lastGameTime(), state.bossBarPlayerIds());
    }
    /** Native mob hooks ask the current action for permission; phase/cooldown
     * gates remain authoritative and ordinary Wardens never enter this path. */
    public static boolean allowsNativeAttack(Warden boss, net.minecraft.resources.Identifier attack) {
        var state = stateFor(boss);
        return state != null && state.actionRuntime() != null && state.actionRuntime().allows(attack);
    }
    private static boolean isRoarAction(ClockworkBossState state) {
        var runtime = state.actionRuntime();
        return runtime != null && runtime.ownsRoar();
    }
    /** Actions complete through one generation-checked transition. A suspended
     * or replaced action cannot finish a later phase on behalf of its old owner. */
    static void onActionCompleted(Warden boss, ClockworkBossState state, long generation) {
        if (stateFor(boss) != state || !boss.isAlive() || boss.isRemoved() || state.dormant() || state.emerging()
                || generation != state.subphaseGeneration()) return;
        if (state.roarInterlude()) { if (!state.advanceSubphase(boss.getRandom())) return; }
        else if (!state.completeAction(boss.getRandom())) return;
        applySubphaseAttributes(boss, state);
    }
    /** Native damage has already been accepted. Failed swings and cancelled
     * charges never emit ATTACK_LANDED; callbacks cannot grant a second hit. */
    public static void onAttackLanded(Warden boss, net.minecraft.world.entity.LivingEntity target,
                                      net.minecraft.resources.Identifier attack, float damage) {
        var state = stateFor(boss);
        if (state != null) clockwork.bosses.api.BossEncounterEvents.ATTACK_LANDED.invoker().onEvent(
                encounterSnapshot(boss, state), attack, target.getUUID(), Float.isFinite(damage) ? Math.max(0, damage) : 0);
    }
    private static void endEncounter(Warden boss, ClockworkBossState state, clockwork.bosses.api.BossAction.StopReason reason) {
        if (!state.claimEncounterEnd()) return;
        var observation = ClockworkBossEncounterObservers.begin(boss, state, reason);
        boolean completed = false;
        try {
            state.face().clear();
            syncFace(boss, state);
            var snapshot = encounterSnapshot(boss, state);
            if (state.actionRuntime() != null) state.actionRuntime().stop(reason);
            var departing = state.bossBarPlayerIds();
            state.setBossBarPlayerIds(Set.of());
            state.bossBar().removeAllPlayers();
            ClockworkBossMusic.end(state.uuid());
            ClockworkBossEncounterObservers.beforeRelease(observation);
            try { releasePlayerSupport(state.uuid()); }
            finally { ClockworkBossEncounterObservers.afterRelease(observation); }
            var departureSnapshot = encounterSnapshot(boss, state);
            for (var player : departing) clockwork.bosses.api.BossEncounterEvents.PLAYER_LEFT.invoker().onEvent(departureSnapshot, player);
            clockwork.bosses.api.BossEncounterEvents.ENDED.invoker().onEvent(snapshot, reason);
            completed = true;
        } finally { ClockworkBossEncounterObservers.finish(observation, state, completed); }
    }

    /** Completed swings/beams choose another opponent; rejected attempts and cancelled charges do not. */
    public static void onAttackCompleted(Warden warden, Entity attacked) {
        ClockworkBossState state = stateFor(warden);
        if (state != null) onAttackCompleted(warden, attacked, state.subphaseGeneration());
    }
    public static void onAttackCompleted(Warden warden, Entity attacked, long generation) {
        ClockworkBossState state = stateFor(warden);
        if (state == null || generation != state.subphaseGeneration() || !warden.isAlive() || warden.isRemoved()) return;
        if (ClockworkBossDiagnostics.enabled()) ClockworkBossDiagnostics.event(warden, attacked, "melee_completed", "captured_generation", generation);
        if (!state.encounterEnded() && !state.dormant() && !state.emerging()) {
            state.face().afterAttack(warden.getRandom());
            syncFace(warden, state);
        }
        ClockworkBossTargets.afterAttack(warden, state, attacked);
        completeSubphaseAttack(warden, state, generation);
        ClockworkBossDiagnostics.end(warden);
    }

    /** Fired beams share rotation accounting, with their captured range instead
     * of follow range. Cancelled charges never call this completion event. */
    public static void onRangedAttackCompleted(Warden warden, Entity attacked, ClockworkBossSettings.SonicBoom shot) {
        ClockworkBossState state = stateFor(warden);
        if (state != null) onRangedAttackCompleted(warden, attacked, shot, state.subphaseGeneration());
    }
    public static void onRangedAttackCompleted(Warden warden, Entity attacked, ClockworkBossSettings.SonicBoom shot, long generation) {
        ClockworkBossState state = stateFor(warden);
        if (state == null || generation != state.subphaseGeneration() || !warden.isAlive() || warden.isRemoved()) return;
        if (ClockworkBossDiagnostics.enabled()) ClockworkBossDiagnostics.event(warden, attacked, "sonic_completed", "captured_generation", generation);
        if (!state.encounterEnded() && !state.dormant() && !state.emerging()) {
            state.face().afterAttack(warden.getRandom());
            syncFace(warden, state);
        }
        ClockworkBossTargets.afterAttack(warden, state, attacked, shot);
        completeSubphaseAttack(warden, state, generation);
        ClockworkBossDiagnostics.end(warden);
    }
    /** Advance only the sequence which produced this attack; nested damage/mod
     * callbacks may already have removed the boss or selected another health phase. */
    private static void completeSubphaseAttack(Warden boss, ClockworkBossState state, long generation) {
        if (stateFor(boss) != state || !boss.isAlive() || state.dormant() || state.emerging()
                || generation != state.subphaseGeneration()) return;
        if (state.completeSubphaseAttack(boss.getRandom())) applySubphaseAttributes(boss, state);
    }

    /** Request Minecraft's existing ROAR activity, retaining its own target,
     * navigation stop, pose, memory expiry and normal 84-tick completion. */
    static void requestRoarInterlude(Warden boss, ClockworkBossState state) {
        if (!isRoarAction(state) || state.dormant() || state.emerging()) return;
        if (state.actionRuntime().nativeRoarFinished()) return;
        var brain = boss.getBrain();
        if (boss.hasPose(net.minecraft.world.entity.Pose.ROARING) || brain.hasMemoryValue(MemoryModuleType.ROAR_TARGET)) return;
        var marker = (ClockworkBossMarker) boss;
        long now = boss.level().getGameTime();
        // Let the completed attack recover before layering a roar over its pose.
        if (marker.clockworkBosses$meleeVisualMillis(now, 0) >= 0 || marker.clockworkBosses$sonicVisualMillis(now, 0) >= 0) return;
        var target = boss.getTarget();
        if (target == null || !target.isAlive() || !boss.canTargetEntity(target)) return;
        ClockworkBossSonicBoom.cancelCharge(boss, state);
        ClockworkBossMelee.cancel(boss, state);
        brain.eraseMemory(MemoryModuleType.ATTACK_TARGET);
        brain.setMemory(MemoryModuleType.ROAR_TARGET, target);
        brain.setActiveActivityIfPossible(net.minecraft.world.entity.schedule.Activity.ROAR);
    }
    /** Native start/stop hooks carry a generation token instead of retaining an entity/state. */
    public static long roarInterludeToken(Warden boss) {
        var state = stateFor(boss);
        return state != null && !state.dormant() && !state.emerging() && isRoarAction(state) ? state.subphaseGeneration() : -1;
    }
    public static boolean playRoarInterludeSound(Warden boss) {
        var state = stateFor(boss);
        if (state == null || !isRoarAction(state)) return false;
        JsonObject sound = new JsonObject(); var settings = state.actionRuntime().roarSound();
        sound.addProperty("file", settings.file()); sound.addProperty("volume", settings.volume());
        sound.addProperty("pitch", settings.pitch()); sound.addProperty("range", settings.range());
        String file = string(sound, "file", "");
        if (file.isBlank()) return false; // Blank keeps Minecraft's normal roar sound.
        playConfiguredSound(boss, sound);
        return true;
    }
    public static void onRoarInterludeCompleted(Warden boss, long generation) {
        var state = stateFor(boss);
        if (generation < 0 || state == null || generation != state.subphaseGeneration() || !isRoarAction(state) || !boss.isAlive()) return;
        state.actionRuntime().finishRoar();
    }

    /** Briefly retain a valid opponent during ordinary native/mod target changes.
     * Clearing a target or replacing a dead/ineligible one is always allowed. */
    public static boolean keepCurrentTarget(Warden boss, net.minecraft.world.entity.LivingEntity current,
                                            net.minecraft.world.entity.LivingEntity requested) {
        ClockworkBossState state = stateFor(boss);
        if (state == null || state.dormant() || state.emerging() || state.currentPhase() == null
                || current == null || requested == null || current.getUUID().equals(requested.getUUID())
                || !current.isAlive() || current.isRemoved() || !boss.canTargetEntity(current) || current.level() != boss.level()
                || current instanceof ServerPlayer player && (player.hasDisconnected() || !isPlayerInArena(state.uuid(), player))) return false;
        var phase = state.combatSettings();
        double rawRange = boss.getAttributeValue(Attributes.FOLLOW_RANGE);
        double range = Double.isNaN(rawRange) ? 0 : Math.clamp(rawRange, 0, 256);
        if (current.distanceToSqr(boss) > range * range
                && !(phase.sonic().enabled() && boss.closerThan(current, phase.sonic().horizontalRange(), phase.sonic().verticalRange()))) return false;
        long now = boss.level().getGameTime();
        state.rememberTarget(current.getUUID(), now);
        return state.targetHoldActive(now, phase.targeting().minTargetTicks());
    }

    /** A real native target change cancels an old telegraph and may emit a throttled encounter title. */
    public static void onTargetChanged(Warden boss, net.minecraft.world.entity.LivingEntity actual) {
        ClockworkBossState state = stateFor(boss);
        if (state == null) return;
        if (ClockworkBossDiagnostics.enabled()) ClockworkBossDiagnostics.event(boss, actual, "target_changed", "has_target", actual != null);
        state.rememberTarget(actual == null ? null : actual.getUUID(), boss.level().getGameTime());
        if (state.sonicCharge() != null && (actual == null || !state.sonicCharge().targetUuid().equals(actual.getUUID())))
            ClockworkBossSonicBoom.cancelCharge(boss, state);
        if (state.pendingMelee() != null && (actual == null || !state.pendingMelee().target().equals(actual.getUUID())))
            ClockworkBossMelee.cancel(boss, state);
        if (!state.encounterEnded() && !state.dormant() && !state.emerging()) {
            state.face().targetChanged(actual != null, boss.level().getGameTime(), boss.getRandom());
            syncFace(boss, state);
        }
        if (actual instanceof ServerPlayer player) ClockworkBossAnnouncements.changed(boss, state, player);
    }

    /** Rolls once per successful hit, after checking dormancy and the current clip deadline. */
    public static void onSuccessfulAttack(Warden warden) {
        ClockworkBossState state = stateFor(warden);
        if (state == null || state.dormant() || state.emerging()) return;
        if (ClockworkBossDiagnostics.enabled()) ClockworkBossDiagnostics.event(warden, warden.getTarget(), "successful_attack_feedback");
        ClockworkBossSettings.VoiceLines voice = state.settings().voiceLines();
        long now = System.nanoTime();
        if (!voice.enabled() || voice.chance() <= 0 || voice.volume() <= 0
                || state.voiceLineBusy(now) || !ClockworkBossSoundNetwork.hasVoiceLines(state.templateName())
                || warden.getRandom().nextDouble() >= voice.chance()) return;
        // Reserve the boss while its file loads. Completion is a one-time
        // server task; it releases the gate on failure or sets the real duration.
        state.reserveVoiceFile();
        ClockworkBossSoundNetwork.playVoiceLine(warden, voice.volume(), voice.pitch(), soundDistance(warden, voice.range()), duration -> {
            if (stateFor(warden) != state) return;
            if (duration > 0) state.beginVoiceLine(System.nanoTime(), duration);
            else state.endVoiceLine();
        });
    }

    /** Builds command suggestions from loaded runtime identities only, without entity/world scans. */
    public static Set<String> getActiveBossNames() {
        Set<String> names = new HashSet<>();
        for (ClockworkBossState state : ACTIVE.values()) {
            names.add(state.name());
        }
        return names;
    }

    /**
     * Returns a loaded boss's runtime record without creating it; hit-triggered
     * features use this lookup without scanning worlds or loading entities.
     */
    public static ClockworkBossState stateFor(Warden warden) {
        return ACTIVE.get(warden.getUUID());
    }

    /** Supported request reuses the same native assignment as ordinary rotation.
     * No target search, forced hold bypass or new cooldown policy is introduced. */
    public static clockwork.bosses.api.BossTargetRequest requestTarget(Warden boss, ServerPlayer target) {
        if (!(boss.level() instanceof ServerLevel level) || !level.getServer().isSameThread())
            throw new IllegalStateException("Target requests require the owning server thread");
        var state = stateFor(boss);
        if (state == null) return clockwork.bosses.api.BossTargetRequest.UNSUPPORTED;
        if (state.encounterEnded() || state.dormant() || state.emerging() || state.currentPhase() == null
                || !boss.isAlive() || boss.isRemoved() || target == null || target.isRemoved()
                || target.level() != level || !boss.canTargetEntity(target) || !isPlayerInArena(state.uuid(), target))
            return clockwork.bosses.api.BossTargetRequest.INELIGIBLE;
        var current = boss.getTarget();
        if (current == target) return clockwork.bosses.api.BossTargetRequest.UNCHANGED;
        if (keepCurrentTarget(boss, current, target)) return clockwork.bosses.api.BossTargetRequest.HELD;
        long generation = state.subphaseGeneration();
        ClockworkBossTargets.assign(boss, state, target);
        return stateFor(boss) == state && !state.encounterEnded() && state.subphaseGeneration() == generation
                && boss.isAlive() && !boss.isRemoved() && boss.getTarget() == target
                ? clockwork.bosses.api.BossTargetRequest.ACCEPTED : clockwork.bosses.api.BossTargetRequest.REJECTED;
    }

    /** Cheap listener-side relevance check: no snapshots, world/player scans or
     * client bookkeeping. A direct boss source can connect a native hit before
     * the next viewer diff. Closed encounters never acquire observation state. */
    static boolean observesVitals(net.minecraft.world.entity.LivingEntity entity, UUID sourceBoss) {
        var direct = ACTIVE.get(entity.getUUID());
        if (direct != null && !direct.encounterEnded()) return true;
        var source = sourceBoss == null ? null : ACTIVE.get(sourceBoss);
        if (source != null && !source.encounterEnded()) return true;
        if (entity instanceof ServerPlayer) for (var state : ACTIVE.values())
            if (!state.encounterEnded() && observesMember(state, entity)) return true;
        return false;
    }
    static boolean observesEncounter(UUID boss) {
        var state = boss == null ? null : ACTIVE.get(boss);
        return state != null && !state.encounterEnded();
    }
    private static boolean observesMember(ClockworkBossState state, net.minecraft.world.entity.LivingEntity entity) {
        // addPlayer precedes support entry and committing the cached UUID diff.
        // Include that actual viewer so the initial health grant is observable.
        return entity instanceof ServerPlayer player && (state.bossBarPlayerIds().contains(player.getUUID())
                || state.bossBar().getPlayers().contains(player));
    }
    static ClockworkBossVitals.Moments vitalsMoments(net.minecraft.world.entity.LivingEntity entity, UUID sourceBoss) {
        var result = new ArrayList<clockwork.bosses.api.BossVitalsChange.Moment>();
        boolean truncated = false;
        for (var state : ACTIVE.values()) {
            if (state.encounterEnded() || !(state.uuid().equals(entity.getUUID()) || state.uuid().equals(sourceBoss)
                    || observesMember(state, entity))) continue;
            if (result.size() == 64) { truncated = true; break; }
            var step = state.subphase();
            result.add(new clockwork.bosses.api.BossVitalsChange.Moment(state.uuid(), state.templateName(), state.name(),
                    state.dimension().identifier().toString(), state.currentPhase() == null ? "" : state.currentPhase().key(),
                    step == null ? "" : step.key(), entity.level().getGameTime()));
        }
        return new ClockworkBossVitals.Moments(result, truncated);
    }

    /** Server visual lifecycle hooks do a loaded-state lookup only. Client
     * previews, ordinary Wardens and disabled rules cannot start an expression. */
    private static ClockworkBossState activeFaceState(Warden boss) {
        if (!(boss.level() instanceof ServerLevel) || !boss.isAlive() || boss.isRemoved()) return null;
        var state = stateFor(boss);
        return state != null && !state.encounterEnded() && !state.dormant() && !state.emerging() && state.face().enabled() ? state : null;
    }
    public static long beginFaceAttack(Warden boss, ClockworkBossFaceEvents.Attack type, long now, int duration) {
        var state = activeFaceState(boss);
        if (state == null) return 0;
        long token = state.face().begin(type, now, duration, boss.getRandom());
        syncFace(boss, state);
        return token;
    }
    public static void finishFaceAttack(Warden boss, long token, long now, int recovery) {
        var state = activeFaceState(boss);
        if (state == null || token == 0) return;
        state.face().finish(token, now, recovery);
        syncFace(boss, state);
    }
    public static void cancelFaceAttack(Warden boss, long token) {
        if (!(boss.level() instanceof ServerLevel) || token == 0) return;
        var state = stateFor(boss);
        if (state != null) { state.face().cancel(token); syncFace(boss, state); }
    }
    private static void syncFace(Warden boss, ClockworkBossState state) {
        if (boss == null) return; // Shutdown/missing-entity cleanup can release state without a loaded mob.
        var marker = (ClockworkBossMarker) boss;
        int pose = state.face().packed(boss.level().getGameTime());
        if (marker.clockworkBosses$getBrows() != pose) marker.clockworkBosses$setBrows(pose);
    }

    /**
     * Creates one runtime record from saved identity; invalid templates log once per loaded entity and
     * remain removable.
     */
    private static ClockworkBossState registerIfNeeded(Warden warden) {
        UUID uuid = warden.getUUID();
        ClockworkBossState existing = ACTIVE.get(uuid);
        if (existing != null) return existing;
        if (FAILED_REHYDRATION.contains(uuid)) return null;
        if (RESTORING.containsKey(uuid)) return null;

        ClockworkBossMarker marker = (ClockworkBossMarker) (Object) warden;
        var burrowCheckpoint = marker.clockworkBosses$getBurrowCheckpoint();
        if (burrowCheckpoint != null) burrowCheckpoint.restore(warden);
        String name = marker.clockworkBosses$getName();
        String template = marker.clockworkBosses$getTemplate();
        if (name == null || name.isBlank() || template == null || template.isBlank()) {
            return null;
        }

        // A loaded saved boss waits quietly for its bounded disk read. Retain
        // only one request per UUID and restore its prior AI flag on completion
        // or unload. No tick retries, folder scans or JSON parsing happen here.
        var request = new Restoration(warden, warden.isNoAi());
        RESTORING.put(uuid, request); warden.setNoAi(true);
        boolean queued = ClockworkBossTemplateTasks.submit(warden.level().getServer(),
                () -> ClockworkBossTemplateStore.load(template), (config, failure) -> {
                    if (!RESTORING.remove(uuid, request)) return;
                    warden.setNoAi(request.noAi());
                    if (warden.isRemoved() || !isClockworkBoss(warden)
                            || !template.equals(marker.clockworkBosses$getTemplate())
                            || !name.equals(marker.clockworkBosses$getName())) return;
                    if (failure != null) {
                        FAILED_REHYDRATION.add(uuid);
                        LOGGER.error("Could not restore saved Clockwork Boss '{}' from template '{}': {}", name, template,
                                ClockworkBossTemplateTasks.reason(failure)); return;
                    }
                    ClockworkBossState restored = restoreLoaded(warden, config);
                    if (restored != null) applyPhaseIfChanged(restored, warden);
                });
        if (!queued) {
            RESTORING.remove(uuid, request); warden.setNoAi(request.noAi());
            FAILED_REHYDRATION.add(uuid);
            LOGGER.warn("Saved Clockwork Boss '{}' is waiting for a busy/stopping file worker; use /boss reload to retry.", name);
        }
        return null;
    }

    /** Completes restoration only on the native thread, using a parsed worker
     * snapshot. Permission-free restore does not start a new encounter or heal
     * players; it reconnects the persisted boss with existing lifecycle rules. */
    private static ClockworkBossState restoreLoaded(Warden warden, JsonObject config) {
        UUID uuid = warden.getUUID();
        if (ACTIVE.containsKey(uuid)) return ACTIVE.get(uuid);
        ClockworkBossMarker marker = (ClockworkBossMarker) warden;
        String name = marker.clockworkBosses$getName(), template = marker.clockworkBosses$getTemplate();

        try {
            ClockworkBossLoot.validate(warden.level().getServer(), config);
            // Eye alignment belongs to the template, not old entity NBT; restore it on every chunk load/restart.
            marker.clockworkBosses$setEyes(ClockworkBossEyes.read(config).packed());
            marker.clockworkBosses$setBrows(ClockworkBossBrows.read(config).packed());
            marker.clockworkBosses$setPupils(ClockworkBossPupils.read(config).packed());
            if (marker.clockworkBosses$getSkinType().isBlank()) {
                // Older saved Wardens have no skin type metadata; initialize it from their template.
                configureSkin(marker, config);
            }
            BlockPos emergencePos = marker.clockworkBosses$getEmergencePos();
            ClockworkBossState state = createState(warden, name, template, config, marker.clockworkBosses$isDormant(), emergencePos);
            if (state.dormant()) {
                configureDormantWarden(warden);
            } else {
                // A saved emergence can retain invulnerability while transient
                // runtime state is gone. Rejoin the native emergence if present;
                // otherwise finish its lock immediately instead of leaving a boss stuck.
                state.setEmerging(marker.clockworkBosses$isEmerging() || warden.getPose() == Pose.EMERGING
                        || warden.getBrain().hasMemoryValue(MemoryModuleType.IS_EMERGING));
                if (state.emerging()
                        && !warden.getBrain().hasMemoryValue(MemoryModuleType.IS_EMERGING)) {
                    finishEmergence(state, warden);
                }
            }
            ACTIVE.put(uuid, state);
            ensureWatcher();
            return state;
        } catch (Exception e) {
            if (FAILED_REHYDRATION.add(uuid)) {
                LOGGER.error("Could not rehydrate Clockwork Boss '{}' from template '{}'.", name, template, e);
            }
            return null;
        }
    }

    /** Pairs saved identity/emergence position with a vanilla boss event and one compiled settings snapshot. */
    private static ClockworkBossState createState(
            Warden warden,
            String name,
            String templateName,
            JsonObject config,
            boolean dormant,
            BlockPos emergencePos) {
        JsonObject bossConfig = config.has("boss_bar") && config.get("boss_bar").isJsonObject()
                ? config.getAsJsonObject("boss_bar")
                : new JsonObject();

        BossEvent.BossBarColor color = parseColor(string(bossConfig, "color", "PURPLE"));
        BossEvent.BossBarOverlay overlay = parseOverlay(string(bossConfig, "overlay", "NOTCHED_20"));

        ServerBossEvent bossBar = new ServerBossEvent(
                UUID.randomUUID(),
                Component.literal(name),
                color,
                overlay
        );

        bossBar.setProgress(
                warden.getHealth() / Math.max(1.0F, warden.getMaxHealth())
        );

        ClockworkBossSoundNetwork.prepareTemplate(templateName, config);
        return new ClockworkBossState(
                warden.getUUID(),
                name,
                templateName,
                warden.level().dimension(),
                emergencePos,
                dormant,
                config,
                bossBar
        );
    }

    /** Copies validated skin settings into entity metadata that is persisted and synced to clients. */
    private static void configureSkin(ClockworkBossMarker marker, JsonObject config) {
        marker.clockworkBosses$setEyes(ClockworkBossEyes.read(config).packed());
        marker.clockworkBosses$setBrows(ClockworkBossBrows.read(config).packed());
        marker.clockworkBosses$setPupils(ClockworkBossPupils.read(config).packed());
        JsonObject skin = object(config, "skin");
        String type = string(skin, "type", "").trim().toLowerCase(java.util.Locale.ROOT);

        // Preserve older templates that only supplied a player name without a type field.
        if (type.isEmpty()) {
            type = string(skin, "name", "").trim().isEmpty() ? "default" : "player";
        }

        if (type.equalsIgnoreCase("default")) {
            marker.clockworkBosses$setSkinType("default");
            marker.clockworkBosses$setSkinPlayerName("");
            marker.clockworkBosses$setCustomSkinFile("");
            return;
        }

        if (type.equals("player")) {
            String playerName = string(skin, "name", "").trim();
            if (playerName.isEmpty()) {
                marker.clockworkBosses$setSkinType("default");
                marker.clockworkBosses$setSkinPlayerName("");
                marker.clockworkBosses$setCustomSkinFile("");
            } else if (playerName.matches("[A-Za-z0-9_]{1,16}")) {
                marker.clockworkBosses$setSkinType("player");
                marker.clockworkBosses$setSkinPlayerName(playerName);
                marker.clockworkBosses$setCustomSkinFile("");
            } else {
                LOGGER.warn("Invalid Clockwork Boss skin player name '{}'; expected a Minecraft username.", playerName);
                marker.clockworkBosses$setSkinType("default");
                marker.clockworkBosses$setSkinPlayerName("");
                marker.clockworkBosses$setCustomSkinFile("");
            }
            return;
        }

        if (type.equals("custom")) {
            String fileName = string(skin, "file", "").trim();
            // The shared packet/file validator keeps saved state, server file
            // lookup and client acceptance on the same lowercase naming policy.
            if (CustomWardenSkinPayload.isValidFileName(fileName)) {
                marker.clockworkBosses$setSkinType("custom");
                marker.clockworkBosses$setSkinPlayerName("");
                marker.clockworkBosses$setCustomSkinFile(fileName);
            } else {
                LOGGER.warn("Invalid Clockwork Boss custom skin filename '{}'; expected a lowercase .png filename without spaces or paths.", fileName);
                marker.clockworkBosses$setSkinType("default");
                marker.clockworkBosses$setSkinPlayerName("");
                marker.clockworkBosses$setCustomSkinFile("");
            }
            return;
        }

        if (!type.isBlank()) {
            LOGGER.warn("Unsupported Clockwork Boss skin type '{}'; using the vanilla Warden texture.", type);
        }
        marker.clockworkBosses$setSkinType("default");
        marker.clockworkBosses$setSkinPlayerName("");
        marker.clockworkBosses$setCustomSkinFile("");
    }

    /** Checks the configured activation edge for one dormant Warden. */
    private static void checkActivation(ClockworkBossState state, Warden warden) {
        ServerLevel level = (ServerLevel) warden.level();
        ClockworkBossState.Areas areas = state.areas();
        String type = areas.activationType();

        if (areas.activationDimension() != null
                && !areas.activationDimension().equals(level.dimension())) {
            state.setActivationBaselineInitialized(false);
            state.setActivationInsidePlayerIds(Set.of());
            state.setPreviousRedstonePowered(false);
            return;
        }

        if ("player_enter_area".equals(type) || "player_enter_radius".equals(type)) {
            Set<UUID> currentInside = playersInsideActivationArea(level, state);
            if (!state.activationBaselineInitialized()) {
                state.setActivationInsidePlayerIds(currentInside);
                state.setActivationBaselineInitialized(true);
                return;
            }

            boolean entered = currentInside.stream()
                    .anyMatch(uuid -> !state.activationInsidePlayerIds().contains(uuid));
            state.setActivationInsidePlayerIds(currentInside);

            if (entered) {
                activateWarden(state, warden);
            }
        } else if ("redstone".equals(type)) {
            BlockPos triggerPos = areas.redstonePosition();
            if (triggerPos == null) return;

            // Querying an unloaded trigger must not load chunks just for a dormant boss.
            if (!level.getChunkSource().hasChunk(triggerPos.getX() >> 4, triggerPos.getZ() >> 4)) return;
            boolean powered = level.hasNeighborSignal(triggerPos);
            if (!state.activationBaselineInitialized()) {
                state.setPreviousRedstonePowered(powered);
                state.setActivationBaselineInitialized(true);
                return;
            }

            boolean activated = powered && !state.previousRedstonePowered();
            state.setPreviousRedstonePowered(powered);
            if (activated) {
                activateWarden(state, warden);
            }
        }
    }

    /** Initializes an activation trigger without treating the current state as a new edge. */
    private static void initializeActivationBaseline(ClockworkBossState state, Warden warden) {
        checkActivation(state, warden);
    }

    /** Native spatial query plus a squared-distance filter handles box and emergence-centered sphere triggers. */
    private static Set<UUID> playersInsideActivationArea(ServerLevel level, ClockworkBossState state) {
        boolean radiusMode = "player_enter_radius".equals(state.areas().activationType());
        AABB area = radiusMode ? state.activationRadiusBox() : state.areas().activationBox();
        if (area == null) return Set.of();
        Set<UUID> result = new HashSet<>();
        for (ServerPlayer player : level.getEntitiesOfClass(
                ServerPlayer.class,
                area,
                player -> player.isAlive() && !player.isSpectator()
                        && (!radiusMode || player.position().distanceToSqr(state.emergenceCenter())
                        <= state.areas().activationRadius() * state.areas().activationRadius()))) {
            result.add(player.getUUID());
        }
        return result;
    }

    /** Moves a dormant Warden to its emergence point and starts the vanilla emergence activity. */
    private static void activateWarden(ClockworkBossState state, Warden warden) {
        if (!state.dormant()) return;

        ServerLevel level = (ServerLevel) warden.level();
        BlockPos pos = state.emergencePos();

        warden.snapTo(
                pos.getX() + 0.5,
                pos.getY(),
                pos.getZ() + 0.5,
                warden.getYRot(),
                warden.getXRot()
        );

        ClockworkBossMarker marker = (ClockworkBossMarker) (Object) warden;
        marker.clockworkBosses$setDormant(false);
        marker.clockworkBosses$setEmerging(true);
        warden.setInvisible(false);
        warden.setSilent(false);
        warden.setNoGravity(false);
        warden.setNoAi(false);
        warden.setPermanentlyInvulnerable(true);
        warden.setPose(Pose.EMERGING);

        int duration = Math.max(1, WardenAi.EMERGE_DURATION);
        warden.getBrain().setMemoryWithExpiry(
                MemoryModuleType.IS_EMERGING,
                Unit.INSTANCE,
                duration
        );
        WardenAi.updateActivity(warden.getBrain());

        state.setDormant(false);
        state.setEmerging(true);
        state.setActivationBaselineInitialized(false);
        state.setActivationInsidePlayerIds(Set.of());
        state.setPreviousRedstonePowered(false);
        state.clearDarknessSchedule();

        // Notify activation before membership. A listener can remove this boss
        // synchronously; then no support grants or sounds should follow it.
        clockwork.bosses.api.BossEncounterEvents.STARTED.invoker().onEvent(encounterSnapshot(warden, state));
        if (ACTIVE.get(state.uuid()) != state || warden.isRemoved() || !warden.isAlive()) return;
        // The boss bar is now eligible to appear inside its selected visibility box.
        updateBossBar(level.getServer(), state, warden);
        // Every trigger (command, area entry or redstone) converges here.
        // Play once at the visible emergence point, after clearing dormancy.
        if (ACTIVE.get(state.uuid()) == state && !warden.isRemoved() && warden.isAlive())
            playConfiguredSound(warden, state.config(), "spawn");
    }

    /** Ends the emergence lock and hands control back to the normal Warden AI. */
    private static void finishEmergence(ClockworkBossState state, Warden warden) {
        warden.setPose(Pose.STANDING);
        warden.getBrain().eraseMemory(MemoryModuleType.IS_EMERGING);
        WardenAi.updateActivity(warden.getBrain());
        warden.setPermanentlyInvulnerable(false);
        state.setEmerging(false);
        ((ClockworkBossMarker) (Object) warden).clockworkBosses$setEmerging(false);
    }

    /** Reapplies all dormant-only entity flags after a server restart. */
    private static void configureDormantWarden(Warden warden) {
        warden.setNoAi(true);
        warden.setNoGravity(true);
        warden.setPermanentlyInvulnerable(true);
        warden.setInvisible(true);
        warden.setSilent(true);
        warden.setPersistenceRequired();
    }

    /** Compile all saved regions once; native keys compare without rebuilding strings or loading worlds. */
    static ClockworkBossState.Areas readAreas(JsonObject config) {
        JsonObject activation = object(config, "activation");
        String type = string(activation, "type", "");
        String dimension = string(activation, "dimension", "");
        ResourceKey<Level> activationDimension = readDimension(dimension);
        // An omitted dimension intentionally accepts the boss's current world.
        // A malformed specified dimension must disable activation, not broaden it.
        if (!dimension.isEmpty() && activationDimension == null) type = "";
        JsonObject visibility = object(object(config, "boss_bar"), "visibility_area");
        String visibilityDimensionName = string(visibility, "dimension", "");
        ResourceKey<Level> visibilityDimension = readDimension(visibilityDimensionName);
        boolean validVisibilityDimension = visibilityDimensionName.isEmpty() || visibilityDimension != null;
        return new ClockworkBossState.Areas(type, activationDimension,
                readBox(activation), clamp(number(activation, "radius", 32), 1, 256),
                readBlockPos(activation, "position", null), visibilityDimension,
                validVisibilityDimension ? readBox(visibility) : null,
                validVisibilityDimension && "radius".equals(string(visibility, "shape", "box")),
                clamp(number(visibility, "radius", 32), 1, 256),
                !visibilityDimensionName.isEmpty() || visibility.has("min") || visibility.has("max")
                        || "radius".equals(string(visibility, "shape", "box")));
    }

    /** The radius follows the boss; fixed boxes retain their saved dimension/bounds. Null means no visible region. */
    static AABB visibilityQuery(ClockworkBossState state, Warden warden) {
        var areas = state.areas();
        if (areas.visibilityDimension() != null && !areas.visibilityDimension().equals(warden.level().dimension())) return null;
        if (areas.visibilityUsesRadius()) return warden.getBoundingBox()
                .move(arenaAnchor(warden).subtract(warden.position())).inflate(areas.visibilityRadius());
        return areas.visibilityDimension() == null ? null : areas.visibilityBox();
    }

    /** Filter the broad query box to a real 3D sphere, using squared distances and no square roots. */
    static boolean insideVisibilityRadius(ClockworkBossState state, Warden warden, ServerPlayer player) {
        double radius = state.areas().visibilityRadius();
        return !state.areas().visibilityUsesRadius() || player.position().distanceToSqr(arenaAnchor(warden)) <= radius * radius;
    }

    /** Burial moves the entity down temporarily. Anchor membership at its saved
     * surface so that the vertical move cannot remove buffs, music or eligible
     * targets from a radius arena. Emergence commits the new surface to this journal. */
    private static net.minecraft.world.phys.Vec3 arenaAnchor(Warden boss) {
        var checkpoint = ((ClockworkBossMarker) boss).clockworkBosses$getBurrowCheckpoint();
        return checkpoint == null ? boss.position() : checkpoint.origin();
    }

    /** Input-time guard reuses compiled geometry and loaded entity lookup. It
     * loads no chunks and makes no spatial query or new recurring check. */
    public static boolean isPlayerInArena(UUID bossId, ServerPlayer player) {
        var state = ACTIVE.get(bossId);
        if (state == null || state.dormant() || !player.isAlive() || player.isSpectator() || player.hasDisconnected()) return false;
        Warden boss = findWardenByState(player.level().getServer(), state);
        if (boss == null || !boss.isAlive() || boss.isRemoved() || boss.level() != player.level()) return false;
        AABB area = visibilityQuery(state, boss);
        return area != null && area.intersects(player.getBoundingBox()) && insideVisibilityRadius(state, boss, player);
    }

    /** Fresh event-time geometry makes PvP entry/exit immediate; the ten-tick
     * viewer snapshot is deliberately not an authority for damage. Read only
     * enabled, live encounters; no team mutation, player scan or new watcher. */
    static boolean hasPvpProtection(ServerPlayer player) {
        for (var state : ACTIVE.values()) {
            if (!state.encounterEnded() && state.pvpProtection().enabled()
                    && state.dimension().equals(player.level().dimension()) && isPlayerInArena(state.uuid(), player)) return true;
        }
        return false;
    }
    /** All support uses the same arena diff. Recheck after native buff/health
     * callbacks, because another mod can synchronously delete the encounter. */
    private static void enterPlayerSupport(ClockworkBossState state, Warden boss, ServerPlayer player) {
        if (ClockworkBossDiagnostics.enabled()) ClockworkBossDiagnostics.event(boss, player, "arena_support_enter");
        ClockworkBossAreaEffects.enter(state.uuid(), player, state.areaEffects());
        if (ACTIVE.get(state.uuid()) != state || !isPlayerInArena(state.uuid(), player)) return;
        var support = state.playerSupport();
        boolean heal = support.health().enabled() && support.health().healOnFirstEntry()
                && ((ClockworkBossMarker) boss).clockworkBosses$claimInitialHeal(player.getUUID());
        ClockworkBossPlayerHealth.enter(state.uuid(), player, support.health(), heal);
        if (ACTIVE.get(state.uuid()) == state && isPlayerInArena(state.uuid(), player))
            ClockworkBossFightPotion.enter(state.uuid(), player, support.potion());
    }
    private static void releasePlayerSupport(UUID boss) {
        ClockworkBossAreaEffects.releaseBoss(boss);
        ClockworkBossPlayerHealth.releaseBoss(boss);
        ClockworkBossFightPotion.releaseBoss(boss);
    }
    /** Used only at native behavior boundaries; no state is created for other mobs. */
    public static boolean isRecovering(Warden boss) {
        if (!isClockworkBoss(boss)) return false;
        var state = stateFor(boss);
        return state != null && !state.dormant() && !state.emerging()
                && (state.actionsStopping() || (state.actionRuntime() != null
                ? state.actionRuntime().pausesNativeBehaviors() : state.recoverySubphase()));
    }

    /** Parsing a registry key does not resolve/load its dimension; unknown worlds simply cannot match. */
    private static ResourceKey<Level> readDimension(String name) {
        if (name.isEmpty()) return null;
        Identifier id = Identifier.tryParse(name);
        return id == null ? null : ResourceKey.create(Registries.DIMENSION, id);
    }

    /** Builds an AABB from a saved min/max box, or returns null when it is incomplete. */
    private static AABB readBox(JsonObject box) {
        if (!"box".equals(string(box, "shape", "box"))) return null;
        JsonObject min = object(box, "min");
        JsonObject max = object(box, "max");
        if (!hasBlockCoordinates(min) || !hasBlockCoordinates(max)) {
            return null;
        }

        int minX = Math.min(blockCoordinate(min, "x", 0), blockCoordinate(max, "x", 0));
        int minY = Math.min(blockCoordinate(min, "y", 0), blockCoordinate(max, "y", 0));
        int minZ = Math.min(blockCoordinate(min, "z", 0), blockCoordinate(max, "z", 0));
        int maxX = Math.max(blockCoordinate(min, "x", 0), blockCoordinate(max, "x", 0)) + 1;
        int maxY = Math.max(blockCoordinate(min, "y", 0), blockCoordinate(max, "y", 0)) + 1;
        int maxZ = Math.max(blockCoordinate(min, "z", 0), blockCoordinate(max, "z", 0)) + 1;
        if (!isAreaSizeAllowed(
                new BlockPos(minX, minY, minZ),
                new BlockPos(maxX - 1, maxY - 1, maxZ - 1))) {
            return null;
        }

        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /** Reads a block position object such as {"x":1,"y":2,"z":3}. */
    private static BlockPos readBlockPos(JsonObject parent, String key, BlockPos fallback) {
        JsonObject object = parent.has(key) && parent.get(key).isJsonObject()
                ? parent.getAsJsonObject(key) : null;
        if (object == null || !hasBlockCoordinates(object)) {
            return fallback;
        }
        return new BlockPos(
                blockCoordinate(object, "x", 0),
                blockCoordinate(object, "y", 0),
                blockCoordinate(object, "z", 0)
        );
    }

    /** Copies configured vanilla bar color/overlay into the owned event; unknown values use defaults. */
    private static void ensureBossBarConfiguration(ClockworkBossState state) {
        JsonObject config = state.config();
        JsonObject bossConfig = object(config, "boss_bar");

        state.bossBar().setColor(parseColor(string(bossConfig, "color", "PURPLE")));
        state.bossBar().setOverlay(parseOverlay(string(bossConfig, "overlay", "NOTCHED_20")));
    }

    /** Shares one existing spatial query and entry/exit diff between the bar and area buffs. */
    private static void updateBossBar(
            MinecraftServer server, ClockworkBossState state, Warden warden) {
        if (state.dormant() || !warden.isAlive()) {
            ClockworkBossMusic.end(state.uuid());
            if (!state.bossBarPlayerIds().isEmpty()) releasePlayerSupport(state.uuid());
            for (ServerPlayer player : Set.copyOf(state.bossBar().getPlayers())) {
                state.bossBar().removePlayer(player);
            }
            state.setBossBarPlayerIds(Set.of());
            return;
        }

        float progress = Math.max(0.0F, Math.min(1.0F,
                warden.getHealth() / Math.max(1.0F, warden.getMaxHealth())));

        if (Float.compare(state.bossBar().getProgress(), progress) != 0) {
            state.bossBar().setProgress(progress);
        }

        Set<UUID> desired = new HashSet<>();
        ServerLevel level = (ServerLevel) warden.level();
        // Box and moving-radius modes share this existing watcher query and viewer diff.
        AABB area = visibilityQuery(state, warden);
        if (area != null) {
            // Use Minecraft's spatial entity query instead of walking every player
            // on the server. This keeps the cost proportional to players in the arena.
            for (ServerPlayer player : level.getEntitiesOfClass(
                    ServerPlayer.class,
                    area,
                    player -> player.isAlive() && !player.isSpectator()
                            && insideVisibilityRadius(state, warden, player))) {
                desired.add(player.getUUID());
            }
        }

        Set<UUID> current = state.bossBarPlayerIds();
        // Music shares this scan even when membership is unchanged, so /boss reload can refresh it.
        ClockworkBossMusic.sync(state, level, desired);
        if (desired.equals(current)) {
            return;
        }

        // Remove viewers directly from the boss bar's own player set. This also
        // cleans up disconnected ServerPlayer references even when the player
        // is no longer present in PlayerList.
        for (ServerPlayer player : Set.copyOf(state.bossBar().getPlayers())) {
            if (!desired.contains(player.getUUID())) {
                ClockworkBossAreaEffects.leave(state.uuid(), player.getUUID());
                ClockworkBossPlayerHealth.leave(state.uuid(), player.getUUID());
                ClockworkBossFightPotion.leave(state.uuid(), player.getUUID());
                state.bossBar().removePlayer(player);
            }
        }

        // Add only newly eligible viewers.
        for (UUID uuid : desired) {
            // A native effect callback may remove this encounter synchronously.
            // Never send a new bar/grant after its cleanup has already run.
            if (ACTIVE.get(state.uuid()) != state || warden.isRemoved()) {
                cleanupState(state);
                return;
            }
            if (!current.contains(uuid)) {
                ServerPlayer player = server.getPlayerList().getPlayer(uuid);
                if (player != null && player.level() == level) {
                    state.bossBar().addPlayer(player);
                    enterPlayerSupport(state, warden, player);
                }
            }
        }

        if (ACTIVE.get(state.uuid()) != state || warden.isRemoved()) return;
        state.setBossBarPlayerIds(desired);
        var snapshot = encounterSnapshot(warden, state);
        for (var uuid : current) if (!desired.contains(uuid))
            clockwork.bosses.api.BossEncounterEvents.PLAYER_LEFT.invoker().onEvent(snapshot, uuid);
        for (var uuid : desired) {
            if (ACTIVE.get(state.uuid()) != state || warden.isRemoved()) return;
            if (!current.contains(uuid)) clockwork.bosses.api.BossEncounterEvents.PLAYER_JOINED.invoker().onEvent(snapshot, uuid);
        }
    }

    /**
     * Sets native attribute bases on creation/reload; only creation refills health, while reload preserves
     * current health.
     */
    private static void applyBaseAttributes(Warden warden, JsonObject config, boolean refillHealth) {
        double oldMaxHealth = Math.max(1.0, warden.getMaxHealth());
        double oldHealth = Math.max(0.0, warden.getHealth());
        double healthPercent = oldHealth / oldMaxHealth;

        AttributeMap attributes = warden.getAttributes();

        double maxHealth = clamp(number(config, "health", 500.0), 1.0, MAX_CONFIGURED_HEALTH);
        set(attributes, Attributes.MAX_HEALTH, maxHealth);

        if (refillHealth) {
            warden.setHealth(warden.getMaxHealth());
        } else {
            warden.setHealth((float) Math.min(warden.getMaxHealth(),
                    Math.max(0.0, healthPercent * warden.getMaxHealth())));
        }
    }

    /**
     * Reuses compiled cutoffs and exits for an unchanged phase; a real transition updates attributes, bar
     * title and one-time effects.
     */
    private static void applyPhaseIfChanged(ClockworkBossState state, Warden warden) {
        ClockworkBossPhase phase = phaseFor(warden, state);
        if (phase.equals(state.currentPhase())) {
            return;
        }

        ClockworkBossPhase previousPhase = state.currentPhase();

        // Release an old wind-up before the phase setter clears its UUID. This
        // keeps cancellation observable and clears its visual at the same event.
        ClockworkBossMelee.cancel(warden, state);
        if (!state.setCurrentPhase(phase) || ACTIVE.get(state.uuid()) != state || !warden.isAlive() || warden.isRemoved()) return;
        syncFace(warden, state); // The new phase has discarded old expression overlays.
        applyPhaseAttributes(warden, state, phase);
        state.clearDarknessSchedule();
        if (ClockworkBossDiagnostics.enabled()) ClockworkBossDiagnostics.event(warden, warden.getTarget(), "health_phase_changed", "boss_hp", warden.getHealth(), "boss_max_hp", warden.getMaxHealth());
        if (state.dormant()) positionDormantWarden(state, warden);
        state.bossBar().setName(
                Component.literal(state.name() + " — " + phase.name())
        );

        // The first phase assignment establishes the initial visual state without
        // producing a phase-change flash or particle burst.
        applyEffectVisuals(warden, state, phase, previousPhase != null);
        if (previousPhase != null) {
            playPhaseChangeSound(warden, state.config(), phase);
            clockwork.bosses.api.BossEncounterEvents.PHASE_CHANGED.invoker().onEvent(
                    encounterSnapshot(warden, state), previousPhase.key(), phase.key());
        }
        // Start the next action after phase identity/visuals are committed and
        // listeners have observed them. Reentrant callbacks may remove/replace it.
        if (ACTIVE.get(state.uuid()) == state && phase.equals(state.currentPhase()) && !warden.isRemoved())
            applySubphaseAttributes(warden, state);
    }

    /** Plays the configured death sound once, before Minecraft removes the entity. */
    public static void playDeathSound(Warden warden) {
        if (!isClockworkBoss(warden)) {
            return;
        }
        ClockworkBossState state = ACTIVE.get(warden.getUUID());
        if (state != null) {
            playConfiguredSound(warden, state.config(), "death");
        }
    }

    /** Reads bounded event settings and delegates OGG delivery to nearby clients. */
    private static void playConfiguredSound(Warden warden, JsonObject config, String event) {
        playConfiguredSound(warden, object(config, "sounds", event));
    }

    /**
     * Phase overrides choose the entering phase's file; only null/missing values
     * inherit. An explicit filename is authoritative even if the file is missing,
     * and an empty filename intentionally mutes this transition.
     */
    private static void playPhaseChangeSound(
            Warden warden,
            JsonObject config,
            ClockworkBossPhase phase
    ) {
        JsonObject settings = object(config, "sounds", "phase_change").deepCopy();
        JsonObject overrides = object(
                config, "phases", phase.key(), "sounds", "phase_change");
        for (String key : List.of("file", "volume", "pitch", "range")) {
            if (overrides.has(key) && !overrides.get(key).isJsonNull()) {
                settings.add(key, overrides.get(key).deepCopy());
            }
        }
        playConfiguredSound(warden, settings);
    }

    /** Reads bounded settings and delegates OGG delivery to nearby clients. */
    private static void playConfiguredSound(Warden warden, JsonObject settings) {
        String fileName = string(settings, "file", "").trim();
        if (fileName.isEmpty()) {
            return;
        }

        double volume = clamp(number(settings, "volume", 1.0), 0.0, 4.0);
        if (volume <= 0.0) {
            return;
        }
        double pitch = clamp(number(settings, "pitch", 1.0), 0.5, 2.0);
        float range = soundDistance(warden, number(settings, "range", ClockworkBossSoundDistance.LEGACY_FALLBACK));
        ClockworkBossSoundNetwork.play(warden, fileName, (float) volume, (float) pitch, range);
    }

    /** Events, custom/action roars and voices resolve Auto before asynchronous media work.
     * An arena in another dimension cannot define this emitter's audience. Manual values survive. */
    private static float soundDistance(Warden warden, double configured) {
        ClockworkBossState state = stateFor(warden);
        var areas = state == null ? null : state.areas();
        if (areas != null && areas.visibilityDimension() != null
                && !areas.visibilityDimension().equals(warden.level().dimension())) areas = null;
        return ClockworkBossSoundDistance.resolve(configured, areas);
    }

    /**
     * Resolves the configured emissive-effect color and the optional phase-change
     * presentation. Visual state is pushed through vanilla entity metadata, so the
     * client receives one small update only when a phase actually changes.
     */
    private static void applyEffectVisuals(
            Warden warden,
            ClockworkBossState state,
            ClockworkBossPhase phase,
            boolean phaseChanged) {

        ClockworkBossMarker marker = (ClockworkBossMarker) (Object) warden;
        marker.clockworkBosses$setMobSoundVolume(state.settings().mobSoundVolume());
        int color = effectColor(state.config(), phase);
        marker.clockworkBosses$setEffectColor(color);

        if (!phaseChanged) {
            marker.clockworkBosses$setPhaseChangeTick(Integer.MIN_VALUE);
            return;
        }

        JsonObject phaseChange = phaseChangeConfig(state.config(), phase);
        boolean flashEnabled = bool(phaseChange, "flash", true);
        marker.clockworkBosses$setPhaseChangeTick(
                flashEnabled ? warden.tickCount : Integer.MIN_VALUE
        );

        JsonObject particles = object(phaseChange, "particle_explosion");
        if (!bool(particles, "enabled", true)) {
            return;
        }

        // A short burst of many small particles is highly visible while remaining
        // bounded to one event and at most 128 particles for each phase change.
        int count = Math.max(1, Math.min(128, integer(particles, "count", 128)));
        double spread = Math.max(0.0, Math.min(4.0, number(particles, "spread", 0.8)));
        double speed = Math.max(0.0, Math.min(2.0, number(particles, "speed", 0.12)));
        float size = (float) clamp(number(particles, "size", 0.65), 0.1, 4.0);
        ServerLevel level = (ServerLevel) warden.level();

        level.sendParticles(
                new DustParticleOptions(color, size),
                warden.getX(),
                warden.getY() + 1.0,
                warden.getZ(),
                count,
                spread,
                spread,
                spread,
                speed
        );
    }

    /** Gets the effective emissive color for the current phase. */
    private static int effectColor(JsonObject config, ClockworkBossPhase phase) {
        JsonObject base = object(config, "warden_effects");
        JsonObject phaseEffects = object(config, "phases", phase.key(), "warden_effects");
        return parseHexColor(
                string(phaseEffects, "color", string(base, "color", "#55FFFF"))
        );
    }

    /** Gets phase-change settings with phase overrides falling back to base settings. */
    private static JsonObject phaseChangeConfig(JsonObject config, ClockworkBossPhase phase) {
        JsonObject base = object(object(config, "warden_effects"), "phase_change");
        JsonObject phaseConfig = object(config, "phases", phase.key(), "warden_effects", "phase_change");
        JsonObject merged = base.deepCopy();

        if (phaseConfig.has("flash") && !phaseConfig.get("flash").isJsonNull()) {
            merged.add("flash", phaseConfig.get("flash").deepCopy());
        }
        if (phaseConfig.has("particle_explosion")
                && phaseConfig.get("particle_explosion").isJsonObject()) {
            // Merge individual particle fields so a phase can change only its
            // enabled flag, count, spread, speed, or size and inherit the rest.
            JsonObject mergedParticles = object(merged, "particle_explosion").deepCopy();
            phaseConfig.getAsJsonObject("particle_explosion").entrySet().forEach(entry -> {
                if (!entry.getValue().isJsonNull()) mergedParticles.add(entry.getKey(), entry.getValue().deepCopy());
            });
            merged.add("particle_explosion", mergedParticles);
        }
        return merged;
    }

    /** Parses #RRGGBB or RRGGBB into an opaque ARGB color. */
    private static int parseHexColor(String value) {
        if (value == null) return 0xFFFFFFFF;
        String hex = value.trim();
        if (hex.startsWith("#")) hex = hex.substring(1);
        try {
            if (hex.matches("[0-9a-fA-F]{6}")) {
                return 0xFF000000 | Integer.parseInt(hex, 16);
            }
            if (hex.matches("[0-9a-fA-F]{8}")) {
                return (int) Long.parseLong(hex, 16);
            }
        } catch (NumberFormatException ignored) {
            // Fall through to the safe default below.
        }
        return 0xFFFFFFFF;
    }


    /**
     * Updates native attributes only at phase assignment/reload; native SCALE notification refreshes
     * entity dimensions.
     */
    private static void applyPhaseAttributes(
            Warden warden, ClockworkBossState state, ClockworkBossPhase phase) {
        JsonObject config = state.config();
        AttributeMap attributes = warden.getAttributes();

        // General Warden attributes. Health deliberately does NOT change by phase.
        set(attributes, Attributes.MOVEMENT_SPEED,
                clamp(phaseValue(config, phase, "movement_speed",
                        number(config, "movement_speed", 0.30)), 0.0, 1.0));
        set(attributes, Attributes.KNOCKBACK_RESISTANCE,
                clamp(phaseValue(config, phase, "knockback_resistance",
                        number(config, "knockback_resistance", 1.0)), 0.0, 1.0));
        double followRange = clamp(phaseValue(config, phase, "follow_range",
                number(config, "follow_range", 0)), 0.0, MAX_CONFIGURED_FOLLOW_RANGE);
        set(attributes, Attributes.FOLLOW_RANGE, followRange > 0 ? followRange : automaticFollowRange(state));
        set(attributes, Attributes.SCALE,
                clamp(phaseValue(config, phase, "scale",
                                number(config, "scale", 1.0)),
                        MIN_CONFIGURED_SCALE, MAX_CONFIGURED_SCALE));
        // Native 26.3 LivingEntity.onAttributeUpdated(SCALE) refreshes dimensions
        // when dirty attributes are processed. Do not duplicate that native work.

        // Melee damage/knockback are attack-specific, but they are implemented
        // by Minecraft's ATTACK_DAMAGE/ATTACK_KNOCKBACK attributes.
        JsonObject melee = object(config, "attacks", "melee");
        JsonObject phaseMelee = phaseAttackObject(config, phase, "melee");

        set(attributes, Attributes.ATTACK_DAMAGE,
                clamp(number(phaseMelee, "damage", number(melee, "damage", 30.0)),
                        0.0, MAX_CONFIGURED_DAMAGE));
        set(attributes, Attributes.ATTACK_KNOCKBACK,
                clamp(number(phaseMelee, "knockback", number(melee, "knockback", 1.5)),
                        0.0, MAX_CONFIGURED_KNOCKBACK));
    }

    /** Resolve automatic (0) follow range only when applying attributes, using
     * already compiled arena geometry. A moving circle is boss-centered. The
     * box's full 3D diagonal covers opposite corners while the boss is inside.
     * Explicit positive ranges still override this. No region uses 24 blocks;
     * the existing 256-block cap bounds native detection and candidate queries. */
    private static double automaticFollowRange(ClockworkBossState state) {
        var areas = state.areas();
        if (areas.visibilityUsesRadius()) return areas.visibilityRadius();
        AABB box = areas.visibilityBox();
        if (box == null) return 24;
        double x = box.maxX - box.minX, y = box.maxY - box.minY, z = box.maxZ - box.minZ;
        return Math.clamp(Math.sqrt(x * x + y * y + z * z), 1, MAX_CONFIGURED_FOLLOW_RANGE);
    }

    /** Child transitions touch native combat attributes only. They do not heal,
     * change scale/bar identity/aura/glow, emit phase effects or reset cooldowns. */
    private static void applySubphaseAttributes(Warden boss, ClockworkBossState state) {
        state.initializeSubphase(boss.getRandom());
        if (!state.dormant() && !state.emerging()) ClockworkBossActionRuntime.ensure(boss, state);
        if (ACTIVE.get(state.uuid()) != state || state.encounterEnded() || boss.isRemoved()) return;
        var step = state.subphase();
        if (step == null) return;
        if (ClockworkBossDiagnostics.enabled()) ClockworkBossDiagnostics.event(boss, boss.getTarget(), "subphase_enter",
                "type", step.type().name(), "duration_ticks", step.durationTicks(), "attack_budget", step.attacks());
    }

    /** The controller commits selected child attributes before calling start(),
     * so core and addon actions observe the same configured native mob values. */
    static void applySubphaseAttributeValues(Warden boss, ClockworkBossState state) {
        if (ACTIVE.get(state.uuid()) != state || state.encounterEnded() || boss.isRemoved()) return;
        var step = state.subphase();
        if (step == null) return;
        // An interlude keeps the outgoing child's attributes until the native
        // roar finishes. Cancel unfinished beams before waiting for recovery.
        if (state.roarInterlude()) { ClockworkBossSonicBoom.cancelCharge(boss, state); return; }
        if (state.recoverySubphase()) return; // Registered recovery owns the pause/deadline.
        var values = step.attributes(); var attributes = boss.getAttributes();
        set(attributes, Attributes.MOVEMENT_SPEED, values.speed());
        set(attributes, Attributes.KNOCKBACK_RESISTANCE, values.resistance());
        set(attributes, Attributes.FOLLOW_RANGE, values.followRange() > 0 ? values.followRange() : automaticFollowRange(state));
        set(attributes, Attributes.ATTACK_DAMAGE, values.damage());
        set(attributes, Attributes.ATTACK_KNOCKBACK, values.knockback());
    }

    /** Resolves one finite numeric phase override with its base value as fallback. */
    private static double phaseValue(
            JsonObject config, ClockworkBossPhase phase, String key, double fallback) {
        JsonObject phaseConfig = object(config, "phases", phase.key());
        return number(phaseConfig, key, fallback);
    }

    /** Reads the phase attack subsection used during attribute assignment, outside the repeating AI hot path. */
    private static JsonObject phaseAttackObject(
            JsonObject config, ClockworkBossPhase phase, String attackName) {
        return object(config, "phases", phase.key(), attackName);
    }

    /**
     * Sets a supported native attribute instance; missing attributes are ignored and vanilla enforces its
     * own range.
     */
    private static void set(AttributeMap map, Holder<Attribute> attribute, double value) {
        var instance = map.getInstance(attribute);
        if (instance != null) {
            instance.setBaseValue(value);
        }
    }

    /** Skips repeated active-entity spatial queries while preserving shorter configured intervals. */
    private static void applyDarknessIfDue(
            ClockworkBossState state,
            ServerLevel level,
            Warden warden,
            long currentTick) {
        if (currentTick < state.nextDarknessScanTick()) {
            return;
        }

        // Hidden, unactivated bosses use their independent aura. Activation
        // already clears deadlines; after emergence the current phase takes over.
        // This selection reuses the existing watcher/AI scan and adds no timer.
        ClockworkBossSettings.Darkness settings = state.settings().darkness(phaseFor(warden, state), state.dormant());
        if (!settings.enabled() || settings.maxRadius() <= 0.0) return;
        applyDarkness(state, level, warden, currentTick, settings);
        int configuredInterval = Math.min(settings.edgeInterval(), settings.centerInterval());
        state.setNextDarknessScanTick(
                currentTick + Math.min(WATCHER_INTERVAL_TICKS, configuredInterval));
    }

    /** Applies the aura using per-player game-time deadlines instead of modulo checks. */
    private static void applyDarkness(
            ClockworkBossState state,
            ServerLevel level,
            Warden warden,
            long currentTick,
            ClockworkBossSettings.Darkness settings) {
        if (!settings.enabled() || settings.maxRadius() <= 0.0) {
            state.clearDarknessSchedule();
            return;
        }

        double maxRadiusSqr = settings.maxRadius() * settings.maxRadius();
        Set<UUID> nearbyPlayers = state.beginDarknessPass();

        // Query only entity sections intersecting the aura instead of scanning the dimension.
        for (ServerPlayer player : level.getEntitiesOfClass(
                ServerPlayer.class,
                warden.getBoundingBox().inflate(settings.maxRadius()),
                player -> !player.isSpectator())) {
            double distanceSqr = player.distanceToSqr(warden);
            if (distanceSqr > maxRadiusSqr) continue;

            UUID playerId = player.getUUID();
            nearbyPlayers.add(playerId);
            if (currentTick < state.darknessNextTicks().getOrDefault(playerId, currentTick)) continue;

            double closeness = 1.0 - Math.min(1.0, Math.sqrt(distanceSqr) / settings.maxRadius());
            int interval = lerpInt(settings.edgeInterval(), settings.centerInterval(), closeness);
            int duration = lerpInt(settings.edgeDuration(), settings.centerDuration(), closeness);
            int amplifier = lerpInt(settings.edgeAmplifier(), settings.centerAmplifier(), closeness);

            MobEffectInstance current = player.getEffect(MobEffects.DARKNESS);
            boolean weaker = current == null || current.getAmplifier() < amplifier;
            boolean sameEffectNeedsRefresh = current != null
                    && current.getAmplifier() == amplifier
                    && current.getDuration() <= Math.max(1, duration / 2);
            if (weaker || sameEffectNeedsRefresh) {
                player.addEffect(new MobEffectInstance(
                        MobEffects.DARKNESS, duration, amplifier, false, false));
            }

            state.darknessNextTicks().put(playerId, currentTick + interval);
        }

        state.darknessNextTicks().keySet().retainAll(nearbyPlayers);
    }

    /** Linearly blends edge/center aura values and rounds to an integer game-tick duration or amplifier. */
    private static int lerpInt(int from, int to, double amount) {
        return (int) Math.round(from + (to - from) * amount);
    }

    /** Bounds a validated configuration number before it affects gameplay, particles or spatial queries. */
    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    /** Bounds a validated configuration number before it affects gameplay, particles or spatial queries. */
    private static double clamp(double value, double minimum, double maximum) {
        if (!Double.isFinite(value)) return minimum;
        return Math.max(minimum, Math.min(maximum, value));
    }

    /** Compares current health with precompiled cutoffs without reading JSON in the AI step. */
    private static ClockworkBossPhase phaseFor(Warden warden, ClockworkBossState state) {
        return ClockworkBossPhase.fromHealth(
                warden.getHealth(), warden.getMaxHealth(), state.settings().thresholds());
    }

    /**
     * Scans loaded native entity collections for saved markers, allowing removal when template restoration
     * failed.
     */
    private static Warden findLoadedWardenByName(MinecraftServer server, String name) {
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof Warden warden && isClockworkBoss(warden)) {
                    String markerName = ((ClockworkBossMarker) (Object) warden)
                            .clockworkBosses$getName();
                    if (markerName != null && markerName.equalsIgnoreCase(name)) {
                        return warden;
                    }
                }
            }
        }
        return null;
    }

    /** Uses known state first and then loaded saved markers, preventing duplicate live instance names. */
    private static Warden findByName(MinecraftServer server, String name) {
        ClockworkBossState state = findStateByName(server, name);
        if (state != null) return findWardenByState(server, state);

        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof Warden warden && isClockworkBoss(warden)) {
                    String markerName = ((ClockworkBossMarker) (Object) warden)
                            .clockworkBosses$getName();
                    if (markerName != null && markerName.equalsIgnoreCase(name)) {
                        registerIfNeeded(warden);
                        return warden;
                    }
                }
            }
        }
        return null;
    }

    /** Resolves a named state and can rehydrate its loaded entity; never loads an absent chunk. */
    private static ClockworkBossState findStateByName(MinecraftServer server, String name) {
        for (ClockworkBossState state : ACTIVE.values()) {
            if (state.name().equalsIgnoreCase(name)) {
                return state;
            }
        }

        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof Warden warden && isClockworkBoss(warden)) {
                    ClockworkBossMarker marker = (ClockworkBossMarker) (Object) warden;
                    String markerName = marker.clockworkBosses$getName();
                    if (markerName != null && markerName.equalsIgnoreCase(name)) {
                        ClockworkBossState state = registerIfNeeded(warden);
                        if (state != null) {
                            return state;
                        }
                    }
                }
            }
        }

        return null;
    }

    /**
     * Finds an Clockwork Boss using the dimension remembered by its runtime state.
     * The hot path performs exactly one dimension lookup and one UUID lookup.
     *
     * The Warden's own ClockworkBoss tick keeps this dimension value synchronized,
     * including after a cross-dimension teleport while the entity is ticking.
     */
    private static Warden findWardenByState(MinecraftServer server, ClockworkBossState state) {
        ServerLevel level = server.getLevel(state.dimension());
        if (level == null) {
            return null;
        }

        Entity entity = level.getEntity(state.uuid());
        if (entity instanceof Warden warden && isClockworkBoss(warden)) {
            return warden;
        }

        return null;
    }

    /** Uses only this template's saved spawn; an unset/missing spawn uses the command source. */
    private static SpawnLocation getConfiguredSpawnOrSourcePosition(
            CommandSourceStack source, JsonObject config) {
        JsonObject spawn = object(config, "spawn");
        if (bool(spawn, "set", false)) {
            if (!hasBlockCoordinates(spawn)) {
                throw new IllegalArgumentException("the saved spawn needs numeric x, y, and z coordinates");
            }
            String configuredDimension = string(spawn, "dimension", "");
            Identifier dimensionId = Identifier.tryParse(configuredDimension);
            if (dimensionId == null) {
                throw new IllegalArgumentException(
                        "the saved dimension id is invalid: " + configuredDimension);
            }

            ResourceKey<Level> dimensionKey = ResourceKey.create(Registries.DIMENSION, dimensionId);
            ServerLevel configuredLevel = source.getServer().getLevel(dimensionKey);
            if (configuredLevel == null) {
                throw new IllegalArgumentException(
                        "dimension " + configuredDimension + " is not available in this world");
            }

            BlockPos configuredPosition = new BlockPos(
                    blockCoordinate(spawn, "x", 0),
                    blockCoordinate(spawn, "y", 0),
                    blockCoordinate(spawn, "z", 0)
            );
            return new SpawnLocation(configuredLevel,
                    safeEmergencePosition(configuredLevel, configuredPosition, initialDormantDepth(config)));
        }

        ServerLevel sourceLevel = source.getLevel();
        return new SpawnLocation(
                sourceLevel,
                safeEmergencePosition(sourceLevel, BlockPos.containing(source.getPosition()), initialDormantDepth(config)));
    }

    /** Reserves the scaled holding depth while keeping the emergence point in build height. */
    private static BlockPos safeEmergencePosition(ServerLevel level, BlockPos position, int depth) {
        int minimumY = Math.min(level.getMinY() + depth, level.getMaxY() - 1);
        int maximumY = level.getMaxY() - 1;
        int y = clamp(position.getY(), minimumY, maximumY);
        return new BlockPos(position.getX(), y, position.getZ());
    }

    /** New bosses start at full health; the first configured phase's scale can exceed the base scale. */
    private static int initialDormantDepth(JsonObject config) {
        return dormantDepth(phaseValue(config, ClockworkBossPhase.Thresholds.read(config).phases().getFirst(), "scale", number(config, "scale", 1.0)));
    }

    /** Five blocks at normal size includes horn clearance; larger models need proportional clearance. */
    private static int dormantDepth(double scale) {
        return (int) Math.ceil(5.0 * Math.max(1.0, clamp(scale, MIN_CONFIGURED_SCALE, MAX_CONFIGURED_SCALE)));
    }

    /** Repositions only on phase assignment/reload, using the current native SCALE attribute. */
    private static void positionDormantWarden(ClockworkBossState state, Warden warden) {
        BlockPos pos = state.emergencePos();
        int depth = dormantDepth(warden.getAttributeValue(Attributes.SCALE));
        // Reload may increase scale beside bedrock. Keep the holding entity in
        // build height; activation still uses the saved surface position.
        int hiddenY = Math.max(warden.level().getMinY(), pos.getY() - depth);
        warden.snapTo(pos.getX() + 0.5, hiddenY, pos.getZ() + 0.5, warden.getYRot(), warden.getXRot());
    }

    /** Keeps the world and block position paired so a template can target another dimension. */
    private record SpawnLocation(ServerLevel level, BlockPos position) {}

    /**
     * Drops player references, aura/charge deadlines and activation membership owned by a disappearing
     * encounter.
     */
    private static void cleanupState(ClockworkBossState state) {
        endEncounter(null, state, clockwork.bosses.api.BossAction.StopReason.REMOVED);
        state.setActionRuntime(null);
        ClockworkBossMusic.end(state.uuid());
        // Native unload/removal already calls this cleanup. Keep only voice
        // catalogs still needed by another instance of the same template.
        boolean anotherInstance = ACTIVE.values().stream().anyMatch(other ->
                !other.uuid().equals(state.uuid()) && other.templateName().equals(state.templateName()));
        if (!anotherInstance) ClockworkBossSoundNetwork.releaseTemplate(state.templateName());
        releasePlayerSupport(state.uuid());
        state.bossBar().removeAllPlayers();
        state.clearDarknessSchedule();
        state.resetSonicBoomCharge();
        state.setPendingMelee(null);
        state.setActivationInsidePlayerIds(Set.of());
        state.setBossBarPlayerIds(Set.of());
    }

    /**
     * Releases all loaded encounter state at shutdown; persistent entities stay in
     * Minecraft's world saves.
     */
    private static void clearAll(MinecraftServer server) {
        for (Restoration pending : RESTORING.values()) pending.entity().setNoAi(pending.noAi());
        RESTORING.clear();
        ClockworkBossAnnouncements.clear();
        for (ClockworkBossState state : List.copyOf(ACTIVE.values())) {
            endEncounter(findWardenByState(server, state), state, clockwork.bosses.api.BossAction.StopReason.SERVER_STOPPED);
            cleanupState(state);
        }
        ACTIVE.clear();
        stopWatcherIfUnused();
        FAILED_REHYDRATION.clear();
    }

    /** Creates the runtime watcher when the first loaded Clockwork Boss is registered. */
    private static void ensureWatcher() {
        if (bossWatcher == null) {
            bossWatcher = new BossWatcher();
        }
    }

    /** Releases the watcher when the last loaded Clockwork Boss leaves runtime state. */
    private static void stopWatcherIfUnused() {
        if (ACTIVE.isEmpty()) {
            bossWatcher = null;
            ClockworkBossAnnouncements.clear();
        }
    }

    /**
     * Traverses configuration objects with safe empty fallbacks; this helper is used outside compiled AI
     * settings.
     */
    private static JsonObject object(JsonObject parent, String... path) {
        JsonObject current = parent;
        for (String key : path) {
            if (!current.has(key) || !current.get(key).isJsonObject()) {
                return new JsonObject();
            }
            current = current.getAsJsonObject(key);
        }
        return current;
    }

    /** Reads a scalar string safely, falling back for null or incompatible JSON values. */
    private static String string(JsonObject object, String key, String fallback) {
        try {
            return object.has(key) ? object.get(key).getAsString() : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    /** Reads a boolean setting with a fallback for missing/null or incompatible values. */
    private static boolean bool(JsonObject object, String key, boolean fallback) {
        try {
            return object.has(key) ? object.get(key).getAsBoolean() : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    /**
     * Accepts finite whole integers only; fractional, overflowing and malformed values use the caller's
     * fallback.
     */
    private static int integer(JsonObject object, String key, int fallback) {
        try {
            if (!object.has(key)) return fallback;
            double value = object.get(key).getAsDouble();
            return !Double.isFinite(value) || value != Math.rint(value)
                    || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE
                    ? fallback : (int) value;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    /** Keeps config coordinates in the integer-safe range used by world positions and AABBs. */
    private static int blockCoordinate(JsonObject object, String key, int fallback) {
        return clamp(integer(object, key, fallback), -MAX_BLOCK_COORDINATE, MAX_BLOCK_COORDINATE);
    }

    /** Rejects missing, non-numeric, fractional, or integer-overflowing block coordinates. */
    private static boolean hasBlockCoordinates(JsonObject object) {
        for (String axis : List.of("x", "y", "z")) {
            if (!object.has(axis) || !object.get(axis).isJsonPrimitive()
                    || !object.getAsJsonPrimitive(axis).isNumber()) {
                return false;
            }
            double coordinate = object.get(axis).getAsDouble();
            if (!Double.isFinite(coordinate)
                    || coordinate != Math.rint(coordinate)
                    || coordinate < Integer.MIN_VALUE
                    || coordinate > Integer.MAX_VALUE) {
                return false;
            }
        }
        return true;
    }

    /** Reads a finite numeric value, preventing NaN/infinity from entering native attributes or calculations. */
    private static double number(JsonObject object, String key, double fallback) {
        try {
            if (!object.has(key)) return fallback;
            double value = object.get(key).getAsDouble();
            return Double.isFinite(value) ? value : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    /** Resolves vanilla BossBarColor names with locale-independent casing and a default for unknown values. */
    private static BossEvent.BossBarColor parseColor(String value) {
        try {
            return BossEvent.BossBarColor.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return BossEvent.BossBarColor.PURPLE;
        }
    }

    /** Resolves vanilla bar overlay names with locale-independent casing and a default for unknown values. */
    private static BossEvent.BossBarOverlay parseOverlay(String value) {
        try {
            return BossEvent.BossBarOverlay.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return BossEvent.BossBarOverlay.NOTCHED_20;
        }
    }
}
