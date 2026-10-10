package clockwork.bosses;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.component.UseCooldown;
import net.minecraft.world.item.component.TooltipDisplay;

/** One owner-bound reusable vanilla bottle per participant. Native drinking and
 * cooldown UI handle presentation; the server alone validates and grants a dose.
 * Membership changes, not another timer, own inventory and reference cleanup. */
public final class ClockworkBossFightPotion {
    private static final String MARKER = "ClockworkBosses_FightPotion", OWNER = "ClockworkBosses_PotionOwner";
    private static final Identifier GROUP = Identifier.fromNamespaceAndPath(ClockworkBosses.MOD_ID, "fight_potion");
    private static final Map<UUID, Member> PLAYERS = new HashMap<>();
    private ClockworkBossFightPotion() {}
    private static final class Member {
        final ServerPlayer player;
        final Map<UUID, ClockworkBossPlayerSupport.Potion> bosses = new HashMap<>();
        ClockworkBossPlayerSupport.Potion policy;
        int displacedSlot = -1;
        ItemStack displaced;
        Member(ServerPlayer player) { this.player = player; }
    }

    /** No custom item registry or client payload: components describe a vanilla
     * potion. Respawn/reconnect preserve the server's single shared deadline. */
    public static void registerLifecycle() {
        UseItemCallback.EVENT.register((player, level, hand) -> {
            ItemStack stack = player.getItemInHand(hand);
            if (!isPotion(stack)) return InteractionResult.PASS;
            if (!owned(stack, player.getUUID()) || player.getCooldowns().isOnCooldown(stack)) return InteractionResult.FAIL;
            if (player instanceof ServerPlayer serverPlayer && available(serverPlayer) == null) return InteractionResult.FAIL;
            return InteractionResult.PASS;
        });
        ServerPlayerEvents.JOIN.register(ClockworkBossFightPotion::clearBottles);
        ServerPlayerEvents.LEAVE.register(player -> releasePlayer(player.getUUID()));
        ServerPlayerEvents.COPY_FROM.register((oldPlayer, newPlayer, alive) -> {
            releasePlayer(oldPlayer.getUUID()); clearBottles(newPlayer);
            ((ClockworkBossPlayerData) newPlayer).clockworkBosses$setPotionReadyTick(
                    ((ClockworkBossPlayerData) oldPlayer).clockworkBosses$potionReadyTick());
        });
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (entity instanceof ItemEntity item && isPotion(item.getItem())) item.discard();
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            for (UUID player : List.copyOf(PLAYERS.keySet())) releasePlayer(player);
            PLAYERS.clear();
        });
    }

    public static boolean isPotion(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return !stack.isEmpty() && stack.is(Items.POTION) && data != null && data.copyTag().getBooleanOr(MARKER, false);
    }
    private static boolean owned(ItemStack stack, UUID player) {
        return isPotion(stack) && player.toString().equals(stack.get(DataComponents.CUSTOM_DATA).copyTag().getStringOr(OWNER, ""));
    }
    private static long now(ServerPlayer player) { return player.level().getServer().overworld().getGameTime(); }

    /** Choose a complete policy, never stack doses across overlapping encounters.
     * Equal healing uses a stable boss UUID order; input/completion also check
     * exact current geometry rather than trusting the half-second membership diff. */
    private static ClockworkBossPlayerSupport.Potion choose(Member member, boolean live) {
        UUID chosen = null;
        ClockworkBossPlayerSupport.Potion result = null;
        for (var entry : member.bosses.entrySet()) {
            if (live && !ClockworkBossManager.isPlayerInArena(entry.getKey(), member.player)) continue;
            if (result == null || entry.getValue().healHp() > result.healHp()
                    || entry.getValue().healHp() == result.healHp() && entry.getKey().compareTo(chosen) < 0) {
                chosen = entry.getKey(); result = entry.getValue();
            }
        }
        return result;
    }
    private static ClockworkBossPlayerSupport.Potion available(ServerPlayer player) {
        Member member = PLAYERS.get(player.getUUID());
        if (member == null || member.player != player || !player.isAlive() || player.isSpectator() || player.hasDisconnected()
                || now(player) < ((ClockworkBossPlayerData) player).clockworkBosses$potionReadyTick()) return null;
        return choose(member, true);
    }

    /** Called only after the full vanilla drink. Claim the deadline before native
     * heal callbacks, so copies, transfers and reentrant uses cannot bypass it.
     * Flat food/saturation amounts are bounded by Minecraft's own food limits. */
    public static void finish(ServerPlayer player) {
        ItemStack used = player.getUseItem();
        if (!owned(used, player.getUUID()) || !ItemStack.isSameItemSameComponents(used, player.getItemInHand(player.getUsedItemHand()))) {
            if (ClockworkBossDiagnostics.enabled()) ClockworkBossDiagnostics.entity(player, "potion_rejected", "reason", "owner_or_changed_item");
            return;
        }
        var policy = available(player);
        if (policy == null) {
            if (ClockworkBossDiagnostics.enabled()) ClockworkBossDiagnostics.entity(player, "potion_rejected", "reason", "membership_lifecycle_or_cooldown", "ready_tick", ((ClockworkBossPlayerData) player).clockworkBosses$potionReadyTick());
            return;
        }
        boolean capture = ClockworkBossDiagnostics.enabled();
        float hpBefore = capture ? player.getHealth() : 0;
        int hungerBefore = capture ? player.getFoodData().getFoodLevel() : 0;
        float saturationBefore = capture ? player.getFoodData().getSaturationLevel() : 0;
        long time = now(player);
        ((ClockworkBossPlayerData) player).clockworkBosses$setPotionReadyTick(time > Long.MAX_VALUE - policy.cooldownTicks()
                ? Long.MAX_VALUE : time + policy.cooldownTicks());
        player.getCooldowns().addCooldown(GROUP, policy.cooldownTicks());
        player.heal(policy.healHp());
        var food = player.getFoodData();
        food.setFoodLevel(Math.min(20, food.getFoodLevel() + policy.hunger()));
        food.setSaturation(Math.min(food.getFoodLevel(), food.getSaturationLevel() + policy.saturation()));
        if (capture) ClockworkBossDiagnostics.entity(player, "potion_dose_observed",
                "hp_before", hpBefore, "hp_after", player.getHealth(), "requested_heal", policy.healHp(),
                "hunger_before", hungerBefore, "hunger_after", food.getFoodLevel(), "saturation_before", saturationBefore,
                "saturation_after", food.getSaturationLevel(), "ready_tick", ((ClockworkBossPlayerData) player).clockworkBosses$potionReadyTick(),
                "requested_hunger", policy.hunger(), "requested_saturation", policy.saturation(), "cooldown_ticks", policy.cooldownTicks());
    }

    /** Move an occupied offhand into a free native inventory slot. Snapshot its
     * contents for later ownership checks: other mods can copy stacks during
     * inventory operations. A full inventory prevents the gift, never deletion. */
    public static void enter(UUID boss, ServerPlayer player, ClockworkBossPlayerSupport.Potion settings) {
        if (!settings.enabled()) { leave(boss, player.getUUID()); return; }
        Member member = PLAYERS.get(player.getUUID());
        if (member != null && member.player != player) { releasePlayer(player.getUUID()); member = null; }
        if (member == null) { member = new Member(player); PLAYERS.put(player.getUUID(), member); }
        member.bosses.put(boss, settings);
        if (ClockworkBossDiagnostics.enabled()) ClockworkBossDiagnostics.entity(player, "potion_request_added", "boss", ClockworkBossDiagnostics.alias(boss));
        var selected = choose(member, false);
        if (selected.equals(member.policy)) return;
        member.policy = selected;
        refreshBottle(member);
    }
    private static void refreshBottle(Member member) {
        ServerPlayer player = member.player;
        boolean found = false;
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (owned(stack, player.getUUID())) { decorate(stack, player.getUUID(), member.policy); found = true; }
        }
        if (!found) {
            ItemStack offhand = player.getOffhandItem();
            if (!offhand.isEmpty()) {
                int slot = inventory.getFreeSlot();
                if (slot < 0) {
                    if (ClockworkBossDiagnostics.enabled()) ClockworkBossDiagnostics.entity(player, "potion_gift_rejected", "reason", "full_inventory");
                    player.sendSystemMessage(Component.literal("Make one inventory slot free and reenter the arena to receive your fight potion.").withStyle(ChatFormatting.YELLOW));
                    return;
                }
                // Native add consumes the held input while moving it to the
                // free slot. Avoid temporarily sharing one mutable stack between
                // offhand and inventory: stack-merging mods can change that input.
                // A refused/partial transfer keeps all remaining items and gives
                // no bottle; the comparison snapshot is never issued as an item.
                if (!inventory.add(slot, offhand) || !offhand.isEmpty() || !player.getOffhandItem().isEmpty()
                        || inventory.getItem(slot).isEmpty()) {
                    if (ClockworkBossDiagnostics.enabled()) ClockworkBossDiagnostics.entity(player, "potion_gift_rejected", "reason", "inventory_transfer_refused");
                    return;
                }
                member.displacedSlot = slot;
                // Snapshot the actual stored result: inventory integrations may
                // normalize components (the pack makes stored totems stackable).
                // Later edits/moves still invalidate restoration; only the live
                // stored item can be moved back, never these comparison bytes.
                member.displaced = inventory.getItem(slot).copy();
                if (ClockworkBossDiagnostics.enabled()) ClockworkBossDiagnostics.entity(player, "offhand_displaced", "inventory_slot", slot);
            }
            ItemStack bottle = new ItemStack(Items.POTION);
            decorate(bottle, player.getUUID(), member.policy);
            player.setItemInHand(InteractionHand.OFF_HAND, bottle);
            if (ClockworkBossDiagnostics.enabled()) ClockworkBossDiagnostics.entity(player, "potion_gift", "encounter_count", member.bosses.size());
        }
        long remaining = Math.max(0, ((ClockworkBossPlayerData) player).clockworkBosses$potionReadyTick() - now(player));
        if (remaining > 0) player.getCooldowns().addCooldown(GROUP, (int) Math.min(12000, remaining));
        sync(player);
    }
    private static void decorate(ItemStack stack, UUID player, ClockworkBossPlayerSupport.Potion policy) {
        CompoundTag tag = new CompoundTag(); tag.putBoolean(MARKER, true); tag.putString(OWNER, player.toString());
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("Reusable Fight Potion").withStyle(ChatFormatting.GOLD));
        stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        // Empty native effects ensure only the server-validated completion heals.
        stack.set(DataComponents.POTION_CONTENTS, new PotionContents(Optional.empty(), Optional.of(0xD92842), List.of(), Optional.empty()));
        stack.set(DataComponents.TOOLTIP_DISPLAY, stack.getOrDefault(DataComponents.TOOLTIP_DISPLAY, TooltipDisplay.DEFAULT)
                .withHidden(DataComponents.POTION_CONTENTS, true));
        stack.set(DataComponents.USE_COOLDOWN, new UseCooldown(policy.cooldownTicks() / 20f, Optional.of(GROUP)));
        stack.set(DataComponents.LORE, new ItemLore(List.of(
                Component.literal("Heals " + policy.healHp() + " HP • " + policy.cooldownTicks() / 20f + "s cooldown"),
                Component.literal("Restores " + policy.hunger() + " hunger / " + policy.saturation() + " saturation"),
                Component.literal("Reusable during an active encounter").withStyle(ChatFormatting.GRAY))));
    }

    public static void leave(UUID boss, UUID player) {
        Member member = PLAYERS.get(player);
        if (member == null || member.bosses.remove(boss) == null) return;
        if (ClockworkBossDiagnostics.enabled()) ClockworkBossDiagnostics.entity(member.player, "potion_request_removed", "boss", ClockworkBossDiagnostics.alias(boss));
        if (member.bosses.isEmpty()) { releasePlayer(player); return; }
        var selected = choose(member, false);
        if (!selected.equals(member.policy)) { member.policy = selected; refreshBottle(member); }
    }
    public static void releaseBoss(UUID boss) {
        for (Member member : List.copyOf(PLAYERS.values())) leave(boss, member.player.getUUID());
    }
    public static void releasePlayer(UUID player) {
        Member member = PLAYERS.remove(player);
        if (member == null) return;
        clearBottles(member.player);
        // Restore unchanged item contents in the recorded slot, into an empty
        // offhand. Identity is not reliable when another mod copies a stack.
        // Remove and transfer the live slot contents; the snapshot is comparison
        // data only and is never given back, preventing a duplicated/stale item.
        var inventory = member.player.getInventory();
        if (member.displaced != null && member.player.getOffhandItem().isEmpty()
                && sameDisplacedContents(inventory.getItem(member.displacedSlot), member.displaced)) {
            ItemStack restored = inventory.removeItemNoUpdate(member.displacedSlot);
            member.player.setItemInHand(InteractionHand.OFF_HAND, restored);
            if (ClockworkBossDiagnostics.enabled()) ClockworkBossDiagnostics.entity(member.player, "offhand_restored", "inventory_slot", member.displacedSlot);
            sync(member.player);
        }
    }
    /** Stackable-totem integrations can change capacity after storage/sync.
     * Capacity is not item ownership: permit only that component difference,
     * while requiring the exact count, item and all remaining components.
     * The transferred live stack keeps its current capacity and other data;
     * the comparison copy is never inserted into a player's inventory. */
    private static boolean sameDisplacedContents(ItemStack current, ItemStack saved) {
        if (current.isEmpty() || current.getCount() != saved.getCount()) return false;
        if (ItemStack.isSameItemSameComponents(current, saved)) return true;
        if (current.getMaxStackSize() == saved.getMaxStackSize()) return false;
        ItemStack comparison = saved.copy();
        comparison.set(DataComponents.MAX_STACK_SIZE, current.getMaxStackSize());
        return ItemStack.isSameItemSameComponents(current, comparison);
    }
    /** Event-boundary inventory cleanup only. Containers in the world are not
     * scanned; any stored copy remains owner-locked and unusable outside a fight. */
    private static void clearBottles(ServerPlayer player) {
        if (isPotion(player.getUseItem())) player.stopUsingItem();
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++)
            if (isPotion(inventory.getItem(slot))) inventory.setItem(slot, ItemStack.EMPTY);
        if (isPotion(player.containerMenu.getCarried())) player.containerMenu.setCarried(ItemStack.EMPTY);
        sync(player);
    }
    private static void sync(ServerPlayer player) {
        player.inventoryMenu.broadcastChanges();
        if (player.containerMenu != player.inventoryMenu) player.containerMenu.broadcastChanges();
    }
}
