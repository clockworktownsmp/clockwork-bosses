package clockwork.bosses;

import clockwork.bosses.api.BossAction;
import clockwork.bosses.api.BossActionContext;
import clockwork.bosses.api.BossActionRegistry;
import clockwork.bosses.api.BossActionType;
import clockwork.bosses.api.BossRoarSound;
import clockwork.bosses.api.BossSetting;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.block.LevelEvent;
import net.minecraft.world.phys.AABB;

/** Core actions are ordinary registrations. The Warden adapter is private to
 * the implementation; addon factories only depend on the public action context. */
public final class ClockworkBossActions {
    public static final Identifier COMBAT = id("combat"), MELEE = id("melee"), SONIC = id("sonic_boom"),
            RECOVERY = id("recovery"), ROAR = id("roar"), BURROW = id("burrow");
    static final Set<Identifier> WARDEN_CAPABILITIES = Set.of(MELEE, SONIC, ROAR, BURROW);
    private ClockworkBossActions() {}
    private static Identifier id(String path) { return Identifier.fromNamespaceAndPath(ClockworkBosses.MOD_ID, path); }
    /** Legacy type names remain valid without rewriting existing templates. */
    public static Identifier resolve(String type) {
        return switch (type) { case "attack" -> COMBAT; case "recovery" -> RECOVERY; default -> {
            Identifier id = Identifier.tryParse(type);
            if (id == null) throw new IllegalArgumentException("Invalid action ID: " + type);
            yield id;
        } };
    }
    public static void register(BossActionRegistry.Registrar registry) {
        var budget = List.of(BossSetting.number("attacks_before_next", "Attacks before next", "Completed swings or fired beams before advancing.", 3, 1, 1000, true));
        registry.register(new BossActionType(COMBAT, "Combat", "Use the phase's enabled melee and sonic attacks.", budget, Set.of(MELEE, SONIC), () -> new Combat(null)));
        registry.register(new BossActionType(MELEE, "Melee", "Use the existing melee wind-up, damage and cooldown settings.", budget, Set.of(MELEE), () -> new Combat(MELEE)));
        registry.register(new BossActionType(SONIC, "Sonic boom", "Use the existing sonic charge, range, damage and cooldown settings.", budget, Set.of(SONIC), () -> new Combat(SONIC)));
        registry.register(new BossActionType(RECOVERY, "Recovery", "Pause attacks and movement for a configured duration.",
                List.of(BossSetting.number("duration_ticks", "Duration ticks", "Length of the pause; 20 ticks = one second.", 60, 1, 12000, true)), Set.of(), Recovery::new));
        registry.register(new BossActionType(ROAR, "Roar", "Perform Minecraft's Warden roar once, then advance after its animation.",
                List.of(BossSetting.text("file", "Sound filename", "Blank keeps the native roar; use a lowercase OGG filename from this template's sounds folder.", "", List.of(), ClockworkBossTemplateText.rule("sounds.roar.file").maxLength()),
                        BossSetting.number("volume", "Volume", "Volume of the custom roar.", 1, 0, 4, false),
                        BossSetting.number("pitch", "Pitch", "Playback pitch of the custom roar.", 1, .5, 2, false),
                        BossSetting.number("range", "Range", "0 = automatic arena diagonal/radius (32 without an arena); positive distance is manual, capped at 512 blocks.",
                                ClockworkBossSoundDistance.AUTOMATIC, ClockworkBossSoundDistance.AUTOMATIC,
                                ClockworkBossSoundDistance.MAX_RANGE, false)), Set.of(ROAR), Roar::new));
        registry.register(new BossActionType(BURROW, "Burrow and emerge", "Dig, wait underground, then emerge safely near the target and resume the sequence.",
                List.of(BossSetting.number("underground_ticks", "Underground ticks", "Time underground between digging and emergence.", 60, 1, 12000, true),
                        BossSetting.number("depth", "Depth", "Burial depth in blocks; automatically increased to hide a scaled boss.", 5, 1, 32, false),
                        BossSetting.number("emerge_damage", "Emergence damage (HP)", "Raw HP before armor and native defenses, once when both feet reach the surface. 0 disables damage; missing/null uses 20. The ground burst is independent.", 20, 0, 1_000_000, false),
                        BossSetting.number("emerge_radius", "Emergence radius (blocks)", "Real-block 3D distance from boss feet to eligible player feet, inclusive and independent of boss scale or line of sight. 0 disables damage; missing/null uses 10. The decorative ground burst keeps its native size.", 10, 0, 64, false),
                        BossSetting.bool("emerge_particles", "Emergence ground burst", "One native mace-style ground burst at surfacing, even without damage or nearby players. Decorative native size; no blocks are broken. Missing/null uses true.", true)), Set.of(BURROW), Burrow::new));
    }
    private static final class Combat implements BossAction {
        private final Identifier only;
        private Combat(Identifier only) { this.only = only; }
        @Override public Result tick(BossActionContext context) {
            return Result.RUNNING; // Native completion callbacks own the attack budget.
        }
        @Override public boolean allowsNativeAttack(Identifier attack) { return only == null || only.equals(attack); }
        @Override public boolean pausesNativeBehaviors() { return false; }
    }
    private static final class Recovery implements BossAction {
        @Override public Result tick(BossActionContext context) {
            return context.elapsedTicks() >= context.settings().integer("duration_ticks") ? Result.COMPLETE : Result.RUNNING;
        }
    }
    private static final class Roar implements BossAction {
        @Override public void start(BossActionContext context) {
            var settings = context.settings();
            context.controller().startRoar(new BossRoarSound(settings.text("file"), settings.number("volume"),
                    settings.number("pitch"), settings.number("range")));
        }
        @Override public Result tick(BossActionContext context) {
            return context.controller().roarFinished() ? Result.COMPLETE : Result.RUNNING;
        }
        @Override public boolean pausesNativeBehaviors() { return false; } // ROAR must run natively.
    }
    /** This exercises scoped lock/pose/position restoration through the same
     * public lifecycle available to addons. It never enables vanilla despawning DIG. */
    private static final class Burrow implements BossAction {
        private boolean live, impactCommitted;
        private record Victim(ServerPlayer player, ServerGamePacketListenerImpl connection) {}
        @Override public void start(BossActionContext context) {
            live = true;
            context.onCleanup(() -> live = false);
            context.controller().startBurrow(context.settings().integer("underground_ticks"), context.settings().number("depth"));
        }
        @Override public Result tick(BossActionContext context) {
            if (!live || impactCommitted) return Result.COMPLETE;
            if (!context.controller().burrowFinished()) return Result.RUNNING;
            // Commit before any native callback. Stop/cleanup can run reentrantly
            // during event delivery, eligibility checks, damage or target rotation.
            impactCommitted = true;
            var mob = context.controller().mob();
            var level = context.level();
            var center = mob.position();
            var victims = List.copyOf(level.players()).stream().map(player -> new Victim(player, player.connection)).toList();
            var bounds = mob.getBoundingBox();
            // getOnPos() may retain its previous supporting-block cache across a
            // burrow teleport. Query the committed feet once without changing that
            // native cache; this also handles tall/slab collision shapes.
            var ground = level.findSupportingBlock(mob, new AABB(bounds.minX, center.y - 1.0E-5, bounds.minZ,
                    bounds.maxX, center.y, bounds.maxZ)).orElseGet(() -> BlockPos.containing(center.x, center.y - 1.0E-5, center.z));
            if (!live) return Result.COMPLETE;
            if (context.settings().bool("emerge_particles")) level.levelEvent(LevelEvent.PARTICLES_SMASH_ATTACK, ground, 750);
            if (!live) return Result.COMPLETE;
            double damage = context.settings().number("emerge_damage"), radius = context.settings().number("emerge_radius");
            if (damage == 0 || radius == 0) return Result.COMPLETE;
            var source = mob.damageSources().mobAttack(mob);
            for (var victim : victims) {
                if (!live) break;
                ServerPlayer player = victim.player();
                if (level.getServer().getPlayerList().getPlayer(player.getUUID()) != player
                        || victim.connection() == null || player.connection != victim.connection() || player.level() != level || !player.isAlive() || player.isRemoved()
                        || player.isSpectator() || player.isCreative() || player.hasDisconnected()) continue;
                double distance = player.position().distanceToSqr(center);
                if (!Double.isFinite(distance) || distance > radius * radius) continue;
                boolean eligible = context.isParticipant(player);
                if (!live) break;
                if (eligible) context.damage(player, source, (float) damage);
            }
            return Result.COMPLETE;
        }
        @Override public void stop(BossActionContext context, StopReason reason) { live = false; }
    }
}
