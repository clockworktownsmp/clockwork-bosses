package clockwork.bosses;

import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Raises max health only to a floor, without changing native base/armor values.
 * Arena membership events own grants; native MAX_HEALTH updates handle equipment
 * changes. All mutable state is on the server thread and released on exit. */
public final class ClockworkBossPlayerHealth {
    private static final Identifier GRANT = Identifier.fromNamespaceAndPath(ClockworkBosses.MOD_ID, "arena_health");
    private static final Map<UUID, Member> PLAYERS = new HashMap<>();
    private ClockworkBossPlayerHealth() {}
    private static final class Member {
        final ServerPlayer player;
        final Map<UUID, ClockworkBossPlayerSupport.Health> bosses = new HashMap<>();
        double lastMax;
        boolean applying;
        long revision;
        Member(ServerPlayer player) { this.player = player; lastMax = player.getMaxHealth(); }
    }

    /** JOIN also removes the namespaced saved modifier after a crashed session.
     * It is persistent so native loading can retain increased saved health, then
     * removed proportionally before new encounter membership is established. */
    public static void registerLifecycle() {
        ServerPlayerEvents.JOIN.register(ClockworkBossPlayerHealth::clearSavedGrant);
        ServerPlayerEvents.LEAVE.register(player -> releasePlayer(player.getUUID()));
        ServerPlayerEvents.COPY_FROM.register((oldPlayer, newPlayer, alive) -> {
            releasePlayer(oldPlayer.getUUID()); clearSavedGrant(newPlayer);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            for (UUID player : List.copyOf(PLAYERS.keySet())) releasePlayer(player);
            PLAYERS.clear();
        });
    }

    /** Strongest floor wins across overlapping fights. The boss's saved UUID
     * ledger decides whether this is the player's one initial full heal. */
    public static void enter(UUID boss, ServerPlayer player, ClockworkBossPlayerSupport.Health settings, boolean firstHeal) {
        if (!settings.enabled()) { leave(boss, player.getUUID()); return; }
        Member member = PLAYERS.get(player.getUUID());
        if (member != null && member.player != player) { releasePlayer(player.getUUID()); member = null; }
        if (member == null) { member = new Member(player); PLAYERS.put(player.getUUID(), member); }
        member.bosses.put(boss, settings);
        if (ClockworkBossDiagnostics.enabled()) ClockworkBossDiagnostics.entity(player, "health_request_added", "boss", ClockworkBossDiagnostics.alias(boss), "minimum_max_health", settings.minimumMaxHealth());
        member.revision++;
        reconcile(member, firstHeal);
    }

    /** Called before native MAX_HEALTH clamping, only for a tracked server player.
     * Readding this same dirty attribute does not change the native dirty-set
     * iterator. No other attribute is touched and no repeating check is added. */
    public static void attributeChanged(ServerPlayer player) {
        Member member = PLAYERS.get(player.getUUID());
        if (member != null && member.player == player && !member.applying) {
            if (ClockworkBossDiagnostics.enabled()) ClockworkBossDiagnostics.entity(player, "external_max_health_update", "previous_max", member.lastMax, "observed_max", player.getMaxHealth());
            reconcile(member, false);
        }
    }

    /** Native copying/calculation keeps external modifier math and range bounds
     * authoritative. The scratch instance removes only our modifier; it does
     * not dirty the live entity while determining the armor's own maximum. */
    private static void reconcile(Member member, boolean fullHeal) {
        if (member.applying) return;
        AttributeInstance live = member.player.getAttribute(Attributes.MAX_HEALTH);
        if (live == null) return;
        member.applying = true;
        long revision = member.revision;
        try {
            double fraction = Math.clamp(member.player.getHealth() / Math.max(1, member.lastMax), 0, 1);
            double floor = 0;
            for (var request : member.bosses.values()) floor = Math.max(floor, request.minimumMaxHealth());
            AttributeInstance scratch = new AttributeInstance(live.getAttribute(), ignored -> {});
            scratch.replaceFrom(live); scratch.removeModifier(GRANT);
            double baseline = Math.max(1, scratch.getValue());
            double amount = floor > baseline ? floor / baseline - 1 : 0;
            AttributeModifier previous = live.getModifier(GRANT);
            if (amount == 0) {
                if (previous != null) live.removeModifier(GRANT);
            } else {
                AttributeModifier desired = new AttributeModifier(GRANT, amount, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
                if (!desired.equals(previous)) live.addOrReplacePermanentModifier(desired);
            }
            double nextMax = member.player.getMaxHealth();
            if (member.player.isAlive() && (fullHeal || Double.compare(member.lastMax, nextMax) != 0))
                member.player.setHealth((float) (fullHeal ? nextMax : fraction * nextMax));
            member.lastMax = nextMax;
            if (ClockworkBossDiagnostics.enabled()) ClockworkBossDiagnostics.entity(member.player, "health_floor_reconciled",
                    "external_baseline", baseline, "requested_floor", floor, "owned_modifier", amount, "observed_max", nextMax,
                    "hp", member.player.getHealth(), "first_heal", fullHeal, "encounter_count", member.bosses.size());
        } finally {
            member.applying = false;
            // Another mod can remove a boss inside a native setHealth callback.
            // Reconcile that changed ownership after our guard is released,
            // otherwise a removed encounter could leave its modifier behind.
            if (revision != member.revision) reconcile(member, false);
        }
    }

    public static void leave(UUID boss, UUID player) {
        Member member = PLAYERS.get(player);
        if (member == null || member.bosses.remove(boss) == null) return;
        if (ClockworkBossDiagnostics.enabled()) ClockworkBossDiagnostics.entity(member.player, "health_request_removed", "boss", ClockworkBossDiagnostics.alias(boss));
        member.revision++;
        reconcile(member, false);
        if (member.bosses.isEmpty()) PLAYERS.remove(player, member);
    }
    /** Rare cleanup visits tracked participants only; snapshots tolerate other
     * mods synchronously changing membership during native attribute callbacks. */
    public static void releaseBoss(UUID boss) {
        for (Member member : List.copyOf(PLAYERS.values())) leave(boss, member.player.getUUID());
    }
    public static void releasePlayer(UUID player) {
        Member member = PLAYERS.remove(player);
        if (member == null) return;
        member.bosses.clear(); member.revision++; reconcile(member, false);
    }
    private static void clearSavedGrant(ServerPlayer player) {
        var attribute = player.getAttribute(Attributes.MAX_HEALTH);
        if (attribute == null || !attribute.hasModifier(GRANT)) return;
        float fraction = Math.clamp(player.getHealth() / Math.max(1, player.getMaxHealth()), 0, 1);
        attribute.removeModifier(GRANT);
        if (player.isAlive()) player.setHealth(fraction * player.getMaxHealth());
    }
}
