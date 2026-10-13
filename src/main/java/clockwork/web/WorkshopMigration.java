package clockwork.web;

import java.io.IOException;
import java.nio.file.*;
import java.util.Arrays;
import org.slf4j.LoggerFactory;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

/** Settings-only migration, called on the configuration worker. Persistent
 * server identity, drafts, generations, audio backups and journals stay in
 * their existing directories. No credential is read, generated or logged here. */
final class WorkshopMigration {
    static Path settingsFile(Path configRoot) throws IOException {
        return settingsFile(configRoot, () -> {});
    }
    // The commit hook lets isolated tests prove interrupted/concurrent changes
    // without sleeping or racing an administrator's actual files.
    static Path settingsFile(Path configRoot, TemplateFiles.CommitCheck beforeCommit) throws IOException {
        Path root = configRoot.toAbsolutePath().normalize();
        Path canonical = root.resolve(WorkshopSettings.FILE_NAME);
        Path legacy = root.resolve(WorkshopSettings.LEGACY_FILE_NAME);
        TemplateFiles.check(canonical);
        if (Files.exists(canonical, NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(canonical, NOFOLLOW_LINKS)) throw new IOException("Workshop config must be a regular file; restore " + WorkshopSettings.FILE_NAME);
            if (Files.exists(legacy, NOFOLLOW_LINKS)) LoggerFactory.getLogger("clockwork_bosses_workshop").info("Using {}; legacy {} is retained but ignored. Apply changes with /boss workshop reload.", WorkshopSettings.FILE_NAME, WorkshopSettings.LEGACY_FILE_NAME);
            return canonical; // Canonical wins without even reading a legacy secret/property.
        }
        TemplateFiles.check(legacy);
        if (!Files.exists(legacy, NOFOLLOW_LINKS)) return canonical;
        byte[] original = WorkshopSettings.readBytes(legacy, WorkshopSettings.LEGACY_FILE_NAME);
        // Validation never serializes Properties back: comments, BOM, unknown
        // fields, newline style and all original bytes must survive unchanged.
        WorkshopSettings.decode(original);
        try {
            TemplateFiles.atomic(canonical, original, false, () -> {
                beforeCommit.check();
                if (!Arrays.equals(original, WorkshopSettings.readBytes(legacy, WorkshopSettings.LEGACY_FILE_NAME))) throw new IOException("Legacy workshop settings changed during migration; no replacement was made. Retry /boss workshop reload.");
            });
        } catch (TemplateFiles.Conflict concurrentCanonical) {
            TemplateFiles.check(canonical);
            if (!Files.isRegularFile(canonical, NOFOLLOW_LINKS)) throw new IOException("Workshop settings appeared concurrently but are not a regular file");
            // Someone deliberately installed a canonical config while copying.
            // It takes precedence, even if its validation later needs correction.
            return canonical;
        }
        LoggerFactory.getLogger("clockwork_bosses_workshop").info("Copied legacy workshop settings into {}. Original {} is retained; relay credentials and saved files were left in place.", WorkshopSettings.FILE_NAME, WorkshopSettings.LEGACY_FILE_NAME);
        return canonical;
    }
}
