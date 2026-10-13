package clockwork.bosses.files;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/** Shared pure-file boundary for native templates and the optional workshop.
 * Check every existing ancestor, including Windows junctions/reparse redirects,
 * before opening a server-owned file. Callers still bound their own root/name,
 * size and file type. OS permissions must exclude hostile concurrent local
 * writers: portable Java checks cannot lock an entire directory tree against
 * an administrator replacing a parent after validation. No Minecraft access. */
public final class ClockworkBossPaths {
    private ClockworkBossPaths() {}
    public static void checkAncestors(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize(), current = absolute.getRoot();
        for (Path segment : absolute) {
            current = current.resolve(segment);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) continue;
            BasicFileAttributes attrs = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attrs.isSymbolicLink() || attrs.isOther() || !current.toRealPath().equals(current))
                throw new IOException("Linked or redirected server file paths are not allowed");
        }
    }
}
