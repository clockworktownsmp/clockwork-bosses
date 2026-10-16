package clockwork.web;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/** Read-only public declaration check through the actual checkpoint jar. It
 * neither sends credentials nor changes a declaration to manufacture support. */
public final class CheckpointWorkshopMetadataChecks {
    public static void main(String[] args) throws Exception {
        String version = System.getProperty("clockwork.test.mod_version", "0.3.0-beta.6");
        if (!TemplateApi.class.getProtectionDomain().getCodeSource().getLocation().toString()
                .endsWith("clockwork_bosses-" + version + ".jar")) throw new AssertionError("Exact checkpoint jar required");
        var mod = WorkshopRelease.from(version,"beta");
        var endpoint = new WorkshopRelease.Endpoint(URI.create("https://beta.clockworkboss.com"),WorkshopRelease.Channel.BETA,false,false,false);
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build()) {
            var response = WorkshopCompatibility.http(client,endpoint.origin()).fetch();
            if (response.status()!=200) throw new AssertionError("Public beta metadata HTTP " + response.status());
            var info = TemplateJson.parse(response.body(),WorkshopCompatibility.MAX_BYTES);
            var assessment = WorkshopCompatibility.assess(info,mod,endpoint,TemplateApi.capabilities(false));
            if (args.length!=1) throw new AssertionError("Public metadata output path required");
            Files.write(Path.of(args[0]),response.body());
            // Report the real decision; unsupported is an expected safety outcome,
            // not a successful edit/publish test or a reason to change host.
            System.out.println("PUBLIC_BETA_DECLARATION: mod=" + version + " web=" + assessment.webVersion()
                    + " writable=" + assessment.writable() + " compatible_mod_versions=" + info.get("compatible_mod_versions"));
            if (assessment.writable() != info.getAsJsonArray("compatible_mod_versions").asList().stream()
                    .anyMatch(value -> value.isJsonPrimitive() && version.equals(value.getAsString())))
                throw new AssertionError("Version declaration and write decision disagree");
            System.out.println("PASS: exact-jar HTTPS metadata, no redirects/credentials, explicit supported-version guard; no website changed");
        }
    }
}
