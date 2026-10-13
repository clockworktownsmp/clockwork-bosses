package clockwork.bosses;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Internal wire adapter for clients without Clockwork Bosses. The server still
 * owns the full Warden metadata; only the outgoing copy loses our visual fields.
 * One immutable layout is shared by every Warden, with no player/entity cache.
 */
public final class ClockworkBossClientData {
    private final int[] visualFieldIds;

    /** Capture the actual accessor IDs so another mod's earlier fields remain intact. */
    public ClockworkBossClientData(EntityDataAccessor<?>... visualFields) {
        visualFieldIds = Arrays.stream(visualFields).mapToInt(EntityDataAccessor::id).sorted().toArray();
        for (int index = 1; index < visualFieldIds.length; index++) {
            if (visualFieldIds[index] == visualFieldIds[index - 1]) {
                throw new IllegalArgumentException("Repeated Clockwork Boss visual field ID");
            }
        }
    }

    /**
     * Every supported client registers the editor receiver during initialization,
     * even non-operators. Fabric negotiates these play channels before tracking.
     * Reusing that receiver avoids a second handshake, timeout or presence map.
     * This is a capability check, not permission or a check of the jar version:
     * installed Clockwork Bosses clients must still match the server's protocol.
     */
    public static boolean hasClientMod(ServerPlayer player) {
        return ServerPlayNetworking.canSend(player, ClockworkBossEditorPayload.TYPE);
    }

    /**
     * Remove only our fields and close their ID gaps. If another mod registered
     * Warden fields after ours, its client without Clockwork Bosses allocated
     * lower IDs; retain that mod's serializer/value and adjust the ID accordingly.
     * This assumes the other required mods have matching client/server layouts.
     * Return the original list when nothing changes; never mutate native data.
     */
    public List<SynchedEntityData.DataValue<?>> withoutClientFields(
            List<SynchedEntityData.DataValue<?>> values) {
        ArrayList<SynchedEntityData.DataValue<?>> filtered = null;
        for (int index = 0; index < values.size(); index++) {
            var value = values.get(index);
            int search = Arrays.binarySearch(visualFieldIds, value.id());
            // Found IDs belong to us. A missing ID's insertion point counts
            // exactly how many of our allocated slots precede the retained field.
            int clientId = search >= 0 ? -1 : value.id() - (-search - 1);
            if (filtered == null && clientId != value.id()) {
                filtered = new ArrayList<>(values.size());
                filtered.addAll(values.subList(0, index));
            }
            if (filtered != null && clientId >= 0) {
                filtered.add(clientId == value.id() ? value : withId(value, clientId));
            }
        }
        return filtered == null ? values : List.copyOf(filtered);
    }

    /** Preserve the serializer's generic type while creating an independent wire record. */
    private static <T> SynchedEntityData.DataValue<T> withId(
            SynchedEntityData.DataValue<T> value, int clientId) {
        return new SynchedEntityData.DataValue<>(clientId, value.serializer(), value.value());
    }
}
