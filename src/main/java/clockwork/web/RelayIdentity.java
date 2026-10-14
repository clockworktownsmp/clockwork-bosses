package clockwork.web;

import clockwork.bosses.files.ClockworkBossPaths;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;

/** Local server credential, unrelated to players. Never sent to a browser or log. */
final class RelayIdentity {
    static final class OriginConflict extends IOException {
        OriginConflict() { super("Relay identity is bound to another origin. Use an isolated test configuration/testing_channel; existing credentials were left in place"); }
    }
    private final String secret, id;
    RelayIdentity(String secret) throws IOException {
        if (!secret.matches("[A-Za-z0-9_-]{43}")) throw new IOException("Invalid relay-server.key; restore its backup or remove it while the server is stopped");
        this.secret = secret; id = AccessSessions.hash(secret);
    }
    String secret() { return secret; }
    String id() { return id; }
    static RelayIdentity load(Path config) throws IOException {
        Path directory = config.toAbsolutePath().normalize().resolve("clockwork-bosses-web");
        return loadDirectory(directory);
    }
    /** Upgrade preserves the legacy key at its explicitly configured origin.
     * A public, non-secret origin marker prevents later cross-host reuse.
     * Build-selected non-stable hosts/test overrides get independently generated
     * identities, never copied/derived from the production secret. */
    static synchronized RelayIdentity load(Path config, WorkshopRelease.Endpoint endpoint) throws IOException {
        Path root = config.toAbsolutePath().normalize().resolve("clockwork-bosses-web");
        Path directory = endpoint.legacyIdentity() ? root
                : root.resolve("relay-identities").resolve(AccessSessions.hash(endpoint.origin().toString()));
        ClockworkBossPaths.checkAncestors(directory); Files.createDirectories(directory); ClockworkBossPaths.checkAncestors(directory);
        Path binding = directory.resolve("relay-server.origin");
        ClockworkBossPaths.checkAncestors(binding);
        String origin = endpoint.origin().toString();
        if (Files.exists(binding, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = TemplateFiles.read(binding, 2048);
            if (!new String(bytes, StandardCharsets.UTF_8).equals(origin + "\n"))
                throw new OriginConflict();
        } else {
            // No secret is written here. Existing explicit relay_url is the
            // owner's legacy trust declaration, not an inferred prior host.
            TemplateFiles.atomic(binding, (origin + "\n").getBytes(StandardCharsets.UTF_8), false, () -> {
                ClockworkBossPaths.checkAncestors(directory);
            });
        }
        return loadDirectory(directory);
    }
    private static RelayIdentity loadDirectory(Path directory) throws IOException {
        ClockworkBossPaths.checkAncestors(directory);
        Files.createDirectories(directory);
        ClockworkBossPaths.checkAncestors(directory);
        Path key = directory.resolve("relay-server.key");
        ClockworkBossPaths.checkAncestors(key);
        try {
            byte[] random = new byte[32]; new SecureRandom().nextBytes(random);
            byte[] bytes = (Base64.getUrlEncoder().withoutPadding().encodeToString(random) + "\n").getBytes(StandardCharsets.US_ASCII);
            var options = java.util.Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            boolean posix = Files.getFileStore(directory).supportsFileAttributeView("posix");
            try (var channel = FileChannel.open(key, options, posix ? new java.nio.file.attribute.FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))} : new java.nio.file.attribute.FileAttribute<?>[0])) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true);
            }
        } catch (FileAlreadyExistsException existing) { /* keep the same identity */ }
        ClockworkBossPaths.checkAncestors(key);
        if (!Files.isRegularFile(key, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Relay key must be a regular file, not a symbolic link");
        try (var channel = FileChannel.open(key, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            if (channel.size() > 64) throw new IOException("Relay key exceeds size limit");
            ByteBuffer buffer = ByteBuffer.allocate(65); while (buffer.hasRemaining() && channel.read(buffer) != -1) { }
            buffer.flip(); return new RelayIdentity(StandardCharsets.US_ASCII.decode(buffer).toString().trim());
        }
    }
}
