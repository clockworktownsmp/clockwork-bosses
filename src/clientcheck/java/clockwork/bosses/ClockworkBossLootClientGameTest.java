package clockwork.bosses;

import clockwork.bosses.client.ClockworkBossLootGuiChecks;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/** Fabric creates a temporary native world so 26.3's item components and creative tabs are bound.
 * Test worlds/screenshots live only in the development run directory; no production editor hooks. */
public final class ClockworkBossLootClientGameTest implements FabricClientGameTest {
    @Override public void runTest(ClientGameTestContext context) {
        try (var world = context.worldBuilder().create()) {
            context.waitFor(client -> client.level != null && client.player != null);
            context.runOnClient(ClockworkBossClientChecks::check);
            int checks = context.computeOnClient(ClockworkBossLootGuiChecks::run);
            int faceChecks = context.computeOnClient(clockwork.bosses.client.ClockworkBossFaceGuiChecks::run);
            context.runOnClient(clockwork.bosses.client.ClockworkBossFaceGuiChecks::preview);
            context.waitTicks(3);
            context.runOnClient(clockwork.bosses.client.ClockworkBossFaceGuiChecks::assertRendered);
            context.takeScreenshot("face-controls-hurt-sad");
            context.runOnClient(client -> ClockworkBossLootGuiChecks.preview(client, ""));
            context.waitTicks(2);
            context.takeScreenshot("loot-picker-all");
            context.runOnClient(client -> ClockworkBossLootGuiChecks.searchPreview(client, "diamond"));
            context.waitTicks(2);
            context.takeScreenshot("loot-picker-diamond");
            context.setScreen(() -> null);
            context.runOnClient(client -> java.nio.file.Files.writeString(java.nio.file.Path.of("loot-gui-check-result.txt"), "PASS:" + checks + ":faces=" + faceChecks));
            System.out.println("CLOCKWORK_LOOT_GUI_CHECK_PASS: " + checks + " loot GUI assertions, " + faceChecks + " face GUI assertions and rendered screenshots");
        } catch (Exception failure) {
            throw new AssertionError("Loot GUI check failed", failure);
        }
    }
}
