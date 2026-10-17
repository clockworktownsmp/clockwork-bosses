package clockwork.bosses.mixin;

import clockwork.bosses.client.ClockworkBossMediaClient;
import clockwork.bosses.client.ClockworkBossMusicReceiptPause;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Native acceptance only: hold cumulative ACKs briefly to prove that a stop
 * before the final bytes prevents late playback and leaves the window bounded. */
@Mixin(value = ClockworkBossMediaClient.class, remap = false)
public abstract class LibraryMusicReceiptProbe {
    @Redirect(method = "accept", at = @At(value = "INVOKE", target = "Lnet/fabricmc/fabric/api/client/networking/v1/ClientPlayNetworking;send(Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;)V", remap = false), remap = false)
    private static void clockworkBosses$holdReceipt(CustomPacketPayload payload) {
        if (!ClockworkBossMusicReceiptPause.capture(payload)) ClientPlayNetworking.send(payload);
    }
}
