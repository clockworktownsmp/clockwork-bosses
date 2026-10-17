package clockwork.bosses.client;

import clockwork.bosses.ClockworkBosses;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.resource.v1.ResourceLoader;
import net.fabricmc.fabric.api.resource.v1.reloader.ResourceReloaderKeys;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.resources.Identifier;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import clockwork.bosses.ClockworkBossSoundPayload;
import clockwork.bosses.ClockworkBossMusicControl;
import clockwork.bosses.ClockworkBossMusicData;
import clockwork.bosses.CustomWardenSkinPayload;
import clockwork.bosses.ClockworkBossMediaChunk;

/**
 * Client entry point for rendering and other client-only Clockwork Boss features.
 */
public class ClockworkBossesClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // Register the client-side activation-area selection preview.
        ClockworkBossSelectorRenderer.register();
        ClockworkBossSelectorClient.register();
        ClockworkBossEditorClient.register();
        ClientPlayNetworking.registerGlobalReceiver(ClockworkBossMusicControl.TYPE,
                (payload, context) -> ClockworkBossMusicClient.control(payload));
        ClientPlayNetworking.registerGlobalReceiver(ClockworkBossMusicData.TYPE,
                (payload, context) -> ClockworkBossMusicClient.accept(payload));
        ClientPlayNetworking.registerGlobalReceiver(clockwork.bosses.ClockworkBossLibraryMusicData.TYPE,
                (payload, context) -> ClockworkBossMusicClient.accept(payload));
        ClientPlayNetworking.registerGlobalReceiver(ClockworkBossMediaChunk.TYPE,
                (payload, context) -> ClockworkBossMediaClient.accept(payload, context.client()));

        // Receive server-owned custom Warden atlas bytes; clients never select a local path.
        ClientPlayNetworking.registerGlobalReceiver(
                CustomWardenSkinPayload.TYPE,
                (payload, context) -> WardenSkinTextureCache.acceptCustomSkinPayload(
                        payload.templateName(), payload.fileName(), payload.pngBytes()));
        ClientPlayNetworking.registerGlobalReceiver(
                ClockworkBossSoundPayload.TYPE,
                (payload, context) -> ClockworkBossSoundClient.accept(payload));
        ClientPlayConnectionEvents.DISCONNECT.register(
                (handler, client) -> client.execute(() -> {
                    // Disconnect can arrive on Netty's thread. Texture/audio
                    // ownership changes must run on Minecraft's client thread.
                    // A delayed old disconnect must not clear a new connection.
                    if (client.getConnection() == null || client.getConnection() == handler) clearSession();
                }));
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            // Clear the previous server before receiving this server's assets,
            // including rapid reconnects whose old disconnect callback was skipped.
            clearSession();
        });
        // Native 26.3 resource-loader-v1 hook: drop generated assets before vanilla
        // textures/audio reload, so new pack pixels are used on the next boss render.
        Identifier reloadId = Identifier.fromNamespaceAndPath(ClockworkBosses.MOD_ID, "generated_assets");
        ResourceLoader resources = ResourceLoader.get(PackType.CLIENT_RESOURCES);
        resources.registerReloadListener(reloadId, (ResourceManagerReloadListener) manager -> {
            WardenSkinTextureCache.clearGeneratedTextures();
            WardenEmissiveMaskTexture.clear();
            ClockworkBossSoundClient.stopPlayback();
            ClockworkBossMusicClient.stopPlayback();
        });
        resources.addListenerOrdering(reloadId, ResourceReloaderKeys.Client.TEXTURES);
        resources.addListenerOrdering(reloadId, ResourceReloaderKeys.Client.SOUNDS);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> clearSession());
    }

    /** Client-thread teardown releases owned assets and invalidates outstanding asynchronous loads. */
    private static void clearSession() {
        ClockworkBossSelectorClient.clear();
        ClockworkBossEditorClient.clear();
        ClockworkBossMediaClient.clear();
        WardenSkinTextureCache.clear();
        WardenEmissiveMaskTexture.clear();
        ClockworkBossSoundClient.clear();
        ClockworkBossMusicClient.clear();
    }
}
