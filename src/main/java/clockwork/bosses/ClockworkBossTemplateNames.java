package clockwork.bosses;

import java.util.regex.Pattern;

/** Shared command/wire identifier policy; validation never touches Fabric or the filesystem. */
public final class ClockworkBossTemplateNames {
    private static final Pattern NAME = Pattern.compile("[a-z0-9_-][a-z0-9._-]{0,63}");
    // Also safe for administrators developing on Windows before deploying to Linux.
    private static final Pattern RESERVED = Pattern.compile("(con|prn|aux|nul|com[1-9]|lpt[1-9])(\\..*)?");

    private ClockworkBossTemplateNames() {}

    /** One lowercase component, never a path; compiled patterns avoid per-render regex compilation. */
    public static boolean isValid(String name) {
        return name != null && NAME.matcher(name).matches()
                && !name.endsWith(".") && !RESERVED.matcher(name).matches();
    }
}
