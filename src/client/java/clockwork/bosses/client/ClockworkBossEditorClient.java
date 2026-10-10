package clockwork.bosses.client;

import clockwork.bosses.ClockworkBossEditorPayload;
import clockwork.bosses.ClockworkBossTemplateDocument;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import java.io.StringReader;
import java.util.UUID;

/** Client transport only: the server owns paths, permissions, file revisions and durable writes. */
public final class ClockworkBossEditorClient {
    private static UUID activeToken;
    // Picker/document replacement can reuse a server session. Native screen
    // removal must close external exits, but not our own same-session navigation.
    private static boolean replacingScreen;
    private ClockworkBossEditorClient() {}

    public static void register() {
        ClientPlayNetworking.registerGlobalReceiver(ClockworkBossEditorPayload.TYPE, (payload, context) -> accept(payload));
    }

    private static void accept(ClockworkBossEditorPayload payload) {
        Minecraft client = Minecraft.getInstance();
        if (client.getConnection() == null) return;
        try {
            switch (payload.action()) {
                case "catalog", "document" -> {
                    JsonObject document = ClockworkBossTemplateDocument.read(new StringReader(payload.json()));
                    ClockworkBossTemplateScreen next = new ClockworkBossTemplateScreen(payload, document);
                    activeToken = payload.token();
                    replacingScreen = true;
                    try { client.gui.setScreen(next); }
                    finally { replacingScreen = false; }
                }
                case "saved", "error", "preview_ready", "reloaded" -> {
                    if (payload.token().equals(activeToken) && client.gui.screen() instanceof ClockworkBossTemplateScreen screen) screen.reply(payload);
                }
                default -> { }
            }
        } catch (Exception malformed) {
            if (client.gui.screen() instanceof ClockworkBossTemplateScreen screen) screen.message("Could not read the editor response. Reopen /boss template edit.");
        }
    }

    /** Never send a full document in a save: UTF-8 patches stay below vanilla's serverbound payload limit. */
    public static void send(ClockworkBossEditorPayload payload) {
        if (Minecraft.getInstance().getConnection() != null && ClientPlayNetworking.canSend(ClockworkBossEditorPayload.TYPE)) ClientPlayNetworking.send(payload);
    }
    public static void close(UUID token) {
        if (token.equals(activeToken)) {
            send(new ClockworkBossEditorPayload(token, "close", false, "", "", ""));
            activeToken = null;
        }
    }
    /** Screen removal also covers menus/other mods replacing the editor, which
     * bypass onClose. Invalidate the token before any late preview packets arrive. */
    public static void screenRemoved(UUID token) { if (!replacingScreen) close(token); }
    public static void clear() { activeToken = null; }
    public static boolean acceptsPreview(String identity) { return activeToken != null && identity.equals("__gui_" + activeToken); }
}
