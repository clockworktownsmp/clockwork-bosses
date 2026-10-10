package clockwork.bosses;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import java.util.UUID;

/** Operator-only editor messages. Saves carry a small scalar patch, never media or filesystem paths. */
public record ClockworkBossEditorPayload(UUID token, String action, boolean preset, String name,
                                        String revision, String json) implements CustomPacketPayload {
    public static final int MAX_JSON_CHARS = 65536;
    // Leave room for the token/action/field metadata beneath vanilla's serverbound packet limit.
    public static final int MAX_PATCH_BYTES = 24000;
    public static final Type<ClockworkBossEditorPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(ClockworkBosses.MOD_ID, "template_editor_v1"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ClockworkBossEditorPayload> CODEC = StreamCodec.of(
            (buffer, value) -> { buffer.writeUUID(value.token); buffer.writeUtf(value.action, 16);
                buffer.writeBoolean(value.preset); buffer.writeUtf(value.name, 64);
                buffer.writeUtf(value.revision, 64); buffer.writeUtf(value.json, MAX_JSON_CHARS); },
            buffer -> new ClockworkBossEditorPayload(buffer.readUUID(), buffer.readUtf(16), buffer.readBoolean(),
                    buffer.readUtf(64), buffer.readUtf(64), buffer.readUtf(MAX_JSON_CHARS)));
    public ClockworkBossEditorPayload {
        if (token == null || action == null || name == null || revision == null || json == null
                || action.length() > 16 || name.length() > 64 || revision.length() > 64 || json.length() > MAX_JSON_CHARS)
            throw new IllegalArgumentException("Oversized editor message");
    }
    @Override public Type<ClockworkBossEditorPayload> type() { return TYPE; }
}
