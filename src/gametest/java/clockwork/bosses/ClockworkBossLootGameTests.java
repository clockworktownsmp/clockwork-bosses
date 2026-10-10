package clockwork.bosses;

import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;

import java.nio.file.Files;
import java.util.Arrays;
import java.util.UUID;

/** Development-only reward regressions use real death hooks, registry tables and item entities. */
public class ClockworkBossLootGameTests {
    private static UUID vetoedDeath, vetoedDamage;
    private static boolean vetoesRegistered;
    private static final String TABLE = "clockwork_bosses_regression:reward";

    /** Missing/disabled sections and ordinary Wardens retain their native catalyst drop. */
    @GameTest public void loot_backward_and_vanilla_scope(GameTestHelper helper) throws Exception {
        JsonObject config = config(false, "keep", "");
        config.remove("loot");
        Warden absent = boss(helper, config, "loot_absent");
        try {
            die(helper, absent, helper.getLevel().damageSources().generic());
            expect(helper, absent, 0, 1, "absent config keeps vanilla");
            helper.assertTrue(!((ClockworkBossRewardData) absent).clockworkBosses$rewardClaimed(), "absent config has no reward claim");
        } finally { clear(helper, absent); absent.discard(); }
        Warden disabled = boss(helper, config(false, "replace", TABLE), "loot_disabled");
        try { die(helper, disabled, helper.getLevel().damageSources().generic()); expect(helper, disabled, 0, 1, "disabled replacement keeps vanilla"); }
        finally { clear(helper, disabled); disabled.discard(); }
        var vanilla = helper.spawn(EntityTypes.WARDEN, new Vec3(2, 2, 2));
        vanilla.setNoAi(true);
        try { die(helper, vanilla, helper.getLevel().damageSources().generic()); expect(helper, vanilla, 0, 1, "unmarked Warden unaffected"); }
        finally { clear(helper, vanilla); vanilla.discard(); }
        helper.succeed();
    }

    /** Real environmental deaths roll once. Keep/replace affects the native table, not XP/equipment. */
    @GameTest public void loot_keep_replace_once_and_persistence(GameTestHelper helper) throws Exception {
        for (String mode : new String[]{"keep", "replace"}) {
            Warden boss = boss(helper, config(true, mode, TABLE), "loot_" + mode);
            try {
                die(helper, boss, helper.getLevel().damageSources().generic());
                expect(helper, boss, 1, mode.equals("keep") ? 1 : 0, mode + " real death");
                var data = (ClockworkBossRewardData) boss;
                helper.assertTrue(data.clockworkBosses$rewardClaimed(), "death claim set");
                helper.assertTrue(data.clockworkBosses$takeReward() == null, "death handoff releases source references");
                // Explicit duplicate service callbacks and a duplicate native die cannot issue another item.
                ClockworkBossRewards.payCommittedDeath(boss);
                boss.die(helper.getLevel().damageSources().generic());
                expect(helper, boss, 1, mode.equals("keep") ? 1 : 0, "duplicate callback");
                var output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, helper.getLevel().registryAccess());
                // saveWithoutId includes removed/dead entities, unlike Entity.save's removal gate.
                boss.saveWithoutId(output);
                Warden restored = EntityTypes.WARDEN.create(helper.getLevel(), EntitySpawnReason.LOAD);
                helper.assertTrue(restored != null, "native restore created");
                restored.load(TagValueInput.create(ProblemReporter.DISCARDING, helper.getLevel().registryAccess(), output.buildResult()));
                helper.assertTrue(((ClockworkBossRewardData) restored).clockworkBosses$rewardClaimed(), "claim survives native NBT restore");
                helper.assertTrue(((ClockworkBossRewardData) restored).clockworkBosses$takeReward() == null, "pending source never persisted");
                restored.discard();
            } finally { clear(helper, boss); boss.discard(); }
        }
        helper.succeed();
    }

    /** Fabric damage/death vetoes commit no rewards; discard and administrative /kill also pay nothing. */
    @GameTest public void loot_veto_admin_and_discard(GameTestHelper helper) throws Exception {
        if (!vetoesRegistered) {
            vetoesRegistered = true;
            ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
                if (!entity.getUUID().equals(vetoedDeath)) return true;
                // Fabric's veto skips die; a reviving protection mod restores positive health itself.
                entity.setHealth(1);
                return false;
            });
            ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> !entity.getUUID().equals(vetoedDamage));
        }
        Warden boss = boss(helper, config(true, "replace", TABLE), "loot_veto");
        try {
            vetoedDeath = boss.getUUID();
            boss.setHealth(1);
            boss.hurtServer(helper.getLevel(), helper.getLevel().damageSources().generic(), 10000);
            helper.assertTrue(boss.isAlive(), "death veto keeps boss alive");
            expect(helper, boss, 0, 0, "death veto");
            helper.assertTrue(!((ClockworkBossRewardData) boss).clockworkBosses$rewardClaimed(), "veto does not consume claim");
            vetoedDeath = null; vetoedDamage = boss.getUUID();
            boss.setInvulnerableTime(0); boss.damageCooldownTime = 0;
            helper.assertTrue(!boss.hurtServer(helper.getLevel(), helper.getLevel().damageSources().generic(), 10000), "damage veto rejects hit");
            expect(helper, boss, 0, 0, "damage veto");
            vetoedDamage = null;
            die(helper, boss, helper.getLevel().damageSources().generic());
            expect(helper, boss, 1, 0, "later legitimate death still pays once");
        } finally { vetoedDeath = null; vetoedDamage = null; clear(helper, boss); boss.discard(); }
        for (String path : new String[]{"discard", "remove", "kill"}) {
            Warden removed = boss(helper, config(true, "keep", TABLE), "loot_admin_" + path);
            try {
                if (path.equals("discard")) removed.discard();
                else if (path.equals("remove")) {
                    String name = ((ClockworkBossMarker) removed).clockworkBosses$getName();
                    helper.assertTrue(ClockworkBossManager.remove(ClockworkBossCombatGameTests.source(helper), name) == 1, "admin remove succeeds");
                } else removed.kill(helper.getLevel());
                expect(helper, removed, 0, 0, "admin " + path);
                helper.assertTrue(!((ClockworkBossRewardData) removed).clockworkBosses$rewardClaimed(), "cleanup never claims rewards");
            } finally { clear(helper, removed); removed.discard(); }
        }
        helper.succeed();
    }

    /** Native doMobLoot gate suppresses both vanilla and configured reward tables. */
    @GameTest public void loot_native_gamerule(GameTestHelper helper) throws Exception {
        var rules = helper.getLevel().getGameRules();
        boolean previous = rules.get(GameRules.MOB_DROPS);
        Warden boss = boss(helper, config(true, "keep", TABLE), "loot_rule");
        try {
            rules.set(GameRules.MOB_DROPS, false, helper.getLevel().getServer());
            die(helper, boss, helper.getLevel().damageSources().generic());
            expect(helper, boss, 0, 0, "doMobLoot false");
            helper.assertTrue(!((ClockworkBossRewardData) boss).clockworkBosses$rewardClaimed(), "native gate has no pending reward");
            helper.succeed();
        } finally { rules.set(GameRules.MOB_DROPS, previous, helper.getLevel().getServer()); clear(helper, boss); boss.discard(); }
    }

    /** An intentionally empty registered table is valid. A stale runtime key fails open for vanilla loot. */
    @GameTest public void loot_empty_and_stale_runtime_table(GameTestHelper helper) throws Exception {
        Warden empty = boss(helper, config(true, "replace", "clockwork_bosses_regression:empty"), "loot_empty");
        try {
            die(helper, empty, helper.getLevel().damageSources().generic());
            expect(helper, empty, 0, 0, "registered empty table is not mistaken for a missing table");
            helper.assertTrue(((ClockworkBossRewardData) empty).clockworkBosses$rewardClaimed(), "empty roll still claims once");
        } finally { clear(helper, empty); empty.discard(); }
        Warden stale = boss(helper, config(true, "replace", TABLE), "loot_stale");
        try {
            // Model a previously loaded key removed by a datapack reload. Normal command/editor
            // validation rejects this config; this bypass exercises the death-time safety check only.
            ClockworkBossManager.stateFor(stale).setConfig(config(true, "replace", "clockwork_bosses_regression:missing"));
            die(helper, stale, helper.getLevel().damageSources().generic());
            expect(helper, stale, 0, 1, "stale table preserves vanilla drop rather than losing it");
            helper.assertTrue(!((ClockworkBossRewardData) stale).clockworkBosses$rewardClaimed(), "unavailable table makes no custom claim");
            helper.succeed();
        } finally { clear(helper, stale); stale.discard(); }
    }

    /** Server editor transaction rejects bad refs before publication and preserves bytes/revision. */
    @GameTest public void loot_validation_editor_and_reload(GameTestHelper helper) throws Exception {
        var catalog = ClockworkBossLoot.catalog(helper.getLevel().getServer());
        helper.assertTrue(catalog.validate(config(false, "keep", "")).equals(ClockworkBossLoot.DISABLED), "disabled empty settings");
        helper.assertTrue(catalog.validate(config(true, "replace", TABLE)).enabled(), "native table exists");
        for (String id : new String[]{"Wrong:TABLE", "minecraft:bad path", "reward", "minecraft:", "clockwork_bosses_regression:missing", "clockwork_bosses_regression:incompatible"}) {
            boolean rejected = false;
            try { catalog.validate(config(true, "keep", id)); }
            catch (IllegalArgumentException expected) { rejected = true; }
            helper.assertTrue(rejected, "reject invalid/incompatible reference " + id);
        }
        String name = "regression_loot_editor";
        ClockworkBossTemplateStore.save(name, config(false, "keep", ""));
        var current = ClockworkBossTemplateStore.loadForEditor(name, false);
        JsonObject patch = new JsonObject();
        patch.addProperty("loot.enabled", true); patch.addProperty("loot.loot_table", TABLE); patch.addProperty("loot.vanilla_drops", "replace");
        var saved = ClockworkBossTemplateStore.saveFromEditor(name, false, current.revision(), patch, catalog);
        helper.assertTrue(ClockworkBossLoot.read(saved.stored()).enabled() && !ClockworkBossLoot.read(saved.stored()).keepVanilla(), "GUI save persisted valid settings");
        byte[] original = Files.readAllBytes(ClockworkBossTemplateStore.pathFor(name));
        for (String id : new String[]{"Clockwork:bad", "clockwork_bosses_regression:missing"}) {
            JsonObject invalid = new JsonObject(); invalid.addProperty("loot.loot_table", id);
            boolean rejected = false;
            try { ClockworkBossTemplateStore.saveFromEditor(name, false, saved.revision(), invalid, catalog); }
            catch (Exception expected) { rejected = true; }
            helper.assertTrue(rejected && Arrays.equals(original, Files.readAllBytes(ClockworkBossTemplateStore.pathFor(name))), "invalid save preserves exact original bytes");
            helper.assertTrue(saved.revision().equals(ClockworkBossTemplateStore.loadForEditor(name, false).revision()), "invalid save preserves revision");
        }
        // The same validated transaction supports preset editing, never deleting a preset.
        var preset = ClockworkBossTemplateStore.loadForEditor("1_player", true);
        JsonObject presetPatch = new JsonObject(); presetPatch.addProperty("loot.loot_table", TABLE);
        var presetSaved = ClockworkBossTemplateStore.saveFromEditor("1_player", true, preset.revision(), presetPatch, catalog);
        helper.assertTrue(ClockworkBossLoot.read(presetSaved.stored()).table().equals(ClockworkBossLoot.parseTable(TABLE)), "preset save supports loot");
        // Restore the disposable preset document so subsequent tests see the normal balance preset.
        JsonObject restore = new JsonObject(); restore.addProperty("loot.loot_table", "");
        ClockworkBossTemplateStore.saveFromEditor("1_player", true, presetSaved.revision(), restore, catalog);
        Warden boss = boss(helper, config(true, "replace", TABLE), "loot_reload");
        try {
            var state = ClockworkBossManager.stateFor(boss);
            JsonObject changed = state.config().deepCopy(); changed.getAsJsonObject("loot").addProperty("vanilla_drops", "keep");
            ClockworkBossTemplateStore.save(state.templateName(), changed);
            ClockworkBossManager.reload(ClockworkBossCombatGameTests.source(helper));
            helper.assertTrue(state.loot().keepVanilla(), "valid reload publishes reward settings");
            var valid = state.loot(); changed.getAsJsonObject("loot").addProperty("loot_table", "clockwork_bosses_regression:missing");
            ClockworkBossTemplateStore.save(state.templateName(), changed);
            ClockworkBossManager.reload(ClockworkBossCombatGameTests.source(helper));
            helper.assertTrue(state.loot().equals(valid), "missing-table reload retains live valid settings");
            helper.assertTrue(ClockworkBossManager.spawn(ClockworkBossCombatGameTests.source(helper), state.templateName(), "loot_invalid_spawn") == 0, "missing-table spawn rejected");
            changed.getAsJsonObject("loot").addProperty("loot_table", TABLE);
            ClockworkBossTemplateStore.save(state.templateName(), changed);
            helper.succeed();
        } finally { clear(helper, boss); boss.discard(); }
    }

    /** Separate native contexts distinguish two players and two bosses; environmental kills have no player rule. */
    @GameTest public void loot_multiple_bosses_and_killer_context(GameTestHelper helper) throws Exception {
        var server = helper.getLevel().getServer();
        var board = server.getScoreboard();
        var objective = board.getObjective("cw_loot_test");
        if (objective == null) objective = board.addObjective("cw_loot_test", ObjectiveCriteria.DUMMY, Component.literal("Loot regression"), ObjectiveCriteria.RenderType.INTEGER, false, null);
        Warden first = boss(helper, config(true, "replace", "clockwork_bosses_regression:players"), "loot_player_one");
        Warden second = boss(helper, config(true, "replace", "clockwork_bosses_regression:players"), "loot_player_two");
        // Keep embedded players away from the drops to prevent native pickup during assertions.
        var one = new ClockworkBossCombatGameTests.PlayerFixture(helper, first.position().add(30, 0, 0));
        var two = new ClockworkBossCombatGameTests.PlayerFixture(helper, first.position().add(32, 0, 0));
        board.getOrCreatePlayerScore(one.player, objective).set(1);
        board.getOrCreatePlayerScore(two.player, objective).set(2);
        try {
            die(helper, first, helper.getLevel().damageSources().playerAttack(one.player));
            expect(helper, first, 1, 0, "first player's attacker context");
            clear(helper, first);
            helper.assertTrue(count(helper, first, Items.DIAMOND) == 0, "first loot cleanup before second kill");
            die(helper, second, helper.getLevel().damageSources().playerAttack(two.player));
            helper.assertTrue(count(helper, second, Items.EMERALD) == 1 && count(helper, second, Items.DIAMOND) == 0, "second player's direct-attacker context is independent: emeralds=" + count(helper, second, Items.EMERALD) + " diamonds=" + count(helper, second, Items.DIAMOND));
            clear(helper, second);
            Warden environment = boss(helper, config(true, "replace", "clockwork_bosses_regression:players"), "loot_player_none");
            try {
                die(helper, environment, helper.getLevel().damageSources().generic());
                expect(helper, environment, 0, 0, "player-only native table conditions exclude environmental kill");
                helper.assertTrue(count(helper, environment, Items.EMERALD) == 0, "no inherited killer from another encounter");
            } finally { clear(helper, environment); environment.discard(); }
            helper.succeed();
        } finally { clear(helper, first); clear(helper, second); first.discard(); second.discard(); one.close(); two.close(); }
    }

    private static JsonObject config(boolean enabled, String mode, String table) {
        var config = ClockworkBossCombatGameTests.config(1000);
        var loot = config.getAsJsonObject("loot"); loot.addProperty("source", "table"); loot.addProperty("enabled", enabled); loot.addProperty("vanilla_drops", mode); loot.addProperty("loot_table", table);
        return config;
    }

    /** Template tables use native pools, guaranteed/zero chances, inclusive count ranges and native stack splitting. */
    @GameTest public void loot_entries_native_items_and_exactly_once(GameTestHelper helper) throws Exception {
        for (String mode : new String[]{"keep", "replace"}) {
            var config = entryConfig(mode);
            var entries = config.getAsJsonObject("loot").getAsJsonObject("entries");
            entries.add("diamonds", entry("minecraft:diamond", 3, 3, 1));
            entries.add("never", entry("minecraft:emerald", 1, 1, 0));
            entries.add("range", entry("minecraft:stick", 4, 9, 1));
            entries.add("unstackable", entry("minecraft:iron_sword", 3, 3, 1));
            Warden boss = boss(helper, config, "entries_" + mode);
            try {
                die(helper, boss, helper.getLevel().damageSources().generic());
                expect(helper, boss, 3, mode.equals("keep") ? 1 : 0, "entry " + mode);
                helper.assertTrue(count(helper, boss, Items.EMERALD) == 0, "zero chance never drops");
                int sticks = count(helper, boss, Items.STICK);
                helper.assertTrue(sticks >= 4 && sticks <= 9, "inclusive native quantity range");
                helper.assertTrue(count(helper, boss, Items.IRON_SWORD) == 3, "unstackable count splits natively");
                helper.assertTrue(helper.getLevel().getEntitiesOfClass(ItemEntity.class, boss.getBoundingBox().inflate(3)).stream()
                        .filter(item -> item.getItem().is(Items.IRON_SWORD)).allMatch(item -> item.getItem().getCount() == 1), "native sword stacks remain size one");
                ClockworkBossRewards.payCommittedDeath(boss); boss.die(helper.getLevel().damageSources().generic());
                expect(helper, boss, 3, mode.equals("keep") ? 1 : 0, "entry duplicate callback does not pay again");
            } finally { clear(helper, boss); boss.discard(); }
        }
        // All registered mod items use the same ID boundary. Native-only runs may have none;
        // supplied-pack runs exercise a real mod item without depending on a particular mod name.
        var modItem = net.minecraft.core.registries.BuiltInRegistries.ITEM.keySet().stream()
                .filter(id -> !id.getNamespace().equals("minecraft")).findFirst();
        if (modItem.isPresent()) {
            var config = entryConfig("replace");
            config.getAsJsonObject("loot").getAsJsonObject("entries").add("mod_item", entry(modItem.get().toString(), 1, 1, 1));
            Warden boss = boss(helper, config, "entry_mod_item");
            try {
                die(helper, boss, helper.getLevel().damageSources().generic());
                helper.assertTrue(count(helper, boss, net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(modItem.get())) == 1, "registered mod item drops");
            } finally { clear(helper, boss); boss.discard(); }
        }
        helper.succeed();
    }

    /** Explicit empty/disabled entries and native administrative/gamerule gates retain their policies. */
    @GameTest public void loot_entries_empty_and_native_gates(GameTestHelper helper) throws Exception {
        Warden empty = boss(helper, entryConfig("replace"), "entry_empty");
        try {
            die(helper, empty, helper.getLevel().damageSources().generic()); expect(helper, empty, 0, 0, "empty replacement table");
            helper.assertTrue(((ClockworkBossRewardData) empty).clockworkBosses$rewardClaimed(), "empty table commits claim");
        } finally { clear(helper, empty); empty.discard(); }
        var config = entryConfig("replace"); config.getAsJsonObject("loot").getAsJsonObject("entries").add("reward", entry("minecraft:diamond", 1, 1, 1));
        for (String action : new String[]{"disabled", "kill", "gamerule"}) {
            var changed = config.deepCopy(); if (action.equals("disabled")) changed.getAsJsonObject("loot").addProperty("enabled", false);
            Warden boss = boss(helper, changed, "entry_gate_" + action);
            var rules = helper.getLevel().getGameRules(); boolean old = rules.get(GameRules.MOB_DROPS);
            try {
                if (action.equals("gamerule")) rules.set(GameRules.MOB_DROPS, false, helper.getLevel().getServer());
                if (action.equals("kill")) boss.kill(helper.getLevel()); else die(helper, boss, helper.getLevel().damageSources().generic());
                expect(helper, boss, 0, action.equals("disabled") ? 1 : 0, "entry gate " + action);
                helper.assertTrue(!((ClockworkBossRewardData) boss).clockworkBosses$rewardClaimed(), "gate never claims custom payout");
            } finally { rules.set(GameRules.MOB_DROPS, old, helper.getLevel().getServer()); clear(helper, boss); boss.discard(); }
        }
        helper.succeed();
    }

    /** GUI patches create/edit/reorder/delete definitions without erasing extensions, table IDs or other settings.
     * Bad registry IDs, count pairs, injection attempts and stale saves preserve the original bytes and revision. */
    @GameTest public void loot_entries_editor_transactions(GameTestHelper helper) throws Exception {
        var catalog = ClockworkBossLoot.catalog(helper.getLevel().getServer());
        var original = config(false, "keep", TABLE);
        original.addProperty("extension", "keep me");
        var entries = original.getAsJsonObject("loot").getAsJsonObject("entries");
        var existing = entry("minecraft:diamond", 2, 2, 1); existing.add("extension", com.google.gson.JsonParser.parseString("{\"nested\": [1,2]}"));
        entries.add("existing", existing);
        String name = "regression_entry_editor";
        ClockworkBossTemplateStore.save(name, original);
        var current = ClockworkBossTemplateStore.loadForEditor(name, false);
        JsonObject patch = new JsonObject(); patch.add("_loot_entry_order", com.google.gson.JsonParser.parseString("[\"new_reward\",\"existing\"]"));
        patch.addProperty("loot.source", "entries"); patch.addProperty("loot.enabled", true);
        patch.addProperty("loot.entries.new_reward.item", "minecraft:emerald"); patch.addProperty("loot.entries.new_reward.min_count", 4); patch.addProperty("loot.entries.new_reward.max_count", 6);
        patch.addProperty("loot.entries.new_reward.chance", .25);
        var saved = ClockworkBossTemplateStore.saveFromEditor(name, false, current.revision(), patch, catalog);
        helper.assertTrue(saved.stored().get("extension").equals(original.get("extension")), "unrelated data retained");
        helper.assertTrue(saved.stored().getAsJsonObject("loot").getAsJsonObject("entries").get("existing").equals(existing), "unchanged reward extension retained");
        helper.assertTrue(saved.stored().getAsJsonObject("loot").get("loot_table").getAsString().equals(TABLE), "table ID retained while editing entries");
        helper.assertTrue(ClockworkBossLoot.read(saved.stored()).entries().getFirst().key().equals("new_reward"), "GUI ordering persisted");
        byte[] before = Files.readAllBytes(ClockworkBossTemplateStore.pathFor(name));
        for (String field : new String[]{"item", "min_count", "max_count", "chance", "type", "extension"}) {
            var bad = new JsonObject();
            switch (field) {
                case "item" -> bad.addProperty("loot.entries.existing.item", "missing:no_such_item");
                case "min_count" -> bad.addProperty("loot.entries.existing.min_count", 3); // Above max=2.
                case "max_count" -> bad.addProperty("loot.entries.existing.max_count", 65);
                case "chance" -> bad.addProperty("loot.entries.existing.chance", -1);
                case "type" -> bad.addProperty("loot.entries.existing.type", "command");
                default -> bad.add("loot.entries.existing.extension", com.google.gson.JsonParser.parseString("{\"execute\": \"no\"}"));
            }
            boolean rejected = false;
            try { ClockworkBossTemplateStore.saveFromEditor(name, false, saved.revision(), bad, catalog); }
            catch (Exception expected) { rejected = true; }
            helper.assertTrue(rejected && Arrays.equals(before, Files.readAllBytes(ClockworkBossTemplateStore.pathFor(name))), "invalid entry preserves bytes: " + field);
        }
        boolean staleRejected = false;
        try { ClockworkBossTemplateStore.saveFromEditor(name, false, current.revision(), patch, catalog); }
        catch (Exception expected) { staleRejected = true; }
        helper.assertTrue(staleRejected && Arrays.equals(before, Files.readAllBytes(ClockworkBossTemplateStore.pathFor(name))), "stale save rejected");
        var mode = new JsonObject(); mode.addProperty("loot.source", "table");
        var table = ClockworkBossTemplateStore.saveFromEditor(name, false, saved.revision(), mode, catalog);
        helper.assertTrue(table.stored().getAsJsonObject("loot").get("entries").equals(saved.stored().getAsJsonObject("loot").get("entries")), "switch back preserves all entries");
        var remove = new JsonObject(); remove.add("_loot_entry_order", com.google.gson.JsonParser.parseString("[\"existing\"]"));
        var removed = ClockworkBossTemplateStore.saveFromEditor(name, false, table.revision(), remove, catalog);
        helper.assertTrue(removed.stored().getAsJsonObject("loot").getAsJsonObject("entries").size() == 1, "remove selected reward only");
        var preset = ClockworkBossTemplateStore.loadForEditor("1_player", true);
        var add = new JsonObject(); add.add("_loot_entry_order", com.google.gson.JsonParser.parseString("[\"reward\"]")); add.addProperty("loot.entries.reward.item", "minecraft:gold_ingot");
        var presetSaved = ClockworkBossTemplateStore.saveFromEditor("1_player", true, preset.revision(), add, catalog);
        helper.assertTrue(presetSaved.stored().getAsJsonObject("loot").getAsJsonObject("entries").getAsJsonObject("reward").get("item").getAsString().equals("minecraft:gold_ingot"), "presets use same entry editor");
        var restore = new JsonObject(); restore.add("_loot_entry_order", new com.google.gson.JsonArray());
        ClockworkBossTemplateStore.saveFromEditor("1_player", true, presetSaved.revision(), restore, catalog);
        helper.succeed();
    }

    /** Legacy documents need no rewrite; only an explicitly edited loot setting is materialized. */
    @GameTest public void loot_entries_legacy_preservation_and_reload(GameTestHelper helper) throws Exception {
        var config = config(true, "replace", TABLE); config.getAsJsonObject("loot").remove("source"); config.getAsJsonObject("loot").remove("entries");
        String name = "regression_legacy_loot"; ClockworkBossTemplateStore.save(name, config);
        var current = ClockworkBossTemplateStore.loadForEditor(name, false);
        var other = new JsonObject(); other.addProperty("scale", 1.25);
        var saved = ClockworkBossTemplateStore.saveFromEditor(name, false, current.revision(), other, ClockworkBossLoot.catalog(helper.getLevel().getServer()));
        helper.assertTrue(!saved.stored().getAsJsonObject("loot").has("source") && !saved.stored().getAsJsonObject("loot").has("entries"), "unrelated save does not rewrite old loot");
        helper.assertTrue(ClockworkBossLoot.read(saved.stored()).source().equals("table"), "old semantics preserved");
        Warden boss = boss(helper, config, "entry_reload");
        try {
            var state = ClockworkBossManager.stateFor(boss);
            var changed = state.config().deepCopy(); changed.getAsJsonObject("loot").addProperty("source", "entries");
            var rewards = new JsonObject(); rewards.add("reward", entry("minecraft:diamond", 5, 5, 1)); changed.getAsJsonObject("loot").add("entries", rewards);
            ClockworkBossTemplateStore.save(state.templateName(), changed); ClockworkBossManager.reload(ClockworkBossCombatGameTests.source(helper));
            helper.assertTrue(state.loot().source().equals("entries"), "valid inline reload applies");
            var valid = state.loot(); rewards.getAsJsonObject("reward").addProperty("item", "missing:item");
            ClockworkBossTemplateStore.save(state.templateName(), changed); ClockworkBossManager.reload(ClockworkBossCombatGameTests.source(helper));
            helper.assertTrue(state.loot().equals(valid), "invalid item reload keeps previous live settings");
            die(helper, boss, helper.getLevel().damageSources().generic()); expect(helper, boss, 5, 0, "previous valid entries still drop after rejected reload");
            helper.succeed();
        } finally { clear(helper, boss); boss.discard(); }
    }

    private static JsonObject entryConfig(String mode) {
        var config = config(true, mode, ""); config.getAsJsonObject("loot").addProperty("source", "entries"); return config;
    }
    private static JsonObject entry(String item, int min, int max, double chance) {
        var entry = ClockworkBossLootEntries.newEntry(); entry.addProperty("item", item);
        entry.addProperty("min_count", min); entry.addProperty("max_count", max); entry.addProperty("chance", chance); return entry;
    }
    private static Warden boss(GameTestHelper helper, JsonObject config, String suffix) throws Exception {
        Warden boss = ClockworkBossCombatGameTests.boss(helper, config, suffix);
        boss.setNoAi(true); boss.setNoGravity(true);
        return boss;
    }
    private static void die(GameTestHelper helper, Warden boss, DamageSource source) {
        boss.setInvulnerableTime(0); boss.damageCooldownTime = 0; boss.setHealth(1);
        helper.assertTrue(boss.hurtServer(helper.getLevel(), source, 10000) && !boss.isAlive(), "native lethal damage accepted");
    }
    private static int count(GameTestHelper helper, Warden boss, Item item) {
        return helper.getLevel().getEntitiesOfClass(ItemEntity.class, boss.getBoundingBox().inflate(3))
                .stream().filter(entity -> !entity.isRemoved() && entity.getItem().is(item)).mapToInt(entity -> entity.getItem().getCount()).sum();
    }
    private static void expect(GameTestHelper helper, Warden boss, int diamonds, int catalysts, String reason) {
        helper.assertTrue(count(helper, boss, Items.DIAMOND) == diamonds && count(helper, boss, Items.SCULK_CATALYST) == catalysts,
                reason + ": diamonds=" + count(helper, boss, Items.DIAMOND) + " catalysts=" + count(helper, boss, Items.SCULK_CATALYST));
    }
    private static void clear(GameTestHelper helper, Warden boss) {
        helper.getLevel().getEntitiesOfClass(ItemEntity.class, boss.getBoundingBox().inflate(3)).forEach(ItemEntity::discard);
    }
}
