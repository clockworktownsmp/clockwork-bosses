package clockwork.bosses;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.storage.ValueInput;

/** Read compatibility for existing saved bosses/arrows; every new write uses ClockworkBosses keys. */
public final class ClockworkBossLegacyData {
    public static final String PREFIX = "ClockworkBosses_";
    // These literal spellings are the old on-disk contract, not current branding.
    private static final String LEGACY = "ArenaBossFight_";
    private static final String[] SELECTOR_SUFFIXES = {"Selector", "SelectorActive", "SelectorTemplate", "SelectorSession",
            "SelectorOwner", "SelectorMode", "SelectorTarget", "Pos1X", "Pos1Y", "Pos1Z"};
    private ClockworkBossLegacyData() {}

    /** Must run before native parent health loading so old marked entities receive the extended health range. */
    public static boolean isBoss(ValueInput input) {
        return input.getBooleanOr(PREFIX + "Boss", input.getBooleanOr(LEGACY + "ArenaBoss",
                input.getBooleanOr(LEGACY + "EventWarden", false)));
    }
    public static String string(ValueInput input, String suffix, String fallback) {
        return input.getStringOr(PREFIX + suffix, input.getStringOr(LEGACY + suffix, fallback));
    }
    public static boolean bool(ValueInput input, String suffix, boolean fallback) {
        return input.getBooleanOr(PREFIX + suffix, input.getBooleanOr(LEGACY + suffix, fallback));
    }
    public static int integer(ValueInput input, String suffix, int fallback) {
        return input.getIntOr(PREFIX + suffix, input.getIntOr(LEGACY + suffix, fallback));
    }

    /** Normalize only owned item keys on a copied tag; other mods' item data and original saved items remain intact. */
    public static CompoundTag selector(CompoundTag copy) {
        for (String suffix : SELECTOR_SUFFIXES) {
            String old = LEGACY + suffix;
            String current = PREFIX + suffix;
            if (!copy.contains(current) && copy.contains(old)) copy.put(current, copy.get(old).copy());
            copy.remove(old);
        }
        return copy;
    }
}
