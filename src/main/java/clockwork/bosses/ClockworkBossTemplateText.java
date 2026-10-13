package clockwork.bosses;

/** Shared text constraints for GUI validation, media basenames and schema
 * export. These rules do not access Minecraft registries or the filesystem. */
public final class ClockworkBossTemplateText {
    public static final String PNG_FILE = "[a-z0-9][a-z0-9._-]{0,63}\\.png";
    public static final String OGG_FILE = "[a-z0-9][a-z0-9._-]{0,63}\\.ogg";
    public static final String RESOURCE_ID = "[a-z0-9_.-]+:[a-z0-9/._-]+";
    private ClockworkBossTemplateText() {}

    /** Patterns describe nonempty values; allowEmpty explicitly handles fields
     * such as a disabled sound. Null inheritance is owned by the field definition. */
    public record Rule(int maxLength, String pattern, boolean allowEmpty, String error) {
        public void validate(String text) {
            if (text.length() > maxLength) throw new IllegalArgumentException("Text is too long (maximum " + maxLength + ")");
            if (!pattern.isEmpty() && !(allowEmpty && text.isEmpty()) && !text.matches(pattern))
                throw new IllegalArgumentException(error);
        }
    }

    /** Path patterns work for any phase/subphase name; no parallel website list
     * is needed when the editor adds or changes a text constraint. */
    public static Rule rule(String path) {
        if (path.endsWith(".file")) return new Rule(68, path.equals("skin.file") ? PNG_FILE : OGG_FILE, true,
                "Use a lowercase filename only; no spaces or paths");
        if (path.equals("skin.name")) return new Rule(16, "[A-Za-z0-9_]{1,16}", true,
                "Use a Minecraft username (1–16 letters, numbers or underscores)");
        if (path.endsWith("warden_effects.color")) return new Rule(7, "#[0-9A-Fa-f]{6}", false, "Use #RRGGBB");
        if (path.endsWith(".dimension")) return new Rule(512, RESOURCE_ID, true,
                "Use a dimension ID such as minecraft:overworld");
        if (path.equals("loot.loot_table") || path.matches("loot\\.entries\\.[^.]+\\.item"))
            return new Rule(256, RESOURCE_ID, path.equals("loot.loot_table"), "Use a lowercase namespaced resource ID");
        return new Rule(path.endsWith("announcement.text") ? 80 : 512, "", true, "");
    }
}
