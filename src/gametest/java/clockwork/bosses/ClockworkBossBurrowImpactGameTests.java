package clockwork.bosses;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.phys.Vec3;

/** Real transformed-server actions and connected embedded native player
 * entities. This is server-logic evidence, not human login or client pixels.
 * Each case owns its actors/probe and releases them on failure and success. */
public final class ClockworkBossBurrowImpactGameTests implements clockwork.bosses.api.BossAddon {
    private static final Map<UUID, Fixture> ACTIVE = new HashMap<>();
    private static final Map<UUID, Fixture> ACTOR_OWNERS = new HashMap<>();
    private static boolean registered, kitChecked;

    private static void checkKitSyntax(GameTestHelper helper) throws Exception {
        if (kitChecked) return;
        byte[] bytes;
        try (var input=ClockworkBossBurrowImpactGameTests.class.getResourceAsStream("/clockwork-playtest-kit-commands.txt")) {
            if (input==null) throw new IllegalStateException("Private kit syntax fixture missing");
            bytes=input.readAllBytes();
        }
        var server=helper.getLevel().getServer();
        int count=0;
        for (String command:new String(bytes,java.nio.charset.StandardCharsets.UTF_8).lines().toList()) {
            if(command.isBlank()||command.startsWith("#")) continue;
            var parsed=server.getCommands().getDispatcher().parse(command,server.createCommandSourceStack());
            var failure=net.minecraft.commands.Commands.getParseException(parsed);
            helper.assertTrue(failure==null && !parsed.getReader().canRead(), "native kit command syntax: "+command+"; "+failure);
            count++;
        }
        helper.assertTrue(count==11,"all eleven prepared kit commands parsed by real native registries");
        var receipt=new JsonObject(); receipt.addProperty("status","PASS"); receipt.addProperty("commands",count);
        receipt.addProperty("sha256",java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)));
        receipt.addProperty("scope","native command/ItemArgument/enchantment codec parsing; no player inventory or combat acceptance");
        Files.writeString(Path.of("burrow-kit-syntax.json"),receipt+"\n",StandardOpenOption.CREATE_NEW);
        kitChecked=true;
    }

    @Override public void register(clockwork.bosses.api.BossActionRegistry.Registrar registry) {
        registry.register(new clockwork.bosses.api.BossActionType(
                net.minecraft.resources.Identifier.fromNamespaceAndPath("clockwork_bosses_regression", "legacy_burrow"),
                "Legacy controller probe", "Test-only existing two-argument controller call.", List.of(),
                java.util.Set.of(ClockworkBossActions.BURROW), () -> new clockwork.bosses.api.BossAction() {
                    @Override public void start(clockwork.bosses.api.BossActionContext context) { context.controller().startBurrow(5, 5); }
                    @Override public Result tick(clockwork.bosses.api.BossActionContext context) {
                        return context.controller().burrowFinished() ? Result.COMPLETE : Result.RUNNING;
                    }
                }));
    }

    private static void register() {
        if (registered) return;
        registered = true;
        net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            var actorOwner = ACTOR_OWNERS.get(entity.getUUID());
            if (actorOwner != null && entity instanceof ServerPlayer player) actorOwner.observeDamage("before", player, source, amount, false);
            var fixture = source.getEntity() == null ? null : ACTIVE.get(source.getEntity().getUUID());
            if (fixture == null || !(entity instanceof ServerPlayer player)) return true;
            fixture.attempts.add(player.getUUID());
            fixture.amounts.add(amount);
            if (fixture.damageCallback != null) fixture.damageCallback.accept(player);
            return !player.getUUID().equals(fixture.veto);
        });
        net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> {
            var owner = ACTOR_OWNERS.get(entity.getUUID());
            if (owner != null && entity instanceof ServerPlayer player) owner.observeDamage("after", player, source, taken, blocked);
        });
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            for (var fixture : List.copyOf(ACTIVE.values())) fixture.close();
        });
    }

    public static void observeBurst(ServerLevel level, int event, BlockPos position, int data) {
        if (event != LevelEvent.PARTICLES_SMASH_ATTACK) return;
        for (var fixture : List.copyOf(ACTIVE.values())) {
            if (fixture.boss.level() != level || fixture.boss.position().distanceToSqr(Vec3.atCenterOf(position)) > 16) continue;
            fixture.events.add(position.immutable());
            fixture.eventData.add(data);
            fixture.eventAt.add(level.getGameTime());
            fixture.targetAtCommit = fixture.boss.getTarget() == null ? null : fixture.boss.getTarget().getUUID();
            fixture.geometryAtCommit = fixture.actors.stream().map(actor -> actor.player)
                    .filter(player -> !player.isRemoved()).allMatch(fixture::geometryClear);
            fixture.runtimeAtCommit = ClockworkBossManager.stateFor(fixture.boss).actionRuntime();
            fixture.checkpointAtCommit = ((ClockworkBossMarker) fixture.boss).clockworkBosses$getBurrowCheckpoint();
            fixture.cachedGroundAtEvent = fixture.boss.getOnPos();
            if (fixture.burstCallback != null) fixture.burstCallback.run();
        }
    }

    private static final class Fixture implements AutoCloseable {
        final GameTestHelper helper;
        final Warden boss;
        final boolean detailedObservation;
        final List<ClockworkBossCombatGameTests.PlayerFixture> actors = new ArrayList<>();
        final List<Vec3> offsets = new ArrayList<>();
        final List<UUID> attempts = new ArrayList<>();
        final List<Float> amounts = new ArrayList<>();
        final List<BlockPos> events = new ArrayList<>();
        final List<Integer> eventData = new ArrayList<>();
        final List<Long> eventAt = new ArrayList<>();
        final com.google.gson.JsonArray damageObservations = new com.google.gson.JsonArray();
        UUID veto;
        UUID targetAtCommit;
        UUID targetBeforeDamage;
        clockwork.bosses.api.BossTargetRequest targetRequestAtBurst;
        int counterBeforeDamage = -1;
        Consumer<ServerPlayer> damageCallback;
        Runnable burstCallback;
        BlockPos cachedGroundAtEvent;
        ClockworkBossActionRuntime runtimeAtCommit;
        ClockworkBossBurrowCheckpoint checkpointAtCommit;
        final List<net.minecraft.world.level.ChunkPos> ownedChunks = new ArrayList<>();
        long started, emergeAt = -1;
        Vec3 surface;
        int emergenceCount;
        int clearedBarriers;
        boolean sawEmerging, closed, geometryAtCommit = true;

        Fixture(GameTestHelper helper, String name, JsonObject settings, boolean repeat) throws Exception {
            this(helper, name, settings, repeat, false);
        }
        Fixture(GameTestHelper helper, String name, JsonObject settings, boolean repeat, boolean legacy) throws Exception {
            register(); checkKitSyntax(helper); this.helper = helper;
            detailedObservation = name.equals("defenses") || name.startsWith("reentrant_");
            if (detailedObservation && !ClockworkBossDiagnostics.enabled()) ClockworkBossDiagnostics.start();
            var config = configuration(settings, repeat);
            if (legacy) for (var phase : config.getAsJsonObject("phases").entrySet())
                phase.getValue().getAsJsonObject().getAsJsonObject("subphases").getAsJsonObject("dig")
                        .addProperty("type", "clockwork_bosses_regression:legacy_burrow");
            // The shared helper prepares its first action immediately. Start
            // harmless recovery, establish visible/gravity baseline, THEN reload
            // burrow so its real restoration journal captures that baseline.
            var initial = config.deepCopy();
            for (var phase : initial.getAsJsonObject("phases").entrySet()) phase.getValue().getAsJsonObject().add("subphases",
                    JsonParser.parseString("{\"wait\":{\"type\":\"clockwork_bosses:recovery\",\"action_settings\":{\"duration_ticks\":600}}}"));
            boss = ClockworkBossCombatGameTests.boss(helper, initial, "impact_" + name);
            boss.setInvisible(false); boss.setNoGravity(true);
            helper.assertTrue(ClockworkBossManager.stateFor(boss).setConfig(config), "initialized fixture loads actual burrow sequence");
            ClockworkBossManager.prepareCombatPhase(boss);
            started = helper.getLevel().getGameTime();
            var floor = boss.blockPosition().below();
            for (int x = -9; x <= 22; x++) for (int z = -9; z <= 9; z++)
                helper.getLevel().setBlockAndUpdate(floor.offset(x, 0, z), Blocks.STONE.defaultBlockState());
            // Native GameTest cages can surround the small empty structure.
            // R9 recorded in_wall/barrier damage on actors outside that cage.
            // Prepare this fixture's padded arena air without filtering damage,
            // resetting player vitals/timers, or removing native control blocks.
            for (int x = -9; x <= 22; x++) for (int z = -9; z <= 9; z++) for (int y = 1; y <= 10; y++) {
                var position = floor.offset(x,y,z);
                if (helper.getLevel().getBlockState(position).is(Blocks.BARRIER)) {
                    helper.getLevel().setBlockAndUpdate(position,Blocks.AIR.defaultBlockState()); clearedBarriers++;
                }
            }
            ACTIVE.put(boss.getUUID(), this);
        }
        ServerPlayer actor(Vec3 offset) {
            var actor = new ClockworkBossCombatGameTests.PlayerFixture(helper, boss.position().add(offset));
            actors.add(actor); offsets.add(offset); actor.acknowledgeLoaded();
            var player = actor.player;
            player.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100); player.setHealth(100);
            player.setNoGravity(true); player.setDeltaMovement(Vec3.ZERO);
            player.getFoodData().setFoodLevel(17); player.getFoodData().setSaturation(0);
            ACTOR_OWNERS.put(player.getUUID(), this);
            return player;
        }
        void observeDamage(String stage, ServerPlayer player, net.minecraft.world.damagesource.DamageSource source, float amount, boolean blocked) {
            if (damageObservations.size() >= 128) return;
            var row=new JsonObject(); row.addProperty("stage",stage); row.addProperty("world_tick",helper.getLevel().getGameTime());
            row.addProperty("actor",ClockworkBossDiagnostics.alias(player.getUUID()));
            row.addProperty("damage_type",source.typeHolder().unwrapKey().map(key->key.identifier().toString()).orElse("unregistered"));
            row.addProperty("from_own_boss",source.getEntity()==boss); row.addProperty("amount",amount);
            row.addProperty("health",player.getHealth()); row.addProperty("blocked",blocked);
            row.addProperty("x",player.getX()); row.addProperty("y",player.getY()); row.addProperty("z",player.getZ());
            row.addProperty("fall_distance",player.fallDistance); row.addProperty("in_wall",player.isInWall());
            row.addProperty("eye_block",net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(
                    player.level().getBlockState(BlockPos.containing(player.getEyePosition())).getBlock()).toString());
            damageObservations.add(row);
        }
        void align() {
            // Embedded channels do not run a real client's play-listener loop.
            // Drive native player ticks (item-use delay, effects, hurt cooldown)
            // explicitly instead of editing those timers or bypassing defenses.
            for (var actor : actors) if (!actor.player.isRemoved()) {
                helper.assertTrue(geometryClear(actor.player),"fixture body/eye must be clear before native player tick");
                actor.player.doTick();
            }
            if (boss.hasPose(Pose.EMERGING)) {
                if (!sawEmerging) {
                    sawEmerging = true; emergenceCount++; emergeAt = helper.getLevel().getGameTime(); surface = boss.position();
                }
                for (int i = 0; i < actors.size(); i++) {
                    var player = actors.get(i).player;
                    if (player.isRemoved()) continue;
                    player.snapTo(surface.add(offsets.get(i))); player.setDeltaMovement(Vec3.ZERO);
                    helper.assertTrue(geometryClear(player),"fixture body/eye must be clear after real emergence relocation");
                }
            } else sawEmerging = false;
        }
        boolean geometryClear(ServerPlayer player) {
            return !player.isInWall() && !helper.getLevel().getBlockCollisions(player,player.getBoundingBox()).iterator().hasNext();
        }
        void retainRelocationChunks(Vec3 target) {
            int cx = BlockPos.containing(target).getX() >> 4, cz = BlockPos.containing(target).getZ() >> 4;
            var level = helper.getLevel();
            for (int x=cx-3;x<=cx+3;x++) for (int z=cz-3;z<=cz+3;z++) level.getChunk(x,z);
            for (int x=cx-1;x<=cx+1;x++) for (int z=cz-1;z<=cz+1;z++)
                if (level.setChunkForced(x,z,true)) ownedChunks.add(new net.minecraft.world.level.ChunkPos(x,z));
        }
        boolean finished() {
            var state = ClockworkBossManager.stateFor(boss);
            return emergeAt >= 0 && state.subphase() != null && state.subphase().key().equals("wait");
        }
        void completion() {
            helper.assertTrue(helper.getLevel().getGameTime() - emergeAt == 34, "surface action completes 34 ticks after actual emergence start");
            helper.assertTrue(!boss.isInvisible() && boss.hasPose(Pose.STANDING), "impact uses visible standing surface");
            helper.assertTrue(((ClockworkBossMarker) boss).clockworkBosses$getBurrowCheckpoint() == null, "surface checkpoint released");
            helper.assertTrue(geometryAtCommit,"all fixture actor body/eye geometry clear at committed burst");
        }
        void record(String label) {
            var row = new JsonObject(); row.addProperty("case", label);
            row.addProperty("events", events.size()); row.addProperty("attempts", attempts.size());
            row.addProperty("emergence_ticks", helper.getLevel().getGameTime() - emergeAt);
            row.addProperty("test_cage_barriers_cleared",clearedBarriers);row.addProperty("geometry_at_commit",geometryAtCommit);
            row.add("all_native_player_damage",damageObservations.deepCopy());
            if (detailedObservation) {
                row.addProperty("target_at_commit", targetAtCommit == null ? "none" : ClockworkBossDiagnostics.alias(targetAtCommit));
                row.addProperty("target_request_at_burst",targetRequestAtBurst==null?"none":targetRequestAtBurst.name());
                row.addProperty("target_before_damage",targetBeforeDamage==null?"none":ClockworkBossDiagnostics.alias(targetBeforeDamage));
                row.addProperty("counter_before_damage",counterBeforeDamage);
                row.addProperty("target_after", boss.getTarget() == null ? "none" : ClockworkBossDiagnostics.alias(boss.getTarget().getUUID()));
                var state = ClockworkBossManager.stateFor(boss);
                row.addProperty("state_present", state != null);
                if (state != null) try {
                    var field=ClockworkBossState.class.getDeclaredField("attacksOnTarget"); field.setAccessible(true);
                    row.addProperty("completed_attacks_after",field.getInt(state));
                    row.addProperty("generation_after",state.subphaseGeneration());
                    row.addProperty("action_after",state.subphase()==null?"none":state.subphase().key());
                } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
                var players=new com.google.gson.JsonArray();
                for (var actor:actors) {
                    var player=new JsonObject(); player.addProperty("alias",ClockworkBossDiagnostics.alias(actor.player.getUUID()));
                    player.addProperty("health",actor.player.getHealth());player.addProperty("max_health",actor.player.getMaxHealth());
                    player.addProperty("attempted",attempts.contains(actor.player.getUUID()));player.addProperty("veto",actor.player.getUUID().equals(veto));
                    players.add(player);
                }
                row.add("players",players);
                var events=new com.google.gson.JsonArray();
                String alias=ClockworkBossDiagnostics.alias(boss.getUUID());
                for(var event:ClockworkBossDiagnostics.snapshot()) {
                    var item=event.getAsJsonObject();
                    if(item.has("boss")&&item.get("boss").getAsString().equals(alias)) events.add(item);
                }
                row.add("native_diagnostics",events);
            }
            row.addProperty("scope", "native server logic with embedded connections; no human login/pixel claim");
            try { Files.writeString(Path.of("burrow-impact-native.jsonl"), row + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
            catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
        }
        @Override public void close() {
            if (closed) return;
            closed = true; ACTIVE.remove(boss.getUUID()); damageCallback = null; burstCallback = null;
            for (var actor : actors) ACTOR_OWNERS.remove(actor.player.getUUID(),this);
            boss.discard(); actors.forEach(ClockworkBossCombatGameTests.PlayerFixture::close);
            for (var chunk : ownedChunks) helper.getLevel().setChunkForced(chunk.x(),chunk.z(),false);
            helper.assertTrue(ownedChunks.stream().noneMatch(chunk -> helper.getLevel().getForceLoadedChunks().contains(net.minecraft.world.level.ChunkPos.pack(chunk.x(),chunk.z()))),
                    "only this fixture's temporary relocation chunk grants released");
        }
    }

    private static JsonObject configuration(JsonObject settings, boolean repeat) {
        var config = ClockworkBossCombatGameTests.config(1000);
        // Keep the shared combat helper's opt-outs across this fixture's reload;
        // first-entry healing or an arena potion is not part of a raw-hit test.
        for (String key : List.of("player_health", "reusable_potion", "pvp_protection", "area_effects"))
            config.getAsJsonObject("boss_bar").getAsJsonObject(key).addProperty("enabled", false);
        config.getAsJsonObject("voice_lines").addProperty("enabled", false);
        config.getAsJsonObject("music").addProperty("enabled", false);
        config.getAsJsonObject("dormant_darkness").addProperty("enabled", false);
        var area = config.getAsJsonObject("boss_bar").getAsJsonObject("visibility_area");
        area.addProperty("shape", "radius"); area.addProperty("radius", 48); area.addProperty("dimension", "");
        var targeting = config.getAsJsonObject("targeting");
        targeting.addProperty("switch_chance", 0); targeting.addProperty("min_target_ticks", 12000);
        for (var entry : config.getAsJsonObject("phases").entrySet()) {
            var phase = entry.getValue().getAsJsonObject();
            phase.getAsJsonObject("darkness").addProperty("enabled", false);
            phase.addProperty("roar_between_subphases", false);
            phase.getAsJsonObject("melee").addProperty("enabled", false);
            phase.getAsJsonObject("sonic_boom").addProperty("enabled", false);
            var sequence = JsonParser.parseString("{\"dig\":{\"type\":\"clockwork_bosses:burrow\",\"action_settings\":{}},\"wait\":{\"type\":\"clockwork_bosses:recovery\",\"action_settings\":{\"duration_ticks\":" + (repeat ? 1 : 600) + "}}}").getAsJsonObject();
            var actionSettings = settings.deepCopy(); actionSettings.addProperty("underground_ticks", 5); actionSettings.addProperty("depth", 5);
            sequence.getAsJsonObject("dig").add("action_settings", actionSettings); phase.add("subphases", sequence);
        }
        return config;
    }
    private static JsonObject options(double damage, double radius, boolean particles) {
        var result = new JsonObject(); result.addProperty("emerge_damage", damage);
        result.addProperty("emerge_radius", radius); result.addProperty("emerge_particles", particles); return result;
    }
    private static void checkTick(Fixture fixture, Runnable body) {
        fixture.helper.onEachTick(() -> {
            if (fixture.closed) return;
            try { fixture.align(); body.run(); }
            catch (RuntimeException | Error failure) {
                fixture.record("FAIL:" + failure.getMessage()); fixture.close(); throw failure;
            }
        });
    }
    private static void hp(GameTestHelper helper, ServerPlayer player, float expected, String message) {
        helper.assertTrue(Math.abs(player.getHealth() - expected) < .001F, message + ": HP=" + player.getHealth());
    }

    @GameTest(maxTicks = 180, padding = 64)
    public void emergence_radius_native_defenses_and_target_budget(GameTestHelper helper) throws Exception {
        var f = new Fixture(helper, "defenses", options(20, 4, true), false);
        var inside = f.actor(new Vec3(1.5, 0, 0));
        var edge = f.actor(new Vec3(4, 0, 0));
        var outside = f.actor(new Vec3(4.01, 0, 0));
        var vertical = f.actor(new Vec3(0, 4.01, 0));
        var armor = f.actor(new Vec3(-1.5, 0, 0));
        armor.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));
        armor.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
        armor.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.DIAMOND_LEGGINGS));
        armor.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.DIAMOND_BOOTS));
        var veto = f.actor(new Vec3(0, 0, 1.5)); f.veto = veto.getUUID();
        var creative = f.actor(new Vec3(0, 0, -1.5)); creative.setGameMode(GameType.CREATIVE);
        var spectator = f.actor(new Vec3(0, 1, 0)); spectator.setGameMode(GameType.SPECTATOR);
        helper.assertTrue(ClockworkBossManager.requestTarget(f.boss,veto)==clockwork.bosses.api.BossTargetRequest.ACCEPTED,
                "fixture uses production target assignment and native anger");
        // The native burrow wait may clear the initial target (R10 observed
        // none at commitment). This real level-event boundary occurs before the
        // impact's damage loop. Establish, record and test the intended current
        // opponent here through the supported production assignment path.
        f.burstCallback = () -> {
            f.targetRequestAtBurst=ClockworkBossManager.requestTarget(f.boss,veto);
            f.targetBeforeDamage=f.boss.getTarget()==null?null:f.boss.getTarget().getUUID();
            try {
                var field=ClockworkBossState.class.getDeclaredField("attacksOnTarget");field.setAccessible(true);
                f.counterBeforeDamage=field.getInt(ClockworkBossManager.stateFor(f.boss));
            } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
        };
        checkTick(f, () -> {
            if (!f.finished()) return;
            f.completion(); hp(helper, inside, 80, "configured raw damage"); hp(helper, edge, 80, "closed four-block boundary");
            helper.assertTrue((f.targetRequestAtBurst==clockwork.bosses.api.BossTargetRequest.ACCEPTED
                            || f.targetRequestAtBurst==clockwork.bosses.api.BossTargetRequest.UNCHANGED)
                            && veto.getUUID().equals(f.targetBeforeDamage) && f.counterBeforeDamage==0,
                    "real pre-damage assignment establishes intended veto target with a fresh attack budget");
            hp(helper, outside, 100, "outside radius"); hp(helper, vertical, 100, "three-dimensional radius");
            hp(helper, veto, 100, "Fabric veto preserved"); hp(helper, creative, 100, "creative excluded"); hp(helper, spectator, 100, "spectator excluded");
            helper.assertTrue(armor.getHealth() > 80 && armor.getHealth() < 100, "native diamond armor reduces raw hit");
            helper.assertTrue(armor.getItemBySlot(EquipmentSlot.CHEST).getDamageValue() > 0, "native armor durability wears");
            helper.assertTrue(f.attempts.size() == 4 && f.amounts.stream().allMatch(amount -> amount == 20F), "only four eligible in-sphere attempts, raw20 each");
            helper.assertTrue(f.events.size() == 1 && f.eventData.equals(List.of(750)), "one native mace burst with native data");
            int decisions=0;
            String bossAlias=ClockworkBossDiagnostics.alias(f.boss.getUUID()),vetoAlias=ClockworkBossDiagnostics.alias(veto.getUUID());
            for(var entry:ClockworkBossDiagnostics.snapshot()) {
                var event=entry.getAsJsonObject();
                if(!event.has("boss")||!event.get("boss").getAsString().equals(bossAlias)
                        ||!event.get("event").getAsString().equals("retarget_decision")) continue;
                decisions++;
                helper.assertTrue(event.get("actor").getAsString().equals(vetoAlias)
                                && event.get("completed_attacks").getAsInt()==1,
                        "actual afterAttack boundary counts rejected current-target attempt once, no other AoE victim budget");
            }
            helper.assertTrue(decisions==1,"exactly one target-budget decision before Recovery; post-Recovery state recorded separately");
            f.record("bounds_defenses_budget"); f.close(); helper.succeed();
        });
    }

    @GameTest(maxTicks = 650, padding = 64)
    public void emergence_respects_native_iframes_shield_and_resistance(GameTestHelper helper) throws Exception {
        var f = new Fixture(helper, "native_guards", new JsonObject(), false);
        var iframe = f.actor(new Vec3(1, 0, 0));
        var shield = f.actor(new Vec3(-1, 0, 0));
        shield.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
        shield.setYRot(-90); shield.setXRot(0); shield.startUsingItem(net.minecraft.world.InteractionHand.OFF_HAND);
        var resistance = f.actor(new Vec3(0, 0, 1));
        resistance.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.RESISTANCE, 200, 0));
        f.burstCallback = () -> {
            f.burstCallback = null;
            shield.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.FEET, f.boss.position());
            helper.assertTrue(shield.isBlocking() && shield.getTicksUsingItem() >= 5,
                    "actual native shield is raised before hit: using="+shield.isUsingItem()+" ticks="+shield.getTicksUsingItem());
            helper.assertTrue(iframe.hurtServer(helper.getLevel(), iframe.damageSources().generic(), 30), "native preparation hit accepted");
        };
        checkTick(f, () -> {
            if (!f.finished()) return;
            hp(helper, iframe, 70, "smaller emerging hit respects native damage cooldown after real prior hit");
            hp(helper, shield, 100, "native facing shield blocks emergence");
            hp(helper, resistance, 84, "native Resistance reduces raw20 by20percent");
            helper.assertTrue(f.events.size()==1,"native defenses do not suppress independent burst");
            f.record("native_iframes_shield_resistance"); f.close(); helper.succeed();
        });
    }

    @GameTest(maxTicks = 650, padding = 64)
    public void emergence_zero_fractional_null_and_particle_options(GameTestHelper helper) throws Exception {
        var nulls = new JsonObject(); nulls.add("emerge_damage", com.google.gson.JsonNull.INSTANCE);
        nulls.add("emerge_radius", com.google.gson.JsonNull.INSTANCE); nulls.add("emerge_particles", com.google.gson.JsonNull.INSTANCE);
        var choices = List.of(options(0, 4, true), options(20, 0, true), options(0, 0, false),
                options(2.5, 1.25, false), nulls);
        int[] index = {0}; Fixture[] active = {new Fixture(helper, "options_0", choices.getFirst(), false)};
        active[0].actor(new Vec3(1.25, 0, 0));
        helper.onEachTick(() -> {
            var f = active[0]; if (f == null) return;
            try {
                f.align(); if (!f.finished()) return;
                f.completion(); int i = index[0];
                hp(helper, f.actors.getFirst().player, i == 3 ? 97.5F : i == 4 ? 80 : 100, "zero/fractional/null damage case " + i);
                helper.assertTrue(f.events.size() == ((i == 2 || i == 3) ? 0 : 1), "independent particle toggle case " + i);
                f.record("options_" + i); f.close();
                if (++index[0] == choices.size()) { active[0] = null; helper.succeed(); return; }
                active[0] = new Fixture(helper, "options_" + index[0], choices.get(index[0]), false);
                active[0].actor(new Vec3(1.25, 0, 0));
            } catch (Exception | Error failure) { f.record("FAIL:options_"+index[0]+":"+failure.getMessage()); f.close(); active[0] = null; throw new IllegalStateException(failure); }
        });
    }

    @GameTest(maxTicks = 400, padding = 64)
    public void emergence_repeats_once_per_real_burrow(GameTestHelper helper) throws Exception {
        var f = new Fixture(helper, "repeat", new JsonObject(), true); var player = f.actor(new Vec3(1, 0, 0));
        int[] seen = {0};
        checkTick(f, () -> {
            if (!f.finished() || f.emergenceCount == seen[0]) return;
            f.completion(); seen[0] = f.emergenceCount;
            hp(helper, player, 100 - 20 * seen[0], "one damage per real burrow");
            helper.assertTrue(f.events.size() == seen[0] && f.attempts.size() == seen[0], "no replay between cycles");
            f.record("repeat_" + seen[0]);
            if (seen[0] == 3) { f.close(); helper.succeed(); }
        });
    }

    @GameTest(maxTicks = 400, padding = 64)
    public void emergence_reentrant_damage_stops_old_action(GameTestHelper helper) throws Exception {
        int[] index = {0}; Fixture[] active = {new Fixture(helper, "reentrant_0", new JsonObject(), false)};
        Consumer<Fixture> prepare = f -> {
            f.actor(new Vec3(1, 0, 0)); f.actor(new Vec3(-1, 0, 0)); f.actor(new Vec3(0, 0, 1));
            f.damageCallback = victim -> {
                f.damageCallback = null;
                if (index[0] == 0) { f.boss.setHealth(400); ClockworkBossManager.tickClockworkBossLogic(f.boss); }
                else if (index[0] == 1) { ClockworkBossManager.stateFor(f.boss).setConfig(configuration(new JsonObject(), false)); ClockworkBossManager.prepareCombatPhase(f.boss); }
                else f.boss.discard();
            };
        };
        prepare.accept(active[0]);
        helper.onEachTick(() -> {
            var f = active[0]; if (f == null) return;
            try {
                f.align(); if (f.attempts.isEmpty()) return;
                helper.assertTrue(f.attempts.size() == 1 && f.events.size() == 1, "phase/reload/removal closes owner before later victim attempts");
                helper.assertTrue(f.actors.stream().filter(actor -> actor.player.getHealth() < 100).count() == 1, "already committed native attempt may complete, others untouched");
                var currentState = ClockworkBossManager.stateFor(f.boss);
                helper.assertTrue(f.runtimeAtCommit != null && !f.runtimeAtCommit.active()
                                && (currentState == null || currentState.actionRuntime() != f.runtimeAtCommit)
                                && ((ClockworkBossMarker) f.boss).clockworkBosses$getBurrowCheckpoint() != f.checkpointAtCommit
                                && !f.boss.isInvisible() && f.boss.isNoGravity(),
                        "old scope/journal released and flags restored even when callback starts a new burrow journal");
                f.record("reentrant_" + index[0]); f.close();
                if (++index[0] == 3) { active[0] = null; helper.succeed(); return; }
                active[0] = new Fixture(helper, "reentrant_" + index[0], new JsonObject(), false); prepare.accept(active[0]);
            } catch (Exception | Error failure) { f.record("FAIL:reentrant_"+index[0]+":"+failure.getMessage()); f.close(); active[0] = null; throw new IllegalStateException(failure); }
        });
    }

    @GameTest(maxTicks = 180, padding = 64)
    public void emergence_snapshot_rechecks_and_particle_callback_stop(GameTestHelper helper) throws Exception {
        var f = new Fixture(helper, "snapshot", new JsonObject(), false);
        var first = f.actor(new Vec3(1, 0, 0)); var removed = f.actor(new Vec3(-1, 0, 0));
        var third = f.actor(new Vec3(0, 0, 1)); ServerPlayer[] late = {null};
        f.burstCallback = () -> {
            f.burstCallback = null; removed.level().getServer().getPlayerList().remove(removed);
            late[0] = f.actor(new Vec3(0, 0, -1)); late[0].snapTo(f.boss.position().add(0, 0, -1));
        };
        Fixture[] active = {f};
        // Register exactly once. Adding onEachTick from one of its callbacks
        // mutates GameTestInfo's scheduled map during native iteration.
        helper.onEachTick(() -> {
            var current = active[0]; if (current == null) return;
            try {
                current.align();
                if (current == f) {
                    if (!f.finished()) return;
                    hp(helper, first, 80, "first snapshot member hit"); hp(helper, third, 80, "later snapshot member hit");
                    hp(helper, removed, 100, "removed identity skipped"); hp(helper, late[0], 100, "late member excluded from committed snapshot");
                    helper.assertTrue(f.attempts.size() == 2 && f.events.size() == 1, "no concurrent-list replay");
                    f.record("snapshot_mutation"); f.close();
                    var stopped = new Fixture(helper, "burst_stop", new JsonObject(), false);
                    stopped.actor(new Vec3(1, 0, 0)); stopped.burstCallback = stopped.boss::discard;
                    active[0] = stopped;
                    return;
                }
                if (current.events.isEmpty()) return;
                hp(helper, current.actors.getFirst().player, 100, "particle callback ended owner before damage");
                helper.assertTrue(current.attempts.isEmpty() && current.events.size() == 1, "one committed particle, no stale damage continuation");
                current.record("burst_stop"); current.close(); active[0] = null; helper.succeed();
            } catch (Exception | Error failure) {
                current.record("FAIL:snapshot_or_stop:"+failure.getMessage()); current.close(); active[0] = null;
                throw new IllegalStateException(failure);
            }
        });
    }

    @GameTest(maxTicks = 180, padding = 64)
    public void emergence_legacy_controller_keeps_hit_and_fx_opt_out(GameTestHelper helper) throws Exception {
        var f = new Fixture(helper, "legacy", new JsonObject(), false, true); var player = f.actor(new Vec3(1, 0, 0));
        checkTick(f, () -> {
            if (!f.finished()) return;
            f.completion(); hp(helper, player, 100, "existing addon controller does not acquire core impact");
            helper.assertTrue(f.attempts.isEmpty() && f.events.isEmpty(), "legacy controller has no new hit or particle side effects");
            f.record("legacy_controller"); f.close(); helper.succeed();
        });
    }

    @GameTest(maxTicks = 180, padding = 64)
    public void emergence_relocation_queries_current_ground(GameTestHelper helper) throws Exception {
        var f = new Fixture(helper, "ground", new JsonObject(), false);
        var original = f.boss.blockPosition().below();
        var target = f.actor(new Vec3(12, 0, 0)); f.boss.setAttackTarget(target);
        f.retainRelocationChunks(target.position());
        for (int x = 5; x <= 22; x++) for (int z = -9; z <= 9; z++)
            helper.getLevel().setBlockAndUpdate(original.offset(x, 0, z), Blocks.DIAMOND_BLOCK.defaultBlockState());
        var supporting = net.minecraft.world.entity.Entity.class.getDeclaredField("mainSupportingBlockPos"); supporting.setAccessible(true);
        checkTick(f, () -> {
            if (f.boss.hasPose(Pose.EMERGING)) {
                try { supporting.set(f.boss, Optional.of(original)); }
                catch (IllegalAccessException failure) { throw new IllegalStateException(failure); }
            }
            if (!f.finished()) return;
            helper.assertTrue(f.surface.distanceToSqr(Vec3.atCenterOf(original)) > 25, "real burrow relocated to a different surface");
            var expected = BlockPos.containing(f.surface.x, f.surface.y - 1E-5, f.surface.z);
            helper.assertTrue(f.events.equals(List.of(expected)) && helper.getLevel().getBlockState(expected).is(Blocks.DIAMOND_BLOCK), "native burst uses current diamond support after actual relocation");
            helper.assertTrue(original.equals(f.cachedGroundAtEvent), "fixture demonstrates stale native getOnPos cache at impact");
            f.record("relocated_ground"); f.close(); helper.succeed();
        });
    }
}
