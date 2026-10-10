package clockwork.bosses;

import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.nbt.CompoundTag;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Handles the vanilla-arrow selection tool used to define template areas.
 *
 * Activation selection:
 * - AREA: left-click corner 1, right-click corner 2.
 * - REDSTONE: click one block to make it the redstone trigger.
 * - Sneak + right-click cycles between AREA and REDSTONE.
 *
 * Arena selection (boss-bar visibility and player effects):
 * - Always AREA: left-click corner 1, right-click corner 2.
 * - The activation selector can copy the same box to boss-bar visibility.
 * - Mode switching is disabled for boss-bar and combined selections.
 */
public final class ClockworkBossSelector {
    private static final String SELECTOR_KEY = "ClockworkBosses_Selector";
    private static final String ACTIVE_KEY = "ClockworkBosses_SelectorActive";
    private static final String TEMPLATE_KEY = "ClockworkBosses_SelectorTemplate";
    private static final String SESSION_KEY = "ClockworkBosses_SelectorSession";
    private static final String OWNER_KEY = "ClockworkBosses_SelectorOwner";
    private static final String MODE_KEY = "ClockworkBosses_SelectorMode";
    private static final String TARGET_KEY = "ClockworkBosses_SelectorTarget";
    private static final String POS1_X = "ClockworkBosses_Pos1X";
    private static final String POS1_Y = "ClockworkBosses_Pos1Y";
    private static final String POS1_Z = "ClockworkBosses_Pos1Z";

    private static final String MODE_AREA = "player_enter_area";
    private static final String MODE_REDSTONE = "redstone";
    private static final String TARGET_ACTIVATION = "activation";
    // Keep the saved selector identifier; the public command and labels say "arena".
    private static final String TARGET_BOSS_BAR = "bossbar";

    private static final Map<UUID, SelectionSession> SESSIONS = new HashMap<>();

    private ClockworkBossSelector() {}

    /**
     * Registers common interaction callbacks. Client metadata identifies the tool;
     * only the server's matching session can save template changes.
     */
    public static void register() {
        // Left-clicking a block selects corner 1 in AREA mode, or the single
        // redstone trigger block in REDSTONE mode.
        AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> {
            ItemStack stack = player.getItemInHand(hand);
            if (!isActiveSelector(stack, player, !level.isClientSide())) {
                return InteractionResult.PASS;
            }

            if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
                SelectionSession session = SESSIONS.get(serverPlayer.getUUID());
                if (session != null) {
                    if (TARGET_ACTIVATION.equals(session.target())
                            && MODE_REDSTONE.equals(session.mode())) {
                        setRedstonePosition(serverPlayer, pos);
                    } else {
                        setFirstPosition(serverPlayer, pos);
                    }
                }
            }

            // CONSUME sends Fabric's native selection packet without reporting
            // a successful mining swing. The client pre-attack hook also stops
            // Minecraft 26.3's separate punch packet and held-button continuation.
            return InteractionResult.CONSUME;
        });

        // Right-clicking a block selects corner 2 in AREA mode. Sneak + right-click
        // changes selector mode instead, matching the idea of a tool with switchable modes.
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            ItemStack stack = player.getItemInHand(hand);
            if (!isActiveSelector(stack, player, !level.isClientSide())) {
                return InteractionResult.PASS;
            }

            if (player.isShiftKeyDown()) {
                if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
                    SelectionSession session = SESSIONS.get(serverPlayer.getUUID());
                    if (session != null && TARGET_ACTIVATION.equals(session.target())) {
                        cycleMode(serverPlayer);
                    }
                }
                return InteractionResult.SUCCESS;
            }

            if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
                SelectionSession session = SESSIONS.get(serverPlayer.getUUID());
                if (session != null) {
                    if (TARGET_ACTIVATION.equals(session.target())
                            && MODE_REDSTONE.equals(session.mode())) {
                        setRedstonePosition(serverPlayer, hit.getBlockPos());
                    } else {
                        setSecondPosition(serverPlayer, hit.getBlockPos());
                    }
                }
            }

            // Prevent ordinary right-click behavior on the selected block.
            return InteractionResult.SUCCESS;
        });

        // Sneak + right-click in the air also cycles modes. A vanilla arrow does not
        // have a useful right-click-air action, so this does not conflict with normal use.
        UseItemCallback.EVENT.register((player, level, hand) -> {
            ItemStack stack = player.getItemInHand(hand);
            if (!isActiveSelector(stack, player, !level.isClientSide())) {
                return InteractionResult.PASS;
            }

            if (!player.isShiftKeyDown()) {
                return InteractionResult.PASS;
            }

            if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
                SelectionSession session = SESSIONS.get(serverPlayer.getUUID());
                if (session != null && TARGET_ACTIVATION.equals(session.target())) {
                    cycleMode(serverPlayer);
                }
            }

            return InteractionResult.SUCCESS;
        });

        // Cancel runtime selection sessions when the server stops.
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> SESSIONS.clear());

        // Remove stale selector arrows when a player reconnects after a restart/crash.
        ServerPlayerEvents.JOIN.register(ClockworkBossSelector::removeSelectorArrow);

        // Native player events run on the server thread. LEAVE precedes saving
        // inventory; a Netty disconnect callback cannot safely edit that inventory.
        ServerPlayerEvents.LEAVE.register(ClockworkBossSelector::cancelInternal);
        ServerPlayerEvents.COPY_FROM.register((oldPlayer, newPlayer, alive) -> {
            cancelInternal(oldPlayer);
            removeSelectorArrow(newPlayer);
        });
    }

    /**
     * Starts a selection session for a template and gives the player a marked arrow.
     * The initial mode follows the template's current activation type when possible.
     */
    public static int begin(
            net.minecraft.commands.CommandSourceStack source,
            String templateName,
            String target
    ) {
        return begin(source, templateName, target, false);
    }

    /**
     * Starts an activation-area selection and can copy the same two-corner box
     * into the template's boss-bar visibility setting when the selection ends.
     */
    public static int begin(
            net.minecraft.commands.CommandSourceStack source,
            String templateName,
            String target,
            boolean alsoBossbar
    ) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("This command must be run by a player."));
            return 0;
        }

        if (!ClockworkBossTemplateStore.exists(templateName)) {
            source.sendFailure(Component.literal("Template does not exist: " + templateName));
            return 0;
        }

        if (!TARGET_ACTIVATION.equals(target) && !TARGET_BOSS_BAR.equals(target)) {
            source.sendFailure(Component.literal("Unknown selector target: " + target));
            return 0;
        }
        if (alsoBossbar && !TARGET_ACTIVATION.equals(target)) {
            source.sendFailure(Component.literal(
                    "The also-bossbar option is only available while selecting activation."));
            return 0;
        }

        cancelInternal(player);

        String initialMode = TARGET_BOSS_BAR.equals(target) || alsoBossbar
                ? MODE_AREA
                : readTemplateMode(templateName);

        SelectionSession session = new SelectionSession(
                UUID.randomUUID(),
                templateName,
                target,
                initialMode,
                alsoBossbar
        );

        ItemStack selector = createSelectorArrow(
                templateName,
                session.sessionId(),
                player.getUUID(),
                target,
                initialMode
        );

        ItemStack selectorIdentity = selector.copy();
        if (!player.addItem(selector)) {
            source.sendFailure(Component.literal(
                    "Your inventory is full; I could not give you the selector arrow."));
            return 0;
        }

        // Put the new tool in the player's hand. If the hotbar is full, swap it
        // with the selected slot and preserve that item in the selector's old slot.
        selectGivenSelectorArrow(player, selectorIdentity);

        SESSIONS.put(player.getUUID(), session);

        String instructions = alsoBossbar
                ? "Left-click corner 1, then right-click corner 2; this area will also control boss-bar visibility and player effects."
                : TARGET_BOSS_BAR.equals(target)
                        ? "Left-click corner 1, then right-click corner 2."
                        : "Left-click corner 1, right-click corner 2, or sneak + right-click to switch to REDSTONE.";

        source.sendSuccess(() -> Component.literal(
                "Selecting " + displayTarget(target) + " for \"")
                .append(Component.literal(templateName).withStyle(ChatFormatting.AQUA))
                .append(Component.literal("\". "))
                .append(Component.literal("Mode: " + displayMode(initialMode) + ". ")
                        .withStyle(modeColor(initialMode)))
                .append(Component.literal(instructions).withStyle(ChatFormatting.GOLD)), false);
        return 1;
    }

    /**
     * Cancels the player's current selection session and removes the selector arrow.
     */
    public static int cancel(net.minecraft.commands.CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("This command must be run by a player."));
            return 0;
        }

        if (SESSIONS.remove(player.getUUID()) == null) {
            source.sendFailure(Component.literal(
                    "You do not have an active Clockwork Boss selection."));
            return 0;
        }

        removeSelectorArrow(player);
        source.sendSuccess(() -> Component.literal(
                "Clockwork Boss selection cancelled.").withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    /**
     * Returns the current selector mode stored on a selector arrow.
     */
    public static String getMode(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return MODE_AREA;
        }

        String mode = ClockworkBossLegacyData.selector(data.copyTag()).getStringOr(MODE_KEY, MODE_AREA);
        return isKnownMode(mode) ? mode : MODE_AREA;
    }

    /** Returns whether this selector is configuring activation or boss-bar visibility. */
    public static String getSelectionTarget(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return TARGET_ACTIVATION;
        }

        String target = ClockworkBossLegacyData.selector(data.copyTag()).getStringOr(TARGET_KEY, TARGET_ACTIVATION);
        return TARGET_BOSS_BAR.equals(target) ? TARGET_BOSS_BAR : TARGET_ACTIVATION;
    }

    /**
     * Returns true when the given activation mode is one of the modes supported by the selector.
     */
    public static boolean isKnownMode(String mode) {
        return MODE_AREA.equals(mode) || MODE_REDSTONE.equals(mode);
    }

    /**
     * Records corner 1 for an AREA selection.
     */
    private static void setFirstPosition(ServerPlayer player, BlockPos pos) {
        SelectionSession session = SESSIONS.get(player.getUUID());
        if (session == null) {
            return;
        }

        if (session.isDuplicateInteraction(player.level().getGameTime(), pos, "area_corner_1")) {
            return;
        }

        session.setPos1(pos.immutable());
        session.firstCornerDimension = player.level().dimension().identifier().toString();
        writeFirstPositionToArrow(player, session);

        player.sendSystemMessage(Component.literal(
                "Area corner 1 set: " + pos.getX() + ", "
                        + pos.getY() + ", " + pos.getZ()).withStyle(ChatFormatting.GREEN));
    }

    /**
     * Records corner 2, saves the rectangular area, and ends the selection session.
     */
    private static void setSecondPosition(ServerPlayer player, BlockPos pos) {
        SelectionSession session = SESSIONS.get(player.getUUID());
        if (session == null || session.pos1() == null) {
            player.sendSystemMessage(Component.literal(
                    "Set area corner 1 first by left-clicking a block.").withStyle(ChatFormatting.GOLD));
            return;
        }

        if (session.isDuplicateInteraction(player.level().getGameTime(), pos, "area_corner_2")) {
            return;
        }

        if (!player.level().dimension().identifier().toString().equals(session.firstCornerDimension)) {
            player.sendSystemMessage(Component.literal("Both corners must be in the same dimension; set corner 1 here first.")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        if (!ClockworkBossManager.isAreaSizeAllowed(session.pos1(), pos)) {
            player.sendSystemMessage(Component.literal(
                    "Selection is too large. Each side must be 256 blocks or less.").withStyle(ChatFormatting.RED));
            return;
        }

        int minX = Math.min(session.pos1().getX(), pos.getX());
        int minY = Math.min(session.pos1().getY(), pos.getY());
        int minZ = Math.min(session.pos1().getZ(), pos.getZ());
        int maxX = Math.max(session.pos1().getX(), pos.getX());
        int maxY = Math.max(session.pos1().getY(), pos.getY());
        int maxZ = Math.max(session.pos1().getZ(), pos.getZ());

        try {
            JsonObject config = ClockworkBossTemplateStore.load(session.templateName()).deepCopy();

            if (TARGET_BOSS_BAR.equals(session.target())) {
                JsonObject bossBar = config.has("boss_bar") && config.get("boss_bar").isJsonObject()
                        ? config.getAsJsonObject("boss_bar")
                        : new JsonObject();
                JsonObject area = new JsonObject();
                area.addProperty("shape", "box");
                area.addProperty("dimension",
                        player.level().dimension().identifier().toString());
                area.add("min", blockToJson(minX, minY, minZ));
                area.add("max", blockToJson(maxX, maxY, maxZ));
                preserveRadius(bossBar, "visibility_area", area);
                bossBar.add("visibility_area", area);
                config.add("boss_bar", bossBar);

                ClockworkBossTemplateStore.save(session.templateName(), config);

                player.sendSystemMessage(Component.literal(
                        "Arena area saved (boss bar and player effects): "
                                + (maxX - minX + 1) + " x "
                                + (maxY - minY + 1) + " x "
                                + (maxZ - minZ + 1)).withStyle(ChatFormatting.GREEN));
            } else {
                JsonObject activation = new JsonObject();
                activation.addProperty("type", MODE_AREA);
                activation.addProperty("shape", "box");
                activation.addProperty(
                        "dimension",
                        player.level().dimension().identifier().toString()
                );
                activation.add("min", blockToJson(minX, minY, minZ));
                activation.add("max", blockToJson(maxX, maxY, maxZ));
                preserveRadius(config, "activation", activation);
                config.add("activation", activation);

                if (session.alsoBossbar()) {
                    // Copy the exact activation cuboid and dimension so the user
                    // only has to mark the shared area once. Keep boss-bar styling.
                    JsonObject bossBar = config.has("boss_bar") && config.get("boss_bar").isJsonObject()
                            ? config.getAsJsonObject("boss_bar")
                            : new JsonObject();
                    JsonObject visibilityArea = new JsonObject();
                    visibilityArea.addProperty("shape", "box");
                    visibilityArea.addProperty(
                            "dimension", player.level().dimension().identifier().toString());
                    visibilityArea.add("min", blockToJson(minX, minY, minZ));
                    visibilityArea.add("max", blockToJson(maxX, maxY, maxZ));
                    preserveRadius(bossBar, "visibility_area", visibilityArea);
                    bossBar.add("visibility_area", visibilityArea);
                    config.add("boss_bar", bossBar);
                }

                ClockworkBossTemplateStore.save(session.templateName(), config);

                player.sendSystemMessage(Component.literal(
                        "Player-enter-area activation saved: "
                                + (maxX - minX + 1) + " x "
                                + (maxY - minY + 1) + " x "
                                + (maxZ - minZ + 1)).withStyle(ChatFormatting.GREEN));
                if (session.alsoBossbar()) {
                    player.sendSystemMessage(Component.literal(
                            "The same area was also saved for boss-bar visibility and player effects.").withStyle(ChatFormatting.GREEN));
                }
            }

            cancelInternal(player);
        } catch (Exception e) {
            player.sendSystemMessage(Component.literal(
                    "Could not save selection: " + e.getMessage()).withStyle(ChatFormatting.RED));
        }
    }

    /**
     * Saves one clicked block as the redstone activation trigger and ends the session.
     */
    private static void setRedstonePosition(ServerPlayer player, BlockPos pos) {
        SelectionSession session = SESSIONS.get(player.getUUID());
        if (session == null || !TARGET_ACTIVATION.equals(session.target())) {
            return;
        }

        try {
            JsonObject config = ClockworkBossTemplateStore.load(session.templateName()).deepCopy();
            JsonObject activation = new JsonObject();
            activation.addProperty("type", MODE_REDSTONE);
            activation.addProperty(
                    "dimension",
                    player.level().dimension().identifier().toString()
            );
            activation.add("position", blockToJson(pos.getX(), pos.getY(), pos.getZ()));
            preserveRadius(config, "activation", activation);
            config.add("activation", activation);

            ClockworkBossTemplateStore.save(session.templateName(), config);

            player.sendSystemMessage(Component.literal(
                    "Redstone activation block saved: "
                            + pos.getX() + ", " + pos.getY() + ", " + pos.getZ()).withStyle(ChatFormatting.GREEN));
            cancelInternal(player);
        } catch (Exception e) {
            player.sendSystemMessage(Component.literal(
                    "Could not save redstone activation: " + e.getMessage()).withStyle(ChatFormatting.RED));
        }
    }

    /**
     * Toggles between the two selector modes and updates the marked arrow immediately.
     * Existing AREA corner data is cleared because it has no meaning in REDSTONE mode.
     */
    private static void cycleMode(ServerPlayer player) {
        SelectionSession session = SESSIONS.get(player.getUUID());
        if (session == null) {
            return;
        }

        if (session.alsoBossbar()) {
            player.sendSystemMessage(Component.literal(
                    "This combined selection needs an AREA; its mode cannot switch to REDSTONE.")
                    .withStyle(ChatFormatting.GOLD));
            return;
        }

        if (session.isDuplicateInteraction(player.level().getGameTime(), null, "mode")) {
            return;
        }

        String nextMode = MODE_AREA.equals(session.mode())
                ? MODE_REDSTONE
                : MODE_AREA;

        session.setMode(nextMode);
        session.clearPositions();
        updateSelectorModeOnArrows(player, session);

        player.sendSystemMessage(Component.literal(
                "Clockwork Boss activation mode: " + displayMode(nextMode)
                        + ".").withStyle(modeColor(nextMode)));

        if (MODE_AREA.equals(nextMode)) {
            player.sendSystemMessage(Component.literal(
                    "Left-click corner 1, then right-click corner 2.").withStyle(ChatFormatting.GOLD));
        } else {
            player.sendSystemMessage(Component.literal(
                    "Click one block to use it as the redstone trigger.").withStyle(ChatFormatting.GOLD));
        }
    }

    /**
     * Writes the current mode to the selector arrows and clears any old corner data.
     */
    private static void updateSelectorModeOnArrows(
            ServerPlayer player,
            SelectionSession session
    ) {
        updateSelectorArrows(player, stack -> {
            CompoundTag tag = getCustomTag(stack);
            if (tag == null) {
                return;
            }

            tag.putString(TARGET_KEY, session.target());
            tag.putString(MODE_KEY, session.mode());
            tag.remove(POS1_X);
            tag.remove(POS1_Y);
            tag.remove(POS1_Z);
            updateSelectorName(stack, session.target(), session.mode());
            CustomData.set(DataComponents.CUSTOM_DATA, stack, tag);
        });
    }

    /**
     * Creates the marked vanilla arrow used by the current selection session.
     */
    private static ItemStack createSelectorArrow(
            String templateName,
            UUID sessionId,
            UUID ownerId,
            String target,
            String mode
    ) {
        ItemStack stack = new ItemStack(Items.ARROW, 1);
        CompoundTag tag = new CompoundTag();
        tag.putBoolean(SELECTOR_KEY, true);
        tag.putBoolean(ACTIVE_KEY, true);
        tag.putString(TEMPLATE_KEY, templateName);
        tag.putString(SESSION_KEY, sessionId.toString());
        tag.putString(OWNER_KEY, ownerId.toString());
        tag.putString(TARGET_KEY, target);
        tag.putString(MODE_KEY, mode);
        CustomData.set(DataComponents.CUSTOM_DATA, stack, tag);
        // This has no gameplay effect; the glint makes the special arrow easy to
        // find among ordinary arrows in the inventory.
        stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        updateSelectorName(stack, target, mode);
        return stack;
    }

    /** Selects the newly-given selector arrow without deleting an existing item. */
    private static void selectGivenSelectorArrow(ServerPlayer player, ItemStack selector) {
        var inventory = player.getInventory();
        int selectorSlot = inventory.findSlotMatchingItem(selector);
        if (selectorSlot < 0) {
            return;
        }

        if (selectorSlot >= inventory.getSelectionSize()) {
            int selectedSlot = inventory.getSelectedSlot();
            ItemStack previouslyHeld = inventory.getItem(selectedSlot);
            ItemStack selectorInMainInventory = inventory.getItem(selectorSlot);
            inventory.setItem(selectorSlot, previouslyHeld);
            inventory.setItem(selectedSlot, selectorInMainInventory);
            selectorSlot = selectedSlot;
            player.containerMenu.broadcastChanges();
        }

        inventory.setSelectedSlot(selectorSlot);
        player.connection.send(
                new net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket(selectorSlot));
    }

    /**
     * Updates the selector's name and native text color for inventory and hotbar display.
     * The same mode colors are used in chat, so switching the tool gives a consistent cue.
     */
    private static void updateSelectorName(
            ItemStack stack,
            String target,
            String mode
    ) {
        String name = TARGET_BOSS_BAR.equals(target)
                ? "Clockwork Boss Selector [ARENA: AREA]"
                : "Clockwork Boss Selector [ACTIVATION: " + displayMode(mode) + "]";

        stack.set(
                DataComponents.CUSTOM_NAME,
                Component.literal(name).withStyle(modeColor(mode))
        );
    }

    /** Area selections are yellow; redstone selections are red, using vanilla text styling. */
    private static ChatFormatting modeColor(String mode) {
        return MODE_REDSTONE.equals(mode) ? ChatFormatting.RED : ChatFormatting.YELLOW;
    }

    /**
     * Determines whether an ItemStack is the active selector for this player.
     */
    public static boolean isActiveSelector(
            ItemStack stack,
            Player player,
            boolean serverSide
    ) {
        if (stack.isEmpty() || !stack.is(Items.ARROW)) {
            return false;
        }

        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return false;
        }

        CompoundTag tag = ClockworkBossLegacyData.selector(data.copyTag());
        if (!tag.getBooleanOr(SELECTOR_KEY, false)
                || !tag.getBooleanOr(ACTIVE_KEY, false)) {
            return false;
        }

        if (!tag.getStringOr(OWNER_KEY, "").equals(player.getUUID().toString())) {
            return false;
        }

        if (!serverSide) {
            return true;
        }

        // A selector issued while the player was an operator must stop editing
        // templates immediately if those permissions are later revoked.
        if (!(player instanceof ServerPlayer serverPlayer)
                || !serverPlayer.createCommandSourceStack().permissions()
                        .hasPermission(Permissions.COMMANDS_GAMEMASTER)) {
            if (player instanceof ServerPlayer formerOperator) cancelInternal(formerOperator);
            return false;
        }

        SelectionSession session = SESSIONS.get(player.getUUID());
        if (session == null) {
            return false;
        }

        return session.sessionId().toString().equals(
                        tag.getStringOr(SESSION_KEY, ""))
                && session.templateName().equals(
                        tag.getStringOr(TEMPLATE_KEY, ""))
                && session.target().equals(
                        tag.getStringOr(TARGET_KEY, TARGET_ACTIVATION))
                && session.mode().equals(
                        tag.getStringOr(MODE_KEY, MODE_AREA));
    }

    /**
     * Returns the first selected block stored on the selector arrow.
     */
    public static BlockPos getFirstPosition(ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }

        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return null;
        }

        CompoundTag tag = ClockworkBossLegacyData.selector(data.copyTag());
        if (!tag.getBooleanOr(SELECTOR_KEY, false)) {
            return null;
        }

        // Native typed NBT avoids string parsing in every selector-preview tick.
        var x = tag.getInt(POS1_X);
        var y = tag.getInt(POS1_Y);
        var z = tag.getInt(POS1_Z);
        return x.isPresent() && y.isPresent() && z.isPresent()
                ? new BlockPos(x.get(), y.get(), z.get()) : null;
    }

    /**
     * Reads the current activation mode already saved in the template JSON.
     */
    private static String readTemplateMode(String templateName) {
        try {
            JsonObject config = ClockworkBossTemplateStore.load(templateName);
            JsonObject activation = config.has("activation")
                    && config.get("activation").isJsonObject()
                    ? config.getAsJsonObject("activation")
                    : null;

            if (activation != null) {
                String type = activation.get("type") != null
                        ? activation.get("type").getAsString()
                        : MODE_AREA;
                if (isKnownMode(type)) {
                    return type;
                }
            }
        } catch (Exception ignored) {
            // Fall back to AREA when the template does not have a usable activation type.
        }

        return MODE_AREA;
    }

    /**
     * Writes corner 1 into every selector arrow currently held by the player.
     */
    private static void writeFirstPositionToArrow(
            ServerPlayer player,
            SelectionSession session
    ) {
        updateSelectorArrows(player, stack -> {
            CompoundTag tag = getCustomTag(stack);
            if (tag == null || session.pos1() == null) {
                return;
            }

            tag.putInt(POS1_X, session.pos1().getX());
            tag.putInt(POS1_Y, session.pos1().getY());
            tag.putInt(POS1_Z, session.pos1().getZ());
            CustomData.set(DataComponents.CUSTOM_DATA, stack, tag);
        });
    }

    /**
     * Applies a small mutation to selector arrows in the player's hands.
     */
    private static void updateSelectorArrows(
            ServerPlayer player,
            java.util.function.Consumer<ItemStack> consumer
    ) {
        ItemStack main = player.getMainHandItem();
        if (isMarkedSelectorArrow(main, player)) {
            consumer.accept(main);
        }

        ItemStack offhand = player.getOffhandItem();
        if (isMarkedSelectorArrow(offhand, player)) {
            consumer.accept(offhand);
        }
    }

    /**
     * Returns a copy of selector custom data, or null for a normal arrow.
     */
    private static CompoundTag getCustomTag(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data == null ? null : ClockworkBossLegacyData.selector(data.copyTag());
    }

    /**
     * Identifies a selector arrow without requiring an active runtime session.
     */
    private static boolean isMarkedSelectorArrow(ItemStack stack, Player owner) {
        if (stack.isEmpty() || !stack.is(Items.ARROW)) {
            return false;
        }

        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return false;
        }

        CompoundTag tag = ClockworkBossLegacyData.selector(data.copyTag());
        return tag.getBooleanOr(SELECTOR_KEY, false)
                && tag.getBooleanOr(ACTIVE_KEY, false)
                && tag.getStringOr(OWNER_KEY, "")
                .equals(owner.getUUID().toString());
    }

    /**
     * Removes selector arrows from the player's hands/inventory.
     */
    private static void removeSelectorArrow(ServerPlayer player) {
        // Inventory includes both hands. Remove whole marked stacks and every
        // duplicate, preserving normal arrows and selectors belonging to others.
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            if (isMarkedSelectorArrow(player.getInventory().getItem(slot), player)) {
                player.getInventory().setItem(slot, ItemStack.EMPTY);
            }
        }
    }

    /**
     * Cancels a runtime session and removes its selector arrow.
     */
    private static void cancelInternal(ServerPlayer player) {
        SESSIONS.remove(player.getUUID());
        removeSelectorArrow(player);
    }

    /** Keep the editable radius when selecting a box/redstone point; inactive mode fields otherwise stay separate. */
    private static void preserveRadius(JsonObject parent, String section, JsonObject replacement) {
        JsonObject previous = parent.has(section) && parent.get(section).isJsonObject()
                ? parent.getAsJsonObject(section) : new JsonObject();
        if (previous.has("radius")) replacement.add("radius", previous.get("radius").deepCopy());
        else replacement.addProperty("radius", 32.0);
    }

    /** Creates a block-coordinate object for a saved selection corner or trigger point. */
    private static JsonObject blockToJson(int x, int y, int z) {
        JsonObject object = new JsonObject();
        object.addProperty("x", x);
        object.addProperty("y", y);
        object.addProperty("z", z);
        return object;
    }

    /**
     * Converts an internal mode name into a short human-readable label.
     */
    private static String displayMode(String mode) {
        return MODE_REDSTONE.equals(mode) ? "REDSTONE" : "AREA";
    }

    /** Converts a selector target into a short human-readable label. */
    private static String displayTarget(String target) {
        return TARGET_BOSS_BAR.equals(target) ? "arena (boss bar and player effects)" : "activation";
    }

    /**
     * Mutable server-side state for one player's selection session.
     */
    private static final class SelectionSession {
        private final UUID sessionId;
        private final String templateName;
        private final String target;
        private final boolean alsoBossbar;
        private String mode;
        private BlockPos pos1;
        private String firstCornerDimension;
        private long lastInteractionTick = Long.MIN_VALUE;
        private BlockPos lastInteractionPos;
        private String lastInteractionType = "";

        private SelectionSession(
                UUID sessionId,
                String templateName,
                String target,
                String mode,
                boolean alsoBossbar
        ) {
            this.sessionId = sessionId;
            this.templateName = templateName;
            this.target = target;
            this.mode = mode;
            this.alsoBossbar = alsoBossbar;
        }

        private UUID sessionId() {
            return sessionId;
        }

        private String templateName() {
            return templateName;
        }

        private String target() {
            return target;
        }

        private boolean alsoBossbar() {
            return alsoBossbar;
        }

        private String mode() {
            return mode;
        }

        private void setMode(String mode) {
            this.mode = mode;
        }

        private BlockPos pos1() {
            return pos1;
        }

        private void setPos1(BlockPos pos1) {
            this.pos1 = pos1;
        }

        private void clearPositions() {
            pos1 = null;
            firstCornerDimension = null;
        }

        /** Ignores duplicate callback invocations for the same physical interaction. */
        private boolean isDuplicateInteraction(long gameTime, BlockPos pos, String type) {
            if (lastInteractionTick == gameTime
                    && java.util.Objects.equals(lastInteractionPos, pos)
                    && lastInteractionType.equals(type)) {
                return true;
            }

            lastInteractionTick = gameTime;
            lastInteractionPos = pos == null ? null : pos.immutable();
            lastInteractionType = type;
            return false;
        }

    }
}
