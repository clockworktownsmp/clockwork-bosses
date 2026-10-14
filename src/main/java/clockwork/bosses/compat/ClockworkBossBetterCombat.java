package clockwork.bosses.compat;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.entity.player.Player;
import org.slf4j.LoggerFactory;

/** The only production Better Combat query: choose a usable potion slot.
 * No combat callbacks, damage replay, animation changes or listeners. All typed
 * third-party references live in the nested class, loaded only when installed. */
public final class ClockworkBossBetterCombat {
    private static final boolean INSTALLED = FabricLoader.getInstance().isModLoaded("bettercombat");
    private static boolean warned;
    private ClockworkBossBetterCombat() {}

    /** Read at grant/refresh boundaries, never each tick. If a later BC version
     * changes this lookup, prefer inventory and warn once rather than hiding or
     * overwriting equipment or interrupting the encounter. */
    public static boolean offhandUsable(Player player) {
        if (!INSTALLED) return true;
        try { return Installed.offhandUsable(player); }
        catch (LinkageError failure) {
            if (!warned) {
                warned = true;
                LoggerFactory.getLogger("clockwork_bosses").warn(
                        "Better Combat hand lookup is incompatible; fight potions will use free inventory slots.", failure);
            }
            return false;
        }
    }

    private static final class Installed {
        /** Same public registry lookup BC 3.2.2 uses for its hand mask. Inventory
         * reads are backing slots, not LivingEntity's masked equipment view.
         * An occupied offhand is rejected separately by the caller. */
        static boolean offhandUsable(Player player) {
            var attributes = net.bettercombat.logic.WeaponRegistry.getAttributes(player.getInventory().getSelectedItem());
            return attributes == null || !attributes.isTwoHanded();
        }
    }
}
