package clockwork.bosses.client;

import clockwork.bosses.ClockworkBossEditorPayload;
import clockwork.bosses.ClockworkBossEditorSchema;
import clockwork.bosses.ClockworkBossLootEntries;
import clockwork.bosses.ClockworkBossNumbers;
import clockwork.bosses.ClockworkBossPhase;
import clockwork.bosses.ClockworkBossEyes;
import clockwork.bosses.ClockworkBossPupils;
import clockwork.bosses.ClockworkBossMarker;
import clockwork.bosses.ClockworkBossTemplateDocument;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import clockwork.bosses.client.ClockworkBossEditorTheme.FlatButton;
import static clockwork.bosses.client.ClockworkBossEditorTheme.*;

/**
 * Compact workshop-colored native Minecraft screen. Layout uses GUI coordinates, not display pixels.
 * Only fully visible rows own widgets; scrolling/collapse never discards draft text.
 * No config reads, packets or rebuilding happens each frame: these are input/response operations.
 */
public final class ClockworkBossTemplateScreen extends Screen {
    private static final int GROUP_HEIGHT = 24, ENTRY_HEIGHT = 36, INDEX_HEIGHT = 20, FIELD_GAP = 12, SCROLL_STEP = 32;
    private final UUID token;
    private final boolean picker;
    private final String name;
    private final boolean preset;
    private String revision;
    private JsonObject original, draft;
    private final JsonObject catalog;
    private final Map<String, String> inputs = new HashMap<>();
    private final Set<String> collapsed = new HashSet<>();
    private final List<Section> sections = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();
    private final List<Label> labels = new ArrayList<>();
    private final List<AbstractWidget> pickerButtons = new ArrayList<>();
    private final Map<EditBox, String> editPaths = new HashMap<>();
    private String selected = "base", status = "", selectedHelp = "", filter = "", selectedPageTitle = "";
    private boolean busy, pickPresets, confirmingClose, helpExpanded;
    // Sidebar disclosure is separate from page folds: phase fields stay on their
    // own pages, and rebuilding widgets or resizing must not close this branch.
    private boolean phasesExpanded;
    private boolean lootEntriesExpanded;
    private final Set<String> subphasesExpanded = new HashSet<>();
    // Dragging changes insertion order only on release. Names, draft inputs and
    // server state are untouched until Save; no drag timer or per-frame rebuild.
    private final List<DragRow> dragRows = new ArrayList<>();
    private String dragRoot, dragKey;
    private int dragPosition, phaseListHeader;
    private double dragMouseY;
    private int scroll, indexScroll, contentX, contentWidth, top, bottom, indexWidth, fullHeight;
    private FlatButton saveButton;
    private Dropdown dropdown;
    private ClockworkBossLootPicker lootPicker;
    private int focusCursor;
    private String focusPath;
    private net.minecraft.world.entity.monster.warden.Warden previewEntity;
    private JsonObject previewConfig;
    private boolean previewFailed, headPreview = false, rotating;
    private String previewPhase = "", previewEye = "Both", previewBrow = "Both", previewPupil = "Both";
    private float previewYaw, previewPitch;
    private int previewX, previewY, previewWidth, previewHeight;
    // Template fields change on input/response, not every frame. Cache metadata
    // until those events invalidate it; native pose extraction still runs per frame.
    private boolean previewMetadataDirty = true;

    private record Section(String path, String label) {}
    private record Row(String path, String label, int depth, boolean heading) {}
    /** Input-driven placement is also the source of scroll bounds; fields never overlap section headers. */
    private record RowLayout(Row row, int x, int width, int offset, int height) {}
    private record Label(String text, int x, int y, int width, boolean secondary) {}
    private record DragRow(String key, int position, int y) {}
    /** One cross-pad layout, with a different coordinate space for each face part. */
    private enum FacePart {
        EYES("skin.glowing_eyes.", "Eyes", "__eye"),
        BROWS("skin.glowing_eyebrows.", "Eyebrows", "__brow"),
        PUPILS("skin.glowing_pupils.", "Pupils", "__pupil");
        final String root, title, selector;
        FacePart(String root, String title, String selector) { this.root = root; this.title = title; this.selector = selector; }
    }
    private record Dropdown(String path, List<String> choices, int x, int y, int width, int first, int cursor) {
        Dropdown(String path, List<String> choices, int x, int y, int width, int first) { this(path, choices, x, y, width, first, first); }
    }

    public ClockworkBossTemplateScreen(ClockworkBossEditorPayload payload, JsonObject document) {
        super(Component.literal("Clockwork Bosses — " + (payload.action().equals("catalog") ? "Template editor" : (payload.preset() ? "Preset: " : "Template: ") + payload.name())));
        token = payload.token(); picker = payload.action().equals("catalog");
        name = payload.name(); preset = payload.preset(); revision = payload.revision();
        catalog = picker ? document : null;
        if (!picker) { original = document.deepCopy(); draft = document.deepCopy(); previewConfig = draft.deepCopy(); buildSections(); collapsePage(); }
    }

    @Override protected void init() {
        cancelOrderDrag(); // A resized GUI must not drop using the old geometry.
        // Minecraft guarantees at least about 320x240 GUI units at normal scales.
        // Keep the standard font. Compact padding and adaptive columns supply more room.
        indexWidth = Math.clamp(width / 5, 84, 140);
        contentX = indexWidth + 16; contentWidth = width - contentX - 10;
        top = 38;
        rebuild();
    }

    /** Replace widgets only on layout/input changes, restoring the focused draft's caret after scroll/resize. */
    private void rebuild() {
        if (busy) cancelOrderDrag();
        previewMetadataDirty = true;
        if (getFocused() instanceof EditBox box && focusPath != null) focusCursor = box.getCursorPosition();
        clearWidgets(); labels.clear(); rows.clear(); pickerButtons.clear(); editPaths.clear(); dragRows.clear(); dropdown = null;
        if (lootPicker != null) {
            for (var widget : lootPicker.widgets(width, height, this::rebuild)) addRenderableWidget(widget);
            setInitialFocus(lootPicker.searchBox());
            return;
        }
        addButton(width - 70, 8, 60, CONTROL_HEIGHT, "Close", this::onClose);
        if (picker) { pickerWidgets(); return; }
        // Detailed help is available on demand instead of permanently reserving a tall footer.
        bottom = height - (helpExpanded ? 72 : 42);
        // Navigation/resize owns this label; rendering never searches the section catalog.
        selectedPageTitle = ellipsis(sections.stream().filter(section -> section.path.equals(selected))
                .findFirst().map(Section::label).orElse("Settings"), contentWidth);
        if (selectedHelp.isEmpty()) selectedHelp = sectionHelp(selected);
        int foldWidth = width < 420 ? 44 : 64;
        int actionWidth = Math.min(90, (width - 2 * foldWidth - 62) / 3);
        int actionX = width - 8 - actionWidth * 3 - 8;
        saveButton = addButton(actionX, height - 24, actionWidth, CONTROL_HEIGHT, "Save", this::save);
        saveButton.primary = true;
        saveButton.active = !busy && dirty();
        addButton(actionX + actionWidth + 4, height - 24, actionWidth, CONTROL_HEIGHT, width < 420 ? "Reload" : "Reload saved", this::reloadSaved)
                .setTooltip(Tooltip.create(Component.literal("Apply saved templates to loaded bosses, like /boss reload. Save edits first. Presets do not change existing template copies.")));
        addButton(8, height - 24, foldWidth, CONTROL_HEIGHT, width < 420 ? "− All" : "Collapse all", () -> { collapsePage(); scroll = 0; rebuild(); })
                .setTooltip(Tooltip.create(Component.literal("Collapse all sections on this page.")));
        addButton(12 + foldWidth, height - 24, foldWidth, CONTROL_HEIGHT, width < 420 ? "+ All" : "Expand all", () -> { collapsed.clear(); scroll = 0; rebuild(); })
                .setTooltip(Tooltip.create(Component.literal("Expand all sections on this page.")));
        FlatButton help = addButton(16 + foldWidth * 2, height - 24, 22, CONTROL_HEIGHT, "?", () -> { helpExpanded = !helpExpanded; rebuild(); });
        help.selected = helpExpanded;
        help.setTooltip(Tooltip.create(Component.literal("Show/hide full help for the selected field or page. Field tooltips remain available.")));
        addButton(actionX + (actionWidth + 4) * 2, height - 24, actionWidth, CONTROL_HEIGHT, "Cancel", this::onClose);
        int indexVisible = Math.max(1, (bottom - top) / INDEX_HEIGHT);
        indexScroll = Math.clamp(indexScroll, 0, Math.max(0, sections.size() - indexVisible));
        for (int index = indexScroll; index < Math.min(sections.size(), indexScroll + indexVisible); index++) {
            Section section = sections.get(index);
            boolean phaseParent = section.path.equals("phases");
            boolean phaseChild = section.path.startsWith("phases.");
            boolean lootChild = section.path.startsWith("loot.");
            boolean subParent = sequencePage(section.path);
            boolean rewardParent = section.path.equals("loot.entries");
            int indent = lootChild ? section.path.equals("loot.entries") ? 12 : 24 : section.path.split("\\.").length == 4 && phaseChild ? 36 : subParent ? 24 : phaseChild ? 12 : 0;
            String title = rewardParent ? (lootEntriesExpanded ? "− " : "+ ") + section.label : phaseParent ? (phasesExpanded ? "− " : "+ ") + section.label
                    : subParent ? (subphasesExpanded.contains(section.path) ? "− " : "+ ") + section.label : section.label;
            FlatButton button = addButton(8 + indent, top + (index - indexScroll) * INDEX_HEIGHT, indexWidth - indent, CONTROL_HEIGHT, title, () -> {
                if (phaseParent) phasesExpanded = !phasesExpanded;
                if (rewardParent) lootEntriesExpanded = !lootEntriesExpanded;
                if (subParent && !subphasesExpanded.add(section.path)) subphasesExpanded.remove(section.path);
                selectPage(section.path);
            });
            button.selected = selected.equals(section.path) || rewardParent && lootEntriesExpanded || phaseParent && phasesExpanded || subParent && subphasesExpanded.contains(section.path);
            button.nested = phaseChild || lootChild;
            button.setTooltip(Tooltip.create(Component.literal(phaseParent
                    ? "Manage phases; click to " + (phasesExpanded ? "hide" : "show") + " phase pages in the index."
                    : section.label)));
        }
        if (selected.equals("__preview")) previewWidgets();
        else if (selected.equals("loot.entries")) lootEntryWidgets();
        else if (selected.equals("phases") || sequencePage(selected)) phaseWidgets();
        else {
            if (selected.equals("base")) {
                for (var entry : draft.entrySet()) if (!entry.getValue().isJsonObject() && visibleField(entry.getKey())) rows.add(new Row(entry.getKey(), label(entry.getKey()), 0, false));
                for (String root : List.of("attacks", "targeting", "warden_effects")) addGroup(root, label(root), 0);
            } else if (selected.equals("sounds")) {
                addRows(draft.getAsJsonObject("sounds"), "sounds", 0);
                addGroup("music", "Music", 0); addGroup("voice_lines", "Voice lines", 0);
                int phaseIndex = 1;
                for (String key : draft.getAsJsonObject("phases").keySet())
                    addGroup("phases." + key + ".sounds", "Phase " + phaseIndex++ + " sounds", 0);
            } else if (selected.equals("boss_bar.area_effects")) {
                addGroup("boss_bar.area_effects", "Beacon effects", 0);
                addGroup("boss_bar.player_health", "Fight health", 0);
                addGroup("boss_bar.reusable_potion", "Reusable potion", 0);
            } else addRows(currentObject(), selected, 0);
            formWidgets(0, contentWidth, false);
        }
        if (busy) children().forEach(child -> { if (child instanceof AbstractWidget widget && widget != saveButton) widget.active = false; });
    }

    private void pickerWidgets() {
        FlatButton templates = addButton(12, 36, 98, CONTROL_HEIGHT, "Templates", () -> { pickPresets = false; scroll = 0; rebuild(); });
        FlatButton presets = addButton(116, 36, 98, CONTROL_HEIGHT, "Presets", () -> { pickPresets = true; scroll = 0; rebuild(); });
        templates.selected = !pickPresets; presets.selected = pickPresets;
        EditBox search = ClockworkBossEditorTheme.input(font, 12, 60, width - 24, Component.literal("Filter names"));
        search.setMaxLength(64); search.setValue(filter); search.setHint(Component.literal("Filter names…"));
        // Refresh result buttons only; replacing a focused search box mid-keystroke would move its caret.
        search.setResponder(value -> { filter = value; scroll = 0; pickerEntries(); });
        addRenderableWidget(search);
        editPaths.put(search, "search");
        if ("search".equals(focusPath)) { setInitialFocus(search); search.setCursorPosition(Math.min(focusCursor, filter.length())); }
        pickerEntries();
    }

    private void pickerEntries() {
        pickerButtons.forEach(this::removeWidget); pickerButtons.clear(); labels.clear();
        List<String> names = new ArrayList<>();
        JsonElement list = catalog.get(pickPresets ? "presets" : "templates");
        if (list != null && list.isJsonArray()) for (JsonElement item : list.getAsJsonArray()) if (item.isJsonPrimitive() && item.getAsString().toLowerCase(java.util.Locale.ROOT).contains(filter.toLowerCase(java.util.Locale.ROOT))) names.add(item.getAsString());
        int visible = Math.max(1, (height - 50 - 84 + 4) / 22);
        scroll = Math.clamp(scroll, 0, Math.max(0, names.size() - visible)); fullHeight = names.size();
        for (int index = scroll; index < Math.min(names.size(), scroll + visible); index++) {
            String file = names.get(index);
            FlatButton button = addButton(12, 84 + (index - scroll) * 22, width - 24, CONTROL_HEIGHT, file, () -> {
                if (!busy) { busy = true; status = "Loading " + file + "…"; ClockworkBossEditorClient.send(new ClockworkBossEditorPayload(token, "open", pickPresets, file, "", "")); }
            });
            button.active = !busy;
            pickerButtons.add(button);
        }
        labels.add(new Label(names.isEmpty() ? "No matching files." : "Choose a file to edit. Presets stay inactive.", 12, height - 42, width - 24, true));
    }

    /** Build the index from the draft. Only an open Manage phases branch exposes
     * its indented child pages; its own page retains add/remove/order controls. */
    private void buildSections() {
        sections.clear(); sections.add(new Section("base", "Base settings"));
        sections.add(new Section("__preview", "Skin preview / eyes"));
        for (String root : List.of("spawn", "sounds", "loot", "dormant_darkness", "activation", "boss_bar", "phases")) {
            if (!draft.has(root) || !draft.get(root).isJsonObject()) continue;
            sections.add(new Section(root, switch (root) { case "dormant_darkness" -> "Dormant darkness"; case "phases" -> "Manage phases"; case "boss_bar" -> "Arena / boss bar"; default -> label(root); }));
            if (root.equals("loot") && effectiveText("loot.source", "table").equals("entries")) {
                sections.add(new Section("loot.entries", "Reward entries"));
                int reward = 1;
                if (lootEntriesExpanded) for (String key : draft.getAsJsonObject("loot").getAsJsonObject("entries").keySet())
                    sections.add(new Section("loot.entries." + key, "Reward " + reward++));
            }
            if (root.equals("phases") && phasesExpanded) {
                int index = 1;
                for (String key : draft.getAsJsonObject("phases").keySet()) {
                    String parent = "phases." + key;
                    sections.add(new Section(parent, "Phase " + index++ + ": " + label(key)));
                    for (String catalog : List.of("subphases", "roars")) {
                        String childrenPath = parent + "." + catalog;
                        sections.add(new Section(childrenPath, catalog.equals("roars") ? "Roars between" : "Subphases"));
                        if (subphasesExpanded.contains(childrenPath)) {
                            int childIndex = 1;
                            for (String child : draft.getAsJsonObject("phases").getAsJsonObject(key).getAsJsonObject(catalog).keySet())
                                sections.add(new Section(childrenPath + "." + child, (catalog.equals("roars") ? "Roar " : "Sub ") + childIndex++ + ": " + label(child)));
                        }
                    }
                }
            }
            if (root.equals("boss_bar") && draft.getAsJsonObject(root).has("area_effects")) sections.add(new Section("boss_bar.area_effects", "Arena effects"));
        }
        Set<String> grouped = Set.of("skin", "attacks", "targeting", "warden_effects", "music", "voice_lines", clockwork.bosses.ClockworkBossActionEditor.CATALOG);
        for (var entry : draft.entrySet()) if (entry.getValue().isJsonObject() && !grouped.contains(entry.getKey()) && sections.stream().noneMatch(section -> section.path.equals(entry.getKey()))) sections.add(new Section(entry.getKey(), label(entry.getKey())));
        if (sections.stream().noneMatch(section -> section.path.equals(selected))) selected = "phases";
    }

    private JsonObject currentObject() { return selected.equals("base") ? draft : selected.equals("__preview") ? draft.getAsJsonObject("skin") : ClockworkBossEditorSchema.get(draft, selected).getAsJsonObject(); }

    /** Entering a page starts its nested sections folded; draft values survive navigation. */
    private void selectPage(String path) {
        if (path.startsWith("loot.entries.")) lootEntriesExpanded = true;
        cancelOrderDrag();
        // Direct Edit/add navigation also reveals the phase's sidebar parent.
        if (path.startsWith("phases.")) phasesExpanded = true;
        String[] parts = path.split("\\.");
        if (parts.length == 4 && parts[0].equals("phases") && List.of("subphases", "roars").contains(parts[2]))
            subphasesExpanded.add(parts[0] + "." + parts[1] + "." + parts[2]);
        selected = path; selectedHelp = sectionHelp(path); scroll = 0; focusPath = null;
        confirmingClose = false; buildSections(); collapsePage(); rebuild();
    }
    private void collapsePage() {
        collapsed.clear();
        collectObjectPaths(draft, "", collapsed);
        collapsed.add("skin"); collapsed.add("music"); collapsed.add("voice_lines");
    }
    private void addGroup(String path, String title, int depth) {
        JsonElement object = ClockworkBossEditorSchema.get(draft, path);
        if (object == null || !object.isJsonObject()) return;
        rows.add(new Row(path, title, depth, true));
        if (!collapsed.contains(path)) addRows(object.getAsJsonObject(), path, depth + 1);
    }

    /** Hide fields unused by the chosen geometry, preserving their values for switching back. */
    private boolean visibleField(String path) {
        if (path.equals(clockwork.bosses.ClockworkBossTemplateFormat.VERSION_FIELD)) return false;
        // Sequence controls live beside their ordered lists, not duplicated on
        // the parent settings page. Roar entries contain only their sound fields.
        if (path.endsWith(".subphase_order") || path.endsWith(".roar_between_subphases")) return false;
        if (path.equals("activation.shape")) return false; // Entry area is always a box; type selects geometry.
        var visibility = clockwork.bosses.ClockworkBossTemplateVisibility.rule(path);
        return visibility == null || visibility.matches(this::effectiveText);
    }
    private String effectiveText(String path, String fallback) {
        JsonElement value = ClockworkBossEditorSchema.get(draft, path);
        return inputs.getOrDefault(path, value == null || value.isJsonNull() ? fallback : value.getAsString());
    }

    private void addRows(JsonObject object, String path, int depth) {
        for (var entry : object.entrySet()) {
            String child = path.isEmpty() ? entry.getKey() : path + "." + entry.getKey();
            if (!visibleField(child)) continue;
            if (selected.equals("boss_bar") && List.of("boss_bar.area_effects", "boss_bar.player_health", "boss_bar.reusable_potion").contains(child)) continue;
            if (selected.startsWith("phases.") && child.endsWith(".sounds")) continue; // All audio is on Sounds.
            if (entry.getValue().isJsonObject()) {
                rows.add(new Row(child, label(entry.getKey()), depth, true));
                if (sequencePage(child) || child.equals("loot.entries")) continue; // Dedicated management/child pages, never inline values.
                if (!collapsed.contains(child)) addRows(entry.getValue().getAsJsonObject(), child, depth + 1);
            } else {
                String fieldLabel = clockwork.bosses.ClockworkBossActionEditor.label(draft, child, label(entry.getKey()));
                if (path.startsWith("loot.entries.")) fieldLabel = switch (entry.getKey()) {
                    case "type" -> "Reward type"; case "item" -> "Item";
                    case "min_count" -> "Minimum quantity"; case "max_count" -> "Maximum quantity";
                    case "chance" -> "Drop chance"; default -> fieldLabel;
                };
                if (entry.getKey().equals("minimum_health_percent") && selected.startsWith("phases.")) fieldLabel = "Leave phase at health %";
                rows.add(new Row(child, fieldLabel, depth, false));
            }
        }
    }

    /**
     * Measure once per rebuild. Wide forms pair sibling number/option fields;
     * filenames, prose and preserved structured values keep the full width.
     * Preview coordinate fields can pair sooner because their labels/values are short.
     */
    private List<RowLayout> layoutRows(int formWidth, boolean previewCoordinates) {
        List<RowLayout> layout = new ArrayList<>();
        boolean columns = formWidth >= (previewCoordinates ? 252 : 560);
        int columnWidth = (formWidth - FIELD_GAP) / 2, offset = 0;
        for (int index = 0; index < rows.size(); index++) {
            Row row = rows.get(index);
            Row next = index + 1 < rows.size() ? rows.get(index + 1) : null;
            if (columns && next != null && row.depth == next.depth
                    && pairable(row, previewCoordinates) && pairable(next, previewCoordinates)) {
                int height = Math.max(fieldHeight(row, columnWidth), fieldHeight(next, columnWidth));
                layout.add(new RowLayout(row, 0, columnWidth, offset, height));
                layout.add(new RowLayout(next, columnWidth + FIELD_GAP, formWidth - columnWidth - FIELD_GAP, offset, height));
                offset += height; index++;
            } else {
                int height = fieldHeight(row, formWidth);
                layout.add(new RowLayout(row, 0, formWidth, offset, height));
                offset += height;
            }
        }
        return layout;
    }

    private boolean pairable(Row row, boolean previewCoordinates) {
        if (row.heading || row.path.startsWith("__")) return false;
        if (previewCoordinates) return row.path.startsWith("skin.glowing_eyes.") || row.path.startsWith("skin.glowing_eyebrows.")
                || row.path.startsWith("skin.glowing_pupils.");
        var field = ClockworkBossEditorSchema.field(row.path, ClockworkBossEditorSchema.get(draft, row.path), draft);
        return !field.choices().isEmpty() || field.kind() != ClockworkBossEditorSchema.Kind.TEXT
                && field.kind() != ClockworkBossEditorSchema.Kind.JSON;
    }

    private int fieldHeight(Row row, int availableWidth) {
        if (row.heading || row.path.equals("__skin_load")) return GROUP_HEIGHT;
        if (row.path.equals("__eye_controls")) return 114;
        if (row.path.equals("__brow_controls") || row.path.equals("__pupil_controls")) return 90;
        int usableWidth = availableWidth - Math.min(row.depth, 6) * 10;
        return (usableWidth < 300 ? 44 : 32) + (darknessNote(row.path).isEmpty() ? 0 : 10);
    }

    /** Widget creation and scrolling share measured geometry; only fully visible fields own controls. */
    private void formWidgets(int prefixHeight, int formWidth, boolean previewCoordinates) {
        List<RowLayout> layout = layoutRows(formWidth, previewCoordinates);
        fullHeight = prefixHeight + (layout.isEmpty() ? 0 : layout.getLast().offset + layout.getLast().height);
        scroll = Math.clamp(scroll, 0, Math.max(0, fullHeight - (bottom - top)));
        int savedX = contentX, savedWidth = contentWidth;
        try {
            for (RowLayout placement : layout) {
                Row row = placement.row;
                int y = top + prefixHeight + placement.offset - scroll;
                contentX = savedX + placement.x; contentWidth = placement.width;
                // Cross pads may span a short viewport; each individual button clips itself.
                if (row.path.equals("__eye_controls")) { positionControls(y, formWidth, FacePart.EYES); continue; }
                if (row.path.equals("__brow_controls")) { positionControls(y, formWidth, FacePart.BROWS); continue; }
                if (row.path.equals("__pupil_controls")) { positionControls(y, formWidth, FacePart.PUPILS); continue; }
                if (y < top || y + placement.height > bottom) continue;
                if (row.heading) {
                    int indent = Math.min(row.depth, 6) * 10;
                    FlatButton button = addButton(contentX + indent, y + 2, contentWidth - indent, CONTROL_HEIGHT,
                            (collapsed.contains(row.path) ? "+ " : "− ") + row.label, () -> {
                                if (row.path.equals("loot.entries")) { selectPage(row.path); return; }
                                if (sequencePage(row.path)) { subphasesExpanded.add(row.path); selectPage(row.path); return; }
                                if (!collapsed.add(row.path)) collapsed.remove(row.path);
                                focusPath = null; rebuild();
                            });
                    button.selected = !collapsed.contains(row.path); button.nested = row.depth > 0;
                    button.setTooltip(Tooltip.create(Component.literal(sectionHelp(row.path))));
                } else if (row.path.equals("__skin_load")) {
                    addButton(contentX + 10, y + 2, contentWidth - 10, CONTROL_HEIGHT, row.label, this::refreshPreview);
                } else fieldWidgets(row, y);
            }
        } finally { contentX = savedX; contentWidth = savedWidth; }
    }

    /** Labels/ranges stay outside the text box; full field purpose is available on hover and focus. */
    private void fieldWidgets(Row row, int y) {
        int savedX = contentX, savedWidth = contentWidth;
        int indent = Math.min(row.depth, 6) * 10;
        contentX += indent; contentWidth -= indent;
        try { fieldContents(row, y); }
        finally { contentX = savedX; contentWidth = savedWidth; }
    }
    private void fieldContents(Row row, int y) {
        JsonElement value = ClockworkBossEditorSchema.get(draft, row.path);
        var field = ClockworkBossEditorSchema.field(row.path, value, draft);
        String text = inputs.getOrDefault(row.path, displayValue(row.path, value));
        boolean narrow = contentWidth < 300;
        int labelWidth = narrow ? contentWidth : Math.max(120, contentWidth / 2 - 12);
        labels.add(new Label(row.label, contentX, y + 2, labelWidth, false));
        String shortNote = darknessNote(row.path);
        int noteHeight = shortNote.isEmpty() ? 0 : 10;
        if (!shortNote.isEmpty()) labels.add(new Label(shortNote, contentX, y + 13, labelWidth, true));
        String range = percentage(row.path) ? "0 to 100%" : field.range();
        boolean finalCutoff = row.path.endsWith(".minimum_health_percent") && row.path.equals("phases." + draft.getAsJsonObject("phases").keySet().stream().toList().getLast() + ".minimum_health_percent");
        if (finalCutoff) { labels.add(new Label("Final phase: covers remaining health", contentX, y + 16, contentWidth, true)); return; }
        if (range.isEmpty()) range = field.nullable() ? "Base = inherit; blank file = disabled" : field.kind() == ClockworkBossEditorSchema.Kind.TEXT ? "Text / filename" : "Choose an option";
        labels.add(new Label(range, contentX, y + 13 + noteHeight, labelWidth, true));
        int x = narrow ? contentX : contentX + labelWidth + 12;
        int inputY = (narrow ? y + 25 : y + 5) + noteHeight;
        int inputWidth = narrow ? contentWidth : contentWidth - labelWidth - 12;
        String help = row.path.matches("loot\\.entries\\.[^.]+\\.chance") ? "Drop chance: 0 to 100%. Each reward rolls independently once. 0 never drops; 100 always drops. Stored as 0–1."
                : percentage(row.path) ? row.label + ": 0 to 100%. 0 switches only at Max attacks; 100 switches at the first eligible completion after minimum time/attacks. Stored as 0–1 in the file."
                : row.label + (field.range().isEmpty() ? "" : ": " + field.range()) + "\n" + field.help();
        if (field.kind() == ClockworkBossEditorSchema.Kind.JSON) {
            labels.add(new Label("Existing structured value (preserved)", x, inputY + 5, inputWidth, true)); return;
        }
        if (!field.choices().isEmpty()) {
            List<String> options = new ArrayList<>(field.choices());
            if (field.nullable()) options.addFirst("<inherit>");
            FlatButton choice = addButton(x, inputY, inputWidth, CONTROL_HEIGHT, optionLabel(row.path, text) + " ▾", () -> {
                selectedHelp = help; dropdown = new Dropdown(row.path, options, x, inputY + CONTROL_HEIGHT + 2, inputWidth, 0);
            });
            choice.setTooltip(Tooltip.create(Component.literal(help)));
        } else {
            boolean inheritsParent = childField(row.path);
            boolean rewardItem = row.path.matches("loot\\.entries\\.[^.]+\\.item");
            int inheritWidth = rewardItem ? 44 : field.nullable() ? inheritsParent ? 54 : 40 : 0;
            EditBox input = ClockworkBossEditorTheme.input(font, x, inputY, inputWidth - inheritWidth, Component.literal(help));
            input.setMaxLength(row.path.endsWith("announcement.text") ? 80 : 512);
            input.setValue(text.equals("<inherit>") ? "" : text);
            if (text.equals("<inherit>")) input.setHint(Component.literal(inheritsParent ? "Inherit parent" : "Inherit base"));
            input.setTooltip(Tooltip.create(Component.literal(help)));
            input.setResponder(changed -> {
                inputs.put(row.path, changed); selectedHelp = help; focusPath = row.path; confirmingClose = false;
                if (row.path.startsWith("skin.glowing_")) previewMetadataDirty = true;
                if (saveButton != null) saveButton.active = !busy && dirty();
            });
            addRenderableWidget(input);
            if (rewardItem) addButton(x + inputWidth - 42, inputY, 42, CONTROL_HEIGHT, "Pick", () -> openLootPicker(row.path));
            editPaths.put(input, row.path);
            if (row.path.equals(focusPath)) { setInitialFocus(input); input.setCursorPosition(Math.min(focusCursor, input.getValue().length())); }
            if (field.nullable()) addButton(x + inputWidth - inheritWidth + 2, inputY, inheritWidth - 2, CONTROL_HEIGHT, inheritsParent ? "Parent" : "Base", () -> { inputs.put(row.path, "<inherit>"); selectedHelp = help; focusPath = null; confirmingClose = false; rebuild(); });
        }
    }

    /** Check structure, not a suffix: a user may name a health phase or entry
     * "roars"/"subphases" without turning its settings page into a catalog. */
    private static boolean sequencePage(String path) { return path.matches("phases\\.[^.]+\\.(subphases|roars)"); }
    private static boolean childField(String path) { return path.matches("phases\\.[^.]+\\.(subphases|roars)\\.[^.]+\\..+"); }

    /** Native controls plus drag handles edit JSON insertion order. Main phases
     * retain at least one entry; attack/roar catalogs may be emptied to disable them. */
    private void phaseWidgets() {
        boolean sub = sequencePage(selected), roar = selected.endsWith(".roars");
        String root = selected;
        var phases = ClockworkBossEditorSchema.get(draft, root).getAsJsonObject();
        List<String> names = phases.keySet().stream().toList();
        phaseListHeader = sub ? 44 : 0;
        fullHeight = phaseListHeader + names.size() * ENTRY_HEIGHT + GROUP_HEIGHT;
        scroll = Math.clamp(scroll, 0, Math.max(0, fullHeight - (bottom - top)));
        if (sub) {
            String parent = root.substring(0, root.lastIndexOf('.'));
            int y = top - scroll;
            if (y >= top && y + CONTROL_HEIGHT <= bottom) {
                String path = parent + ".subphase_order";
                var button = addButton(contentX, y, contentWidth, CONTROL_HEIGHT, "Order: " + label(effectiveText(path, "sequential")) + " ▾",
                        () -> dropdown = new Dropdown(path, List.of("sequential", "random"), contentX, y + CONTROL_HEIGHT + 2, contentWidth, 0));
                button.setTooltip(Tooltip.create(Component.literal(sectionHelp(path))));
            }
            if (y + 22 >= top && y + 22 + CONTROL_HEIGHT <= bottom) {
                String path = parent + ".roar_between_subphases";
                boolean enabled = effectiveText(path, "true").equals("true");
                var button = addButton(contentX, y + 22, contentWidth, CONTROL_HEIGHT, "Roar between: " + (enabled ? "On" : "Off"), () -> choose(path, Boolean.toString(!enabled)));
                button.setTooltip(Tooltip.create(Component.literal("A roar plays after each completed subphase, including the loop boundary. Empty roar list skips interludes.")));
            }
        }
        for (int index = 0; index < names.size(); index++) {
            String key = names.get(index); int position = index;
            int y = top + phaseListHeader + index * ENTRY_HEIGHT - scroll;
            if (y < top || y + ENTRY_HEIGHT > bottom) continue;
            dragRows.add(new DragRow(key, position, y));
            labels.add(new Label((roar ? "Roar " : sub ? "Subphase " : "Phase ") + (index + 1) + ": " + label(key), contentX + 24, y, contentWidth - 24, false));
            int buttonWidth = Math.max(30, (contentWidth - 36) / 4), x = contentX + 24;
            addButton(x, y + 14, buttonWidth, CONTROL_HEIGHT, "Edit", () -> selectPage(root + "." + key));
            FlatButton up = addButton(x + buttonWidth + 4, y + 14, buttonWidth, CONTROL_HEIGHT, "Up", () -> movePhase(root, key, position - 1)); up.active = index > 0;
            FlatButton down = addButton(x + (buttonWidth + 4) * 2, y + 14, buttonWidth, CONTROL_HEIGHT, "Down", () -> movePhase(root, key, position + 1)); down.active = index < names.size() - 1;
            FlatButton remove = addButton(x + (buttonWidth + 4) * 3, y + 14, buttonWidth, CONTROL_HEIGHT, "Remove", () -> {
                phases.remove(key); inputs.keySet().removeIf(path -> path.startsWith(root + "." + key + ".")); buildSections(); confirmingClose = false; rebuild();
            }); remove.active = sub || names.size() > 1;
        }
        int y = top + phaseListHeader + names.size() * ENTRY_HEIGHT - scroll;
        if (y >= top && y + GROUP_HEIGHT <= bottom) {
            FlatButton add = addButton(contentX, y, contentWidth, CONTROL_HEIGHT, roar ? "+ Add roar" : sub ? "+ Add subphase" : "+ Add phase", () -> { if (sub) addSubphase(root); else addPhase(); });
            add.active = names.size() < ClockworkBossPhase.MAX_PHASES;
        }
        selectedHelp = roar ? "Drag ↕ or use Up/Down. Sequential cycles this list independently: one roar repeats, two alternate. Random picks a roar per transition; repeats are allowed. Empty skips roars."
                : sub ? "Drag ↕ or use Up/Down. Sequential follows this order; Random visits every subphase once before reshuffling. Attack entries count hits; Recovery entries pause for a duration. Configure interludes in Roars between."
                : "Drag ↕ or use Up/Down. Set descending health cutoffs after reordering. Up to 16 phases; new phases inherit base settings.";
    }

    private void movePhase(String root, String key, int position) {
        JsonObject phases = ClockworkBossEditorSchema.get(draft, root).getAsJsonObject();
        if (!phases.has(key) || position < 0 || position >= phases.size()) return;
        List<String> order = new ArrayList<>(phases.keySet()); order.remove(key); order.add(position, key);
        JsonObject reordered = new JsonObject(); for (String entry : order) reordered.add(entry, phases.get(entry));
        ClockworkBossEditorSchema.put(draft, root, reordered); buildSections(); confirmingClose = false; rebuild();
        status = root.equals("loot.entries") ? "Reward order changed; chances stay the same." : root.equals("phases") ? "Order changed. Adjust leaving-health cutoffs before saving." : root.endsWith(".roars") ? "Roar order changed." : "Subphase order changed.";
    }

    private void addSubphase(String root) {
        JsonObject children = ClockworkBossEditorSchema.get(draft, root).getAsJsonObject();
        if (children.size() >= 16) return;
        boolean roar = root.endsWith(".roars"); String prefix = roar ? "roar_" : "subphase_";
        int index = children.size() + 1; while (children.has(prefix + index)) index++;
        String key = prefix + index; children.add(key, roar ? ClockworkBossEditorSchema.newRoar() : ClockworkBossEditorSchema.newSubphase());
        selectPage(root + "." + key); status = "Added " + key + ". Null fields inherit its parent phase.";
    }

    private void addPhase() {
        JsonObject phases = draft.getAsJsonObject("phases");
        if (phases.size() >= ClockworkBossPhase.MAX_PHASES) return;
        String key = "phase_" + (phases.size() + 1);
        for (int suffix = phases.size() + 1; phases.has(key); suffix++) key = "phase_" + (suffix + 1);
        List<String> names = phases.keySet().stream().toList();
        String lastPath = "phases." + names.getLast() + ".minimum_health_percent";
        // The old final phase needs its first leaving threshold. Keep earlier thresholds unchanged.
        double prior = names.size() < 2 ? 100 : cutoff(names.get(names.size() - 2), 50);
        inputs.put(lastPath, Double.toString(prior / 2));
        phases.add(key, ClockworkBossEditorSchema.newPhase());
        phasesExpanded = true;
        selected = "phases." + key; scroll = 0; buildSections(); confirmingClose = false; collapsePage(); rebuild();
        status = "Added " + key + ". The previous final phase leaves at " + prior / 2 + "% health.";
    }

    /** A bounded entry list uses the same scroll layout as phases. Its structural patch contains
     * stable keys only; the server constructs defaults and validates every item/count/chance. */
    private void lootEntryWidgets() {
        var entries = draft.getAsJsonObject("loot").getAsJsonObject("entries");
        List<String> keys = entries.keySet().stream().toList();
        fullHeight = keys.size() * ENTRY_HEIGHT + GROUP_HEIGHT;
        scroll = Math.clamp(scroll, 0, Math.max(0, fullHeight - (bottom - top)));
        for (int index = 0; index < keys.size(); index++) {
            String key = keys.get(index); int position = index, y = top + index * ENTRY_HEIGHT - scroll;
            if (y < top || y + ENTRY_HEIGHT > bottom) continue;
            String path = "loot.entries." + key;
            labels.add(new Label("Reward " + (index + 1) + ": " + effectiveText(path + ".item", "minecraft:diamond"), contentX, y, contentWidth, false));
            int size = Math.max(30, (contentWidth - 12) / 4);
            addButton(contentX, y + 14, size, CONTROL_HEIGHT, "Edit", () -> selectPage(path));
            var up = addButton(contentX + size + 4, y + 14, size, CONTROL_HEIGHT, "Up", () -> movePhase("loot.entries", key, position - 1)); up.active = position > 0;
            var down = addButton(contentX + (size + 4) * 2, y + 14, size, CONTROL_HEIGHT, "Down", () -> movePhase("loot.entries", key, position + 1)); down.active = position + 1 < keys.size();
            addButton(contentX + (size + 4) * 3, y + 14, size, CONTROL_HEIGHT, "Remove", () -> {
                entries.remove(key); inputs.keySet().removeIf(field -> field.startsWith(path + "."));
                buildSections(); confirmingClose = false; rebuild();
            });
        }
        int y = top + keys.size() * ENTRY_HEIGHT - scroll;
        if (y >= top && y + GROUP_HEIGHT <= bottom) {
            var add = addButton(contentX, y, contentWidth, CONTROL_HEIGHT, "+ Add item…", () -> openLootPicker(null));
            add.active = entries.size() < ClockworkBossLootEntries.MAX_ENTRIES;
        }
        selectedHelp = "Each item rolls independently once on death. Edit sets quantity and chance. Up to 32 entries; ordering does not change drop chances. Empty means no custom drops.";
    }

    /** Keep the same Screen/token while choosing, retaining all pending text and server revision. */
    private void openLootPicker(String itemPath) {
        if (busy) return;
        // 26.3 binds item components when a world joins. A disconnect must not create
        // item stacks from a title-screen registry snapshot or throw out this draft.
        if (minecraft.level == null || minecraft.player == null) { message("Join a world before choosing reward items."); return; }
        cancelOrderDrag(); focusPath = null;
        try {
            lootPicker = new ClockworkBossLootPicker(font, id -> {
                lootPicker = null;
                if (itemPath == null) {
                    var entries = draft.getAsJsonObject("loot").getAsJsonObject("entries");
                    if (entries.size() >= ClockworkBossLootEntries.MAX_ENTRIES) { rebuild(); return; }
                    int number = 1; while (entries.has("reward_" + number)) number++;
                    String key = "reward_" + number;
                    var entry = ClockworkBossLootEntries.newEntry(); entry.addProperty("item", id.toString());
                    entries.add(key, entry); selectPage("loot.entries." + key);
                    status = "Item added to draft. Set quantity/chance, then Save.";
                } else {
                    inputs.put(itemPath, id.toString()); confirmingClose = false; rebuild();
                }
            }, this::closeLootPicker);
        } catch (RuntimeException failure) {
            // A mod can fail while constructing its creative tab. Keep the operator's
            // draft and manual item-ID input usable instead of crashing the client.
            org.slf4j.LoggerFactory.getLogger("clockwork_bosses").warn("Could not open reward item picker", failure);
            message("Could not load item picker. Your draft is intact; item IDs can still be entered manually.");
            return;
        }
        rebuild();
    }
    private void closeLootPicker() { lootPicker = null; rebuild(); }
    private double cutoff(String key, double fallback) {
        String path = "phases." + key + ".minimum_health_percent";
        try { return Double.parseDouble(inputs.getOrDefault(path, ClockworkBossEditorSchema.value(ClockworkBossEditorSchema.get(draft, path)))); }
        catch (RuntimeException invalid) { return fallback; }
    }

    /** Keep the model visible while skin/eye subsections fold and scroll independently. */
    private void previewWidgets() {
        boolean split = contentWidth >= 440;
        int panelWidth = split ? contentWidth / 2 - 8 : contentWidth;
        int availableHeight = bottom - top - 44;
        // A stacked preview reserves roughly half the viewport for its form.
        // Side-by-side layouts can use more height without pushing controls offscreen.
        int modelHeight = Math.max(40, Math.min(split ? 180 : 120,
                split ? availableHeight : Math.min(availableHeight, (bottom - top) / 2 - 22)));
        previewX = split ? contentX + panelWidth + 16 : contentX;
        previewWidth = panelWidth; previewHeight = modelHeight;
        rows.add(new Row("skin", "Skin", 0, true));
        if (!collapsed.contains("skin")) {
            rows.add(new Row("skin.type", "Texture source", 1, false));
            String type = effectiveText("skin.type", "player");
            if (type.equals("player")) rows.add(new Row("skin.name", "Player name", 1, false));
            if (type.equals("custom")) rows.add(new Row("skin.file", "PNG filename", 1, false));
            rows.add(new Row("__skin_load", "Load / refresh skin", 1, false));
        }
        rows.add(new Row("skin.glowing_eyes", "Glowing eyes", 0, true));
        if (!collapsed.contains("skin.glowing_eyes")) {
            for (String key : List.of("left_x", "left_y", "right_x", "right_y", "scale_x", "scale_y"))
                rows.add(new Row("skin.glowing_eyes." + key, label(key), 1, false));
            rows.add(new Row("__eye_controls", "Position eyes", 1, false));
        }
        rows.add(new Row("skin.glowing_eyebrows", "Glowing eyebrows", 0, true));
        if (!collapsed.contains("skin.glowing_eyebrows")) {
            for (String key : List.of("left_x", "left_y", "right_x", "right_y"))
                rows.add(new Row("skin.glowing_eyebrows." + key, label(key), 1, false));
            rows.add(new Row("__brow_controls", "Position eyebrows", 1, false));
        }
        rows.add(new Row("skin.glowing_pupils", "Pupils", 0, true));
        if (!collapsed.contains("skin.glowing_pupils")) {
            for (String key : List.of("enabled", "scale_x", "scale_y", "left_x", "left_y", "right_x", "right_y"))
                rows.add(new Row("skin.glowing_pupils." + key, label(key), 1, false));
            rows.add(new Row("__pupil_controls", "Position pupils", 1, false));
        }
        int modelBlock = split ? 0 : modelHeight + 46;
        formWidgets(modelBlock, panelWidth, true);
        previewY = split ? top : top - scroll;
        int buttonY = previewY + modelHeight + 4;
        if (buttonY >= top && buttonY + 40 <= bottom) {
            int half = (panelWidth - 4) / 2;
            addButton(previewX, buttonY, half, CONTROL_HEIGHT, headPreview ? "Show body" : "Show head", () -> { headPreview = !headPreview; rebuild(); });
            addButton(previewX + half + 4, buttonY, half, CONTROL_HEIGHT, "Face forward", () -> { previewYaw = previewPitch = 0; });
            List<String> phaseNames = draft.getAsJsonObject("phases").keySet().stream().toList();
            if (!phaseNames.contains(previewPhase)) previewPhase = phaseNames.getFirst();
            addButton(previewX, buttonY + 22, panelWidth, CONTROL_HEIGHT, "Glow: " + label(previewPhase) + " ▾",
                    () -> dropdown = new Dropdown("__phase", phaseNames, previewX, buttonY + 42, panelWidth, 0));
        }
        selectedHelp = "Preview face glow uses the phase hue at full brightness. Arrows move Both/Left/Right; eye spacing is separate. Pupils use eye-local offsets (-1 = automatic); Reset restores automatic placement. Scale X/Y is independent: eye 1 = 2 pixels, pupil 1 = 1 pixel. Save stores changes.";
    }

    private String faceSelection(FacePart part) {
        return switch (part) { case EYES -> previewEye; case BROWS -> previewBrow; case PUPILS -> previewPupil; };
    }
    /** Shared cross pad moves the selected part; pupil offsets are always inside their owning eye. */
    private void positionControls(int y, int panelWidth, FacePart part) {
        String selectedPart = faceSelection(part);
        if (y >= top && y + CONTROL_HEIGHT <= bottom) addButton(contentX + 10, y, panelWidth - 10, CONTROL_HEIGHT,
                part.title + ": " + selectedPart + " ▾",
                () -> dropdown = new Dropdown(part.selector, List.of("Both", "Left", "Right"), contentX + 10, y + 20, panelWidth - 10, 0));
        int cell = Math.min(64, (panelWidth - 20) / 3), gap = 4;
        int centerX = contentX + (panelWidth - cell) / 2;
        eyeButton(centerX, y + 24, cell, "↑", () -> nudgeFace(part, 0, -1));
        eyeButton(centerX - cell - gap, y + 46, cell, "←", () -> nudgeFace(part, -1, 0));
        eyeButton(centerX, y + 46, cell, "Reset", () -> resetFace(part));
        eyeButton(centerX + cell + gap, y + 46, cell, "→", () -> nudgeFace(part, 1, 0));
        eyeButton(centerX, y + 68, cell, "↓", () -> nudgeFace(part, 0, 1));
        if (part == FacePart.EYES) {
            int half = (panelWidth - 16) / 2;
            eyeButton(contentX + 6, y + 92, half, "Narrower", () -> spaceEyes(false));
            eyeButton(contentX + half + 10, y + 92, half, "Wider", () -> spaceEyes(true));
        }
    }
    private void eyeButton(int x, int y, int buttonWidth, String text, Runnable action) {
        if (y >= top && y + CONTROL_HEIGHT <= bottom) addButton(x, y, buttonWidth, CONTROL_HEIGHT, text, action);
    }
    /** Reset positioning keeps X/Y scale; normalize default positions to the current eye dimensions. */
    private void resetFace(FacePart part) {
        String selection = faceSelection(part);
        boolean brows = part == FacePart.BROWS;
        var eyes = currentEyes();
        var defaults = new ClockworkBossEyes(3, 7, 11, 7, eyes.width(), eyes.height());
        var browDefaults = clockwork.bosses.ClockworkBossBrows.DEFAULT;
        String root = part.root;
        for (String side : List.of("left", "right")) {
            if (!selection.equals("Both") && !selection.equalsIgnoreCase(side)) continue;
            int x = brows ? (side.equals("left") ? browDefaults.leftX() : browDefaults.rightX()) : (side.equals("left") ? defaults.leftX() : defaults.rightX());
            int y = brows ? (side.equals("left") ? browDefaults.leftY() : browDefaults.rightY()) : (side.equals("left") ? defaults.leftY() : defaults.rightY());
            if (part == FacePart.PUPILS) x = y = -1; // Reset only position, keeping pupil size/enabled.
            inputs.put(root + side + "_x", Integer.toString(x)); inputs.put(root + side + "_y", Integer.toString(y));
        }
        focusPath = null; confirmingClose = false; rebuild();
    }

    private void choose(String path, String value) {
        if (path.equals("__eye")) previewEye = value;
        else if (path.equals("__brow")) previewBrow = value;
        else if (path.equals("__pupil")) previewPupil = value;
        else if (path.equals("__phase")) previewPhase = value;
        else if (clockwork.bosses.ClockworkBossActionEditor.typePath(path)) {
            String child = path.substring(0, path.length() - 5);
            clockwork.bosses.ClockworkBossActionEditor.changeType(draft, child, value);
            inputs.keySet().removeIf(key -> key.equals(path) || key.startsWith(child + ".action_settings."));
        } else inputs.put(path, value);
        if (path.equals("activation.type") && value.equals("player_enter_area")) inputs.put("activation.shape", "box");
        if (path.equals("loot.source")) buildSections();
        confirmingClose = false; focusPath = null; rebuild();
    }

    /** Movement edits scalars only; native metadata/mask generation receives bounded positions on Save. */
    private void nudgeFace(FacePart part, int horizontal, int vertical) {
        String selection = faceSelection(part);
        boolean brows = part == FacePart.BROWS;
        var eyes = currentEyes(); var eyebrows = currentBrows();
        var pupils = currentPupils();
        String root = part.root;
        for (String side : List.of("left", "right")) {
            if (!selection.equals("Both") && !selection.equalsIgnoreCase(side)) continue;
            int x = brows ? (side.equals("left") ? eyebrows.leftX() : eyebrows.rightX()) : (side.equals("left") ? eyes.leftX() : eyes.rightX());
            int y = brows ? (side.equals("left") ? eyebrows.leftY() : eyebrows.rightY()) : (side.equals("left") ? eyes.leftY() : eyes.rightY());
            int maxX = brows ? 12 : 16 - eyes.width(), maxY = brows ? 12 : 16 - eyes.height();
            if (part == FacePart.PUPILS) {
                boolean right = side.equals("right");
                x = pupils.xIn(eyes, right); y = pupils.yIn(eyes, right);
                maxX = eyes.width() - pupils.widthIn(eyes); maxY = eyes.height() - pupils.heightIn(eyes);
            }
            inputs.put(root + side + "_x", Integer.toString(Math.clamp(x + horizontal, 0, maxX)));
            inputs.put(root + side + "_y", Integer.toString(Math.clamp(y + vertical, 0, maxY)));
        }
        focusPath = null; confirmingClose = false; rebuild();
    }
    /** Symmetric spacing moves both eyes one pixel, retaining their size and vertical alignment. */
    private void spaceEyes(boolean wider) {
        var eyes = currentEyes(); int step = wider ? 1 : -1;
        int left = eyes.leftX() - step, right = eyes.rightX() + step;
        if (left < 0 || right > 16 - eyes.width() || left + eyes.width() > right) {
            message("Eye spacing reached the face boundary or the eyes would overlap."); return;
        }
        inputs.put("skin.glowing_eyes.left_x", Integer.toString(left));
        inputs.put("skin.glowing_eyes.right_x", Integer.toString(right));
        focusPath = null; confirmingClose = false; rebuild();
    }

    private void refreshPreview() {
        try {
            JsonObject config = draft.deepCopy();
            for (var entry : inputs.entrySet()) if (entry.getKey().startsWith("skin.")) {
                JsonElement value = ClockworkBossEditorSchema.get(config, entry.getKey());
                ClockworkBossEditorSchema.put(config, entry.getKey(), ClockworkBossEditorSchema.parse(entry.getKey(), entry.getValue(), value));
            }
            previewConfig = config; previewFailed = false; previewMetadataDirty = true;
            if (config.getAsJsonObject("skin").get("type").getAsString().equals("custom")) {
                String file = config.getAsJsonObject("skin").get("file").getAsString();
                if (file.isBlank()) { message("Enter a PNG filename from this file's skins folder."); return; }
                busy = true; status = "Requesting server PNG…"; rebuild();
                ClockworkBossEditorClient.send(new ClockworkBossEditorPayload(token, "skin", preset, name, revision, file));
            } else { status = "Preview loading. Player skins may take a few seconds."; }
        } catch (RuntimeException invalid) { message(invalid.getMessage()); }
    }

    private void drawPreview(GuiGraphicsExtractor graphics) {
        int clippedTop = Math.max(top, previewY), clippedBottom = Math.min(bottom, previewY + previewHeight);
        if (clippedTop >= clippedBottom || previewFailed || minecraft.level == null) return;
        try {
            if (previewEntity == null || previewEntity.level() != minecraft.level) {
                previewEntity = new net.minecraft.world.entity.monster.warden.Warden(net.minecraft.world.entity.EntityTypes.WARDEN, minecraft.level);
                // 26.3 client-world construction leaves ID 0 (unassigned). Item-model
                // extraction requires a nonzero seed ID even with no equipment. This
                // detached, screen-owned entity uses -1 only for rendering; it is never
                // registered in ClientLevel, ticked, or referenced in network packets.
                previewEntity.setId(-1);
                previewMetadataDirty = true;
                previewEntity.setNoAi(true); previewEntity.setSilent(true);
                ((ClockworkBossMarker) previewEntity).clockworkBosses$setClockworkBoss(true);
            }
            ClockworkBossMarker marker = (ClockworkBossMarker) previewEntity;
            if (previewMetadataDirty) {
                JsonObject skin = previewConfig.getAsJsonObject("skin");
                marker.clockworkBosses$setSkinType(skin.get("type").getAsString());
                marker.clockworkBosses$setSkinPlayerName(skin.get("name").getAsString());
                marker.clockworkBosses$setCustomSkinFile(skin.get("file").getAsString());
                marker.clockworkBosses$setTemplate("__gui_" + token);
                marker.clockworkBosses$setEyes(currentEyes().packed());
                marker.clockworkBosses$setBrows(currentBrows().packed());
                marker.clockworkBosses$setPupils(currentPupils().packed());
                String colorPath = "phases." + previewPhase + ".warden_effects.color";
                JsonElement color = ClockworkBossEditorSchema.get(draft, colorPath);
                if (color == null || color.isJsonNull()) color = ClockworkBossEditorSchema.get(draft, "warden_effects.color");
                int tint;
                try { tint = 0xFF000000 | Integer.parseInt(inputs.getOrDefault(colorPath, color.getAsString()).replace("#", ""), 16); }
                catch (RuntimeException invalid) { tint = 0xFF55FFFF; }
                marker.clockworkBosses$setEffectColor(tint);
                previewMetadataDirty = false;
            }
            // Use the same fresh snapshot extraction as the native inventory screen.
            var state = (net.minecraft.client.renderer.entity.state.WardenRenderState) minecraft.getEntityRenderDispatcher().getRenderer(previewEntity).createRenderState(previewEntity, 1);
            ((WardenRenderStateVisuals) state).clockworkBosses$setEditorPreview(true);
            state.bodyRot = 180 + previewYaw; state.yRot = 0; state.xRot = 0; state.scale = 1;
            state.shadowPieces.clear(); state.outlineColor = 0; state.nameTag = null; state.lightCoords = 0xF000F0;
            var tilt = new org.joml.Quaternionf().rotateX((float) Math.toRadians(previewPitch));
            var orientation = new org.joml.Quaternionf().rotateZ((float) Math.PI).mul(tilt);
            // Reduce full-body model size by 10% to leave room for the head, feet and
            // tendrils while rotating. Head zoom retains its close alignment view.
            float scale = headPreview ? Math.min(previewWidth / 1.25F, previewHeight / 0.9F) : 0.9F * Math.min(previewWidth / 2.1F, previewHeight / 3.1F);
            graphics.fill(previewX, clippedTop, previewX + previewWidth, clippedBottom, RAISED);
            // PiP entities must occupy a foreground stratum above the opaque editor panels.
            // Keep the bounds/scissor separate from the picture's native render-to-texture state.
            graphics.nextStratum();
            graphics.enableScissor(previewX, clippedTop, previewX + previewWidth, clippedBottom);
            try {
                graphics.entity(state, scale, new org.joml.Vector3f(0, headPreview ? state.eyeHeight : state.boundingBoxHeight / 2, 0), orientation, tilt,
                        previewX, previewY, previewX + previewWidth, previewY + previewHeight);
            } finally { graphics.disableScissor(); }
        } catch (RuntimeException failure) {
            previewFailed = true;
            org.slf4j.LoggerFactory.getLogger("clockwork_bosses").warn("Template editor model preview failed", failure);
            status = "Model preview failed; see client log. Load / refresh retries. Other pages remain available.";
        }
    }

    /** Build a typed draft and field patch; no packet is sent until all edited fields validate. */
    private void save() {
        if (busy) return;
        try {
            JsonObject candidate = draft.deepCopy();
            for (var entry : inputs.entrySet()) {
                JsonElement value = ClockworkBossEditorSchema.get(candidate, entry.getKey());
                if (value != null) {
                    try { ClockworkBossEditorSchema.put(candidate, entry.getKey(), parseInput(entry.getKey(), entry.getValue(), value, draft)); }
                    catch (IllegalArgumentException invalid) { throw new IllegalArgumentException(label(entry.getKey()) + ": " + invalid.getMessage()); }
                }
            }
            JsonObject comparison = ClockworkBossEditorSchema.comparison(original, candidate);
            ClockworkBossEditorSchema.validate(comparison, candidate);
            JsonObject patch = ClockworkBossEditorSchema.orderPatch(original, candidate);
            changes(comparison, candidate, "", patch);
            // Choosing a previously unset geometry must also persist its displayed coordinates.
            if (patch.has("activation.type")) {
                String mode = candidate.getAsJsonObject("activation").get("type").getAsString();
                if (mode.equals("player_enter_area")) {
                    geometryPatch(candidate, patch, "activation.min"); geometryPatch(candidate, patch, "activation.max");
                    patch.addProperty("activation.shape", "box");
                }
                else if (mode.equals("redstone")) geometryPatch(candidate, patch, "activation.position");
            }
            if (patch.has("boss_bar.visibility_area.shape") && candidate.getAsJsonObject("boss_bar").getAsJsonObject("visibility_area").get("shape").getAsString().equals("box")) {
                geometryPatch(candidate, patch, "boss_bar.visibility_area.min"); geometryPatch(candidate, patch, "boss_bar.visibility_area.max");
            }
            if (patch.size() == 0) {
                // Typing a value and then restoring it is not a pending edit; allow Reload afterward.
                inputs.clear(); draft = original.deepCopy(); confirmingClose = false;
                message("No changes to save."); return;
            }
            String patchText = patch.toString();
            if (patchText.getBytes(StandardCharsets.UTF_8).length > ClockworkBossEditorPayload.MAX_PATCH_BYTES) throw new IllegalArgumentException("Too many edits for one save. Save fewer changes at a time.");
            busy = true; status = "Saving to server…"; rebuild();
            ClockworkBossEditorClient.send(new ClockworkBossEditorPayload(token, "save", preset, name, revision, patchText));
        } catch (Exception invalid) { message(invalid.getMessage() == null ? "Could not save these values." : invalid.getMessage()); }
    }

    /** Percent is a presentation unit only; config and server validation retain probabilities. */
    private static boolean percentage(String path) { return path.endsWith("targeting.switch_chance") || path.matches("loot\\.entries\\.[^.]+\\.chance"); }
    private static String displayValue(String path, JsonElement value) {
        if (percentage(path) && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
            try { return ClockworkBossNumbers.display(value.getAsBigDecimal().multiply(java.math.BigDecimal.valueOf(100))); }
            catch (NumberFormatException | ArithmeticException invalid) {
                // Gson also bounds imported exponents. Keep a malformed legacy value visible
                // as compact text instead of failing to open the entire editor; edits must validate.
                return value.getAsString();
            }
        }
        return ClockworkBossEditorSchema.value(value);
    }
    /** Registered fields use this document's server schema; existing core inputs
     * retain the shared percentage conversion used by the development fixtures. */
    private static JsonElement parseInput(String path, String text, JsonElement value, JsonObject document) {
        if (clockwork.bosses.ClockworkBossActionEditor.typePath(path) || path.contains(".action_settings."))
            return ClockworkBossEditorSchema.parse(path, text, value, document);
        return parseInput(path, text, value);
    }
    private static JsonElement parseInput(String path, String text, JsonElement value) {
        if (percentage(path) && !text.isBlank() && !text.equals("<inherit>")) {
            try {
                var percent = ClockworkBossNumbers.decimal(text);
                if (percent.signum() < 0 || percent.compareTo(java.math.BigDecimal.valueOf(100)) > 0) throw new NumberFormatException();
                text = percent.scaleByPowerOfTen(-2).toString();
            }
            catch (NumberFormatException invalid) { throw new IllegalArgumentException("Enter 0 to 100%"); }
        }
        return ClockworkBossEditorSchema.parse(path, text, value);
    }
    private static void geometryPatch(JsonObject config, JsonObject patch, String path) {
        for (String axis : List.of("x", "y", "z")) patch.add(path + "." + axis, ClockworkBossEditorSchema.get(config, path + "." + axis).deepCopy());
    }

    private static void changes(JsonObject before, JsonObject after, String path, JsonObject patch) {
        for (var entry : after.entrySet()) {
            JsonElement old = before.get(entry.getKey()), value = entry.getValue();
            String key = path.isEmpty() ? entry.getKey() : path + "." + entry.getKey();
            if (old != null && old.isJsonObject() && value.isJsonObject()) changes(old.getAsJsonObject(), value.getAsJsonObject(), key, patch);
            else if (old == null || !old.equals(value)) patch.add(key, value.deepCopy());
        }
    }

    public void reply(ClockworkBossEditorPayload payload) throws Exception {
        cancelOrderDrag(); // A new server document must not accept an old drop.
        busy = false;
        if (payload.action().equals("saved")) {
            original = ClockworkBossTemplateDocument.read(new StringReader(payload.json())); draft = original.deepCopy();
            revision = payload.revision(); inputs.clear(); confirmingClose = false; buildSections();
            status = preset ? "Preset saved. Existing templates are separate copies." : "Template saved. Click Reload saved to apply to loaded bosses.";
        } else if (payload.action().equals("preview_ready")) status = "PNG queued. Preview appears when its bounded download finishes.";
        else status = payload.json();
        rebuild();
    }
    public void message(String text) { cancelOrderDrag(); busy = false; status = text; rebuild(); }
    /** Compare trees without allocating two serialized documents. Gson object
     * equality ignores insertion order, so compare the meaningful phase order too. */
    private boolean dirty() {
        if (!inputs.isEmpty() || !original.equals(draft)) return true;
        return !ClockworkBossEditorSchema.orderPatch(original, draft).isEmpty();
    }

    private void reloadSaved() {
        if (busy) return;
        if (dirty()) { message("Save your pending edits before reloading."); return; }
        busy = true; status = "Reloading saved templates…"; rebuild();
        ClockworkBossEditorClient.send(new ClockworkBossEditorPayload(token, "reload", preset, name, revision, ""));
    }

    @Override public void onClose() {
        if (lootPicker != null) { closeLootPicker(); return; }
        if (busy) { status = "Wait for the server response before closing."; return; }
        if (!picker && dirty() && !confirmingClose) { confirmingClose = true; status = "Unsaved changes. Press Close or Cancel again to discard them."; return; }
        ClockworkBossEditorClient.close(token); minecraft.gui.setScreen(null);
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void removed() {
        cancelOrderDrag(); dragRows.clear();
        lootPicker = null;
        ClockworkBossEditorClient.screenRemoved(token);
        previewEntity = null;
        WardenSkinTextureCache.releasePreview("__gui_" + token);
    }

    /** Numeric drafts update alignment immediately; invalid partial typing retains the saved preview value. */
    private int faceCoordinate(String root, String key, int fallback, int max) {
        return faceCoordinate(root, key, fallback, 0, max);
    }
    private int faceCoordinate(String root, String key, int fallback, int min, int max) {
        try { return Math.clamp(Integer.parseInt(inputs.getOrDefault(root + key, Integer.toString(fallback))), min, max); }
        catch (NumberFormatException invalid) { return fallback; }
    }
    private int facePixels(String root, String key, int fallback, int pixelsPerUnit) {
        String text = inputs.get(root + key);
        if (text == null) return fallback;
        try {
            double scale = Double.parseDouble(text);
            return Double.isFinite(scale) ? (int) Math.round(Math.clamp(scale, 0.5, 8.0 / pixelsPerUnit) * pixelsPerUnit) : fallback;
        } catch (NumberFormatException invalid) { return fallback; }
    }
    private ClockworkBossEyes currentEyes() {
        var saved = ClockworkBossEyes.read(draft); String root = "skin.glowing_eyes.";
        return new ClockworkBossEyes(faceCoordinate(root, "left_x", saved.leftX(), 15), faceCoordinate(root, "left_y", saved.leftY(), 15),
                faceCoordinate(root, "right_x", saved.rightX(), 15), faceCoordinate(root, "right_y", saved.rightY(), 15),
                facePixels(root, "scale_x", saved.width(), 2), facePixels(root, "scale_y", saved.height(), 2));
    }
    private ClockworkBossPupils currentPupils() {
        var saved = ClockworkBossPupils.read(draft); String root = "skin.glowing_pupils.";
        boolean enabled = Boolean.parseBoolean(inputs.getOrDefault(root + "enabled", Boolean.toString(saved.enabled())));
        return new ClockworkBossPupils(enabled,
                faceCoordinate(root, "left_x", saved.leftX(), -1, 7), faceCoordinate(root, "left_y", saved.leftY(), -1, 7),
                faceCoordinate(root, "right_x", saved.rightX(), -1, 7), faceCoordinate(root, "right_y", saved.rightY(), -1, 7),
                facePixels(root, "scale_x", saved.width(), 1), facePixels(root, "scale_y", saved.height(), 1));
    }
    private clockwork.bosses.ClockworkBossBrows currentBrows() {
        var saved = clockwork.bosses.ClockworkBossBrows.read(draft); String root = "skin.glowing_eyebrows.";
        return new clockwork.bosses.ClockworkBossBrows(faceCoordinate(root, "left_x", saved.leftX(), 12), faceCoordinate(root, "left_y", saved.leftY(), 12),
                faceCoordinate(root, "right_x", saved.rightX(), 12), faceCoordinate(root, "right_y", saved.rightY(), 12));
    }

    /** Dropdown is a real selectable overlay; clicks are intercepted so covered fields cannot be edited. */
    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (lootPicker != null) return super.mouseClicked(event, doubleClick);
        if (dropdown != null) {
            int visible = Math.min(6, dropdown.choices.size() - dropdown.first);
            int y = Math.min(dropdown.y, bottom - visible * 20);
            if (event.x() >= dropdown.x && event.x() < dropdown.x + dropdown.width && event.y() >= y && event.y() < y + visible * 20 && event.button() == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT) {
                int choice = dropdown.first + (int) ((event.y() - y) / 20);
                choose(dropdown.path, dropdown.choices.get(choice)); return true;
            }
            dropdown = null; return true;
        }
        if (!busy && event.button() == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT
                && event.x() >= contentX && event.x() < contentX + 20) {
            for (var row : dragRows) if (event.y() >= row.y + 14 && event.y() < row.y + 14 + CONTROL_HEIGHT) {
                dragRoot = selected; dragKey = row.key; dragPosition = row.position; dragMouseY = event.y();
                setFocused(null); selectedHelp = "Drag to a new position. Drag near the edge or scroll to reach other rows; Escape cancels.";
                return true;
            }
        }
        if (selected.equals("__preview") && event.button() == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT && event.x() >= previewX && event.x() < previewX + previewWidth && event.y() >= Math.max(top, previewY) && event.y() < Math.min(bottom, previewY + previewHeight)) { rotating = true; return true; }
        boolean result = super.mouseClicked(event, doubleClick);
        if (getFocused() instanceof EditBox box) {
            // Focused field help is readable without needing a prolonged hover.
            selectedHelp = box.getMessage().getString(); focusPath = editPaths.get(box); focusCursor = box.getCursorPosition();
        }
        return result;
    }

    @Override public boolean mouseDragged(MouseButtonEvent event, double horizontal, double vertical) {
        if (dragRoot != null) {
            dragMouseY = event.y();
            // Input-driven edge scrolling; no repeating watcher or render mutation.
            int oldScroll = scroll;
            if (event.y() < top + 12) scroll -= 10;
            else if (event.y() > bottom - 12) scroll += 10;
            scroll = Math.clamp(scroll, 0, Math.max(0, fullHeight - (bottom - top)));
            if (oldScroll != scroll) rebuild();
            updateOrderDrop(); return true;
        }
        if (rotating) { previewYaw += (float) horizontal; previewPitch = Math.clamp(previewPitch + (float) vertical, -45, 45); return true; }
        return super.mouseDragged(event, horizontal, vertical);
    }
    @Override public boolean mouseReleased(MouseButtonEvent event) {
        if (dragRoot != null && event.button() == com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT) {
            dragMouseY = event.y(); updateOrderDrop();
            String root = dragRoot, key = dragKey; int position = dragPosition;
            boolean inside = event.x() >= contentX && event.x() < width - 8 && event.y() >= top && event.y() < bottom;
            cancelOrderDrag();
            if (inside && !busy) movePhase(root, key, position);
            else { status = "Reorder cancelled."; rebuild(); }
            return true;
        }
        rotating = false; return super.mouseReleased(event);
    }
    private void cancelOrderDrag() { dragRoot = null; dragKey = null; }
    /** GUI coordinates and current scroll determine the drop; capped catalogs
     * keep this a constant-size calculation even at high/low GUI scales. */
    private void updateOrderDrop() {
        var entries = ClockworkBossEditorSchema.get(draft, dragRoot).getAsJsonObject();
        dragPosition = Math.clamp((int) ((dragMouseY - top + scroll - phaseListHeader) / ENTRY_HEIGHT), 0, entries.size() - 1);
    }

    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (lootPicker != null) { lootPicker.scroll(vertical, this::rebuild); return true; }
        if (dropdown != null) {
            int first = Math.clamp(dropdown.first - (int) Math.signum(vertical), 0, Math.max(0, dropdown.choices.size() - 6));
            dropdown = new Dropdown(dropdown.path, dropdown.choices, dropdown.x, dropdown.y, dropdown.width, first, Math.clamp(dropdown.cursor, first, Math.min(first + 5, dropdown.choices.size() - 1))); return true;
        }
        if (busy || vertical == 0) return true;
        if (dragRoot != null) {
            scroll -= (int) Math.signum(vertical) * ENTRY_HEIGHT;
            rebuild(); updateOrderDrop(); return true;
        }
        if (picker) scroll -= (int) Math.signum(vertical) * 2;
        else if (x < contentX) indexScroll -= (int) Math.signum(vertical) * 2;
        else scroll -= (int) Math.signum(vertical) * SCROLL_STEP;
        rebuild(); return true;
    }
    @Override public boolean keyPressed(KeyEvent event) {
        if (lootPicker != null) {
            if (event.isEscape()) { closeLootPicker(); return true; }
            return super.keyPressed(event);
        }
        if (dragRoot != null) {
            if (event.isEscape()) { cancelOrderDrag(); status = "Reorder cancelled."; rebuild(); }
            return true; // Keyboard actions cannot remove/save a row mid-drop.
        }
        if (dropdown != null) {
            if (event.isEscape()) { dropdown = null; return true; }
            if (event.isDown() || event.isUp()) {
                int cursor = Math.clamp(dropdown.cursor + (event.isDown() ? 1 : -1), 0, dropdown.choices.size() - 1);
                int first = Math.clamp(dropdown.first, Math.max(0, cursor - 5), cursor);
                dropdown = new Dropdown(dropdown.path, dropdown.choices, dropdown.x, dropdown.y, dropdown.width, first, cursor);
                scheduleNarration(); return true;
            }
            if (event.isConfirmation()) { choose(dropdown.path, dropdown.choices.get(dropdown.cursor)); return true; }
            return true;
        }
        return super.keyPressed(event);
    }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        if (lootPicker != null) {
            lootPicker.background(graphics, width, height);
            super.extractRenderState(graphics, mouseX, mouseY, delta);
            return;
        }
        graphics.fill(0, 0, width, height, BG);
        graphics.fill(0, 0, width, 24, SIDE);
        if (!picker) {
            graphics.fill(6, top - 2, indexWidth + 10, bottom, SIDE);
            graphics.fill(contentX - 4, top - 2, width - 8, bottom, PANEL);
            graphics.text(font, "Sections ↕", 8, 26, BRASS, false);
            graphics.text(font, selectedPageTitle, contentX, 26, BRASS, false);
            graphics.fill(0, height - 27, width, height - 26, LINE);
            graphics.fill(0, height - 26, width, height, SIDE);
        }
        graphics.text(font, ellipsis(title.getString(), width - 90), 10, 12, BRASS, false);
        super.extractRenderState(graphics, mouseX, mouseY, delta);
        if (selected.equals("__preview")) drawPreview(graphics);
        for (Label label : labels) graphics.text(font, ellipsis(label.text, label.width), label.x, label.y, label.secondary ? MUTED : TEXT, false);
        for (var row : dragRows) {
            boolean hovering = mouseX >= contentX && mouseX < contentX + 20 && mouseY >= row.y + 14 && mouseY < row.y + 14 + CONTROL_HEIGHT;
            graphics.fill(contentX, row.y + 14, contentX + 20, row.y + 14 + CONTROL_HEIGHT, row.key.equals(dragKey) || hovering ? SELECTED : RAISED);
            graphics.centeredText(font, "↕", contentX + 10, row.y + 19, busy ? DIM : BRASS);
            if (hovering && dragRoot == null) graphics.setTooltipForNextFrame(font, Component.literal("Drag to reorder; Up/Down also works with keyboard."), mouseX, mouseY);
        }
        if (dragRoot != null) {
            var names = ClockworkBossEditorSchema.get(draft, dragRoot).getAsJsonObject().keySet().stream().toList();
            int source = names.indexOf(dragKey), line = dragPosition + (dragPosition > source ? 1 : 0);
            int y = Math.clamp(top + phaseListHeader + line * ENTRY_HEIGHT - scroll, top, bottom - 2);
            graphics.fill(contentX, y, width - 12, y + 2, COPPER);
        }
        if (!picker && helpExpanded && !selectedHelp.isEmpty()) {
            int y = bottom + 4;
            List<net.minecraft.util.FormattedCharSequence> help = font.split(Component.literal(selectedHelp), width - 24);
            for (int index = 0; index < Math.min(3, help.size()); index++) graphics.text(font, help.get(index), 12, y + index * 10, MUTED, false);
        }
        String footerText = status.isEmpty() && !picker ? selectedHelp : status;
        if (!footerText.isEmpty()) {
            int statusY = picker ? height - 28 : height - 38;
            graphics.text(font, ellipsis(footerText.replace('\n', ' '), width - 24), 12, statusY, status.isEmpty() ? MUTED : TEXT, false);
            if (mouseY >= statusY - 4 && mouseY < statusY + 10) graphics.setTooltipForNextFrame(font, Component.literal(footerText), mouseX, mouseY);
        }
        if (!picker && fullHeight > bottom - top) {
            int track = bottom - top, thumb = Math.max(8, track * track / fullHeight);
            int y = top + (track - thumb) * scroll / Math.max(1, fullHeight - track);
            graphics.fill(width - 7, y, width - 4, y + thumb, BRASS);
        }
        if (dropdown != null) {
            graphics.nextStratum();
            int visible = Math.min(6, dropdown.choices.size() - dropdown.first), y = Math.min(dropdown.y, bottom - visible * 20);
            graphics.fill(dropdown.x - 1, y - 1, dropdown.x + dropdown.width + 1, y + visible * 20 + 1, LINE);
            for (int index = 0; index < visible; index++) {
                boolean hovered = mouseX >= dropdown.x && mouseX < dropdown.x + dropdown.width && mouseY >= y + index * 20 && mouseY < y + (index + 1) * 20;
                boolean current = hovered || dropdown.first + index == dropdown.cursor;
                graphics.fill(dropdown.x, y + index * 20, dropdown.x + dropdown.width, y + (index + 1) * 20, current ? SELECTED : PANEL);
                String option = dropdown.choices.get(dropdown.first + index);
                graphics.text(font, ellipsis(optionLabel(dropdown.path, option), dropdown.width - 8), dropdown.x + 4, y + index * 20 + 6, current ? SELECTED_TEXT : TEXT, false);
            }
        }
    }

    private FlatButton addButton(int x, int y, int width, int height, String text, Runnable action) {
        return addRenderableWidget(new FlatButton(font, x, y, width, height, text, action));
    }
    @Override public Component getNarrationMessage() {
        return dropdown == null ? super.getNarrationMessage() : Component.literal(label(dropdown.path) + ": " + dropdown.choices.get(dropdown.cursor) + ". Up/down chooses; Enter selects; Escape closes.");
    }
    private String ellipsis(String text, int width) {
        return ClockworkBossEditorTheme.ellipsis(font, text, width);
    }
    private static String label(String text) {
        return ClockworkBossEditorSchema.label(text);
    }
    /** Show native command names as familiar slider labels without changing serialized values. */
    private String optionLabel(String path, String value) {
        if (clockwork.bosses.ClockworkBossActionEditor.typePath(path)) return clockwork.bosses.ClockworkBossActionEditor.actionLabel(draft, value);
        if (path.equals("loot.source")) return value.equals("entries") ? "Entry editor" : "Datapack table";
        if (value.equals("<inherit>")) return path.contains(".action_settings.") ? "Action default"
                : childField(path) ? "Parent (inherit)" : "Base (inherit)";
        if (!path.equals("music.channel")) return value;
        return switch (value) {
            case "record" -> "Jukebox / Note Blocks";
            case "master" -> "Master only";
            case "hostile" -> "Hostile Creatures";
            case "neutral" -> "Friendly Creatures";
            case "block" -> "Blocks";
            case "player" -> "Players";
            case "ambient" -> "Ambient / Environment";
            default -> label(value);
        };
    }
    /** Short inline help complements the longer tooltip; ticks and amplifier semantics stay explicit. */
    private static String darknessNote(String path) {
        if (!path.startsWith("dormant_darkness.") && !path.contains(".darkness.")) return "";
        return switch (path.substring(path.lastIndexOf('.') + 1)) {
            case "enabled" -> "Enable Darkness.";
            case "max_radius" -> "Maximum range, in blocks.";
            case "edge_interval_ticks" -> "Refresh gap at the edge.";
            case "center_interval_ticks" -> "Refresh gap near the boss.";
            case "edge_duration_ticks" -> "Duration at the edge.";
            case "center_duration_ticks" -> "Duration near the boss.";
            case "edge_amplifier", "center_amplifier" -> "Keep at 0; priority only.";
            default -> "";
        };
    }
    private static String sectionHelp(String path) { return ClockworkBossTemplateDocument.description(path); }
    private static void collectObjectPaths(JsonObject object, String path, Set<String> output) {
        for (var entry : object.entrySet()) if (entry.getValue().isJsonObject()) { String child = path.isEmpty() ? entry.getKey() : path + "." + entry.getKey(); output.add(child); collectObjectPaths(entry.getValue().getAsJsonObject(), child, output); }
    }
}
