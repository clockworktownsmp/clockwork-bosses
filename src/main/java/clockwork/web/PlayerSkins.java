package clockwork.web;

import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;

/** HTTP-worker-only public skin lookup. Fixed HTTPS services, no redirects or disk cache. */
final class PlayerSkins {
    private record Cached(JsonObject value, long expires) {}
    private final Map<String, Cached> cache = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.Semaphore pending = new java.util.concurrent.Semaphore(1);
    private volatile boolean closed;
    private HttpURLConnection activeConnection;
    JsonObject fetch(String name) throws IOException {
        if (closed) throw new IOException("Workshop is stopping");
        if (!name.matches("[A-Za-z0-9_]{1,16}")) throw new IOException("Enter a Minecraft Java username (1–16 letters, numbers or underscores)");
        String key = name.toLowerCase(Locale.ROOT);
        Cached cached = cache.get(key);
        if (cached != null && cached.expires > System.currentTimeMillis()) return cached.value.deepCopy();
        if (!pending.tryAcquire()) throw new IOException("A skin lookup is already running; retry shortly");
        long deadline = System.nanoTime() + 15_000_000_000L;
        try {
            JsonObject profile = json(read(URI.create("https://api.mojang.com/users/profiles/minecraft/" + name), 16384, deadline));
            String id = profile.get("id").getAsString();
            if (!id.matches("[a-fA-F0-9]{32}")) throw new IOException("Invalid profile response");
            JsonObject details = json(read(URI.create("https://sessionserver.mojang.com/session/minecraft/profile/" + id), 32768, deadline));
            JsonObject skin = null;
            for (JsonElement property : details.getAsJsonArray("properties")) {
                JsonObject value = property.getAsJsonObject();
                if ("textures".equals(value.get("name").getAsString())) {
                    byte[] decoded = Base64.getDecoder().decode(value.get("value").getAsString());
                    skin = json(decoded).getAsJsonObject("textures").getAsJsonObject("SKIN"); break;
                }
            }
            if (skin == null) throw new IOException("This account has no uploaded player skin; load a local PNG instead");
            byte[] png = read(texture(skin.get("url").getAsString()), 512 * 1024, deadline);
            validatePng(png);
            JsonObject result = new JsonObject();
            result.addProperty("username", profile.get("name").getAsString());
            result.addProperty("slim", skin.has("metadata") && skin.getAsJsonObject("metadata").has("model") && "slim".equals(skin.getAsJsonObject("metadata").get("model").getAsString()));
            result.addProperty("image", "data:image/png;base64," + Base64.getEncoder().encodeToString(png));
            synchronized (this) {
                if (closed) throw new IOException("Workshop is stopping");
                cache.put(key, new Cached(result, System.currentTimeMillis() + 600_000));
            }
            while (cache.size() > 16) cache.remove(cache.keySet().iterator().next());
            return result.deepCopy();
        } catch (RuntimeException invalid) { throw new IOException("Invalid response from the Minecraft skin service"); }
        finally { pending.release(); }
    }
    static URI texture(String url) throws IOException {
        URI uri;
        try { uri = URI.create(url); } catch (IllegalArgumentException invalid) { throw new IOException("Invalid skin URL"); }
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || !"textures.minecraft.net".equals(uri.getHost()) || uri.getPort() != -1 || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null || !uri.getRawPath().matches("/texture/[a-fA-F0-9]{32,64}")) throw new IOException("Skin must come from Minecraft's texture service");
        return URI.create("https://textures.minecraft.net" + uri.getRawPath());
    }
    static void validatePng(byte[] bytes) throws IOException {
        if (bytes.length < 24) throw new IOException("Invalid skin PNG");
        ByteBuffer header = ByteBuffer.wrap(bytes);
        int width = header.getInt(16), height = header.getInt(20);
        if (header.getLong(0) != 0x89504e470d0a1a0aL || header.getInt(12) != 0x49484452 || width != 64 || (height != 64 && height != 32)) throw new IOException("Player skin must be a 64×64 or 64×32 PNG");
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("Invalid skin PNG");
            var reader = readers.next();
            try {
                reader.setInput(input, true, true);
                var image = reader.read(0);
                if (image == null || image.getWidth() != width || image.getHeight() != height) throw new IOException("Invalid skin PNG");
            } finally { reader.dispose(); }
        }
    }
    private static JsonObject json(byte[] bytes) throws IOException { return TemplateJson.parse(bytes); }
    private byte[] read(URI uri, int limit, long deadline) throws IOException {
        long remainingMillis = (deadline - System.nanoTime()) / 1_000_000;
        if (remainingMillis <= 0 || Thread.currentThread().isInterrupted()) throw new IOException("Skin lookup timed out or stopped");
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        synchronized (this) {
            if (closed) { connection.disconnect(); throw new IOException("Workshop is stopping"); }
            activeConnection = connection;
        }
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout((int)Math.min(4000, remainingMillis)); connection.setReadTimeout((int)Math.min(4000, remainingMillis));
        try {
            int status = connection.getResponseCode();
            if (status != 200) throw new IOException(status == 404 || status == 204 ? "Minecraft account was not found" : "Minecraft skin service unavailable; retry later");
            if (connection.getContentLengthLong() > limit) throw new IOException("Skin service response exceeds size limit");
            try (InputStream input = connection.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] chunk = new byte[4096];
                while (true) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0 || Thread.currentThread().isInterrupted()) throw new IOException("Skin lookup timed out or stopped");
                    connection.setReadTimeout((int)Math.max(1, Math.min(4000, remaining / 1_000_000)));
                    int count = input.read(chunk, 0, Math.min(chunk.length, limit + 1 - output.size()));
                    if (System.nanoTime() >= deadline) throw new IOException("Skin lookup timed out");
                    if (count < 0) return output.toByteArray();
                    output.write(chunk, 0, count);
                    if (output.size() > limit) throw new IOException("Skin service response exceeds size limit");
                }
            }
        } finally { connection.disconnect(); synchronized (this) { if (activeConnection == connection) activeConnection = null; } }
    }
    void clear() {
        HttpURLConnection connection;
        synchronized (this) { closed = true; cache.clear(); connection = activeConnection; }
        if (connection != null) connection.disconnect();
    }
}
