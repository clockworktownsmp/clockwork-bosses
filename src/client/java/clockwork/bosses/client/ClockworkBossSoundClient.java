package clockwork.bosses.client;

import clockwork.bosses.ClockworkBosses;

import clockwork.bosses.ClockworkBossSoundPayload;
import clockwork.bosses.mixin.client.SoundEngineAccessor;
import clockwork.bosses.mixin.client.SoundManagerAccessor;
import com.mojang.blaze3d.audio.Library;
import com.mojang.blaze3d.audio.SoundBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.JOrbisAudioStream;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundEngineExecutor;
import net.minecraft.util.Util;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.lwjgl.system.MemoryUtil;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Decodes server-approved OGG files off-thread and plays them through Minecraft's audio channels. */
public final class ClockworkBossSoundClient {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClockworkBosses.MOD_ID);
    private static final int MAX_CACHED_FILES = 16;
    private static final int MAX_PENDING_DECODES = 4;
    private static final int MAX_WAITING_PLAYBACKS = 16;
    private static final int MAX_DECODED_SOUND_BYTES = 8 * 1024 * 1024;
    private static final long MAX_DECODED_CACHE_BYTES = 32L * 1024 * 1024;
    private static final int MAX_SOUND_DURATION_MILLIS = 30_000;
    private static final int MAX_ACTIVE_PLAYBACKS = 8;
    private static final Object CACHE_LOCK = new Object();
    private static final LinkedHashMap<String, byte[]> COMPRESSED_CACHE =
            new LinkedHashMap<>(16, 0.75F, true);
    private static final LinkedHashMap<String, Boolean> REJECTED_CACHE =
            new LinkedHashMap<>(16, 0.75F, true);
    private static final LinkedHashMap<String, DecodedSound> DECODED_CACHE =
            new LinkedHashMap<>(8, 0.75F, true);
    private static final Map<String, CompletableFuture<DecodedSound>> PENDING_DECODES = new LinkedHashMap<>();
    private static final Set<ActivePlayback> ACTIVE_PLAYBACKS = ConcurrentHashMap.newKeySet();
    private static final AtomicInteger ACTIVE_PLAYBACK_COUNT = new AtomicInteger();
    // Reservations cover decode, channel acquisition, playing and pause. Identity
    // removal prevents late completion from releasing a newer line for this boss.
    private static final Map<UUID, VoiceReservation> VOICE_RESERVATIONS = new ConcurrentHashMap<>();
    private static final AtomicInteger VOICE_RESERVATION_COUNT = new AtomicInteger();
    // Unlike the per-session pending map, this count survives cache clears until
    // the old decoder jobs finish, bounding work across repeated reconnects.
    private static final AtomicInteger RUNNING_DECODES = new AtomicInteger();
    private static final AtomicInteger WAITING_PLAYBACKS = new AtomicInteger();
    private static final AtomicLong CONNECTION_SESSION = new AtomicLong();
    // Device reloads invalidate pending channel acquisitions without dropping file caches.
    private static final AtomicLong AUDIO_GENERATION = new AtomicLong();
    // Borrowed native executor, never a mod-owned thread. Retain it so stopAll
    // can drain channel acquisitions even before they enter ACTIVE_PLAYBACKS.
    private static volatile SoundEngineExecutor currentAudioExecutor;
    private static volatile boolean playbackPaused;
    private static long decodedCacheBytes;

    private ClockworkBossSoundClient() {}

    /** Begins decoding received audio without making the game thread wait for Vorbis work. */
    public static void accept(ClockworkBossSoundPayload payload) {
        ClockworkBossSoundPayload playback = payload.playbackOnly();
        String identity = ClockworkBossSoundPayload.soundIdentity(payload.templateName(), payload.fileName(), payload.voiceLine());
        String key = identity + ":" + payload.revision();
        long session = CONNECTION_SESSION.get();
        DecodedSound decoded;
        CompletableFuture<DecodedSound> decodeTask;
        final VoiceReservation voice;

        synchronized (CACHE_LOCK) {
            byte[] receivedBytes = payload.oggBytes();
            if (receivedBytes.length > 0) {
                if (!ClockworkBossSoundPayload.matchesRevision(receivedBytes, payload.revision())) {
                    rememberRejected(key);
                    LOGGER.warn("Rejected Clockwork Boss sound '{}' because its content hash did not match",
                            payload.fileName());
                    return;
                }
                // Server delivery tracks sixteen filenames, each with one latest
                // revision. Retaining old revisions here would evict another file
                // earlier than the server expects and break metadata-only replay.
                forgetSupersededRevisions(identity, key);
                COMPRESSED_CACHE.put(key, receivedBytes);
                trimCompressedCache();
            }
            byte[] compressed = COMPRESSED_CACHE.get(key);
            if (compressed == null) {
                LOGGER.debug("Skipping Clockwork Boss sound '{}' because its audio bytes are not cached",
                        payload.fileName());
                return;
            }
            // Touch/cache every delivery before suppression so server/client
            // file LRUs remain aligned even when this voice is already busy.
            if (REJECTED_CACHE.get(key) != null) return;
            voice = payload.voiceLine() ? reserveVoice(payload.bossId()) : null;
            if (payload.voiceLine() && voice == null) return;

            decoded = DECODED_CACHE.get(key);
            if (decoded != null) {
                decodeTask = null;
            } else {
                decodeTask = PENDING_DECODES.get(key);
                if (decodeTask == null) {
                    // Do not let a burst of unique server files queue unlimited Vorbis work.
                    if (RUNNING_DECODES.get() >= MAX_PENDING_DECODES) {
                        releaseVoice(voice);
                        return;
                    }
                    RUNNING_DECODES.incrementAndGet();
                    CompletableFuture<DecodedSound> pending = new CompletableFuture<>();
                    PENDING_DECODES.put(key, pending);
                    // Cache arrays are private and immutable; eviction drops a
                    // reference, so the decoder does not need another file copy.
                    byte[] compressedInput = compressed;
                    CompletableFuture.supplyAsync(() -> decode(compressedInput), Util.backgroundExecutor())
                            .whenComplete((result, failure) -> {
                                RUNNING_DECODES.decrementAndGet();
                                synchronized (CACHE_LOCK) {
                                    PENDING_DECODES.remove(key, pending);
                                    if (failure == null && CONNECTION_SESSION.get() == session
                                            && COMPRESSED_CACHE.containsKey(key)) {
                                        cacheDecodedSound(key, result);
                                    } else if (failure != null && CONNECTION_SESSION.get() == session) {
                                        rememberRejected(key);
                                    }
                                }
                                if (failure == null) {
                                    pending.complete(result);
                                } else {
                                    pending.completeExceptionally(failure);
                                }
                            });
                    decodeTask = pending;
                }
            }
        }

        if (decoded != null) {
            queuePlayback(decoded, playback, session, voice);
        } else if (decodeTask != null) {
            // Repeated events for one pending decode must not accumulate unlimited
            // callbacks retaining packet arrays. This count also survives reconnects.
            if (!reserveSlot(WAITING_PLAYBACKS, MAX_WAITING_PLAYBACKS)) {
                releaseVoice(voice);
                return;
            }
            decodeTask.whenComplete((result, failure) -> {
                WAITING_PLAYBACKS.decrementAndGet();
                if (failure != null) {
                    Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                            ? failure.getCause() : failure;
                    LOGGER.warn("Could not decode Clockwork Boss sound '{}': {}",
                            playback.fileName(), cause.getMessage());
                    releaseVoice(voice);
                    return;
                }
                queuePlayback(result, playback, session, voice);
            });
        }
    }

    /** Drops per-server audio data on disconnect; the shared decoder pool remains Minecraft-owned. */
    public static void clear() {
        CONNECTION_SESSION.incrementAndGet();
        synchronized (CACHE_LOCK) {
            COMPRESSED_CACHE.clear();
            REJECTED_CACHE.clear();
            DECODED_CACHE.clear();
            PENDING_DECODES.clear();
            decodedCacheBytes = 0;
        }
        stopPlayback();
    }

    /** A device/resource reload stops native playback but retains server file bytes. */
    public static void stopPlayback() {
        AUDIO_GENERATION.incrementAndGet();
        playbackPaused = false;
        SoundEngineExecutor executor = currentAudioExecutor;
        if (executor != null) executor.executeBlocking(() -> {});
        // Include reservations whose decoder/client callback has not reached
        // the native channel yet. Released tokens cannot start playback later.
        VOICE_RESERVATIONS.values().forEach(VoiceReservation::release);
        ActivePlayback[] playbacks = ACTIVE_PLAYBACKS.toArray(ActivePlayback[]::new);
        for (ActivePlayback playback : playbacks) {
            playback.stop();
        }
        // Native stopAll destroys its device after this callback. Drain queued
        // source removal and buffer deletion while that device still exists.
        if (executor != null) executor.executeBlocking(() -> {});
    }

    /** Freezes cleanup deadlines while Minecraft pauses Hostile audio. */
    public static void pausePlayback() {
        playbackPaused = true;
        for (ActivePlayback playback : ACTIVE_PLAYBACKS) playback.pause();
    }

    /** Continues the remaining deadline rather than restarting the whole clip. */
    public static void resumePlayback() {
        playbackPaused = false;
        for (ActivePlayback playback : ACTIVE_PLAYBACKS) playback.resume();
    }

    /** Uses vanilla's category calculation, including gain added by other native callers. */
    public static void refreshVolume(SoundEngineAccessor engine) {
        float gain = engine.clockworkBosses$calculateVolume(1.0F, SoundSource.HOSTILE);
        for (ActivePlayback playback : ACTIVE_PLAYBACKS) {
            playback.handle.execute(channel -> channel.setVolume(gain * playback.baseVolume));
        }
    }

    /** Plays an already-decoded clip after returning to Minecraft's main thread. */
    private static void queuePlayback(
            DecodedSound sound,
            ClockworkBossSoundPayload payload,
            long session,
            VoiceReservation voice
    ) {
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> {
            if (CONNECTION_SESSION.get() != session || client.level == null
                    || (voice != null && voice.released.get())) {
                releaseVoice(voice);
                return;
            }
            play(sound, payload, client, session, voice);
        });
    }

    /** Uses vanilla's OpenAL channel pool, OGG decoder, world positioning, and Hostile volume slider. */
    private static void play(
            DecodedSound sound,
            ClockworkBossSoundPayload payload,
            Minecraft client,
            long session,
            VoiceReservation voice
    ) {
        SoundEngine soundEngine = ((SoundManagerAccessor) (Object) client.getSoundManager())
                .clockworkBosses$getSoundEngine();
        SoundEngineAccessor engine = (SoundEngineAccessor) (Object) soundEngine;
        float hostileVolume = engine.clockworkBosses$calculateVolume(1.0F, SoundSource.HOSTILE);
        float volume = hostileVolume * payload.volume();
        if (!Float.isFinite(volume) || volume <= 0.0F) {
            releaseVoice(voice);
            return;
        }
        if (!reserveSlot(ACTIVE_PLAYBACK_COUNT, MAX_ACTIVE_PLAYBACKS)) {
            releaseVoice(voice);
            return;
        }

        ChannelAccess channels = engine.clockworkBosses$getChannelAccess();
        SoundEngineExecutor audioExecutor = engine.clockworkBosses$getExecutor();
        currentAudioExecutor = audioExecutor;
        long generation = AUDIO_GENERATION.get();
        CompletableFuture<ChannelAccess.ChannelHandle> channelRequest;
        try {
            channelRequest = channels.createHandle(Library.Pool.STATIC);
        } catch (RuntimeException unavailable) {
            ACTIVE_PLAYBACK_COUNT.decrementAndGet();
            releaseVoice(voice);
            LOGGER.warn("Could not acquire Clockwork Boss audio channel: {}", unavailable.getMessage());
            return;
        }
        channelRequest.whenComplete((handle, failure) -> {
            if (failure != null || handle == null) {
                ACTIVE_PLAYBACK_COUNT.decrementAndGet();
                releaseVoice(voice);
                return;
            }
            if (handle.isStopped() || CONNECTION_SESSION.get() != session
                    || AUDIO_GENERATION.get() != generation || (voice != null && voice.released.get())) {
                releaseUnstartedChannel(handle, channels, voice);
                return;
            }
            // Cached PCM lives on the Java heap. Allocate native upload memory
            // only for a real channel and release it immediately after upload
            // (or during cleanup if the channel was stopped before its callback).
            ByteBuffer upload;
            SoundBuffer buffer;
            try {
                upload = MemoryUtil.memAlloc(sound.pcm().remaining());
            } catch (RuntimeException | OutOfMemoryError unavailable) {
                releaseUnstartedChannel(handle, channels, voice);
                LOGGER.warn("Could not allocate Clockwork Boss audio upload memory: {}", unavailable.getMessage());
                return;
            }
            try {
                upload.put(sound.pcm().duplicate()).flip();
                buffer = new SoundBuffer(upload, sound.format());
            } catch (RuntimeException | OutOfMemoryError unavailable) {
                MemoryUtil.memFree(upload);
                releaseUnstartedChannel(handle, channels, voice);
                LOGGER.warn("Could not prepare Clockwork Boss audio upload: {}", unavailable.getMessage());
                return;
            }
            ActivePlayback playback = new ActivePlayback(handle, buffer, upload, channels,
                    audioExecutor, payload.volume(), voice);
            ACTIVE_PLAYBACKS.add(playback);
            // Schedule even when an engine reload stops the handle before execute()
            // runs. Pitch changes playback duration: 0.5 plays twice as long.
            long lifetime = (long) Math.ceil(sound.durationMillis() / payload.pitch()) + 250L;
            playback.beginLifetime(lifetime);
            handle.execute(channel -> {
                if (handle.isStopped() || CONNECTION_SESSION.get() != session
                        || AUDIO_GENERATION.get() != generation || (voice != null && voice.released.get())) {
                    playback.stop();
                    return;
                }
                try {
                    channel.setRelative(false);
                    channel.setSelfPosition(new Vec3(payload.x(), payload.y(), payload.z()));
                    channel.linearAttenuation(payload.range());
                    channel.setPitch(payload.pitch());
                    channel.setVolume(volume);
                    channel.attachStaticBuffer(buffer);
                    channel.play();
                    if (playbackPaused) channel.pause();
                } catch (RuntimeException e) {
                    LOGGER.warn("Could not play Clockwork Boss OGG sound '{}': {}",
                            payload.fileName(), e.getMessage());
                    playback.stop();
                } finally {
                    playback.releaseUpload();
                }
            });
        });
    }

    /** Failed/stale acquisitions own a source but no playback buffer; native channel pruning releases it. */
    private static void releaseUnstartedChannel(ChannelAccess.ChannelHandle handle, ChannelAccess channels,
                                               VoiceReservation voice) {
        try {
            handle.execute(channel -> channel.stop());
            channels.scheduleTick();
        } finally {
            ACTIVE_PLAYBACK_COUNT.decrementAndGet();
            releaseVoice(voice);
        }
    }

    /** Bounds live work even when asynchronous jobs from an earlier connection remain. */
    private static boolean reserveSlot(AtomicInteger count, int maximum) {
        while (true) {
            int current = count.get();
            if (current >= maximum) {
                return false;
            }
            if (count.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    /** Bounds outstanding voices as well as enforcing one line per boss on this client. */
    private static VoiceReservation reserveVoice(UUID bossId) {
        if (!reserveSlot(VOICE_RESERVATION_COUNT, MAX_ACTIVE_PLAYBACKS)) return null;
        VoiceReservation voice = new VoiceReservation(bossId);
        if (VOICE_RESERVATIONS.putIfAbsent(bossId, voice) != null) {
            voice.release();
            return null;
        }
        return voice;
    }

    private static void releaseVoice(VoiceReservation voice) {
        if (voice != null) voice.release();
    }

    /** One idempotent lease; disconnect/failure/normal completion all release the same owner. */
    private static final class VoiceReservation {
        private final UUID bossId;
        private final AtomicBoolean released = new AtomicBoolean();

        private VoiceReservation(UUID bossId) { this.bossId = bossId; }

        private void release() {
            if (!released.compareAndSet(false, true)) return;
            VOICE_RESERVATIONS.remove(bossId, this);
            VOICE_RESERVATION_COUNT.decrementAndGet();
        }
    }

    /** Decodes bounded mono/stereo PCM through Minecraft's bundled Vorbis decoder. */
    private static DecodedSound decode(byte[] oggBytes) {
        if (!ClockworkBossSoundPayload.hasSafeVorbisContainer(oggBytes)) {
            throw new CompletionException(new IOException("invalid OGG page structure or checksum"));
        }
        try (JOrbisAudioStream stream = new JOrbisAudioStream(new ByteArrayInputStream(oggBytes))) {
            var format = stream.getFormat();
            int channels = format.getChannels();
            if (channels < 1 || channels > 2
                    || format.getSampleSizeInBits() != 16
                    || format.getSampleRate() < 8_000.0F
                    || format.getSampleRate() > 192_000.0F) {
                throw new IOException("only 16-bit mono or stereo OGG at 8-192 kHz is supported");
            }

            ByteArrayOutputStream pcmBytes = new ByteArrayOutputStream();
            int decodedSize = 0;
            while (true) {
                ByteBuffer chunk = stream.read(32 * 1024);
                if (chunk == null || !chunk.hasRemaining()) {
                    break;
                }
                int chunkSize = chunk.remaining();
                decodedSize += chunkSize;
                if (decodedSize > MAX_DECODED_SOUND_BYTES) {
                    throw new IOException("decoded audio exceeds the 8 MiB safety limit");
                }
                byte[] copy = new byte[chunkSize];
                chunk.get(copy);
                pcmBytes.write(copy);
            }

            int frameSize = format.getFrameSize();
            float frameRate = format.getFrameRate();
            if (decodedSize == 0 || frameSize <= 0 || !Float.isFinite(frameRate) || frameRate <= 0.0F) {
                throw new IOException("OGG contains no playable audio frames");
            }
            long durationMillis = Math.round((decodedSize / (double) frameSize) / frameRate * 1000.0);
            if (durationMillis <= 0 || durationMillis > MAX_SOUND_DURATION_MILLIS) {
                throw new IOException("decoded sound must be no longer than 30 seconds");
            }

            byte[] bytes = pcmBytes.toByteArray();
            // Heap ownership follows the bounded LRU. Repeated reconnects no
            // longer accumulate direct buffers waiting for Java's cleaner.
            return new DecodedSound(ByteBuffer.wrap(bytes).asReadOnlyBuffer(), format, durationMillis, bytes.length);
        } catch (IOException | RuntimeException e) {
            throw new CompletionException(e);
        }
    }

    /** Maintains both file-count and byte-count LRU limits under CACHE_LOCK. */
    private static void cacheDecodedSound(String key, DecodedSound sound) {
        DecodedSound previous = DECODED_CACHE.put(key, sound);
        if (previous != null) {
            decodedCacheBytes -= previous.memoryBytes();
        }
        decodedCacheBytes += sound.memoryBytes();
        Iterator<Map.Entry<String, DecodedSound>> iterator = DECODED_CACHE.entrySet().iterator();
        while ((DECODED_CACHE.size() > MAX_CACHED_FILES || decodedCacheBytes > MAX_DECODED_CACHE_BYTES)
                && iterator.hasNext()) {
            DecodedSound oldest = iterator.next().getValue();
            decodedCacheBytes -= oldest.memoryBytes();
            iterator.remove();
        }
    }

    /** Keeps one revision per filename; active playback still owns its independent PCM view. */
    private static void forgetSupersededRevisions(String fileName, String currentKey) {
        String prefix = fileName + ":";
        COMPRESSED_CACHE.keySet().removeIf(key -> key.startsWith(prefix) && !key.equals(currentKey));
        var decoded = DECODED_CACHE.entrySet().iterator();
        while (decoded.hasNext()) {
            var entry = decoded.next();
            if (entry.getKey().startsWith(prefix) && !entry.getKey().equals(currentKey)) {
                decodedCacheBytes -= entry.getValue().memoryBytes();
                decoded.remove();
            }
        }
    }

    /** Compressed files have a packet-size cap, so a count cap also bounds their bytes. */
    private static void trimCompressedCache() {
        while (COMPRESSED_CACHE.size() > MAX_CACHED_FILES) {
            String eldest = COMPRESSED_CACHE.keySet().iterator().next();
            COMPRESSED_CACHE.remove(eldest);
        }
    }

    /** Suppresses repeated decode attempts and log spam for the same invalid revision. */
    private static void rememberRejected(String key) {
        REJECTED_CACHE.put(key, Boolean.TRUE);
        while (REJECTED_CACHE.size() > MAX_CACHED_FILES) {
            String eldest = REJECTED_CACHE.keySet().iterator().next();
            REJECTED_CACHE.remove(eldest);
        }
    }

    /** Owns one temporary vanilla channel and its OpenAL buffer until playback ends. */
    private static final class ActivePlayback {
        private final ChannelAccess.ChannelHandle handle;
        private final SoundBuffer buffer;
        private final ByteBuffer upload;
        private final AtomicBoolean uploadReleased = new AtomicBoolean();
        private final ChannelAccess channels;
        private final SoundEngineExecutor audioExecutor;
        private final float baseVolume;
        private final VoiceReservation voice;
        private final AtomicBoolean released = new AtomicBoolean();
        // One cancellable timeout per clip; no extra scheduled thread or tick watcher.
        private CompletableFuture<Void> timeout;
        private long deadlineNanos;
        private long remainingNanos;
        private boolean paused;

        private ActivePlayback(ChannelAccess.ChannelHandle handle, SoundBuffer buffer, ByteBuffer upload,
                               ChannelAccess channels, SoundEngineExecutor audioExecutor, float baseVolume,
                               VoiceReservation voice) {
            this.handle = handle;
            this.buffer = buffer;
            this.upload = upload;
            this.channels = channels;
            this.audioExecutor = audioExecutor;
            this.baseVolume = baseVolume;
            this.voice = voice;
        }

        /** Native SoundBuffer copies PCM into OpenAL; it does not free caller-owned upload memory. */
        private void releaseUpload() {
            if (uploadReleased.compareAndSet(false, true)) MemoryUtil.memFree(upload);
        }

        /** Starts a pitch-adjusted deadline, respecting an already paused game. */
        private synchronized void beginLifetime(long millis) {
            remainingNanos = TimeUnit.MILLISECONDS.toNanos(millis);
            paused = playbackPaused;
            if (!released.get() && !paused) armTimeout();
        }

        /** Cancels the scheduled future so disconnects do not retain native buffers for 60 seconds. */
        private void cancelTimeout() {
            if (timeout != null) {
                timeout.complete(null);
                timeout = null;
            }
        }

        /** Called with this object's monitor held; completion queues cleanup without holding it. */
        private void armTimeout() {
            deadlineNanos = System.nanoTime() + remainingNanos;
            CompletableFuture<Void> next = new CompletableFuture<>();
            timeout = next;
            next.orTimeout(Math.max(1L, remainingNanos), TimeUnit.NANOSECONDS)
                    .whenComplete((unused, failure) -> {
                        if (failure == null) return;
                        // Run cleanup on Minecraft's main thread, also preventing
                        // a device shutdown racing a timer-thread buffer deletion.
                        Minecraft.getInstance().execute(() -> {
                            synchronized (this) {
                                if (timeout != next || paused) return;
                            }
                            stop();
                        });
                    });
        }

        /** Native channel pause plus a frozen timeout keep long clips intact across menus. */
        private synchronized void pause() {
            if (released.get() || paused) return;
            paused = true;
            remainingNanos = Math.max(1L, deadlineNanos - System.nanoTime());
            cancelTimeout();
            handle.execute(channel -> channel.pause());
        }

        /** Vanilla resumes pooled channels itself; only our timeout needs restarting. */
        private synchronized void resume() {
            if (released.get() || !paused) return;
            paused = false;
            armTimeout();
        }

        private void stop() {
            if (!released.compareAndSet(false, true)) {
                return;
            }
            synchronized (this) { cancelTimeout(); }
            // execute(channel -> ...) skips its callback when vanilla has already
            // released the source. Buffer cleanup must therefore be a separate task
            // on the native audio executor, never conditional on a live channel.
            // Never call ChannelHandle.release() directly: it does not remove
            // the handle from ChannelAccess's owned set. Native scheduleTick
            // both releases the stopped source and removes its handle. FIFO
            // audio tasks then delete the buffer even if the handle was already
            // auto-released. stopPlayback drains this queue before device teardown.
            handle.execute(channel -> channel.stop());
            channels.scheduleTick();
            audioExecutor.execute(() -> {
                try {
                    buffer.discardAlBuffer();
                } finally {
                    releaseUpload();
                    // Keep stopped-but-pending cleanup in the live set until
                    // native resources are freed, so device teardown can drain it.
                    ACTIVE_PLAYBACKS.remove(this);
                    ACTIVE_PLAYBACK_COUNT.decrementAndGet();
                    releaseVoice(voice);
                }
            });
        }
    }

    private record DecodedSound(ByteBuffer pcm, javax.sound.sampled.AudioFormat format,
                                long durationMillis, long memoryBytes) {}
}
