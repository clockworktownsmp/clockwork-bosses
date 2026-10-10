package clockwork.bosses;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.functions.SetItemCountFunction;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.predicates.LootItemRandomChanceCondition;
import net.minecraft.world.level.storage.loot.providers.number.ints.ConstantValue;
import net.minecraft.world.level.storage.loot.providers.number.ints.ContextIntProvider;
import net.minecraft.world.level.storage.loot.providers.number.ints.UniformGenerator;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** A bounded, template-owned table definition. New reward kinds can extend this boundary;
 * it never changes datapacks or installs global registry entries. Unknown JSON fields stay on disk. */
public final class ClockworkBossLootEntries {
    public static final int MAX_ENTRIES = 32;
    public static final int MAX_COUNT = 64;
    private ClockworkBossLootEntries() {}

    /** Immutable parsed data keeps the file worker independent of live Minecraft registries. */
    public record Entry(String key, Identifier item, int minimum, int maximum, float chance) {}

    public static JsonObject newEntry() {
        var entry = new JsonObject();
        entry.addProperty("type", "item");
        entry.addProperty("item", "minecraft:diamond");
        entry.addProperty("min_count", 1);
        entry.addProperty("max_count", 1);
        entry.addProperty("chance", 1.0);
        return entry;
    }

    public static void validateKey(String key) {
        if (!key.matches("[a-z0-9_-]{1,48}"))
            throw new IllegalArgumentException("Reward names need 1–48 lowercase letters, numbers, underscores or hyphens");
    }

    /** Explicit item IDs avoid both accidental default namespace and the air fallback for unknown items. */
    public static Identifier parseItem(String text) {
        if (text.length() > 256 || !text.matches("[a-z0-9_.-]+:[a-z0-9/._-]+"))
            throw new IllegalArgumentException("Use an item ID such as minecraft:diamond (lowercase, no spaces)");
        var id = Identifier.tryParse(text);
        if (id == null || text.equals("minecraft:air")) throw new IllegalArgumentException("Use a non-air item ID");
        return id;
    }

    /** Parse all definitions, including hidden ones, without coercing strings, fractional counts or nonfinite chance. */
    public static List<Entry> read(JsonObject loot) {
        if (!loot.has("entries")) return List.of();
        JsonElement raw = loot.get("entries");
        if (!raw.isJsonObject() || raw.getAsJsonObject().size() > MAX_ENTRIES)
            throw new IllegalArgumentException("Use 0–32 reward entries");
        var entries = new ArrayList<Entry>();
        for (var field : raw.getAsJsonObject().entrySet()) {
            validateKey(field.getKey());
            if (!field.getValue().isJsonObject()) throw new IllegalArgumentException("Reward settings must be objects");
            var entry = field.getValue().getAsJsonObject();
            if (!text(entry, "type").equals("item")) throw new IllegalArgumentException("Supported reward type: item");
            var item = parseItem(text(entry, "item"));
            int min = count(entry, "min_count"), max = count(entry, "max_count");
            if (min > max) throw new IllegalArgumentException(field.getKey() + ": Maximum quantity must be at least Minimum quantity");
            var chance = number(entry, "chance");
            if (chance.compareTo(BigDecimal.ZERO) < 0 || chance.compareTo(BigDecimal.ONE) > 0)
                throw new IllegalArgumentException("Reward chance must be 0–1 (0–100% in the editor)");
            entries.add(new Entry(field.getKey(), item, min, max, chance.floatValue()));
        }
        return List.copyOf(entries);
    }

    private static String text(JsonObject entry, String key) {
        var value = entry.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException("Reward " + key + " must be text");
        return value.getAsString();
    }

    private static BigDecimal number(JsonObject entry, String key) {
        var value = entry.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException("Reward " + key + " must be a number");
        try { return ClockworkBossNumbers.decimal(value.getAsString()); }
        catch (NumberFormatException invalid) { throw new IllegalArgumentException("Reward " + key + " needs a finite number with a reasonable exponent/precision"); }
    }

    private static int count(JsonObject entry, String key) {
        var value = number(entry, key);
        if (value.stripTrailingZeros().scale() > 0 || value.compareTo(BigDecimal.ONE) < 0
                || value.compareTo(BigDecimal.valueOf(MAX_COUNT)) > 0)
            throw new IllegalArgumentException("Reward quantity must be a whole number from 1 to 64");
        return value.intValueExact();
    }

    public static void validateItems(List<Entry> entries, Set<Identifier> registeredItems) {
        for (var entry : entries) if (!registeredItems.contains(entry.item()))
            throw new IllegalArgumentException("Item not found: " + entry.item());
    }

    /** Compile once at payout (at most 32 pools), then use native chance/count providers and stack splitting.
     * Each entry rolls independently once; ordering is for readability, not weighted selection. */
    public static LootTable compile(List<Entry> entries) {
        var table = LootTable.lootTable().setParamSet(LootContextParamSets.ENTITY);
        for (var entry : entries) {
            if (!BuiltInRegistries.ITEM.containsKey(entry.item())) throw new IllegalArgumentException("Item not found: " + entry.item());
            Holder<ContextIntProvider> count = Holder.direct(new UniformGenerator(Holder.direct(new ConstantValue(entry.minimum())),
                    Holder.direct(new ConstantValue(entry.maximum()))));
            table.withPool(LootPool.lootPool().setRolls(Holder.direct(new ConstantValue(1)))
                    .when(LootItemRandomChanceCondition.randomChance(entry.chance()))
                    .add(LootItem.lootTableItem(BuiltInRegistries.ITEM.getValue(entry.item()))
                            .apply(SetItemCountFunction.setCount(count))));
        }
        return table.build();
    }

    /** An unregistered table cannot use the public key overload. Match its native 26.3 entity context,
     * including optional attacker/player luck, then let LootTable and Entity handle generation/spawning. */
    public static void drop(List<Entry> entries, Warden boss, ServerLevel level, DamageSource source, boolean killedByPlayer) {
        // Empty enabled lists intentionally give no configured items. Avoid building a native table/context.
        if (entries.isEmpty()) return;
        var params = new LootParams.Builder(level)
                .withParameter(LootContextParams.THIS_ENTITY, boss)
                .withParameter(LootContextParams.ORIGIN, boss.position())
                .withParameter(LootContextParams.DAMAGE_SOURCE, source)
                .withOptionalParameter(LootContextParams.ATTACKING_ENTITY, source.getEntity())
                .withOptionalParameter(LootContextParams.DIRECT_ATTACKING_ENTITY, source.getDirectEntity());
        var player = boss.getLastHurtByPlayer();
        if (killedByPlayer && player != null) params.withParameter(LootContextParams.LAST_DAMAGE_PLAYER, player).withLuck(player.getLuck());
        compile(entries).getRandomItems(params.create(LootContextParamSets.ENTITY), boss.getLootTableSeed(),
                stack -> boss.spawnAtLocation(level, stack));
    }
}
