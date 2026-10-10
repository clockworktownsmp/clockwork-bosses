package clockwork.bosses;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.permissions.Permissions;

import java.util.Locale;

/** Registers the Clockwork Bosses mod, server lifecycle hooks, and player commands. */
public class ClockworkBosses implements ModInitializer {
    // Fabric lists this ID during startup. Use the same namespace for generated
    // client assets and negotiated packets so both sides identify this mod alike.
    public static final String MOD_ID = "clockwork_bosses";

    private static final SuggestionProvider<CommandSourceStack> TEMPLATE_SUGGESTIONS =
            (context, builder) -> {
                String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
                // Names are cached at initialization/reload/config writes;
                // tab completion never opens the template directory.
                for (String template : ClockworkBossTemplateStore.list()) {
                    if (template.toLowerCase(Locale.ROOT).startsWith(remaining)) {
                        builder.suggest(template);
                    }
                }
                return builder.buildFuture();
            };

    private static final SuggestionProvider<CommandSourceStack> BOSS_NAME_SUGGESTIONS =
            (context, builder) -> {
                String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
                for (String name : ClockworkBossManager.getActiveBossNames()) {
                    if (name.toLowerCase(Locale.ROOT).startsWith(remaining)) {
                        builder.suggest(name);
                    }
                }
                return builder.buildFuture();
            };

    /** Presets have their own cached suggestions; they never appear in spawn's active-template list. */
    private static final SuggestionProvider<CommandSourceStack> PRESET_SUGGESTIONS =
            (context, builder) -> {
                String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
                for (String preset : ClockworkBossTemplateStore.presets()) {
                    if (preset.startsWith(remaining)) builder.suggest(preset);
                }
                return builder.buildFuture();
            };

    /**
     * Registers common/server features once; client rendering/audio classes are loaded only by the client
     * entry point.
     */
    @Override
    public void onInitialize() {
        initializeTemplateStore();
        ClockworkBossMediaTransfers.register();
        ClockworkBossMusic.register();
        ClockworkBossSkinNetwork.register();
        ClockworkBossSoundNetwork.register();
        ClockworkBossRewards.register();
        ClockworkBossManager.registerLifecycle();
        ClockworkBossSelector.register();
        ClockworkBossTemplateEditor.register();
        ClockworkBossDiagnostics.register();

        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess, environment) -> registerCommands(dispatcher)
        );

        ServerLifecycleEvents.SERVER_STARTED.register(ClockworkBossManager::rehydrate);
    }

    /**
     * Initializes template folders and suggestions at startup; an unusable config location produces a
     * clear initialization error.
     */
    private static void initializeTemplateStore() {
        try {
            ClockworkBossTemplateStore.installBundledPresets();
            ClockworkBossTemplateStore.refreshCache();
        } catch (Exception e) {
            throw new RuntimeException(
                    "Unable to initialize Clockwork Boss template directory", e);
        }
    }

    /** Defines the administrative Brigadier command tree using native 26.3 permissions and cached suggestions. */
    private static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        // Template edits and encounter control are administrative operations.
        // Use 26.3's native permission object so console and operators work normally.
        var root = Commands.literal("boss")
                .requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER));

        // Captures are server-local and off by default. The root's native
        // operator permission protects enabling, clearing and exporting them.
        root.then(Commands.literal("debug")
                .executes(ctx -> ClockworkBossDiagnostics.status(ctx.getSource()))
                .then(Commands.literal("on").executes(ctx -> ClockworkBossDiagnostics.toggle(ctx.getSource(), true)))
                .then(Commands.literal("off").executes(ctx -> ClockworkBossDiagnostics.toggle(ctx.getSource(), false)))
                .then(Commands.literal("status").executes(ctx -> ClockworkBossDiagnostics.status(ctx.getSource())))
                .then(Commands.literal("clear").executes(ctx -> ClockworkBossDiagnostics.clearCommand(ctx.getSource())))
                .then(Commands.literal("dump").executes(ctx -> ClockworkBossDiagnostics.dump(ctx.getSource()))));

        // The picker separates reusable presets from active templates; deletion only accepts active names.
        root.then(Commands.literal("template")
                .then(Commands.literal("list")
                        .executes(ctx -> ClockworkBossManager.listTemplates(ctx.getSource())))
                .then(Commands.literal("edit")
                        .executes(ctx -> ClockworkBossTemplateEditor.open(ctx.getSource(), "", false))
                        .then(Commands.argument("template", StringArgumentType.word()).suggests(TEMPLATE_SUGGESTIONS)
                                .executes(ctx -> ClockworkBossTemplateEditor.open(ctx.getSource(), StringArgumentType.getString(ctx, "template"), false)))
                        .then(Commands.literal("preset").then(Commands.argument("preset", StringArgumentType.word()).suggests(PRESET_SUGGESTIONS)
                                .executes(ctx -> ClockworkBossTemplateEditor.open(ctx.getSource(), StringArgumentType.getString(ctx, "preset"), true)))))
                .then(Commands.literal("delete").then(Commands.argument("template", StringArgumentType.word()).suggests(TEMPLATE_SUGGESTIONS)
                        .executes(ctx -> ClockworkBossTemplateEditor.delete(ctx.getSource(), StringArgumentType.getString(ctx, "template"))))));

        // Native literal completion offers new/preset; preset names come from the cached folder list.
        root.then(
                Commands.literal("create")
                        .then(Commands.literal("new").then(
                                Commands.argument("template", StringArgumentType.word())
                                        .executes(ctx -> ClockworkBossManager.createTemplate(
                                                ctx.getSource(),
                                                StringArgumentType.getString(ctx, "template")
                                        ))))
                        .then(Commands.literal("preset").then(
                                Commands.argument("preset", StringArgumentType.word())
                                        .suggests(PRESET_SUGGESTIONS)
                                        .then(Commands.argument("template", StringArgumentType.word())
                                                .executes(ctx -> ClockworkBossManager.createTemplateFromPreset(
                                                        ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "preset"),
                                                        StringArgumentType.getString(ctx, "template"))))))
        );

        // /boss spawn <template> <name>
        root.then(
                Commands.literal("spawn")
                        .then(
                                Commands.argument("template", StringArgumentType.word())
                                        .suggests(TEMPLATE_SUGGESTIONS)
                                        .then(
                                                Commands.argument("name", StringArgumentType.greedyString())
                                                        .executes(ctx -> ClockworkBossManager.spawn(
                                                                ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "template"),
                                                                StringArgumentType.getString(ctx, "name")
                                                        ))
                                        )
                        )
        );

        // /boss activate <name>
        root.then(
                Commands.literal("activate")
                        .then(
                                Commands.argument("name", StringArgumentType.greedyString())
                                        .suggests(BOSS_NAME_SUGGESTIONS)
                                        .executes(ctx -> ClockworkBossManager.activate(
                                                ctx.getSource(),
                                                StringArgumentType.getString(ctx, "name")
                                        ))
                        )
        );

        // /boss remove <name> | /boss remove all
        root.then(
                Commands.literal("remove")
                        .then(
                                Commands.literal("all")
                                        .executes(ctx -> ClockworkBossManager.removeAll(ctx.getSource()))
                        )
                        .then(
                                Commands.argument("name", StringArgumentType.greedyString())
                                        .suggests(BOSS_NAME_SUGGESTIONS)
                                        .executes(ctx -> ClockworkBossManager.remove(
                                                ctx.getSource(),
                                                StringArgumentType.getString(ctx, "name")
                                        ))
                        )
        );

        // /boss list
        root.then(
                Commands.literal("list")
                        .executes(ctx -> ClockworkBossManager.listActive(ctx.getSource()))
        );

        // /boss select activation <template> [also_bossbar]
        // /boss select arena <template> sets the shared boss-bar/player-effect area.
        // /boss select cancel
        root.then(
                Commands.literal("select")
                        .then(
                                Commands.literal("cancel")
                                        .executes(ctx -> ClockworkBossSelector.cancel(ctx.getSource()))
                        )
                        .then(
                                Commands.literal("activation")
                                        .then(
                                                Commands.argument("template", StringArgumentType.word())
                                                        .suggests(TEMPLATE_SUGGESTIONS)
                                                        .executes(ctx -> ClockworkBossSelector.begin(
                                                                ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "template"),
                                                                "activation",
                                                                false
                                                        ))
                                                        .then(
                                                                Commands.argument("also_bossbar", BoolArgumentType.bool())
                                                                        .executes(ctx -> ClockworkBossSelector.begin(
                                                                                ctx.getSource(),
                                                                                StringArgumentType.getString(ctx, "template"),
                                                                                "activation",
                                                                                BoolArgumentType.getBool(ctx, "also_bossbar")
                                                                        ))
                                                        )
                                        )
                        )
                        .then(
                                Commands.literal("arena")
                                        .then(
                                                Commands.argument("template", StringArgumentType.word())
                                                        .suggests(TEMPLATE_SUGGESTIONS)
                                                        .executes(ctx -> ClockworkBossSelector.begin(
                                                                ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "template"),
                                                                "bossbar"
                                                        ))
                                        )
                        )
        );

        // /boss setspawn <template> stores a position on that template only.
        root.then(
                Commands.literal("setspawn")
                        .then(
                                Commands.argument("template", StringArgumentType.word())
                                        .suggests(TEMPLATE_SUGGESTIONS)
                                        .executes(ctx -> ClockworkBossManager.setSpawn(
                                                ctx.getSource(),
                                                StringArgumentType.getString(ctx, "template")
                                        ))
                        )
        );

        // /boss reload
        root.then(
                Commands.literal("reload")
                        .executes(ctx -> ClockworkBossManager.reload(ctx.getSource()))
        );

        dispatcher.register(root);
    }
}
