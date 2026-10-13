package clockwork.example;

import clockwork.bosses.api.BossAction;
import clockwork.bosses.api.BossActionContext;
import clockwork.bosses.api.BossActionRegistry;
import clockwork.bosses.api.BossActionType;
import clockwork.bosses.api.BossAddon;
import clockwork.bosses.api.BossEncounterEvents;
import clockwork.bosses.api.BossSetting;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import org.slf4j.LoggerFactory;

/** No mixins, core implementation imports or client entrypoint. The server
 * advertises this schema to the editor; every execution receives a fresh action. */
public final class LightningAddon implements BossAddon {
    @Override public void register(BossActionRegistry.Registrar actions) {
        actions.register(new BossActionType(
                Identifier.fromNamespaceAndPath("clockwork_lightning_example", "lightning"),
                "Example lightning", "Warn one eligible participant, then strike once without setting fires.",
                List.of(
                        BossSetting.number("warning_ticks", "Warning ticks", "Time to react before the strike; 20 ticks = one second.", 40, 1, 200, true),
                        BossSetting.number("damage", "Damage HP", "Native damage amount, before armor and other mods adjust it.", 8, 0, 100, false),
                        BossSetting.text("target_mode", "Target choice", "Use the current target or pick a random arena participant.", "current", List.of("current", "random"), 16)),
                Set.of(), Lightning::new)); // No Warden-specific capability is required.

        // Event snapshots can be logged without retaining players/worlds.
        BossEncounterEvents.STARTED.register(encounter -> LoggerFactory.getLogger("clockwork_lightning_example")
                .info("Encounter activated: {} ({})", encounter.name(), encounter.bossId()));
    }

    /** Holds only a participant UUID and timing state. Native damage is routed
     * through the core so its eligibility, hit notifications and rotation apply. */
    private static final class Lightning implements BossAction {
        private UUID target;
        private long struckAt = -1;
        @Override public void start(BossActionContext context) {
            if (context.settings().text("target_mode").equals("current"))
                context.target().filter(ServerPlayer.class::isInstance).map(ServerPlayer.class::cast)
                        .ifPresent(player -> target = player.getUUID());
            if (target == null) {
                var players = context.participants();
                if (!players.isEmpty()) target = players.get(context.controller().mob().getRandom().nextInt(players.size())).getUUID();
            }
        }
        @Override public Result tick(BossActionContext context) {
            long elapsed = context.elapsedTicks();
            if (struckAt >= 0) return elapsed - struckAt >= 5 ? Result.COMPLETE : Result.RUNNING;
            ServerPlayer player = target == null ? null : context.level().getServer().getPlayerList().getPlayer(target);
            if (player == null || !context.isParticipant(player)) return Result.COMPLETE;
            if (elapsed < context.settings().integer("warning_ticks")) {
                // A few native particles from the existing action callback; no
                // additional watcher, task queue, packet protocol or assets.
                if (elapsed % 5 == 0) context.level().sendParticles(ParticleTypes.ELECTRIC_SPARK,
                        player.getX(), player.getY() + 1, player.getZ(), 6, .35, .6, .35, .03);
                return Result.RUNNING;
            }
            var bolt = EntityTypes.LIGHTNING_BOLT.create(context.level(), EntitySpawnReason.COMMAND);
            if (bolt == null) return Result.COMPLETE;
            bolt.setVisualOnly(true); // No fire or duplicate native lightning damage.
            bolt.snapTo(player.getX(), player.getY(), player.getZ(), 0, 0);
            context.ownEntity(bolt); // Acquire cleanup before publishing the entity.
            context.level().addFreshEntity(bolt);
            struckAt = elapsed;
            context.damage(player, context.controller().mob().damageSources().mobAttack(context.controller().mob()),
                    (float) context.settings().number("damage"));
            return Result.RUNNING;
        }
        // Scope cleanup discards the owned bolt on completion/interruption even
        // if a later callback fails. No custom stop method is needed here.
    }
}
