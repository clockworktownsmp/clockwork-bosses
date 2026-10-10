package clockwork.bosses.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** Searchable creative-style item window inside the existing editor screen/session.
 * Native creative tabs supply categories; the All category also includes registered mod items.
 * Selecting writes an ID into the draft only: no inventory packets, item grants or file writes. */
final class ClockworkBossLootPicker {
    private record ItemChoice(Identifier id, ItemStack stack, String name, String search) {}
    private record Category(String name, Set<Identifier> items) {}
    private final Font font;
    private final Consumer<Identifier> selected;
    private final Runnable cancel;
    private final List<ItemChoice> all;
    private final List<Category> categories = new ArrayList<>();
    private List<ItemChoice> filtered = List.of();
    private String query = "";
    private int category, page, pages = 1, left, right, top, bottom, pageSize;
    private EditBox searchBox;

    ClockworkBossLootPicker(Font font, Consumer<Identifier> selected, Runnable cancel) {
        this.font = font; this.selected = selected; this.cancel = cancel;
        // Cache localized names once per opening, not on each render/search keystroke.
        all = BuiltInRegistries.ITEM.stream().filter(item -> item != Items.AIR).map(item -> {
            var id = BuiltInRegistries.ITEM.getKey(item);
            var stack = new ItemStack(item);
            String name = stack.getHoverName().getString();
            return new ItemChoice(id, stack, name, (name + " " + id).toLowerCase(Locale.ROOT));
        }).sorted(Comparator.comparing(choice -> choice.id().toString())).toList();
        categories.add(new Category("All items", Set.of()));
        var client = Minecraft.getInstance();
        if (client.level != null) {
            CreativeModeTabs.tryRebuildTabContents(client.level.enabledFeatures(), true, client.level.registryAccess());
            for (var tab : CreativeModeTabs.tabs()) {
                if (tab.getDisplayItems().isEmpty()) continue;
                // Basic rewards store item IDs, so variants of the same potion/book collapse to
                // one default item. Component/enchantment editing can extend the reward type later.
                var ids = tab.getDisplayItems().stream().map(stack -> BuiltInRegistries.ITEM.getKey(stack.getItem())).collect(Collectors.toUnmodifiableSet());
                categories.add(new Category(tab.getDisplayName().getString(), ids));
            }
        }
        filter();
    }

    private void filter() {
        String text = query.trim().toLowerCase(Locale.ROOT);
        Set<Identifier> allowed = categories.get(category).items();
        filtered = all.stream().filter(item -> category == 0 || allowed.contains(item.id()))
                .filter(item -> item.search().contains(text)).toList();
    }

    /** Native widgets provide keyboard activation/narration. Only a visible page owns icon widgets. */
    List<AbstractWidget> widgets(int width, int height, Runnable rebuild) {
        left = Math.max(12, (width - 704) / 2); right = width - left;
        top = 80; bottom = height - 52;
        int columns = Math.max(1, (right - left) / 24), rows = Math.max(1, (bottom - top) / 24);
        pageSize = columns * rows;
        pages = Math.max(1, (filtered.size() + pageSize - 1) / pageSize);
        page = Math.clamp(page, 0, pages - 1);
        var widgets = new ArrayList<AbstractWidget>();
        int cursor = searchBox == null ? query.length() : searchBox.getCursorPosition();
        searchBox = new EditBox(font, left, 34, right - left - 72, 20, Component.literal("Search item names or namespace:item IDs"));
        searchBox.setMaxLength(128); searchBox.setValue(query); searchBox.setCursorPosition(Math.min(cursor, query.length()));
        searchBox.setHint(Component.literal("Search items…"));
        searchBox.setResponder(text -> { query = text; page = 0; filter(); rebuild.run(); });
        widgets.add(searchBox);
        widgets.add(button(right - 68, 34, 68, "Back", cancel));
        widgets.add(button(left, 58, right - left, "Category: " + categories.get(category).name() + " ›", () -> {
            category = (category + 1) % categories.size(); page = 0; filter(); rebuild.run();
        }));
        for (int index = page * pageSize; index < Math.min(filtered.size(), (page + 1) * pageSize); index++) {
            var item = filtered.get(index); int position = index - page * pageSize;
            int x = left + position % columns * 24, y = top + position / columns * 24;
            var icon = new Button(x, y, 22, 22, Component.literal(item.name() + " — " + item.id()), ignored -> selected.accept(item.id()), narration -> narration.get()) {
                @Override protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
                    graphics.fill(getX(), getY(), getRight(), getBottom(), isHoveredOrFocused() ? 0xFF777777 : 0xFF555555);
                    if (isFocused()) graphics.outline(getX(), getY(), getWidth(), getHeight(), 0xFFF1F1F1);
                    graphics.item(item.stack(), getX() + 3, getY() + 3);
                }
            };
            icon.setTooltip(Tooltip.create(Component.literal(item.name() + "\n" + item.id() + "\nDefault item; no extra components.")));
            widgets.add(icon);
        }
        var previous = button(left, height - 46, 64, "Previous", () -> { page--; rebuild.run(); }); previous.active = page > 0;
        var next = button(right - 64, height - 46, 64, "Next", () -> { page++; rebuild.run(); }); next.active = page + 1 < pages;
        widgets.add(previous); widgets.add(next);
        return widgets;
    }

    private static Button button(int x, int y, int width, String text, Runnable action) {
        return Button.builder(Component.literal(text), ignored -> action.run()).bounds(x, y, width, 20).build();
    }
    EditBox searchBox() { return searchBox; }
    void scroll(double amount, Runnable rebuild) {
        int next = Math.clamp(page - (int) Math.signum(amount), 0, pages - 1);
        if (next != page) { page = next; rebuild.run(); }
    }
    void background(GuiGraphicsExtractor graphics, int width, int height) {
        graphics.fill(0, 0, width, height, 0xFF353535);
        graphics.text(font, "Choose reward item", left, 16, 0xFFF1F1F1, false);
        graphics.fill(left - 2, top - 2, right + 2, bottom + 2, 0xFF454545);
        if (filtered.isEmpty()) graphics.text(font, "No matching items.", left + 8, top + 10, 0xFFF1F1F1, false);
        graphics.centeredText(font, "Page " + (page + 1) + "/" + pages + " · " + filtered.size() + " items", width / 2, height - 40, 0xFFF1F1F1);
        graphics.text(font, "Choose an icon. Save the template to keep your changes.", left, height - 18, 0xFFC5C5C5, false);
    }
}
