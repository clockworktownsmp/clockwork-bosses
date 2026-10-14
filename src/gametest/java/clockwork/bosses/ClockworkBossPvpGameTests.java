package clockwork.bosses;

import clockwork.bosses.ClockworkBossCombatGameTests.PlayerFixture;
import clockwork.bosses.mixin.PvpProjectileProbe;
import clockwork.bosses.mixin.PvpSweepProbe;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

/** Native player/ownership/effect regressions in the disposable world. Players
 * acknowledge the real 26.3 loading handler; no client mod metadata is required. */
public final class ClockworkBossPvpGameTests {
    private static Warden boss(GameTestHelper helper, String name) throws Exception {
        JsonObject config = ClockworkBossCombatGameTests.config(1000);
        var area = config.getAsJsonObject("boss_bar").getAsJsonObject("visibility_area");
        area.addProperty("shape", "radius"); area.addProperty("radius", 2);
        Warden boss = ClockworkBossCombatGameTests.boss(helper, config, "pvp_" + name, false, true);
        boss.setNoAi(true); boss.setNoGravity(true);
        return boss;
    }
    private static PlayerFixture player(GameTestHelper helper, Vec3 position) {
        var fixture = new PlayerFixture(helper, helper.absoluteVec(position));
        fixture.acknowledgeLoaded(); fixture.player.setNoGravity(true);
        fixture.player.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100);
        fixture.player.setHealth(100); fixture.player.setInvulnerableTime(0);
        return fixture;
    }
    /** Separate native hurt immunity from the policy tested by immediate calls. */
    private static void resetHurt(ServerPlayer player) {
        player.setInvulnerableTime(0); player.damageCooldownTime = 0;
    }

    /** Incoming and outgoing harm are both gated, with no wait for the watcher.
     * Dormancy, exit, settings reload and removal restore ordinary native PvP. */
    @GameTest public void pvp_activation_exit_reload_and_remove(GameTestHelper helper) throws Exception {
        Warden boss = boss(helper, "boundaries"); var state = ClockworkBossManager.stateFor(boss);
        try (var inside = player(helper, new Vec3(3, 2, 2)); var outside = player(helper, new Vec3(7, 2, 2))) {
            var victim = inside.player; var attacker = outside.player; var level = helper.getLevel();
            helper.assertTrue(ClockworkBossManager.hasPvpProtection(victim) && !ClockworkBossManager.hasPvpProtection(attacker), "fixture has one participant and one outsider");
            helper.assertTrue(!victim.hurtServer(level, victim.damageSources().playerAttack(attacker), 5)
                    && !attacker.hurtServer(level, attacker.damageSources().playerAttack(victim), 5), "incoming and outgoing PvP rejected");
            helper.assertTrue(victim.getHealth() == 100 && attacker.getHealth() == 100, "blocked harm does not change health");
            helper.assertTrue(victim.hurtServer(level, victim.damageSources().mobAttack(boss), 5), "boss damage still works");
            resetHurt(victim);
            // This airborne fixture disables gravity, which natively disables
            // fall damage too. Cactus tests a genuine environmental damage path.
            helper.assertTrue(victim.hurtServer(level, victim.damageSources().cactus(), 3), "environmental damage still works");
            resetHurt(victim);
            helper.assertTrue(victim.hurtServer(level, victim.damageSources().playerAttack(victim), 1), "self-inflicted damage is not PvP");
            state.setDormant(true); resetHurt(victim);
            helper.assertTrue(victim.hurtServer(level, victim.damageSources().playerAttack(attacker), 5), "dormant encounter has no PvP protection");
            state.setDormant(false); resetHurt(victim);
            helper.assertTrue(!victim.hurtServer(level, victim.damageSources().playerAttack(attacker), 5), "activation immediately restores protection");
            victim.snapTo(helper.absoluteVec(new Vec3(6, 2, 2))); resetHurt(victim);
            helper.assertTrue(victim.hurtServer(level, victim.damageSources().playerAttack(attacker), 5), "exit takes effect before next watcher scan");
            victim.snapTo(helper.absoluteVec(new Vec3(3, 2, 2))); resetHurt(victim);
            JsonObject replacement = state.config().deepCopy(); replacement.getAsJsonObject("boss_bar").getAsJsonObject("pvp_protection").addProperty("enabled", false);
            state.setConfig(replacement);
            helper.assertTrue(victim.hurtServer(level, victim.damageSources().playerAttack(attacker), 5), "reload disables protection immediately");
            replacement.getAsJsonObject("boss_bar").getAsJsonObject("pvp_protection").addProperty("enabled", true); state.setConfig(replacement);
            boss.discard(); resetHurt(victim);
            helper.assertTrue(victim.hurtServer(level, victim.damageSources().playerAttack(attacker), 5), "unload/removal releases protection");
            helper.succeed();
        } finally { boss.discard(); }
    }

    /** Sweeping a mob cannot injure/push a protected bystander. Arrows skip
     * friendly collision and rejected direct hits cannot ignite or add poison. */
    @GameTest public void pvp_native_sweep_and_flaming_tipped_arrow(GameTestHelper helper) throws Exception {
        Warden boss = boss(helper, "attacks");
        try (var inside = player(helper, new Vec3(3, 2, 2)); var outside = player(helper, new Vec3(4.5, 2, 2))) {
            var attacker = outside.player; var victim = inside.player;
            var primary = helper.spawn(EntityTypes.COW, new Vec3(3.5, 2, 2)); primary.setNoAi(true); primary.setNoGravity(true);
            var bystander = helper.spawn(EntityTypes.COW, new Vec3(3.5, 2, 2.5)); bystander.setNoAi(true); bystander.setNoGravity(true);
            float cowHealth = bystander.getHealth(); var velocity = victim.getDeltaMovement();
            ((PvpSweepProbe) attacker).clockworkTest$sweep(primary, 5, attacker.damageSources().playerAttack(attacker), 1);
            helper.assertTrue(victim.getHealth() == 100 && victim.getDeltaMovement().equals(velocity), "native sweep spares player's health and knockback");
            helper.assertTrue(bystander.getHealth() < cowHealth, "same native sweep still damages an ordinary mob");
            attacker.attack(victim);
            helper.assertTrue(victim.getHealth() == 100 && victim.getDeltaMovement().equals(velocity), "native direct melee cannot damage or push participant");
            Arrow arrow = new Arrow(helper.getLevel(), attacker, new ItemStack(Items.ARROW), null);
            arrow.addEffect(new MobEffectInstance(MobEffects.POISON, 100)); arrow.igniteForSeconds(10);
            helper.getLevel().addFreshEntity(arrow); arrow.snapTo(victim.position().add(0, 0.5, 1)); arrow.setDeltaMovement(0, 0, -1);
            helper.assertTrue(!((PvpProjectileProbe) arrow).clockworkTest$canHit(victim), "friendly player excluded from native projectile collision");
            ((PvpProjectileProbe) arrow).clockworkTest$hit(new EntityHitResult(victim));
            helper.assertTrue(victim.getHealth() == 100 && !victim.isOnFire() && !victim.hasEffect(MobEffects.POISON), "rejected native arrow hit has no damage/fire/poison");
            helper.assertTrue(((PvpProjectileProbe) arrow).clockworkTest$canHit(bystander), "native projectile can still hit a mob");
            arrow.discard(); primary.discard(); bystander.discard(); helper.succeed();
        } finally { boss.discard(); }
    }

    /** Sourced harmful effects are rejected without deleting existing effects;
     * beneficial/self/mob/command effects and nested callback sources stay separate. */
    @GameTest public void pvp_sourced_effects_and_helpful_potions(GameTestHelper helper) throws Exception {
        Warden boss = boss(helper, "effects");
        try (var inside = player(helper, new Vec3(3, 2, 2)); var outside = player(helper, new Vec3(7, 2, 2))) {
            var victim = inside.player; var attacker = outside.player;
            for (var effect : java.util.List.of(MobEffects.POISON, MobEffects.WITHER, MobEffects.SLOWNESS, MobEffects.WEAKNESS))
                helper.assertTrue(!victim.addEffect(new MobEffectInstance(effect, 200), attacker) && !victim.hasEffect(effect), "player harmful effect rejected: " + effect);
            helper.assertTrue(victim.addEffect(new MobEffectInstance(MobEffects.SPEED, 200), attacker), "helpful player buff allowed");
            helper.assertTrue(victim.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 200), boss), "boss harmful effect allowed");
            helper.assertTrue(victim.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 200), victim), "self effect allowed");
            victim.removeEffect(MobEffects.WEAKNESS);
            helper.assertTrue(victim.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 200)), "source-less/native effects are not guessed to be PvP");
            victim.setHealth(50);
            var heal = EntityTypes.SPLASH_POTION.create(helper.getLevel(), EntitySpawnReason.COMMAND);
            heal.setOwner(attacker); heal.snapTo(victim.position());
            var stack = new ItemStack(Items.SPLASH_POTION);
            stack.set(DataComponents.POTION_CONTENTS, new PotionContents(java.util.Optional.empty(), java.util.Optional.empty(),
                    java.util.List.of(new MobEffectInstance(MobEffects.INSTANT_HEALTH, 1)), java.util.Optional.empty()));
            heal.setItem(stack); helper.getLevel().addFreshEntity(heal);
            ((PvpProjectileProbe) heal).clockworkTest$hit(new EntityHitResult(victim));
            helper.assertTrue(victim.getHealth() > 50, "native splash healing remains allowed");
            var poison = EntityTypes.SPLASH_POTION.create(helper.getLevel(), EntitySpawnReason.COMMAND);
            poison.setOwner(attacker); poison.snapTo(victim.position());
            stack = new ItemStack(Items.SPLASH_POTION);
            stack.set(DataComponents.POTION_CONTENTS, new PotionContents(java.util.Optional.empty(), java.util.Optional.empty(),
                    java.util.List.of(new MobEffectInstance(MobEffects.POISON, 100)), java.util.Optional.empty()));
            poison.setItem(stack); helper.getLevel().addFreshEntity(poison);
            ((PvpProjectileProbe) poison).clockworkTest$hit(new EntityHitResult(victim));
            helper.assertTrue(!victim.hasEffect(MobEffects.POISON), "native splash poison rejected");
            var hurt = EntityTypes.SPLASH_POTION.create(helper.getLevel(), EntitySpawnReason.COMMAND);
            hurt.setOwner(attacker); hurt.snapTo(victim.position());
            stack = new ItemStack(Items.SPLASH_POTION);
            stack.set(DataComponents.POTION_CONTENTS, new PotionContents(java.util.Optional.empty(), java.util.Optional.empty(),
                    java.util.List.of(new MobEffectInstance(MobEffects.INSTANT_DAMAGE, 1)), java.util.Optional.empty()));
            hurt.setItem(stack); helper.getLevel().addFreshEntity(hurt);
            var health = victim.getHealth(); resetHurt(victim);
            ((PvpProjectileProbe) hurt).clockworkTest$hit(new EntityHitResult(victim));
            helper.assertTrue(victim.getHealth() == health, "native instant-harm potion cannot bypass damage gate");
            helper.getLevel().getServer().getCommands().getDispatcher().execute("effect give " + victim.getScoreboardName()
                    + " minecraft:poison 10", ClockworkBossCombatGameTests.source(helper).withEntity(attacker));
            helper.assertTrue(victim.hasEffect(MobEffects.POISON), "authorized native effect command still works");
            victim.removeEffect(MobEffects.POISON);
            helper.succeed();
        } finally { boss.discard(); }
    }

    /** Source UUIDs survive player logout and save/read without a global cache.
     * Native reassignment to a mob clears the player flag instead of blocking it. */
    @GameTest public void pvp_owner_logout_save_and_blast_knockback(GameTestHelper helper) throws Exception {
        Warden boss = boss(helper, "ownership");
        var outside = player(helper, new Vec3(7, 2, 2));
        try (var inside = player(helper, new Vec3(3, 2, 2))) {
            var victim = inside.player; var attacker = outside.player;
            Arrow arrow = new Arrow(helper.getLevel(), attacker, new ItemStack(Items.ARROW), null);
            helper.getLevel().addFreshEntity(arrow);
            var cloud = new AreaEffectCloud(helper.getLevel(), victim.getX(), victim.getY(), victim.getZ());
            cloud.setOwner(attacker); cloud.setWaitTime(0); cloud.setRadius(2);
            cloud.addEffect(new MobEffectInstance(MobEffects.POISON, 100)); helper.getLevel().addFreshEntity(cloud);
            var cloudMob = helper.spawn(EntityTypes.COW, new Vec3(3, 2, 2.5)); cloudMob.setNoAi(true); cloudMob.setNoGravity(true);
            var tnt = new PrimedTnt(helper.getLevel(), 0, 0, 0, attacker); tnt.snapTo(victim.position().add(1, 0, 0));
            helper.getLevel().addFreshEntity(tnt);
            var id = attacker.getUUID(); outside.close(); outside = null;
            helper.assertTrue(ClockworkBossPvp.playerOrigin(arrow).equals(id) && ClockworkBossPvp.playerOrigin(cloud).equals(id)
                    && ClockworkBossPvp.playerOrigin(tnt).equals(id), "native player sources retain a UUID after logout");
            var output = net.minecraft.world.level.storage.TagValueOutput.createWithoutContext(net.minecraft.util.ProblemReporter.DISCARDING);
            arrow.saveWithoutId(output);
            var loaded = EntityTypes.ARROW.create(helper.getLevel(), EntitySpawnReason.LOAD);
            loaded.load(net.minecraft.world.level.storage.TagValueInput.create(net.minecraft.util.ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), output.buildResult()));
            helper.assertTrue(id.equals(ClockworkBossPvp.playerOrigin(loaded)), "saved native owner provenance survives reload");
            var cloudOutput = net.minecraft.world.level.storage.TagValueOutput.createWithoutContext(net.minecraft.util.ProblemReporter.DISCARDING);
            cloud.saveWithoutId(cloudOutput); var loadedCloud = EntityTypes.AREA_EFFECT_CLOUD.create(helper.getLevel(), EntitySpawnReason.LOAD);
            loadedCloud.load(net.minecraft.world.level.storage.TagValueInput.create(net.minecraft.util.ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), cloudOutput.buildResult()));
            var tntOutput = net.minecraft.world.level.storage.TagValueOutput.createWithoutContext(net.minecraft.util.ProblemReporter.DISCARDING);
            tnt.saveWithoutId(tntOutput); var loadedTnt = EntityTypes.TNT.create(helper.getLevel(), EntitySpawnReason.LOAD);
            loadedTnt.load(net.minecraft.world.level.storage.TagValueInput.create(net.minecraft.util.ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), tntOutput.buildResult()));
            helper.assertTrue(id.equals(ClockworkBossPvp.playerOrigin(loadedCloud)) && id.equals(ClockworkBossPvp.playerOrigin(loadedTnt)), "cloud/TNT player attribution also survives save/read");
            helper.assertTrue(!victim.addEffect(new MobEffectInstance(MobEffects.POISON, 100), cloud), "disconnected owner's cloud cannot poison participant");
            // Native clouds apply every five ticks. Exercise their actual tick
            // method in this isolated fixture, not an added mod tick listener.
            cloud.tickCount = 5; cloud.tick();
            helper.assertTrue(!victim.hasEffect(MobEffects.POISON), "native lingering cloud blocks poison after owner logout");
            helper.assertTrue(cloudMob.hasEffect(MobEffects.POISON), "same actual cloud still poisons ordinary mobs");
            var health = victim.getHealth(); var velocity = victim.getDeltaMovement();
            new ServerExplosion(helper.getLevel(), tnt, null, null, tnt.position(), 2, false, Explosion.BlockInteraction.KEEP).explode();
            helper.assertTrue(victim.getHealth() == health && victim.getDeltaMovement().equals(velocity), "player-owned blast loses damage and independent knockback");
            loaded.setOwner(boss);
            helper.assertTrue(ClockworkBossPvp.playerOrigin(loaded) == null, "reassignment invalidates an old player owner");
            helper.assertTrue(victim.addEffect(new MobEffectInstance(MobEffects.POISON, 100), loaded), "mob-owned source remains allowed");
            arrow.discard(); loaded.discard(); cloud.discard(); loadedCloud.discard(); tnt.discard(); loadedTnt.discard(); cloudMob.discard(); helper.succeed();
        } finally { if (outside != null) outside.close(); boss.discard(); }
    }

    /** Two encounters may overlap: ending one must not disable the other's
     * policy. Fixed boxes are evaluated independently of the boss position. */
    @GameTest public void pvp_box_overlap_and_encounter_end(GameTestHelper helper) throws Exception {
        Warden first = boss(helper, "box_a"), second = boss(helper, "box_b");
        try (var inside = player(helper, new Vec3(3, 2, 2)); var outside = player(helper, new Vec3(7, 2, 2))) {
            var firstState = ClockworkBossManager.stateFor(first); var secondState = ClockworkBossManager.stateFor(second);
            var config = firstState.config().deepCopy(); var area = config.getAsJsonObject("boss_bar").getAsJsonObject("visibility_area");
            var min = helper.absoluteVec(new Vec3(1, 1, 1)); var max = helper.absoluteVec(new Vec3(4, 4, 4));
            area.addProperty("shape", "box"); area.addProperty("dimension", helper.getLevel().dimension().identifier().toString());
            var low = new JsonObject(); low.addProperty("x", min.x); low.addProperty("y", min.y); low.addProperty("z", min.z);
            var high = new JsonObject(); high.addProperty("x", max.x); high.addProperty("y", max.y); high.addProperty("z", max.z);
            area.add("min", low); area.add("max", high); firstState.setConfig(config);
            // Move outside the fixed box vertically, staying in the same loaded
            // chunk column. Random GameTest origins can put a horizontal move
            // across a chunk edge; unloading would test a different policy.
            first.snapTo(helper.absoluteVec(new Vec3(2, 7, 2)));
            helper.assertTrue(helper.getLevel().getEntity(first.getUUID()) == first && first.isAlive() && !first.isRemoved(),
                    "box fixture keeps the displaced boss loaded and alive");
            secondState.claimEncounterEnd();
            helper.assertTrue(ClockworkBossPvp.blocks(inside.player, outside.player), "ended overlap does not release a live box encounter"
                    + " loaded=" + (helper.getLevel().getEntity(first.getUUID()) == first)
                    + " removed=" + first.isRemoved() + " alive=" + first.isAlive()
                    + " enabled=" + firstState.pvpProtection().enabled() + " dormant=" + firstState.dormant() + " ended=" + firstState.encounterEnded()
                    + " area=" + ClockworkBossManager.visibilityQuery(firstState, first) + " player=" + inside.player.getBoundingBox());
            firstState.claimEncounterEnd();
            helper.assertTrue(!ClockworkBossPvp.blocks(inside.player, outside.player), "ending last encounter releases protection immediately");
            helper.succeed();
        } finally { first.discard(); second.discard(); }
    }

    /** A callback failure must release the thread-local scope. Reflection is
     * test-only, so production does not gain a debug accessor or retained map. */
    @GameTest public void pvp_nested_effect_scope_cleanup(GameTestHelper helper) throws Exception {
        Warden boss = boss(helper, "scope");
        try (var inside = player(helper, new Vec3(3, 2, 2)); var outside = player(helper, new Vec3(7, 2, 2))) {
            var effect = new MobEffectInstance(MobEffects.POISON, 100);
            var field = ClockworkBossPvp.class.getDeclaredField("EFFECT_SOURCE"); field.setAccessible(true);
            var scope = (ThreadLocal<?>) field.get(null);
            try {
                ClockworkBossPvp.withEffectSource(inside.player, effect, outside.player, () -> {
                    var outer = scope.get();
                    ClockworkBossPvp.withEffectSource(inside.player, new MobEffectInstance(MobEffects.WITHER, 100), boss, () -> {
                        helper.assertTrue(scope.get() != outer, "nested operation has its own provenance"); return true;
                    });
                    helper.assertTrue(scope.get() == outer, "nested operation restores outer provenance");
                    throw new IllegalStateException("expected fixture failure");
                });
                throw new AssertionError("original callback did not throw");
            } catch (IllegalStateException expected) {
                helper.assertTrue(expected.getMessage().equals("expected fixture failure"), "callback failure is propagated");
            }
            helper.assertTrue(scope.get() == null, "no player/effect reference retained after callback failure");
            helper.succeed();
        } finally { boss.discard(); }
    }

    /** Channeling's native cause must be read before thunderHit creates a
     * source-less DamageSource and ignites. Unowned lightning remains a hazard. */
    @GameTest public void pvp_channeling_and_natural_lightning(GameTestHelper helper) throws Exception {
        Warden boss = boss(helper, "lightning");
        var outside = player(helper, new Vec3(7, 2, 2));
        try (var inside = player(helper, new Vec3(3, 2, 2))) {
            var victim = inside.player;
            var owned = EntityTypes.LIGHTNING_BOLT.create(helper.getLevel(), EntitySpawnReason.COMMAND);
            owned.setCause(outside.player);
            victim.thunderHit(helper.getLevel(), owned);
            helper.assertTrue(victim.getHealth() == 100 && !victim.isOnFire(), "Channeling cannot damage/ignite a protected player");
            outside.close(); outside = null;
            victim.thunderHit(helper.getLevel(), owned);
            helper.assertTrue(victim.getHealth() == 100 && !victim.isOnFire(), "native Channeling cause still protects victim after player logout");
            var cow = helper.spawn(EntityTypes.COW, new Vec3(3, 2, 3)); cow.setNoAi(true);
            var health = cow.getHealth(); cow.thunderHit(helper.getLevel(), owned);
            helper.assertTrue(cow.getHealth() < health, "same player-caused lightning still damages an ordinary mob");
            var natural = EntityTypes.LIGHTNING_BOLT.create(helper.getLevel(), EntitySpawnReason.COMMAND);
            resetHurt(victim); victim.thunderHit(helper.getLevel(), natural);
            helper.assertTrue(victim.getHealth() < 100, "natural lightning still hurts participant");
            owned.discard(); natural.discard(); cow.discard(); helper.succeed();
        } finally { if (outside != null) outside.close(); boss.discard(); }
    }
}
