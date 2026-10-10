package clockwork.bosses;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.monster.warden.Warden;

import java.util.HashSet;

/** Native title packets for actual target assignments; presentation never changes attack timing. */
public final class ClockworkBossAnnouncements {
    private static final ClockworkBossTitleThrottle THROTTLE = new ClockworkBossTitleThrottle();
    private ClockworkBossAnnouncements() {}

    /** Called only after setAttackTarget changed the actual target, including the first native acquisition. */
    public static void changed(Warden boss, ClockworkBossState state, ServerPlayer target) {
        if (state.dormant() || state.emerging() || boss.isRemoved() || !boss.isAlive() || state.currentPhase() == null) return;
        var setting = state.combatSettings().targeting().announcement();
        if (!setting.enabled()) return;
        var recipients = new HashSet<>(state.bossBar().getPlayers());
        recipients.add(target); // A valid attacked player is warned even if the bar has no configured region.
        var area = ClockworkBossManager.visibilityQuery(state, boss);
        var text = Component.literal(setting.text().replace("<player>", target.getGameProfile().name()))
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
        int now = target.level().getServer().getTickCount();
        int cooldown = Math.max(setting.cooldownTicks(), setting.displayTicks() + 6);
        for (ServerPlayer player : recipients) {
            if (player.hasDisconnected() || !player.isAlive() || player.isSpectator() || player.level() != boss.level()) continue;
            // Membership can lag by half a second; recheck current position before sending a shared title.
            if (player != target && (area == null || !area.intersects(player.getBoundingBox())
                    || !ClockworkBossManager.insideVisibilityRadius(state, boss, player))) continue;
            boolean permitted = player == target ? THROTTLE.acquireTarget(player.getUUID(), now, cooldown)
                    : THROTTLE.acquire(player.getUUID(), now, cooldown);
            if (!permitted) continue;
            player.connection.send(new ClientboundSetTitlesAnimationPacket(2, setting.displayTicks(), 4));
            player.connection.send(new ClientboundSetSubtitleTextPacket(Component.empty()));
            player.connection.send(new ClientboundSetTitleTextPacket(text));
        }
    }
    public static void leave(ServerPlayer player) { THROTTLE.leave(player.getUUID()); }
    public static void clear() { THROTTLE.clear(); }
}
