package clockwork.bosses;

import clockwork.bosses.api.*;
import com.google.gson.JsonObject;
import java.util.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Focused native transformed-server API checks. Existing embedded connections
 * exercise server logic only, not human login, client rendering or Better Combat.
 * Event listeners register once and retain only active, finally-cleared probes. */
public final class ClockworkBossApiGameTests {
    private static final Map<UUID, Probe> PROBES = new HashMap<>();
    private static boolean registered;
    public static UUID vetoBoss;
    public static UUID captureFailure;
    private static Probe shutdownProbe;
    private static ClockworkBossCombatGameTests.PlayerFixture shutdownPlayer;
    private static Warden shutdownBoss;
    private static net.minecraft.server.level.ServerLevel shutdownLevel;
    private static boolean shutdownChunkWasForced, ownsShutdownChunk, shutdownChunkRestored;
    private static BossSupportSnapshot shutdownPreStop;
    private static BossVitals shutdownBossPreStop;
    private static boolean shutdownPreStopDisconnected;
    private static ClockworkBossState shutdownInitialState;
    private static boolean shutdownStatePreserved;
    private static int shutdownReadinessChecks;
    private static final class Probe implements AutoCloseable {
        final UUID boss; final List<BossTerminalSnapshot> terminal = new ArrayList<>();
        final List<BossCleanupSnapshot> cleanup = new ArrayList<>();
        final List<BossVitalsChange> changes = new ArrayList<>(); final List<String> order = new ArrayList<>();
        boolean throwObserver; Runnable callback; ServerPlayer player; float endedMax;
        Probe(Warden boss) { register(); this.boss = boss.getUUID(); PROBES.put(this.boss, this); }
        @Override public void close() { PROBES.remove(boss); callback = null; player = null; }
    }
    private static void register() {
        if (registered) return; registered = true;
        BossEncounterEvents.TERMINAL.register(snapshot -> {
            var probe = PROBES.get(snapshot.encounter().bossId());
            if (probe != null && probe.throwObserver) throw new IllegalStateException("intentional API observer failure");
        });
        BossEncounterEvents.TERMINAL.register(snapshot -> {
            var probe = PROBES.get(snapshot.encounter().bossId());
            if (probe != null) { probe.terminal.add(snapshot); probe.order.add("terminal"); }
        });
        BossEncounterEvents.PLAYER_LEFT.register((snapshot, player) -> { var probe = PROBES.get(snapshot.bossId()); if (probe != null) probe.order.add("left"); });
        BossEncounterEvents.ENDED.register((snapshot, reason) -> {
            var probe = PROBES.get(snapshot.bossId());
            if (probe != null) { probe.order.add("ended"); if (probe.player != null) probe.endedMax = probe.player.getMaxHealth(); }
        });
        BossEncounterEvents.CLEANUP_FINISHED.register(snapshot -> {
            var probe = PROBES.get(snapshot.encounter().bossId());
            if (probe != null) { probe.cleanup.add(snapshot); probe.order.add("cleanup"); }
        });
        BossEncounterEvents.VITALS_CHANGED.register(change -> {
            for (var moment : change.encounters()) {
                var probe = PROBES.get(moment.bossId());
                if (probe != null && probe.throwObserver) throw new IllegalStateException("intentional vitals observer failure");
            }
        });
        BossEncounterEvents.VITALS_CHANGED.register(change -> {
            for (var moment : change.encounters()) {
                var probe = PROBES.get(moment.bossId());
                if (probe != null) { probe.changes.add(change); if (probe.callback != null) probe.callback.run(); }
            }
        });
    }
    private static Warden boss(GameTestHelper helper, String suffix) throws Exception {
        return boss(helper, suffix, null);
    }
    private static Warden boss(GameTestHelper helper, String suffix, net.minecraft.world.phys.Vec3 position) throws Exception {
        JsonObject config = ClockworkBossCombatGameTests.config(1000);
        var area = config.getAsJsonObject("boss_bar").getAsJsonObject("visibility_area");
        area.addProperty("shape", "radius"); area.addProperty("radius", 16); area.addProperty("dimension", "");
        config.getAsJsonObject("targeting").addProperty("min_target_ticks", 0);
        var boss = position == null ? ClockworkBossCombatGameTests.boss(helper, config, "api_" + suffix)
                : ClockworkBossCombatGameTests.boss(helper, config, "api_" + suffix, false, false, position);
        boss.setNoAi(true);
        return boss;
    }
    private static void member(Warden boss, ServerPlayer player) {
        var state = ClockworkBossManager.stateFor(boss);
        state.bossBar().addPlayer(player);
        var members = new HashSet<>(state.bossBarPlayerIds()); members.add(player.getUUID()); state.setBossBarPlayerIds(members);
    }
    private static void support(Warden boss, ServerPlayer player) {
        member(boss, player);
        ClockworkBossPlayerHealth.enter(boss.getUUID(), player, new ClockworkBossPlayerSupport.Health(true, 100, false), false);
        ClockworkBossFightPotion.enter(boss.getUUID(), player, new ClockworkBossPlayerSupport.Potion(true, 20, 300, 0, 0));
    }
    @GameTest public void api_terminal_before_restoration_and_legacy_order(GameTestHelper helper) throws Exception {
        var boss = boss(helper, "end");
        try (var fixture = new ClockworkBossCombatGameTests.PlayerFixture(helper, boss.position().add(2, 0, 0)); var probe = new Probe(boss)) {
            var player = fixture.player; fixture.acknowledgeLoaded(); support(boss, player); player.setHealth(50);
            probe.player = player; probe.throwObserver = true;
            boss.discard(); boss.discard();
            helper.assertTrue(probe.terminal.size() == 1 && probe.cleanup.size() == 1, "one terminal/result despite repeat removal and failed observers");
            var terminal = probe.terminal.getFirst(); var cleanup = probe.cleanup.getFirst();
            helper.assertTrue(terminal.players().get(player.getUUID()).health() == 50 && terminal.players().get(player.getUUID()).maxHealth() == 100, "terminal fight vitals before restoration");
            helper.assertTrue(probe.endedMax == 20 && player.getHealth() == 10 && probe.order.equals(List.of("terminal", "left", "ended", "cleanup")), "legacy ENDED remains after restoration/player-left");
            var result = cleanup.players().get(player.getUUID());
            helper.assertTrue(cleanup.encounterClosed() && cleanup.actionClosed() && cleanup.viewersReleased() && cleanup.completedNormally(), "actual closure diagnostics");
            helper.assertTrue(result.before().orElseThrow().health().predictedHealth() == 10 && result.after().orElseThrow().vitals().health() == 10, "prediction and actual separately captured");
            helper.assertTrue(!result.after().orElseThrow().health().ownedModifierPresent() && result.after().orElseThrow().potion().bottles() == 0, "actual temporary state removed");
            player.setHealth(1);
            helper.assertTrue(terminal.players().get(player.getUUID()).health() == 50 && !ClockworkBossVitals.hasTemporaryState(), "retained immutable values and no temporary observer state");
            helper.succeed();
        } finally { boss.discard(); }
    }
    @GameTest public void api_native_damage_absorption_heal_potion_and_phase(GameTestHelper helper) throws Exception {
        var boss = boss(helper, "damage");
        try (var fixture = new ClockworkBossCombatGameTests.PlayerFixture(helper, boss.position().add(2, 0, 0)); var probe = new Probe(boss)) {
            fixture.acknowledgeLoaded(); var player = fixture.player;
            ClockworkBossManager.stateFor(boss).bossBar().addPlayer(player);
            ClockworkBossPlayerHealth.enter(boss.getUUID(), player, new ClockworkBossPlayerSupport.Health(true, 100, false), false);
            helper.assertTrue(probe.changes.stream().anyMatch(c -> c.after().entityId().equals(player.getUUID()) && c.after().maxHealth() == 100),
                    "initial support entry observed before cached viewer UUID diff is committed");
            support(boss, player);
            player.getAttribute(Attributes.MAX_ABSORPTION).setBaseValue(20); player.setAbsorptionAmount(4); player.setHealth(100);
            probe.changes.clear();
            var source = player.damageSources().sonicBoom(boss);
            try (var action = ClockworkBossVitals.action(boss, player, ClockworkBossActions.SONIC, source)) {
                helper.assertTrue(player.hurtServer(helper.getLevel(), source, 10), "native hit accepted");
            }
            double loss = probe.changes.stream().mapToDouble(c -> c.exclusiveHealthChange() + c.exclusiveAbsorptionChange()).sum();
            helper.assertTrue(Math.abs(loss + 10) < .001 && player.getAbsorptionAmount() == 0, "committed HP plus absorption, never nominal-as-health loss");
            helper.assertTrue(probe.changes.stream().anyMatch(c -> c.damage().isPresent() && c.action().map(a -> a.actionId().equals(ClockworkBossActions.SONIC)).orElse(false)), "known source/action copied");
            player.setHealth(95); probe.changes.clear();
            player.startUsingItem(net.minecraft.world.InteractionHand.OFF_HAND); ClockworkBossFightPotion.finish(player); player.stopUsingItem();
            helper.assertTrue(probe.changes.size() == 1 && probe.changes.getFirst().cause() == BossVitalsChange.Cause.FIGHT_POTION
                    && probe.changes.getFirst().requestedHealing().orElseThrow() == 20 && probe.changes.getFirst().exclusiveHealthChange() == 5, "potion actual heal and overheal distinguished");
            probe.changes.clear(); player.heal(20);
            helper.assertTrue(probe.changes.getFirst().exclusiveHealthChange() == 0 && probe.changes.getFirst().requestedHealing().orElseThrow() == 20, "full-health overheal retains unchanged observed values");
            player.setInvulnerableTime(0); player.damageCooldownTime = 0; probe.changes.clear();
            player.hurtServer(helper.getLevel(), player.damageSources().generic(), 1);
            helper.assertTrue(probe.changes.stream().allMatch(c -> c.action().isEmpty() && c.damage().orElseThrow().attacker().isEmpty()), "environmental action/attacker unknown");
            player.setInvulnerableTime(0); player.damageCooldownTime = 0; probe.changes.clear();
            var explicit = player.damageSources().generic();
            try (var action = ClockworkBossVitals.action(boss, player, ClockworkBossActions.MELEE, explicit)) {
                player.hurtServer(helper.getLevel(), explicit, 1);
            }
            helper.assertTrue(probe.changes.stream().anyMatch(c -> c.action().map(a -> a.actionId().equals(ClockworkBossActions.MELEE)).orElse(false)
                    && c.damage().orElseThrow().attacker().isEmpty()), "explicit action call known even with unknown native attacker");
            String phase = ClockworkBossManager.stateFor(boss).currentPhase().key();
            probe.changes.clear(); boss.setHealth(490); ClockworkBossManager.prepareCombatPhase(boss);
            helper.assertTrue(probe.changes.getFirst().encounters().stream().anyMatch(m -> m.bossId().equals(boss.getUUID()) && m.phase().equals(phase)), "phase captured at mutation before later transition");
            helper.assertTrue(!ClockworkBossVitals.hasTemporaryState(), "damage/heal/potion contexts released");
            helper.succeed();
        } finally { boss.discard(); }
    }
    @GameTest public void api_nested_frames_failure_and_feedback(GameTestHelper helper) throws Exception {
        var boss = boss(helper, "nesting");
        try (var fixture = new ClockworkBossCombatGameTests.PlayerFixture(helper, boss.position().add(2, 0, 0)); var probe = new Probe(boss)) {
            fixture.acknowledgeLoaded(); var player = fixture.player; support(boss, player); player.setHealth(80); probe.changes.clear();
            var parent = ClockworkBossVitals.begin(player, BossVitalsChange.Field.HEALTH);
            try { player.setHealth(60); player.heal(10); } finally { ClockworkBossVitals.end(parent, false); }
            helper.assertTrue(probe.changes.size() == 3 && probe.changes.getLast().exclusiveHealthChange() == 0
                    && !probe.changes.getLast().completedNormally() && probe.changes.getLast().nestedComplete(), "inclusive parent excludes legitimate nested damage/healing");
            helper.assertTrue(probe.changes.stream().mapToDouble(BossVitalsChange::exclusiveHealthChange).sum() == -10, "exclusive sum counts native mutation once");
            probe.changes.clear(); probe.throwObserver = true;
            probe.callback = () -> player.setHealth(40);
            player.setHealth(50);
            helper.assertTrue(probe.changes.size() == 1 && player.getHealth() == 40, "failed listener permits next; feedback dispatch suppressed while mutation remains native");
            probe.callback = null; probe.throwObserver = false;
            probe.changes.clear();
            var interrupted = ClockworkBossVitals.begin(player, BossVitalsChange.Field.HEALTH);
            try {
                captureFailure = player.getUUID(); player.setHealth(30);
            } finally { captureFailure = null; ClockworkBossVitals.end(interrupted, true); }
            helper.assertTrue(player.getHealth() == 30 && probe.changes.size() == 1 && !probe.changes.getFirst().nestedComplete(),
                    "early capture fault preserves original setter and marks suspended measurement incomplete");
            var other = net.minecraft.world.entity.EntityTypes.COW.create(helper.getLevel(), net.minecraft.world.entity.EntitySpawnReason.COMMAND);
            helper.assertTrue(other != null, "native cow fixture created");
            other.getAttribute(Attributes.MAX_HEALTH).setBaseValue(40);
            other.setHealth(10); probe.changes.clear();
            helper.assertTrue(other.getMaxHealth() == 40 && other.getHealth() == 10, "explicit healing headroom for both nested cases");
            try (var damage = ClockworkBossVitals.damage(other, other.damageSources().mobAttack(boss))) {
                var outer = ClockworkBossVitals.begin(other, BossVitalsChange.Field.HEALTH);
                try { other.heal(2); } finally { ClockworkBossVitals.end(outer, true); }
            }
            helper.assertTrue(probe.changes.size() == 2 && probe.changes.getFirst().cause() == BossVitalsChange.Cause.HEAL
                    && probe.changes.getFirst().exclusiveHealthChange() == 2 && probe.changes.getLast().exclusiveHealthChange() == 0,
                    "ordinary nested heal keeps encounter relationship without inheriting damage attribution");
            probe.changes.clear();
            try (var damage = ClockworkBossVitals.damage(other, other.damageSources().mobAttack(boss))) { other.heal(2); }
            helper.assertTrue(probe.changes.size() == 1 && probe.changes.getFirst().cause() == BossVitalsChange.Cause.HEAL
                    && probe.changes.getFirst().damage().isEmpty() && probe.changes.getFirst().exclusiveHealthChange() == 2,
                    "context-only nested heal is observed before any setter frame without damage attribution");
            helper.assertTrue(!ClockworkBossVitals.hasTemporaryState(), "finally releases every temporary frame");
            helper.succeed();
        } finally { captureFailure = null; boss.discard(); }
    }
    @GameTest public void api_cleanup_overlap_external_modifier_and_inventory(GameTestHelper helper) throws Exception {
        var boss = boss(helper, "overlap"); UUID other = UUID.randomUUID();
        try (var fixture = new ClockworkBossCombatGameTests.PlayerFixture(helper, boss.position().add(2, 0, 0)); var probe = new Probe(boss)) {
            fixture.acknowledgeLoaded(); var player = fixture.player;
            var external = new AttributeModifier(net.minecraft.resources.Identifier.fromNamespaceAndPath("api_fixture", "external"), 5, AttributeModifier.Operation.ADD_VALUE);
            player.getAttribute(Attributes.MAX_HEALTH).addOrReplacePermanentModifier(external);
            player.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND, new ItemStack(Items.SHIELD));
            for (int slot = 0; slot < 36; slot++) player.getInventory().setItem(slot, new ItemStack(Items.STONE, 64));
            support(boss, player);
            double remainingFloor = 60.123456789;
            ClockworkBossPlayerHealth.enter(other, player, new ClockworkBossPlayerSupport.Health(true, remainingFloor, false), false);
            ClockworkBossFightPotion.enter(other, player, new ClockworkBossPlayerSupport.Potion(true, 20, 300, 0, 0));
            player.setHealth(50); boss.discard();
            var result = probe.cleanup.getFirst().players().get(player.getUUID());
            var before = result.before().orElseThrow(); var after = result.after().orElseThrow();
            float nativeMax = (float) remainingFloor;
            helper.assertTrue(before.health().baselineMaxHealth() == 25 && before.health().predictedMaxHealth() == nativeMax
                    && before.health().predictedHealth() == nativeMax / 2, "native float precision and overlap prediction");
            helper.assertTrue(after.health().owners().equals(Set.of(other)) && after.vitals().maxHealth() == nativeMax
                    && after.vitals().health() == nativeMax / 2, "other owner retained and actual values reported");
            helper.assertTrue(after.potion().owners().equals(Set.of(other)) && after.potion().bottles() == 0 && player.getOffhandItem().is(Items.SHIELD), "full inventory/shield preserved, no invented bottle receipt");
            ClockworkBossPlayerHealth.releaseBoss(other); ClockworkBossFightPotion.releaseBoss(other);
            helper.assertTrue(player.getMaxHealth() == 25 && player.getAttribute(Attributes.MAX_HEALTH).hasModifier(external.id()), "external modifier survives all owner release");
            helper.succeed();
        } finally { ClockworkBossPlayerHealth.releaseBoss(other); ClockworkBossFightPotion.releaseBoss(other); boss.discard(); }
    }
    @GameTest public void api_target_policy_and_old_controller(GameTestHelper helper) throws Exception {
        var boss = boss(helper, "target");
        try (var one = new ClockworkBossCombatGameTests.PlayerFixture(helper, boss.position().add(2, 0, 0));
             var two = new ClockworkBossCombatGameTests.PlayerFixture(helper, boss.position().add(3, 0, 0))) {
            one.acknowledgeLoaded(); two.acknowledgeLoaded(); member(boss, one.player); member(boss, two.player);
            var brain = boss.getBrain(); brain.setMemoryWithExpiry(MemoryModuleType.SONIC_BOOM_COOLDOWN, net.minecraft.util.Unit.INSTANCE, 73);
            helper.assertTrue(BossEncounters.requestTarget(boss, one.player) == BossTargetRequest.ACCEPTED, "native assignment accepted");
            helper.assertTrue(brain.getTimeUntilExpiry(MemoryModuleType.SONIC_BOOM_COOLDOWN) == 73, "existing cooldown preserved");
            helper.assertTrue(BossEncounters.requestTarget(boss, one.player) == BossTargetRequest.UNCHANGED
                    && brain.getTimeUntilExpiry(MemoryModuleType.SONIC_BOOM_COOLDOWN) == 73, "unchanged request is a no-op");
            var stale = one.player;
            var replacement = one.respawn(); replacement.snapTo(boss.position().add(2, 0, 0)); member(boss, replacement);
            brain.setMemory(MemoryModuleType.ATTACK_TARGET, stale);
            helper.assertTrue(boss.getTarget() == stale && stale != replacement && stale.getUUID().equals(replacement.getUUID()), "native stale instance/same-UUID precondition");
            helper.assertTrue(BossEncounters.requestTarget(boss, replacement) == BossTargetRequest.ACCEPTED
                    && boss.getTarget() == replacement, "live same-UUID replacement is actually assigned");
            helper.assertTrue(BossEncounters.requestTarget(boss, replacement) == BossTargetRequest.UNCHANGED, "true replacement-instance repeat remains a no-op");
            var state = ClockworkBossManager.stateFor(boss); var config = state.config().deepCopy();
            config.getAsJsonObject("targeting").addProperty("min_target_ticks", 100); state.setConfig(config);
            helper.assertTrue(BossEncounters.requestTarget(boss, two.player) == BossTargetRequest.HELD && boss.getTarget() == one.player, "current native hold retained");
            config.getAsJsonObject("targeting").addProperty("min_target_ticks", 0); state.setConfig(config);
            vetoBoss = boss.getUUID();
            helper.assertTrue(BossEncounters.requestTarget(boss, two.player) == BossTargetRequest.REJECTED && boss.getTarget() == one.player, "external native veto is not acceptance");
            vetoBoss = null;
            helper.assertTrue(BossEncounters.requestTarget(boss, two.player) == BossTargetRequest.ACCEPTED, "eligible switch after hold");
            two.player.snapTo(boss.position().add(100, 0, 0));
            helper.assertTrue(BossEncounters.requestTarget(boss, two.player) == BossTargetRequest.INELIGIBLE, "fresh arena eligibility before unchanged outcome");
            state.setDormant(true);
            helper.assertTrue(BossEncounters.requestTarget(boss, one.player) == BossTargetRequest.INELIGIBLE, "dormant encounter refuses"); state.setDormant(false);
            BossMobController old = new BossMobController() {
                public Warden mob() { return boss; }
                public Set<net.minecraft.resources.Identifier> capabilities() { return Set.of(); }
            };
            helper.assertTrue(old.requestTarget(one.player) == BossTargetRequest.UNSUPPORTED, "old adapter default remains unsupported");
            var thrown = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            var thread = Thread.ofPlatform().start(() -> { try { BossEncounters.requestTarget(boss, one.player); } catch (Throwable failure) { thrown.set(failure); } });
            thread.join(1000);
            helper.assertTrue(!thread.isAlive() && thrown.get() instanceof IllegalStateException, "off-thread request rejected before mutable access");
            helper.succeed();
        } finally { vetoBoss = null; boss.discard(); }
    }
    @GameTest public void api_committed_boss_death_and_unavailable_player(GameTestHelper helper) throws Exception {
        var boss = boss(helper, "death");
        try (var fixture = new ClockworkBossCombatGameTests.PlayerFixture(helper, boss.position().add(2, 0, 0)); var probe = new Probe(boss)) {
            fixture.acknowledgeLoaded(); support(boss, fixture.player); fixture.player.setHealth(50);
            boss.setInvulnerableTime(0);
            helper.assertTrue(boss.hurtServer(helper.getLevel(), boss.damageSources().genericKill(), 1000000), "committed native death accepted");
            helper.assertTrue(probe.terminal.size() == 1 && probe.terminal.getFirst().reason() == BossAction.StopReason.DIED
                    && probe.terminal.getFirst().boss().orElseThrow().health() == 0, "death snapshot has terminal boss HP exactly once");
            helper.assertTrue(probe.terminal.getFirst().players().get(fixture.player.getUUID()).maxHealth() == 100
                    && probe.cleanup.getFirst().players().get(fixture.player.getUUID()).after().orElseThrow().vitals().maxHealth() == 20,
                    "death records fight vitals and actual restoration separately");
        } finally { boss.discard(); }
        var removed = boss(helper, "unavailable");
        try (var fixture = new ClockworkBossCombatGameTests.PlayerFixture(helper, removed.position().add(2, 0, 0)); var probe = new Probe(removed)) {
            fixture.acknowledgeLoaded(); support(removed, fixture.player); fixture.player.discard(); removed.discard();
            UUID player = fixture.player.getUUID();
            helper.assertTrue(probe.terminal.getFirst().unavailablePlayers().contains(player) && !probe.terminal.getFirst().players().containsKey(player), "removed player never gets invented terminal HP");
            helper.assertTrue(probe.cleanup.getFirst().unavailablePlayers().contains(player)
                    && probe.cleanup.getFirst().players().get(player).after().isEmpty(), "unavailable cleanup is not successful-restoration evidence");
        } finally { removed.discard(); }
        helper.succeed();
    }
    /** Shutdown is checked by the test-mod lifecycle after core's real stop
     * callback. Its separate PASS receipt is required by the guarded native plan. */
    @GameTest(maxTicks = 200) public void api_prepare_real_shutdown_capture(GameTestHelper helper) throws Exception {
        if (!Boolean.getBoolean("clockwork_bosses.api4.guard")) { helper.succeed(); return; }
        // GameTest clears entities inside its structures after success. Keep this
        // one fixture outside those boxes until the actual server stop, using an
        // explicitly owned chunk grant whose previous state is restored at stop.
        shutdownLevel = helper.getLevel();
        long chunk = net.minecraft.world.level.ChunkPos.pack(0, 0);
        shutdownChunkWasForced = shutdownLevel.getForceLoadedChunks().contains(chunk);
        ownsShutdownChunk = shutdownLevel.setChunkForced(0, 0, true);
        // Native prepareEntityTickingChunk requires FULL chunks within radius 2.
        // GameTest advances ticks without waiting for wall time, so load those
        // prerequisites once through the native synchronous task pump first.
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) shutdownLevel.getChunk(x, z);
        }
        // FULL loading does not imply entity simulation is ready. Repeat only
        // this read-only readiness assertion, bounded by the 200-tick GameTest
        // deadline; execute the production spawn/support acquisition once.
        helper.startSequence().thenWaitUntil(() -> {
            shutdownReadinessChecks++;
            var cache = shutdownLevel.getChunkSource();
            int fullChunks = 0;
            for (int x = -2; x <= 2; x++) {
                for (int z = -2; z <= 2; z++) if (cache.getChunkNow(x, z) != null) fullChunks++;
            }
            boolean ready = shutdownLevel.isPositionEntityTicking(new net.minecraft.core.BlockPos(8, 100, 8));
            try { java.nio.file.Files.writeString(java.nio.file.Path.of("api4-shutdown-readiness.txt"),
                    "checks=" + shutdownReadinessChecks + "; game_time=" + shutdownLevel.getGameTime()
                    + "; chunk=0,0; full_loaded=" + (cache.getChunkNow(0, 0) != null) + "; full_radius2=" + fullChunks + "/25"
                    + "; simulation_ticking=" + cache.chunkMap.getDistanceManager().inEntityTickingRange(chunk)
                    + "; entities_loaded=" + shutdownLevel.areEntitiesLoaded(chunk)
                    + "; visibility_ticking_and_loaded=" + shutdownLevel.areEntitiesActuallyLoadedAndTicking(new net.minecraft.world.level.ChunkPos(0, 0))
                    + "; entity_ticking=" + ready + "; forced=" + shutdownLevel.getForceLoadedChunks().contains(chunk) + "\n"); }
            catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
            helper.assertTrue(ready, "owned fixture chunk must be entity-ticking before production spawn (bounded200ticks)");
        }).thenExecute(() -> {
            try {
                var position = new net.minecraft.world.phys.Vec3(8, 100, 8);
                helper.assertTrue(shutdownLevel.isPositionEntityTicking(net.minecraft.core.BlockPos.containing(position)),
                        "owned fixture chunk is entity-ticking before production spawn");
                shutdownBoss = boss(helper, "shutdown", position); shutdownBoss.setNoGravity(true);
                shutdownPlayer = new ClockworkBossCombatGameTests.PlayerFixture(helper, shutdownBoss.position().add(2, 0, 0));
                shutdownPlayer.player.setNoGravity(true);
                helper.assertTrue(ClockworkBossManager.stateFor(shutdownBoss) != null && !shutdownBoss.isRemoved(), "shutdown encounter survives fixture setup");
                shutdownInitialState = ClockworkBossManager.stateFor(shutdownBoss);
                shutdownPlayer.acknowledgeLoaded(); support(shutdownBoss, shutdownPlayer.player); shutdownPlayer.player.setHealth(50);
                shutdownProbe = new Probe(shutdownBoss);
                shutdownProbe.player = shutdownPlayer.player;
                captureShutdownPreStop();
            } catch (Exception failure) {
                var trace = new java.io.StringWriter(); failure.printStackTrace(new java.io.PrintWriter(trace));
                try { java.nio.file.Files.writeString(java.nio.file.Path.of("api4-shutdown-setup-failure.txt"), trace.toString()); }
                catch (java.io.IOException writing) { failure.addSuppressed(writing); }
                throw new IllegalStateException("Shutdown fixture setup failed: " + failure, failure);
            }
        }).thenSucceed();
    }
    /** Independent initial/latest end-of-tick state, before the real stop hook.
     * This separates fixture drift from restoration during encounter cleanup. */
    static void captureShutdownPreStop() {
        if (shutdownProbe == null || shutdownPlayer == null) return;
        var player = shutdownPlayer.player;
        shutdownPreStop = new BossSupportSnapshot(ClockworkBossVitals.snapshot(player),
                ClockworkBossPlayerHealth.snapshot(player, shutdownBoss.getUUID()),
                ClockworkBossAreaEffects.snapshot(player), ClockworkBossFightPotion.snapshot(player));
        shutdownBossPreStop = ClockworkBossVitals.snapshot(shutdownBoss);
        shutdownPreStopDisconnected = player.hasDisconnected();
        shutdownStatePreserved = ClockworkBossManager.stateFor(shutdownBoss) == shutdownInitialState;
        try { java.nio.file.Files.writeString(java.nio.file.Path.of("api4-shutdown-prestop.txt"),
                "game_time=" + shutdownLevel.getGameTime() + "; boss=" + shutdownBossPreStop + "; disconnected="
                        + shutdownPreStopDisconnected + "; position=" + shutdownBoss.position() + "; chunk=0,0; entity_ticking="
                        + shutdownLevel.isPositionEntityTicking(shutdownBoss.blockPosition()) + "; initial_state_identity="
                        + System.identityHashCode(shutdownInitialState) + "; same_state=" + shutdownStatePreserved
                        + "; ticket_baseline=" + shutdownChunkWasForced + "; owns_ticket=" + ownsShutdownChunk + "; player=" + shutdownPreStop + "\n"); }
        catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
    }
    static void releaseShutdownChunk() {
        if (shutdownLevel == null) return;
        boolean owned = ownsShutdownChunk;
        if (ownsShutdownChunk) { shutdownLevel.setChunkForced(0, 0, false); ownsShutdownChunk = false; }
        shutdownChunkRestored = shutdownLevel.getForceLoadedChunks().contains(net.minecraft.world.level.ChunkPos.pack(0, 0)) == shutdownChunkWasForced;
        try { java.nio.file.Files.writeString(java.nio.file.Path.of("api4-shutdown-chunk.txt"), "chunk=0,0; baseline_forced="
                + shutdownChunkWasForced + "; owned_before_release=" + owned + "; baseline_restored=" + shutdownChunkRestored + "\n"); }
        catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
    }
    static void verifyShutdown() {
        String result;
        try {
            if (shutdownProbe == null || shutdownPlayer == null) throw new AssertionError("Shutdown fixture did not run");
            var terminal = shutdownProbe.terminal.getFirst();
            var cleanup = shutdownProbe.cleanup.getFirst();
            if (shutdownPreStop == null) throw new AssertionError("No independent pre-stop snapshot");
            UUID player = shutdownPreStop.vitals().entityId();
            if (shutdownPreStop == null || shutdownPreStop.vitals().health() != 50 || shutdownPreStop.vitals().maxHealth() != 100
                    || shutdownPreStop.vitals().removed() || shutdownBossPreStop.removed() || shutdownPreStopDisconnected
                    || !shutdownStatePreserved || !shutdownPreStop.health().owners().contains(shutdownProbe.boss))
                throw new AssertionError("Shutdown preconditions drifted; boss=" + shutdownBossPreStop + "; player=" + shutdownPreStop);
            if (terminal.reason() != BossAction.StopReason.SERVER_STOPPED || terminal.players().get(player).maxHealth() != 100
                    || terminal.players().get(player).health() != 50 || cleanup.players().get(player).after().orElseThrow().vitals().maxHealth() != 20)
                throw new AssertionError("Real stop did not capture before restoring support; terminal=" + terminal + "; cleanup=" + cleanup);
            if (ClockworkBossVitals.hasTemporaryState()) throw new AssertionError("Native observation state survived stop");
            if (shutdownProbe.terminal.size() != 1 || shutdownProbe.cleanup.size() != 1 || !shutdownChunkRestored
                    || !shutdownProbe.order.equals(List.of("terminal", "left", "ended", "cleanup")))
                throw new AssertionError("Repeated stop capture or un-restored fixture chunk ownership");
            if (shutdownProbe.endedMax != 20) throw new AssertionError("Legacy ENDED preceded support restoration");
            result = "PASS: real SERVER_STOPPING terminal before restoration; actual cleanup captured; fixture chunk baseline restored\n";
        } catch (Throwable failure) { result = "FAIL: " + failure + "\n"; }
        finally {
            if (shutdownProbe != null) shutdownProbe.close();
            // Server shutdown owns removal of remaining player/boss entities.
            shutdownProbe = null; shutdownPlayer = null; shutdownBoss = null;
            shutdownLevel = null;
            shutdownPreStop = null; shutdownBossPreStop = null;
            shutdownInitialState = null;
        }
        try { java.nio.file.Files.writeString(java.nio.file.Path.of("api4-shutdown-result.txt"), result); }
        catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
    }
}
