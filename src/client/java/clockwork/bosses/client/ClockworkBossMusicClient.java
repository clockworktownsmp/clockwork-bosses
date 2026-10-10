package clockwork.bosses.client;

import clockwork.bosses.ClockworkBossMusicControl;
import clockwork.bosses.ClockworkBossMusicData;
import clockwork.bosses.ClockworkBossSoundPayload;
import clockwork.bosses.ClockworkBosses;
import clockwork.bosses.mixin.client.SoundEngineAccessor;
import com.mojang.blaze3d.audio.Library;
import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.JOrbisAudioStream;
import net.minecraft.client.sounds.LoopingAudioStream;
import net.minecraft.client.sounds.SoundEngineExecutor;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sound.sampled.AudioFormat;

/**
 * One native streaming music channel, selected from the most recently entered ready encounter.
 * The client thread owns requests/cache/fade state; native workers own Vorbis and OpenAL work.
 * Minecraft's existing audio tick drives fades, not a new server/client watcher or timer thread.
 */
public final class ClockworkBossMusicClient {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClockworkBosses.MOD_ID);
    // Delivery order, not playback access, must match the server's four-file cache history.
    private static final LinkedHashMap<String, File> FILES = new LinkedHashMap<>();
    private static final LinkedHashMap<UUID, Request> REQUESTS = new LinkedHashMap<>();
    private static final AtomicInteger WORKERS = new AtomicInteger();
    private static volatile long generation;
    private static boolean preparing;
    private static volatile boolean paused;
    private static boolean menuPaused;
    private static java.util.Set<SoundSource> pauseExceptions = java.util.Set.of();
    private static long pauseStarted;
    private static Playback active;
    private static SoundEngineExecutor audioExecutor;
    private ClockworkBossMusicClient() {}

    /** Ordered tokens make death/exit effective even while a track is still crossing a slow connection. */
    public static void control(ClockworkBossMusicControl message) {
        if (message.start()) {
            Request previous = REQUESTS.get(message.boss());
            if (previous != null && previous.control.token().equals(message.token())) return;
            if (previous == null && REQUESTS.size() >= 16) return;
            REQUESTS.remove(message.boss());
            REQUESTS.put(message.boss(), new Request(message));
        } else {
            Request request = REQUESTS.get(message.boss());
            if (request == null || !request.control.token().equals(message.token())) return;
            REQUESTS.remove(message.boss());
            if (active != null && active.request == request) fade(active, message.fadeSeconds());
        }
    }

    /** Keep bounded compressed bytes; integrity/container validation happens off the render thread before decoding. */
    public static void accept(ClockworkBossMusicData message) {
        String key = message.key();
        byte[] bytes = message.bytes();
        File file = FILES.remove(key);
        if (bytes.length > 0) file = new File(bytes, message.revision());
        if (file != null) FILES.put(key, file);
        while (FILES.size() > 4) FILES.remove(FILES.keySet().iterator().next());
    }

    /** Called from vanilla SoundEngine.tick: constant work when inactive, one channel/fade while active. */
    public static void tick(SoundEngineAccessor engine) {
        if (active == null && REQUESTS.isEmpty() && !preparing) return;
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || !engine.clockworkBosses$isLoaded()) return;
        // Each encounter chooses its own native slider. Muted requests wait without
        // decoding; live category changes preserve the same bounded request history.
        if (active != null && engine.clockworkBosses$calculateVolume(1, active.request.control.channel()) <= 0) stopActive();
        if (active != null && active.handle.isStopped()) {
            Playback finished = active;
            active = null;
            if (audioExecutor != null) audioExecutor.execute(() -> close(finished.stream));
            if (!finished.fading && !finished.request.control.loop()) finished.request.completed = true;
        }
        Request desired = null;
        for (Request request : REQUESTS.values()) {
            File file = FILES.get(request.control.key());
            if (!request.completed && file != null && !file.rejected && engine.clockworkBosses$calculateVolume(1, request.control.channel()) > 0) desired = request;
        }
        if (active != null) {
            if (active.request != desired && !active.fading) fade(active, active.request.control.fadeSeconds());
            if (active.fading && !paused) {
                long now = System.nanoTime();
                if (now - active.fadeStart >= active.fadeNanos) {
                    stopActive();
                } else {
                    active.fadeGain = 1 - (float) ((now - active.fadeStart) / (double) active.fadeNanos);
                    volume(engine);
                }
            }
        }
        if (active == null && !preparing && desired != null) prepare(desired, FILES.get(desired.control.key()), engine);
    }

    /** Pause/resume freezes the wall-clock fade when the selected native category is paused. */
    public static void pause(SoundSource[] exceptions) {
        menuPaused = true; pauseExceptions = java.util.Set.copyOf(java.util.List.of(exceptions));
        if (active != null && pauseExceptions.contains(active.request.control.channel())) return;
        if (!paused) { paused = true; pauseStarted = System.nanoTime(); }
        // These borrowed channels have no SoundInstance entry; native pauseAllExcept
        // cannot discover them. Pause this owned stream explicitly, respecting exemptions.
        if (active != null) active.handle.execute(channel -> channel.pause());
    }
    public static void resume() {
        if (paused && active != null && active.fading) active.fadeStart += System.nanoTime() - pauseStarted;
        paused = false; menuPaused = false; pauseExceptions = java.util.Set.of();
    }
    /** Live category/master changes preserve the current fade gain. */
    public static void volume(SoundEngineAccessor engine) {
        Playback playback = active;
        if (playback == null) return;
        float gain = engine.clockworkBosses$calculateVolume(1, playback.request.control.channel())
                * playback.request.control.volume() * playback.fadeGain;
        playback.handle.execute(channel -> channel.setVolume(gain));
    }
    /** Ambient music yields only to an actual native channel, never to a pending/failed file. */
    public static boolean isPlaying() { return active != null && !active.handle.isStopped(); }

    /** Reload/device loss invalidates pending jobs before native channels close; cached requests may resume afterward. */
    public static void stopPlayback() {
        generation++;
        preparing = false;
        paused = false; menuPaused = false; pauseExceptions = java.util.Set.of();
        stopActive();
        if (audioExecutor != null) audioExecutor.executeBlocking(() -> {});
    }
    /** /stopsound respects each encounter's selected native category. */
    public static void stopRequested() { REQUESTS.clear(); stopPlayback(); }
    public static void stopRequested(SoundSource source) {
        if (source == null) { stopRequested(); return; }
        REQUESTS.values().removeIf(request -> request.control.channel() == source);
        if (active != null && active.request.control.channel() == source) stopActive();
    }
    /** Disconnect/client shutdown releases both requests and server-owned compressed content. */
    public static void clear() { stopRequested(); FILES.clear(); }

    /** Stops one borrowed source; native scheduleTick releases the source and closes its attached streaming decoder. */
    private static void stopActive() {
        Playback playback = active;
        active = null;
        if (playback != null) {
            playback.handle.execute(channel -> channel.stop());
            playback.channels.scheduleTick();
            // A stopped handle skips execute callbacks. Close even a stream whose
            // attachment callback never ran, after native channel cleanup in FIFO order.
            if (audioExecutor != null) audioExecutor.execute(() -> close(playback.stream));
        }
    }
    private static void fade(Playback playback, float seconds) {
        if (playback.fading) return;
        playback.fading = true;
        playback.fadeStart = paused ? pauseStarted : System.nanoTime();
        playback.fadeNanos = (long) (seconds * 1_000_000_000L);
        if (seconds == 0) stopActive();
    }

    /** Header/decode work uses the shared native pool; at most two old/current jobs survive reconnects. */
    private static void prepare(Request request, File file, SoundEngineAccessor engine) {
        if (WORKERS.incrementAndGet() > 2) { WORKERS.decrementAndGet(); return; }
        preparing = true;
        long epoch = generation;
        Minecraft client = Minecraft.getInstance();
        CompletableFuture<SafeStream> preparation;
        try {
            preparation = CompletableFuture.supplyAsync(() -> {
                try {
                    if (!ClockworkBossSoundPayload.matchesRevision(file.bytes, file.revision)
                            || ClockworkBossMusicData.validatedDurationMillis(file.bytes) <= 0)
                        throw new IOException("Music failed content hash or safe Vorbis checks");
                    ByteArrayInputStream input = new ByteArrayInputStream(file.bytes);
                    AudioStream decoded = request.control.loop()
                            ? new LoopingAudioStream(BoundedStream::new, input) : new BoundedStream(input);
                    return new SafeStream(decoded, file, request);
                } catch (IOException | RuntimeException invalid) { throw new java.util.concurrent.CompletionException(invalid); }
            }, Util.backgroundExecutor());
        } catch (java.util.concurrent.RejectedExecutionException unavailable) {
            // Submission itself can fail during shutdown. There is no completion
            // callback in that case, so return the reservation here exactly once.
            WORKERS.decrementAndGet();
            preparing = false;
            request.completed = true;
            LOGGER.warn("Could not schedule boss music decoding: {}", unavailable.getMessage());
            return;
        }
        preparation.whenComplete((stream, failure) -> {
            WORKERS.decrementAndGet();
            client.execute(() -> {
                if (epoch != generation) { close(stream); return; }
                if (failure != null) {
                    preparing = false;
                    file.rejected = true;
                    LOGGER.warn("Could not stream boss music: {}", failure.getCause() == null ? failure.getMessage() : failure.getCause().getMessage());
                    return;
                }
                if (!wanted(request) || !engine.clockworkBosses$isLoaded()) {
                    preparing = false; close(stream); return;
                }
                acquire(request, stream, engine, epoch);
            });
        });
    }
    private static boolean wanted(Request request) {
        return REQUESTS.get(request.control.boss()) == request && !request.completed;
    }

    /** Minecraft owns streaming buffers and refill work. No full PCM track is allocated or uploaded. */
    private static void acquire(Request request, AudioStream stream, SoundEngineAccessor engine, long epoch) {
        ChannelAccess channels = engine.clockworkBosses$getChannelAccess();
        audioExecutor = engine.clockworkBosses$getExecutor();
        CompletableFuture<ChannelAccess.ChannelHandle> acquisition;
        try {
            acquisition = channels.createHandle(Library.Pool.STREAMING);
        } catch (java.util.concurrent.RejectedExecutionException unavailable) {
            // No channel owns this decoder yet. Close it if native scheduling
            // rejects acquisition instead of leaving the preparation latch set.
            close(stream);
            preparing = false;
            request.completed = true;
            LOGGER.warn("Could not schedule a boss music channel: {}", unavailable.getMessage());
            return;
        }
        acquisition.whenComplete((handle, failure) -> {
            Minecraft.getInstance().execute(() -> {
                if (epoch != generation || failure != null || handle == null || !wanted(request) || active != null) {
                    if (epoch == generation) preparing = false;
                    // A null handle means temporary pool exhaustion; a failed
                    // future is an actual error and must not retry every tick.
                    if (epoch == generation && failure != null) request.completed = true;
                    close(stream);
                    if (handle != null) { handle.execute(channel -> channel.stop()); channels.scheduleTick(); }
                    return;
                }
                preparing = false;
                Playback playback = new Playback(request, handle, channels, stream);
                active = playback;
                paused = menuPaused && !pauseExceptions.contains(request.control.channel());
                if (paused) pauseStarted = System.nanoTime();
                // Prevent vanilla ambient music starting another track during this encounter.
                Minecraft.getInstance().getMusicManager().stopPlaying();
                float gain = engine.clockworkBosses$calculateVolume(1, request.control.channel()) * request.control.volume();
                handle.execute(channel -> {
                    if (epoch != generation || handle.isStopped()) { close(stream); channel.stop(); return; }
                    try {
                        channel.setRelative(true);
                        channel.disableAttenuation();
                        channel.setPitch(1);
                        channel.setVolume(gain);
                        channel.attachBufferStream(stream);
                        channel.play();
                        if (paused) channel.pause();
                    } catch (RuntimeException invalid) {
                        close(stream);
                        channel.stop();
                        LOGGER.warn("Could not play boss music stream: {}", invalid.getMessage());
                        Minecraft.getInstance().execute(() -> request.completed = true);
                    }
                });
            });
        });
    }
    private static void close(AudioStream stream) {
        if (stream != null) try { stream.close(); } catch (IOException ignored) { /* Native teardown is best effort. */ }
    }

    /** Decoder failures stop this request once instead of escaping onto Minecraft's audio thread or retrying every frame. */
    private static final class SafeStream implements AudioStream {
        private final AudioStream delegate;
        private final File file;
        private final Request request;
        private boolean closed;
        private boolean readAudio;
        SafeStream(AudioStream delegate, File file, Request request) {
            this.delegate = delegate; this.file = file; this.request = request;
        }
        @Override public AudioFormat getFormat() { return delegate.getFormat(); }
        @Override public synchronized ByteBuffer read(int size) {
            if (closed) return null;
            try {
                ByteBuffer result = delegate.read(size);
                if (result == null || !result.hasRemaining()) {
                    if (!readAudio || request.control.loop()) throw new IOException("Music contains no decoded audio");
                } else readAudio = true;
                return result;
            }
            catch (IOException | RuntimeException invalid) {
                try { close(); } catch (IOException ignored) { /* Stop still invalidates the request. */ }
                Minecraft.getInstance().execute(() -> {
                    file.rejected = true;
                    request.completed = true;
                    if (active != null && active.request == request) stopActive();
                    LOGGER.warn("Stopped invalid boss music stream: {}", invalid.getMessage());
                });
                return null;
            }
        }
        @Override public synchronized void close() throws IOException {
            if (!closed) { closed = true; delegate.close(); }
        }
    }

    /** Bounds each native streaming read and each decoded loop, even if a hostile granule count lies. */
    private static final class BoundedStream implements AudioStream {
        private final JOrbisAudioStream decoder;
        private final long maxDecodedBytes;
        private long decodedBytes;
        private boolean closed;
        BoundedStream(java.io.InputStream input) throws IOException {
            decoder = new JOrbisAudioStream(input);
            AudioFormat format = decoder.getFormat();
            if (format.getSampleSizeInBits() != 16 || format.getChannels() < 1 || format.getChannels() > 2
                    || !Float.isFinite(format.getSampleRate()) || format.getSampleRate() < 8000 || format.getSampleRate() > 192000) {
                decoder.close(); throw new IOException("Unsupported music format");
            }
            maxDecodedBytes = (long) format.getFrameSize() * (long) format.getSampleRate()
                    * (ClockworkBossMusicData.MAX_SECONDS + 1L);
        }
        @Override public AudioFormat getFormat() { return decoder.getFormat(); }
        @Override public ByteBuffer read(int size) throws IOException {
            if (closed || size <= 0 || size > 1024 * 1024) throw new IOException("Invalid music stream read");
            ByteBuffer result = decoder.read(size);
            decodedBytes += result.remaining();
            if (result.remaining() > 1024 * 1024 + 32768 || decodedBytes > maxDecodedBytes)
                throw new IOException("Decoded music exceeds streaming bounds");
            return result;
        }
        @Override public synchronized void close() throws IOException {
            if (!closed) { closed = true; decoder.close(); }
        }
    }
    private static final class File {
        final byte[] bytes;
        final String revision;
        boolean rejected;
        File(byte[] bytes, String revision) { this.bytes = bytes; this.revision = revision; }
    }
    private static final class Request {
        final ClockworkBossMusicControl control;
        boolean completed;
        Request(ClockworkBossMusicControl control) { this.control = control; }
    }
    private static final class Playback {
        final Request request;
        final ChannelAccess.ChannelHandle handle;
        final ChannelAccess channels;
        final AudioStream stream;
        boolean fading;
        long fadeStart;
        long fadeNanos;
        float fadeGain = 1;
        Playback(Request request, ChannelAccess.ChannelHandle handle, ChannelAccess channels, AudioStream stream) {
            this.request = request; this.handle = handle; this.channels = channels; this.stream = stream;
        }
    }
}
