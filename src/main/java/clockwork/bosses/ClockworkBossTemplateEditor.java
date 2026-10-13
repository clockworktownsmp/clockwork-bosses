package clockwork.bosses;

import clockwork.bosses.ClockworkBossPermissions.Action;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Util;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/** Server-owned editor sessions, using native networking and I/O workers without a tick watcher. */
public final class ClockworkBossTemplateEditor {
    private static final Map<UUID, Session> SESSIONS = new HashMap<>();
    private static final Set<String> DELETING = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final AtomicInteger JOBS = new AtomicInteger();
    private static long deletionEpoch; // Native thread only; no growing per-file tombstone cache.
    private static final long LIFETIME = java.time.Duration.ofMinutes(30).toNanos();
    private ClockworkBossTemplateEditor() {}

    /** A session holds UUIDs and strings only; outstanding jobs are globally bounded, even across restarts. */
    private static final class Session {
        final UUID token = UUID.randomUUID();
        final UUID player;
        String name = "", revision = "";
        boolean preset, busy;
        long expires = System.nanoTime() + LIFETIME, lastRequest;
        Session(UUID player) { this.player = player; }
    }

    public static void register() {
        PayloadTypeRegistry.clientboundPlay().register(ClockworkBossEditorPayload.TYPE, ClockworkBossEditorPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ClockworkBossEditorPayload.TYPE, ClockworkBossEditorPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ClockworkBossEditorPayload.TYPE,
                (payload, context) -> receive(context.player(), payload));
        ServerPlayerEvents.LEAVE.register(player -> SESSIONS.remove(player.getUUID()));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> SESSIONS.clear());
    }

    /** Empty-name opens the picker; optional command arguments open the selected file directly. */
    public static int open(CommandSourceStack source, String name, boolean preset) {
        ServerPlayer player = source.getPlayer();
        if (player == null) { source.sendFailure(Component.literal("Open the template editor from an in-game player.")); return 0; }
        if (name.isEmpty()) {
            if (!ClockworkBossPermissions.canEdit(source)) {
                source.sendFailure(Component.literal("Opening the editor requires template or preset edit permission."));
                return 0;
            }
        } else if (!ClockworkBossPermissions.documentAction(preset).require(source)) return 0;
        if (!ServerPlayNetworking.canSend(player, ClockworkBossEditorPayload.TYPE)) {
            source.sendFailure(Component.literal("Install the editor build of Clockwork Bosses on your client.")); return 0;
        }
        SESSIONS.values().removeIf(session -> session.expires < System.nanoTime());
        if (SESSIONS.size() >= 64 && !SESSIONS.containsKey(player.getUUID())) {
            source.sendFailure(Component.literal("Too many editor sessions. Try again later.")); return 0;
        }
        Session previous = SESSIONS.get(player.getUUID());
        if (previous != null && previous.busy) { source.sendFailure(Component.literal("Wait for the current editor operation.")); return 0; }
        Session session = new Session(player.getUUID());
        SESSIONS.put(player.getUUID(), session);
        if (name.isEmpty()) catalog(player, session);
        else load(player, session, name, preset);
        return 1;
    }

    /** File kind comes from the request being opened or the established server session when saving. */
    private static boolean requireEdit(ServerPlayer player, Session session, boolean preset) {
        Action action = ClockworkBossPermissions.documentAction(preset);
        if (action.allowed(player.createCommandSourceStack())) return true;
        error(player, session, "Missing permission: " + action.dottedName());
        return false;
    }

    /** Check authority/token on every request; a malicious client cannot choose a server path. */
    private static void receive(ServerPlayer player, ClockworkBossEditorPayload payload) {
        Session session = SESSIONS.get(player.getUUID());
        if (session == null || !session.token.equals(payload.token())) return;
        // A player with revoked permissions may still close their own session. All operational
        // actions retain the permission check; closing grants no file access.
        if (payload.action().equals("close")) { SESSIONS.remove(player.getUUID()); return; }
        if (session.expires < System.nanoTime()) { error(player, session, "Editor session expired. Reopen /boss template edit."); SESSIONS.remove(player.getUUID()); return; }
        // Native custom serverbound packets are small. Reject oversized patches before parsing or scheduling I/O.
        if (session.busy || payload.json().getBytes(StandardCharsets.UTF_8).length > ClockworkBossEditorPayload.MAX_PATCH_BYTES) return;
        long now = System.nanoTime();
        if (now - session.lastRequest < 500_000_000L) { error(player, session, "Please wait a moment before trying again."); return; }
        session.lastRequest = now;
        session.expires = now + LIFETIME;
        if (payload.action().equals("open")) load(player, session, payload.name(), payload.preset());
        else if (payload.action().equals("reload")) {
            if (!Action.RELOAD.allowed(player.createCommandSourceStack())) {
                error(player, session, "Missing permission: " + Action.RELOAD.dottedName());
            } else if (ClockworkBossManager.reloadAsync(player.createCommandSourceStack()) > 0) {
                send(player, session, "reloaded", "Reload queued. Chat will report when settings are applied.");
            } else error(player, session, "Reload failed. Check chat for details.");
        }
        else if (payload.action().equals("skin") && !session.name.isEmpty()) previewSkin(player, session, payload.json());
        else if (payload.action().equals("save") && session.name.equals(payload.name())
                && session.preset == payload.preset() && session.revision.equals(payload.revision())) save(player, session, payload.json());
    }

    private static void catalog(ServerPlayer player, Session session) {
        JsonObject catalog = new JsonObject();
        JsonArray templates = new JsonArray(), presets = new JsonArray();
        // Showing one editable kind never grants access to the other kind's files.
        var source = player.createCommandSourceStack();
        if (Action.TEMPLATE_EDIT.allowed(source)) ClockworkBossTemplateStore.list().stream().limit(256).forEach(templates::add);
        if (Action.PRESET_EDIT.allowed(source)) ClockworkBossTemplateStore.presets().stream().limit(256).forEach(presets::add);
        catalog.add("templates", templates); catalog.add("presets", presets);
        send(player, session, "catalog", catalog.toString());
    }

    private static void load(ServerPlayer player, Session session, String name, boolean preset) {
        if (!requireEdit(player, session, preset)) return;
        if (!ClockworkBossTemplateNames.isValid(name) || !preset && DELETING.contains(name)) { error(player, session, "Invalid or deleting template."); return; }
        if (!acquire(player, session)) return;
        var server = player.level().getServer();
        CompletableFuture.supplyAsync(() -> {
            try { return ClockworkBossTemplateStore.loadForEditor(name, preset); }
            catch (Exception failure) { throw new java.util.concurrent.CompletionException(failure); }
        }, Util.ioPool()).whenComplete((document, failure) -> {
            JOBS.decrementAndGet();
            server.execute(() -> {
                if (!current(player, session)) return;
                session.busy = false;
                if (!requireEdit(player, session, preset)) return;
                if (failure != null) { error(player, session, reason(failure)); return; }
                session.name = name; session.preset = preset; session.revision = document.revision();
                send(player, session, "document", document.config().toString());
            });
        });
    }

    private static void save(ServerPlayer player, Session session, String patchText) {
        if (!requireEdit(player, session, session.preset)) return;
        if (!session.preset && DELETING.contains(session.name)) { error(player, session, "This template is being deleted."); return; }
        if (!acquire(player, session)) return;
        // Capture before leaving the server thread; worker tasks never read mutable session state.
        String name = session.name, revision = session.revision;
        boolean preset = session.preset;
        var server = player.level().getServer();
        var lootCatalog = ClockworkBossLoot.catalog(server);
        CompletableFuture.supplyAsync(() -> {
            try {
                JsonObject patch = ClockworkBossTemplateDocument.read(new StringReader(patchText));
                return ClockworkBossTemplateStore.saveFromEditor(name, preset, revision, patch, lootCatalog);
            } catch (Exception failure) { throw new java.util.concurrent.CompletionException(failure); }
        }, Util.ioPool()).whenComplete((document, failure) -> {
            JOBS.decrementAndGet();
            server.execute(() -> {
                if (!current(player, session)) return;
                session.busy = false;
                if (!requireEdit(player, session, preset)) return;
                if (failure != null) { error(player, session, reason(failure)); return; }
                session.revision = document.revision();
                // Saving is deliberately separate from /boss reload; a form edit cannot restart a live encounter.
                send(player, session, "saved", document.config().toString());
            });
        });
    }

    private static boolean current(ServerPlayer player, Session session) {
        // Identity and authorization are separate so a denied completion can release
        // busy state. A write accepted before revocation may finish on its I/O worker.
        return SESSIONS.get(session.player) == session && player.level().getServer().getPlayerList().getPlayer(session.player) == player;
    }

    /** Custom preview reuses the validated PNG/chunk path with an isolated identity, not a live boss texture key. */
    private static void previewSkin(ServerPlayer player, Session session, String file) {
        if (!requireEdit(player, session, session.preset)) return;
        if (!CustomWardenSkinPayload.isValidFileName(file)) { error(player, session, "Invalid PNG filename."); return; }
        if (!acquire(player, session)) return;
        String name = session.name; boolean preset = session.preset;
        var server = player.level().getServer();
        CompletableFuture.supplyAsync(() -> {
            try {
                byte[] bytes = ClockworkBossTemplateStore.loadEditorSkin(name, preset, file);
                return ClockworkBossMediaTransfers.prepare(new CustomWardenSkinPayload("__gui_" + session.token, file, bytes), net.minecraft.core.RegistryAccess.EMPTY);
            } catch (Exception failure) { throw new java.util.concurrent.CompletionException(failure); }
        }, Util.ioPool()).whenComplete((transfer, failure) -> {
            JOBS.decrementAndGet();
            server.execute(() -> {
                if (!current(player, session)) return;
                session.busy = false;
                if (!requireEdit(player, session, preset)) return;
                if (failure != null) { error(player, session, reason(failure)); return; }
                boolean queued = ClockworkBossMediaTransfers.send(player, "editor-skin:" + session.token + ":" + file, transfer, () -> {}, () -> false,
                        () -> current(player, session) && ClockworkBossPermissions.documentAction(preset).allowed(player.createCommandSourceStack()));
                if (queued) send(player, session, "preview_ready", file);
                else error(player, session, "Skin transfer is busy. Try Preview again shortly.");
            });
        });
    }

    private static boolean acquire(ServerPlayer player, Session session) {
        if (JOBS.incrementAndGet() > 4) { JOBS.decrementAndGet(); error(player, session, "Editor is busy. Try again shortly."); return false; }
        session.busy = true;
        return true;
    }

    private static void send(ServerPlayer player, Session session, String action, String json) {
        if (json.length() > ClockworkBossEditorPayload.MAX_JSON_CHARS) { error(player, session, "Document is too large for the GUI. Edit it as a file."); return; }
        ServerPlayNetworking.send(player, new ClockworkBossEditorPayload(session.token, action, session.preset, session.name, session.revision, json));
    }
    private static void error(ServerPlayer player, Session session, String message) { send(player, session, "error", message.substring(0, Math.min(512, message.length()))); }
    private static String reason(Throwable failure) { while (failure.getCause() != null) failure = failure.getCause(); return failure.getMessage() == null ? "Could not access the template file." : failure.getMessage(); }

    public static boolean isDeleting(String name) { return DELETING.contains(name); }
    public static long deletionEpoch() { return deletionEpoch; }

    /** Templates only: never resolves a preset path, and refuses templates used by loaded bosses. */
    public static int delete(CommandSourceStack source, String name) {
        if (!Action.TEMPLATE_DELETE.require(source)) return 0;
        if (!ClockworkBossTemplateNames.isValid(name)) { source.sendFailure(Component.literal("Use a valid template name.")); return 0; }
        if (ClockworkBossManager.usesTemplate(name)) {
            source.sendFailure(Component.literal("Remove loaded bosses using this template before deleting it.")); return 0;
        }
        if (JOBS.incrementAndGet() > 4) { JOBS.decrementAndGet(); source.sendFailure(Component.literal("Editor is busy.")); return 0; }
        if (!DELETING.add(name)) { JOBS.decrementAndGet(); source.sendFailure(Component.literal("This template is already being deleted.")); return 0; }
        deletionEpoch++;
        CompletableFuture.supplyAsync(() -> {
            try { return ClockworkBossTemplateStore.deleteTemplate(name); }
            catch (Exception failure) { throw new java.util.concurrent.CompletionException(failure); }
        }, Util.ioPool()).whenComplete((archive, failure) -> {
            JOBS.decrementAndGet();
            DELETING.remove(name);
            source.getServer().execute(() -> {
                if (failure != null) source.sendFailure(Component.literal(reason(failure)));
                else source.sendSuccess(() -> Component.literal("Deleted template " + name + ". Its files are retained in deleted-templates/.").withStyle(net.minecraft.ChatFormatting.GREEN), true);
            });
        });
        return 1;
    }
}
