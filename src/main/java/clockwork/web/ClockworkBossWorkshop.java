package clockwork.web;

import clockwork.bosses.ClockworkBossPermissions;
import clockwork.bosses.ClockworkBossTemplateSchema;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.server.players.NameAndId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Dedicated-server entrypoint for the optional workshop module. Fabric never
 * invokes it on a physical client. It owns lifecycle/commands, while file and
 * relay workers own migration, crypto, media validation and connections. */
public final class ClockworkBossWorkshop implements DedicatedServerModInitializer {
    static final String LEGACY_MOD_ID = "clockwork_bosses_web";
    static final String UPGRADE_MESSAGE = "Clockwork Bosses now includes the workshop. Remove the old Clockwork Bosses Web Companion jar from mods, keep its config/data, and restart with the single Clockwork Bosses jar.";
    private static final Logger LOG = LoggerFactory.getLogger("clockwork_bosses_workshop");
    private static final AtomicBoolean REGISTERED = new AtomicBoolean();
    private volatile WorkshopRuntime runtime;
    private volatile TemplateApi api;
    // Transport readiness can disappear while authenticated sessions remain
    // valid. Keep their owner separately so logout also revokes during outages.
    private volatile TemplateApi accessApi;
    // Capture on the native thread; the settings worker must not read server state.
    private volatile boolean onlineMode;

    @Override public void onInitializeServer() {
        rejectLegacy(FabricLoader.getInstance().isModLoaded(LEGACY_MOD_ID));
        if (!REGISTERED.compareAndSet(false, true)) return;
        ServerLifecycleEvents.SERVER_STARTED.register(this::start);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> stop());
        CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> registerCommands(dispatcher));
    }

    /** Metadata breaks normally rejects the legacy mod before initialization.
     * This guard also protects manual/alternate entrypoint invocation. */
    static void rejectLegacy(boolean present) {
        if (present) throw new IllegalStateException(UPGRADE_MESSAGE);
    }
    static boolean ownerAllowed(CommandSourceStack source) {
        return source.permissions().hasPermission(Permissions.COMMANDS_OWNER);
    }

    /** Both spellings share the same handlers and native owner-level guard.
     * The boss root also admits owners without granting its other operations. */
    void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("boss").requires(ClockworkBossPermissions::canUseCommands)
                .then(branch("workshop")));
        dispatcher.register(branch("bossworkshop"));
    }
    private LiteralArgumentBuilder<CommandSourceStack> branch(String name) {
        return Commands.literal(name).requires(ClockworkBossWorkshop::ownerAllowed)
                .then(Commands.literal("open").executes(context -> open(context.getSource())))
                .then(Commands.literal("logout").executes(context -> logout(context.getSource())))
                .then(Commands.literal("reload").executes(context -> reload(context.getSource())));
    }
    private int open(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var player = source.getPlayerOrException();
        TemplateApi current = api;
        if (current == null || !source.getServer().usesAuthentication()) {
            source.sendFailure(Component.literal("Workshop unavailable. Enable workshop/template access on an online-mode server and check its HTTPS relay connection."));
            return 0;
        }
        try {
            var owner = new AccessSessions.Owner(player.getUUID(), player.getGameProfile().name());
            String code = current.access().issue(owner);
            URI link = URI.create(current.workshopUrl() + "#login=" + code);
            // A private chat fragment, never a broadcast, log or query parameter.
            source.sendSuccess(() -> Component.literal("Open Boss Workshop (expires in 2 minutes; one use)")
                    .withStyle(style -> style.withUnderlined(true).withClickEvent(new ClickEvent.OpenUrl(link))), false);
            return 1;
        } catch (IllegalStateException unavailable) {
            source.sendFailure(Component.literal(unavailable.getMessage())); return 0;
        }
    }
    private int logout(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var player = source.getPlayerOrException(); revoke(player.getUUID());
        source.sendSuccess(() -> Component.literal("Workshop links and browser sessions revoked."), false);
        return 1;
    }
    void revoke(java.util.UUID owner) {
        TemplateApi current = accessApi;
        if (current != null) current.access().revoke(owner);
    }
    private int reload(CommandSourceStack source) {
        WorkshopRuntime current = runtime;
        if (current == null) { source.sendFailure(Component.literal("Workshop is not running yet.")); return 0; }
        onlineMode = source.getServer().usesAuthentication();
        try {
            var pending = current.reload(false);
            source.sendSuccess(() -> Component.literal("Reloading workshop settings..."), false);
            pending.whenComplete((result, error) -> {
                var server = source.getServer();
                if (!server.isStopped()) server.execute(() -> {
                    if (runtime != current || server.isStopped()) return;
                    if (error == null) source.sendSuccess(() -> Component.literal(result.message()), false);
                    else {
                        LOG.warn("Workshop reload failed ({})", error.getClass().getSimpleName());
                        String kept = error instanceof WorkshopRuntime.StartFailed ? "" : ". Previous workshop settings were kept.";
                        source.sendFailure(Component.literal("Workshop reload failed: " + error.getMessage() + kept));
                    }
                });
            });
            return 1;
        } catch (IOException busy) { source.sendFailure(Component.literal(busy.getMessage())); return 0; }
    }

    /** Startup creates only the settings worker. An inactive configuration never
     * captures items, creates a relay key or starts network/upload workers. */
    private void start(MinecraftServer server) {
        stop(); onlineMode = server.usesAuthentication();
        Path config = FabricLoader.getInstance().getConfigDir();
        WorkshopRuntime current = new WorkshopRuntime(() -> WorkshopMigration.settingsFile(config), () -> onlineMode,
                new WorkshopRuntime.Factory() {
                    private TemplateFiles files;
                    private ItemCatalog catalog;
                    private boolean catalogCaptured;
                    @Override public WorkshopRuntime.Prepared prepare(WorkshopSettings settings) throws IOException {
                        RelayIdentity identity = RelayIdentity.load(config);
                        // One writer lock and immutable registry snapshot survive config replacements.
                        // Schema comes from core/addon definitions rather than a second settings catalog.
                        if (files == null) files = new TemplateFiles(config,
                                new TemplateJson(ClockworkBossTemplateSchema.write().getBytes(StandardCharsets.UTF_8)),
                                clockwork.bosses.ClockworkBossTemplateStore.class);
                        else files.verifyStorage();
                        if (!catalogCaptured) {
                            catalogCaptured = true;
                            try { catalog = WorkshopItemSnapshot.capture(server); }
                            catch (IOException unavailable) { LOG.warn("Workshop item catalog unavailable; manual item IDs remain available"); }
                        }
                        TemplateApi prepared = new TemplateApi(settings.origin().toString(),
                                new AccessSessions(owner -> ownerAllowed(server, owner)), files, identity.id(), catalog);
                        return new WorkshopRuntime.Prepared() {
                            private RelayClient relay;
                            @Override public void start(java.util.function.Consumer<TemplateApi> available) {
                                accessApi = prepared;
                                relay = new RelayClient(settings.origin(), identity, () -> prepared, available);
                            }
                            @Override public void close() {
                                if (accessApi == prepared) accessApi = null;
                                if (relay != null) relay.close(); prepared.close();
                            }
                        };
                    }
                }, configured -> api = configured);
        runtime = current;
        try {
            current.reload(true).whenComplete((result, error) -> {
                if (runtime != current) return;
                if (error == null) LOG.info("{}", result.message());
                else LOG.error("Workshop unavailable: {}. Correct workshop settings and use /boss workshop reload. Boss gameplay remains available.", error.getMessage());
            });
        } catch (IOException stopping) { LOG.warn("Workshop settings did not start ({})", stopping.getClass().getSimpleName()); }
    }

    /** Every admitted API operation asks native Minecraft for current owner
     * permission. Workers wait at most two seconds; no disk/network runs there. */
    private boolean ownerAllowed(MinecraftServer server, AccessSessions.Owner owner) {
        Supplier<Boolean> check = () -> !server.isStopped() && server.usesAuthentication()
                && server.getProfilePermissions(new NameAndId(owner.id(), owner.name())).hasPermission(Permissions.COMMANDS_OWNER);
        if (server.isSameThread()) return check.get();
        var pending = server.submit(check);
        try { return pending.get(2, TimeUnit.SECONDS); }
        catch (Exception unavailable) {
            pending.cancel(false);
            if (unavailable instanceof InterruptedException) Thread.currentThread().interrupt();
            return false;
        }
    }
    /** Only the server-stop hook waits for bounded cancellation; normal reload
     * replaces connections on the config worker rather than blocking a fight. */
    private void stop() {
        WorkshopRuntime previous = runtime; runtime = null; api = null; accessApi = null;
        if (previous != null) previous.close();
    }
}
