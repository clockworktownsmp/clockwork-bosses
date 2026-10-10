package clockwork.bosses.client;

import clockwork.bosses.ClockworkBossEditorPayload;
import clockwork.bosses.ClockworkBossEditorSchema;
import clockwork.bosses.ClockworkBossTemplateDocument;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonInfo;

import java.util.Map;
import java.util.UUID;

/** Development-only native GUI checks. Reflection reaches private draft controls without adding
 * production test hooks. No connected server, packets, real inventory changes or file writes. */
public final class ClockworkBossLootGuiChecks {
    private static int checks;
    private ClockworkBossLootGuiChecks() {}

    public static int run(Minecraft client) throws Exception {
        for (int[] size : new int[][]{{320, 240}, {640, 360}, {1280, 720}}) {
            var document = ClockworkBossEditorSchema.editable(ClockworkBossTemplateDocument.defaults());
            var screen = new ClockworkBossTemplateScreen(new ClockworkBossEditorPayload(UUID.randomUUID(), "document", false, "test", "revision", ""), document);
            screen.init(size[0], size[1]);
            inputs(screen).put("health", "2345");
            select(screen, "loot.entries");
            press(screen, "+ Add item…");
            check(value(screen, "lootPicker") != null, "picker opened at " + size[0]);
            check(screen.children().stream().filter(Button.class::isInstance).count() > 5, "visible native item icons");
            bounds(screen, size);
            search(screen).setValue("minecraft:diamond");
            check(screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                    .anyMatch(button -> button.getMessage().getString().endsWith(" — minecraft:diamond")), "ID search finds diamond");
            pressItem(screen, "minecraft:diamond");
            check(value(screen, "lootPicker") == null, "picker closes after choosing");
            var draft = (JsonObject) value(screen, "draft");
            check(ClockworkBossEditorSchema.get(draft, "loot.entries.reward_1.item").getAsString().equals("minecraft:diamond"), "chosen item added to draft");
            check(inputs(screen).get("health").equals("2345"), "unrelated pending text preserved");
            select(screen, "loot.entries"); press(screen, "+ Add item…"); search(screen).setValue("NO_SUCH_REWARD_ITEM");
            check(screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                    .noneMatch(button -> button.getMessage().getString().contains(" — minecraft:")), "no matches hides item choices");
            screen.keyPressed(new KeyEvent(com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE, 0, 0));
            check(value(screen, "lootPicker") == null, "Escape returns to editor without discarding draft");
            check(draft.getAsJsonObject("loot").getAsJsonObject("entries").size() == 1, "cancel adds nothing");
            // Invoke the same Pick action after a resize; quantity pages may scroll at the highest scale.
            screen.init(640, 360); select(screen, "loot.entries.reward_1"); press(screen, "Pick");
            search(screen).setValue("minecraft:emerald"); pressItem(screen, "minecraft:emerald");
            check(inputs(screen).get("loot.entries.reward_1.item").equals("minecraft:emerald"), "existing reward item changes through picker");
            var parse = ClockworkBossTemplateScreen.class.getDeclaredMethod("parseInput", String.class, String.class, JsonElement.class); parse.setAccessible(true);
            var chance = (JsonElement) parse.invoke(null, "loot.entries.reward_1.chance", "25", ClockworkBossEditorSchema.get(draft, "loot.entries.reward_1.chance"));
            check(chance.getAsDouble() == .25, "GUI percentage stores native probability");
            for (String invalid : new String[]{"1e-2000000000", "0e2000000000", "100.00000000000000000001", "-0.00000000000000000001"}) {
                try {
                    parse.invoke(null, "loot.entries.reward_1.chance", invalid, new com.google.gson.JsonPrimitive(1));
                    throw new AssertionError("Unsafe percentage accepted: " + invalid);
                } catch (java.lang.reflect.InvocationTargetException failure) {
                    check(failure.getCause() instanceof IllegalArgumentException, "unsafe percentage rejected without large allocation");
                }
            }
            var display = ClockworkBossTemplateScreen.class.getDeclaredMethod("displayValue", String.class, JsonElement.class); display.setAccessible(true);
            var legacy = (String) display.invoke(null, "loot.entries.reward_1.chance", com.google.gson.JsonParser.parseString("1e-2000000000"));
            check(legacy.length() < 32, "legacy percentage displayed compactly");
            select(screen, "loot.entries"); press(screen, "Remove");
            check(draft.getAsJsonObject("loot").getAsJsonObject("entries").isEmpty(), "remove clears selected entry only");
            check(!inputs(screen).containsKey("loot.entries.reward_1.item"), "removed entry releases pending text");
            screen.removed();
        }
        return checks;
    }

    private static void bounds(ClockworkBossTemplateScreen screen, int[] size) {
        for (var child : screen.children()) if (child instanceof AbstractWidget widget)
            check(widget.getX() >= 0 && widget.getY() >= 0 && widget.getRight() <= size[0] && widget.getBottom() <= size[1], "picker widget fits GUI size");
    }
    public static void preview(Minecraft client, String query) throws Exception {
        var screen = new ClockworkBossTemplateScreen(new ClockworkBossEditorPayload(UUID.randomUUID(), "document", false, "loot_preview", "revision", ""),
                ClockworkBossEditorSchema.editable(ClockworkBossTemplateDocument.defaults()));
        client.gui.setScreen(screen); select(screen, "loot.entries"); press(screen, "+ Add item…"); search(screen).setValue(query);
    }
    public static void searchPreview(Minecraft client, String query) { search((ClockworkBossTemplateScreen) client.gui.screen()).setValue(query); }
    private static void check(boolean test, String text) { checks++; if (!test) throw new AssertionError(text); }
    private static void select(ClockworkBossTemplateScreen screen, String page) throws Exception {
        var method = ClockworkBossTemplateScreen.class.getDeclaredMethod("selectPage", String.class); method.setAccessible(true); method.invoke(screen, page);
    }
    private static Object value(ClockworkBossTemplateScreen screen, String key) throws Exception {
        var field = ClockworkBossTemplateScreen.class.getDeclaredField(key); field.setAccessible(true); return field.get(screen);
    }
    @SuppressWarnings("unchecked")
    private static Map<String, String> inputs(ClockworkBossTemplateScreen screen) throws Exception { return (Map<String, String>) value(screen, "inputs"); }
    private static EditBox search(ClockworkBossTemplateScreen screen) { return screen.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast).findFirst().orElseThrow(); }
    private static void press(ClockworkBossTemplateScreen screen, String label) {
        var button = screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(item -> item.getMessage().getString().equals(label)).findFirst().orElseThrow(() -> new AssertionError("Missing button: " + label));
        button.onPress(new MouseButtonInfo(0, 0));
    }
    private static void pressItem(ClockworkBossTemplateScreen screen, String id) {
        var button = screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(item -> item.getMessage().getString().endsWith(" — " + id)).findFirst().orElseThrow();
        button.onPress(new MouseButtonInfo(0, 0));
    }
}
