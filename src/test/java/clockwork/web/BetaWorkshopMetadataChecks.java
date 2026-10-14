package clockwork.web;

import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/** Read beta's public declaration through the packaged mod's actual bounded
 * HTTP implementation. No keys, cookies, owner sessions or server files. */
public final class BetaWorkshopMetadataChecks {
    public static void main(String[] args) throws Exception {
        if (!TemplateApi.class.getProtectionDomain().getCodeSource().getLocation().toString()
                .endsWith("clockwork_bosses-0.3.0-beta.4.jar")) throw new AssertionError("Exact beta jar required");
        var mod = WorkshopRelease.from("0.3.0-beta.4", "beta");
        var endpoint = new WorkshopRelease.Endpoint(WorkshopRelease.Channel.BETA.origin,
                WorkshopRelease.Channel.BETA, false, false, false);
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build()) {
            var response = WorkshopCompatibility.http(client, endpoint.origin()).fetch();
            System.out.println("Actual packaged Java metadata request: status=" + response.status() + ", bytes=" + response.body().length);
            if (response.status() != 200) throw new AssertionError("Beta public metadata is unavailable to the actual mod HTTP client");
            var declaration = TemplateJson.parse(response.body(), WorkshopCompatibility.MAX_BYTES);
            var state = WorkshopCompatibility.assess(declaration, mod, endpoint, TemplateApi.capabilities(false));
            if (!state.writable() || !"0.3.0-rc.1".equals(state.webVersion())) throw new AssertionError("Beta does not declare supported publication");
            if (args.length != 1) throw new AssertionError("One local public-metadata output path required");
            Files.write(Path.of(args[0]), response.body());
            System.out.println("PASS: actual beta.4 Java HTTP client reads verified HTTPS beta metadata, declares compatible/writable with independent web0.3.0-rc.1; no credentials or redirects");
        }
    }
}
