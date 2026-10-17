package clockwork.bosses.client;

import clockwork.bosses.ClockworkBossMediaReceipt;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Test-only one-slot receipt hold creates a real partial transfer. No server
 * file request or fake audio is introduced; normal receipt handling is unchanged
 * whenever this driver is inactive. Never included in the universal jar. */
public final class ClockworkBossMusicReceiptPause {
    private static boolean holding;
    private static ClockworkBossMediaReceipt pending;
    private ClockworkBossMusicReceiptPause() {}
    public static void hold() { holding = true; pending = null; }
    public static boolean capture(CustomPacketPayload payload) {
        if (!holding || !(payload instanceof ClockworkBossMediaReceipt receipt)) return false;
        pending = receipt; return true;
    }
    public static void release() {
        holding = false; var receipt = pending; pending = null;
        if (receipt != null && net.minecraft.client.Minecraft.getInstance().getConnection() != null
                && ClientPlayNetworking.canSend(ClockworkBossMediaReceipt.TYPE)) ClientPlayNetworking.send(receipt);
    }
}
