package clockwork.bosses;

import clockwork.bosses.client.ClockworkBossMusicClient;
import clockwork.bosses.client.ClockworkBossTemplateScreen;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.sounds.SoundSource;

/** Actual integrated Minecraft/editor/network/JOrbis/OpenAL acceptance. The
 * driver opens owner editor sessions, presses native buttons, saves through
 * the real server, and activates/removes real bosses. No audio is injected into
 * the client cache. A null OpenAL device proves native streaming, not speakers. */
public final class ClockworkBossSharedMusicClientTest implements FabricClientGameTest {
    private static int assertions;
    private static final JsonArray audio = new JsonArray();
    private static final List<Object> streams = new ArrayList<>();
    private static void check(boolean condition, String text) { assertions++; if (!condition) throw new AssertionError(text); }
    private static Object field(Object object, String name) {
        try { var f = (object instanceof Class<?> type ? type : object.getClass()).getDeclaredField(name); f.setAccessible(true); return f.get(object instanceof Class<?> ? null : object); }
        catch (ReflectiveOperationException e) { throw new AssertionError(name, e); }
    }
    private static void set(Object object, String name, Object value) throws Exception { var f = object.getClass().getDeclaredField(name); f.setAccessible(true); f.set(object, value); }
    private static Object call(Object object, String name, Class<?>[] types, Object... args) throws Exception {
        var method = object.getClass().getDeclaredMethod(name, types); method.setAccessible(true); return method.invoke(object, args);
    }
    private static ClockworkBossTemplateScreen screen(Minecraft client) { return (ClockworkBossTemplateScreen) client.gui.screen(); }
    private static void press(Button button) { button.onPress(new net.minecraft.client.input.MouseButtonInfo(0, 0)); }
    @SuppressWarnings("unchecked") private static void showMusic(Minecraft client) throws Exception {
        var screen = screen(client); call(screen, "selectPage", new Class<?>[]{String.class}, "sounds");
        ((Set<String>) field(screen, "collapsed")).remove("music"); set(screen, "scroll", 0); call(screen, "rebuild", new Class<?>[0]);
    }
    private static void select(Minecraft client, String path, String value) throws Exception {
        var screen = screen(client); String old = path.equals("music.source") ? "template" : "boss1.ogg";
        // Open the actual field's native dropdown before driving its production
        // selection responder. Scroll uses the same visible widget rebuild.
        boolean opened = false;
        for (int scroll = 0; scroll <= (Integer) field(screen, "fullHeight") + 100; scroll += 60) {
            set(screen, "scroll", scroll); call(screen, "rebuild", new Class<?>[0]);
            var button = screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                    .filter(b -> b.getMessage().getString().equalsIgnoreCase(old + " ▾")).findFirst();
            if (button.isPresent()) { press(button.get()); opened = true; break; }
        }
        check(opened && field(screen, "dropdown") != null, "actual dropdown opened for " + path);
        check(field(field(screen, "dropdown"), "path").equals(path), "native dropdown belongs to selected music field");
        call(screen, "choose", new Class<?>[]{String.class, String.class}, path, value);
    }
    private static void save(Minecraft client) { press((Button) field(screen(client), "saveButton")); }
    private static boolean settled(Minecraft client) { return client.gui.screen() instanceof ClockworkBossTemplateScreen s && !(Boolean) field(s, "busy"); }

    @Override public void runTest(ClientGameTestContext context) {
        try {
            // All fixture file work is on this test orchestration thread, never
            // the owning Minecraft thread. This config belongs to this run only.
            prepare("music-native", "template", "boss1.ogg", false);
            prepare("music-missing", "library", "missing.ogg", false);
            prepare("music-first", "library", "boss1.ogg", true);
            prepare("music-second", "library", "boss2.ogg", true);
            prepare("music-alias", "library", "boss1.ogg", true);
            prepare("music-cancelled", "library", "boss2.ogg", true);
            prepare("library", "template", "boss1.ogg", true);
            try (var world = context.worldBuilder().adjustSettings(settings -> {
                settings.setAllowCommands(true);
                settings.setGameMode(net.minecraft.client.gui.screens.worldselection.WorldCreationUiState.SelectedGameMode.CREATIVE);
            }).create()) {
                context.waitFor(client -> client.level != null && client.player != null, 600);
                context.runOnClient(client -> {
                    client.options.getSoundSourceOptionInstance(SoundSource.MASTER).set(1.0);
                    client.options.getSoundSourceOptionInstance(SoundSource.RECORDS).set(1.0);
                    client.options.getSoundSourceOptionInstance(SoundSource.MUSIC).set(1.0);
                    provenance();
                });
                var serverContext = world.getServer();
                serverContext.runOnServer(server -> {
                    var player = server.getPlayerList().getPlayers().getFirst();
                    check(player.isCreative(), "native creative owner isolates audio acceptance from boss combat");
                    check(ClockworkBossPermissions.Action.TEMPLATE_EDIT.allowed(player.createCommandSourceStack()), "actual singleplayer owner editor permission");
                    check(net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(player, ClockworkBossLibraryMusicData.TYPE), "library payload negotiated by real client");
                    check(net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(player, ClockworkBossMusicData.TYPE), "legacy template payload still negotiated");
                    check(ClockworkBossTemplateEditor.open(player.createCommandSourceStack(), "music-native", false) == 1, "real server editor session opened");
                });
                context.waitFor(client -> client.gui.screen() instanceof ClockworkBossTemplateScreen, 600);
                context.runOnClient(client -> { showMusic(client); select(client, "music.source", "library"); select(client, "music.file", "boss2.ogg"); save(client); });
                context.waitFor(ClockworkBossSharedMusicClientTest::settled, 600);
                JsonObject saved = ClockworkBossTemplateStore.load("music-native");
                check(saved.getAsJsonObject("music").get("source").getAsString().equals("library")
                        && saved.getAsJsonObject("music").get("file").getAsString().equals("boss2.ogg"), "actual native Save writes explicit library reference");
                check(saved.getAsJsonObject("music").get("volume").getAsDouble() == .65
                        && !saved.getAsJsonObject("music").get("loop").getAsBoolean(), "native selection preserves playback settings");
                check(saved.getAsJsonObject("addon").get("extreme").getAsString().equals("1e-2000000000"), "native Save preserves opaque addon lexeme");
                check(saved.getAsJsonObject("_music_library").get("addon_field").getAsString().equals("untouched"), "native Save preserves unknown legacy root field");
                context.runOnClient(client -> { client.options.guiScale().set(1); client.getWindow().setWindowed(1000, 650); });
                context.waitFor(client -> client.getWindow().getWidth() == 1000 && client.getWindow().getHeight() == 650);
                context.runOnClient(client -> { client.resizeGui(); showMusic(client); scrollToFile(client); });
                context.waitTicks(4); context.takeScreenshot("shared-music-native-library-high");

                serverContext.runOnServer(server -> { var player = server.getPlayerList().getPlayers().getFirst(); ClockworkBossTemplateEditor.open(player.createCommandSourceStack(), "music-missing", false); });
                context.waitFor(client -> client.gui.screen() instanceof ClockworkBossTemplateScreen s && field(s, "name").equals("music-missing"), 600);
                context.runOnClient(client -> {
                    client.options.guiScale().set(2); client.getWindow().setWindowed(640, 480);
                });
                context.waitFor(client -> client.getWindow().getWidth() == 640 && client.getWindow().getHeight() == 480);
                context.runOnClient(client -> {
                    client.resizeGui(); showMusic(client); scrollToFile(client);
                    check(((String) call(screen(client), "musicNote", new Class<?>[0])).contains("Missing shared OGG"), "actual native missing dependency diagnosis");
                });
                context.waitTicks(4); context.takeScreenshot("shared-music-native-missing-low");
                byte[] missingBefore = Files.readAllBytes(ClockworkBossTemplateStore.pathFor("music-missing"));
                context.runOnClient(client -> { input(client, "music.volume").setValue("0.6"); save(client); });
                context.waitFor(ClockworkBossSharedMusicClientTest::settled, 600);
                check(Arrays.equals(missingBefore, Files.readAllBytes(ClockworkBossTemplateStore.pathFor("music-missing"))), "native Save refuses missing shared dependency without changing active JSON");
                context.runOnClient(client -> check(((String) field(screen(client), "status")).contains("missing.ogg"), "native Save returns actual server dependency failure"));
                context.runOnClient(client -> client.gui.setScreen(null));

                // Start through the real encounter resolver. Full transfer,
                // receipt flow, native decoder and streaming pool are exercised.
                for (String name : List.of("music-first", "music-alias", "music-second", "library")) {
                    if (name.equals("music-second")) {
                        JsonObject cancelled = ClockworkBossTemplateStore.load("music-cancelled");
                        context.runOnClient(client -> clockwork.bosses.client.ClockworkBossMusicReceiptPause.hold());
                        serverContext.runOnServer(server -> {
                            var player = server.getPlayerList().getPlayers().getFirst(); var source = server.createCommandSourceStack().withEntity(player).withPosition(player.position());
                            check(ClockworkBossManager.spawnLoaded(source, "music-cancelled", "music-cancelled", cancelled) == 1, "native boss for partial-transfer cancellation");
                            check(ClockworkBossManager.activate(source, "music-cancelled") == 1, "native partial-transfer activation");
                        });
                        context.waitFor(client -> field(clockwork.bosses.client.ClockworkBossMediaClient.class, "assembled") != null, 1200);
                        context.runOnClient(client -> {
                            int received = (Integer) field(clockwork.bosses.client.ClockworkBossMediaClient.class, "received");
                            check(received > 0 && received <= 4 * ClockworkBossMediaChunk.CHUNK_BYTES, "real native transfer stays inside 64KiB unacknowledged window");
                        });
                        serverContext.runOnServer(server -> check(ClockworkBossManager.remove(server.createCommandSourceStack(), "music-cancelled") == 1, "native removal before full track arrival"));
                        context.waitFor(client -> ((Map<?, ?>) field(ClockworkBossMusicClient.class, "REQUESTS")).isEmpty(), 600);
                        context.runOnClient(client -> clockwork.bosses.client.ClockworkBossMusicReceiptPause.release());
                        context.waitFor(client -> field(clockwork.bosses.client.ClockworkBossMediaClient.class, "assembled") == null, 600);
                        context.runOnClient(client -> check(field(ClockworkBossMusicClient.class, "active") == null
                                && ((Map<?, ?>) field(ClockworkBossMusicClient.class, "REQUESTS")).isEmpty(), "late real track bytes cannot restart removed encounter"));
                    }
                    JsonObject config = ClockworkBossTemplateStore.load(name);
                    serverContext.runOnServer(server -> {
                        var player = server.getPlayerList().getPlayers().getFirst();
                        var source = server.createCommandSourceStack().withEntity(player).withPosition(player.position());
                        check(ClockworkBossManager.spawnLoaded(source, name, name, config) == 1, "native boss spawned for actual track");
                        check(ClockworkBossManager.activate(source, name) == 1, "native activation");
                    });
                    String digest = name.equals("library") || name.equals("music-second")
                            ? "1f498c058329537ba455e3ac6d7bc0b82b3c55f9630252d4a4c6007024d78dc6"
                            : "0073610006a2dc0914469e4460f53c47bce8576d53198dd98997e8f3d7609a07";
                    String expected = (name.equals("library") ? "library:boss1.ogg:" : "library::" + (name.equals("music-second") ? "boss2.ogg:" : "boss1.ogg:")) + digest;
                    context.waitFor(client -> playing(expected), 1200); context.waitTicks(30);
                    context.runOnClient(client -> recordAudio(name, expected));
                    serverContext.runOnServer(server -> {
                        check(ClockworkBossManager.sharedMusicReferences().isEmpty() == name.equals("library"), "loaded references distinguish shared music from real template named library");
                        var player = server.getPlayerList().getPlayers().getFirst();
                        var sent = (Map<?, ?>) field(ClockworkBossMusic.class, "SENT");
                        check(sent.get(player.getUUID()) instanceof Map<?, ?> history && history.keySet().stream().anyMatch(key -> key.toString().startsWith(expected)), "real native transfer records the acknowledged/cached content identity");
                        var transfers = (Map<?, ?>) field(ClockworkBossMediaTransfers.class, "PLAYERS"); Object transfer = transfers.get(player.getUUID());
                        check(transfer == null || field(transfer, "current") == null && ((Map<?, ?>) field(transfer, "waiting")).isEmpty(), "real receipt flow drains native transfer window and queue");
                        if (name.equals("music-alias")) {
                            Object cache = field(ClockworkBossMusic.class, "FILES");
                            check(((Map<?, ?>) field(cache, "files")).size() == 1, "second template shares exactly one native server asset snapshot");
                        }
                        check(ClockworkBossManager.remove(server.createCommandSourceStack(), name) == 1, "actual encounter removal");
                    });
                    context.waitFor(client -> field(ClockworkBossMusicClient.class, "active") == null
                            && ((Map<?, ?>) field(ClockworkBossMusicClient.class, "REQUESTS")).isEmpty(), 600);
                    context.waitFor(client -> streamsClosed(), 200);
                    context.runOnClient(client -> { for (Object stream : streams) synchronized (stream) { check((Boolean) field(stream, "closed"), "native stream closed after encounter removal"); } });
                }
                context.runOnClient(client -> { ClockworkBossMusicClient.clear(); check(((Map<?, ?>) field(ClockworkBossMusicClient.class, "FILES")).isEmpty(), "client compressed cache released"); });
            }
            context.runOnClient(client -> {
                check(field(ClockworkBossMusicClient.class, "active") == null && ((Map<?, ?>) field(ClockworkBossMusicClient.class, "REQUESTS")).isEmpty(), "world disconnect clears music requests/playback");
                check(((java.util.concurrent.atomic.AtomicInteger) field(ClockworkBossMusicClient.class, "WORKERS")).get() == 0, "native decoder jobs released");
                check(field(clockwork.bosses.client.ClockworkBossMediaClient.class, "assembled") == null, "partial transfer buffer released");
                check(((Map<?, ?>) field(ClockworkBossMusic.class, "ENCOUNTERS")).isEmpty() && ((Map<?, ?>) field(ClockworkBossMusic.class, "SENT")).isEmpty(), "stopped native server releases encounter and receipt history");
                streams.clear();
                JsonObject result = new JsonObject(); result.addProperty("status", "PASS"); result.addProperty("assertions", assertions); result.add("native_audio", audio);
                result.addProperty("scope", "Actual integrated Minecraft/native editor Save, real network+receipt path, native JOrbis/OpenAL streaming. Null device is not speaker/human listening acceptance; no full-song loop-cycle or Windows GPU claim.");
                Files.writeString(Path.of("shared-music-native-result.json"), result + "\n", StandardOpenOption.CREATE_NEW);
            });
        } catch (Exception failure) { throw new AssertionError("Shared music native acceptance failed", failure); }
        finally { context.runOnClient(client -> clockwork.bosses.client.ClockworkBossMusicReceiptPause.release()); }
    }

    private static void scrollToFile(Minecraft client) throws Exception {
        var screen = screen(client);
        for (int scroll = 0; scroll <= (Integer) field(screen, "fullHeight") + 100; scroll += 50) {
            set(screen, "scroll", scroll); call(screen, "rebuild", new Class<?>[0]);
            if (screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                    .anyMatch(b -> b.getMessage().getString().toLowerCase(Locale.ROOT).contains(".ogg"))) return;
        }
        throw new AssertionError("Native library file selection not visible");
    }
    @SuppressWarnings("unchecked") private static net.minecraft.client.gui.components.EditBox input(Minecraft client, String path) throws Exception {
        var screen = screen(client);
        for (int scroll = 0; scroll <= (Integer) field(screen, "fullHeight") + 100; scroll += 50) {
            set(screen, "scroll", scroll); call(screen, "rebuild", new Class<?>[0]);
            var paths = (Map<net.minecraft.client.gui.components.EditBox, String>) field(screen, "editPaths");
            for (var entry : paths.entrySet()) if (entry.getValue().equals(path)) return entry.getKey();
        }
        throw new AssertionError("Native edit responder missing " + path);
    }
    private static boolean playing(String prefix) {
        Object playback = field(ClockworkBossMusicClient.class, "active"); if (playback == null) return false;
        var control = (ClockworkBossMusicControl) field(field(playback, "request"), "control");
        Object stream = field(playback, "stream");
        synchronized (stream) { return control.key().startsWith(prefix) && (Boolean) field(stream, "readAudio"); }
    }
    private static boolean streamsClosed() {
        for (Object stream : streams) synchronized (stream) { if (!(Boolean) field(stream, "closed")) return false; }
        return true;
    }
    private static void recordAudio(String name, String prefix) {
        check(playing(prefix), "real native audio remains active"); Object playback = field(ClockworkBossMusicClient.class, "active"); Object stream = field(playback, "stream");
        var control = (ClockworkBossMusicControl) field(field(playback, "request"), "control");
        synchronized (stream) {
            streams.add(stream);
            Object decoder = field(stream, "delegate"); long decoded = (Long) field(decoder, "decodedBytes");
            var format = ((net.minecraft.client.sounds.AudioStream) stream).getFormat();
            check(decoded > 0 && format.getChannels() == 2 && format.getSampleRate() == 44100, "actual supplied PCM decoded in native streaming pool");
            check(control.key().equals(prefix), "native streamed content hash matches the exact supplied track, including same-name legacy source");
            check(control.channel() == SoundSource.RECORDS && control.volume() == .65F && !control.loop(), "actual per-template channel/volume/loop delivered");
            JsonObject record = new JsonObject(); record.addProperty("template", name); record.addProperty("key", control.key());
            record.addProperty("decoded_bytes", decoded); record.addProperty("channels", format.getChannels()); record.addProperty("sample_rate", format.getSampleRate());
            record.addProperty("native_stream", decoder.getClass().getName()); record.addProperty("channel", control.channel().getName()); audio.add(record);
        }
    }
    private static void prepare(String name, String source, String file, boolean enabled) throws Exception {
        JsonObject document = ClockworkBossTemplateDocument.defaults(); JsonObject music = document.getAsJsonObject("music");
        music.addProperty("source", source); music.addProperty("file", file); music.addProperty("enabled", enabled);
        music.addProperty("volume", .65); music.addProperty("loop", false); music.addProperty("fade_out_seconds", 0); music.addProperty("channel", "record");
        document.getAsJsonObject("boss_bar").getAsJsonObject("player_health").addProperty("enabled", false);
        document.getAsJsonObject("boss_bar").getAsJsonObject("reusable_potion").addProperty("enabled", false);
        JsonObject arena = document.getAsJsonObject("boss_bar").getAsJsonObject("visibility_area");
        arena.addProperty("shape", "radius"); arena.addProperty("radius", 32); arena.addProperty("dimension", "");
        JsonObject addon = new JsonObject(); addon.add("extreme", JsonParser.parseString("1e-2000000000")); document.add("addon", addon);
        JsonObject unknown = new JsonObject(); unknown.addProperty("addon_field", "untouched"); document.add("_music_library", unknown);
        Path path = ClockworkBossTemplateStore.pathFor(name); Files.createDirectories(path.getParent());
        Files.writeString(path, ClockworkBossTemplateFormat.writeJson(document), StandardOpenOption.CREATE_NEW);
        if (source.equals("template")) {
            Path sounds = path.getParent().resolve("sounds"); Files.createDirectories(sounds);
            try (var input = ClockworkBossSharedMusicClientTest.class.getResourceAsStream("/music/boss2.ogg")) { Files.write(sounds.resolve(file), input.readAllBytes(), StandardOpenOption.CREATE_NEW); }
        }
        ClockworkBossTemplateStore.refreshCache();
    }
    private static void provenance() throws Exception {
        JsonObject result = new JsonObject(); result.addProperty("mod_version", net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("clockwork_bosses").orElseThrow().getMetadata().getVersion().getFriendlyString());
        result.addProperty("fabric_api", net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("fabric-api").orElseThrow().getMetadata().getVersion().getFriendlyString());
        for (Class<?> type : List.of(ClockworkBossMusic.class, ClockworkBossLibraryMusicData.class, ClockworkBossTemplateScreen.class, ClockworkBossMusicClient.class, clockwork.web.SharedMusicLibrary.class)) {
            JsonObject item = new JsonObject(); item.addProperty("code_source", type.getProtectionDomain().getCodeSource().getLocation().toString());
            try (var input = type.getResourceAsStream('/' + type.getName().replace('.', '/') + ".class")) { item.addProperty("raw_class_sha256", HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()))); }
            result.add(type.getName(), item);
        }
        Files.writeString(Path.of("shared-music-native-provenance.json"), result + "\n", StandardOpenOption.CREATE_NEW);
    }
}
