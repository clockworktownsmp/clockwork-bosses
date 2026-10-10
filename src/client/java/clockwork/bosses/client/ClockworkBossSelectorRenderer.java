package clockwork.bosses.client;

import clockwork.bosses.ClockworkBossSelector;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Client-only renderer for the live Clockwork Boss activation selector.
 */
public final class ClockworkBossSelectorRenderer {
    private static final GizmoStyle SELECTION_STYLE =
            new GizmoStyle(0xFF55FFFF, 2.0F, 0x3355FFFF);

    private static final GizmoStyle REDSTONE_STYLE =
            new GizmoStyle(0xFFFF5555, 2.0F, 0x335555FF);

    private static final int CORNER_COLOR = 0xFFFF55FF;

    private ClockworkBossSelectorRenderer() {}

    /**
     * Registers the client tick callback used to draw the current selection preview.
     */
    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(
                ClockworkBossSelectorRenderer::renderSelection
        );
    }

    /**
     * Draws either an AREA cuboid or a REDSTONE single-block preview based on the selector mode.
     */
    private static void renderSelection(Minecraft client) {
        if (client.player == null) {
            return;
        }

        ItemStack selector = findHeldSelector(client);
        if (selector.isEmpty()) {
            return;
        }

        String target = ClockworkBossSelector.getSelectionTarget(selector);
        String mode = ClockworkBossSelector.getMode(selector);
        if ("activation".equals(target) && "redstone".equals(mode)) {
            renderRedstonePreview(client);
            return;
        }

        BlockPos first = ClockworkBossSelector.getFirstPosition(selector);
        if (first == null) {
            renderAreaFirstCornerPreview(client);
            return;
        }

        Gizmos.point(Vec3.atCenterOf(first), CORNER_COLOR, 0.15F)
                .setAlwaysOnTop();

        BlockPos second = getHoveredBlock(client);
        if (second == null) {
            return;
        }

        AABB box = selectionBox(first, second);
        Gizmos.cuboid(box, SELECTION_STYLE, true)
                .setAlwaysOnTop();
    }

    /**
     * Shows the block under the crosshair before AREA corner 1 has been selected.
     */
    private static void renderAreaFirstCornerPreview(Minecraft client) {
        BlockPos hovered = getHoveredBlock(client);
        if (hovered == null) {
            return;
        }

        Gizmos.cuboid(
                selectionBox(hovered, hovered),
                SELECTION_STYLE,
                true
        ).setAlwaysOnTop();
    }

    /**
     * Shows the single block that will become the REDSTONE trigger.
     */
    private static void renderRedstonePreview(Minecraft client) {
        BlockPos hovered = getHoveredBlock(client);
        if (hovered == null) {
            return;
        }

        Gizmos.cuboid(
                selectionBox(hovered, hovered),
                REDSTONE_STYLE,
                true
        ).setAlwaysOnTop();
    }

    /**
     * Finds the marked selector arrow in either hand and confirms it belongs to the current player.
     */
    private static ItemStack findHeldSelector(Minecraft client) {
        ItemStack main = client.player.getMainHandItem();
        if (isSelectorForPlayer(main, client)) {
            return main;
        }

        ItemStack offhand = client.player.getOffhandItem();
        if (isSelectorForPlayer(offhand, client)) {
            return offhand;
        }

        return ItemStack.EMPTY;
    }

    /**
     * Verifies selector metadata on the client copy of the vanilla arrow.
     */
    private static boolean isSelectorForPlayer(ItemStack stack, Minecraft client) {
        return client.player != null && ClockworkBossSelector.isActiveSelector(stack, client.player, false);
    }

    /**
     * Gets the block currently under the crosshair when the client has a block hit.
     */
    private static BlockPos getHoveredBlock(Minecraft client) {
        HitResult hit = client.hitResult;
        if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
            return blockHit.getBlockPos();
        }
        return null;
    }

    /**
     * Builds an inclusive block-selection AABB from two corners.
     */
    private static AABB selectionBox(BlockPos first, BlockPos second) {
        int minX = Math.min(first.getX(), second.getX());
        int minY = Math.min(first.getY(), second.getY());
        int minZ = Math.min(first.getZ(), second.getZ());
        int maxX = Math.max(first.getX(), second.getX()) + 1;
        int maxY = Math.max(first.getY(), second.getY()) + 1;
        int maxZ = Math.max(first.getZ(), second.getZ()) + 1;

        return new AABB(
                minX,
                minY,
                minZ,
                maxX,
                maxY,
                maxZ
        );
    }
}
