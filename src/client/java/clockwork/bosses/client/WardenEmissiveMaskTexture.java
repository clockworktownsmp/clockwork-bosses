package clockwork.bosses.client;

import clockwork.bosses.ClockworkBosses;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Builds per-texture white alpha masks so Clockwork Boss emissive layers keep their shape and pulse. */
public final class WardenEmissiveMaskTexture {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClockworkBosses.MOD_ID);
    private static final String WARDEN_TEXTURE_PREFIX = "textures/entity/warden/";
    private static final String WARDEN_BIOLUMINESCENT_LAYER =
            WARDEN_TEXTURE_PREFIX + "warden_bioluminescent_layer.png";
    private static final String WARDEN_TENDRIL_SOURCE = WARDEN_TEXTURE_PREFIX + "warden.png";
    private static final String MASK_TEXTURE_PREFIX = "warden_emissive_tint_mask/";
    private static final int WARDEN_HEAD_FRONT_X = 10;
    private static final int WARDEN_HEAD_FRONT_Y = 42;
    private static final int WARDEN_HEAD_FRONT_SIZE = 16;
    private static final int MAX_MASK_TEXTURES = 32;
    // The five native 26.3 sources include warden.png for the tendrils.
    // Other mods' emissive sources keep their own texture and tint behavior.
    private static final Set<String> SUPPORTED_TEXTURES = Set.of(
            WARDEN_BIOLUMINESCENT_LAYER, WARDEN_TENDRIL_SOURCE,
            WARDEN_TEXTURE_PREFIX + "warden_heart.png",
            WARDEN_TEXTURE_PREFIX + "warden_pulsating_spots_1.png",
            WARDEN_TEXTURE_PREFIX + "warden_pulsating_spots_2.png");
    // Bound our extra resource-pack allocation before NativeImage decodes pixels.
    private static final int MAX_MASK_IMAGE_BYTES = 4 * 1024 * 1024;
    private static final int MAX_MASK_DIMENSION = 1024;
    private record MaskKey(Identifier source, int eyes, int brows, int pupils, boolean preview) {}
    private record MaskTexture(Identifier id, DynamicTexture texture) {}
    // Non-face keys never depend on alignment and can be reused by every render.
    // Face lookups allocate only a small key; identifiers/paths and normalized
    // face records are constructed on cache misses, rather than for every layer.
    private static final Map<Identifier, MaskKey> SHARED_KEYS = SUPPORTED_TEXTURES.stream()
            .filter(path -> !path.equals(WARDEN_BIOLUMINESCENT_LAYER))
            .map(Identifier::withDefaultNamespace)
            .collect(java.util.stream.Collectors.toUnmodifiableMap(id -> id, id -> new MaskKey(id, 0, 0, 0, false)));
    private static final Map<MaskKey, MaskTexture> TEXTURES =
            new LinkedHashMap<>(16, 0.75F, true);
    // A broken resource pack must not retry image decoding and log every frame.
    // Resource reload clears these failures so repaired pack files can be retried.
    private static final Map<Identifier, Boolean> FAILED_TEXTURES = new LinkedHashMap<>();

    private WardenEmissiveMaskTexture() {}

    /** Returns a tintable mask for a vanilla Warden emissive texture, or null when unsupported. */
    public static Identifier getTexture(Identifier sourceTexture) {
        return getTexture(sourceTexture, clockwork.bosses.ClockworkBossEyes.DEFAULT.packed());
    }

    /** Only the face layer varies by coordinates; native tendril/heart masks remain shared. */
    public static Identifier getTexture(Identifier sourceTexture, int coordinates) {
        return getTexture(sourceTexture, coordinates, clockwork.bosses.ClockworkBossBrows.DEFAULT.packed(), false);
    }

    /** Preview brightness is a separate bounded face-mask variant; live mask opacity is unchanged. */
    public static Identifier getTexture(Identifier sourceTexture, int coordinates, int browCoordinates, boolean preview) {
        return getTexture(sourceTexture, coordinates, browCoordinates, clockwork.bosses.ClockworkBossPupils.DEFAULT.packed(), preview);
    }

    /** Pupil edits participate in the existing bounded face cache; shared non-face masks do not vary. */
    public static Identifier getTexture(Identifier sourceTexture, int coordinates, int browCoordinates, int pupilSettings, boolean preview) {
        if (!sourceTexture.getNamespace().equals("minecraft")
                || !SUPPORTED_TEXTURES.contains(sourceTexture.getPath())) {
            return null;
        }
        if (FAILED_TEXTURES.containsKey(sourceTexture)) return null;

        boolean face = sourceTexture.getPath().equals(WARDEN_BIOLUMINESCENT_LAYER);
        MaskKey key = face ? new MaskKey(sourceTexture, coordinates, browCoordinates, pupilSettings, preview) : SHARED_KEYS.get(sourceTexture);
        TextureManager textureManager = Minecraft.getInstance().getTextureManager();
        MaskTexture cached = TEXTURES.get(key);
        if (cached != null && textureManager.getTexture(cached.id()) == cached.texture()) return cached.id();

        String maskPath = MASK_TEXTURE_PREFIX
                + sourceTexture.getPath().substring(WARDEN_TEXTURE_PREFIX.length());
        if (maskPath.endsWith(".png")) {
            maskPath = maskPath.substring(0, maskPath.length() - 4);
        }
        var eyes = clockwork.bosses.ClockworkBossEyes.unpack(coordinates);
        var brows = clockwork.bosses.ClockworkBossBrows.unpack(browCoordinates);
        if (face)
            // Match the raw lookup key's identity; drawing still normalizes its
            // coordinates. Distinct malformed aliases cannot replace/close an
            // atlas still referenced by another cached entry with the same ID.
            maskPath += "/eyes_" + coordinates + "_brows_" + browCoordinates + "_pupils_" + pupilSettings + (preview ? "_preview" : "");
        Identifier maskTexture = Identifier.fromNamespaceAndPath(ClockworkBosses.MOD_ID, maskPath);
        DynamicTexture texture = null;

        NativeImage mask = createWhiteTintMask(sourceTexture, eyes.packed(), brows.packed(), pupilSettings, preview);
        if (mask == null) {
            rememberFailure(sourceTexture);
            return null;
        }

        try {
            texture = new DynamicTexture(
                    () -> "Clockwork Bosses Warden emissive tint mask: " + sourceTexture, mask);
            textureManager.register(maskTexture, texture);
        } catch (RuntimeException failure) {
            if (texture != null) texture.close();
            else mask.close();
            LOGGER.warn("Could not register Clockwork Boss emissive mask {}", sourceTexture, failure);
            rememberFailure(sourceTexture);
            return null;
        }
        TEXTURES.put(key, new MaskTexture(maskTexture, texture));
        if (TEXTURES.size() > MAX_MASK_TEXTURES) {
            Iterator<MaskTexture> iterator = TEXTURES.values().iterator();
            MaskTexture eldest = iterator.next();
            iterator.remove();
            if (textureManager.getTexture(eldest.id()) == eldest.texture()) {
                textureManager.release(eldest.id());
            }
        }
        return maskTexture;
    }

    /** Releases only masks owned by this cache; rebuild after a pack change or reconnect. */
    public static void clear() {
        TextureManager manager = Minecraft.getInstance().getTextureManager();
        TEXTURES.values().forEach(entry -> {
            if (manager.getTexture(entry.id()) == entry.texture()) manager.release(entry.id());
        });
        TEXTURES.clear();
        FAILED_TEXTURES.clear();
    }

    /** Bounds resource-pack failure bookkeeping to the same size as the owned mask cache. */
    private static void rememberFailure(Identifier source) {
        FAILED_TEXTURES.put(source, Boolean.TRUE);
        if (FAILED_TEXTURES.size() > MAX_MASK_TEXTURES) {
            FAILED_TEXTURES.remove(FAILED_TEXTURES.keySet().iterator().next());
        }
    }

    /** Preserve native glow coverage while removing baked cyan. The tendril
     * source is a complete base atlas, so its mask must also exclude body UVs. */
    private static NativeImage createWhiteTintMask(Identifier sourceTexture, int coordinates, int browCoordinates, int pupilSettings, boolean preview) {
        NativeImage mask = null;
        try (InputStream input = Minecraft.getInstance().getResourceManager().open(sourceTexture);
             NativeImage source = readBoundedMaskSource(input)) {
            mask = new NativeImage(source.getWidth(), source.getHeight(), false);
            boolean tendrils = sourceTexture.getPath().equals(WARDEN_TENDRIL_SOURCE);
            for (int y = 0; y < source.getHeight(); y++) {
                for (int x = 0; x < source.getWidth(); x++) {
                    int pixel = source.getPixel(x, y);
                    int alpha = (pixel >>> 24) & 0xFF;
                    // Native 26.3 tendril planes use these front/back regions
                    // of the 128-unit atlas. Normalize pack resolution only;
                    // skin pixels and other emissive sources are untouched.
                    // Relying on the native tendril-only model alone lets a
                    // replacement/full model tint the entire opaque base atlas.
                    if (tendrils) {
                        int u = x * 128 / source.getWidth(), v = y * 128 / source.getHeight();
                        if (!(u >= 58 && u < 90 && v < 16 || u >= 52 && u < 84 && v >= 32 && v < 48)) alpha = 0;
                    }
                    // Preserve alpha within the glow regions. A white RGB mask
                    // avoids the baked cyan texture suppressing red/orange tint
                    // channels during the emissive shader's color multiplication.
                    mask.setPixel(x, y, (alpha << 24) | 0x00FFFFFF);
                }
            }

            // Only replace the vanilla bioluminescent face when it uses the
            // expected 128x128 Warden atlas. Other emissive textures, resource
            // pack dimensions, and all non-face glow details keep their source mask.
            if (sourceTexture.getPath().equals(WARDEN_BIOLUMINESCENT_LAYER)
                    && mask.getWidth() == 128
                    && mask.getHeight() == 128) {
                applyAngryEyeMask(mask, coordinates, browCoordinates, pupilSettings, preview);
            }
            return mask;
        } catch (IOException | RuntimeException exception) {
            if (mask != null) {
                mask.close();
            }
            LOGGER.warn("Could not create the Warden emissive tint mask for {}; using the vanilla texture.",
                    sourceTexture, exception);
            return null;
        }
    }

    /** Checks PNG dimensions and compressed size before allocating native source and mask images. */
    private static NativeImage readBoundedMaskSource(InputStream input) throws IOException {
        byte[] bytes = input.readNBytes(MAX_MASK_IMAGE_BYTES + 1);
        if (bytes.length > MAX_MASK_IMAGE_BYTES || bytes.length < 33) {
            throw new IOException("emissive PNG exceeds the 4 MiB limit or is truncated");
        }
        var header = java.nio.ByteBuffer.wrap(bytes);
        if (header.getLong() != 0x89504E470D0A1A0AL
                || header.getInt() != 13 || header.getInt() != 0x49484452) {
            throw new IOException("emissive resource must be a PNG");
        }
        int width = header.getInt();
        int height = header.getInt();
        if (width <= 0 || height <= 0 || width > MAX_MASK_DIMENSION || height > MAX_MASK_DIMENSION) {
            throw new IOException("emissive PNG dimensions must be at most 1024x1024");
        }
        return NativeImage.read(bytes);
    }

    /**
     * Face-only mask: independently positioned slanted brows and crisp rectangular eyes.
     * Coordinates and dimensions are normalized before drawing, keeping every pixel on
     * the 16x16 face. The preview uses opaque brows; live brows retain their original alpha.
     * Only marked bosses use these masks; base skins and ordinary Wardens are untouched.
     */
    static void applyAngryEyeMask(NativeImage mask) {
        applyAngryEyeMask(mask, clockwork.bosses.ClockworkBossEyes.DEFAULT.packed());
    }

    static void applyAngryEyeMask(NativeImage mask, int coordinates) {
        applyAngryEyeMask(mask, coordinates, clockwork.bosses.ClockworkBossBrows.DEFAULT.packed(), false);
    }

    static void applyAngryEyeMask(NativeImage mask, int coordinates, int browCoordinates, boolean preview) {
        applyAngryEyeMask(mask, coordinates, browCoordinates, clockwork.bosses.ClockworkBossPupils.DEFAULT.packed(), preview);
    }

    /** Draw only clipped eye-local rectangles; pupil pixels retain the same darker phase hue. */
    static void applyAngryEyeMask(NativeImage mask, int coordinates, int browCoordinates, int pupilSettings, boolean preview) {
        if (mask.getWidth() != 128 || mask.getHeight() != 128) {
            return;
        }

        for (int y = WARDEN_HEAD_FRONT_Y;
             y < WARDEN_HEAD_FRONT_Y + WARDEN_HEAD_FRONT_SIZE;
             y++) {
            for (int x = WARDEN_HEAD_FRONT_X;
                 x < WARDEN_HEAD_FRONT_X + WARDEN_HEAD_FRONT_SIZE;
                 x++) {
                // Transparent white keeps the tintable-mask format consistent.
                mask.setPixel(x, y, 0x00FFFFFF);
            }
        }

        var brows = clockwork.bosses.ClockworkBossBrows.unpack(browCoordinates);
        // Each brow is two 2x2 blocks in a 4x4 envelope, sloping toward the face center.
        for (int brow = 0; brow < 2; brow++) {
            int left = WARDEN_HEAD_FRONT_X + (brow == 0 ? brows.leftX() : brows.rightX());
            int top = WARDEN_HEAD_FRONT_Y + (brow == 0 ? brows.leftY() : brows.rightY());
            for (int block = 0; block < 2; block++) {
                int offsetX = (brow == 0 ? block : 1 - block) * 2;
                for (int dy = 0; dy < 2; dy++) for (int dx = 0; dx < 2; dx++)
                    mask.setPixel(left + offsetX + dx, top + block * 2 + dy, preview ? 0xFFFFFFFF : 0x90FFFFFF);
            }
        }
        // Draw each scaled eye at its independent position. Neutral gray multiplies the
        // current phase tint into a darker shade, without forcing a different hue.
        var eyes = clockwork.bosses.ClockworkBossEyes.unpack(coordinates);
        var pupils = clockwork.bosses.ClockworkBossPupils.unpack(pupilSettings);
        for (int eye = 0; eye < 2; eye++) {
            int left = WARDEN_HEAD_FRONT_X + (eye == 0 ? eyes.leftX() : eyes.rightX());
            int top = WARDEN_HEAD_FRONT_Y + (eye == 0 ? eyes.leftY() : eyes.rightY());
            boolean visible = pupils.visibleIn(eyes, eye == 1);
            int pupilX = pupils.xIn(eyes, eye == 1), pupilY = pupils.yIn(eyes, eye == 1);
            int pupilWidth = pupils.widthIn(eyes), pupilHeight = pupils.heightIn(eyes);
            for (int dy = 0; dy < eyes.height(); dy++) {
                for (int dx = 0; dx < eyes.width(); dx++) {
                    boolean pupil = visible && dx >= pupilX && dx < pupilX + pupilWidth
                            && dy >= pupilY && dy < pupilY + pupilHeight;
                    mask.setPixel(left + dx, top + dy, pupil ? 0xFF666666 : 0xFFFFFFFF);
                }
            }
        }
    }
}
