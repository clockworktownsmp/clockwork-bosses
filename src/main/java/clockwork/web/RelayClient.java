package clockwork.web;

import com.google.gson.*;
import com.sun.net.httpserver.Headers;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Outbound TLS only. Four file/API workers, bounded uploads, credited 32 KiB
 * response chunks, idle heartbeat, and bounded retry/shutdown. No work runs on
 * Minecraft's tick thread except the existing owner-permission query. */
final class RelayClient implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger("clockwork_bosses_web");
    private static final int FRAME_LIMIT = 768 * 1024;
    private static final String NAME = "[a-z0-9][a-z0-9_-]{0,63}", PRESET = "(?:1_player|[2-6]_players|boss-sounds-example)";
    private static final String MEDIA = "(?:(?:active|drafts)/" + NAME + "|presets/" + PRESET + ")";
    private static final Pattern READ = Pattern.compile("/api/(?:status|session|templates|(?:active|drafts|sounds)/" + NAME + "|sounds/" + MEDIA + "|skins/[A-Za-z0-9_]{1,16}|audio/(?:" + NAME + "|" + MEDIA + ")/(?:sounds|voices)/[a-z0-9][a-z0-9._-]{0,63}\\.ogg)");
    private static final Set<String> HEADERS = Set.of("Host", "Content-Type", "Content-Length", "Origin", "If-Match", "X-Active-Revision", "X-Workshop-CSRF", "X-Workshop-Preset", "X-Workshop-Draft-Source", "X-Sound-Revision", "X-Sound-Overwrite", "X-Sound-Purpose", "Cookie");
    interface ApiFactory { TemplateApi create() throws IOException; }
    private final URI socketUrl;
    private final RelayIdentity identity;
    private final ApiFactory factory;
    private final Consumer<TemplateApi> available;
    private final ScheduledThreadPoolExecutor schedule = new ScheduledThreadPoolExecutor(1, r -> daemon(r, "clockwork-relay-control"));
    private final ExecutorService network = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(128), r -> daemon(r, "clockwork-relay-network"), new ThreadPoolExecutor.AbortPolicy());
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(12), r -> daemon(r, "clockwork-relay-files"), new ThreadPoolExecutor.AbortPolicy());
    private final HttpClient http;
    private final Set<Connection> connections = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    // Authentication belongs to this running companion, not to a TCP socket.
    // A transient relay reconnect retains bounded credentials/caches; stopping
    // the companion clears them, and every authenticated request rechecks owner
    // permission. Failed operations are canceled and are never replayed.
    private TemplateApi lifetimeApi;
    private volatile Connection current;
    private int failures;
    private ScheduledFuture<?> retryTask;

    static URI origin(String value, boolean loopbackTest) throws IOException {
        URI uri;
        try { uri = URI.create(value); } catch (IllegalArgumentException invalid) { throw new IOException("Invalid relay_url"); }
        boolean local = loopbackTest && "http".equals(uri.getScheme()) && ("127.0.0.1".equals(uri.getHost()) || "[::1]".equals(uri.getHost()));
        if ((!"https".equals(uri.getScheme()) && !local) || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null || !uri.getRawPath().isEmpty() || uri.getPort() == 0 || uri.getPort() > 65535)
            throw new IOException("relay_url must be an HTTPS origin without a path, trailing slash, or credentials");
        // Browser Origin serializes host case/default ports canonically. Use
        // that same exact origin for websocket, links, metadata and key binding;
        // this never changes a configured host or broadens origin checks.
        try { return new URI(uri.getScheme(), null, uri.getHost().toLowerCase(Locale.ROOT),
                uri.getPort() == (local ? 80 : 443) ? -1 : uri.getPort(), "", null, null); }
        catch (URISyntaxException invalid) { throw new IOException("Invalid relay_url origin"); }
    }
    RelayClient(URI origin, RelayIdentity identity, ApiFactory factory, Consumer<TemplateApi> available) {
        this.identity = identity; this.factory = factory; this.available = available;
        schedule.setRemoveOnCancelPolicy(true);
        socketUrl = URI.create((origin.getScheme().equals("https") ? "wss" : "ws") + "://" + origin.getRawAuthority() + "/relay/" + identity.id() + "/connect");
        http = HttpClient.newBuilder().executor(network).connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
        schedule.execute(this::connect);
        schedule.scheduleWithFixedDelay(this::heartbeat, 30, 30, TimeUnit.SECONDS);
    }
    private static Thread daemon(Runnable runnable, String name) { Thread thread = new Thread(runnable, name); thread.setDaemon(true); return thread; }
    private void connect() {
        if (closed) return;
        TemplateApi api;
        synchronized (this) { api = lifetimeApi; }
        try { if (api == null) api = factory.create(); }
        catch (IOException failure) { LOG.warn("Workshop relay could not open local template storage; retrying. Check its paths and permissions."); retry(); return; }
        synchronized (this) {
            if (closed) { api.close(); return; }
        }
        // Read-only website metadata uses this same origin/client. No new
        // websocket protocol or automatic routing negotiation is introduced.
        api.connectCompatibility(http);
        synchronized (this) {
            if (closed) { api.close(); return; }
            lifetimeApi = api;
            Connection connection = new Connection(api);
            current = connection; connections.add(connection);
            connection.readyTimeout = schedule.schedule(() -> { if (!connection.ready) disconnected(connection); }, 15, TimeUnit.SECONDS);
            try {
                connection.connecting = http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + identity.secret()).header("X-Workshop-Media-Sources", "1").header("X-Workshop-Sound-Uploads", "1").header("X-Workshop-Item-Catalog", api.itemCatalogAvailable() ? "1" : "0").buildAsync(socketUrl, connection);
                connection.connecting.whenComplete((socket, error) -> { if (error != null) disconnected(connection); else if (closed || connection.dead) socket.abort(); });
            }
            catch (RuntimeException unavailable) { disconnected(connection); }
        }
    }
    private synchronized void retry() {
        if (closed) return;
        if (retryTask != null && !retryTask.isDone()) return;
        int delay = retryDelay(failures, ThreadLocalRandom.current().nextInt(2)); failures = Math.min(6, failures + 1);
        try { retryTask = schedule.schedule(() -> { synchronized (RelayClient.this) { retryTask = null; } connect(); }, delay, TimeUnit.SECONDS); }
        catch (RejectedExecutionException stopping) { }
    }
    static int retryDelay(int failures, int jitter) { return Math.min(60, 1 << Math.max(0, Math.min(6, failures))) + Math.max(0, Math.min(1, jitter)); }
    private synchronized void disconnected(Connection connection) {
        if (connection.dead) return;
        connection.dead = true; connection.operations.values().forEach(Operation::cancel); connection.operations.clear();
        if (connection.readyTimeout != null) connection.readyTimeout.cancel(false);
        if (connection.connecting != null && !connection.connecting.isDone()) connection.connecting.cancel(true);
        if (connection.socket != null) connection.socket.abort(); connections.remove(connection);
        if (current == connection) { current = null; available.accept(null); if (!closed) { LOG.warn("Workshop relay disconnected; browser access paused. Retrying in the background."); retry(); } }
    }
    private void heartbeat() {
        Connection connection = current;
        if (connection == null || !connection.ready || connection.dead) return;
        if (System.nanoTime() - connection.seen > TimeUnit.SECONDS.toNanos(85)) { LOG.warn("Workshop relay heartbeat timed out"); disconnected(connection); return; }
        try { connection.sendText("ping"); } catch (IOException failure) { disconnected(connection); }
    }
    private final class Connection implements WebSocket.Listener {
        final TemplateApi api;
        final Map<String,Operation> operations = new ConcurrentHashMap<>();
        final StringBuilder fragment = new StringBuilder();
        final Object wire = new Object();
        volatile WebSocket socket;
        volatile boolean ready, dead;
        volatile ScheduledFuture<?> readyTimeout;
        volatile CompletableFuture<WebSocket> connecting;
        volatile long seen = System.nanoTime();
        Connection(TemplateApi api) { this.api = api; }
        @Override public void onOpen(WebSocket socket) { this.socket = socket; if (closed || dead) socket.abort(); else socket.request(1); }
        @Override public CompletionStage<?> onText(WebSocket socket, CharSequence text, boolean last) {
            if (dead || closed) return null;
            try {
                if (fragment.length() + text.length() > FRAME_LIMIT) throw new IOException("Frame exceeds limit");
                fragment.append(text);
                if (last) { String frame = fragment.toString(); fragment.setLength(0); receive(frame); }
                socket.request(1);
            } catch (IOException | RuntimeException invalid) { LOG.warn("Workshop relay rejected a frame ({})", invalid.getClass().getSimpleName()); disconnected(this); }
            return null;
        }
        @Override public CompletionStage<?> onBinary(WebSocket socket, java.nio.ByteBuffer data, boolean last) { LOG.warn("Workshop relay received an unexpected binary frame"); disconnected(this); return null; }
        @Override public CompletionStage<?> onClose(WebSocket socket, int status, String reason) { if (!closed && !dead) LOG.warn("Workshop relay socket closed (status {})", status); disconnected(this); return null; }
        @Override public void onError(WebSocket socket, Throwable error) { if (!closed && !dead) LOG.warn("Workshop relay transport failed ({})", error.getClass().getSimpleName()); disconnected(this); }
        void receive(String text) throws IOException {
            if (text.equals("pong")) { seen = System.nanoTime(); return; }
            JsonObject frame = TemplateJson.parse(text.getBytes(StandardCharsets.UTF_8), FRAME_LIMIT);
            if (frame.get("v") == null || !frame.get("v").isJsonPrimitive() || !frame.get("v").getAsString().equals("1")) throw new IOException("Version mismatch");
            String type = string(frame, "t", 16); seen = System.nanoTime();
            if (type.equals("ready")) {
                synchronized (RelayClient.this) {
                    if (ready || dead || closed || current != this) throw new IOException("Unexpected ready");
                    ready = true; failures = 0; if (readyTimeout != null) readyTimeout.cancel(false); available.accept(api);
                    LOG.info("Workshop relay connected; owners can run /boss workshop open. Templates remain on this server.");
                }
                return;
            }
            if (!ready) throw new IOException("Relay is not ready");
            String id = string(frame, "id", 32); if (!id.matches("[a-f0-9]{32}")) throw new IOException("Invalid request identity");
            if(type.equals("upload-data")||type.equals("upload-end")){
                Operation operation=operations.get(id);if(operation==null||operation.canceled)return;
                if(operation.upload==null)throw new IOException("Unexpected upload data");
                if(type.equals("upload-end"))operation.upload.end();
                else {if(!frame.has("seq")||!frame.get("seq").isJsonPrimitive()||!frame.get("seq").getAsString().matches("[0-9]{1,4}"))throw new IOException("Invalid upload sequence");byte[] bytes;try{bytes=Base64.getDecoder().decode(string(frame,"body",43692));}catch(IllegalArgumentException bad){throw new IOException("Invalid upload encoding");}operation.upload.accept(frame.get("seq").getAsInt(),bytes);}
                return;
            }
            if (type.equals("credit") || type.equals("cancel")) {
                Operation operation = operations.get(id); if (operation == null) return;
                if (type.equals("cancel")) operation.cancel();
                else { if (!frame.has("n") || !frame.get("n").getAsString().equals("1") || operation.credits.availablePermits() >= 2) throw new IOException("Invalid stream credit"); operation.credits.release(); }
                return;
            }
            if (!type.equals("request")) throw new IOException("Unknown relay frame");
            if (operations.size() >= 16 || operations.containsKey(id)) { sendError(id); return; }
            String method = string(frame, "method", 4), path = string(frame, "path", 240);
            if (!routeAllowed(method, path)) throw new IOException("Unsupported workshop operation");
            if (!frame.has("headers") || !frame.get("headers").isJsonObject() || frame.getAsJsonObject("headers").size() > HEADERS.size()) throw new IOException("Invalid headers");
            Headers headers = new Headers();
            for (var entry : frame.getAsJsonObject("headers").entrySet()) {
                if (!HEADERS.contains(entry.getKey())) throw new IOException("Unexpected header");
                String value = string(frame.getAsJsonObject("headers"), entry.getKey(), 4096); if (value.contains("\r") || value.contains("\n")) throw new IOException("Invalid header"); headers.set(entry.getKey(), value);
            }
            String encoded = string(frame, "body", 699052);
            byte[] body; try { body = Base64.getDecoder().decode(encoded); } catch (IllegalArgumentException invalid) { throw new IOException("Invalid request body"); }
            Operation operation = new Operation(this, id); operations.put(id, operation);
            boolean streaming=frame.has("stream")&&frame.get("stream").isJsonPrimitive()&&frame.get("stream").getAsJsonPrimitive().isBoolean()&&frame.get("stream").getAsBoolean();
            InputStream input;
            if(streaming){
                if(!method.equals("PUT")||!uploadRoute(path)||body.length!=0)throw new IOException("Invalid streamed operation");
                long length;try{length=Long.parseLong(headers.getFirst("Content-Length"));}catch(RuntimeException invalid){throw new IOException("Invalid upload length");}
                operation.upload=new UploadInput(length,()->{JsonObject credit=envelope("upload-credit",id);credit.addProperty("n",1);send(credit);});input=operation.upload;
            }else{
                int limit = method.equals("PUT") ? TemplateJson.MAX_BYTES : 1024;
                if (uploadRoute(path)||body.length > limit || !Integer.toString(body.length).equals(headers.getFirst("Content-Length"))) throw new IOException("Invalid request length");input=new ByteArrayInputStream(body);
            }
            try { operation.future = workers.submit(() -> {
                try (var exchange = new RelayExchange(method, path, headers, input, operation)) {
                    if (!operation.canceled && !dead && !closed) api.handle(exchange);
                } catch (IOException failure) { operation.error(); }
                finally { operations.remove(id, operation); }
            }); if (operation.canceled) operation.cancel(); }
            catch (RejectedExecutionException busy) { operation.cancel(); if (!dead && !closed) sendError(id); }
        }
        void send(JsonObject frame) throws IOException { sendText(frame.toString()); }
        void sendError(String id) throws IOException { send(envelope("error", id)); }
        void sendText(String text) throws IOException {
            synchronized (wire) {
                if (dead || closed || socket == null) throw new IOException("Relay disconnected");
                try { socket.sendText(text, true).get(5, TimeUnit.SECONDS); }
                catch (Exception unavailable) { if (unavailable instanceof InterruptedException) Thread.currentThread().interrupt(); throw new IOException("Relay send failed"); }
            }
        }
    }
    private final class Operation implements RelayExchange.Sink {
        final Connection connection;
        final String id;
        final Semaphore credits = new Semaphore(0);
        volatile boolean canceled;
        volatile Future<?> future;
        UploadInput upload;
        int sequence;
        Operation(Connection connection, String id) { this.connection = connection; this.id = id; }
        void cancel() { canceled = true; if(upload!=null)upload.close(); credits.release(); if (future != null) { future.cancel(true); if (future instanceof Runnable task) workers.remove(task); } connection.operations.remove(id, this); }
        @Override public void begin(int status, Headers headers) throws IOException {
            if (canceled) throw new IOException("Request canceled");
            JsonObject frame = envelope("begin", id), values = new JsonObject(); frame.addProperty("status", status);
            for (var entry : headers.entrySet()) if (entry.getValue().size() == 1) values.addProperty(entry.getKey().toLowerCase(Locale.ROOT), entry.getValue().getFirst());
            frame.add("headers", values); connection.send(frame);
        }
        @Override public void data(byte[] bytes, int offset, int count) throws IOException {
            try { if (!credits.tryAcquire(10, TimeUnit.SECONDS)) throw new IOException("Relay consumer stalled"); }
            catch (InterruptedException stopped) { Thread.currentThread().interrupt(); throw new IOException("Relay operation canceled"); }
            if (canceled || closed || connection.dead) throw new IOException("Request canceled");
            JsonObject frame = envelope("data", id); frame.addProperty("seq", sequence++); frame.addProperty("body", Base64.getEncoder().encodeToString(Arrays.copyOfRange(bytes, offset, offset + count))); connection.send(frame);
        }
        @Override public void end() throws IOException { if (!canceled) connection.send(envelope("end", id)); }
        @Override public void error() { if (!canceled) try { connection.sendError(id); } catch (IOException ignored) { } }
    }
    static boolean routeAllowed(String method, String path) {
        if (method.equals("GET")) return READ.matcher(path).matches() || path.matches(ItemCatalog.ROUTE) || path.matches("/api/draft-audio/"+NAME);
        if (method.equals("HEAD")) return (path.startsWith("/api/audio/") || path.startsWith("/api/active/") || path.startsWith("/api/drafts/")) && READ.matcher(path).matches();
        if (method.equals("POST")) return path.equals("/api/login") || path.equals("/api/logout") || path.matches("/api/publish/[a-z0-9][a-z0-9_-]{0,63}");
        return method.equals("PUT") && (path.matches("/api/drafts/[a-z0-9][a-z0-9_-]{0,63}")||uploadRoute(path));
    }
    private static boolean uploadRoute(String path){return path.matches("/api/draft-audio/"+NAME+"/(?:sounds|voices)/[a-z0-9][a-z0-9._-]{0,63}\\.ogg");}
    private static String string(JsonObject object, String key, int limit) throws IOException {
        JsonElement value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IOException("Expected relay string");
        String text = value.getAsString(); if (text.length() > limit) throw new IOException("Relay string exceeds limit"); return text;
    }
    private static JsonObject envelope(String type, String id) { JsonObject frame = new JsonObject(); frame.addProperty("v", 1); frame.addProperty("t", type); frame.addProperty("id", id); return frame; }
    @Override public void close() {
        TemplateApi previous; List<Connection> active;
        synchronized (this) {
            if (closed) return; closed = true;
            previous = lifetimeApi; lifetimeApi = null; active = List.copyOf(connections);
            if (retryTask != null) retryTask.cancel(false);
        }
        // Do not hold the relay monitor while invoking another module's callback
        // or waiting for file workers. It can be taken by shutdown/reload races.
        available.accept(null); schedule.shutdownNow();
        for (Connection connection : active) disconnected(connection);
        if (previous != null) previous.close();
        workers.shutdownNow(); http.shutdownNow(); network.shutdownNow();
        try { workers.awaitTermination(2, TimeUnit.SECONDS); }
        catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
    }
}
