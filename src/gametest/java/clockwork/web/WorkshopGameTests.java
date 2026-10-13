package clockwork.web;

import clockwork.bosses.ClockworkBossClientData;
import clockwork.bosses.ClockworkBossManager;
import clockwork.bosses.ClockworkBossMarker;
import clockwork.bosses.ClockworkBossTemplateDocument;
import clockwork.bosses.ClockworkBossTemplateStore;
import com.mojang.brigadier.CommandDispatcher;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.phys.Vec3;

/** Native merge checks run in a disposable world, with no real server identity,
 * remote relay, Minecraft account, workshop credential or active templates. */
public final class WorkshopGameTests {
    private static CommandSourceStack source(GameTestHelper helper) {
        return helper.getLevel().getServer().createCommandSourceStack().withLevel(helper.getLevel())
                .withPosition(helper.absoluteVec(new Vec3(2, 2, 2)));
    }

    @GameTest public void command_aliases_and_owner_boundary(GameTestHelper helper) {
        var dispatcher = new CommandDispatcher<CommandSourceStack>(); new ClockworkBossWorkshop().registerCommands(dispatcher);
        var primary = dispatcher.getRoot().getChild("boss").getChild("workshop");
        var alias = dispatcher.getRoot().getChild("bossworkshop");
        helper.assertTrue(primary.getChildren().stream().map(node -> node.getName()).sorted().toList()
                .equals(alias.getChildren().stream().map(node -> node.getName()).sorted().toList()), "Both commands expose identical operations");
        var owner = source(helper).withPermission(LevelBasedPermissionSet.OWNER);
        helper.assertTrue(primary.canUse(owner) && alias.canUse(owner), "Owner reaches main and temporary alias");
        for (var permission : List.of(LevelBasedPermissionSet.GAMEMASTER, PermissionSet.NO_PERMISSIONS)) {
            var limited = source(helper).withPermission(permission);
            helper.assertTrue(!primary.canUse(limited) && !alias.canUse(limited), "Boss operator grants do not grant workshop owner access");
        }
        helper.assertTrue(dispatcher.parse("boss workshop reload", owner).getReader().getRemaining().isEmpty()
                && dispatcher.parse("bossworkshop reload", owner).getReader().getRemaining().isEmpty(), "Both native command paths parse");
        helper.succeed();
    }

    @GameTest public void client_optional_metadata_native_codec(GameTestHelper helper) {
        var adapter = new ClockworkBossClientData(new EntityDataAccessor<>(20, EntityDataSerializers.STRING),
                new EntityDataAccessor<>(22, EntityDataSerializers.INT));
        List<SynchedEntityData.DataValue<?>> nativeValues = List.of(new SynchedEntityData.DataValue<>(19, EntityDataSerializers.INT, 42),
                new SynchedEntityData.DataValue<>(20, EntityDataSerializers.STRING, "clockwork-only"),
                new SynchedEntityData.DataValue<>(21, EntityDataSerializers.INT, 8),
                new SynchedEntityData.DataValue<>(22, EntityDataSerializers.INT, 5),
                new SynchedEntityData.DataValue<>(23, EntityDataSerializers.STRING, "other-mod"));
        var filtered = adapter.withoutClientFields(nativeValues);
        helper.assertTrue(filtered.stream().map(SynchedEntityData.DataValue::id).toList().equals(List.of(19, 20, 21)), "Only mod slots removed; later third-party IDs close gaps");
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            ClientboundSetEntityDataPacket.STREAM_CODEC.encode(buffer, new ClientboundSetEntityDataPacket(123, filtered));
            var decoded = ClientboundSetEntityDataPacket.STREAM_CODEC.decode(buffer);
            helper.assertTrue(decoded.id() == 123 && decoded.packedItems().equals(filtered) && buffer.readableBytes() == 0,
                    "Fallback packet round-trips with native 26.3 serializers");
            helper.assertTrue(nativeValues.size() == 5 && nativeValues.get(1).value().equals("clockwork-only"), "Modded native list remains intact");
        } finally { buffer.release(); }
        helper.succeed();
    }

    @GameTest(maxTicks = 300) public void item_snapshot_uses_native_batches(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        try { WorkshopItemSnapshot.capture(server); throw new AssertionError("Capture blocked the native thread"); }
        catch (java.io.IOException expected) { }
        var pending = CompletableFuture.supplyAsync(() -> {
            try { return WorkshopItemSnapshot.capture(server); }
            catch (java.io.IOException failure) { throw new java.util.concurrent.CompletionException(failure); }
        }, net.minecraft.util.Util.ioPool());
        helper.succeedWhen(() -> {
            helper.assertTrue(pending.isDone(), "Native item batches still completing");
            com.google.gson.JsonObject result;
            try { result = pending.join().page("/api/items/0/_/_"); }
            catch (java.io.IOException failure) { throw new java.util.concurrent.CompletionException(failure); }
            helper.assertTrue(result.get("registry_total").getAsInt() == BuiltInRegistries.ITEM.size() - 1,
                    "Every registered item except AIR captured without request-time native reads");
        });
    }

    @GameTest(maxTicks = 100) public void saved_boss_restores_without_native_disk_read(GameTestHelper helper) throws Exception {
        String name = "merge_restore_" + UUID.randomUUID().toString().substring(0, 8);
        var config = ClockworkBossTemplateDocument.defaults(); config.getAsJsonObject("spawn").addProperty("set", false);
        // Fixture setup deliberately writes directly. Production registration is
        // the asynchronous path under test; no real user config is touched.
        ClockworkBossTemplateStore.save(name, config);
        var gate = new java.util.concurrent.CountDownLatch(1);
        helper.assertTrue(clockwork.bosses.ClockworkBossTemplateTasks.command(source(helper),
                clockwork.bosses.ClockworkBossPermissions.Action.DEBUG, "Fixture gate failed: ", () -> {
                    gate.await(3, java.util.concurrent.TimeUnit.SECONDS); return true;
                }, ignored -> {}) == 1, "Disposable worker gate admitted");
        Warden boss = EntityTypes.WARDEN.create(helper.getLevel(), EntitySpawnReason.COMMAND);
        var marker = (ClockworkBossMarker) boss; marker.clockworkBosses$setClockworkBoss(true);
        marker.clockworkBosses$setTemplate(name); marker.clockworkBosses$setName(name);
        marker.clockworkBosses$setDormant(true); marker.clockworkBosses$setEmergencePos(helper.absolutePos(new net.minecraft.core.BlockPos(2, 2, 2)));
        try {
            boss.snapTo(helper.absoluteVec(new Vec3(2, 2, 2))); helper.getLevel().addFreshEntity(boss);
            helper.assertTrue(ClockworkBossManager.stateFor(boss) == null && boss.isNoAi(), "Entity-load queues file read and quiets the saved boss immediately");
        } finally { gate.countDown(); }
        helper.succeedWhen(() -> {
            helper.assertTrue(ClockworkBossManager.stateFor(boss) != null, "Restoration worker still completing");
            helper.assertTrue(ClockworkBossManager.stateFor(boss).dormant() && boss.isNoAi(), "Native completion preserves dormant lifecycle");
            boss.discard();
        });
    }
}
