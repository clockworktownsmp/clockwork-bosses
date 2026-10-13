package clockwork.bosses;

import clockwork.bosses.api.BossAction;
import clockwork.bosses.api.BossActionContext;
import clockwork.bosses.api.BossActionRegistry;
import clockwork.bosses.api.BossActionType;
import clockwork.bosses.api.BossRoarSound;
import clockwork.bosses.api.BossSetting;
import java.util.List;
import java.util.Set;
import net.minecraft.resources.Identifier;

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
                        BossSetting.number("depth", "Depth", "Burial depth in blocks; automatically increased to hide a scaled boss.", 5, 1, 32, false)), Set.of(BURROW), Burrow::new));
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
        @Override public void start(BossActionContext context) {
            context.controller().startBurrow(context.settings().integer("underground_ticks"), context.settings().number("depth"));
        }
        @Override public Result tick(BossActionContext context) {
            return context.controller().burrowFinished() ? Result.COMPLETE : Result.RUNNING;
        }
    }
}
