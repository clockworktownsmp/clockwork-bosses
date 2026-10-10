package clockwork.bosses.client;

import clockwork.bosses.ClockworkBosses;
import clockwork.bosses.CustomWardenSkinPayload;
import clockwork.bosses.ClockworkBossTemplateNames;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.ClientAsset;
import net.minecraft.core.ClientAsset.DownloadedTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import net.minecraft.world.item.component.ResolvableProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLConnection;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Resolves player and server-provided custom skins on the client. Player skins are mapped
 * into a transparent Warden UV atlas; custom atlases are loaded unchanged. The
 * separate emissive masks provide Clockwork Boss glow effects for both skin types.
 */
public final class WardenSkinTextureCache {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClockworkBosses.MOD_ID);
    private static final Identifier WARDEN_TEXTURE =
            Identifier.withDefaultNamespace("textures/entity/warden/warden.png");
    private static final int MAX_SKIN_BYTES = 512 * 1024;
    private static final int MAX_SKIN_CACHE_ENTRIES = 64;
    private static final long MAX_CUSTOM_SKIN_BYTE_CACHE_SIZE = 32L * 1024 * 1024;
    // Skin lookups are client-local, so retry temporary service or connection failures.
    private static final long SKIN_LOAD_RETRY_DELAY_NANOS = 30L * 1_000_000_000L;
    private static final long PLAYER_PROFILE_LOOKUP_TIMEOUT_SECONDS = 15;
    private static final java.util.regex.Pattern PLAYER_NAME =
            java.util.regex.Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final int SKIN_REQUEST_TIMEOUT_MILLIS = 15_000;
    private static final long SKIN_DOWNLOAD_DEADLINE_NANOS = TimeUnit.SECONDS.toNanos(60);
    private static final Map<String, byte[]> CUSTOM_SKIN_BYTES = new LinkedHashMap<>(16, 0.75F, true);
    private static long customSkinByteCacheSize;
    // Reservations include jobs from an old connection until they actually finish.
    // Clearing a cache cannot accidentally grant another unlimited batch of work.
    private static final int MAX_PENDING_SKIN_LOADS = 8;
    private static final AtomicInteger PENDING_SKIN_LOADS = new AtomicInteger();
    private static final AtomicLong TEXTURE_GENERATION = new AtomicLong();
    private static final Map<String, SkinEntry> SKINS = new LinkedHashMap<>(16, 0.75F, true);

    private WardenSkinTextureCache() {}

    /**
     * Resolves a selected skin source. Player skins are mapped from the standard
     * 64x64 player layout; custom images are already complete 128x128 Warden
     * atlases, so they are loaded directly without another UV transformation.
     */
    public static Identifier textureFor(String skinType, String playerName, String customSkinFile, String templateName) {
        if ("player".equalsIgnoreCase(skinType)) {
            return playerTextureFor(playerName);
        }
        if ("custom".equalsIgnoreCase(skinType)) {
            return customTextureFor(templateName, customSkinFile);
        }
        return null;
    }

    /**
     * Validates an account name and shares its lowercase cache key across bosses, while native profile
     * lookup supplies the skin.
     */
    private static Identifier playerTextureFor(String playerName) {
        if (playerName == null || !PLAYER_NAME.matcher(playerName).matches()) {
            return null;
        }

        String cacheKey = "player:" + playerName.toLowerCase(Locale.ROOT);
        SkinEntry entry = getOrStart(cacheKey, value -> lookup(playerName, value));
        return entry.texture;
    }

    /** Uses a server-delivered filename key; a missing packet is not cached as a permanent failure. */
    private static Identifier customTextureFor(String templateName, String fileName) {
        if (!ClockworkBossTemplateNames.isValid(templateName) || !CustomWardenSkinPayload.isValidFileName(fileName)) {
            return null;
        }

        String identity = CustomWardenSkinPayload.skinIdentity(templateName, fileName);
        byte[] pngBytes = customSkinBytes(identity);
        // The client keeps using the vanilla texture until the server sends this
        // referenced atlas; do not cache a miss because the packet may arrive later.
        if (pngBytes == null) return null;

        String cacheKey = "custom:" + identity;
        SkinEntry entry = getOrStart(cacheKey, value -> loadCustomSkin(identity, pngBytes, value));
        return entry.texture;
    }

    /**
     * Stores only server-validated, bounded PNG data. The small LRU avoids
     * unbounded memory growth if a server uses many distinct custom skins.
     */
    public static void acceptCustomSkinPayload(String templateName, String fileName, byte[] pngBytes) {
        if (templateName.startsWith("__gui_") && !ClockworkBossEditorClient.acceptsPreview(templateName)) return;
        if (!ClockworkBossTemplateNames.isValid(templateName) || !CustomWardenSkinPayload.isValidFileName(fileName)
                || pngBytes == null
                || pngBytes.length == 0
                || pngBytes.length > CustomWardenSkinPayload.MAX_PNG_BYTES
                || !CustomWardenSkinPayload.hasWardenAtlasHeader(pngBytes)) {
            LOGGER.warn("Rejected invalid custom Warden skin data received from the server.");
            return;
        }

        String key = CustomWardenSkinPayload.skinIdentity(templateName, fileName);
        byte[] copy = pngBytes.clone();
        synchronized (CUSTOM_SKIN_BYTES) {
            byte[] old = CUSTOM_SKIN_BYTES.get(key);
            if (old != null && Arrays.equals(old, copy)) {
                return;
            }
            if (old != null) customSkinByteCacheSize -= old.length;
            CUSTOM_SKIN_BYTES.put(key, copy);
            customSkinByteCacheSize += copy.length;
            while (CUSTOM_SKIN_BYTES.size() > MAX_SKIN_CACHE_ENTRIES
                    || customSkinByteCacheSize > MAX_CUSTOM_SKIN_BYTE_CACHE_SIZE) {
                Map.Entry<String, byte[]> eldest = CUSTOM_SKIN_BYTES.entrySet().iterator().next();
                customSkinByteCacheSize -= eldest.getValue().length;
                CUSTOM_SKIN_BYTES.remove(eldest.getKey());
            }
        }

        // If the server replaces a file while this client is connected, discard
        // the old GPU texture so the next render rebuilds it from the new packet.
        synchronized (SKINS) {
            SkinEntry old = SKINS.remove("custom:" + key);
            if (old != null) evict(old);
        }
    }

    /** Release editor-owned custom bytes/textures when its screen closes; ordinary boss caches are unaffected. */
    public static void releasePreview(String templateName) {
        synchronized (CUSTOM_SKIN_BYTES) {
            var entries = CUSTOM_SKIN_BYTES.entrySet().iterator();
            while (entries.hasNext()) {
                var entry = entries.next();
                if (entry.getKey().startsWith(templateName + ":")) { customSkinByteCacheSize -= entry.getValue().length; entries.remove(); }
            }
        }
        synchronized (SKINS) {
            var entries = SKINS.entrySet().iterator();
            while (entries.hasNext()) {
                var entry = entries.next();
                if (entry.getKey().startsWith("custom:" + templateName + ":")) { evict(entry.getValue()); entries.remove(); }
            }
        }
    }

    /** Reads the bounded compressed atlas cache under its lock; callers never mutate the owned byte array. */
    private static byte[] customSkinBytes(String fileName) {
        synchronized (CUSTOM_SKIN_BYTES) {
            return CUSTOM_SKIN_BYTES.get(fileName);
        }
    }

    /** Releases all generated GPU atlases on disconnect and forgets server-owned bytes. */
    public static void clear() {
        synchronized (CUSTOM_SKIN_BYTES) {
            CUSTOM_SKIN_BYTES.clear();
            customSkinByteCacheSize = 0;
        }
        clearGeneratedTextures();
    }

    /** Resource reloads rebuild mapped ribs/masks while retaining connected-server PNG data. */
    public static void clearGeneratedTextures() {
        synchronized (SKINS) {
            SKINS.values().forEach(WardenSkinTextureCache::evict);
            SKINS.clear();
        }
    }

    /** Shares one bounded LRU for downloaded profiles and local custom atlases. */
    private static SkinEntry getOrStart(String key, Consumer<SkinEntry> startLoading) {
        SkinEntry entry;
        boolean startLookup = false;
        synchronized (SKINS) {
            entry = SKINS.get(key);
            if (entry == null) {
                entry = new SkinEntry();
                SKINS.put(key, entry);

                if (SKINS.size() > MAX_SKIN_CACHE_ENTRIES) {
                    Iterator<Map.Entry<String, SkinEntry>> iterator = SKINS.entrySet().iterator();
                    Map.Entry<String, SkinEntry> eldest = iterator.next();
                    iterator.remove();
                    evict(eldest.getValue());
                }
            }

            // A failed asynchronous lookup leaves no texture; let later renders
            // retry only after the backoff, with this lock preventing duplicate loads.
            long now = System.nanoTime();
            if (!entry.discarded
                    && entry.texture == null
                    && !entry.loading
                    && (entry.retryAfterNanos == Long.MIN_VALUE || now - entry.retryAfterNanos >= 0)
                    && PENDING_SKIN_LOADS.get() < MAX_PENDING_SKIN_LOADS) {
                PENDING_SKIN_LOADS.incrementAndGet();
                entry.loadReserved.set(true);
                entry.loading = true;
                startLookup = true;
            }
        }
        if (startLookup) {
            try {
                startLoading.accept(entry);
            } catch (RuntimeException failure) {
                markLoadFailed(entry);
                LOGGER.warn("Could not start Clockwork Boss skin load for '{}'.", key, failure);
            }
        }
        return entry;
    }

    /** Schedules a bounded retry after a failed player-profile or texture load. */
    private static void markLoadFailed(SkinEntry entry) {
        finishLoad(entry);
        synchronized (SKINS) {
            if (!entry.discarded) {
                entry.loading = false;
                entry.retryAfterNanos = System.nanoTime() + SKIN_LOAD_RETRY_DELAY_NANOS;
            }
        }
    }

    /** Releases each job's capacity once, even after eviction or a connection change. */
    private static void finishLoad(SkinEntry entry) {
        if (entry.loadReserved.compareAndSet(true, false)) PENDING_SKIN_LOADS.decrementAndGet();
    }

    /** Evicted futures may finish, but never register another texture for this entry. */
    private static void evict(SkinEntry entry) {
        entry.discarded = true;
        Identifier texture = entry.texture;
        if (texture != null) {
            // Identifiers include an entry generation, so delayed removal cannot
            // release a replacement texture or another account using the same skin.
            Minecraft.getInstance().execute(
                    () -> Minecraft.getInstance().getTextureManager().release(texture));
        }
    }

    /**
     * Uses Minecraft's profile/skin cache, times out only the dependent stage, then loads CPU pixels for
     * UV mapping.
     */
    private static void lookup(String playerName, SkinEntry entry) {
        if (entry.discarded) { finishLoad(entry); return; }
        Minecraft minecraft = Minecraft.getInstance();
        ResolvableProfile profile = ResolvableProfile.createUnresolved(playerName);
        LOGGER.info("Resolving player skin for Clockwork Boss player '{}'.", playerName);

        // Time out this dependent stage, not Minecraft's shared profile-cache future.
        minecraft.playerSkinRenderCache().lookup(profile)
                .thenApply(result -> result)
                .orTimeout(PLAYER_PROFILE_LOOKUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .whenComplete((result, failure) -> {
                    if (entry.discarded) { finishLoad(entry); return; }
                    if (failure != null) {
                        LOGGER.warn("Could not resolve skin for Clockwork Boss player '{}'.", playerName, failure);
                        markLoadFailed(entry);
                        return;
                    }
                    if (result == null || result.isEmpty()) {
                        LOGGER.warn("No player skin was found for Clockwork Boss player '{}'.", playerName);
                        markLoadFailed(entry);
                        return;
                    }

                    PlayerSkin skin = result.get().playerSkin();
                    ClientAsset.Texture body = skin.body();
                    if (body instanceof DownloadedTexture downloaded) {
                        loadDownloadedSkin(minecraft, playerName, downloaded, skin.model(), entry);
                    } else {
                        minecraft.execute(() -> loadResourceSkin(
                                minecraft, playerName, body, skin.model(), entry));
                    }
                });
    }

    /** Reads custom Warden atlases off-thread and registers their GPU texture on the client thread. */
    private static void loadCustomSkin(String fileName, byte[] pngBytes, SkinEntry entry) {
        Minecraft minecraft = Minecraft.getInstance();
        CompletableFuture.supplyAsync(() -> {
            try {
                return readCustomSkin(pngBytes);
            } catch (IOException e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        }, Util.ioPool()).whenComplete((image, failure) -> {
            if (entry.discarded) {
                if (image != null) image.flush();
                finishLoad(entry);
                return;
            }
            if (failure != null) {
                LOGGER.warn("Could not load custom Clockwork Boss skin '{}'.", fileName, failure);
                markLoadFailed(entry);
                return;
            }
            minecraft.execute(() -> registerCustomSkin(minecraft, fileName, image, entry));
        });
    }

    /** Rechecks the size and atlas dimensions on the client before ImageIO allocates pixels. */
    private static BufferedImage readCustomSkin(byte[] pngBytes) throws IOException {
        if (pngBytes.length > MAX_SKIN_BYTES
                || !CustomWardenSkinPayload.hasWardenAtlasHeader(pngBytes)) {
            throw new IOException("Custom Warden skin is not a bounded 128x128 PNG atlas");
        }
        try (InputStream stream = new ByteArrayInputStream(pngBytes)) {
            return readExpectedImage(stream, 128, 128, 128);
        }
    }

    /** Registers the custom atlas unchanged, retaining its authored UV layout and colors. */
    private static void registerCustomSkin(
            Minecraft minecraft,
            String fileName,
            BufferedImage atlas,
            SkinEntry entry) {
        if (entry.discarded) {
            atlas.flush();
            finishLoad(entry);
            return;
        }
        try {
            Identifier textureId = Identifier.fromNamespaceAndPath(
                    ClockworkBosses.MOD_ID,
                    "warden_custom_skins/" + textureHash("custom:" + fileName) + "_" + entry.generation + ".png");
            registerAtlasTexture(minecraft, textureId, "Custom Clockwork Boss skin " + fileName, atlas, entry);
            if (!entry.discarded && entry.texture != null) {
                LOGGER.info("Loaded custom Warden atlas '{}' as {}.", fileName, textureId);
            }
        } catch (Exception e) {
            LOGGER.warn("Could not register custom Clockwork Boss skin '{}'.", fileName, e);
            markLoadFailed(entry);
        } finally {
            atlas.flush();
        }
    }

    /** Fetches bounded image bytes on Minecraft's I/O pool and returns GPU registration to the client thread. */
    private static void loadDownloadedSkin(
            Minecraft minecraft,
            String playerName,
            DownloadedTexture skin,
            PlayerModelType model,
            SkinEntry entry) {
        CompletableFuture.supplyAsync(() -> {
            try {
                return downloadImage(skin.url());
            } catch (IOException e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        }, Util.ioPool()).whenComplete((image, failure) -> {
            if (entry.discarded) {
                if (image != null) image.flush();
                finishLoad(entry);
                return;
            }
            if (failure != null) {
                LOGGER.warn("Could not download skin texture for Clockwork Boss player '{}'.", playerName, failure);
                markLoadFailed(entry);
                return;
            }
            minecraft.execute(() -> registerSkinTexture(
                    minecraft, playerName, skin.id(), image, model, entry));
        });
    }

    /**
     * Downloads only a Mojang texture URL over HTTPS without redirects; byte/time limits and finally-
     * disconnect bound the request.
     */
    private static BufferedImage downloadImage(String url) throws IOException {
        URI uri = URI.create(url);
        String scheme = uri.getScheme();
        if (!("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))
                || uri.getHost() == null
                || !uri.getHost().equalsIgnoreCase("textures.minecraft.net")
                || uri.getPort() != -1
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null
                || uri.getRawPath() == null
                || !uri.getRawPath().startsWith("/texture/")) {
            throw new IOException("Skin URL is not a Minecraft texture URL");
        }

        // Mojang profile properties commonly publish this URL with an http
        // scheme. Fetch the same trusted texture over HTTPS instead.
        URI secureUri = URI.create("https://textures.minecraft.net" + uri.getRawPath());
        URLConnection connection = secureUri.toURL().openConnection();
        // Allow ordinary slow connections while bounding the entire transfer;
        // readTimeout alone can be extended indefinitely by trickled bytes.
        long deadline = System.nanoTime() + SKIN_DOWNLOAD_DEADLINE_NANOS;
        connection.setConnectTimeout(SKIN_REQUEST_TIMEOUT_MILLIS);
        connection.setReadTimeout(SKIN_REQUEST_TIMEOUT_MILLIS);
        if (connection instanceof HttpURLConnection http) {
            http.setInstanceFollowRedirects(false);
        }
        try {
            long contentLength = connection.getContentLengthLong();
            if (contentLength > MAX_SKIN_BYTES) {
                throw new IOException("Skin image exceeds the size limit");
            }

            byte[] bytes;
            try (InputStream stream = connection.getInputStream()) {
                ByteArrayOutputStream received = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                while (true) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) throw new IOException("Skin download exceeded 60 seconds");
                    connection.setReadTimeout((int) Math.max(1,
                            Math.min(SKIN_REQUEST_TIMEOUT_MILLIS, TimeUnit.NANOSECONDS.toMillis(remaining))));
                    int count = stream.read(chunk, 0, Math.min(chunk.length, MAX_SKIN_BYTES + 1 - received.size()));
                    if (System.nanoTime() - deadline >= 0) throw new IOException("Skin download exceeded 60 seconds");
                    if (count < 0) break;
                    received.write(chunk, 0, count);
                    if (received.size() > MAX_SKIN_BYTES) throw new IOException("Skin image exceeds the size limit");
                }
                bytes = received.toByteArray();
            }
            if (bytes.length > MAX_SKIN_BYTES) {
                throw new IOException("Skin image exceeds the size limit");
            }

            try (ByteArrayInputStream input = new ByteArrayInputStream(bytes)) {
                return readExpectedImage(input, 64, 32, 64);
            }
        } finally {
            if (connection instanceof HttpURLConnection http) http.disconnect();
        }
    }

    /** Reads a native bundled/default player skin and maps it using the same pipeline as downloaded skins. */
    private static void loadResourceSkin(
            Minecraft minecraft,
            String playerName,
            ClientAsset.Texture skin,
            PlayerModelType model,
            SkinEntry entry) {
        if (entry.discarded) { finishLoad(entry); return; }
        try {
            Optional<Resource> resource = minecraft.getResourceManager().getResource(skin.texturePath());
            if (resource.isEmpty()) {
                LOGGER.warn("Skin texture resource '{}' was not found for '{}'.", skin.texturePath(), playerName);
                markLoadFailed(entry);
                return;
            }
            BufferedImage image;
            try (InputStream stream = resource.get().open()) {
                image = readExpectedImage(stream, 64, 32, 64);
            }
            registerSkinTexture(minecraft, playerName, skin.id(), image, model, entry);
        } catch (Exception e) {
            LOGGER.warn("Could not read skin resource for Clockwork Boss player '{}'.", playerName, e);
            markLoadFailed(entry);
        }
    }

    /**
     * Maps player faces into a transparent Warden atlas with nearest-neighbor sampling and the requested
     * original moving rib plates.
     */
    private static void registerSkinTexture(
            Minecraft minecraft,
            String playerName,
            Identifier sourceId,
            BufferedImage rawSkin,
            PlayerModelType model,
            SkinEntry entry) {
        if (entry.discarded) {
            rawSkin.flush();
            finishLoad(entry);
            return;
        }
        BufferedImage source = null;
        BufferedImage vanillaAtlas = null;
        BufferedImage atlas = null;
        try {
            source = normalizeSkin(rawSkin);
            mergeOuterLayers(source, model);

            Optional<Resource> wardenResource = minecraft.getResourceManager().getResource(WARDEN_TEXTURE);
            if (wardenResource.isEmpty()) {
                throw new IOException("Vanilla Warden texture was not found");
            }

            try (InputStream stream = wardenResource.get().open()) {
                vanillaAtlas = readExpectedImage(stream, 128, 128, 128);
            }
            if (vanillaAtlas == null
                    || vanillaAtlas.getWidth() != 128
                    || vanillaAtlas.getHeight() != 128) {
                throw new IOException("Warden texture must be 128x128 pixels");
            }

            // Start transparent instead of cloning the vanilla atlas. Player skin
            // pixels fill the head, body, arms, and legs; unmapped UVs stay
            // transparent so unrelated Warden markings cannot leak through. The
            // original atlas is retained only for the animated rib plates.
            atlas = new BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB);

            Graphics2D graphics = atlas.createGraphics();
            try {
                graphics.setComposite(AlphaComposite.SrcOver);
                useNearestNeighborInterpolation(graphics);

                int armWidth = model == PlayerModelType.SLIM ? 3 : 4;
                mapBox(graphics, source, new Box(0, 0, 8, 8, 8), new Box(0, 32, 16, 16, 10));
                mapBox(graphics, source,
                        new Box(16, 16, 8, 12, 4), new Box(0, 0, 18, 21, 11));
                // The Warden's ribcage plates are separate, animated zero-depth
                // cubes rendered over the player-textured torso. Keep their
                // original Warden UV pixels so the ribs remain distinct and read
                // correctly when the chest opens for a sonic boom.
                Box ribPlates = new Box(90, 11, 9, 21, 0);
                mapBox(graphics, vanillaAtlas, ribPlates, ribPlates);
                mapBox(graphics, source,
                        new Box(40, 16, armWidth, 12, 4), new Box(44, 50, 8, 28, 8));
                mapBox(graphics, source,
                        new Box(32, 48, armWidth, 12, 4), new Box(0, 58, 8, 28, 8));
                mapBox(graphics, source,
                        new Box(0, 16, 4, 12, 4), new Box(76, 48, 6, 13, 6));
                mapBox(graphics, source,
                        new Box(16, 48, 4, 12, 4), new Box(76, 76, 6, 13, 6));
            } finally {
                graphics.dispose();
            }

            Identifier textureId = Identifier.fromNamespaceAndPath(
                    ClockworkBosses.MOD_ID,
                    "warden_skins/" + textureHash(sourceId, model) + "_" + entry.generation + ".png");
            registerAtlasTexture(minecraft, textureId, "Clockwork Bosses skin for " + playerName, atlas, entry);
            if (entry.discarded || entry.texture == null) return;
            LOGGER.info(
                    "Loaded {} skin for Clockwork Boss player '{}' as {}.",
                    model == PlayerModelType.SLIM ? "slim" : "classic",
                    playerName,
                    textureId);
        } catch (Exception e) {
            LOGGER.warn("Could not build Warden texture for player '{}'.", playerName, e);
            markLoadFailed(entry);
        } finally {
            rawSkin.flush();
            if (source != null && source != rawSkin) source.flush();
            if (vanillaAtlas != null) vanillaAtlas.flush();
            if (atlas != null) atlas.flush();
        }
    }

    /** Converts an already Warden-mapped atlas to the GPU texture without changing any pixels. */
    private static void registerAtlasTexture(
            Minecraft minecraft,
            Identifier textureId,
            String textureName,
            BufferedImage atlas,
            SkinEntry entry) throws IOException {
        NativeImage pixels = new NativeImage(atlas.getWidth(), atlas.getHeight(), false);
        DynamicTexture texture = null;
        boolean registered = false;
        try {
            // 26.3 NativeImage.setPixel uses ARGB, matching BufferedImage.getRGB.
            // Copy pixels directly: no PNG encode/decode cycle or second image allocation.
            for (int y = 0; y < atlas.getHeight(); y++) {
                for (int x = 0; x < atlas.getWidth(); x++) pixels.setPixel(x, y, atlas.getRGB(x, y));
            }
            texture = new DynamicTexture(() -> textureName, pixels);
            if (entry.discarded) return;
            minecraft.getTextureManager().register(textureId, texture);
            registered = true;
            entry.texture = textureId;
            if (entry.discarded) minecraft.getTextureManager().release(textureId);
        } finally {
            // DynamicTexture takes NativeImage ownership. A constructor or registration
            // failure must still close the correct owner of that native allocation.
            if (!registered) {
                if (texture != null) texture.close();
                else pixels.close();
            }
            finishLoad(entry);
        }
    }

    /** Keeps modern skins unchanged; mirrors legacy right limbs into a 64x64 atlas before normal mapping. */
    private static BufferedImage normalizeSkin(BufferedImage rawSkin) throws IOException {
        if (rawSkin.getWidth() != 64 || (rawSkin.getHeight() != 32 && rawSkin.getHeight() != 64)) {
            throw new IOException("Player skin must be 64x64 or legacy 64x32 pixels");
        }
        if (rawSkin.getHeight() == 64) {
            return rawSkin;
        }

        BufferedImage modern = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = modern.createGraphics();
        try {
            graphics.setComposite(AlphaComposite.Src);
            graphics.drawImage(rawSkin, 0, 0, null);
            graphics.setComposite(AlphaComposite.SrcOver);
            useNearestNeighborInterpolation(graphics);

            // Legacy skins only include the right limbs. Mirror them into the
            // modern left-limb UV regions, matching Minecraft's normalization.
            mapBox(graphics, rawSkin, new Box(40, 16, 4, 12, 4), new Box(32, 48, 4, 12, 4), true);
            mapBox(graphics, rawSkin, new Box(0, 16, 4, 12, 4), new Box(16, 48, 4, 12, 4), true);
        } finally {
            graphics.dispose();
        }
        return modern;
    }

    /** Checks image dimensions before decoding pixels to avoid oversized image allocations. */
    private static BufferedImage readExpectedImage(
            InputStream input,
            int expectedWidth,
            int firstAllowedHeight,
            int secondAllowedHeight) throws IOException {
        // Explicit memory caching avoids ImageIO's global disk-cache preference
        // and never changes that preference for other mods. All sources are bounded.
        byte[] bytes = input.readNBytes(MAX_SKIN_BYTES + 1);
        if (bytes.length > MAX_SKIN_BYTES) throw new IOException("Skin image exceeds 512 KiB");
        try (ImageInputStream imageInput = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInput);
            if (!readers.hasNext()) {
                throw new IOException("Image data has no supported reader");
            }

            ImageReader reader = readers.next();
            try {
                reader.setInput(imageInput, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width != expectedWidth
                        || (height != firstAllowedHeight && height != secondAllowedHeight)) {
                    throw new IOException("Image dimensions are not supported");
                }
                BufferedImage image = reader.read(0);
                if (image == null) {
                    throw new IOException("Image reader returned no pixels");
                }
                return image;
            } finally {
                reader.dispose();
            }
        }
    }

    /**
     * Composites hat/jacket/sleeve/leg overlays into corresponding player base faces before Warden UV
     * conversion.
     */
    private static void mergeOuterLayers(BufferedImage skin, PlayerModelType model) {
        int armWidth = model == PlayerModelType.SLIM ? 3 : 4;
        Graphics2D graphics = skin.createGraphics();
        try {
            graphics.setComposite(AlphaComposite.SrcOver);
            useNearestNeighborInterpolation(graphics);
            mergeBox(graphics, skin, new Box(32, 0, 8, 8, 8), new Box(0, 0, 8, 8, 8));
            mergeBox(graphics, skin, new Box(16, 32, 8, 12, 4), new Box(16, 16, 8, 12, 4));
            mergeBox(graphics, skin, new Box(40, 32, armWidth, 12, 4), new Box(40, 16, armWidth, 12, 4));
            mergeBox(graphics, skin, new Box(48, 48, armWidth, 12, 4), new Box(32, 48, armWidth, 12, 4));
            mergeBox(graphics, skin, new Box(0, 32, 4, 12, 4), new Box(0, 16, 4, 12, 4));
            mergeBox(graphics, skin, new Box(0, 48, 4, 12, 4), new Box(16, 48, 4, 12, 4));
        } finally {
            graphics.dispose();
        }
    }

    /**
     * Selects exact source pixels during stretching/mirroring, preserving skin colors and Minecraft pixel-
     * art edges.
     */
    private static void useNearestNeighborInterpolation(Graphics2D graphics) {
        graphics.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
    }

    /** Composites each outer cube face onto its matching base face using the shared vanilla cube-face order. */
    private static void mergeBox(Graphics2D graphics, BufferedImage image, Box outer, Box base) {
        Face[] outerFaces = faces(outer);
        Face[] baseFaces = faces(base);
        for (Face face : outerFaces) {
            Face target = baseFaces[face.index()];
            drawFace(graphics, image,
                    face.u(), face.v(), face.width(), face.height(),
                    target.u(), target.v(), target.width(), target.height(), false);
        }
    }

    /**
     * Maps matching cube faces between atlas rectangles; optional horizontal mirroring supports legacy
     * left-limb reconstruction.
     */
    private static void mapBox(Graphics2D graphics, BufferedImage source, Box sourceBox, Box targetBox) {
        mapBox(graphics, source, sourceBox, targetBox, false);
    }

    /**
     * Maps matching cube faces between atlas rectangles; optional horizontal mirroring supports legacy
     * left-limb reconstruction.
     */
    private static void mapBox(
            Graphics2D graphics,
            BufferedImage source,
            Box sourceBox,
            Box targetBox,
            boolean mirror) {
        Face[] sourceFaces = faces(sourceBox);
        Face[] targetFaces = faces(targetBox);
        for (int i = 0; i < sourceFaces.length; i++) {
            Face from = sourceFaces[i];
            Face to = targetFaces[i];
            drawFace(graphics, source,
                    from.u(), from.v(), from.width(), from.height(),
                    to.u(), to.v(), to.width(), to.height(), mirror);
        }
    }

    /**
     * Expands one CubeListBuilder-style UV box into its six face rectangles, including zero-depth animated
     * ribs.
     */
    private static Face[] faces(Box box) {
        int u = box.u();
        int v = box.v();
        int w = box.width();
        int h = box.height();
        int d = box.depth();
        // Face order follows the standard CubeListBuilder atlas layout:
        // west, north/front, east, south/back, top, bottom.
        return new Face[] {
                new Face(0, u, v + d, d, h),
                new Face(1, u + d, v + d, w, h),
                new Face(2, u + d + w, v + d, d, h),
                new Face(3, u + d + w + d, v + d, w, h),
                new Face(4, u + d, v, w, d),
                new Face(5, u + d + w, v, w, d)
        };
    }

    /**
     * Draws one UV rectangle with optional mirroring; Graphics2D nearest-neighbor sampling keeps original
     * source colors.
     */
    private static void drawFace(
            Graphics2D graphics,
            BufferedImage source,
            int sourceX,
            int sourceY,
            int sourceWidth,
            int sourceHeight,
            int targetX,
            int targetY,
            int targetWidth,
            int targetHeight,
            boolean mirror) {
        int targetStartX = mirror ? targetX + targetWidth : targetX;
        int targetEndX = mirror ? targetX : targetX + targetWidth;
        graphics.drawImage(
                source,
                targetStartX,
                targetY,
                targetEndX,
                targetY + targetHeight,
                sourceX,
                sourceY,
                sourceX + sourceWidth,
                sourceY + sourceHeight,
                null);
    }

    /**
     * Creates a stable short SHA-256 key for a source/model; the entry generation separately prevents
     * ownership collisions.
     */
    private static String textureHash(Identifier source, PlayerModelType model) {
        return textureHash(source.toString() + ":" + model.getSerializedName());
    }

    /**
     * Creates a stable short SHA-256 key for a source/model; the entry generation separately prevents
     * ownership collisions.
     */
    private static String textureHash(String cacheKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(cacheKey
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private record Box(int u, int v, int width, int height, int depth) {}
    private record Face(int index, int u, int v, int width, int height) {}

    /** One asynchronous load and one uniquely owned texture, never a server entity reference. */
    private static final class SkinEntry {
        private final long generation = TEXTURE_GENERATION.incrementAndGet();
        private final AtomicBoolean loadReserved = new AtomicBoolean();
        private volatile Identifier texture;
        private volatile boolean discarded;
        // The state below is read and written only while holding the SKINS lock.
        private boolean loading;
        private long retryAfterNanos = Long.MIN_VALUE;
    }
}
