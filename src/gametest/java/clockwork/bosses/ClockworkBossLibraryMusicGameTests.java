package clockwork.bosses;

import com.google.gson.JsonObject;
import java.util.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

/** Real native player/listener negotiation guards. Embedded peers cover the
 * absent capability only; actual decode/playback is the separate joined-client
 * test, never promoted from this server fixture. */
public final class ClockworkBossLibraryMusicGameTests {
    @GameTest public void unsupported_library_peer_receives_no_control_or_transfer(GameTestHelper helper) throws Exception {
        JsonObject config = ClockworkBossCombatGameTests.config(1000); JsonObject music = new JsonObject();
        music.addProperty("enabled", true); music.addProperty("source", "library"); music.addProperty("file", "boss1.ogg"); config.add("music", music);
        var boss = ClockworkBossCombatGameTests.boss(helper, config, "library_capability");
        var active = ClockworkBossManager.class.getDeclaredField("ACTIVE"); active.setAccessible(true);
        var state = (ClockworkBossState) ((Map<?, ?>) active.get(null)).get(boss.getUUID());
        try {
            for (boolean legacy : new boolean[]{false, true}) try (var peer = new Peer(helper, legacy)) {
                helper.assertTrue(ServerPlayNetworking.canSend(peer.player, ClockworkBossMusicData.TYPE) == legacy, "native legacy channel state is explicit");
                helper.assertTrue(!ServerPlayNetworking.canSend(peer.player, ClockworkBossLibraryMusicData.TYPE), "peer has no library capability");
                peer.messages.clear(); ClockworkBossMusic.sync(state, helper.getLevel(), Set.of(peer.player.getUUID()));
                helper.assertTrue(peer.messages.isEmpty(), "no library start/data/chunk is sent before matching negotiation");
                var encounters = ClockworkBossMusic.class.getDeclaredField("ENCOUNTERS"); encounters.setAccessible(true);
                helper.assertTrue(!((Map<?, ?>) encounters.get(null)).containsKey(boss.getUUID()), "unsupported peer leaves no pending library encounter");
                helper.assertTrue(boss.isAlive() && !boss.isRemoved(), "optional-client gameplay remains intact");
            }
            helper.succeed();
        } finally { ClockworkBossMusic.end(boss.getUUID(), helper.getLevel().getServer()); boss.discard(); }
    }

    @GameTest public void late_library_delivery_rechecks_capability_before_control(GameTestHelper helper) throws Exception {
        // An async file completion can outlive a channel advertisement. Test the
        // real delivery boundary independently of admission and file decoding.
        try (var peer = new Peer(helper, true)) {
            var type = ClockworkBossMusic.class;
            var encounterType = Class.forName("clockwork.bosses.ClockworkBossMusic$Encounter");
            var listenerType = Class.forName("clockwork.bosses.ClockworkBossMusic$Listener");
            var assetType = Class.forName("clockwork.bosses.ClockworkBossMusic$Asset");
            var constructor = encounterType.getDeclaredConstructor(UUID.class, String.class, ClockworkBossMusic.Settings.class, net.minecraft.server.MinecraftServer.class); constructor.setAccessible(true);
            var settings = new ClockworkBossMusic.Settings(true, "library", "boss1.ogg", 1, false, 0, net.minecraft.sounds.SoundSource.MUSIC);
            UUID boss = UUID.randomUUID(); Object encounter = constructor.newInstance(boss, "library", settings, helper.getLevel().getServer());
            var listenerConstructor = listenerType.getDeclaredConstructor(); listenerConstructor.setAccessible(true);
            var listenersField = encounterType.getDeclaredField("listeners"); listenersField.setAccessible(true);
            @SuppressWarnings("unchecked") var listeners = (Map<UUID, Object>) listenersField.get(encounter); listeners.put(peer.player.getUUID(), listenerConstructor.newInstance());
            var encountersField = type.getDeclaredField("ENCOUNTERS"); encountersField.setAccessible(true);
            @SuppressWarnings("unchecked") var encounters = (Map<UUID, Object>) encountersField.get(null); encounters.put(boss, encounter);
            try {
                var assetConstructor = assetType.getDeclaredConstructor(ClockworkBossLibraryMusicData.class); assetConstructor.setAccessible(true);
                // Payload framing only: native audio acceptance uses supplied
                // tracks through the real resolver in the joined-client test.
                Object asset = assetConstructor.newInstance(new ClockworkBossLibraryMusicData("boss1.ogg", "a".repeat(64), new byte[]{1}));
                var send = type.getDeclaredMethod("send", encounterType, ServerPlayer.class, assetType); send.setAccessible(true);
                peer.messages.clear(); send.invoke(null, encounter, peer.player, asset);
                helper.assertTrue(peer.messages.stream().noneMatch(p -> p instanceof ClockworkBossMusicControl control && control.start()),
                        "late completion cannot send a library start to a peer lacking the independently negotiated channel");
                helper.succeed();
            } finally { ClockworkBossMusic.end(boss, helper.getLevel().getServer()); }
        }
    }

    private static final class Peer implements AutoCloseable {
        final ServerPlayer player;
        final io.netty.channel.embedded.EmbeddedChannel channel;
        final List<CustomPacketPayload> messages = new ArrayList<>();
        Peer(GameTestHelper helper, boolean legacy) {
            var server = helper.getLevel().getServer(); var profile = new com.mojang.authlib.GameProfile(UUID.randomUUID(), "music_" + UUID.randomUUID().toString().substring(0, 6));
            player = new ServerPlayer(server, helper.getLevel(), profile, net.minecraft.server.level.ClientInformation.createDefault());
            var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
            channel = new io.netty.channel.embedded.EmbeddedChannel(connection); channels(connection, legacy);
            server.getPlayerList().placeNewPlayer(connection, player, net.minecraft.server.network.CommonListenerCookie.createInitial(profile, false));
            channels(connection, legacy);
            player.connection = new net.minecraft.server.network.ServerGamePacketListenerImpl(server, connection, player,
                    net.minecraft.server.network.CommonListenerCookie.createInitial(profile, false)) {
                @Override public void send(net.minecraft.network.protocol.Packet<?> packet) {
                    if (packet instanceof net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket custom) messages.add(custom.payload());
                }
            };
            player.snapTo(helper.absoluteVec(new net.minecraft.world.phys.Vec3(1, 2, 1)));
        }
        private static void channels(net.minecraft.network.Connection connection, boolean legacy) {
            if (!legacy) return;
            var pending = ((net.fabricmc.fabric.impl.networking.ChannelInfoHolder) connection)
                    .fabric_getPendingChannelsNames(net.minecraft.network.ConnectionProtocol.PLAY);
            pending.add(ClockworkBossMusicControl.TYPE.id()); pending.add(ClockworkBossMusicData.TYPE.id()); pending.add(ClockworkBossMediaChunk.TYPE.id());
        }
        @Override public void close() { player.level().getServer().getPlayerList().remove(player); channel.finishAndReleaseAll(); }
    }
}
