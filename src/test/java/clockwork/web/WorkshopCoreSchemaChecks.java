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
        System.out.println("PASS: live core/addon schema validation, unknown number preservation, offline constructor and darkness publication");
    }
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
}
