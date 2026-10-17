package clockwork.bosses;

import com.google.gson.*;
import java.util.*;
import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryFriendlyByteBuf;

/** Source selection and the actual production codecs, including a real
 * template named library. Storage/HTTP/real supplied tracks have separate tests. */
final class ClockworkBossSharedMusicChecks {
    private static int assertions;
    private static void check(boolean value, String why) { assertions++; if (!value) throw new AssertionError(why); }
    static int run() {
        for (String source : List.of("", ",\"source\":null", ",\"source\":\"template\"", ",\"source\":\"library\"")) {
            JsonObject document = JsonParser.parseString("{\"music\":{\"file\":\"boss1.ogg\",\"volume\":1.25,\"loop\":false,\"fade_out_seconds\":3,\"channel\":\"record\"" + source + "}}").getAsJsonObject();
            var music = ClockworkBossMusic.Settings.read(document);
            check(music.source().equals(source.contains("library") ? "library" : "template"), "explicit source and legacy omission/null");
            check(music.volume() == 1.25 && !music.loop() && music.fadeSeconds() == 3 && music.channel() == net.minecraft.sounds.SoundSource.RECORDS, "source does not change playback settings");
            var editable = ClockworkBossEditorSchema.editable(document);
            check(ClockworkBossEditorSchema.get(editable, "music.source").getAsString().equals(music.source()), "editor virtual source matches runtime");
        }
        for (String source : List.of("\"other\"", "true", "42", "{}")) {
            try { ClockworkBossMusic.Settings.read(JsonParser.parseString("{\"music\":{\"source\":" + source + "}}").getAsJsonObject()); throw new AssertionError("invalid source accepted"); }
            catch (IllegalArgumentException expected) { assertions++; }
        }
        String digest = "a".repeat(64);
        var legacy = new ClockworkBossMusicData("library", "boss1.ogg", digest, new byte[]{1, 2});
        var shared = new ClockworkBossLibraryMusicData("boss1.ogg", digest, new byte[]{1, 2});
        check(!legacy.key().equals(shared.key()), "real template named library cannot collide with shared identity");
        check(ClockworkBossMusicData.isValidKey(legacy.key()) && ClockworkBossMusicData.isValidKey(shared.key()), "control accepts both explicit key forms");
        check(legacy.type().id().getPath().equals("boss_music_data_v1"), "legacy template channel unchanged");
        check(shared.type().id().getPath().equals("library_music_data_v1"), "library is independently negotiated");
        for (String key : List.of("library:boss1.ogg:" + digest, "library:::boss1.ogg:" + digest, "library::../boss1.ogg:" + digest,
                "library::BOSS.ogg:" + digest, "library::boss1.ogg:bad", "library::boss1.ogg:" + digest + ":extra", "x".repeat(217)))
            check(!ClockworkBossLibraryMusicData.isValidKey(key), "library key independently validates part count/name/digest/length");
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), net.minecraft.core.RegistryAccess.EMPTY);
        try {
            ClockworkBossLibraryMusicData.CODEC.encode(buffer, shared); var decoded = ClockworkBossLibraryMusicData.CODEC.decode(buffer);
            check(decoded.key().equals(shared.key()) && Arrays.equals(decoded.bytes(), shared.bytes()) && !buffer.isReadable(), "library codec round trip");
        } finally { buffer.release(); }
        check(preparedKind(shared) == ClockworkBossMediaChunk.LIBRARY_MUSIC, "library uses existing prepared chunk pipeline");
        check(preparedKind(legacy) == ClockworkBossMediaChunk.MUSIC, "legacy prepared kind unchanged");
        var first = new ClockworkBossMusic.Settings(true, "library", "boss1.ogg", 1, true, 2, net.minecraft.sounds.SoundSource.MUSIC);
        check(ClockworkBossMusic.cacheKey("one", first).equals(ClockworkBossMusic.cacheKey("two", first)), "one shared cache/encoding across templates");
        var local = new ClockworkBossMusic.Settings(true, "boss1.ogg", 1, true, 2, net.minecraft.sounds.SoundSource.MUSIC);
        check(!ClockworkBossMusic.cacheKey("one", local).equals(ClockworkBossMusic.cacheKey("two", local)), "template cache remains isolated");
        check(!ClockworkBossMusic.cacheKey("library", local).equals(ClockworkBossMusic.cacheKey("library", first)), "source-aware server cache cannot collide");
        JsonObject opaque = JsonParser.parseString("{\"format_version\":1,\"_music_library\":{\"addon_number\":1e-2000000000},\"addon\":{\"_documentation\":{\"music_library\":42}}}").getAsJsonObject();
        var persisted = ClockworkBossTemplateFormat.forSave(opaque);
        check(persisted.get("_music_library").equals(opaque.get("_music_library")), "native hints do not reserve or discard an older addon root name");
        check(persisted.get("addon").equals(opaque.get("addon")), "reserved transport names remain ordinary unknown nested addon data");
        System.out.println("PASS: " + assertions + " shared-music source/identity/production-codec assertions"); return assertions;
    }
    private static int preparedKind(net.minecraft.network.protocol.common.custom.CustomPacketPayload payload) {
        var prepared = ClockworkBossMediaTransfers.prepare(payload, net.minecraft.core.RegistryAccess.EMPTY);
        try { var kind = prepared.getClass().getDeclaredField("kind"); kind.setAccessible(true); return kind.getInt(prepared); }
        catch (ReflectiveOperationException missing) { throw new AssertionError("Prepared chunk kind unavailable", missing); }
    }
}
