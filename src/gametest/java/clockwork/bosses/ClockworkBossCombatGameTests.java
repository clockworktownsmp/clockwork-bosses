package clockwork.bosses;

import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;
import net.minecraft.resources.Identifier;

import java.util.UUID;

/** Native 26.3/mixin regressions; run only in build/combat-gametest, never in the user's live worlds. */
public class ClockworkBossCombatGameTests {
    private static UUID protectedVictim;
    private static boolean protectionRegistered;
    private static UUID preventedDeath;
    private static boolean deathProtectionRegistered;

    /** Chase movement can temporarily separate a boss and its opponent. The
     * captured swing must keep its deadline, then use their positions at impact. */
    @GameTest(maxTicks = 25, setupTicks = 10)
    public void melee_movement_keeps_windup_deadline(GameTestHelper helper) throws Exception {
        JsonObject config = config(1000);
        for (var phase : ClockworkBossPhase.values()) {
            var melee = phase(config, phase).getAsJsonObject("melee");
            melee.addProperty("charge_time_ticks", 10);
            melee.addProperty("damage", 7);
            melee.addProperty("knockback", 0);
            phase(config, phase).getAsJsonObject("sonic_boom").addProperty("enabled", false);
        }
        Warden boss = boss(helper, config, "moving_melee");
        boss.setNoGravity(true);
        var victim = helper.spawn(EntityTypes.COW, new Vec3(3, 2, 2));
        victim.setNoAi(true); victim.setNoGravity(true);
        victim.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100);
        victim.setHealth(100); victim.setInvulnerableTime(0);
        boss.setAttackTarget(victim);
        var state = ClockworkBossManager.stateFor(boss);
        boss.doHurtTarget(helper.getLevel(), victim);
        var pending = state.pendingMelee();
        helper.assertTrue(pending != null, "moving melee captures its initial deadline");
        helper.runAfterDelay(1, () -> {
            boss.snapTo(helper.absoluteVec(new Vec3(2, 2, 6)));
            helper.assertTrue(!boss.isWithinMeleeAttackRange(victim), "boss movement temporarily leaves melee reach");
            ClockworkBossMelee.tick(boss, state);
            helper.assertTrue(state.pendingMelee() == pending && victim.getHealth() == 100,
                    "boss movement must not cancel or restart the wind-up");
        });
        helper.runAfterDelay(3, () -> {
            victim.snapTo(helper.absoluteVec(new Vec3(6, 2, 2)));
            ClockworkBossMelee.tick(boss, state);
            helper.assertTrue(state.pendingMelee() == pending && victim.getHealth() == 100,
                    "opponent movement keeps the same deadline without early damage");
        });
        helper.runAfterDelay(8, () -> {
            boss.snapTo(helper.absoluteVec(new Vec3(2, 2, 2)));
            victim.snapTo(helper.absoluteVec(new Vec3(3, 2, 2)));
            // Expire Minecraft's per-tick sensing cache after changing positions.
            boss.getSensing().tick();
            ClockworkBossMelee.tick(boss, state);
            helper.assertTrue(state.pendingMelee() == pending && victim.getHealth() == 100,
                    "back in reach, still waiting for the original deadline");
        });
        helper.runAfterDelay(10, () -> {
            try {
                ClockworkBossMelee.tick(boss, state);
                helper.assertTrue(state.pendingMelee() == null && victim.getHealth() == 93,
                        "moving swing lands once at its original deadline: hp=" + victim.getHealth()
                                + " pending=" + state.pendingMelee() + " now=" + helper.getLevel().getGameTime()
                                + " due=" + pending.dueTick() + " reach=" + boss.isWithinMeleeAttackRange(victim)
                                + " visible=" + boss.getSensing().hasLineOfSight(victim) + " " + diagnostics(boss));
                ClockworkBossMelee.tick(boss, state);
                helper.assertTrue(victim.getHealth() == 93, "completed swing cannot deal a duplicate hit");
                helper.succeed();
            } finally { boss.discard(); victim.discard(); }
        });
    }

    /** A Fabric damage veto stands in for an external protection mod; this
     * proves our beam respects rejection, not actual Combat Roll integration. */
    @GameTest public void compatibility_damage_veto_and_bounded_diagnostics(GameTestHelper helper) throws Exception {
        JsonObject config = config(1000);
        for (var phase : ClockworkBossPhase.values()) {
            phase(config, phase).getAsJsonObject("melee").addProperty("enabled", false);
            var sonic = phase(config, phase).getAsJsonObject("sonic_boom");
            sonic.addProperty("charge_time_ticks", 0); sonic.addProperty("damage", 7);
            sonic.addProperty("requires_line_of_sight", false);
        }
        Warden boss = boss(helper, config, "compat_damage");
        boss.setNoAi(true);
        var victim = helper.spawn(EntityTypes.COW, new Vec3(4, 2, 4));
        victim.setNoAi(true); victim.setNoGravity(true); victim.setHealth(10);
        if (!protectionRegistered) {
            protectionRegistered = true;
            net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.ALLOW_DAMAGE.register(
                    (entity, source, amount) -> !entity.getUUID().equals(protectedVictim));
        }
        protectedVictim = victim.getUUID();
        try {
            ClockworkBossDiagnostics.clear();
            ClockworkBossDiagnostics.event(boss, victim, "disabled_probe");
            helper.assertTrue(ClockworkBossDiagnostics.snapshot().isEmpty(), "disabled capture has no buffer growth");
            ClockworkBossDiagnostics.start();
            boss.setAttackTarget(victim);
            boss.getBrain().eraseMemory(MemoryModuleType.SONIC_BOOM_COOLDOWN);
            var velocity = victim.getDeltaMovement();
            ClockworkBossSonicBoom.tick(boss, ClockworkBossManager.stateFor(boss));
            helper.assertTrue(victim.getHealth() == 10 && victim.getDeltaMovement().equals(velocity), "vetoed damage grants no extra knockback");
            boolean rejected = false, completed = false, successfulFeedback = false;
            for (var raw : ClockworkBossDiagnostics.snapshot()) {
                var event = raw.getAsJsonObject();
                if (event.get("event").getAsString().equals("sonic_native_result")) rejected = !event.get("accepted").getAsBoolean();
                if (event.get("event").getAsString().equals("sonic_completed")) completed = true;
                if (event.get("event").getAsString().equals("successful_attack_feedback")) successfulFeedback = true;
            }
            helper.assertTrue(rejected && completed && !successfulFeedback, "capture separates native rejection, completion and successful feedback");
            ClockworkBossDiagnostics.event(boss, victim, "invalid_number_probe", "value", Double.NaN);
            helper.assertTrue(ClockworkBossDiagnostics.dump(source(helper)) == 1, "operator can write a local report");
            for (int i = 0; i < 3000; i++) ClockworkBossDiagnostics.event(boss, victim, "bounded_probe", "ordinal", i);
            var buffered = ClockworkBossDiagnostics.snapshot();
            helper.assertTrue(buffered.size() == 2048 && !buffered.toString().contains(victim.getUUID().toString()), "bounded capture excludes full actor UUIDs");
            helper.succeed();
        } finally { protectedVictim = null; ClockworkBossDiagnostics.clear(); boss.discard(); victim.discard(); }
    }

    /** Moving out during wind-up keeps its timer. Crossing a health phase still
     * invalidates the captured generation immediately, even before impact. */
    @GameTest public void compatibility_delayed_melee_range_and_phase(GameTestHelper helper) throws Exception {
        JsonObject config = config(1000);
        for (var phase : ClockworkBossPhase.values()) {
            var melee = phase(config, phase).getAsJsonObject("melee");
            melee.addProperty("charge_time_ticks", 6); melee.addProperty("damage", 10);
        }
        Warden boss = boss(helper, config, "compat_windup");
        boss.setNoGravity(true);
        var victim = helper.spawn(EntityTypes.COW, new Vec3(3, 2, 2));
        victim.setNoAi(true); victim.setNoGravity(true); victim.setInvulnerableTime(0);
        boss.setAttackTarget(victim);
        var state = ClockworkBossManager.stateFor(boss);
        try {
            helper.assertTrue(!boss.doHurtTarget(helper.getLevel(), victim) && state.pendingMelee() != null, "native wind-up schedules without immediate damage");
            var pending = state.pendingMelee();
            float health = victim.getHealth();
            // Stay in loaded test chunks: unloading a target is still a valid
            // immediate interruption, distinct from briefly leaving melee reach.
            victim.snapTo(boss.position().add(0, 0, 4));
            ClockworkBossMelee.tick(boss, state);
            helper.assertTrue(state.pendingMelee() == pending && victim.getHealth() == health, "temporary separation preserves pending hit without damage");
            victim.snapTo(boss.position().add(1, 0, 0));
            boss.setAttackTarget(victim);
            boss.doHurtTarget(helper.getLevel(), victim);
            helper.assertTrue(state.pendingMelee() == pending, "another native attempt cannot reset the original wind-up");
            boss.setHealth(499);
            ClockworkBossManager.prepareCombatPhase(boss);
            helper.assertTrue(state.pendingMelee() == null && victim.getHealth() == health, "new health phase cancels an old pending hit");
            helper.succeed();
        } finally { boss.discard(); victim.discard(); }
    }

    /** Completing a wind-up does not guarantee damage: an opponent outside
     * native melee reach at impact dodges, without receiving a late hit on return. */
    @GameTest(maxTicks = 25, setupTicks = 10)
    public void melee_escape_at_impact_cannot_land_later(GameTestHelper helper) throws Exception {
        JsonObject config = config(1000);
        for (var phase : ClockworkBossPhase.values()) {
            phase(config, phase).getAsJsonObject("melee").addProperty("charge_time_ticks", 10);
            phase(config, phase).getAsJsonObject("sonic_boom").addProperty("enabled", false);
        }
        Warden boss = boss(helper, config, "melee_escape");
        boss.setNoGravity(true);
        var victim = helper.spawn(EntityTypes.COW, new Vec3(3, 2, 2));
        victim.setNoAi(true); victim.setNoGravity(true);
        boss.setAttackTarget(victim);
        var state = ClockworkBossManager.stateFor(boss);
        boss.doHurtTarget(helper.getLevel(), victim);
        var pending = state.pendingMelee();
        float health = victim.getHealth();
        victim.snapTo(boss.position().add(10, 0, 0));
        helper.runAfterDelay(9, () -> {
            ClockworkBossMelee.tick(boss, state);
            helper.assertTrue(state.pendingMelee() == pending && victim.getHealth() == health,
                    "escaped opponent keeps the original wind-up through its last tick");
        });
        helper.runAfterDelay(10, () -> {
            try {
                ClockworkBossMelee.tick(boss, state);
                helper.assertTrue(state.pendingMelee() == null && victim.getHealth() == health,
                        "outside reach at impact receives no damage and releases the pending hit");
                victim.snapTo(boss.position().add(1, 0, 0));
                boss.getSensing().tick();
                ClockworkBossMelee.tick(boss, state);
                helper.assertTrue(victim.getHealth() == health, "returning cannot revive the missed swing");
                helper.succeed();
            } finally { boss.discard(); victim.discard(); }
        });
    }

    /** A wall can interrupt visibility without resetting the timer, but native
     * visibility must still reject damage when that wall remains at impact. */
    @GameTest(maxTicks = 25, setupTicks = 10)
    public void melee_wall_at_impact_and_target_interruption(GameTestHelper helper) throws Exception {
        JsonObject config = config(1000);
        config.getAsJsonObject("targeting").addProperty("min_target_ticks", 0);
        for (var phase : ClockworkBossPhase.values()) {
            phase(config, phase).getAsJsonObject("melee").addProperty("charge_time_ticks", 10);
            phase(config, phase).getAsJsonObject("sonic_boom").addProperty("enabled", false);
        }
        Warden boss = boss(helper, config, "melee_wall");
        boss.setNoGravity(true);
        // Enlarge native reach enough for a full solid block between the actors;
        // neither actor's eye is inside the wall. Do not bypass the reach method.
        boss.getAttribute(Attributes.SCALE).setBaseValue(4);
        // This assertion runs before the next entity tick refreshes dirty
        // attributes, so explicitly refresh native dimensions in the fixture.
        boss.refreshDimensions();
        var victim = helper.spawn(EntityTypes.COW, new Vec3(4.8, 2, 2));
        victim.setNoAi(true); victim.setNoGravity(true);
        boss.setAttackTarget(victim);
        var state = ClockworkBossManager.stateFor(boss);
        boss.doHurtTarget(helper.getLevel(), victim);
        helper.assertTrue(state.pendingMelee() != null, "initial wind-up exists before target clearing");
        boss.setAttackTarget(null);
        helper.assertTrue(state.pendingMelee() == null, "lost target cancels immediately despite deferred reach checks");
        boss.setAttackTarget(victim);
        boss.doHurtTarget(helper.getLevel(), victim);
        var pending = state.pendingMelee();
        float health = victim.getHealth();
        var wall = boss.blockPosition().offset(1, 0, 0);
        for (int y = 0; y < 12; y++) helper.getLevel().setBlockAndUpdate(wall.above(y), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
        boss.getSensing().tick();
        helper.assertTrue(boss.isWithinMeleeAttackRange(victim) && !boss.getSensing().hasLineOfSight(victim),
                "fixture obstructs visibility while retaining native melee reach: reach="
                        + boss.isWithinMeleeAttackRange(victim) + " visible=" + boss.getSensing().hasLineOfSight(victim));
        helper.runAfterDelay(9, () -> {
            ClockworkBossMelee.tick(boss, state);
            helper.assertTrue(state.pendingMelee() == pending && victim.getHealth() == health,
                    "temporary loss of visibility does not restart wind-up or cause early damage");
        });
        helper.runAfterDelay(10, () -> {
            try {
                ClockworkBossMelee.tick(boss, state);
                helper.assertTrue(state.pendingMelee() == null && victim.getHealth() == health,
                        "wall at impact rejects damage and releases pending state");
                for (int y = 0; y < 12; y++) helper.getLevel().setBlockAndUpdate(wall.above(y), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
                boss.getSensing().tick();
                ClockworkBossMelee.tick(boss, state);
                helper.assertTrue(victim.getHealth() == health, "removing the wall cannot revive the missed swing");
                helper.succeed();
            } finally { boss.discard(); victim.discard(); }
        });
    }

    /** Native Brain probes exercise the exact calls Lithium optimizes. Recovery
     * must block both starts and running ticks while expiring native memories. */
    // Start after the older fixtures' immediate global reloads, which deliberately
    // reset every encounter's subphase/deadline and would invalidate this timing test.
    @GameTest(maxTicks = 25, setupTicks = 10) public void compatibility_recovery_brain_and_timers(GameTestHelper helper) throws Exception {
        JsonObject config = config(1000);
        var children = new JsonObject();
        var recovery = new JsonObject(); recovery.addProperty("type", "recovery"); recovery.addProperty("duration_ticks", 6);
        var attack = new JsonObject(); attack.addProperty("type", "attack"); attack.addProperty("attacks_before_next", 3);
        children.add("rest", recovery); children.add("fight", attack);
        phase(config, ClockworkBossPhase.AGITATED).add("subphases", children);
        phase(config, ClockworkBossPhase.AGITATED).addProperty("roar_between_subphases", false);
        Warden boss = boss(helper, config, "compat_recovery");
        boss.setNoAi(true);
        var brain = boss.getBrain();
        if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("lithium"))
            helper.assertTrue(java.util.Arrays.stream(net.minecraft.world.entity.ai.Brain.class.getDeclaredFields())
                    .anyMatch(field -> field.getName().equals("runningTasks")), "Lithium's optimized task cache is actually active");
        var running = new BrainProbe(); var waiting = new BrainProbe();
        waiting.allowStart = false;
        Warden vanilla = EntityTypes.WARDEN.create(helper.getLevel(), EntitySpawnReason.COMMAND);
        vanilla.removeFreeWill();
        var ordinary = new BrainProbe();
        vanilla.getBrain().addActivity(net.minecraft.world.entity.schedule.Activity.CORE,
                com.google.common.collect.ImmutableList.of(com.mojang.datafixers.util.Pair.of(0, ordinary)), java.util.Set.of(), java.util.Set.of());
        vanilla.getBrain().setCoreActivities(java.util.Set.of(net.minecraft.world.entity.schedule.Activity.CORE));
        vanilla.getBrain().tick(helper.getLevel(), vanilla);
        helper.assertTrue(ordinary.starts > 0 && ordinary.ticks > 0, "unmarked Warden behavior is unaffected");
        brain.addActivity(net.minecraft.world.entity.schedule.Activity.CORE,
                com.google.common.collect.ImmutableList.of(com.mojang.datafixers.util.Pair.of(0, running), com.mojang.datafixers.util.Pair.of(1, waiting)),
                java.util.Set.of(), java.util.Set.of());
        brain.setCoreActivities(java.util.Set.of(net.minecraft.world.entity.schedule.Activity.CORE));
        // Use Brain's actual scheduler: Lithium maintains a running-task cache
        // there, so calling Behavior.tryStart directly is not a valid integration fixture.
        brain.tick(helper.getLevel(), boss);
        int beforeTicks = running.ticks;
        helper.assertTrue(running.starts > 0 && beforeTicks > 0, "native scheduler starts the initial probe");
        boss.setHealth(749);
        ClockworkBossManager.prepareCombatPhase(boss);
        var state = ClockworkBossManager.stateFor(boss);
        helper.assertTrue(ClockworkBossManager.isRecovering(boss), "entered timed recovery");
        waiting.allowStart = true;
        brain.setMemoryWithExpiry(MemoryModuleType.SONIC_BOOM_COOLDOWN, net.minecraft.util.Unit.INSTANCE, 2);
        brain.tick(helper.getLevel(), boss); brain.tick(helper.getLevel(), boss); brain.tick(helper.getLevel(), boss);
        helper.assertTrue(running.ticks == beforeTicks && waiting.starts == 0, "recovery stops prior work and blocks new starts");
        helper.assertTrue(!brain.hasMemoryValue(MemoryModuleType.SONIC_BOOM_COOLDOWN), "native memory expiration continues during recovery");
        helper.runAfterDelay(7, () -> {
            try {
                ClockworkBossManager.tickClockworkBossLogic(boss);
                helper.assertTrue(!state.recoverySubphase() && state.subphase().key().equals("fight"), "recovery advances after its deadline: "
                        + diagnostics(boss) + " child=" + state.subphase().key() + " due=" + state.recoveryDeadline() + " now=" + helper.getLevel().getGameTime());
                brain.tick(helper.getLevel(), boss);
                helper.assertTrue(running.ticks > beforeTicks && waiting.starts > 0, "native behaviors resume after recovery: running=" + running.ticks + " before=" + beforeTicks + " waiting=" + waiting.starts);
                helper.succeed();
            } finally { boss.discard(); }
        });
    }
    private static final class BrainProbe extends net.minecraft.world.entity.ai.behavior.Behavior<Warden> {
        int starts, ticks;
        boolean allowStart = true;
        BrainProbe() { super(java.util.Map.of(), 40); }
        @Override protected void start(ServerLevel level, Warden boss, long now) { starts++; }
        @Override protected void tick(ServerLevel level, Warden boss, long now) { ticks++; }
        @Override protected boolean canStillUse(ServerLevel level, Warden boss, long now) { return true; }
        @Override protected boolean checkExtraStartConditions(ServerLevel level, Warden boss) { return allowStart; }
    }

    /** Inventory/owner/cooldown invariants use native players and items. This
     * simulates interrupted drinking, duplicate bottles and external armor math;
     * it is not a Better Combat/Combat Roll/Universal Graves integration run. */
    @GameTest(maxTicks = 20, setupTicks = 55) public void compatibility_potion_inventory_and_external_health(GameTestHelper helper) throws Exception {
        JsonObject config = config(1000);
        var area = config.getAsJsonObject("boss_bar").getAsJsonObject("visibility_area");
        area.addProperty("shape", "radius"); area.addProperty("radius", 8); area.addProperty("dimension", "");
        Warden boss = boss(helper, config, "compat_inventory", true); boss.setNoAi(true);
        var fixture = new PlayerFixture(helper, boss.position().add(2, 0, 0));
        var other = new PlayerFixture(helper, boss.position().add(3, 0, 0));
        var player = fixture.player;
        var original = personalTotem();
        // Stackable-totem inventory hooks can consume the input object while
        // moving its contents. Keep immutable expected contents for assertions.
        var expectedOriginal = original.copy();
        var policy = ClockworkBossManager.stateFor(boss).playerSupport();
        try {
            ClockworkBossDiagnostics.clear();
            boolean forbidden = false;
            try { player.level().getServer().getCommands().getDispatcher().execute("boss debug on", player.createCommandSourceStack()); }
            catch (com.mojang.brigadier.exceptions.CommandSyntaxException denied) { forbidden = true; }
            helper.assertTrue(forbidden && !ClockworkBossDiagnostics.enabled(), "non-operator cannot enable diagnostics");
            player.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND, original);
            for (int i = 0; i < 36; i++) player.getInventory().setItem(i, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE, 64));
            ClockworkBossFightPotion.enter(boss.getUUID(), player, policy.potion());
            helper.assertTrue(sameContents(player.getOffhandItem(), expectedOriginal), "full inventory preserves original offhand");
            player.getInventory().setItem(0, net.minecraft.world.item.ItemStack.EMPTY);
            ClockworkBossFightPotion.leave(boss.getUUID(), player.getUUID());
            ClockworkBossFightPotion.enter(boss.getUUID(), player, policy.potion());
            helper.assertTrue(sameTransferredContents(player.getInventory().getItem(0), expectedOriginal) && ClockworkBossFightPotion.isPotion(player.getOffhandItem()),
                    "free slot preserves original contents and offhand receives the potion");
            ClockworkBossPlayerHealth.enter(boss.getUUID(), player, policy.health(), true);
            player.setHealth(40);
            player.startUsingItem(net.minecraft.world.InteractionHand.OFF_HAND); player.stopUsingItem();
            helper.assertTrue(player.getHealth() == 40 && ((ClockworkBossPlayerData) player).clockworkBosses$potionReadyTick() == 0, "interruption grants no dose or cooldown");
            var duplicate = player.getOffhandItem().copy();
            player.startUsingItem(net.minecraft.world.InteractionHand.OFF_HAND); ClockworkBossFightPotion.finish(player); player.stopUsingItem();
            long ready = ((ClockworkBossPlayerData) player).clockworkBosses$potionReadyTick();
            helper.assertTrue(player.getHealth() == 60 && ready > player.level().getServer().overworld().getGameTime(), "one validated dose heals and claims a persistent deadline");
            player.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND, duplicate);
            player.startUsingItem(net.minecraft.world.InteractionHand.OFF_HAND); ClockworkBossFightPotion.finish(player); player.stopUsingItem();
            helper.assertTrue(player.getHealth() == 60 && ((ClockworkBossPlayerData) player).clockworkBosses$potionReadyTick() == ready, "duplicate cannot grant a second dose");
            other.player.setHealth(10); other.player.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND, duplicate.copy());
            other.player.startUsingItem(net.minecraft.world.InteractionHand.OFF_HAND); ClockworkBossFightPotion.finish(other.player); other.player.stopUsingItem();
            helper.assertTrue(other.player.getHealth() == 10, "wrong owner cannot use a stored/transferred bottle");
            var external = new AttributeModifier(Identifier.fromNamespaceAndPath("clockwork_bosses_regression", "external_armor"), 80, AttributeModifier.Operation.ADD_VALUE);
            player.getAttribute(Attributes.MAX_HEALTH).addTransientModifier(external);
            ClockworkBossPlayerHealth.attributeChanged(player);
            helper.assertTrue(player.getMaxHealth() == 100, "external armor replaces the floor rather than stacking extra health");
            ClockworkBossPlayerHealth.releaseBoss(boss.getUUID());
            helper.assertTrue(player.getMaxHealth() == 100 && player.getAttribute(Attributes.MAX_HEALTH).hasModifier(external.id()), "arena exit preserves the external modifier");
            ClockworkBossFightPotion.releaseBoss(boss.getUUID());
            helper.assertTrue(sameTransferredContents(player.getOffhandItem(), expectedOriginal) && player.getInventory().getItem(0).isEmpty(), "cleanup restores original contents once and removes copied bottles");
            helper.succeed();
        } finally { ClockworkBossDiagnostics.clear(); boss.discard(); fixture.close(); other.close(); }
    }
    /** Exercise actual transformed constructors and native save/load, including
     * an empty ledger. Startup alone never creates or saves an encounter entity. */
    @GameTest public void first_heal_ledger_native_creation_and_persistence(GameTestHelper helper) {
        Warden fresh = EntityTypes.WARDEN.create(helper.getLevel(), EntitySpawnReason.COMMAND);
        var marker = (ClockworkBossMarker) fresh;
        marker.clockworkBosses$setClockworkBoss(true);
        var empty = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, helper.getLevel().registryAccess());
        helper.assertTrue(fresh.save(empty), "fresh marked Warden can save before anyone enters");
        UUID player = UUID.randomUUID();
        helper.assertTrue(marker.clockworkBosses$claimInitialHeal(player), "first entry claims a heal");
        helper.assertTrue(!marker.clockworkBosses$claimInitialHeal(player), "second entry cannot claim another heal");
        var output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, helper.getLevel().registryAccess());
        helper.assertTrue(fresh.save(output), "marked Warden can save a populated ledger");
        Warden restored = EntityTypes.WARDEN.create(helper.getLevel(), EntitySpawnReason.COMMAND);
        restored.load(TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), output.buildResult()));
        var loaded = (ClockworkBossMarker) restored;
        helper.assertTrue(!loaded.clockworkBosses$claimInitialHeal(player), "native load retains the once-only heal");
        helper.assertTrue(loaded.clockworkBosses$claimInitialHeal(UUID.randomUUID()), "late arrival still claims a heal after load");
        restored.load(TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), empty.buildResult()));
        helper.assertTrue(loaded.clockworkBosses$claimInitialHeal(player), "loading an empty ledger clears previous members safely");
        helper.succeed();
    }

    /** Real native player membership, inventory and cooldown data: reproduce
     * the failing first entry, then cover exit/reentry and the reusable dose. */
    // The short rotation fixture removes its players by tick three. Delay this
    // fixture so its participant cannot become a legitimate extra target there.
    @GameTest(maxTicks = 80, setupTicks = 5) public void player_support_entry_exit_potion_and_save(GameTestHelper helper) throws Exception {
        JsonObject config = config(1000);
        var area = config.getAsJsonObject("boss_bar").getAsJsonObject("visibility_area");
        area.addProperty("shape", "radius"); area.addProperty("radius", 8); area.addProperty("dimension", "");
        Warden boss = boss(helper, config, "player_support", true);
        boss.setNoAi(true); // Keep this fixture about membership, not combat damage.
        var participant = new PlayerFixture(helper, boss.position().add(2, 0, 0));
        var player = participant.player;
        var originalOffhand = personalTotem();
        var expectedOffhand = originalOffhand.copy();
        player.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND, originalOffhand);
        Runnable cleanup = () -> { boss.discard(); participant.close(); };
        helper.runAtTickTime(12, () -> {
            try {
                helper.assertTrue(player.getMaxHealth() == 100 && player.getHealth() == 100, "native arena entry grants floor and initial heal");
                helper.assertTrue(!((ClockworkBossMarker) boss).clockworkBosses$claimInitialHeal(player.getUUID()), "entry recorded the participant in the saved ledger");
                helper.assertTrue(ClockworkBossFightPotion.isPotion(player.getOffhandItem()), "offhand received the reusable bottle");
                player.setHealth(50);
                player.snapTo(boss.position().add(200, 0, 0));
            } catch (Throwable failure) { cleanup.run(); throw failure; }
        });
        helper.runAtTickTime(24, () -> {
            try {
                helper.assertTrue(player.getMaxHealth() == 20 && Math.abs(player.getHealth() - 10) < .01, "exit preserves half health at native max");
                helper.assertTrue(sameTransferredContents(player.getOffhandItem(), expectedOffhand) && player.getInventory().getItem(0).isEmpty(),
                        "untouched offhand contents return once and displaced slot empties");
                player.snapTo(boss.position().add(2, 0, 0));
            } catch (Throwable failure) { cleanup.run(); throw failure; }
        });
        helper.runAtTickTime(36, () -> {
            try {
                helper.assertTrue(player.getMaxHealth() == 100 && Math.abs(player.getHealth() - 50) < .01, "reentry preserves health and cannot repeat the free heal");
                player.getFoodData().setFoodLevel(0); player.getFoodData().setSaturation(0);
                player.startUsingItem(net.minecraft.world.InteractionHand.OFF_HAND);
                ClockworkBossFightPotion.finish(player);
                helper.assertTrue(Math.abs(player.getHealth() - 70) < .01, "one dose adds 20 HP");
                helper.assertTrue(player.getFoodData().getFoodLevel() == 20 && player.getFoodData().getSaturationLevel() == 20, "one dose fills hunger and saturation");
                helper.assertTrue(((ClockworkBossPlayerData) player).clockworkBosses$potionReadyTick()
                        == helper.getLevel().getServer().overworld().getGameTime() + 300, "dose saves the 15-second shared cooldown");
                ClockworkBossFightPotion.finish(player);
                helper.assertTrue(Math.abs(player.getHealth() - 70) < .01, "repeated completion cannot bypass the cooldown");
                player.stopUsingItem();
                var output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, helper.getLevel().registryAccess());
                helper.assertTrue(boss.save(output), "boss saves successfully after participant entry");
                Warden restored = EntityTypes.WARDEN.create(helper.getLevel(), EntitySpawnReason.COMMAND);
                restored.load(TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), output.buildResult()));
                helper.assertTrue(!((ClockworkBossMarker) restored).clockworkBosses$claimInitialHeal(player.getUUID()), "real participant ledger survives save/load");
                ClockworkBossPlayerHealth.releaseBoss(boss.getUUID());
                ClockworkBossFightPotion.releaseBoss(boss.getUUID());
                helper.assertTrue(player.getMaxHealth() == 20 && Math.abs(player.getHealth() - 14) < .01, "cleanup removes only the owned floor proportionally");
                helper.assertTrue(sameTransferredContents(player.getOffhandItem(), expectedOffhand) && player.getInventory().getItem(0).isEmpty(), "cleanup restores the original offhand contents once");
                helper.succeed();
            } finally { cleanup.run(); }
        });
    }

    /** Real native players, target assignments, completed swings and packet recipients; no production debug hooks. */
    @GameTest(maxTicks = 80)
    public void target_modes_titles_and_rapid_beams(GameTestHelper helper) throws Exception {
        JsonObject config = config(1000);
        config.getAsJsonObject("targeting").addProperty("switch_chance", 1);
        // This existing rapid-rotation fixture intentionally opts out of the new
        // hold gate/short attack budget to keep exercising direct native events.
        config.getAsJsonObject("targeting").addProperty("min_target_ticks", 0);
        config.getAsJsonObject("targeting").addProperty("max_attacks", 1000);
        config.getAsJsonObject("targeting").addProperty("mode", "nearest");
        config.getAsJsonObject("targeting").getAsJsonObject("announcement").addProperty("enabled", true);
        var visibility = config.getAsJsonObject("boss_bar").getAsJsonObject("visibility_area");
        visibility.addProperty("shape", "radius"); visibility.addProperty("radius", 16); visibility.addProperty("dimension", "");
        for (ClockworkBossPhase phase : ClockworkBossPhase.values()) {
            phase(config, phase).getAsJsonObject("melee").addProperty("damage", 0);
            phase(config, phase).getAsJsonObject("melee").addProperty("knockback", 0);
        }
        Warden boss = boss(helper, config, "target_modes");
        var state = ClockworkBossManager.stateFor(boss);
        var one = new PlayerFixture(helper, boss.position().add(2, 0, 0));
        var two = new PlayerFixture(helper, boss.position().add(4, 0, 0));
        var three = new PlayerFixture(helper, boss.position().add(6, 0, 0));
        // Native section indexing observes these newly joined/teleported players
        // on the next world tick. Attacking before that queries their old sections.
        helper.runAfterDelay(1, () -> {
        Warden second = null;
        try {
            for (var p : new PlayerFixture[]{one, two, three}) {
                state.bossBar().addPlayer(p.player);
            }
            boss.setAttackTarget(one.player);
            helper.assertTrue(one.listener.titles.size() == 1 && two.listener.titles.size() == 1
                    && three.listener.titles.size() == 1, "one initial target title to encounter audience");
            helper.assertTrue(one.listener.titles.getFirst().equals("RUN, " + one.player.getGameProfile().name() + "!"), "actual target account name");
            boss.setAttackTarget(one.player);
            helper.assertTrue(one.listener.titles.size() == 1, "same native target assignment stays silent");
            config.getAsJsonObject("targeting").addProperty("switch_chance", 0);
            state.setConfig(config);
            ClockworkBossTargets.afterAttack(boss, state, one.player);
            helper.assertTrue(boss.getTarget() == one.player, "never chance retains current target");
            config.getAsJsonObject("targeting").addProperty("switch_chance", 1);
            visibility.addProperty("dimension", "invalid dimension!");
            state.setConfig(config);
            ClockworkBossTargets.afterAttack(boss, state, one.player);
            helper.assertTrue(boss.getTarget() == one.player, "invalid configured region does not broaden candidate pool");
            visibility.addProperty("dimension", "");
            state.setConfig(config);
            // 26.3 separates damageCooldownTime from invulnerableTime. The
            // former rejects this zero-damage repeat while retaining a target;
            // either temporary or permanent invulnerability hides the player
            // from getTargetFromBrain and cannot model a blocked swing.
            one.player.setInvulnerableTime(0);
            one.player.damageCooldownTime = 20;
            helper.assertTrue(boss.getTarget() == one.player, "blocked swing begins with a valid native opponent");
            helper.assertTrue(!boss.doHurtTarget(helper.getLevel(), one.player), "hurt immunity gives a completed blocked attempt");
            helper.assertTrue(boss.getTarget() == two.player, "completed swing chooses nearest alternative: " + diagnostics(boss)
                    + " policy=" + state.combatSettings().targeting());
            long cooldown = boss.getBrain().getTimeUntilExpiry(MemoryModuleType.SONIC_BOOM_COOLDOWN);
            helper.assertTrue(cooldown == 40, "rotation preserves native post-melee delay instead of adding 200 ticks");
            config.getAsJsonObject("targeting").addProperty("mode", "farthest");
            state.setConfig(config);
            ClockworkBossTargets.afterAttack(boss, state, two.player);
            helper.assertTrue(boss.getTarget() == three.player, "farthest alternative is deterministic");
            helper.assertTrue(boss.getBrain().getTimeUntilExpiry(MemoryModuleType.SONIC_BOOM_COOLDOWN) == cooldown, "cooldown unchanged on direct rotation");
            helper.assertTrue(one.listener.titles.size() == 1 && three.listener.titles.size() == 2, "viewers stay throttled; a new target receives a personal warning");
            second = boss(helper, config.deepCopy(), "target_second");
            second.setAttackTarget(two.player);
            helper.assertTrue(two.listener.titles.size() == 2, "second boss shares the personal-warning recipient throttle");

            // A late native metadata snapshot does not depend on the entity's local age.
            state.startSonicCharge(three.player.getUUID(), helper.getLevel().getGameTime(), state.settings().phase(state.currentPhase()));
            var marker = (ClockworkBossMarker) boss;
            marker.clockworkBosses$beginSonicVisual(helper.getLevel().getGameTime(), 100);
            Warden late = EntityTypes.WARDEN.create(helper.getLevel(), EntitySpawnReason.COMMAND);
            late.getEntityData().assignValues(boss.getEntityData().getNonDefaultValues());
            helper.assertTrue(((ClockworkBossMarker) late).clockworkBosses$sonicVisualMillis(helper.getLevel().getGameTime() + 50, 0) == 850,
                    "late tracker samples captured midpoint");
            state.setConfig(config.deepCopy());
            helper.assertTrue(ClockworkBossChargeVisual.decode(marker.clockworkBosses$getSonicVisual()).duration() == 100, "reload retains captured visual duration");
            boss.setAttackTarget(one.player);
            helper.assertTrue(state.sonicCharge() == null && marker.clockworkBosses$getSonicVisual().isEmpty(), "external retarget cancels charge and visual immediately");

            two.player.setGameMode(net.minecraft.world.level.GameType.SPECTATOR);
            three.player.setGameMode(net.minecraft.world.level.GameType.SPECTATOR);
            ClockworkBossTargets.afterAttack(boss, state, one.player);
            helper.assertTrue(boss.getTarget() == one.player, "solo/filtered pool retains current target");
            two.player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
            for (ClockworkBossPhase phase : ClockworkBossPhase.values()) {
                var section = phase(config, phase);
                section.getAsJsonObject("melee").addProperty("enabled", false);
                var beam = section.getAsJsonObject("sonic_boom");
                beam.addProperty("charge_time_ticks", 0); beam.addProperty("cooldown_ticks", 1);
                beam.addProperty("recovery_time_ticks", 0);
                beam.addProperty("damage", 0); beam.addProperty("knockback_horizontal", 0);
                beam.addProperty("knockback_vertical", 0); beam.addProperty("requires_line_of_sight", false);
            }
            config.getAsJsonObject("targeting").addProperty("mode", "random");
            // Other native fixtures exercise reload-all concurrently. Publish
            // this scenario's settings so those reloads cannot restore the earlier windup.
            ClockworkBossTemplateStore.save(state.templateName(), config);
            state.setConfig(config);
            ClockworkBossManager.prepareCombatPhase(boss);
            boss.getBrain().eraseMemory(MemoryModuleType.SONIC_BOOM_COOLDOWN);
            ClockworkBossSonicBoom.tick(boss, state);
            helper.assertTrue(state.sonicCharge() == null && boss.getTarget() == two.player, "zero windup fires and rotates to sole alternative");
            var first = ClockworkBossChargeVisual.decode(marker.clockworkBosses$getSonicVisual());
            helper.assertTrue(first.duration() == 0 && first.firedTick() >= 0, "instant shot stores fired visual without dividing by zero");
            ClockworkBossSonicBoom.tick(boss, state);
            helper.assertTrue(ClockworkBossChargeVisual.decode(marker.clockworkBosses$getSonicVisual()).sequence() == first.sequence(), "cooldown prevents same-tick duplicate shot");
            // Freeze automatic entity AI while the fixture waits. The callback
            // advances native memory explicitly, then resumes one controlled shot.
            boss.setNoAi(true);
            Warden finalSecond = second;
            helper.runAfterDelay(2, () -> {
                try {
                    boss.getBrain().tick(helper.getLevel(), boss);
                    boss.getBrain().tick(helper.getLevel(), boss);
                    helper.assertTrue(!boss.getBrain().hasMemoryValue(MemoryModuleType.SONIC_BOOM_COOLDOWN), "native one-tick cooldown has expired");
                    boss.setNoAi(false);
                    ClockworkBossSonicBoom.tick(boss, state);
                    var restarted = ClockworkBossChargeVisual.decode(marker.clockworkBosses$getSonicVisual());
                    helper.assertTrue(restarted.sequence() != first.sequence() && boss.getTarget() == one.player,
                            "rapid second beam uses new timing without mandatory recoil delay: " + diagnostics(boss) + " visual=" + restarted);
                    helper.assertTrue(one.listener.titles.size() == 1, "rapid beams keep shared title limit");
                    helper.succeed();
                } finally { boss.discard(); finalSecond.discard(); one.close(); two.close(); three.close(); }
            });
        } catch (Throwable failure) {
            boss.discard(); if (second != null) second.discard(); one.close(); two.close(); three.close();
            if (failure instanceof RuntimeException runtime) throw runtime;
            if (failure instanceof Error error) throw error;
            throw new RuntimeException(failure);
        }
        });
    }

    /** Old entity keys opt into the health extension before native load and are rewritten only on native save. */
    @GameTest public void backward_saved_boss_keys(GameTestHelper helper) throws Exception {
        Warden boss = boss(helper, config(3000), "old_keys");
        try {
            boss.setHealth(2250);
            var output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, helper.getLevel().registryAccess());
            boss.save(output);
            var saved = output.buildResult();
            for (String key : java.util.List.copyOf(saved.keySet())) {
                if (!key.startsWith("ClockworkBosses_")) continue;
                String suffix = key.substring("ClockworkBosses_".length());
                saved.put("ArenaBossFight_" + (suffix.equals("Boss") ? "ArenaBoss" : suffix), saved.get(key).copy());
                saved.remove(key);
            }
            Warden restored = EntityTypes.WARDEN.create(helper.getLevel(), EntitySpawnReason.COMMAND);
            restored.load(TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), saved));
            var marker = (ClockworkBossMarker) restored;
            helper.assertTrue(marker.clockworkBosses$isClockworkBoss() && restored.getMaxHealth() == 3000 && restored.getHealth() == 2250, "old boss health survives native parent load");
            helper.assertTrue(marker.clockworkBosses$getTemplate().equals(((ClockworkBossMarker) boss).clockworkBosses$getTemplate()), "old template identity retained");
            var next = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, helper.getLevel().registryAccess());
            restored.save(next);
            helper.assertTrue(next.buildResult().getBooleanOr("ClockworkBosses_Boss", false)
                    && next.buildResult().keySet().stream().noneMatch(k -> k.startsWith("ArenaBossFight_")), "native save emits only new owned keys");
            saved.putBoolean("ArenaBossFight_EventWarden", true); saved.remove("ArenaBossFight_ArenaBoss");
            restored.load(TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), saved));
            helper.assertTrue(marker.clockworkBosses$isClockworkBoss(), "oldest EventWarden marker still loads");
        } finally { boss.discard(); }
        helper.succeed();
    }

    /** Embedded native connections exercise eligibility; the development listener records just real title packets. */
    /** A native inventory integration may replace objects while keeping every
     * component/count. Assert the ownership invariant rather than Java identity. */
    private static boolean sameContents(net.minecraft.world.item.ItemStack first, net.minecraft.world.item.ItemStack second) {
        return first.getCount() == second.getCount()
                && net.minecraft.world.item.ItemStack.isSameItemSameComponents(first, second);
    }

    /** This pack's inventory_changed advancement clears capacity-1 totems and
     * gives capacity-64 replacements. Test personal-item ownership with its valid
     * configured totem, so fixture setup does not trigger an unrelated item rewrite. */
    private static net.minecraft.world.item.ItemStack personalTotem() {
        var stack = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.TOTEM_OF_UNDYING);
        if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("mr_stackable_totems"))
            stack.set(net.minecraft.core.component.DataComponents.MAX_STACK_SIZE, 64);
        return stack;
    }

    /** The supplied stackable-totems pack legitimately changes only capacity
     * while a totem is stored. Ignore that one configured capacity change in
     * transfer assertions; count and every other effective component must match.
     * Production uses the post-transfer snapshot and preserves the live capacity. */
    private static boolean sameTransferredContents(net.minecraft.world.item.ItemStack first, net.minecraft.world.item.ItemStack second) {
        if (first.isEmpty() || second.isEmpty() || first.getCount() != second.getCount()) return false;
        var normalized = first.copy();
        normalized.set(net.minecraft.core.component.DataComponents.MAX_STACK_SIZE, second.getMaxStackSize());
        return sameContents(normalized, second);
    }

    /** Exercise the real remove command, including bottles moved out of the
     * offhand and overlapping grants. Removal must release only its encounter;
     * the final encounter restores the personal offhand and removes all copies. */
    @GameTest(maxTicks = 20, setupTicks = 60)
    public void remove_command_cleans_fight_potion(GameTestHelper helper) throws Exception {
        var fixture = new PlayerFixture(helper, helper.absoluteVec(new Vec3(1, 2, 1)));
        var player = fixture.player;
        Warden first = boss(helper, ClockworkBossTemplateDocument.defaults(), "potion_remove_a");
        Warden second = boss(helper, ClockworkBossTemplateDocument.defaults(), "potion_remove_b");
        var policy = new ClockworkBossPlayerSupport.Potion(true, 20, 300, 20, 20);
        var original = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.PAPER, 7);
        try {
            player.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND, original.copy());
            ClockworkBossFightPotion.enter(first.getUUID(), player, policy);
            ClockworkBossFightPotion.enter(second.getUUID(), player, policy);
            helper.assertTrue(ClockworkBossFightPotion.isPotion(player.getOffhandItem()), "grant placed a fight potion in offhand");
            helper.assertTrue(ClockworkBossManager.remove(source(helper), ((ClockworkBossMarker) first).clockworkBosses$getName()) == 1,
                    "first remove command succeeds");
            helper.assertTrue(ClockworkBossFightPotion.isPotion(player.getOffhandItem()), "another encounter retains its potion grant");
            var orphan = player.getOffhandItem().copy();
            player.getInventory().setItem(1, player.getOffhandItem().copy());
            player.containerMenu.setCarried(player.getOffhandItem().copy());
            int updatesBefore = fixture.listener.fullInventoryUpdates;
            helper.assertTrue(ClockworkBossManager.remove(source(helper), ((ClockworkBossMarker) second).clockworkBosses$getName()) == 1,
                    "final remove command succeeds");
            helper.assertTrue(sameTransferredContents(player.getOffhandItem(), original), "final removal restores personal offhand contents");
            helper.assertTrue(player.containerMenu.getCarried().isEmpty(), "final removal clears cursor bottle");
            helper.assertTrue(fixture.listener.fullInventoryUpdates > updatesBefore, "removal sends authoritative full inventory for stale client caches");
            for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++)
                helper.assertTrue(!ClockworkBossFightPotion.isPotion(player.getInventory().getItem(slot)), "final removal clears marked inventory bottles");
            // A creative copy or restored inventory can reintroduce a bottle
            // after its membership ends. A later explicit removal must clear it
            // without altering the player's unrelated personal items.
            player.getInventory().setItem(2, player.getOffhandItem());
            player.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND, orphan);
            Warden third = boss(helper, ClockworkBossTemplateDocument.defaults(), "potion_remove_orphan");
            try {
                helper.assertTrue(ClockworkBossManager.remove(source(helper), ((ClockworkBossMarker) third).clockworkBosses$getName()) == 1, "orphan cleanup command succeeds");
                helper.assertTrue(player.getOffhandItem().isEmpty() && sameTransferredContents(player.getInventory().getItem(2), original), "orphan bottle removed; personal item preserved");
            } finally { third.discard(); }
            helper.succeed();
        } finally {
            ClockworkBossFightPotion.releaseBoss(first.getUUID());
            ClockworkBossFightPotion.releaseBoss(second.getUUID());
            first.discard(); second.discard(); fixture.close();
        }
    }

    /** Reproduce death -> grave -> encounter removal -> respawn -> reclaim.
     * The supplied Universal Graves jar is optional: inspect/reclaim through its
     * public API only in this development fixture, never in the release mod.
     * Without it, exercise the same native death path and ordinary item drops. */
    @GameTest(maxTicks = 20, setupTicks = 60)
    public void potion_death_before_grave_and_late_reclaim(GameTestHelper helper) throws Exception {
        var fixture = new PlayerFixture(helper, helper.absoluteVec(new Vec3(1, 2, 1)));
        fixture.acknowledgeLoaded();
        var player = fixture.player;
        Warden boss = boss(helper, ClockworkBossTemplateDocument.defaults(), "potion_grave");
        var original = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.PAPER, 7);
        original.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                net.minecraft.network.chat.Component.literal("grave_test_personal_offhand"));
        var ordinary = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.POTION);
        ordinary.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                net.minecraft.network.chat.Component.literal("grave_test_ordinary_potion"));
        Runnable cleanup = () -> { ClockworkBossFightPotion.releaseBoss(boss.getUUID()); boss.discard(); fixture.close(); };
        var rules = helper.getLevel().getGameRules();
        boolean keep = rules.get(net.minecraft.world.level.gamerules.GameRules.KEEP_INVENTORY);
        try {
            player.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND, original.copy());
            player.getInventory().setItem(9, ordinary.copy());
            ClockworkBossFightPotion.enter(boss.getUUID(), player, new ClockworkBossPlayerSupport.Potion(true, 20, 300, 20, 20));
            helper.assertTrue(ClockworkBossFightPotion.isPotion(player.getOffhandItem()), "death fixture starts with fight bottle");
            player.getInventory().setItem(1, player.getOffhandItem().copy());
            player.containerMenu.setCarried(player.getOffhandItem().copy());
            // Native respawn must stay in the loaded fixture chunk. A remote
            // world spawn starts unrelated chunk generation just before this
            // short-lived GameTest server shuts down; it is not needed to test
            // COPY_FROM or recovering a grave after the encounter has ended.
            player.setRespawnPosition(new net.minecraft.server.level.ServerPlayer.RespawnConfig(
                    net.minecraft.world.level.storage.LevelData.RespawnData.of(helper.getLevel().dimension(),
                            helper.absolutePos(new net.minecraft.core.BlockPos(6, 2, 6)), 0, 0), true), false);
            player.setInvulnerableTime(0); player.damageCooldownTime = 0;
            rules.set(net.minecraft.world.level.gamerules.GameRules.KEEP_INVENTORY, false, helper.getLevel().getServer());
            player.hurtServer(helper.getLevel(), player.damageSources().generic(), 1000);
            helper.assertTrue(!player.isAlive(), "native fatal damage commits death");
        } catch (Throwable failure) { cleanup.run(); throw failure; }
        finally { rules.set(net.minecraft.world.level.gamerules.GameRules.KEEP_INVENTORY, keep, helper.getLevel().getServer()); }
        // Graves places its captured inventory through a scheduled server task.
        // Wait for that test-only boundary; gameplay adds no timer or polling.
        helper.runAfterDelay(2, () -> {
            Object grave = null;
            try {
                boolean graves = net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("universal-graves");
                if (graves) {
                    var captured = (java.util.Collection<?>) Class.forName("eu.pb4.graves.GravesApi")
                            .getMethod("getGravesOf", UUID.class).invoke(null, player.getUUID());
                    helper.assertTrue(captured.size() == 1, "Universal Graves captured one actual grave");
                    grave = captured.iterator().next();
                    var contents = (java.util.List<?>) grave.getClass().getMethod("getItems").invoke(grave);
                    int paper = 0, potions = 0;
                    for (Object entry : contents) {
                        var stack = (net.minecraft.world.item.ItemStack) entry.getClass().getMethod("stack").invoke(entry);
                        helper.assertTrue(!ClockworkBossFightPotion.isPotion(stack), "actual grave never captures a temporary fight bottle");
                        if (net.minecraft.world.item.ItemStack.isSameItemSameComponents(stack, original)) paper += stack.getCount();
                        if (net.minecraft.world.item.ItemStack.isSameItemSameComponents(stack, ordinary)) potions += stack.getCount();
                    }
                    helper.assertTrue(paper == 7 && potions == 1, "grave preserves personal items and ordinary potions exactly once");
                }
                helper.assertTrue(ClockworkBossManager.remove(source(helper), ((ClockworkBossMarker) boss).clockworkBosses$getName()) == 1,
                        "encounter ends before player reclaims their items");
                var restored = fixture.respawn();
                helper.assertTrue(restored.level() == helper.getLevel()
                        && restored.distanceToSqr(helper.absoluteVec(new Vec3(6, 2, 6))) < 9,
                        "native respawn uses the separate loaded checkpoint, not the occupied grave position");
                restored.snapTo(helper.absoluteVec(new Vec3(1, 2, 1)));
                if (grave != null) {
                    grave.getClass().getMethod("quickEquip", net.minecraft.server.level.ServerPlayer.class).invoke(grave, restored);
                    helper.assertTrue(sameTransferredContents(restored.getOffhandItem(), original), "grave restores personal offhand to its original slot");
                } else {
                    for (var drop : helper.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                            restored.getBoundingBox().inflate(4))) {
                        drop.setNoPickUpDelay(); drop.playerTouch(restored);
                    }
                }
                int paper = 0, potions = 0;
                for (int slot = 0; slot < restored.getInventory().getContainerSize(); slot++) {
                    var stack = restored.getInventory().getItem(slot);
                    helper.assertTrue(!ClockworkBossFightPotion.isPotion(stack), "late reclaim leaves no fight potion after encounter end");
                    if (net.minecraft.world.item.ItemStack.isSameItemSameComponents(stack, original)) paper += stack.getCount();
                    if (net.minecraft.world.item.ItemStack.isSameItemSameComponents(stack, ordinary)) potions += stack.getCount();
                }
                helper.assertTrue(paper == 7 && potions == 1, "late reclaim preserves all personal items without duplicates");
                helper.succeed();
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Could not exercise the supplied Universal Graves API", failure);
            } finally {
                // The fixture owns this grave as well as its fake player. Do
                // not leave a retained empty grave in the disposable pack run
                // when its server setting keeps monuments after item recovery.
                try {
                    if (grave != null && !(Boolean) grave.getClass().getMethod("isRemoved").invoke(grave))
                        grave.getClass().getMethod("destroyGrave", net.minecraft.server.MinecraftServer.class,
                                net.minecraft.world.entity.player.Player.class).invoke(grave, helper.getLevel().getServer(), fixture.player);
                } catch (ReflectiveOperationException failure) {
                    throw new IllegalStateException("Could not clean up the test grave", failure);
                } finally { cleanup.run(); }
            }
        });
    }

    /** Old graves/container copies may already hold a marked potion. The first
     * attempted use outside membership removes it/copies from this player's
     * inventory only, with a full native refresh and no ordinary potion loss. */
    @GameTest(setupTicks = 60)
    public void potion_old_grave_copy_cleans_on_use(GameTestHelper helper) {
        var fixture = new PlayerFixture(helper, helper.absoluteVec(new Vec3(1, 2, 1)));
        var player = fixture.player;
        UUID grant = UUID.randomUUID();
        var original = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.PAPER, 7);
        try {
            player.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND, original.copy());
            ClockworkBossFightPotion.enter(grant, player, new ClockworkBossPlayerSupport.Potion(true, 20, 300, 20, 20));
            var saved = player.getOffhandItem().copy();
            ClockworkBossFightPotion.releaseBoss(grant);
            player.getInventory().setItem(2, player.getOffhandItem());
            player.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND, saved.copy());
            player.getInventory().setItem(1, saved.copy());
            player.containerMenu.setCarried(saved.copy());
            player.getInventory().setItem(9, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.POTION));
            int updates = fixture.listener.fullInventoryUpdates;
            var result = net.fabricmc.fabric.api.event.player.UseItemCallback.EVENT.invoker()
                    .interact(player, helper.getLevel(), net.minecraft.world.InteractionHand.OFF_HAND);
            helper.assertTrue(result == net.minecraft.world.InteractionResult.FAIL, "old bottle cannot be used after grant ends");
            helper.assertTrue(player.getOffhandItem().isEmpty() && player.containerMenu.getCarried().isEmpty()
                    && player.getInventory().getItem(1).isEmpty(), "attempted use clears old bottle and all held copies");
            helper.assertTrue(sameTransferredContents(player.getInventory().getItem(2), original)
                    && player.getInventory().getItem(9).is(net.minecraft.world.item.Items.POTION), "orphan cleanup preserves personal items and normal potions");
            helper.assertTrue(fixture.listener.fullInventoryUpdates > updates, "old bottle removal sends authoritative inventory state");
            helper.succeed();
        } finally { ClockworkBossFightPotion.releaseBoss(grant); fixture.close(); }
    }

    /** Canceled fatal damage and a successful vanilla totem are not deaths.
     * Removing a bottle during ALLOW_DEATH would break both of these paths. */
    @GameTest(setupTicks = 60)
    public void potion_survives_cancelled_death_and_totem(GameTestHelper helper) {
        var fixture = new PlayerFixture(helper, helper.absoluteVec(new Vec3(1, 2, 1)));
        fixture.acknowledgeLoaded();
        var player = fixture.player;
        UUID grant = UUID.randomUUID();
        if (!deathProtectionRegistered) {
            deathProtectionRegistered = true;
            net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
                if (!entity.getUUID().equals(preventedDeath)) return true;
                entity.setHealth(1); return false;
            });
        }
        try {
            ClockworkBossFightPotion.enter(grant, player, new ClockworkBossPlayerSupport.Potion(true, 20, 300, 20, 20));
            preventedDeath = player.getUUID();
            player.setInvulnerableTime(0); player.damageCooldownTime = 0;
            player.hurtServer(helper.getLevel(), player.damageSources().generic(), 1000);
            helper.assertTrue(player.getHealth() == 1 && player.isAlive() && ClockworkBossFightPotion.isPotion(player.getOffhandItem()), "a death veto retains the fight potion");
            preventedDeath = null;
            player.setHealth(20); player.setInvulnerableTime(0); player.damageCooldownTime = 0;
            player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,
                    new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.TOTEM_OF_UNDYING));
            player.hurtServer(helper.getLevel(), player.damageSources().generic(), 1000);
            helper.assertTrue(player.getHealth() < 20 && player.isAlive() && ClockworkBossFightPotion.isPotion(player.getOffhandItem()), "native totem survival retains the fight potion");
            helper.succeed();
        } finally { preventedDeath = null; ClockworkBossFightPotion.releaseBoss(grant); fixture.close(); }
    }

    /** keepInventory still ends this player's grant on real death, without
     * losing their own offhand item or touching an unmarked vanilla potion. */
    @GameTest(setupTicks = 60)
    public void potion_death_with_keep_inventory(GameTestHelper helper) {
        var fixture = new PlayerFixture(helper, helper.absoluteVec(new Vec3(1, 2, 1)));
        fixture.acknowledgeLoaded();
        var player = fixture.player;
        UUID grant = UUID.randomUUID();
        var rules = helper.getLevel().getGameRules();
        boolean keep = rules.get(net.minecraft.world.level.gamerules.GameRules.KEEP_INVENTORY);
        var original = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.PAPER, 7);
        try {
            player.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND, original.copy());
            player.getInventory().setItem(9, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.POTION));
            ClockworkBossFightPotion.enter(grant, player, new ClockworkBossPlayerSupport.Potion(true, 20, 300, 20, 20));
            player.setInvulnerableTime(0); player.damageCooldownTime = 0;
            rules.set(net.minecraft.world.level.gamerules.GameRules.KEEP_INVENTORY, true, helper.getLevel().getServer());
            player.hurtServer(helper.getLevel(), player.damageSources().generic(), 1000);
            helper.assertTrue(!player.isAlive() && sameTransferredContents(player.getOffhandItem(), original), "committed death restores personal offhand with keepInventory");
            helper.assertTrue(player.getInventory().getItem(9).is(net.minecraft.world.item.Items.POTION)
                    && !ClockworkBossFightPotion.isPotion(player.getInventory().getItem(9)), "ordinary potion is retained");
            for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++)
                helper.assertTrue(!ClockworkBossFightPotion.isPotion(player.getInventory().getItem(slot)), "keepInventory retains no fight bottle");
            helper.succeed();
        } finally {
            rules.set(net.minecraft.world.level.gamerules.GameRules.KEEP_INVENTORY, keep, helper.getLevel().getServer());
            ClockworkBossFightPotion.releaseBoss(grant); fixture.close();
        }
    }

    /** Copies preserve ownership, but changed/moved items and a newly occupied
     * offhand must not be overwritten. Transfers remove the live source stack. */
    @GameTest(setupTicks = 60) public void potion_copied_inventory_ownership(GameTestHelper helper) {
        var fixture = new PlayerFixture(helper, helper.absoluteVec(new Vec3(1, 2, 1)));
        var player = fixture.player;
        UUID owner = UUID.randomUUID();
        var policy = new ClockworkBossPlayerSupport.Potion(true, 20, 300, 20, 20);
        var original = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.PAPER, 7);
        try {
            player.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND, original.copy());
            ClockworkBossFightPotion.enter(owner, player, policy);
            var copied = player.getInventory().getItem(0).copy();
            copied.set(net.minecraft.core.component.DataComponents.MAX_STACK_SIZE, 32);
            player.getInventory().setItem(0, copied);
            ClockworkBossFightPotion.releaseBoss(owner);
            helper.assertTrue(sameTransferredContents(player.getOffhandItem(), original)
                            && player.getOffhandItem().getMaxStackSize() == 32 && player.getInventory().getItem(0).isEmpty(),
                    "copied stack transfers back once with its live capacity, leaving source empty");

            ClockworkBossFightPotion.enter(owner, player, policy);
            player.getInventory().getItem(0).set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                    net.minecraft.network.chat.Component.literal("changed by another mod"));
            ClockworkBossFightPotion.releaseBoss(owner);
            helper.assertTrue(player.getOffhandItem().isEmpty() && player.getInventory().getItem(0).getCount() == 7,
                    "changed components stay in inventory and are not replaced by a snapshot");

            player.getInventory().setItem(0, net.minecraft.world.item.ItemStack.EMPTY);
            player.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND, original.copy());
            ClockworkBossFightPotion.enter(owner, player, policy);
            player.getInventory().getItem(0).setCount(6);
            ClockworkBossFightPotion.releaseBoss(owner);
            helper.assertTrue(player.getOffhandItem().isEmpty() && player.getInventory().getItem(0).getCount() == 6,
                    "changed count stays untouched and is never restored from the seven-item snapshot");

            player.getInventory().setItem(0, net.minecraft.world.item.ItemStack.EMPTY);
            player.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND, original.copy());
            ClockworkBossFightPotion.enter(owner, player, policy);
            player.getInventory().setItem(1, player.getInventory().removeItemNoUpdate(0));
            ClockworkBossFightPotion.releaseBoss(owner);
            helper.assertTrue(player.getOffhandItem().isEmpty() && sameContents(player.getInventory().getItem(1), original),
                    "moved original remains in its new slot without being duplicated");

            player.getInventory().setItem(1, net.minecraft.world.item.ItemStack.EMPTY);
            player.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND, original.copy());
            ClockworkBossFightPotion.enter(owner, player, policy);
            player.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND,
                    new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND));
            ClockworkBossFightPotion.releaseBoss(owner);
            helper.assertTrue(player.getOffhandItem().is(net.minecraft.world.item.Items.DIAMOND)
                            && sameContents(player.getInventory().getItem(0), original),
                    "new personal offhand is preserved and the displaced original stays in inventory");

            // An incoming ordinary totem may be rewritten by the pack's
            // clear/give rule. Its eventual slot is controlled by that rule,
            // but our transfer/cleanup must not create or lose any totems.
            player.getInventory().setItem(0, net.minecraft.world.item.ItemStack.EMPTY);
            player.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND,
                    new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.TOTEM_OF_UNDYING));
            ClockworkBossFightPotion.enter(owner, player, policy);
            ClockworkBossFightPotion.releaseBoss(owner);
            int totems = 0;
            for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
                var stack = player.getInventory().getItem(i);
                if (stack.is(net.minecraft.world.item.Items.TOTEM_OF_UNDYING)) totems += stack.getCount();
                helper.assertTrue(!ClockworkBossFightPotion.isPotion(stack), "cleanup leaves no extra marked bottles");
            }
            helper.assertTrue(totems == 1, "ordinary totem conversion preserves exactly one personal item");
            helper.succeed();
        } finally { ClockworkBossFightPotion.releaseBoss(owner); fixture.close(); }
    }

    static final class PlayerFixture implements AutoCloseable {
        net.minecraft.server.level.ServerPlayer player;
        final RecordingListener listener;
        final io.netty.channel.embedded.EmbeddedChannel channel;
        PlayerFixture(GameTestHelper helper, Vec3 position) {
            var profile = new com.mojang.authlib.GameProfile(UUID.randomUUID(), "cw_test_" + UUID.randomUUID().toString().substring(0, 4));
            loadFixturePermissions(profile);
            player = new net.minecraft.server.level.ServerPlayer(helper.getLevel().getServer(), helper.getLevel(),
                    profile, net.minecraft.server.level.ClientInformation.createDefault());
            var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
            channel = new io.netty.channel.embedded.EmbeddedChannel(connection);
            declareFixtureChannels(connection);
            helper.getLevel().getServer().getPlayerList().placeNewPlayer(connection, player,
                    net.minecraft.server.network.CommonListenerCookie.createInitial(profile, false));
            // Each listener consumes the negotiated pending set. The recording
            // listener replaces the native one, so supply the same fixture state.
            declareFixtureChannels(connection);
            listener = new RecordingListener(helper.getLevel().getServer(), connection, player);
            player.connection = listener;
            player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
            player.getAbilities().invulnerable = false;
            player.snapTo(position);
        }
        /** The pack requires CCA's entity-sync channel and disconnects clients
         * without it. An embedded fixture has no configuration/network handshake;
         * seed the exact pending channel state Fabric consumes at listener setup.
         * This models a matching client for server logic only, not packet decoding
         * or evidence that a real unmodified client can connect to this pack. */
        private static void declareFixtureChannels(net.minecraft.network.Connection connection) {
            if (net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("cardinal-components-entity")) {
                ((net.fabricmc.fabric.impl.networking.ChannelInfoHolder) connection)
                        .fabric_getPendingChannelsNames(net.minecraft.network.ConnectionProtocol.PLAY)
                        .add(Identifier.fromNamespaceAndPath("cardinal-components", "entity_sync"));
            }
        }
        /** Embedded connections skip the network pre-login stage. LuckPerms
         * normally loads each user's permissions there and correctly rejects
         * an unloaded user on join. Load through its public API only when this
         * optional integration is present; do not disable its login checks.
         * Reflection keeps this development fixture free of release dependencies.
         * The bounded wait belongs only to test setup, never gameplay code. */
        private static void loadFixturePermissions(com.mojang.authlib.GameProfile profile) {
            if (!net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("luckperms")) return;
            try {
                var provider = Class.forName("net.luckperms.api.LuckPermsProvider");
                var api = Class.forName("net.luckperms.api.LuckPerms");
                Object manager = api.getMethod("getUserManager").invoke(provider.getMethod("get").invoke(null));
                var users = Class.forName("net.luckperms.api.model.user.UserManager");
                var future = (java.util.concurrent.CompletableFuture<?>) users.getMethod("loadUser", UUID.class, String.class)
                        .invoke(manager, profile.id(), profile.name());
                future.get(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception failure) {
                throw new IllegalStateException("Could not preload LuckPerms for the embedded test player", failure);
            }
        }
        /** Native respawn replaces the server entity and fires COPY_FROM;
         * close must subsequently release the replacement, not the dead entity. */
        net.minecraft.server.level.ServerPlayer respawn() {
            player = player.level().getServer().getPlayerList().respawn(player, false, net.minecraft.world.entity.Entity.RemovalReason.KILLED);
            acknowledgeLoaded();
            return player;
        }
        /** An embedded connection has no client to acknowledge the 26.3 world
         * load. The real native handler unlocks damage; setting HP alone would
         * otherwise test a player whose fatal damage is silently rejected. */
        void acknowledgeLoaded() {
            player.connection.handleAcceptPlayerLoad(new net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket());
        }
        @Override public void close() { player.level().getServer().getPlayerList().remove(player); channel.finishAndReleaseAll(); }
    }
    private static final class RecordingListener extends net.minecraft.server.network.ServerGamePacketListenerImpl {
        final java.util.List<String> titles = new java.util.ArrayList<>();
        int fullInventoryUpdates;
        RecordingListener(net.minecraft.server.MinecraftServer server, net.minecraft.network.Connection connection,
                          net.minecraft.server.level.ServerPlayer player) {
            super(server, connection, player, net.minecraft.server.network.CommonListenerCookie.createInitial(player.getGameProfile(), false));
        }
        @Override public void send(net.minecraft.network.protocol.Packet<?> packet) {
            if (titles != null && packet instanceof net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket title) titles.add(title.text().getString());
            if (packet instanceof net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket) fullInventoryUpdates++;
        }
    }
    @GameTest public void health_presets_and_native_scope(GameTestHelper helper) throws Exception {
        ServerLevel level = helper.getLevel();
        for (int players = 1; players <= 6; players++) {
            String preset = players == 1 ? "1_player" : players + "_players";
            JsonObject config;
            try (var stream = getClass().getResourceAsStream("/presets/" + preset + ".json")) {
                config = ClockworkBossTemplateDocument.read(new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8));
            }
            Warden boss = boss(helper, config, "health_" + players);
            float expected = 1000 + (players - 1) * 400;
            helper.assertTrue(boss.getMaxHealth() == expected && boss.getHealth() == expected, "preset effective HP " + players);
            helper.assertTrue(ClockworkBossManager.stateFor(boss).bossBar().getProgress() == 1, "full native boss bar");
            boss.discard();
        }
        // The global definition, an unrelated attribute instance, and a vanilla
        // Warden must retain their own ranges even after marked bosses existed.
        AttributeInstance unrelated = new AttributeInstance(Attributes.MAX_HEALTH, ignored -> {});
        unrelated.setBaseValue(3000);
        helper.assertTrue(unrelated.getValue() == 1024, "unrelated health instance unchanged");
        Warden vanilla = EntityTypes.WARDEN.create(level, EntitySpawnReason.COMMAND);
        vanilla.getAttribute(Attributes.MAX_HEALTH).setBaseValue(3000);
        helper.assertTrue(vanilla.getMaxHealth() == 1024, "unmarked Warden unchanged");
        helper.assertTrue(Attributes.MAX_HEALTH.value().sanitizeValue(3000) == 1024, "global native range unchanged");
        helper.succeed();
    }

    @GameTest public void health_damage_healing_reload_and_persistence(GameTestHelper helper) throws Exception {
        JsonObject config = config(3000);
        Warden boss = boss(helper, config, "health_saved");
        try {
            float before = boss.getHealth();
            helper.assertTrue(boss.hurtServer(helper.getLevel(), helper.getLevel().damageSources().generic(), 100), "native damage accepted");
            helper.assertTrue(boss.getHealth() == before - 100, "one native damage pool");
            boss.heal(40);
            helper.assertTrue(boss.getHealth() == 2940, "native healing beyond 1024");
            boss.setHealth(2250);
            ClockworkBossManager.tickClockworkBossLogic(boss);
            helper.assertTrue(ClockworkBossManager.stateFor(boss).currentPhase() == ClockworkBossPhase.AGITATED, "75 percent phase");
            // Bar membership/progress normally reconcile on the existing watcher;
            // reload provides its explicit immediate update without inventing a new tick loop.
            helper.assertTrue(ClockworkBossManager.reload(source(helper)) == 1, "immediate reload bar reconciliation");
            helper.assertTrue(ClockworkBossManager.stateFor(boss).bossBar().getProgress() == .75F, "75 percent bar");
            var output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, helper.getLevel().registryAccess());
            helper.assertTrue(boss.save(output), "native entity save");
            Warden restored = EntityTypes.WARDEN.create(helper.getLevel(), EntitySpawnReason.COMMAND);
            restored.load(TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), output.buildResult()));
            helper.assertTrue(restored.getMaxHealth() == 3000 && restored.getHealth() == 2250, "native save/load does not clamp HP");
            var modifier = new AttributeModifier(Identifier.fromNamespaceAndPath("clockwork_bosses_regression", "extra_health"),
                    500, AttributeModifier.Operation.ADD_VALUE);
            boss.getAttribute(Attributes.MAX_HEALTH).addTransientModifier(modifier);
            helper.assertTrue(boss.getMaxHealth() == 3500, "native modifier math retained");
            boss.getAttribute(Attributes.MAX_HEALTH).removeModifier(modifier.id());
            config.addProperty("health", 1400);
            ClockworkBossTemplateStore.save("regression_health_saved", config);
            helper.assertTrue(ClockworkBossManager.reload(source(helper)) == 1, "template reload succeeded");
            helper.assertTrue(boss.getMaxHealth() == 1400 && boss.getHealth() == 1050, "reload preserves native HP fraction");
            helper.assertTrue(ClockworkBossManager.stateFor(boss).currentPhase() == ClockworkBossPhase.AGITATED, "reload phase consistent");
        } finally { boss.discard(); }
        helper.succeed();
    }

    @GameTest public void health_bounds_and_backward_phases(GameTestHelper helper) throws Exception {
        for (int max : new int[]{1024, 1025, 1_000_000}) {
            Warden boss = boss(helper, config(max), "boundary_" + max);
            try {
                helper.assertTrue(boss.getMaxHealth() == max, "health bound " + max);
                for (float fraction : new float[]{.75F, .5F, .25F, .5F, .75F, 1}) {
                    boss.setHealth(max * fraction);
                    ClockworkBossManager.prepareCombatPhase(boss);
                    ClockworkBossPhase expected = fraction > .75F ? ClockworkBossPhase.ANNOYED
                            : fraction > .5F ? ClockworkBossPhase.AGITATED : fraction > .25F ? ClockworkBossPhase.ENRAGED : ClockworkBossPhase.FURY;
                    helper.assertTrue(ClockworkBossManager.stateFor(boss).currentPhase() == expected, "downward/backward phase " + fraction);
                }
                boss.setHealth(Float.MAX_VALUE);
                helper.assertTrue(boss.getHealth() == max, "native setHealth upper clamp");
                boss.getAttribute(Attributes.MAX_HEALTH).setBaseValue(Double.POSITIVE_INFINITY);
                helper.assertTrue(boss.getMaxHealth() == 1_000_000, "computed attribute upper clamp");
            } finally { boss.discard(); }
        }
        helper.succeed();
    }

    @GameTest public void melee_changes_before_native_hit(GameTestHelper helper) throws Exception {
        JsonObject config = config(3000);
        for (ClockworkBossPhase phase : ClockworkBossPhase.values()) {
            var melee = phase(config, phase).getAsJsonObject("melee");
            melee.addProperty("enabled", phase != ClockworkBossPhase.ANNOYED);
            melee.addProperty("damage", phase == ClockworkBossPhase.AGITATED ? 10 : phase == ClockworkBossPhase.ENRAGED ? 15 : 20);
            melee.addProperty("cooldown_ticks", phase == ClockworkBossPhase.ENRAGED ? 5 : 40);
            melee.addProperty("knockback", 0);
        }
        Warden boss = boss(helper, config, "melee");
        var target = helper.spawn(EntityTypes.COW, new Vec3(4, 2, 4));
        target.setNoAi(true);
        target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200);
        target.setHealth(200);
        target.setInvulnerableTime(0);
        target.getAttribute(Attributes.KNOCKBACK_RESISTANCE).setBaseValue(1);
        try {
            helper.assertTrue(!boss.doHurtTarget(helper.getLevel(), target), "annoyed melee disabled");
            boss.setHealth(2250); // No intervening manager/AI step: reproduce the old ordering bug.
            float before = target.getHealth();
            helper.assertTrue(boss.doHurtTarget(helper.getLevel(), target), "new phase permission: " + diagnostics(boss)
                    + " targetHP=" + target.getHealth() + " targetInvulnerable=" + target.isPermanentlyInvulnerable()
                    + " attackDamage=" + boss.getAttributeValue(Attributes.ATTACK_DAMAGE)
                    + " targetCooldown=" + target.damageCooldownTime + " immunity="
                    + target.isInvulnerableTo(helper.getLevel(), helper.getLevel().damageSources().mobAttack(boss)));
            helper.assertTrue(before - target.getHealth() == 10, "same phase native damage");
            helper.assertTrue(boss.getAttributeValue(Attributes.ATTACK_DAMAGE) == 10, "phase attributes already committed");
            helper.assertTrue(!boss.doHurtTarget(helper.getLevel(), target), "same-tick duplicate blocked");
            boss.setHealth(1500);
            var selected = ClockworkBossManager.prepareCombatPhase(boss);
            helper.assertTrue(selected.meleeCooldown() == 5 && boss.getAttributeValue(Attributes.ATTACK_DAMAGE) == 15,
                    "new phase cooldown and damage agree");
            boss.heal(1500);
            helper.assertTrue(!boss.doHurtTarget(helper.getLevel(), target), "healing backwards disables next melee");
            helper.assertTrue(ClockworkBossManager.stateFor(boss).currentPhase() == ClockworkBossPhase.ANNOYED, "backward phase committed");
        } finally { boss.discard(); target.discard(); }
        helper.succeed();
    }

    @GameTest public void sonic_midcharge_phase_heal_and_reload(GameTestHelper helper) throws Exception {
        JsonObject config = config(3000);
        for (ClockworkBossPhase phase : ClockworkBossPhase.values()) {
            phase(config, phase).getAsJsonObject("melee").addProperty("enabled", false);
            var sonic = phase(config, phase).getAsJsonObject("sonic_boom");
            sonic.addProperty("enabled", true);
            sonic.addProperty("damage", phase == ClockworkBossPhase.AGITATED ? 6 : 8);
            sonic.addProperty("charge_time_ticks", phase == ClockworkBossPhase.AGITATED ? 20 : 40);
            sonic.addProperty("cooldown_ticks", 30);
            sonic.addProperty("requires_line_of_sight", false);
            sonic.addProperty("knockback_horizontal", 0);
            sonic.addProperty("knockback_vertical", 0);
        }
        Warden boss = boss(helper, config, "sonic");
        // Vanilla Wardens cannot target another Warden; use a native eligible mob.
        var target = helper.spawn(EntityTypes.COW, new Vec3(5, 2, 5));
        target.setNoAi(true);
        target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200);
        target.setHealth(200);
        target.setInvulnerableTime(0);
        ClockworkBossState state = ClockworkBossManager.stateFor(boss);
        try {
            boss.setAttackTarget(target);
            boss.getBrain().eraseMemory(MemoryModuleType.SONIC_BOOM_COOLDOWN);
            boss.setHealth(2250);
            ClockworkBossManager.prepareCombatPhase(boss);
            ClockworkBossSonicBoom.tick(boss, state);
            ClockworkBossSonicCharge original = state.sonicCharge();
            helper.assertTrue(original != null && original.settings().damage() == 6, "agitated charge starts: " + diagnostics(boss)
                    + " target=" + boss.getTarget() + " eligible=" + boss.canTargetEntity(target));
            boss.setHealth(1500);
            ClockworkBossManager.prepareCombatPhase(boss);
            helper.assertTrue(state.sonicCharge() == null && target.getHealth() == 200,
                    "phase change interrupts the old action without landing its beam");
            ClockworkBossSonicBoom.tick(boss, state);
            var replacement = state.sonicCharge();
            helper.assertTrue(replacement != null && replacement != original && replacement.settings().damage() == 8,
                    "new phase starts its own beam with new settings");
            // The uninterrupted replacement retains its complete start-time
            // definition. Synthetic time avoids waiting forty world ticks here;
            // the next test separately proves the real replacement deadline.
            state.startSonicCharge(target.getUUID(), helper.getLevel().getGameTime() - 40,
                    new ClockworkBossSettings.Phase(false, 40, replacement.settings(), state.settings().phase(state.currentPhase()).darkness(), state.settings().phase(state.currentPhase()).targeting()));
            float before = target.getHealth();
            ClockworkBossSonicBoom.tick(boss, state);
            helper.assertTrue(before - target.getHealth() == 8 && state.sonicCharge() == null, "replacement charge lands its captured damage once");
            ClockworkBossSonicBoom.tick(boss, state);
            helper.assertTrue(target.getHealth() == before - 8, "no duplicate beam");
            boss.getBrain().eraseMemory(MemoryModuleType.SONIC_BOOM_COOLDOWN);
            ClockworkBossSonicBoom.tick(boss, state);
            ClockworkBossSonicCharge enraged = state.sonicCharge();
            helper.assertTrue(enraged != null && enraged.settings().damage() == 8 && enraged.settings().chargeTime() == 40,
                    "subsequent beam uses new definition");
            boss.heal(1500);
            ClockworkBossManager.prepareCombatPhase(boss);
            helper.assertTrue(state.sonicCharge() == null, "healing into a previous phase also interrupts the action");
            ClockworkBossSonicBoom.tick(boss, state);
            helper.assertTrue(state.sonicCharge() != null, "previous phase can start a fresh charge");
            for (ClockworkBossPhase phase : ClockworkBossPhase.values()) phase(config, phase).getAsJsonObject("sonic_boom").addProperty("enabled", false);
            ClockworkBossTemplateStore.save("regression_sonic", config);
            helper.assertTrue(ClockworkBossManager.reload(source(helper)) == 1, "reload while charging");
            ClockworkBossSonicBoom.tick(boss, state);
            helper.assertTrue(state.sonicCharge() == null && ((ClockworkBossMarker) boss).clockworkBosses$getSonicVisual().isEmpty(),
                    "reload interrupts pending damage and clears its visual");
            ClockworkBossSonicBoom.tick(boss, state);
            helper.assertTrue(target.getHealth() == before - 8 && state.sonicCharge() == null,
                    "disabled reload cannot land the interrupted beam");
            boss.getBrain().eraseMemory(MemoryModuleType.SONIC_BOOM_COOLDOWN);
            ClockworkBossSonicBoom.tick(boss, state);
            helper.assertTrue(state.sonicCharge() == null, "disabled next beam stays disabled");
        } finally { boss.discard(); target.discard(); }
        helper.succeed();
    }

    /** Real elapsed ticks prove an API interruption cancels the old deadline,
     * and the next action receives its full wind-up. No combat code is replaced. */
    @GameTest(maxTicks = 80) public void sonic_real_deadline_and_target_switch(GameTestHelper helper) throws Exception {
        JsonObject config = config(3000);
        // Isolate cancellation after an actual assignment from the deliberate target hold policy.
        config.getAsJsonObject("targeting").addProperty("min_target_ticks", 0);
        for (ClockworkBossPhase phase : ClockworkBossPhase.values()) {
            phase(config, phase).getAsJsonObject("melee").addProperty("enabled", false);
            var sonic = phase(config, phase).getAsJsonObject("sonic_boom");
            sonic.addProperty("damage", phase == ClockworkBossPhase.AGITATED ? 6 : 8);
            sonic.addProperty("charge_time_ticks", phase == ClockworkBossPhase.AGITATED ? 20 : 40);
            sonic.addProperty("requires_line_of_sight", false);
            sonic.addProperty("knockback_horizontal", 0);
            sonic.addProperty("knockback_vertical", 0);
        }
        Warden boss = boss(helper, config, "sonic_timed");
        var target = helper.spawn(EntityTypes.COW, new Vec3(5, 2, 5));
        var other = helper.spawn(EntityTypes.COW, new Vec3(6, 2, 5));
        for (var cow : new net.minecraft.world.entity.animal.cow.Cow[]{target, other}) {
            cow.setNoAi(true);
            cow.setNoGravity(true);
            cow.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200);
            cow.setHealth(200);
            cow.setInvulnerableTime(0);
        }
        // Freeze Brain.tick so this fixture invokes the production beam exactly
        // at each scheduled observation; world time still advances normally.
        boss.setNoAi(true);
        boss.setAttackTarget(target);
        boss.getBrain().eraseMemory(MemoryModuleType.SONIC_BOOM_COOLDOWN);
        boss.setHealth(2250);
        ClockworkBossManager.prepareCombatPhase(boss);
        var state = ClockworkBossManager.stateFor(boss);
        ClockworkBossSonicBoom.tick(boss, state);
        var original = state.sonicCharge();
        var replacement = new ClockworkBossSonicCharge[1];
        helper.assertTrue(original != null, "timed charge starts");
        helper.runAfterDelay(10, () -> {
            boss.setHealth(1500);
            ClockworkBossManager.prepareCombatPhase(boss);
            ClockworkBossSonicBoom.tick(boss, state);
            replacement[0] = state.sonicCharge();
            helper.assertTrue(replacement[0] != null && replacement[0] != original, "midcharge threshold starts a distinct action charge");
        });
        helper.runAfterDelay(19, () -> {
            ClockworkBossSonicBoom.tick(boss, state);
            helper.assertTrue(target.getHealth() == 200 && state.sonicCharge() == replacement[0], "replacement still charging before old deadline");
        });
        helper.runAfterDelay(20, () -> {
            ClockworkBossSonicBoom.tick(boss, state);
            helper.assertTrue(target.getHealth() == 200 && state.sonicCharge() == replacement[0], "interrupted beam cannot fire at its old deadline");
        });
        helper.runAfterDelay(49, () -> {
            ClockworkBossSonicBoom.tick(boss, state);
            helper.assertTrue(target.getHealth() == 200 && state.sonicCharge() == replacement[0], "replacement cannot fire one tick early");
        });
        helper.runAfterDelay(50, () -> {
            ClockworkBossSonicBoom.tick(boss, state);
            helper.assertTrue(target.getHealth() == 192 && state.sonicCharge() == null, "replacement fires after its complete forty-tick wind-up");
            ClockworkBossSonicBoom.tick(boss, state);
            helper.assertTrue(target.getHealth() == 192 && state.sonicCharge() == null, "native cooldown prevents duplicates");
            boss.getBrain().eraseMemory(MemoryModuleType.SONIC_BOOM_COOLDOWN);
            ClockworkBossSonicBoom.tick(boss, state);
            helper.assertTrue(state.sonicCharge().settings().chargeTime() == 40, "next beam gets new charge delay");
            boss.setAttackTarget(other);
            ClockworkBossSonicBoom.tick(boss, state);
            helper.assertTrue(state.sonicCharge() == null && other.getHealth() == 200, "target switch cancels pending beam");
            boss.getBrain().eraseMemory(MemoryModuleType.SONIC_BOOM_COOLDOWN);
            ClockworkBossSonicBoom.tick(boss, state);
            other.discard();
            ClockworkBossSonicBoom.tick(boss, state);
            helper.assertTrue(state.sonicCharge() == null, "removed target cancels beam");
            boss.setAttackTarget(target);
            boss.getBrain().eraseMemory(MemoryModuleType.SONIC_BOOM_COOLDOWN);
            ClockworkBossSonicBoom.tick(boss, state);
            helper.assertTrue(state.sonicCharge() != null, "charge exists before boss unload");
            boss.discard();
            helper.assertTrue(state.sonicCharge() == null && ClockworkBossManager.stateFor(boss) == null,
                    "boss unload releases pending attack and runtime ownership");
            target.discard();
            helper.succeed();
        });
    }

    /** Each hit captures its own cooldown; phase changes affect the next hit's damage and future cooldown. */
    @GameTest(maxTicks = 80) public void melee_real_cooldown_after_threshold(GameTestHelper helper) throws Exception {
        JsonObject config = config(3000);
        for (ClockworkBossPhase phase : ClockworkBossPhase.values()) {
            var melee = phase(config, phase).getAsJsonObject("melee");
            melee.addProperty("enabled", true);
            melee.addProperty("damage", phase == ClockworkBossPhase.ENRAGED ? 15 : 10);
            melee.addProperty("cooldown_ticks", phase == ClockworkBossPhase.ENRAGED ? 5 : 40);
            melee.addProperty("knockback", 0);
        }
        Warden boss = boss(helper, config, "melee_timed");
        var target = helper.spawn(EntityTypes.COW, new Vec3(4, 2, 4));
        target.setNoAi(true);
        target.setNoGravity(true);
        target.getAttribute(Attributes.MAX_HEALTH).setBaseValue(200);
        target.setHealth(200);
        target.setInvulnerableTime(0);
        boss.setHealth(2250);
        helper.assertTrue(boss.doHurtTarget(helper.getLevel(), target) && target.getHealth() == 190, "initial phase melee hit");
        boss.setHealth(1500);
        helper.runAfterDelay(39, () -> {
            target.setInvulnerableTime(0); // Isolate boss cooldown from native target hurt immunity.
            target.damageCooldownTime = 0;
            helper.assertTrue(!boss.doHurtTarget(helper.getLevel(), target), "captured outgoing cooldown blocks one tick early");
        });
        helper.runAfterDelay(40, () -> {
            target.setInvulnerableTime(0);
            target.damageCooldownTime = 0;
            helper.assertTrue(boss.doHurtTarget(helper.getLevel(), target) && target.getHealth() == 175,
                    "captured deadline expires; new phase supplies hit damage");
            boss.heal(750);
        });
        helper.runAfterDelay(44, () -> {
            target.setInvulnerableTime(0);
            target.damageCooldownTime = 0;
            helper.assertTrue(!boss.doHurtTarget(helper.getLevel(), target), "healing backward retains the preceding five-tick cooldown");
        });
        helper.runAfterDelay(45, () -> {
            target.setInvulnerableTime(0);
            target.damageCooldownTime = 0;
            helper.assertTrue(boss.doHurtTarget(helper.getLevel(), target) && target.getHealth() == 165,
                    "backward phase supplies damage after the captured deadline");
            boss.discard();
            target.discard();
            helper.succeed();
        });
    }

    /** Fixtures use the production spawn/config path inside the isolated run directory. */
    static Warden boss(GameTestHelper helper, JsonObject config, String suffix) throws Exception {
        return boss(helper, config, suffix, false);
    }
    static Warden boss(GameTestHelper helper, JsonObject config, String suffix, boolean playerSupport) throws Exception {
        // Existing combat fixtures opt out so arena overlap cannot grant their
        // mock players health/healing intended only for the support fixture.
        config.getAsJsonObject("boss_bar").getAsJsonObject("player_health").addProperty("enabled", playerSupport);
        config.getAsJsonObject("boss_bar").getAsJsonObject("reusable_potion").addProperty("enabled", playerSupport);
        config.getAsJsonObject("spawn").addProperty("set", false);
        config.addProperty("scale", 1);
        config.getAsJsonObject("voice_lines").addProperty("enabled", false);
        config.getAsJsonObject("boss_bar").getAsJsonObject("area_effects").addProperty("enabled", false);
        config.getAsJsonObject("dormant_darkness").addProperty("enabled", false);
        for (ClockworkBossPhase phase : ClockworkBossPhase.values()) phase(config, phase).getAsJsonObject("darkness").addProperty("enabled", false);
        String template = "regression_" + suffix;
        String name = "regression_" + UUID.randomUUID();
        ClockworkBossTemplateStore.save(template, config);
        helper.assertTrue(ClockworkBossManager.spawn(source(helper), template, name) == 1, "production spawn succeeds");
        for (var entity : helper.getLevel().getAllEntities()) {
            if (entity instanceof Warden warden && ((ClockworkBossMarker) warden).clockworkBosses$getName().equals(name)) {
                var marker = (ClockworkBossMarker) warden;
                marker.clockworkBosses$setDormant(false);
                marker.clockworkBosses$setEmerging(false);
                ClockworkBossManager.stateFor(warden).setDormant(false);
                warden.setNoAi(false);
                warden.setPermanentlyInvulnerable(false);
                warden.setSilent(true);
                // Tests drive attacks explicitly; native pathfinding/other mobs
                // must not introduce a second hit between scheduled assertions.
                warden.removeFreeWill();
                warden.snapTo(helper.absoluteVec(new Vec3(2, 2, 2)));
                ClockworkBossManager.prepareCombatPhase(warden);
                return warden;
            }
        }
        throw new IllegalStateException("Spawned regression boss missing");
    }

    static JsonObject config(double health) {
        JsonObject config = ClockworkBossTemplateDocument.defaults();
        config.addProperty("health", health);
        config.getAsJsonObject("targeting").addProperty("switch_chance", 0);
        config.getAsJsonObject("targeting").getAsJsonObject("announcement").addProperty("enabled", false);
        return config;
    }

    /** Include native locks and compiled values when a combat assertion fails. */
    private static String diagnostics(Warden boss) {
        var state = ClockworkBossManager.stateFor(boss);
        return "HP=" + boss.getHealth() + "/" + boss.getMaxHealth() + " noAI=" + boss.isNoAi()
                + " pose=" + boss.getPose() + " dormant=" + state.dormant() + " emerging=" + state.emerging()
                + " phase=" + state.currentPhase() + " settings=" + state.settings().phase(state.currentPhase());
    }


    private static JsonObject phase(JsonObject config, ClockworkBossPhase phase) {
        return config.getAsJsonObject("phases").getAsJsonObject(phase.name().toLowerCase(java.util.Locale.ROOT));
    }

    static CommandSourceStack source(GameTestHelper helper) {
        return helper.getLevel().getServer().createCommandSourceStack().withLevel(helper.getLevel())
                .withPosition(helper.absoluteVec(new Vec3(2, 2, 2)));
    }
}
