package clockwork.bosses.api;

import clockwork.bosses.ClockworkBossActions;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.resources.Identifier;

/** Startup-only registry, frozen before server/editor workers read it. Addons
 * own their namespaces; duplicate IDs fail visibly instead of replacing core behavior. */
public final class BossActionRegistry {
    private static final Map<Identifier, BossActionType> TYPES = new LinkedHashMap<>();
    private static boolean coreInstalled, frozen;
    private static volatile Map<Identifier, BossActionType> snapshot = Map.of();
    private BossActionRegistry() {}
    public static final class Registrar {
        private final String owner;
        private boolean closed;
        private Registrar(String owner) { this.owner = owner; }
        public void register(BossActionType type) {
            synchronized (BossActionRegistry.class) {
                if (closed || frozen) throw new IllegalStateException("Action registration is closed");
                if (!type.id().getNamespace().equals(owner)) throw new IllegalArgumentException("Use your addon namespace: " + owner);
                if (TYPES.size() >= 64 || TYPES.putIfAbsent(type.id(), type) != null)
                    throw new IllegalArgumentException("Duplicate action ID or more than 64 actions: " + type.id());
                snapshot = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(TYPES));
            }
        }
        public void close() { synchronized (BossActionRegistry.class) { closed = true; } }
    }
    /** Core uses the same registrar/factory path as third-party addons. */
    public static synchronized Registrar registrar(String modId) {
        if (frozen) throw new IllegalStateException("Action registration is frozen");
        return new Registrar(modId);
    }
    private static synchronized void ensureCore() {
        if (coreInstalled) return;
        coreInstalled = true;
        Registrar core = registrar("clockwork_bosses");
        try { ClockworkBossActions.register(core); } finally { core.close(); }
    }
    public static BossActionType require(Identifier id) {
        ensureCore();
        BossActionType type = snapshot.get(id);
        if (type == null) throw new IllegalArgumentException("Action addon is missing or ID is unknown: " + id);
        return type;
    }
    public static Map<Identifier, BossActionType> types() { ensureCore(); return snapshot; }
    public static synchronized void freeze() {
        ensureCore();
        if (descriptors().toString().length() > 20000) throw new IllegalStateException("Action editor catalog exceeds 20,000 characters");
        frozen = true;
    }
    /** Bounded data-only catalog sent to operators. A server-only addon can
     * expose basic controls without installing its implementation on clients. */
    public static JsonObject descriptors() {
        ensureCore(); JsonObject result = new JsonObject();
        for (var type : snapshot.values()) {
            JsonObject action = new JsonObject(), fields = new JsonObject();
            action.addProperty("label", type.label()); action.addProperty("help", type.help());
            type.settings().forEach(field -> fields.add(field.key(), field.descriptor()));
            action.add("settings", fields); result.add(type.id().toString(), action);
        }
        return result;
    }
}
