package clockwork.bosses.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * The workshop's brown/copper/brass palette, shared by the editor and item picker.
 * Widgets retain Minecraft's input, focus, tooltips and narration; only their appearance changes.
 */
final class ClockworkBossEditorTheme {
    private ClockworkBossEditorTheme() {}

    // Match the workshop's CSS tokens. Opaque surfaces keep text readable over any world.
    static final int BG = 0xFF1C1916, PANEL = 0xFF26211C, SIDE = 0xFF201C18;
    static final int RAISED = 0xFF302921, LINE = 0xFF44382C;
    static final int TEXT = 0xFFF1E9DD, MUTED = 0xFFC2B3A3, DIM = 0xFFAA9B8B;
    static final int BRASS = 0xFFDBB76E, COPPER = 0xFFCE8953, FOCUS = 0xFFF0B184;
    static final int SELECTED = 0xFF5B3422, SELECTED_TEXT = 0xFFFFE0C2;
    static final int CONTROL_HEIGHT = 18;

    /** Preserve native text editing/IME/caret behavior rather than recreating a text box. */
    static EditBox input(Font font, int x, int y, int width, Component description) {
        EditBox input = new EditBox(font, x, y, width, CONTROL_HEIGHT, description);
        input.setTextShadow(false);
        input.setTextColor(TEXT);
        input.setTextColorUneditable(DIM);
        return input;
    }

    static String ellipsis(Font font, String text, int width) {
        if (font.width(text) <= width) return text;
        return font.plainSubstrByWidth(text, Math.max(0, width - font.width("…"))) + "…";
    }

    /** Solid native button; selection uses a copper rail as well as color, and focus stays visible. */
    static final class FlatButton extends net.minecraft.client.gui.components.Button {
        private final Font font;
        boolean selected, nested, primary;

        FlatButton(Font font, int x, int y, int width, int height, String text, Runnable action) {
            super(x, y, width, height, Component.literal(text), button -> action.run(), DEFAULT_NARRATION);
            this.font = font;
        }

        @Override protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
            int background = !active ? PANEL : primary ? COPPER : selected ? SELECTED
                    : isHoveredOrFocused() ? RAISED : PANEL;
            graphics.fill(getX(), getY(), getRight(), getBottom(), background);
            graphics.outline(getX(), getY(), getWidth(), getHeight(),
                    active && isHoveredOrFocused() ? FOCUS : selected ? COPPER : LINE);
            if (nested || selected) graphics.fill(getX(), getY() + 2, getX() + 2, getBottom() - 2,
                    selected ? COPPER : LINE);
            int color = !active ? DIM : primary ? BG : selected ? SELECTED_TEXT : TEXT;
            graphics.centeredText(font, ellipsis(font, getMessage().getString(), getWidth() - 8),
                    getX() + getWidth() / 2, getY() + (getHeight() - 8) / 2, color);
        }
    }
}
