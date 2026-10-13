package clockwork.bosses;

import com.google.gson.JsonObject;
import net.minecraft.core.registries.Registries;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;

import java.util.HashSet;
import java.util.Set;
import java.util.List;

/** Template-wide item rewards. Missing settings keep vanilla behavior; no phase or tick work is needed. */
public record ClockworkBossLoot(boolean enabled, ResourceKey<LootTable> table, boolean keepVanilla,
                                String source, List<ClockworkBossLootEntries.Entry> entries) {
    public static final String DEFAULT_SOURCE = "table";
    public static final ClockworkBossLoot DISABLED = new ClockworkBossLoot(false, null, true, DEFAULT_SOURCE, List.of());

    /** Parse strictly, so a typo cannot silently turn replacement into extra drops. */
    public static ClockworkBossLoot read(JsonObject config) {
        if (!config.has("loot") || config.get("loot").isJsonNull()) return DISABLED;
        if (!config.get("loot").isJsonObject()) throw new IllegalArgumentException("loot must be an object");
        JsonObject section = config.getAsJsonObject("loot");
        boolean enabled = false;
        if (section.has("enabled")) {
            var value = section.get("enabled");
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean())
                throw new IllegalArgumentException("loot.enabled must be true or false");
            enabled = value.getAsBoolean();
        }
        String mode = text(section, "vanilla_drops", "keep");
        if (!mode.equals("keep") && !mode.equals("replace"))
            throw new IllegalArgumentException("loot.vanilla_drops must be keep or replace");
        String id = text(section, "loot_table", "");
        ResourceKey<LootTable> table = id.isEmpty() ? null : parseTable(id);
        // Omitted source means the RC7 datapack behavior, even if a file has a nonempty table ID.
        String source = text(section, "source", DEFAULT_SOURCE);
        if (!source.equals("table") && !source.equals("entries")) throw new IllegalArgumentException("loot.source must be entries or table");
        var entries = ClockworkBossLootEntries.read(section);
        if (enabled && source.equals("table") && table == null) throw new IllegalArgumentException("Set loot.loot_table before enabling loot");
        return new ClockworkBossLoot(enabled, table, mode.equals("keep"), source, entries);
    }

    /** Require an explicit namespace rather than silently assuming minecraft for an operator's typo. */
    public static ResourceKey<LootTable> parseTable(String text) {
        if (text.length() > 256 || !text.matches("[a-z0-9_.-]+:[a-z0-9/._-]+"))
            throw new IllegalArgumentException("Use a loot-table ID such as minecraft:entities/warden (lowercase, no spaces)");
        Identifier id = Identifier.tryParse(text);
        if (id == null) throw new IllegalArgumentException("Invalid loot-table ID: " + text);
        return ResourceKey.create(Registries.LOOT_TABLE, id);
    }

    private static String text(JsonObject object, String key, String fallback) {
        if (!object.has(key)) return fallback;
        var value = object.get(key);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException("loot." + key + " must be text");
        return value.getAsString();
    }

    /** Snapshot registry metadata on the server thread. The editor's file worker only reads immutable sets. */
    public static Catalog catalog(MinecraftServer server) {
        Set<ResourceKey<LootTable>> present = new HashSet<>(), compatible = new HashSet<>();
        server.reloadableRegistries().lookup().lookupOrThrow(Registries.LOOT_TABLE).listElements().forEach(holder -> {
            present.add(holder.key());
            // Environmental deaths have no optional player/tool/block parameters. Require only parameters
            // native entity death always supplies, allowing entity and compatible generic tables safely.
            if (LootContextParamSets.ENTITY.required().containsAll(holder.value().getParamSet().required()))
                compatible.add(holder.key());
        });
        return new Catalog(Set.copyOf(present), Set.copyOf(compatible), Set.copyOf(BuiltInRegistries.ITEM.keySet()));
    }

    /** Commands/lifecycle already run on the server thread: resolve only this template's single table. */
    public static ClockworkBossLoot validate(MinecraftServer server, JsonObject config) {
        ClockworkBossLoot loot = read(config);
        // Check only configured items on lifecycle paths; do not scan every registry key per boss.
        for (var entry : loot.entries()) if (!BuiltInRegistries.ITEM.containsKey(entry.item()))
            throw new IllegalArgumentException("Item not found: " + entry.item());
        if (loot.enabled() && loot.source().equals("table")) {
            var holder = server.reloadableRegistries().lookup().lookupOrThrow(Registries.LOOT_TABLE).get(loot.table());
            if (holder.isEmpty()) throw new IllegalArgumentException("Loot table not found: " + loot.table().identifier());
            if (!LootContextParamSets.ENTITY.required().containsAll(holder.get().value().getParamSet().required()))
                throw new IllegalArgumentException("Loot table needs parameters unavailable on entity death: " + loot.table().identifier());
        }
        return loot;
    }

    /** No registry object escapes to an I/O worker; validation finishes before a template is written. */
    public record Catalog(Set<ResourceKey<LootTable>> present, Set<ResourceKey<LootTable>> compatible, Set<Identifier> items) {
        public ClockworkBossLoot validate(JsonObject config) {
            ClockworkBossLoot loot = read(config);
            ClockworkBossLootEntries.validateItems(loot.entries(), items);
            if (loot.enabled() && loot.source().equals("table")) {
                if (!present.contains(loot.table())) throw new IllegalArgumentException("Loot table not found: " + loot.table().identifier());
                if (!compatible.contains(loot.table())) throw new IllegalArgumentException("Loot table needs parameters unavailable on entity death: " + loot.table().identifier());
            }
            return loot;
        }
    }

    /** Runtime lookup also handles a datapack removing a table after the template was loaded. */
    public boolean available(MinecraftServer server) {
        if (!enabled) return false;
        if (source.equals("entries")) return entries.stream().allMatch(entry -> BuiltInRegistries.ITEM.containsKey(entry.item()));
        var holder = server.reloadableRegistries().lookup().lookupOrThrow(Registries.LOOT_TABLE).get(table);
        return holder.isPresent() && LootContextParamSets.ENTITY.required().containsAll(holder.get().value().getParamSet().required());
    }

    /** Stable diagnostic label; never dereference a null table in entry-editor mode. */
    public String description() { return source.equals("entries") ? "template reward entries" : String.valueOf(table == null ? "unset table" : table.identifier()); }
}
