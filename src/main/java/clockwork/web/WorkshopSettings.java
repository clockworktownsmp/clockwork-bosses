package clockwork.web;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.util.Arrays;
import java.util.Objects;
import java.util.Properties;

/** A bounded, validated snapshot. Missing reload files never reset opt-in access. */
record WorkshopSettings(boolean enabled, boolean templateAccess, URI origin) {
    static final int MAX_BYTES = 32 * 1024;
    static final String FILE_NAME = "clockwork-bosses/workshop.properties";
    static final String LEGACY_FILE_NAME = "clockwork-bosses-web.properties";
    private static final String DEFAULTS = "# Apply changes with /boss workshop reload (owner or server console).\n" +
        "enabled=false\n# Opt-in owner-authenticated drafts/publishing.\ntemplate_access_enabled=false\n" +
        "# Outbound HTTPS only; no inbound web port is used.\nrelay_url=https://workshop.clockworkboss.com\n";

    boolean active() { return enabled && templateAccess; }
    boolean sameConnection(WorkshopSettings other) {
        return active() == other.active() && Objects.equals(origin, other.origin);
    }
    static WorkshopSettings load(Path file, boolean createDefaults) throws IOException {
        TemplateFiles.check(file);
        if (createDefaults && !Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            try {
                TemplateFiles.atomic(file, DEFAULTS.getBytes(java.nio.charset.StandardCharsets.UTF_8), false, () -> {});
            } catch (TemplateFiles.Conflict concurrentEdit) { /* Read the owner's new file. */ }
            catch (IOException failure) { throw new IOException("Could not create " + FILE_NAME); }
        }
        return decode(readBytes(file, FILE_NAME));
    }
    static byte[] readBytes(Path file, String label) throws IOException {
        TemplateFiles.check(file);
        if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException(label + " is missing; restore it before reloading");
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException(label + " must be a regular file, not a link or directory");
        byte[] bytes;
        try (var input = Files.newInputStream(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            bytes = input.readNBytes(MAX_BYTES + 1);
        } catch (NoSuchFileException missing) { throw new IOException(label + " is missing; restore it before reloading"); }
        catch (IOException failure) { throw new IOException("Could not read " + label + "; check its path and permissions"); }
        if (bytes.length > MAX_BYTES) throw new IOException(label + " exceeds the 32 KiB limit");
        return bytes;
    }
    static WorkshopSettings decode(byte[] bytes) throws IOException {
        if (bytes.length > MAX_BYTES) throw new IOException("Workshop settings exceed the 32 KiB limit");
        // Some panel/Windows editors add a BOM. It must not turn enabled=false
        // into an unknown key and silently fall back to enabled=true.
        if (bytes.length >= 3 && bytes[0] == (byte) 0xef && bytes[1] == (byte) 0xbb && bytes[2] == (byte) 0xbf) bytes = Arrays.copyOfRange(bytes, 3, bytes.length);
        if (bytes.length >= 2 && ((bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xfe) || (bytes[0] == (byte) 0xfe && bytes[1] == (byte) 0xff)))
            throw new IOException(FILE_NAME + " must be saved as UTF-8 or Java properties, not UTF-16");
        Properties values = new Properties();
        try { values.load(new ByteArrayInputStream(bytes)); }
        catch (IllegalArgumentException malformed) { throw new IOException("Malformed properties escapes in " + FILE_NAME); }
        // Preserve the old effective meaning of an omitted enabled flag when
        // importing its exact bytes. Fresh files explicitly disable BOTH flags;
        // omitted template access always remains false, so access is still opt-in.
        boolean enabled = flag(values, "enabled", true), access = flag(values, "template_access_enabled", false);
        URI origin = enabled && access ? RelayClient.origin(values.getProperty("relay_url", "https://workshop.clockworkboss.com").trim(), false) : null;
        return new WorkshopSettings(enabled, access, origin);
    }
    private static boolean flag(Properties values, String key, boolean fallback) throws IOException {
        String value = values.getProperty(key, Boolean.toString(fallback)).trim();
        if (!value.equals("true") && !value.equals("false")) throw new IOException(key + " must be true or false");
        return Boolean.parseBoolean(value);
    }
}
