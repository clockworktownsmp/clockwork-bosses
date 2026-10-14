package clockwork.web;

import java.io.IOException;
import java.net.URI;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.metadata.CustomValue;

/** Installed mod identity and fixed channel hosts. This is configuration, not
 * release negotiation: neither a version suffix nor a failed host chooses one. */
final class WorkshopRelease {
    private WorkshopRelease() {}

    enum Channel {
        STABLE("stable", "production", "https://workshop.clockworkboss.com"),
        BETA("beta", "beta", "https://beta.clockworkboss.com"),
        DEVELOPMENT("development", "dev", "https://dev.clockworkboss.com");
        final String name, websiteName;
        final URI origin;
        Channel(String name, String websiteName, String origin) {
            this.name = name; this.websiteName = websiteName; this.origin = URI.create(origin);
        }
        static Channel read(String value) throws IOException {
            for (var channel : values()) if (channel.name.equals(value)) return channel;
            throw new IOException("Workshop channel must be stable, beta, or development");
        }
        static Channel at(URI origin) {
            for (var channel : values()) if (channel.origin.equals(origin)) return channel;
            return null; // Existing self-hosted relay_url remains an exact origin.
        }
    }

    record Mod(String version, Channel channel) {
        Mod {
            if (version == null || !version.matches("[0-9A-Za-z][0-9A-Za-z.+_-]{0,63}") || channel == null)
                throw new IllegalArgumentException("Invalid installed Workshop release metadata");
        }
    }

    /** Read the actual installed Fabric metadata, never a hardcoded Java version
     * or the website's version. Absent legacy channel metadata means stable;
     * this also holds for a legacy artifact containing a beta suffix. */
    static Mod installed() throws IOException {
        var metadata = FabricLoader.getInstance().getModContainer("clockwork_bosses")
                .orElseThrow(() -> new IOException("Installed Clockwork Bosses metadata is unavailable")).getMetadata();
        CustomValue value = metadata.getCustomValue("clockwork_bosses:workshop_build_channel");
        if (value != null && value.getType() != CustomValue.CvType.STRING)
            throw new IOException("Invalid packaged Workshop channel");
        return from(metadata.getVersion().getFriendlyString(), value == null ? null : value.getAsString());
    }
    static Mod from(String version, String packagedChannel) throws IOException {
        try { return new Mod(version, packagedChannel == null ? Channel.STABLE : Channel.read(packagedChannel)); }
        catch (IllegalArgumentException invalid) { throw new IOException("Invalid installed Workshop release metadata"); }
    }

    /** One immutable origin drives both browser URLs and the outbound websocket.
     * Legacy configs missing new opt-in fields keep stable unless relay_url was
     * explicitly set. A test override is deliberate and separately identified. */
    record Endpoint(URI origin, Channel channel, boolean testing, boolean legacyMetadataAllowed, boolean legacyIdentity) {
        String channelName() { return channel == null ? "custom" : channel.name; }
    }
}
