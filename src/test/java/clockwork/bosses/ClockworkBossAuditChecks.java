package clockwork.bosses;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Random;
import java.util.UUID;

/** Dependency-free regression checks run by Gradle auditChecks; no game/world is modified. */
public final class ClockworkBossAuditChecks {
    private static int assertions;

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--sound-distance-only")) {
            System.out.println("Clockwork Boss sound-distance checks passed: " + ClockworkBossSoundDistanceChecks.run() + " assertions.");
            return;
        }
        checkTemplates();
        checkLootSchema();
        checkLootEntries();
        checkDecimalBounds();
        checkPhasesAndSettings();
        checkHealthAndChargeSnapshots();
        checkTargetingAndVisualTiming();
        checkMediaBoundaries();
        assertions += ClockworkBossSoundDistanceChecks.run();
        assertions += ClockworkBossTemplateSchemaChecks.run();
        assertions += ClockworkBossSharedMusicChecks.run();
        if (args.length > 0) Files.writeString(Path.of(args[0]),
                ClockworkBossTemplateDocument.write(ClockworkBossTemplateDocument.defaults()));
        System.out.println("Clockwork Boss audit checks passed: " + assertions + " assertions.");
    }

    /** Tiny exponent strings must never expand into enormous text or bypass exact numeric bounds. */
    private static void checkDecimalBounds() {
        for (String text : new String[]{"1e-2000000000", "0e2000000000", "1e2000000000", "1e-65", "1e65", "1".repeat(129), "NaN", "Infinity"})
            rejects(() -> ClockworkBossNumbers.decimal(text), "bounded decimal notation");
        check(ClockworkBossNumbers.decimal("2.5e-1").doubleValue() == .25, "ordinary scientific notation accepted");
        check(ClockworkBossNumbers.display(new java.math.BigDecimal("100.00")).equals("100"), "normal display remains plain");
        check(ClockworkBossNumbers.display(new java.math.BigDecimal("1e-2000000000")).length() < 32, "legacy extreme exponent displayed compactly");
        check(ClockworkBossNumbers.display(new java.math.BigDecimal("0e2000000000")).equals("0"), "legacy scaled zero displayed safely");
        for (String text : new String[]{"1.00000000000000000001", "-0.00000000000000000001", "1e-2000000000"})
            rejects(() -> ClockworkBossEditorSchema.parse("targeting.switch_chance", text, new com.google.gson.JsonPrimitive(.25)), "probability exact bounds");
        var config = ClockworkBossTemplateDocument.defaults();
        var entries = config.getAsJsonObject("loot").getAsJsonObject("entries");
        var entry = ClockworkBossLootEntries.newEntry(); entries.add("reward_1", entry);
        for (String text : new String[]{"1e-2000000000", "0e2000000000", "1e65"}) {
            entry.add("chance", JsonParser.parseString(text));
            rejects(() -> ClockworkBossLoot.read(config), "loot decimal exponent bound");
        }
    }

    /** Structural patches stay bounded and preserve unrelated fields and legacy table references. */
    private static void checkLootEntries() throws Exception {
        var stored = ClockworkBossTemplateDocument.defaults();
        stored.getAsJsonObject("loot").remove("source");
        stored.getAsJsonObject("loot").addProperty("loot_table", "minecraft:entities/warden");
        stored.getAsJsonObject("loot").addProperty("enabled", true);
        var old = ClockworkBossEditorSchema.editable(stored);
        check(ClockworkBossLoot.read(old).source().equals("table"), "RC7 source remains datapack when opening");
        var candidate = old.deepCopy();
        candidate.getAsJsonObject("loot").addProperty("source", "entries");
        var entries = candidate.getAsJsonObject("loot").getAsJsonObject("entries");
        var first = ClockworkBossLootEntries.newEntry();
        var extension = JsonParser.parseString("{\"future\": [1,2,3]}"); first.add("extension", extension);
        entries.add("reward_1", first); entries.add("reward_2", ClockworkBossLootEntries.newEntry());
        var patch = ClockworkBossEditorSchema.orderPatch(old, candidate);
        check(patch.has("_loot_entry_order"), "new entries use dedicated structural operation");
        var expected = old.deepCopy(); ClockworkBossEditorSchema.applyOrders(expected, patch);
        // The generic scalar validator rejects arbitrary new extension objects from a client.
        rejects(() -> ClockworkBossEditorSchema.validate(expected, candidate), "client cannot insert extension structure");
        entries.getAsJsonObject("reward_1").remove("extension");
        ClockworkBossEditorSchema.validate(expected, candidate);
        check(ClockworkBossLoot.read(candidate).entries().size() == 2, "item definitions parsed");
        check(candidate.getAsJsonObject("loot").get("loot_table").getAsString().equals("minecraft:entities/warden"), "mode switch retains datapack ID");
        entries.getAsJsonObject("reward_1").add("extension", extension);
        var reorder = new JsonObject(); reorder.add("_loot_entry_order", JsonParser.parseString("[\"reward_2\",\"reward_1\"]"));
        ClockworkBossEditorSchema.applyOrders(candidate, reorder);
        check(candidate.getAsJsonObject("loot").getAsJsonObject("entries").getAsJsonObject("reward_1").get("extension").equals(extension), "reorder preserves unknown existing data");
        var field = ClockworkBossEditorSchema.field("loot.entries.reward_1.min_count", new com.google.gson.JsonPrimitive(1));
        check(field.integer() && field.min() == 1 && field.max() == 64 && !field.nullable(), "quantity field metadata");
        check(!ClockworkBossTemplateDocument.description("loot.entries.reward_1.chance").isBlank(), "reward wildcard help available");
        for (String order : new String[]{"[\"a\",\"a\"]", "[\"../escape\"]", "[null]", "{}", "[\"A\"]"}) {
            var invalid = new JsonObject(); invalid.add("_loot_entry_order", JsonParser.parseString(order));
            rejects(() -> ClockworkBossEditorSchema.applyOrders(candidate.deepCopy(), invalid), "invalid structural reward order");
        }
        var huge = new com.google.gson.JsonArray(); for (int i = 0; i < 33; i++) huge.add("reward_" + i);
        var invalidOrder = new JsonObject(); invalidOrder.add("_loot_entry_order", huge);
        rejects(() -> ClockworkBossEditorSchema.applyOrders(candidate.deepCopy(), invalidOrder), "entry cap enforced");
        for (String invalid : new String[]{"minecraft:air", "diamond", "Minecraft:diamond", "minecraft:bad path", "x:" + "a".repeat(256)})
            rejects(() -> ClockworkBossLootEntries.parseItem(invalid), "item ID validation");
        for (String bad : new String[]{"0", "65", "1.1", "-1", "100000000000000000000000", "\"1\"", "null"}) {
            var config = candidate.deepCopy();
            config.getAsJsonObject("loot").getAsJsonObject("entries").getAsJsonObject("reward_1").add("min_count", JsonParser.parseString(bad));
            rejects(() -> ClockworkBossLoot.read(config), "strict bounded quantity");
        }
        for (String bad : new String[]{"-0.01", "1.01", "\"1\"", "null"}) {
            var config = candidate.deepCopy();
            config.getAsJsonObject("loot").getAsJsonObject("entries").getAsJsonObject("reward_1").add("chance", JsonParser.parseString(bad));
            rejects(() -> ClockworkBossLoot.read(config), "strict chance");
        }
        var minGreater = candidate.deepCopy(); minGreater.getAsJsonObject("loot").getAsJsonObject("entries").getAsJsonObject("reward_1").addProperty("min_count", 2);
        rejects(() -> ClockworkBossLoot.read(minGreater), "minimum cannot exceed maximum");
        var unregistered = candidate.deepCopy(); unregistered.getAsJsonObject("loot").getAsJsonObject("entries").getAsJsonObject("reward_1").addProperty("item", "missing:reward");
        rejects(() -> ClockworkBossLootEntries.validateItems(ClockworkBossLoot.read(unregistered).entries(), java.util.Set.of()), "registry snapshot rejects missing item");
        var empty = ClockworkBossTemplateDocument.defaults(); empty.getAsJsonObject("loot").addProperty("enabled", true);
        check(ClockworkBossLoot.read(empty).entries().isEmpty(), "explicit empty entry table is valid");
        var saved = ClockworkBossTemplateDocument.read(new StringReader(ClockworkBossTemplateDocument.write(candidate)));
        // The editable draft includes a trusted action catalog for the GUI;
        // durable template data intentionally excludes that transport metadata.
        check(saved.equals(ClockworkBossTemplateFormat.forSave(candidate)), "entry comments roundtrip without changing durable data");
        check(!saved.has(ClockworkBossActionEditor.CATALOG), "GUI action catalog is not persisted");
    }

    /** Pure config/editor checks need no live server registry or loot-table roll. */
    private static void checkLootSchema() throws Exception {
        var config = ClockworkBossTemplateDocument.defaults();
        check(!ClockworkBossLoot.read(config).enabled(), "loot disabled by default");
        config.remove("loot");
        check(ClockworkBossLoot.read(config).equals(ClockworkBossLoot.DISABLED), "absent loot preserves native behavior");
        var editable = ClockworkBossEditorSchema.editable(config);
        check(!editable.getAsJsonObject("loot").get("enabled").getAsBoolean(), "old document gets disabled virtual editor fields");
        check(ClockworkBossEditorSchema.field("loot.vanilla_drops", editable.getAsJsonObject("loot").get("vanilla_drops")).choices().equals(java.util.List.of("keep", "replace")), "keep/replace dropdown choices");
        var loot = editable.getAsJsonObject("loot");
        loot.addProperty("enabled", true);
        rejects(() -> ClockworkBossLoot.read(editable), "enabled blank reference");
        loot.addProperty("loot_table", "minecraft:entities/warden");
        check(ClockworkBossLoot.read(editable).keepVanilla(), "keep default for valid reference");
        loot.addProperty("vanilla_drops", "replace");
        check(!ClockworkBossLoot.read(editable).keepVanilla(), "explicit replacement parsed");
        loot.addProperty("vanilla_drops", "typo");
        rejects(() -> ClockworkBossLoot.read(editable), "unknown drop policy");
        for (String invalid : java.util.List.of("Warden", "minecraft:", "Minecraft:entities/warden", "minecraft:bad path", "namespace/path", "x:" + "a".repeat(256)))
            rejects(() -> ClockworkBossLoot.parseTable(invalid), "malformed loot identifier");
        check(!ClockworkBossTemplateDocument.description("loot.loot_table").isBlank(), "GUI and generated config loot help");
        check(ClockworkBossTemplateDocument.write(ClockworkBossTemplateDocument.defaults()).contains("// vanilla_drops:"), "grouped generated comments document drop policy");
    }

    /** Probability boundaries, inheritance, cross-boss title limits and late/rapid shot timing use production helpers. */
    private static void checkTargetingAndVisualTiming() throws Exception {
        for (double sample : new double[]{0, .25, .5, .999999}) {
            check(!ClockworkBossTargeting.switchOn(0, sample), "never endpoint");
            check(ClockworkBossTargeting.switchOn(1, sample), "always endpoint");
        }
        check(ClockworkBossTargeting.switchOn(.5, .499) && !ClockworkBossTargeting.switchOn(.5, .5), "probability strict boundary");
        check(ClockworkBossTargeting.better(ClockworkBossTargeting.Mode.NEAREST, 4, 16), "nearest squared distance");
        check(ClockworkBossTargeting.better(ClockworkBossTargeting.Mode.FARTHEST, 16, 4), "farthest squared distance");
        var config = ClockworkBossTemplateDocument.defaults();
        var base = config.getAsJsonObject("targeting");
        base.addProperty("switch_chance", 1);
        base.addProperty("mode", "farthest");
        var override = config.getAsJsonObject("phases").getAsJsonObject("agitated").getAsJsonObject("targeting");
        override.addProperty("switch_chance", 0);
        var settings = ClockworkBossSettings.read(config);
        check(settings.phase(ClockworkBossPhase.ANNOYED).targeting().switchChance() == 1, "base chance inherited");
        check(settings.phase(ClockworkBossPhase.AGITATED).targeting().switchChance() == 0, "explicit phase never");
        check(settings.phase(ClockworkBossPhase.AGITATED).targeting().mode() == ClockworkBossTargeting.Mode.FARTHEST, "null phase mode inherits");
        base.addProperty("switch_chance", Double.NaN);
        override.addProperty("mode", "unknown");
        check(ClockworkBossSettings.read(config).phase(ClockworkBossPhase.AGITATED).targeting().mode() == ClockworkBossTargeting.Mode.FARTHEST, "invalid phase mode inherits base");
        check(ClockworkBossSettings.read(config).phase(ClockworkBossPhase.ANNOYED).targeting().switchChance() == .5, "invalid base uses safe default");
        base.addProperty("switch_chance", 9);
        check(ClockworkBossSettings.read(config).phase(ClockworkBossPhase.ANNOYED).targeting().switchChance() == 1, "chance clamped");
        base.addProperty("switch_chance", -9);
        check(ClockworkBossSettings.read(config).phase(ClockworkBossPhase.ANNOYED).targeting().switchChance() == 0, "negative chance clamped");
        var title = base.getAsJsonObject("announcement");
        title.addProperty("text", "x".repeat(81)); title.addProperty("cooldown_ticks", 0); title.addProperty("display_ticks", 9999);
        var safeTitle = ClockworkBossSettings.read(config).phase(ClockworkBossPhase.ANNOYED).targeting().announcement();
        check(safeTitle.text().equals("RUN, <player>!"), "oversize title uses safe literal default");
        check(safeTitle.cooldownTicks() == 20 && safeTitle.displayTicks() == 100, "title timer bounds");
        var throttle = new ClockworkBossTitleThrottle();
        UUID one = UUID.randomUUID(), two = UUID.randomUUID();
        check(throttle.acquire(one, 100, 60), "first title");
        check(!throttle.acquire(one, 101, 20), "rapid other-boss title suppressed");
        check(throttle.acquire(two, 101, 60), "independent recipient");
        check(!throttle.acquire(one, 159, 60) && throttle.acquire(one, 160, 60), "cooldown deadline");
        throttle.leave(one); check(throttle.acquire(one, 160, 60), "disconnect releases title timestamp");
        throttle.clear(); check(throttle.acquire(two, 160, 60), "server cleanup releases title timestamps");
        check(throttle.acquire(one, Integer.MAX_VALUE - 10, 60), "tick wrap baseline");
        check(!throttle.acquire(one, Integer.MIN_VALUE + 10, 60), "wrap retains cooldown");
        for (int duration : new int[]{0, 1, 2, 34, 68, 1_000_000}) {
            var shot = new ClockworkBossChargeVisual(1, 100, duration, -1);
            check(ClockworkBossChargeVisual.decode(shot.encode()).equals(shot), "atomic visual round trip");
            check(shot.millis(100 + duration, 0) == 1700, "windup reaches native shot keyframe");
            if (duration >= 2) check(shot.millis(100 + duration / 2, 0) == 850, "late tracker sees mid-charge");
            check(shot.fired(100 + duration).millis(106 + duration, 0) == 2000, "normal recoil speed");
            check(shot.fired(100 + duration).millis(126 + duration, 0) == -1, "finished recoil expires locally");
        }
        check(ClockworkBossChargeVisual.decode("") == null && ClockworkBossChargeVisual.decode("invalid") == null, "cancel/invalid metadata safe");
        check(ClockworkBossChargeVisual.decode("1:100:1000001:-1") == null, "oversize visual duration rejected");
        check(ClockworkBossChargeVisual.decode("1:100:34:99") == null, "fire before start rejected");
        var old = new net.minecraft.nbt.CompoundTag();
        old.putBoolean("ArenaBossFight_Selector", true);
        old.putString("ArenaBossFight_SelectorTemplate", "kept_template");
        old.putInt("ArenaBossFight_Pos1X", 12);
        old.putString("another_mod", "keep");
        var migrated = ClockworkBossLegacyData.selector(old.copy());
        check(migrated.getBooleanOr("ClockworkBosses_Selector", false), "legacy arrow marker migrates");
        check(migrated.getStringOr("ClockworkBosses_SelectorTemplate", "").equals("kept_template"), "legacy arrow template retained");
        check(migrated.getIntOr("ClockworkBosses_Pos1X", 0) == 12, "legacy arrow coordinate retained");
        check(migrated.getStringOr("another_mod", "").equals("keep") && old.contains("ArenaBossFight_Selector"), "foreign/original item data retained");
    }

    /** Exercises the exact production reader/writer, especially null and comment preservation. */
    private static void checkTemplates() throws Exception {
        JsonObject defaults = ClockworkBossTemplateDocument.defaults();
        String text = ClockworkBossTemplateDocument.write(defaults);
        check(ClockworkBossTemplateDocument.read(new StringReader(text)).equals(defaults), "commented round trip");
        check(!text.contains("_documentation"), "documentation is emitted as comments");
        check(text.contains("\"file\": null"), "phase sound file survives serialization");
        check(text.contains("\"scale\": null"), "phase scale inheritance stays visible");
        ClockworkBossSettings settings = ClockworkBossSettings.read(defaults);
        check(settings.dormantDarkness().edgeInterval() == 200
                && settings.dormantDarkness().edgeDuration() == 20
                && settings.dormantDarkness().centerDuration() == 40, "dormant Darkness timing defaults");
        check(settings.phase(ClockworkBossPhase.FURY).darkness().centerDuration()
                >= settings.phase(ClockworkBossPhase.FURY).darkness().centerInterval(), "Fury continuous Darkness defaults");
        check(!text.contains("\\u0027") && !text.contains("\\u003c"), "readable punctuation");
        check(!defaults.getAsJsonObject("phases").getAsJsonObject("fury")
                .has("minimum_health_percent"), "unused terminal threshold removed");
        defaults.addProperty("administrator_note", "a quoted \"sound\"; \\ path; // text; player's skin ♥");
        defaults.add("extension", JsonParser.parseString("{\"array\":[1,null,{\"nested\":true}]}"));
        check(ClockworkBossTemplateDocument.read(new StringReader(ClockworkBossTemplateDocument.write(defaults)))
                .equals(defaults), "unknown fields, arrays, escaping and Unicode survive saves");
        rejects(() -> ClockworkBossTemplateDocument.read(new StringReader("{} {}")), "trailing second root");
        rejects(() -> ClockworkBossTemplateDocument.read(new StringReader("[]")), "non-object root");
        rejects(() -> ClockworkBossTemplateDocument.read(new StringReader("{\"sounds\": {\"phase_change\": {\"phase2.ogg\"}}}")),
                "missing filename field colon");
        String nested = "{\"x\":".repeat(40) + "0" + "}".repeat(40);
        rejects(() -> ClockworkBossTemplateDocument.read(new StringReader(nested)), "excessive nesting");
        check(!ClockworkBossTemplateDocument.defaults().has("extension"), "defaults are independent snapshots");
    }

    /** Tests the production bound, backward phase crossings and the immutable telegraph/hit policy. */
    private static void checkHealthAndChargeSnapshots() throws Exception {
        double[] maxima = {1, 1000, 1024, 1025, 1400, 1800, 2200, 2600, 3000, 1_000_000};
        var cutoffs = new ClockworkBossPhase.Thresholds(75, 50, 25);
        for (double max : maxima) {
            check(ClockworkBossHealth.sanitize(max) == max, "boss health range " + max);
            for (double fraction : new double[]{1, .75, .5, .25, .5, .75, 1}) {
                ClockworkBossPhase expected = fraction > .75 ? ClockworkBossPhase.ANNOYED
                        : fraction > .5 ? ClockworkBossPhase.AGITATED
                        : fraction > .25 ? ClockworkBossPhase.ENRAGED : ClockworkBossPhase.FURY;
                check(ClockworkBossPhase.fromHealth(max * fraction, max, cutoffs) == expected,
                        "downward/backward threshold at " + max + " / " + fraction);
            }
        }
        check(ClockworkBossHealth.sanitize(Double.NaN) == 1, "NaN health becomes native minimum");
        check(ClockworkBossHealth.sanitize(Double.NEGATIVE_INFINITY) == 1, "negative infinite health bounded");
        check(ClockworkBossHealth.sanitize(Double.POSITIVE_INFINITY) == 1_000_000, "positive infinite health bounded");
        check(ClockworkBossHealth.sanitize(2_000_000) == 1_000_000, "modifier result bounded");
        JsonObject config;
        try (var stream = ClockworkBossAuditChecks.class.getResourceAsStream("/presets/1_player.json")) {
            config = ClockworkBossTemplateDocument.read(new java.io.InputStreamReader(stream, java.nio.charset.StandardCharsets.UTF_8));
        }
        var before = ClockworkBossSettings.read(config);
        UUID target = UUID.randomUUID();
        ClockworkBossSonicCharge charge = ClockworkBossSonicCharge.start(target, 100, before.phase(ClockworkBossPhase.AGITATED));
        check(charge.fireTick() == 120 && !charge.due(119) && charge.due(120), "captured 20-tick deadline");
        check(charge.settings().damage() == 6, "captured agitated damage");
        check(before.phase(ClockworkBossPhase.ENRAGED).sonic().damage() == 8
                && before.phase(ClockworkBossPhase.ENRAGED).sonic().chargeTime() == 40, "next phase has different attack");
        var agitated = config.getAsJsonObject("phases").getAsJsonObject("agitated");
        agitated.getAsJsonObject("sonic_boom").addProperty("enabled", false);
        agitated.getAsJsonObject("sonic_boom").addProperty("damage", 42);
        agitated.getAsJsonObject("sonic_boom").addProperty("charge_time_ticks", 100);
        agitated.getAsJsonObject("melee").addProperty("enabled", false);
        var after = ClockworkBossSettings.read(config);
        check(charge.settings().equals(before.phase(ClockworkBossPhase.AGITATED).sonic()) && charge.yieldsToMelee(),
                "reload/disable preserves complete original attack and range rule");
        rejects(() -> ClockworkBossSonicCharge.start(target, 100, after.phase(ClockworkBossPhase.AGITATED)),
                "disabled next attack cannot start");
        check(ClockworkBossSonicCharge.start(target, Long.MAX_VALUE - 1, before.phase(ClockworkBossPhase.AGITATED))
                .fireTick() == Long.MAX_VALUE, "charge deadline overflow bounded");
        check(charge.targetUuid().equals(target), "charge keeps one original target");
    }

    /** Tests transitions and the compiled fallback/clamp logic used by the AI hot path. */
    private static void checkPhasesAndSettings() {
        JsonObject config = ClockworkBossTemplateDocument.defaults();
        ClockworkBossSettings settings = ClockworkBossSettings.read(config);
        double[] health = {100, 75, 74.9, 50, 49.9, 25, 0.1, 0};
        ClockworkBossPhase[] expected = {ClockworkBossPhase.ANNOYED, ClockworkBossPhase.AGITATED,
                ClockworkBossPhase.AGITATED, ClockworkBossPhase.ENRAGED, ClockworkBossPhase.ENRAGED,
                ClockworkBossPhase.FURY, ClockworkBossPhase.FURY, ClockworkBossPhase.FURY};
        for (int index = 0; index < health.length; index++) {
            check(ClockworkBossPhase.fromHealth(health[index], 100, settings.thresholds()) == expected[index],
                    "health boundary " + health[index]);
        }
        JsonObject phases = config.getAsJsonObject("phases");
        JsonObject fury = phases.getAsJsonObject("fury");
        fury.getAsJsonObject("sonic_boom").addProperty("horizontal_range", Double.POSITIVE_INFINITY);
        fury.getAsJsonObject("sonic_boom").addProperty("damage", 2_000_000);
        fury.getAsJsonObject("melee").addProperty("cooldown_ticks", 2.5);
        fury.getAsJsonObject("darkness").addProperty("center_interval_ticks", -50);
        fury.getAsJsonObject("darkness").addProperty("max_radius", 900);
        fury.getAsJsonObject("darkness").addProperty("center_amplifier", 900);
        fury.getAsJsonObject("darkness").addProperty("enabled", false);
        phases.getAsJsonObject("annoyed").addProperty("minimum_health_percent", 30);
        phases.getAsJsonObject("agitated").addProperty("minimum_health_percent", 80);
        ClockworkBossSettings changed = ClockworkBossSettings.read(config);
        check(changed.thresholds().agitated() == 30, "descending cutoffs");
        check(changed.phase(ClockworkBossPhase.FURY).sonic().horizontalRange() == 15, "nonfinite phase inherits base");
        check(changed.phase(ClockworkBossPhase.FURY).sonic().damage() == 1_000_000, "damage cap");
        check(changed.phase(ClockworkBossPhase.FURY).meleeCooldown() == 40, "fractional timer inherits base");
        check(changed.phase(ClockworkBossPhase.FURY).darkness().centerInterval() == 1, "positive aura interval");
        check(changed.phase(ClockworkBossPhase.FURY).darkness().maxRadius() == 256, "query radius cap");
        check(changed.phase(ClockworkBossPhase.FURY).darkness().centerAmplifier() == 4, "amplifier cap");
        check(!changed.phase(ClockworkBossPhase.FURY).darkness().enabled(), "per-phase disable");
        check(settings.phase(ClockworkBossPhase.FURY).darkness().enabled(), "compiled snapshot independent of edits");
        rejects(() -> changed.phases().clear(), "compiled map immutable");
        fury.remove("darkness");
        JsonObject legacy = new JsonObject();
        legacy.addProperty("max_radius", 17);
        config.add("darkness", legacy);
        check(ClockworkBossSettings.read(config).phase(ClockworkBossPhase.FURY).darkness().maxRadius() == 17,
                "used legacy aura fallback remains compatible");
    }

    /** Tests production PNG/OGG guards against truncation, corruption, paths and oversized payloads. */
    private static void checkMediaBoundaries() throws Exception {
        check(!CustomWardenSkinPayload.isValidFileName("../skin.png"), "PNG traversal rejected");
        check(!ClockworkBossSoundPayload.isValidFileName("C:\\sound.ogg"), "OGG absolute path rejected");
        byte[] png = new byte[33];
        byte[] signature = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
        System.arraycopy(signature, 0, png, 0, signature.length);
        ByteBuffer.wrap(png).putInt(8, 13).putInt(12, 0x49484452).putInt(16, 128).putInt(20, 128);
        check(CustomWardenSkinPayload.hasWardenAtlasHeader(png), "128-square PNG header");
        ByteBuffer.wrap(png).putInt(16, Integer.MAX_VALUE);
        check(!CustomWardenSkinPayload.hasWardenAtlasHeader(png), "oversized image header rejected");
        byte[] ogg = identificationPage();
        check(ClockworkBossSoundPayload.hasSafeVorbisContainer(ogg), "known CRC Vorbis container");
        for (int length = 0; length < ogg.length; length++) {
            check(!ClockworkBossSoundPayload.hasSafeVorbisContainer(Arrays.copyOf(ogg, length)), "OGG truncated at " + length);
        }
        for (int offset = 0; offset < ogg.length; offset++) {
            byte[] corrupt = ogg.clone();
            corrupt[offset] ^= 1;
            check(!ClockworkBossSoundPayload.hasSafeVorbisContainer(corrupt), "OGG corrupted at " + offset);
        }
        Random random = new Random(263);
        for (int sample = 0; sample < 2000; sample++) {
            byte[] garbage = new byte[random.nextInt(2048)];
            random.nextBytes(garbage);
            check(!ClockworkBossSoundPayload.hasSafeVorbisContainer(garbage), "random OGG rejected");
            check(!CustomWardenSkinPayload.hasWardenAtlasHeader(garbage), "random PNG rejected");
        }
        String revision = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(ogg));
        check(ClockworkBossSoundPayload.matchesRevision(ogg, revision), "content hash verified");
        check(!ClockworkBossSoundPayload.matchesRevision(new byte[0], revision), "wrong content hash rejected");
        ClockworkBossSoundPayload payload = new ClockworkBossSoundPayload("test.ogg", revision, ogg,
                0, 0, 0, 9, 0.1F, 900);
        ogg[0] = 0;
        check(payload.oggBytes()[0] == 'O', "payload construction is defensive");
        byte[] returned = payload.oggBytes();
        returned[0] = 0;
        check(payload.oggBytes()[0] == 'O', "payload accessor is defensive");
        check(payload.volume() == 4 && payload.pitch() == 0.5 && payload.range() == 512, "playback clamps");
        rejects(() -> new ClockworkBossSoundPayload("test.ogg", revision,
                new byte[ClockworkBossSoundPayload.MAX_OGG_BYTES + 1], 0, 0, 0, 1, 1, 32), "oversized OGG rejected");
        rejects(() -> new ClockworkBossSoundPayload("test.ogg", revision, new byte[0],
                Double.NaN, 0, 0, 1, 1, 32), "nonfinite coordinate rejected");
        check(ClockworkBossSoundPayload.MAX_OGG_BYTES + 256 < 1024 * 1024, "packet metadata fits native 1 MiB limit");
    }

    /** An independent bitwise CRC implementation verifies the optimized production table. */
    private static byte[] identificationPage() {
        byte[] page = new byte[58];
        page[0] = 'O'; page[1] = 'g'; page[2] = 'g'; page[3] = 'S';
        page[5] = 6; // beginning + end of one logical stream
        page[26] = 1; page[27] = 30;
        page[28] = 1;
        byte[] vorbis = "vorbis".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        System.arraycopy(vorbis, 0, page, 29, 6);
        page[39] = 2;
        ByteBuffer.wrap(page).order(ByteOrder.LITTLE_ENDIAN).putInt(40, 48_000);
        page[56] = (byte) 0xA8; page[57] = 1;
        int crc = 0;
        for (byte value : page) {
            crc ^= (value & 255) << 24;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 0x80000000) != 0 ? (crc << 1) ^ 0x04C11DB7 : crc << 1;
            }
        }
        ByteBuffer.wrap(page).order(ByteOrder.LITTLE_ENDIAN).putInt(22, crc);
        return page;
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }

    private static void rejects(ThrowingAction action, String message) {
        try {
            action.run();
        } catch (Exception expected) {
            assertions++;
            return;
        }
        throw new AssertionError("Expected rejection: " + message);
    }

    @FunctionalInterface private interface ThrowingAction { void run() throws Exception; }
}
