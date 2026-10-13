package clockwork.bosses.mixin;

import clockwork.bosses.ClockworkBossClientData;
import clockwork.bosses.ClockworkBossMarker;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.warden.Warden;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import java.util.function.Consumer;

/**
 * Adapts Warden metadata at Minecraft 26.3's two native tracking boundaries:
 * the first pairing snapshot and later dirty updates. Other entity types and
 * attribute/movement packets use their original calls. Nothing is scheduled.
 */
@Mixin(ServerEntity.class)
public abstract class ServerEntityMixin {
    @Shadow @Final private Entity entity;

    /**
     * Filter the second Consumer.accept call, which adds metadata to the spawn
     * bundle. The native method already supplies the recipient and entity, so
     * reconnects, dimension changes and late tracking need no lookup or cache.
     */
    @WrapOperation(method = "sendPairingData", at = @At(value = "INVOKE",
            target = "Ljava/util/function/Consumer;accept(Ljava/lang/Object;)V", ordinal = 1))
    private void clockworkBosses$pairingData(Consumer<?> sender, Object packet, Operation<Void> original,
                                           @Local(argsOnly = true) ServerPlayer player) {
        if (!(entity instanceof Warden) || !(packet instanceof ClientboundSetEntityDataPacket data)
                || ClockworkBossClientData.hasClientMod(player)) {
            original.call(sender, packet);
            return;
        }
        var values = ((ClockworkBossMarker) entity).clockworkBosses$getClientDataLayout()
                .withoutClientFields(data.packedItems());
        // An empty metadata snapshot is unnecessary; the native spawn packet
        // already constructed the vanilla Warden with its default values.
        if (!values.isEmpty()) {
            original.call(sender, values == data.packedItems()
                    ? data : new ClientboundSetEntityDataPacket(data.id(), values));
        }
    }

    /**
     * Build at most one fallback packet per dirty update, shared by all players
     * without the mod. Native filtered broadcasts use the existing tracking
     * set; full metadata reaches modded recipients unchanged. Warden entities
     * are never players, so the native "and self" branch adds no recipient here.
     * Apply this to ordinary Wardens too: later fields from other mods must lose
     * our ID gaps even when none of our default visual fields were sent.
     */
    @WrapOperation(method = "sendDirtyEntityData", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/server/level/ServerEntity$Synchronizer;sendToTrackingPlayersAndSelf(Lnet/minecraft/network/protocol/Packet;)V"))
    private void clockworkBosses$dirtyData(ServerEntity.Synchronizer synchronizer,
            Packet<? super ClientGamePacketListener> packet, Operation<Void> original) {
        if (!(entity instanceof Warden) || !(packet instanceof ClientboundSetEntityDataPacket data)) {
            original.call(synchronizer, packet);
            return;
        }
        var values = ((ClockworkBossMarker) entity).clockworkBosses$getClientDataLayout()
                .withoutClientFields(data.packedItems());
        if (values == data.packedItems()) {
            original.call(synchronizer, packet);
            return;
        }
        synchronizer.sendToTrackingPlayersFiltered(packet, ClockworkBossClientData::hasClientMod);
        if (!values.isEmpty()) {
            synchronizer.sendToTrackingPlayersFiltered(new ClientboundSetEntityDataPacket(data.id(), values),
                    player -> !ClockworkBossClientData.hasClientMod(player));
        }
    }
}
