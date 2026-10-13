package clockwork.bosses;

import net.fabricmc.fabric.api.permission.v1.PermissionNode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permissions;

/**
 * The shared authorization boundary for commands, editor packets, and selection tools.
 * Fabric asks the installed permission provider; an unanswered request falls back to
 * Minecraft's game-master permission (normally operator level 2). No provider is required.
 */
public final class ClockworkBossPermissions {
    private ClockworkBossPermissions() {}

    /**
     * One immutable node per operation. Fabric uses namespaced slash paths;
     * LuckPerms exposes them as dotted nodes, such as clockwork_bosses.command.spawn.
     * Keeping the catalog here prevents the GUI and command tree from drifting apart.
     */
    public enum Action {
        CREATE("command/create"),
        SPAWN("command/spawn"),
        ACTIVATE("command/activate"),
        REMOVE("command/remove"),
        REMOVE_ALL("command/remove/all"),
        LIST("command/list"),
        TEMPLATE_LIST("command/template/list"),
        TEMPLATE_SCHEMA("command/template/schema"),
        TEMPLATE_EDIT("command/template/edit"),
        PRESET_EDIT("command/preset/edit"),
        TEMPLATE_DELETE("command/template/delete"),
        SETSPAWN("command/setspawn"),
        SELECT_ACTIVATION("command/select/activation"),
        SELECT_ARENA("command/select/arena"),
        RELOAD("command/reload"),
        DEBUG("command/debug");

        private final PermissionNode<Boolean> node;
        private final String dottedName;

        Action(String path) {
            node = PermissionNode.of(ClockworkBosses.MOD_ID, path);
            dottedName = ClockworkBosses.MOD_ID + '.' + path.replace('/', '.');
        }

        /**
         * Requests the current contextual grant every time. Explicit provider denials
         * override operator access. The native Boolean fallback also works with providers
         * that replace Minecraft's permission set, without assuming an integer OP level.
         */
        public boolean allowed(CommandSourceStack source) {
            return source.checkPermission(node,
                    source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER));
        }

        /** Execution-time protection also covers packets and direct command-handler calls. */
        public boolean require(CommandSourceStack source) {
            if (allowed(source)) return true;
            source.sendFailure(Component.literal("Missing permission: " + dottedName));
            return false;
        }

        /** The conventional node name used by LuckPerms commands and user-facing errors. */
        public String dottedName() { return dottedName; }
    }

    // Cache definitions, never grants: permission providers own their caching and contexts.
    private static final Action[] COMMAND_ACTIONS = Action.values();
    private static final Action[] TEMPLATE_ACTIONS = {
            Action.TEMPLATE_LIST, Action.TEMPLATE_SCHEMA, Action.TEMPLATE_EDIT,
            Action.PRESET_EDIT, Action.TEMPLATE_DELETE
    };

    /** A parent command must not block someone granted only one of its child operations. */
    public static boolean canUseCommands(CommandSourceStack source) {
        if (ClockworkBossSelector.hasSession(source)) return true;
        for (Action action : COMMAND_ACTIONS) if (action.allowed(source)) return true;
        // Workshop keeps the companion's native owner-level guard. A server
        // owner denied all boss-admin nodes must still reach /boss workshop.
        // This checks native permissions only and loads no server workshop code
        // during common/client initialization; its child commands recheck access.
        return source.permissions().hasPermission(Permissions.COMMANDS_OWNER);
    }

    /** The picker can be opened with either editing grant; each file type is checked separately. */
    public static boolean canEdit(CommandSourceStack source) {
        return Action.TEMPLATE_EDIT.allowed(source) || Action.PRESET_EDIT.allowed(source);
    }

    /** Hide an empty template branch without requiring all of its child grants. */
    public static boolean canUseTemplates(CommandSourceStack source) {
        for (Action action : TEMPLATE_ACTIONS) if (action.allowed(source)) return true;
        return false;
    }

    /** Chooses the grant for the actual server-owned document kind. */
    public static Action documentAction(boolean preset) {
        return preset ? Action.PRESET_EDIT : Action.TEMPLATE_EDIT;
    }

    /** Own-session cancellation remains available after a grant is revoked; it writes no config. */
    public static boolean canSelect(CommandSourceStack source) {
        return ClockworkBossSelector.hasSession(source)
                || Action.SELECT_ACTIVATION.allowed(source) || Action.SELECT_ARENA.allowed(source);
    }
}
