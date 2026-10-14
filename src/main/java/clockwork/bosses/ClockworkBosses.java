package clockwork.bosses;

import clockwork.bosses.ClockworkBossPermissions.Action;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

import java.util.Locale;

/** Registers the Clockwork Bosses mod, server lifecycle hooks, and player commands. */
public class ClockworkBosses implements ModInitializer {
    // Fabric lists this ID during startup. Use the same namespace for generated
    // client assets and negotiated packets so both sides identify this mod alike.
    public static final String MOD_ID = "clockwork_bosses";
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(MOD_ID);

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
        // Every addon uses this startup entrypoint. Freeze definitions before
        // templates or editor I/O workers compile settings from the catalog.
        clockwork.bosses.api.BossActionRegistry.types();
        for (var entry : net.fabricmc.loader.api.FabricLoader.getInstance().getEntrypointContainers(
                "clockwork_bosses", clockwork.bosses.api.BossAddon.class)) {
            var registry = clockwork.bosses.api.BossActionRegistry.registrar(entry.getProvider().getMetadata().getId());
            try { entry.getEntrypoint().register(registry); } finally { registry.close(); }
        }
        clockwork.bosses.api.BossActionRegistry.freeze();
        initializeTemplateStore();
        ClockworkBossTemplateTasks.register();
        ClockworkBossMediaTransfers.register();
        ClockworkBossMusic.register();
        ClockworkBossSkinNetwork.register();
        ClockworkBossSoundNetwork.register();
        ClockworkBossRewards.register();
        ClockworkBossManager.registerLifecycle();
        ClockworkBossPvp.register();
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

    /** Defines the command tree using Fabric permission nodes and cached suggestions. */
    private static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        // A grant for any child makes the root visible. Each operation checks its own
        // node both here (completion) and in its handler (execution and GUI requests).
        var root = Commands.literal("boss")
                .requires(ClockworkBossPermissions::canUseCommands);

        // Captures are server-local and off by default; their separate permission
        // does not need to be granted to ordinary encounter hosts or template editors.
        root.then(Commands.literal("debug")
                .requires(Action.DEBUG::allowed)
                .executes(ctx -> ClockworkBossDiagnostics.status(ctx.getSource()))
                .then(Commands.literal("on").executes(ctx -> ClockworkBossDiagnostics.toggle(ctx.getSource(), true)))
                .then(Commands.literal("off").executes(ctx -> ClockworkBossDiagnostics.toggle(ctx.getSource(), false)))
                .then(Commands.literal("status").executes(ctx -> ClockworkBossDiagnostics.status(ctx.getSource())))
                .then(Commands.literal("clear").executes(ctx -> ClockworkBossDiagnostics.clearCommand(ctx.getSource())))
                .then(Commands.literal("dump").executes(ctx -> ClockworkBossDiagnostics.dump(ctx.getSource()))));

        // The picker separates reusable presets from active templates; deletion only accepts active names.
        root.then(Commands.literal("template")
                .requires(ClockworkBossPermissions::canUseTemplates)
                .then(Commands.literal("schema").requires(Action.TEMPLATE_SCHEMA::allowed)
                        .executes(ctx -> exportTemplateSchema(ctx.getSource())))
                .then(Commands.literal("list")
                        .requires(Action.TEMPLATE_LIST::allowed)
                        .executes(ctx -> ClockworkBossManager.listTemplates(ctx.getSource())))
                .then(Commands.literal("edit")
                        .requires(ClockworkBossPermissions::canEdit)
                        .executes(ctx -> ClockworkBossTemplateEditor.open(ctx.getSource(), "", false))
                        .then(Commands.argument("template", StringArgumentType.word()).suggests(TEMPLATE_SUGGESTIONS)
                                .requires(Action.TEMPLATE_EDIT::allowed)
                                .executes(ctx -> ClockworkBossTemplateEditor.open(ctx.getSource(), StringArgumentType.getString(ctx, "template"), false)))
                        .then(Commands.literal("preset").requires(Action.PRESET_EDIT::allowed)
                                .then(Commands.argument("preset", StringArgumentType.word()).suggests(PRESET_SUGGESTIONS)
                                .executes(ctx -> ClockworkBossTemplateEditor.open(ctx.getSource(), StringArgumentType.getString(ctx, "preset"), true)))))
                .then(Commands.literal("delete").requires(Action.TEMPLATE_DELETE::allowed)
                        .then(Commands.argument("template", StringArgumentType.word()).suggests(TEMPLATE_SUGGESTIONS)
                        .executes(ctx -> ClockworkBossTemplateEditor.delete(ctx.getSource(), StringArgumentType.getString(ctx, "template"))))));

        // Native literal completion offers new/preset; preset names come from the cached folder list.
        root.then(
                Commands.literal("create")
                        .requires(Action.CREATE::allowed)
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
                        .requires(Action.SPAWN::allowed)
                        .then(
                                Commands.argument("template", StringArgumentType.word())
                                        .suggests(TEMPLATE_SUGGESTIONS)
                                        .then(
                                                Commands.argument("name", StringArgumentType.greedyString())
                                                        .executes(ctx -> ClockworkBossManager.spawnAsync(
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
                        .requires(Action.ACTIVATE::allowed)
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
                        .requires(source -> Action.REMOVE.allowed(source) || Action.REMOVE_ALL.allowed(source))
                        .then(
                                Commands.literal("all")
                                        .requires(Action.REMOVE_ALL::allowed)
                                        .executes(ctx -> ClockworkBossManager.removeAll(ctx.getSource()))
                        )
                        .then(
                                Commands.argument("name", StringArgumentType.greedyString())
                                        .requires(Action.REMOVE::allowed)
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
                        .requires(Action.LIST::allowed)
                        .executes(ctx -> ClockworkBossManager.listActive(ctx.getSource()))
        );

        // /boss select activation <template> [also_bossbar]
        // /boss select arena <template> sets the shared boss-bar/player-effect area.
        // /boss select cancel
        root.then(
                Commands.literal("select")
                        .requires(ClockworkBossPermissions::canSelect)
                        .then(
                                Commands.literal("cancel")
                                        .requires(ClockworkBossPermissions::canSelect)
                                        .executes(ctx -> ClockworkBossSelector.cancel(ctx.getSource()))
                        )
                        .then(
                                Commands.literal("activation")
                                        .requires(Action.SELECT_ACTIVATION::allowed)
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
                                        .requires(Action.SELECT_ARENA::allowed)
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
                        .requires(Action.SETSPAWN::allowed)
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
                        .requires(Action.RELOAD::allowed)
                        .executes(ctx -> ClockworkBossManager.reloadAsync(ctx.getSource()))
        );

        dispatcher.register(root);
    }

    /** Authorized sources can export definitions after addon registration. The
     * destination is fixed; this command never accepts arbitrary filesystem paths. */
    private static int exportTemplateSchema(CommandSourceStack source) {
        if (!Action.TEMPLATE_SCHEMA.require(source)) return 0;
        try {
            ClockworkBossTemplateStore.exportSchema();
            source.sendSuccess(() -> net.minecraft.network.chat.Component.literal(
                    "Exported config/clockwork-bosses/template-schema.json (including installed addon actions)."), false);
            return 1;
        } catch (Exception failure) {
            LOGGER.warn("Could not export template schema", failure);
            source.sendFailure(net.minecraft.network.chat.Component.literal("Could not export template schema: " + failure.getMessage()));
            return 0;
        }
    }
}
