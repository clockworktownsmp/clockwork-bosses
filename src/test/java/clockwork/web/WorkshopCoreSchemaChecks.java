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
        System.out.println("PASS: live core/addon schema validation, unknown number preservation and offline constructor");
    }
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
}
