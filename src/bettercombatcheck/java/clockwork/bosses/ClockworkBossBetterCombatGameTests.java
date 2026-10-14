package clockwork.bosses;

import net.bettercombat.api.component.BetterCombatDataComponents;
import net.bettercombat.network.Packets;
import net.bettercombat.network.ServerNetwork;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/** Optional development mod only. Direct Better Combat types never enter the
 * normal GameTest entrypoint or distributable. Fixtures use its real components. */
public final class ClockworkBossBetterCombatGameTests {
    private static final java.util.Map<java.util.UUID, Integer> HITS = new java.util.HashMap<>();
    static {
        ServerLivingEntityEvents.AFTER_DAMAGE.register((victim, source, base, taken, blocked) -> {
            if (taken > 0 && source.getEntity() instanceof ServerPlayer && HITS.containsKey(victim.getUUID()))
                HITS.computeIfPresent(victim.getUUID(), (id, count) -> count + 1);
        });
    }
    private static ItemStack weapon(String preset) {
        ItemStack stack = new ItemStack(Items.IRON_SWORD);
        stack.set(BetterCombatDataComponents.WEAPON_PRESET_ID, Identifier.fromNamespaceAndPath("bettercombat", preset));
        return stack;
    }
    private static Warden boss(GameTestHelper helper, String name, boolean protection) throws Exception {
        var config = ClockworkBossCombatGameTests.config(1000);
        var area = config.getAsJsonObject("boss_bar").getAsJsonObject("visibility_area");
        area.addProperty("shape", "radius"); area.addProperty("radius", 4);
        Warden boss = ClockworkBossCombatGameTests.boss(helper, config, "bc_" + name, true, protection);
        config = ClockworkBossManager.stateFor(boss).config().deepCopy();
        config.getAsJsonObject("boss_bar").getAsJsonObject("player_health").addProperty("enabled", false);
        ClockworkBossManager.stateFor(boss).setConfig(config);
        boss.setNoAi(true); boss.setNoGravity(true); return boss;
    }
    private static ClockworkBossCombatGameTests.PlayerFixture player(GameTestHelper helper, Vec3 absolute) {
        var fixture = new ClockworkBossCombatGameTests.PlayerFixture(helper, absolute);
        fixture.acknowledgeLoaded(); fixture.player.setNoGravity(true);
        fixture.player.getAttribute(Attributes.MAX_HEALTH).setBaseValue(100); fixture.player.setHealth(100);
        return fixture;
    }
    /** Invoke the shipped C2S handler with actual target IDs; it chooses native
     * packet/attack logic. Health deltas and server AFTER_DAMAGE prove outcomes,
     * not the client swing/hit callbacks. No production damage is duplicated. */
    private static void attack(ServerPlayer player, int combo, Entity... targets) {
        attack(player, combo, false, targets);
    }
    private static void attack(ServerPlayer player, int combo, boolean critical, Entity... targets) {
        // ServerPlayer.tick updates connection bookkeeping; its doTick owns
        // actual living/use/attack-strength work. Embedded clients need this.
        for (int tick = 0; tick < 240; tick++) player.doTick();
        player.setOnGround(!critical); player.fallDistance = critical ? 1 : 0;
        var request = new Packets.C2S_AttackRequest(combo, false, player.getInventory().getSelectedSlot(),
                targets[0], java.util.List.of(targets));
        ServerNetwork.handleAttackRequest(request, player.level().getServer(), player, player.connection);
    }

    /** Reproduce the false empty hand: a real shield exists behind BC's view.
     * Grant/cleanup must preserve that backing slot byte-for-byte. */
    @GameTest public void bettercombat_hidden_offhand_is_preserved(GameTestHelper helper) throws Exception {
        var config = ClockworkBossCombatGameTests.config(1000);
        var boss = ClockworkBossCombatGameTests.boss(helper, config, "bc_hidden_offhand", true);
        boss.setNoAi(true);
        try (var fixture = new ClockworkBossCombatGameTests.PlayerFixture(helper, boss.position().add(1, 0, 0))) {
            var player = fixture.player; fixture.acknowledgeLoaded();
            var shield = new ItemStack(Items.SHIELD); var snapshot = shield.copy();
            player.setItemInHand(InteractionHand.MAIN_HAND, weapon("claymore"));
            player.getInventory().setItem(Inventory.SLOT_OFFHAND, shield);
            helper.assertTrue(player.getOffhandItem().isEmpty() && !player.getInventory().getItem(Inventory.SLOT_OFFHAND).isEmpty(), "Better Combat hides a genuinely occupied offhand");
            ClockworkBossFightPotion.enter(boss.getUUID(), player, ClockworkBossManager.stateFor(boss).playerSupport().potion());
            helper.assertTrue(ItemStack.isSameItemSameComponents(snapshot, player.getInventory().getItem(Inventory.SLOT_OFFHAND)), "grant does not overwrite the hidden shield");
            helper.assertTrue(ClockworkBossFightPotion.isPotion(player.getInventory().getItem(1)), "inaccessible offhand uses an empty inventory slot");
            ClockworkBossFightPotion.releaseBoss(boss.getUUID());
            helper.assertTrue(ItemStack.isSameItemSameComponents(snapshot, player.getInventory().getItem(Inventory.SLOT_OFFHAND)), "cleanup keeps the backing offhand intact");
            helper.succeed();
        } finally { boss.discard(); }
    }

    @GameTest public void bettercombat_empty_hidden_hand_full_inventory_and_cleanup(GameTestHelper helper) throws Exception {
        Warden boss = boss(helper, "placement", false);
        try (var fixture = player(helper, boss.position().add(1, 0, 0))) {
            var player = fixture.player; var inventory = player.getInventory();
            player.setItemInHand(InteractionHand.MAIN_HAND, weapon("claymore"));
            var policy = ClockworkBossManager.stateFor(boss).playerSupport().potion();
            ClockworkBossFightPotion.enter(boss.getUUID(), player, policy);
            helper.assertTrue(inventory.getItem(Inventory.SLOT_OFFHAND).isEmpty() && ClockworkBossFightPotion.isPotion(inventory.getItem(1)), "empty but unusable offhand uses inventory");
            // A user may later move the bottle into a masked slot. Cleanup must
            // read backing storage too, without creating restoration snapshots.
            inventory.setItem(Inventory.SLOT_OFFHAND, inventory.removeItemNoUpdate(1));
            helper.assertTrue(player.getOffhandItem().isEmpty(), "moved bottle is hidden by two-handed weapon");
            ClockworkBossFightPotion.releaseBoss(boss.getUUID());
            helper.assertTrue(inventory.getItem(Inventory.SLOT_OFFHAND).isEmpty(), "cleanup clears hidden owned bottle");
            for (int slot = 1; slot < Inventory.INVENTORY_SIZE; slot++) inventory.setItem(slot, new ItemStack(Items.STONE, 64));
            var shield = new ItemStack(Items.SHIELD); inventory.setItem(Inventory.SLOT_OFFHAND, shield);
            ClockworkBossFightPotion.enter(boss.getUUID(), player, policy);
            helper.assertTrue(inventory.getItem(Inventory.SLOT_OFFHAND) == shield, "full inventory preserves exact shield stack");
            for (int slot = 0; slot < inventory.getContainerSize(); slot++)
                helper.assertTrue(!ClockworkBossFightPotion.isPotion(inventory.getItem(slot)), "full inventory grants no bottle and overwrites no equipment");
            ClockworkBossFightPotion.releaseBoss(boss.getUUID());
            inventory.setItem(5, ItemStack.EMPTY); ClockworkBossFightPotion.enter(boss.getUUID(), player, policy);
            helper.assertTrue(ClockworkBossFightPotion.isPotion(inventory.getItem(5)) && inventory.getItem(Inventory.SLOT_OFFHAND) == shield, "freeing one slot permits safe reentry grant");
            helper.succeed();
        } finally { boss.discard(); }
    }

    /** Real native duration and completion, preceded by interruption. Weapon
     * changes require no new drinking animation or separate potion cooldown. */
    @GameTest(maxTicks = 65, setupTicks = 100) public void bettercombat_native_mainhand_drink_and_shared_cooldown(GameTestHelper helper) throws Exception {
        Warden boss = boss(helper, "drink", false);
        // Isolate the dose from vanilla saturated-food regeneration. The base
        // support suite separately verifies the default 20 hunger/saturation.
        var settings = ClockworkBossManager.stateFor(boss).config().deepCopy();
        settings.getAsJsonObject("boss_bar").getAsJsonObject("reusable_potion").addProperty("hunger_restored", 0);
        settings.getAsJsonObject("boss_bar").getAsJsonObject("reusable_potion").addProperty("saturation_restored", 0);
        ClockworkBossManager.stateFor(boss).setConfig(settings);
        var fixture = player(helper, boss.position().add(1, 0, 0));
        var player = fixture.player; var inventory = player.getInventory();
        Runnable cleanup = () -> { boss.discard(); fixture.close(); };
        try {
            player.setItemInHand(InteractionHand.MAIN_HAND, weapon("claymore"));
            var offWeapon = weapon("sword"); inventory.setItem(Inventory.SLOT_OFFHAND, offWeapon);
            ClockworkBossFightPotion.enter(boss.getUUID(), player, ClockworkBossManager.stateFor(boss).playerSupport().potion());
            inventory.setSelectedSlot(1); // The bottle is the held main item; weapons stay in backing slots.
            player.setHealth(40); player.getFoodData().setFoodLevel(8); player.getFoodData().setSaturation(0);
            player.startUsingItem(InteractionHand.MAIN_HAND); player.stopUsingItem();
            helper.assertTrue(player.getHealth() == 40 && ((ClockworkBossPlayerData) player).clockworkBosses$potionReadyTick() == 0, "interrupted use grants no dose/cooldown");
            helper.assertTrue(UseItemCallback.EVENT.invoker().interact(player, player.level(), InteractionHand.MAIN_HAND) == InteractionResult.PASS, "owned main-hand potion passes native use gate");
            player.gameMode.useItem(player, player.level(), player.getMainHandItem(), InteractionHand.MAIN_HAND);
            helper.assertTrue(player.isUsingItem(), "native potion starts its normal drink");
        } catch (Throwable failure) { cleanup.run(); throw failure; }
        // Native dedicated-server connections drive doTick. These embedded
        // fixtures have no real socket, so advance that exact native path at
        // each test tick instead of faking completion or changing game logic.
        for (int tick = 1; tick <= 34; tick++) helper.runAtTickTime(tick, () -> {
            try { player.doTick(); } catch (Throwable failure) { cleanup.run(); throw failure; }
        });
        helper.runAtTickTime(28, () -> {
            try { helper.assertTrue(player.getHealth() == 40 && ((ClockworkBossPlayerData) player).clockworkBosses$potionReadyTick() == 0, "no healing before normal drink completes: hp=" + player.getHealth() + ", ready=" + ((ClockworkBossPlayerData) player).clockworkBosses$potionReadyTick() + ", useTicks=" + player.getUseItemRemainingTicks()); }
            catch (Throwable failure) { cleanup.run(); throw failure; }
        });
        helper.runAtTickTime(36, () -> {
            try {
                helper.assertTrue(player.getHealth() == 60 && ClockworkBossFightPotion.isPotion(player.getMainHandItem()) && player.getMainHandItem().getCount() == 1, "one completed native drink heals once and reuses bottle: hp=" + player.getHealth() + ", remaining=" + player.getUseItemRemainingTicks() + ", bottle=" + player.getMainHandItem());
                helper.assertTrue(((ClockworkBossPlayerData) player).clockworkBosses$potionReadyTick() > player.level().getServer().overworld().getGameTime(), "persistent server cooldown claimed");
                helper.assertTrue(UseItemCallback.EVENT.invoker().interact(player, player.level(), InteractionHand.MAIN_HAND) == InteractionResult.FAIL, "main-hand copy shares cooldown");
                var copy = player.getMainHandItem().copy();
                inventory.setItem(3, inventory.removeItemNoUpdate(Inventory.SLOT_OFFHAND)); inventory.setItem(Inventory.SLOT_OFFHAND, copy);
                helper.assertTrue(UseItemCallback.EVENT.invoker().interact(player, player.level(), InteractionHand.OFF_HAND) == InteractionResult.FAIL, "offhand copy shares same cooldown");
                inventory.setSelectedSlot(0); // Return to two-handed weapon, hiding the copied bottle.
                player.snapTo(boss.position().add(100, 0, 0));
            } catch (Throwable failure) { cleanup.run(); throw failure; }
        });
        helper.runAtTickTime(52, () -> {
            try {
                for (int slot = 0; slot < inventory.getContainerSize(); slot++) helper.assertTrue(!ClockworkBossFightPotion.isPotion(inventory.getItem(slot)), "arena exit clears visible/hidden bottle copies");
                helper.assertTrue(inventory.getItem(3).is(Items.IRON_SWORD) && inventory.getItem(0).is(Items.IRON_SWORD), "weapon swaps and cleanup preserve both personal weapons");
                helper.succeed();
            } finally { cleanup.run(); }
        });
    }

    @GameTest public void bettercombat_server_hits_speed_crit_scale_and_phase(GameTestHelper helper) throws Exception {
        Warden boss = boss(helper, "damage", false);
        try (var fixture = player(helper, boss.position().add(1.8, 0, 0))) {
            var player = fixture.player; player.setItemInHand(InteractionHand.MAIN_HAND, weapon("sword"));
            player.getAttribute(Attributes.ATTACK_DAMAGE).setBaseValue(20); HITS.put(boss.getUUID(), 0);
            for (double speed : new double[]{1, 4, 8}) {
                player.getAttribute(Attributes.ATTACK_SPEED).setBaseValue(speed);
                boss.setInvulnerableTime(0); boss.damageCooldownTime = 0; var health = boss.getHealth(); int hits = HITS.get(boss.getUUID());
                attack(player, 0, boss);
                helper.assertTrue(boss.getHealth() < health && HITS.get(boss.getUUID()) == hits + 1, "one committed server hit at speed " + speed);
            }
            boss.setInvulnerableTime(0); boss.damageCooldownTime = 0; var beforeNormal = boss.getHealth();
            attack(player, 0, boss); float normal = beforeNormal - boss.getHealth();
            boss.setInvulnerableTime(0); boss.damageCooldownTime = 0; var beforeCrit = boss.getHealth();
            attack(player, 0, true, boss);
            helper.assertTrue(beforeCrit - boss.getHealth() > normal, "server falling-crit conditions increase committed damage: normal=" + normal + ", crit=" + (beforeCrit - boss.getHealth()) + ", canCrit=" + ((clockwork.bosses.mixin.PvpSweepProbe) player).clockworkTest$canCrit(boss));
            // Scaled hitboxes must use native range checks instead of treating
            // the boss's distant center as its closest attackable surface.
            boss.getAttribute(Attributes.SCALE).setBaseValue(2); boss.refreshDimensions();
            player.snapTo(boss.position().add(boss.getBbWidth() / 2 + 2, 0, 0));
            boss.setInvulnerableTime(0); boss.damageCooldownTime = 0; var health = boss.getHealth();
            attack(player, 0, boss); helper.assertTrue(boss.getHealth() < health, "native Better Combat path hits scaled boss at reachable surface");
            player.snapTo(boss.position().add(50, 0, 0)); boss.setInvulnerableTime(0); boss.damageCooldownTime = 0; health = boss.getHealth();
            attack(player, 0, boss); helper.assertTrue(boss.getHealth() == health, "server rejects out-of-reach target ID");
            player.snapTo(boss.position().add(1.8, 0, 0)); boss.setHealth(755); boss.setInvulnerableTime(0); boss.damageCooldownTime = 0;
            int before = HITS.get(boss.getUUID()); attack(player, 0, boss);
            helper.assertTrue(boss.getHealth() < 750 && HITS.get(boss.getUUID()) == before + 1, "threshold-crossing attack lands once");
            // This fixture disables AI. Invoke the same phase boundary used
            // before native Brain.tick; damage itself is never replayed.
            ClockworkBossManager.prepareCombatPhase(boss);
            helper.assertTrue(ClockworkBossPhase.AGITATED.equals(ClockworkBossManager.stateFor(boss).currentPhase()), "same committed hit advances health phase");
            helper.assertTrue(HITS.get(boss.getUUID()) == before + 1, "phase application adds no second damage event");
            helper.succeed();
        } finally { HITS.remove(boss.getUUID()); boss.discard(); }
    }

    /** Better Combat's multi-target swing, including its knockback path, must
     * preserve the existing policy for participants and outsiders in both directions. */
    @GameTest public void bettercombat_pvp_sweep_knockback_and_fire_aspect(GameTestHelper helper) throws Exception {
        Warden boss = boss(helper, "pvp", true);
        try (var inside = player(helper, boss.position().add(1, 0, 0)); var outside = player(helper, boss.position().add(5, 0, 0))) {
            var attacker = outside.player; var victim = inside.player;
            var sword = weapon("sword");
            sword.enchant(helper.getLevel().registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT).getOrThrow(net.minecraft.world.item.enchantment.Enchantments.FIRE_ASPECT), 1);
            attacker.setItemInHand(InteractionHand.MAIN_HAND, sword); attacker.getAttribute(Attributes.ENTITY_INTERACTION_RANGE).setBaseValue(6);
            var cow = helper.spawn(EntityTypes.COW, new Vec3(6, 2, 3)); cow.snapTo(victim.position().add(.5, 0, 0)); cow.setNoAi(true); cow.setNoGravity(true);
            var health = cow.getHealth(); var velocity = victim.getDeltaMovement();
            attack(attacker, 0, victim, cow);
            helper.assertTrue(victim.getHealth() == 100 && victim.getDeltaMovement().equals(velocity) && !victim.isOnFire(), "BC incoming sweep has no player damage/push/fire");
            helper.assertTrue(cow.getHealth() < health, "same BC multi-target attack still hits an ordinary mob");
            victim.setItemInHand(InteractionHand.MAIN_HAND, weapon("sword")); victim.getAttribute(Attributes.ENTITY_INTERACTION_RANGE).setBaseValue(6);
            var outsiderVelocity = attacker.getDeltaMovement(); attack(victim, 0, attacker);
            helper.assertTrue(attacker.getHealth() == 100 && attacker.getDeltaMovement().equals(outsiderVelocity), "BC outgoing attack cannot exploit participant protection");
            cow.discard(); helper.succeed();
        } finally { boss.discard(); }
    }

    @GameTest public void bettercombat_dual_wield_inventory_and_attacks(GameTestHelper helper) throws Exception {
        Warden boss = boss(helper, "dual", false);
        try (var fixture = player(helper, boss.position().add(1.8, 0, 0))) {
            var player = fixture.player;
            player.setItemInHand(InteractionHand.MAIN_HAND, weapon("sword"));
            player.setItemInHand(InteractionHand.OFF_HAND, weapon("axe"));
            helper.assertTrue(net.bettercombat.logic.PlayerAttackHelper.isDualWielding(player), "real registry recognizes dual wielding");
            ClockworkBossFightPotion.enter(boss.getUUID(), player, ClockworkBossManager.stateFor(boss).playerSupport().potion());
            helper.assertTrue(player.getOffhandItem().is(Items.IRON_SWORD) && ClockworkBossFightPotion.isPotion(player.getInventory().getItem(1)), "dual-wield weapon stays put and potion uses inventory");
            HITS.put(boss.getUUID(), 0);
            attack(player, 0, boss); boss.setInvulnerableTime(0); boss.damageCooldownTime = 0;
            attack(player, 1, boss);
            helper.assertTrue(HITS.get(boss.getUUID()) == 2, "main/offhand BC attacks each commit exactly one hit");
            ClockworkBossFightPotion.releaseBoss(boss.getUUID());
            helper.assertTrue(player.getMainHandItem().is(Items.IRON_SWORD) && player.getInventory().getItem(Inventory.SLOT_OFFHAND).is(Items.IRON_SWORD), "cleanup keeps both weapons");
            helper.succeed();
        } finally { HITS.remove(boss.getUUID()); boss.discard(); }
    }

    @GameTest(setupTicks = 100) public void bettercombat_hidden_equipment_survives_death(GameTestHelper helper) throws Exception {
        Warden boss = boss(helper, "death", false);
        var rules = helper.getLevel().getGameRules(); boolean keep = rules.get(net.minecraft.world.level.gamerules.GameRules.KEEP_INVENTORY);
        try (var fixture = player(helper, boss.position().add(1, 0, 0))) {
            var player = fixture.player; var inventory = player.getInventory();
            player.setItemInHand(InteractionHand.MAIN_HAND, weapon("claymore"));
            var shield = new ItemStack(Items.SHIELD); inventory.setItem(Inventory.SLOT_OFFHAND, shield);
            ClockworkBossFightPotion.enter(boss.getUUID(), player, ClockworkBossManager.stateFor(boss).playerSupport().potion());
            rules.set(net.minecraft.world.level.gamerules.GameRules.KEEP_INVENTORY, true, helper.getLevel().getServer());
            player.setInvulnerableTime(0); player.damageCooldownTime = 0;
            player.hurtServer(helper.getLevel(), player.damageSources().generic(), 1000);
            helper.assertTrue(!player.isAlive() && inventory.getItem(Inventory.SLOT_OFFHAND) == shield, "confirmed death keeps hidden personal equipment in its backing slot");
            for (int slot = 0; slot < inventory.getContainerSize(); slot++) helper.assertTrue(!ClockworkBossFightPotion.isPotion(inventory.getItem(slot)), "death leaves no marked bottle for inventory/grave capture");
            helper.succeed();
        } finally { rules.set(net.minecraft.world.level.gamerules.GameRules.KEEP_INVENTORY, keep, helper.getLevel().getServer()); boss.discard(); }
    }
}
