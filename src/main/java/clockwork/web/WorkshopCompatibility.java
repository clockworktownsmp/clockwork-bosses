package clockwork.web;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Optional read-only website metadata, not a new relay handshake. One bounded
 * request/cache per connection uses the relay's existing network executor.
 * Reads/login/logout remain available when writes are incompatible. */
final class WorkshopCompatibility {
    static final int MAX_BYTES = 16 * 1024;
    private static final long TTL = TimeUnit.SECONDS.toNanos(30);
    record Response(int status, byte[] body) {}
    @FunctionalInterface interface Source { Response fetch() throws IOException; }
    record State(String name, String reason, String webVersion, String build, boolean writable) {}
    private final WorkshopRelease.Mod mod;
    private final WorkshopRelease.Endpoint endpoint;
    private final JsonObject capabilities;
    private final LongSupplier clock;
    private volatile State state = new State("unavailable", "Website compatibility has not been checked", null, null, false);
    private volatile long checked = Long.MIN_VALUE;
    private Source source;
    private volatile boolean closed;

    WorkshopCompatibility(WorkshopRelease.Mod mod, WorkshopRelease.Endpoint endpoint, JsonObject capabilities) {
        this(mod, endpoint, capabilities, System::nanoTime);
    }
    WorkshopCompatibility(WorkshopRelease.Mod mod, WorkshopRelease.Endpoint endpoint, JsonObject capabilities, LongSupplier clock) {
        this.mod = mod; this.endpoint = endpoint; this.capabilities = capabilities.deepCopy(); this.clock = clock;
    }
    synchronized void source(Source source) { if (this.source == null) this.source = source; }

    /** Refresh on relay connect/reconnect and before writes when expired. This
     * runs on existing relay/API workers, never on a Minecraft tick thread.
     * Failures replace an expired approval rather than permitting stale edits. */
    synchronized void refresh(boolean force) {
        if (closed) return;
        long now = clock.getAsLong();
        if (!force && checked != Long.MIN_VALUE && now - checked >= 0 && now - checked < TTL) return;
        try {
            if (source == null) throw new IOException("Metadata source unavailable");
            Response response = source.fetch();
            if (response.status == 404 && endpoint.legacyMetadataAllowed())
                state = new State("legacy", "Selected legacy relay has no build-info; protocol v1 remains available", null, null, true);
            else if (response.status != 200) throw new IOException("Metadata endpoint unavailable");
            else state = assess(TemplateJson.parse(response.body, MAX_BYTES), mod, endpoint, capabilities);
        } catch (IOException | RuntimeException unavailable) {
            // Do not echo remote bodies, URLs with credentials, or parser errors.
            state = new State("unavailable", "Website compatibility metadata is unavailable or invalid; editing and publishing are paused", null, null, false);
        }
        if (closed) state = new State("unavailable", "Workshop connection closed", null, null, false);
        checked = clock.getAsLong();
    }
    void requireWritable() throws IOException { refresh(false); requireCurrent(); }
    void requireCurrent() throws IOException {
        State current = currentState();
        if (!current.writable) throw new Blocked(current.reason, current.name.equals("incompatible") ? 409 : 503);
    }
    private State currentState() {
        State current = state;
        long age = clock.getAsLong() - checked;
        if (closed || checked == Long.MIN_VALUE || age < 0 || age >= TTL)
            return new State("unavailable", "Website compatibility check expired or connection closed; retry after revalidation", current.webVersion, current.build, false);
        return current;
    }
    void close() { closed = true; state = new State("unavailable", "Workshop connection closed", null, null, false); }
    void attach(HttpClient client) { source(http(client, endpoint.origin())); }
    static final class Blocked extends IOException {
        final int status;
        Blocked(String reason, int status) { super(reason); this.status = status; }
    }
    void describe(JsonObject result) {
        result.addProperty("mod_version", mod.version()); result.addProperty("mod_channel", mod.channel().name);
        result.addProperty("workshop_channel", endpoint.channelName()); result.addProperty("workshop_origin", endpoint.origin().toString());
        var value = new JsonObject(); State current = currentState();
        value.addProperty("state", current.name); value.addProperty("reason", current.reason);
        value.addProperty("writable", current.writable);
        value.addProperty("web_version", current.webVersion); value.addProperty("build", current.build);
        result.add("workshop_compatibility", value);
    }

    static State assess(JsonObject info, WorkshopRelease.Mod mod, WorkshopRelease.Endpoint endpoint, JsonObject capabilities) throws IOException {
        String channel = text(info, "channel", 16), build = text(info, "build", 64);
        if (!List.of("production", "beta", "dev").contains(channel) || !build.matches("[a-f0-9]{64}")) throw new IOException("Invalid website identity");
        if (endpoint.channel() != null && !endpoint.channel().websiteName.equals(channel)) throw new IOException("Website channel does not match selected origin");
        String webVersion = info.has("web_version") && info.get("web_version").isJsonNull() ? null : text(info, "web_version", 64);
        List<String> versions = names(info, "compatible_mod_versions", 64), required = names(info, "required_capabilities", 32);
        if (!versions.contains(mod.version())) return new State("incompatible", "Website does not declare support for mod " + mod.version(), webVersion, build, false);
        for (String capability : required) {
            if (!Set.of("preset_media", "draft_media", "media_sources", "item_catalog", "sound_uploads").contains(capability))
                return new State("incompatible", "Website requires unsupported capability: " + capability, webVersion, build, false);
            var supported = capabilities.get(capability);
            boolean enabled = supported != null && (supported.isJsonPrimitive() && supported.getAsJsonPrimitive().isBoolean() && supported.getAsBoolean()
                    || supported.isJsonObject() && supported.getAsJsonObject().has("enabled") && supported.getAsJsonObject().get("enabled").isJsonPrimitive()
                    && supported.getAsJsonObject().getAsJsonPrimitive("enabled").isBoolean() && supported.getAsJsonObject().get("enabled").getAsBoolean());
            if (!enabled) return new State("incompatible", "Website requires unsupported capability: " + capability, webVersion, build, false);
        }
        return new State("compatible", "Website explicitly supports the connected mod and required capabilities", webVersion, build, true);
    }
    private static String text(JsonObject object, String key, int max) throws IOException {
        var value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IOException("Invalid metadata field");
        String text = value.getAsString();
        if (text.isEmpty() || text.length() > max || !text.matches("[0-9A-Za-z][0-9A-Za-z.+_-]*")) throw new IOException("Invalid metadata value");
        return text;
    }
    private static List<String> names(JsonObject object, String key, int max) throws IOException {
        var value = object.get(key); if (value == null || !value.isJsonArray() || value.getAsJsonArray().size() > max) throw new IOException("Invalid metadata list");
        var names = new ArrayList<String>();
        for (var item : value.getAsJsonArray()) {
            var entry = new JsonObject(); entry.add("value", item); String name = text(entry, "value", 64);
            if (names.contains(name)) throw new IOException("Duplicate metadata entry"); names.add(name);
        }
        return List.copyOf(names);
    }

    /** Public GET only: no credential/cookie/auth headers, redirects or alternate
     * hosts. Total wait and body size are bounded; timeout cancels the request. */
    static Source http(HttpClient client, URI origin) {
        return () -> {
            if (client.followRedirects() != HttpClient.Redirect.NEVER || client.cookieHandler().isPresent() || client.authenticator().isPresent())
                throw new IOException("Metadata client must not use redirects, cookies or authentication");
            var request = HttpRequest.newBuilder(origin.resolve("/build-info.json")).timeout(Duration.ofSeconds(5)).GET().build();
            var pending = client.sendAsync(request, response -> new BoundedBody());
            try {
                var response = pending.get(5, TimeUnit.SECONDS);
                if (!response.uri().equals(request.uri())) throw new IOException("Unexpected metadata origin");
                return new Response(response.statusCode(), response.body());
            } catch (Exception unavailable) {
                pending.cancel(true); if (unavailable instanceof InterruptedException) Thread.currentThread().interrupt();
                throw new IOException("Website metadata could not be read");
            }
        };
    }
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription subscription) { this.subscription = subscription; subscription.request(1); }
        @Override public void onNext(List<ByteBuffer> data) {
            for (ByteBuffer buffer : data) {
                if (buffer.remaining() > MAX_BYTES - bytes.size()) { subscription.cancel(); result.completeExceptionally(new IOException("Metadata exceeds size limit")); return; }
                byte[] piece = new byte[buffer.remaining()]; buffer.get(piece); bytes.writeBytes(piece);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { result.completeExceptionally(error); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
