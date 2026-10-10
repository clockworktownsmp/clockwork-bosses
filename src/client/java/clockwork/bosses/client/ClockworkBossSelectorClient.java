package clockwork.bosses.client;

import clockwork.bosses.ClockworkBossSelector;
import net.fabricmc.fabric.api.event.client.player.ClientPreAttackCallback;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Treats a selector block click as selection rather than a mining gesture.
 * Minecraft 26.3 sends its punch packet outside startDestroyBlock, so cancelling
 * AttackBlockCallback alone still permits a swing, block dust and hit sounds.
 * Fabric's native pre-attack event cancels the whole gesture without a new mixin,
 * custom packet, timer or tick listener.
 */
public final class ClockworkBossSelectorClient {
    // A completing selection removes the arrow asynchronously. Keep suppressing
    // that held click even after the hand becomes empty, until a fresh click arrives.
    private static boolean suppressHeldAttack;

    private ClockworkBossSelectorClient() {}

    /** Registers once; only selector block clicks intercept normal attack handling. */
    public static void register() {
        ClientPreAttackCallback.EVENT.register((client, player, clickCount) -> {
            // Fabric supplies actual queued presses separately from a held button.
            // The next fresh press restores ordinary input after tool removal.
            if (clickCount > 0) suppressHeldAttack = false;
            if (suppressHeldAttack) return true;
            if (client.gameMode == null || player.isSpectator()
                    || !(client.hitResult instanceof BlockHitResult hit)
                    || hit.getType() != HitResult.Type.BLOCK
                    || !ClockworkBossSelector.isActiveSelector(player.getMainHandItem(), player, false)) {
                return false;
            }

            if (clickCount > 0) {
                // Stop any earlier mining gesture, then use the native predicted
                // action path. Our common AttackBlockCallback returns CONSUME:
                // Fabric sends START_DESTROY_BLOCK once, and the server validates
                // the session/permissions and saves the selection as before.
                client.gameMode.stopDestroyBlock();
                client.gameMode.startDestroyBlock(hit.getBlockPos(), hit.getDirection());
                suppressHeldAttack = true;
            }
            // Cancel both vanilla startAttack and continueAttack, including the
            // separate punch packet. Holding the button never repeats a selection.
            return true;
        });
    }

    /** Forget input state across world/server connections; no player or world reference is retained. */
    public static void clear() {
        suppressHeldAttack = false;
    }
}
