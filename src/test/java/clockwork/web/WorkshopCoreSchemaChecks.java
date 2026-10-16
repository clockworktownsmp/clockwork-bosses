package clockwork.web;

import clockwork.bosses.ClockworkBossEditorSchema;
import clockwork.bosses.ClockworkBossTemplateDocument;
import clockwork.bosses.ClockworkBossTemplateFormat;
import clockwork.bosses.ClockworkBossTemplateSchema;
import clockwork.bosses.api.BossAction;
import clockwork.bosses.api.BossActionRegistry;
import clockwork.bosses.api.BossActionType;
import clockwork.bosses.api.BossSetting;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import net.minecraft.resources.Identifier;

/** The relay validator consumes the real core/addon export, not a second list
 * of settings. Existing public standalone validator construction remains valid. */
public final class WorkshopCoreSchemaChecks {
    public static void main(String[] args) throws Exception {
        var registrar = BossActionRegistry.registrar("merge_fixture");
        try { registrar.register(new BossActionType(Identifier.parse("merge_fixture:probe"), "Probe", "Test only",
                List.of(BossSetting.number("power", "Power", "Bounded addon setting", 3, 1, 7, true)), Set.of(),
                () -> context -> BossAction.Result.COMPLETE)); }
        finally { registrar.close(); }
        var live = new TemplateJson(ClockworkBossTemplateSchema.write().getBytes(StandardCharsets.UTF_8));
        var config = ClockworkBossTemplateDocument.defaults();
        var child = ClockworkBossEditorSchema.newSubphase(); child.addProperty("type", "merge_fixture:probe");
        var settings = new com.google.gson.JsonObject(); settings.addProperty("power", 7); child.add("action_settings", settings);
        config.getAsJsonObject("phases").getAsJsonObject("annoyed").getAsJsonObject("subphases").add("probe", child);
        live.inspect(bytes(ClockworkBossTemplateFormat.writeJson(config)), true);
        settings.addProperty("power", 8);
        try { live.inspect(bytes(ClockworkBossTemplateFormat.writeJson(config)), true); throw new AssertionError("Installed addon bound was ignored"); }
        catch (java.io.IOException expected) { }
        String opaque = "{\"format_version\":1,\"addon\":{\"huge\":900719925474099312345,\"small\":1.2300e-120},\"loot\":null}";
        live.inspect(bytes(opaque), true);
        var tree = TemplateJson.parse(bytes(opaque));
        if (!tree.getAsJsonObject("addon").get("huge").getAsString().equals("900719925474099312345")
                || !tree.getAsJsonObject("addon").get("small").getAsString().equals("1.2300e-120")) throw new AssertionError("Unknown number tokens changed");
        new TemplateJson().inspect(bytes("{\"format_version\":1}"), true);
        // Exercise the actual file store and live-schema validator. A correct
        // save/publish must retain these fields, not replace them with defaults.
        var auraConfig = ClockworkBossTemplateDocument.defaults();
        var aura = auraConfig.getAsJsonObject("phases").getAsJsonObject("annoyed").getAsJsonObject("darkness");
        aura.addProperty("max_radius", 100);
        aura.addProperty("edge_interval_ticks", 150); aura.addProperty("center_interval_ticks", 100);
        aura.addProperty("edge_duration_ticks", 100); aura.addProperty("center_duration_ticks", 150);
        var auraStore = new TemplateFiles(java.nio.file.Files.createTempDirectory(java.nio.file.Path.of("build").toAbsolutePath(), "darkness-publish-"), live);
        byte[] editedAura = bytes(ClockworkBossTemplateFormat.writeJson(auraConfig));
        var auraDraft = auraStore.save("darkness", editedAura, "none", "none");
        auraStore.publish("darkness", auraDraft.revision());
        if (!java.util.Arrays.equals(editedAura, auraStore.draft("darkness").content())
                || !java.util.Arrays.equals(editedAura, auraStore.active("darkness").content()))
            throw new AssertionError("Darkness values changed between saved draft and active publication");
        var compiledAura = clockwork.bosses.ClockworkBossSettings.read(TemplateJson.parse(auraStore.active("darkness").content()))
                .phase(clockwork.bosses.ClockworkBossPhase.ANNOYED).darkness();
        if (compiledAura.maxRadius() != 100 || compiledAura.edgeInterval() != 150 || compiledAura.centerInterval() != 100
                || compiledAura.edgeDuration() != 100 || compiledAura.centerDuration() != 150)
            throw new AssertionError("Published darkness values did not reach compiled encounter settings");
        checkOpaqueAndKnownNumbers(live);
        checkPhaseParity(live);
        System.out.println("PASS: live core/addon schema validation, unknown number preservation, offline constructor and darkness publication");
    }

    /** Prepared source regressions: unknown values remain lexical through both
     * file commits, while only numbers actually interpreted by schema rules
     * enter bounded arithmetic. No decimal expansion is needed for these checks. */
    private static void checkOpaqueAndKnownNumbers(TemplateJson validator) throws Exception {
        String wide = "9".repeat(160);
        String leading = "1e00000000000000000000000000001";
        String fixture = "{\"format_version\":1,\"foreign_addon\":{\"extreme\":1e-2000000000,"
                + "\"positive\":1e2000000000,\"negative\":-1e-2000000000,\"wide\":" + wide
                + ",\"spelling\":" + leading + ",\"integer\":9007199254740993,\"array\":[null,{\"x\":true}]},"
                + "\"sounds\":{\"spawn\":{\"volume\":1e-120}}}";
        byte[] original = bytes(fixture);
        validator.inspect(original, false); validator.inspect(original, true);
        var opaque = TemplateJson.parseDocument(original).getAsJsonObject("foreign_addon");
        require(opaque.get("extreme").getAsString().equals("1e-2000000000")
                && opaque.get("positive").getAsString().equals("1e2000000000")
                && opaque.get("negative").getAsString().equals("-1e-2000000000")
                && opaque.get("wide").getAsString().equals(wide)
                && opaque.get("spelling").getAsString().equals(leading), "Opaque numeric tokens changed");
        var store = new TemplateFiles(java.nio.file.Files.createTempDirectory(
                java.nio.file.Path.of("build").toAbsolutePath(), "opaque-publish-"), validator);
        var draft = store.save("opaque", original, "none", "none");
        store.publish("opaque", draft.revision());
        require(java.util.Arrays.equals(original, store.draft("opaque").content())
                && java.util.Arrays.equals(original, store.active("opaque").content()), "Opaque draft/publication bytes changed");
        var unconstrained = new TemplateJson(bytes("{\"type\":\"object\",\"properties\":{\"format_version\":{\"type\":\"integer\",\"const\":1},\"extension\":{\"type\":\"array\",\"items\":{}}}}"));
        unconstrained.inspect(bytes("{\"format_version\":1,\"extension\":[1e-2000000000," + wide + "]}"), true);

        validator.inspect(bytes("{\"format_version\":1e00000000000000000000000000000}"), true);
        validator.inspect(bytes("{\"format_version\":1,\"health\":1e00000000000000000000000000003}"), true);
        for (String version : List.of("1e-2000000000", wide, "1.00000000000000000000000000001", "\"1\""))
            rejects(() -> validator.inspect(bytes("{\"format_version\":" + version + "}"), false), "Unsafe/inexact format version accepted");
        for (String health : List.of("1e2000000000", wide, "1e10000", "-1")) {
            byte[] unfinished = bytes("{\"format_version\":1,\"health\":" + health + "}");
            validator.inspect(unfinished, false); // Drafts can retain invalid known settings.
            rejects(() -> validator.inspect(unfinished, true), "Unsafe/out-of-range health published");
        }
        rejects(() -> validator.inspect(bytes("{\"format_version\":1,\"dormant_darkness\":{\"edge_interval_ticks\":1.5}}"), true),
                "Fractional integer setting published");
        for (String invalid : List.of("{\"format_version\":1,\"x\":1e+}", "{\"format_version\":1,\"x\":1,\"x\":2}"))
            rejects(() -> TemplateJson.parseDocument(bytes(invalid)), "Malformed/duplicate JSON accepted");
        rejects(() -> TemplateJson.parseDocument(bytes(" ".repeat(TemplateJson.MAX_BYTES + 1))), "Document size budget bypassed");
        rejects(() -> TemplateJson.parseDocument(bytes("{\"x\":" + "[".repeat(34) + "0" + "]".repeat(34) + "}")),
                "Document depth budget bypassed");
        rejects(() -> TemplateJson.parse(bytes("{\"seq\":1e2000000000}")), "Relay/control numeric guard removed");
        // Edit policy remains narrower than inspection of unchanged legacy data.
        for (String edit : List.of("1e-120", wide, "1e-2000000000")) {
            try { clockwork.bosses.ClockworkBossNumbers.decimal(edit); throw new AssertionError("Declared edit limit removed"); }
            catch (NumberFormatException expected) { }
        }
    }

    /** Validate an effective ordered view, never backfill omitted/null raw
     * cutoffs. Ties are legal zero-width phases; final scalar bounds still apply. */
    private static void checkPhaseParity(TemplateJson validator) throws Exception {
        for (String[] cutoffs : List.of(new String[]{"75", "75", "25", "0"},
                new String[]{"75", "50", "25", "100"}, new String[]{"75", "missing", "25", "100"},
                new String[]{"75", "null", "25", "100"}, new String[]{"40", "missing", "35", "100"},
                new String[]{"100"}, new String[]{"50.00000000000000000001", "50.00000000000000000002", "0"},
                new String[]{"-0", "null", "0", "100"})) {
            byte[] fixture = phaseFixture(cutoffs);
            validator.inspect(fixture, false); validator.inspect(fixture, true);
            var raw = TemplateJson.parseDocument(fixture); String before = raw.toString();
            var effective = ClockworkBossEditorSchema.editable(raw);
            ClockworkBossEditorSchema.validate(effective, effective.deepCopy());
            require(raw.toString().equals(before), "Native validation materialized an inherited cutoff");
        }
        String[] sixteen = new String[16]; java.util.Arrays.fill(sixteen, "missing");
        validator.inspect(phaseFixture(sixteen), true);
        var ties = TemplateJson.parseDocument(phaseFixture("75", "75", "25", "0"));
        var thresholds = clockwork.bosses.ClockworkBossPhase.Thresholds.read(ties);
        require(clockwork.bosses.ClockworkBossPhase.fromHealth(50, 100,
                thresholds).key().equals("phase_2"), "Equal-cutoff native stage selection changed");
        require(clockwork.bosses.ClockworkBossPhase.fromHealth(75, 100, thresholds).key().equals("phase_2")
                && clockwork.bosses.ClockworkBossPhase.fromHealth(25, 100, thresholds).key().equals("phase_3"),
                "Native exact-boundary equality changed");
        checkNativeScalarPatch();
        byte[] valid = phaseFixture("75", "missing", "25", "100");
        var store = new TemplateFiles(java.nio.file.Files.createTempDirectory(
                java.nio.file.Path.of("build").toAbsolutePath(), "phase-publish-"), validator);
        var first = store.save("phases", valid, "none", "none"); String active = store.publish("phases", first.revision());
        require(java.util.Arrays.equals(valid, store.active("phases").content()), "Phase omission/final cutoff bytes changed");
        var invalid = store.save("phases", phaseFixture("75", "null", "60", "0"), first.revision(), active);
        rejects(() -> store.publish("phases", invalid.revision()), "Rising explicit cutoff after fallback published");
        require(java.util.Arrays.equals(valid, store.active("phases").content())
                && store.active("phases").revision().equals(active), "Rejected publication changed the active template/revision");
        for (String[] cutoffs : List.of(new String[]{"75", "missing", "60", "0"}, new String[]{"75", "76", "25", "0"},
                new String[]{"75", "-1", "25", "0"}, new String[]{"101", "50", "25", "0"},
                new String[]{"75", "50", "25", "101"})) {
            byte[] fixture = phaseFixture(cutoffs); validator.inspect(fixture, false);
            rejects(() -> validator.inspect(fixture, true), "Invalid explicit/final scalar cutoff published");
        }
        validator.inspect(bytes("{\"format_version\":1,\"phases\":{\"10\":{\"minimum_health_percent\":75},\"2\":{\"minimum_health_percent\":50},\"one\":{\"minimum_health_percent\":100}}}"), true);
    }

    /** Exercise the same parse/put/validate and raw scalar-patch operations used
     * by saveFromEditor. This is a pure codec regression; the actual native
     * session/file transaction still needs the deferred dedicated-server fixture. */
    private static void checkNativeScalarPatch() throws Exception {
        String cutoffPath = "phases.phase_1.minimum_health_percent";
        var stored = TemplateJson.parseDocument(phaseFixture("75", "50", "25", "0"));
        var expected = ClockworkBossEditorSchema.editable(stored);
        var candidate = expected.deepCopy();
        var inherited = ClockworkBossEditorSchema.parse(cutoffPath, "<inherit>",
                ClockworkBossEditorSchema.get(expected, cutoffPath), expected);
        ClockworkBossEditorSchema.put(candidate, cutoffPath, inherited);
        ClockworkBossEditorSchema.validate(expected, candidate);
        var persisted = stored.deepCopy(); ClockworkBossEditorSchema.putCreating(persisted, cutoffPath, inherited);
        var saved = ClockworkBossTemplateFormat.read(new java.io.StringReader(ClockworkBossTemplateFormat.writeJson(persisted)));
        require(ClockworkBossEditorSchema.get(saved, cutoffPath).isJsonNull(), "Native scalar patch discarded explicit inheritance");

        stored = TemplateJson.parseDocument(phaseFixture("75", "missing", "25", "100"));
        expected = ClockworkBossEditorSchema.editable(stored); candidate = expected.deepCopy();
        var health = ClockworkBossEditorSchema.parse("health", "1234", ClockworkBossEditorSchema.get(expected, "health"), expected);
        ClockworkBossEditorSchema.put(candidate, "health", health); ClockworkBossEditorSchema.validate(expected, candidate);
        persisted = stored.deepCopy(); ClockworkBossEditorSchema.putCreating(persisted, "health", health);
        saved = ClockworkBossTemplateFormat.read(new java.io.StringReader(ClockworkBossTemplateFormat.writeJson(persisted)));
        require(ClockworkBossEditorSchema.get(saved, cutoffPath) == null && saved.get("health").getAsInt() == 1234,
                "Unrelated native scalar patch materialized an omitted cutoff");
        var rising = candidate.deepCopy();
        ClockworkBossEditorSchema.put(rising, "phases.phase_2.minimum_health_percent", new com.google.gson.JsonPrimitive(60));
        try { ClockworkBossEditorSchema.validate(expected, rising); throw new AssertionError("Native edit accepted a rise after the virtual fallback"); }
        catch (IllegalArgumentException invalid) { }
    }
    private static byte[] phaseFixture(String... cutoffs) {
        var result = new StringBuilder("{\"format_version\":1,\"phases\":{");
        for (int index = 0; index < cutoffs.length; index++) {
            if (index > 0) result.append(','); result.append("\"phase_").append(index).append("\":{");
            if (!cutoffs[index].equals("missing")) result.append("\"minimum_health_percent\":").append(cutoffs[index]);
            result.append('}');
        }
        return bytes(result.append("}}").toString());
    }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
    private static void rejects(Checked action, String message) throws Exception {
        try { action.run(); throw new AssertionError(message); } catch (java.io.IOException expected) { }
    }
    private static void require(boolean accepted, String message) { if (!accepted) throw new AssertionError(message); }
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
}
