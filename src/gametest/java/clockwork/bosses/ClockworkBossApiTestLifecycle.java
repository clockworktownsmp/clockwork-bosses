package clockwork.bosses;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

/** Disposable test mod only. Guard heartbeat/owned stop never enter the core jar
 * and run only under the explicit isolated candidate property. */
public final class ClockworkBossApiTestLifecycle implements ModInitializer {
    @Override public void onInitialize() {
        if (!Boolean.getBoolean("clockwork_bosses.api4.guard")) return;
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            ClockworkBossApiGameTests.captureShutdownPreStop();
            try {
                if (server.getTickCount() % 20 == 0) Files.writeString(Path.of("api4-heartbeat.txt"), Integer.toString(server.getTickCount()));
                if (Files.exists(Path.of("api4-stop.request"))) server.halt(false);
            } catch (IOException failure) { throw new UncheckedIOException("Disposable API guard heartbeat failed", failure); }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> ClockworkBossApiGameTests.releaseShutdownChunk());
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> ClockworkBossApiGameTests.verifyShutdown());
    }
}
