package clockwork.web;

import com.google.gson.*;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Authenticated same-origin API. Cookies are HttpOnly; CSRF is session-bound.
 * The static site remains public, while template bytes/listings require an owner.
 * Relay file workers own request bodies and file I/O; the supplied guard performs only
 * short permission queries on Minecraft's main thread.
 */
public final class TemplateApi {
    private static final class Denied extends IOException { Denied() { super("Session expired or owner access revoked before save/publish commit"); } }
    private static final Logger LOG = LoggerFactory.getLogger("clockwork_bosses_web");
    private final AccessSessions access;
    private final PlayerSkins skins = new PlayerSkins();
    private final AudioUploadService uploads = new AudioUploadService();
    private final TemplateFiles files;
    private final ItemCatalog items;
    private final String origin, authority, cookieName, workshopUrl;
    private final boolean secure;
    TemplateApi(String origin, AccessSessions access, TemplateFiles files, String serverId) throws IOException { this(origin, access, files, serverId, null); }
    TemplateApi(String origin, AccessSessions access, TemplateFiles files, String serverId, ItemCatalog items) throws IOException {
        this.items = items;
        URI uri;
        try { uri = URI.create(origin); } catch (IllegalArgumentException invalid) { throw new IOException("Invalid workshop origin"); }
        if (uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null || !uri.getRawPath().isEmpty()) throw new IOException("Workshop origin must have no trailing slash/path");
        secure = "https".equals(uri.getScheme());
        if (!secure && !("http".equals(uri.getScheme()) && (uri.getHost().equals("127.0.0.1") || uri.getHost().equals("[::1]")))) throw new IOException("Workshop origin must use HTTPS; loopback HTTP is accepted only for tests");
        int port = uri.getPort();
        if (port == 0 || port > 65535) throw new IOException("Invalid workshop origin port");
        authority = uri.getHost().toLowerCase(Locale.ROOT) + (port != -1 && port != (secure ? 443 : 80) ? ":" + port : "");
        this.origin = (secure ? "https://" : "http://") + authority;
        if (serverId == null || !serverId.matches("[a-f0-9]{64}")) throw new IOException("Invalid relay identity");
        cookieName = (secure ? "__Host-clockwork_workshop_" : "clockwork_workshop_") + serverId;
        workshopUrl = this.origin + "/s/" + serverId + "/";
        this.access = access; this.files = files;
    }
    public boolean itemCatalogAvailable() { return items != null; }
    public String workshopUrl() { return workshopUrl; }
    public AccessSessions access() { return access; }
    public void close() { access.clear(); skins.clear(); }

    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod(), path = exchange.getRequestURI().getRawPath();
        try {
            // Never trust Forwarded/X-Forwarded-* from arbitrary HTTP callers.
            var hosts = exchange.getRequestHeaders().get("Host");
            if (hosts == null || hosts.size() != 1 || !authority.equals(hosts.getFirst().toLowerCase(Locale.ROOT))) { json(exchange, 403, error("Unexpected host")); return; }
            String suppliedOrigin = exchange.getRequestHeaders().getFirst("Origin");
            if (suppliedOrigin != null && !suppliedOrigin.equals(origin)) { json(exchange, 403, error("Unexpected origin")); return; }
            boolean changing = method.equals("POST") || method.equals("PUT");
            if (changing && !origin.equals(suppliedOrigin)) { json(exchange, 403, error("Same-origin request required")); return; }
            if (path.equals("/api/status") && method.equals("GET")) { var result = object("enabled", true); result.addProperty("preset_media", true); result.addProperty("draft_media", true); result.addProperty("media_sources", true); result.addProperty("item_catalog", items != null); result.add("sound_uploads", AudioUploadService.capabilities()); json(exchange, 200, result); return; }
            if (path.equals("/api/login") && method.equals("POST")) {
                if (!access.permit("login", 20)) { json(exchange, 429, error("Too many login attempts; retry in a minute")); return; }
                JsonObject body = TemplateJson.parse(body(exchange, 1024));
                String code = body.has("code") && body.get("code").isJsonPrimitive() && body.getAsJsonPrimitive("code").isString() ? body.get("code").getAsString() : "";
                AccessSessions.Login login = access.redeem(code);
                if (login == null) { json(exchange, 401, error("Login link expired, already used, or owner permission unavailable. Run /boss workshop open again.")); return; }
                cookie(exchange, login.token(), 1800);
                json(exchange, 200, identity(login.session())); return;
            }
            String token = token(exchange);
            AccessSessions.Session session = path.equals("/api/logout") && method.equals("POST") ? access.current(token) : access.authenticate(token);
            if (session == null) { cookie(exchange, "", 0); json(exchange, 401, error("Sign in using /boss workshop open on the online-mode server")); return; }
            if (changing && !equal(session.csrf(), exchange.getRequestHeaders().getFirst("X-Workshop-CSRF"))) { json(exchange, 403, error("Session CSRF check failed")); return; }
            if (path.equals("/api/session") && method.equals("GET")) { json(exchange, 200, identity(session)); return; }
            if (path.equals("/api/logout") && method.equals("POST")) { access.logout(token); cookie(exchange, "", 0); json(exchange, 200, object("logged_out", true)); return; }
            if (path.equals("/api/templates") && method.equals("GET")) {
                JsonArray list = new JsonArray();
                for (var entry : files.listing()) {
                    JsonObject item = new JsonObject(); item.addProperty("name", entry.name()); item.addProperty("active_revision", entry.activeRevision());
                    item.addProperty("draft_revision", entry.draftRevision()); item.addProperty("base_revision", entry.baseRevision()); list.add(item);
                }
                JsonObject result = new JsonObject(); result.add("templates", list); json(exchange, 200, result); return;
            }
            if (path.startsWith("/api/items/") && method.equals("GET")) {
                if (items == null) { json(exchange, 503, error("Item catalog unavailable; use manual item IDs")); return; }
                json(exchange, 200, items.page(path)); return;
            }
            String[] parts = path.split("/", -1);
            if (parts.length == 4 && parts[1].equals("api") && parts[2].equals("draft-audio") && method.equals("GET")) {
                var listing = files.uploadListing(parts[3]);
                if (access.recheck(token, session) == null) throw new Denied();
                JsonObject result = new JsonObject(); result.addProperty("name", listing.name()); result.addProperty("revision", listing.revision()); result.addProperty("storage_bytes", listing.storageBytes());
                JsonArray entries = new JsonArray();
                for (var file : listing.files()) { JsonObject entry = new JsonObject(); entry.addProperty("kind",file.kind()); entry.addProperty("filename",file.filename()); entry.addProperty("revision",file.revision()); entry.addProperty("bytes",file.bytes()); entry.addProperty("uploaded",file.uploaded()); entries.add(entry); }
                result.add("files", entries); json(exchange,200,result); return;
            }
            if (parts.length == 6 && parts[1].equals("api") && parts[2].equals("draft-audio") && method.equals("PUT")) {
                TemplateFiles.name(parts[3]); DraftSounds.relative(parts[4],parts[5]);
                String expected = revision(exchange), fileRevision = singleHeader(exchange,"X-Sound-Revision"); TemplateFiles.revision(fileRevision);
                if (!fileRevision.equals("none") && !"1".equals(singleHeader(exchange,"X-Sound-Overwrite"))) throw new IOException("Confirm the existing sound overwrite before uploading");
                if (!access.permit("sound-upload",12)) { exchange.getResponseHeaders().set("Retry-After","60"); json(exchange,429,error("Sound upload limit reached (12 per minute); retry in a minute")); return; }
                var snapshot = uploads.upload(exchange,files,parts[3],parts[4],parts[5],expected,fileRevision,() -> { if (access.recheck(token,session)==null) throw new Denied(); });
                audit(session,"upload-draft-sound",parts[3],snapshot.revision());
                JsonObject result = new JsonObject(); result.addProperty("revision",snapshot.revision()); result.addProperty("base_revision",snapshot.baseRevision()); result.addProperty("filename",parts[5]); result.addProperty("kind",parts[4]); json(exchange,200,result); return;
            }
            if ((parts.length == 6 || parts.length == 7 && mediaKind(parts[3])) && parts[1].equals("api") && parts[2].equals("audio") && (method.equals("GET") || method.equals("HEAD"))) {
                int offset = parts.length == 7 ? 1 : 0;
                byte[] audio;
                try { audio = files.audio(offset == 1 ? parts[3] : "active", parts[3 + offset], parts[4 + offset], parts[5 + offset]); }
                catch (java.nio.file.NoSuchFileException missing) { json(exchange, 404, error("Sound file is missing from this server; choose another filename or a matching local OGG")); return; }
                // A sign-out/revocation during the disk read cannot release audio.
                if (access.recheck(token, session) == null) { json(exchange, 401, error("Session expired or owner access revoked before playback")); return; }
                exchange.getResponseHeaders().set("Content-Disposition", "inline; filename=\"" + parts[5 + offset] + "\"");
                exchange.getResponseHeaders().set("Accept-Ranges", "none");
                respond(exchange, 200, audio, "audio/ogg"); return;
            }
            if ((parts.length == 4 || parts.length == 5 && mediaKind(parts[3])) && parts[1].equals("api") && parts[2].equals("sounds") && method.equals("GET")) {
                String source = parts.length == 5 ? parts[3] : "active";
                TemplateFiles.Sounds soundFiles;
                try { soundFiles = files.sounds(source, parts[parts.length - 1]); }
                catch (java.nio.file.NoSuchFileException missing) { json(exchange, 404, error("Draft media snapshot is missing. Save draft again to copy its folders and sounds.")); return; }
                JsonObject result = new JsonObject();
                result.addProperty("name", soundFiles.name());
                result.addProperty("source", source);
                JsonArray sounds = new JsonArray(), voices = new JsonArray();
                soundFiles.sounds().forEach(sounds::add); soundFiles.voices().forEach(voices::add);
                result.add("sounds", sounds); result.add("voices", voices);
                JsonArray uploadedSounds=new JsonArray(),uploadedVoices=new JsonArray();soundFiles.uploadedSounds().forEach(uploadedSounds::add);soundFiles.uploadedVoices().forEach(uploadedVoices::add);
                result.add("uploaded_sounds",uploadedSounds);result.add("uploaded_voices",uploadedVoices);
                result.addProperty("sounds_truncated", soundFiles.soundsTruncated()); result.addProperty("voices_truncated", soundFiles.voicesTruncated());
                json(exchange, 200, result); return;
            }
            if (parts.length == 4 && parts[2].equals("skins") && method.equals("GET")) {
                if (!access.permit("skins", 12)) { json(exchange, 429, error("Skin lookup limit reached; retry in a minute")); return; }
                json(exchange, 200, skins.fetch(parts[3])); return;
            }
            if (parts.length == 4 && parts[1].equals("api") && (parts[2].equals("active") || parts[2].equals("drafts"))) {
                String name = parts[3]; TemplateFiles.name(name); boolean draft = parts[2].equals("drafts");
                if (method.equals("GET") || method.equals("HEAD")) {
                    var snapshot = draft ? files.draft(name) : files.active(name);
                    if (snapshot.content() == null) { json(exchange, 404, error("Template does not exist")); return; }
                    exchange.getResponseHeaders().set("ETag", quote(snapshot.revision()));
                    exchange.getResponseHeaders().set("X-Active-Revision", draft ? snapshot.baseRevision() : snapshot.revision());
                    if (draft) exchange.getResponseHeaders().set("X-Workshop-Preset", snapshot.preset().isEmpty() ? "none" : snapshot.preset());
                    exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + name + ".json\"");
                    respond(exchange, 200, snapshot.content(), "application/json; charset=utf-8"); return;
                }
                if (draft && method.equals("PUT")) {
                    String expected = revision(exchange);
                    String base = exchange.getRequestHeaders().getFirst("X-Active-Revision");
                    byte[] content = body(exchange, TemplateJson.MAX_BYTES);
                    if (access.recheck(token, session) == null) { json(exchange, 401, error("Session expired or owner access revoked during upload")); return; }
                    var presetHeaders = exchange.getRequestHeaders().get("X-Workshop-Preset");
                    if (presetHeaders != null && presetHeaders.size() != 1) throw new IOException("One preset header is required");
                    var snapshot = files.save(name, content, expected, base, exchange.getRequestHeaders().getFirst("X-Workshop-Preset"), singleHeader(exchange,"X-Workshop-Draft-Source"), () -> { if (access.recheck(token, session) == null) throw new Denied(); });
                    audit(session, "save-draft", name, snapshot.revision());
                    JsonObject result = new JsonObject(); result.addProperty("revision", snapshot.revision()); result.addProperty("base_revision", snapshot.baseRevision()); result.addProperty("preset", snapshot.preset()); json(exchange, 200, result); return;
                }
            }
            if (parts.length == 4 && parts[1].equals("api") && parts[2].equals("publish") && method.equals("POST")) {
                String name = parts[3]; TemplateFiles.name(name); body(exchange, 1024);
                if (access.recheck(token, session) == null) { json(exchange, 401, error("Session expired or owner access revoked before publish")); return; }
                String revision = files.publish(name, revision(exchange), () -> { if (access.recheck(token, session) == null) throw new Denied(); }); audit(session, "publish", name, revision);
                JsonObject result = new JsonObject(); result.addProperty("revision", revision); result.addProperty("reload_required", true); json(exchange, 200, result); return;
            }
            json(exchange, 405, error("Unsupported template operation"));
        } catch (AccessSessions.RateLimited limited) { exchange.getResponseHeaders().set("Retry-After", "60"); json(exchange, 429, error("Request limit reached; retry in a minute")); }
        catch (Denied denied) { cookie(exchange, "", 0); json(exchange, 401, error(denied.getMessage())); }
        catch (AudioUploadService.Busy busy) { exchange.getResponseHeaders().set("Retry-After","5"); json(exchange,429,error(busy.getMessage())); }
        catch (TemplateFiles.Conflict conflict) { json(exchange, 409, error(conflict.getMessage())); }
        catch (IOException invalid) { if (exchange.getResponseCode() == -1) json(exchange, 400, error(invalid.getMessage())); }
        catch (RuntimeException failure) { LOG.error("Workshop API operation failed (no request bodies or credentials logged)", failure); json(exchange, 500, error("Operation failed; check server logs")); }
    }
    private byte[] body(HttpExchange exchange, int max) throws IOException {
        String type = exchange.getRequestHeaders().getFirst("Content-Type");
        if (type == null || !type.toLowerCase(Locale.ROOT).matches("application/json(?:;\\s*charset=utf-8)?")) throw new IOException("Use application/json UTF-8");
        String length = exchange.getRequestHeaders().getFirst("Content-Length");
        try { if (length != null && (Long.parseLong(length) < 0 || Long.parseLong(length) > max)) throw new IOException("Request body exceeds limit"); }
        catch (NumberFormatException invalid) { throw new IOException("Invalid body length"); }
        byte[] bytes = exchange.getRequestBody().readNBytes(max + 1);
        if (bytes.length > max) throw new IOException("Request body exceeds limit"); return bytes;
    }
    private String revision(HttpExchange exchange) throws IOException {
        String value = singleHeader(exchange,"If-Match");
        if (value == null || !value.matches("\"(?:none|[a-f0-9]{64})\"")) throw new IOException("If-Match revision required; use quoted none to create");
        return value.substring(1, value.length() - 1);
    }
    private static String singleHeader(HttpExchange exchange,String name) throws IOException { var values=exchange.getRequestHeaders().get(name); if(values!=null&&values.size()!=1)throw new IOException("One "+name+" header is required");return values==null?null:values.getFirst(); }
    private String token(HttpExchange exchange) {
        String cookie = exchange.getRequestHeaders().getFirst("Cookie"), token = "";
        if (cookie == null || cookie.length() > 4096) return "";
        for (String part : cookie.split(";")) {
            String item = part.trim();
            if (item.startsWith(cookieName + "=")) { if (!token.isEmpty()) return ""; token = item.substring(cookieName.length() + 1); }
        }
        return token;
    }
    private void cookie(HttpExchange exchange, String value, int seconds) {
        exchange.getResponseHeaders().set("Set-Cookie", cookieName + "=" + value + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=" + seconds + (secure ? "; Secure" : ""));
    }
    private static boolean equal(String expected, String actual) { return actual != null && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8)); }
    private static boolean mediaKind(String source) { return source.equals("active") || source.equals("drafts") || source.equals("presets"); }
    private JsonObject identity(AccessSessions.Session session) { JsonObject value = new JsonObject(); value.addProperty("username", session.owner().name()); value.addProperty("csrf", session.csrf()); value.addProperty("expires_at", session.expiresAt()); return value; }
    private static JsonObject object(String name, boolean value) { JsonObject object = new JsonObject(); object.addProperty(name, value); return object; }
    private static JsonObject error(String message) { JsonObject object = new JsonObject(); object.addProperty("error", message); return object; }
    private static String quote(String revision) { return "\"" + revision + "\""; }
    private static void json(HttpExchange exchange, int status, JsonObject value) throws IOException { respond(exchange, status, value.toString().getBytes(StandardCharsets.UTF_8), "application/json; charset=utf-8"); }
    private static void respond(HttpExchange exchange, int status, byte[] body, String type) throws IOException {
        var headers = exchange.getResponseHeaders();
        headers.set("Content-Type", type); headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Cache-Control", "no-store"); headers.set("Referrer-Policy", "no-referrer");
        headers.set("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
        headers.set("Content-Length", Integer.toString(body.length));
        exchange.sendResponseHeaders(status, exchange.getRequestMethod().equals("HEAD") ? -1 : body.length);
        if (!exchange.getRequestMethod().equals("HEAD")) try (var output = exchange.getResponseBody()) { output.write(body); }
    }
    private static void audit(AccessSessions.Session session, String action, String name, String revision) { LOG.info("Workshop {} owner={} uuid={} template={} revision={}", action, session.owner().name(), session.owner().id(), name, revision); }
}
