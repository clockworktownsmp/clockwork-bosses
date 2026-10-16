package clockwork.bosses;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Arrays;
import java.util.HashSet;
import net.minecraft.util.RandomSource;

/** Pure pose/config regressions. Native hooks/rendering have separate game and
 * client fixtures; passing this suite alone is not proof of an in-world face. */
final class ClockworkBossFaceChecks {
    private static int assertions;
    static int run() throws Exception {
        assertions = 0;
        packingAndPixels(); settingsAndSchema(); priorityAndCleanup();
        return assertions;
    }
    private static void packingAndPixels() {
        var old = new ClockworkBossBrows(2, 2, 10, 2);
        check(old.packed() == (2 | 2 << 4 | 10 << 8 | 2 << 12), "unchanged legacy integer");
        check(Arrays.equals(old.facePixels(false), new int[]{34,35,50,51,68,69,84,85}), "exact original left angry pixels");
        check(Arrays.equals(old.facePixels(true), new int[]{44,45,60,61,74,75,90,91}), "exact original right angry pixels");
        for (var shape : ClockworkBossBrows.Expression.values()) {
            for (int left = -45; left <= 45; left++) for (int right = -45; right <= 45; right++) {
                var pose = new ClockworkBossBrows(2, 2, 10, 2, shape, left, right);
                check(ClockworkBossBrows.unpack(pose.packed()).equals(pose), "all shape/angle pairs round trip, including signed values");
                check((pose.packed() & 65535) == old.packed(), "upper bits never change legacy anchors");
            }
            for (int anchor : new int[]{0, 2, 12}) for (int rotation : new int[]{-45,-30,-1,0,1,30,45}) {
                var pose = new ClockworkBossBrows(anchor, anchor, anchor, anchor, shape, rotation, rotation);
                for (boolean right : new boolean[]{false, true}) {
                    var seen = new HashSet<Integer>();
                    int[] pixels = pose.facePixels(right);
                    check(pixels.length <= 36, "bounded rotated mask");
                    for (int pixel : pixels) {
                        check(pixel >= 0 && pixel < 256, "every pixel inside 16x16 face");
                        check(seen.add(pixel), "inverse sampling has no duplicate writes");
                    }
                }
            }
        }
        var config = JsonParser.parseString("{\"skin\":{\"glowing_eyebrows\":{\"left_x\":3,\"right_x\":9}}}").getAsJsonObject();
        check(ClockworkBossBrows.read(config).equals(new ClockworkBossBrows(3,2,9,2)), "old template anchors and exact default shape");
        check(!ClockworkBossFaceEvents.Settings.read(config).enabled(), "omitted events do not opt in");
    }
    private static JsonObject config() {
        var config = ClockworkBossTemplateDocument.defaults();
        var brows = config.getAsJsonObject("skin").getAsJsonObject("glowing_eyebrows");
        brows.addProperty("expression", "neutral"); brows.addProperty("left_rotation", 15); brows.addProperty("right_rotation", -10);
        var events = brows.getAsJsonObject("events"); events.addProperty("enabled", true);
        events.getAsJsonObject("melee").addProperty("expression", "angry");
        events.getAsJsonObject("sonic_boom").addProperty("expression", "curious");
        events.getAsJsonObject("hurt").addProperty("expression", "sad");
        events.getAsJsonObject("target_change").addProperty("expression", "curious");
        return config;
    }
    private static void settingsAndSchema() throws Exception {
        var config = config(); var base = ClockworkBossBrows.read(config);
        var settings = ClockworkBossFaceEvents.Settings.read(config);
        var random = RandomSource.create(1);
        check(settings.melee().pose(settings, random).equals(new ClockworkBossBrows(2,2,10,2,ClockworkBossBrows.Expression.ANGRY,15,-10)), "null rule angles inherit base");
        var events = config.getAsJsonObject("skin").getAsJsonObject("glowing_eyebrows").getAsJsonObject("events");
        events.getAsJsonObject("melee").addProperty("left_rotation", -45);
        events.getAsJsonObject("melee").addProperty("expression", "inherit");
        settings = ClockworkBossFaceEvents.Settings.read(config);
        check(settings.melee().pose(settings, random).equals(new ClockworkBossBrows(2,2,10,2,base.expression(),-45,-10)), "inherit means base shape with independent angle override");
        var pool = events.getAsJsonObject("random_pool"); pool.keySet().forEach(key -> pool.addProperty(key, false));
        events.getAsJsonObject("melee").addProperty("expression", "random"); settings = ClockworkBossFaceEvents.Settings.read(config);
        check(settings.melee().pose(settings, random).expression() == base.expression() && settings.melee().pose(settings, random).leftRotation() == -45, "empty pool falls back to base shape and retains angle override");
        pool.addProperty("sad", true); settings = ClockworkBossFaceEvents.Settings.read(config);
        for (int i = 0; i < 30; i++) check(settings.melee().pose(settings, random).expression() == ClockworkBossBrows.Expression.SAD, "one-item pool is deterministic");
        var nullable = events.getAsJsonObject("melee"); nullable.add("expression", JsonNull.INSTANCE); nullable.add("left_rotation", JsonNull.INSTANCE);
        events.add("hit_duration_ticks", JsonNull.INSTANCE); events.add("enabled", JsonNull.INSTANCE);
        settings = ClockworkBossFaceEvents.Settings.read(config);
        check(!settings.enabled() && settings.hitDuration() == 20 && settings.melee().pose(settings, random).equals(base), "null fields use exact runtime fallbacks");
        String root = "skin.glowing_eyebrows.events.";
        var angle = ClockworkBossEditorSchema.field(root + "melee.left_rotation", JsonNull.INSTANCE);
        check(angle.integer() && angle.nullable() && angle.min() == -45 && angle.max() == 45, "shared rule angle metadata");
        for (String path : new String[]{root + "hit_duration_ticks",root + "target_duration_ticks",root + "hit_cooldown_ticks",root + "target_cooldown_ticks"}) {
            var field = ClockworkBossEditorSchema.field(path, JsonNull.INSTANCE);
            check(field.integer() && field.max() == 12000 && field.min() == (path.endsWith("cooldown_ticks") ? 0 : 1), "duration/cooldown bounds do not inherit coordinate limits");
        }
        for (String value : new String[]{"-46","46","0.5","1e1000000000","NaN"}) rejects(() -> ClockworkBossEditorSchema.parse(root + "melee.left_rotation", value, JsonNull.INSTANCE), "invalid rule angle rejected");
        check(ClockworkBossEditorSchema.parse(root + "melee.left_rotation", "<inherit>", JsonNull.INSTANCE).isJsonNull(), "native editor preserves null");
        var schema = ClockworkBossTemplateSchema.create().getAsJsonObject("properties").getAsJsonObject("skin").getAsJsonObject("properties")
                .getAsJsonObject("glowing_eyebrows").getAsJsonObject("properties").getAsJsonObject("events").getAsJsonObject("properties");
        var exportedAngle = schema.getAsJsonObject("melee").getAsJsonObject("properties").getAsJsonObject("left_rotation");
        check(exportedAngle.get("type").toString().contains("null") && exportedAngle.getAsJsonObject("x-clockwork").get("inherits_from").getAsString().equals("skin.glowing_eyebrows.left_rotation"), "web schema matches null inheritance");
        check(schema.getAsJsonObject("enabled").get("type").toString().contains("null"), "all accepted event scalar nulls export correctly");
        var precise = JsonParser.parseString("{\"huge\":900719925474099312345,\"nested\":{\"future\":true}}");
        events.add("unknown_addon", precise);
        var saved = ClockworkBossTemplateFormat.read(new java.io.StringReader(ClockworkBossTemplateFormat.writeJson(config)));
        check(saved.getAsJsonObject("skin").getAsJsonObject("glowing_eyebrows").getAsJsonObject("events").get("unknown_addon").equals(precise), "unknown event fields survive save/read");
    }
    private static void priorityAndCleanup() {
        var config = config(); var face = new ClockworkBossFaceEvents(); var random = RandomSource.create(2);
        face.configure(ClockworkBossFaceEvents.Settings.read(config));
        var base = ClockworkBossBrows.read(config);
        face.targetChanged(true, 100, random); check(shape(face,100) == ClockworkBossBrows.Expression.CURIOUS, "target overlay");
        long melee = face.begin(ClockworkBossFaceEvents.Attack.MELEE, 101, 100, random);
        check(shape(face,101) == ClockworkBossBrows.Expression.ANGRY, "attack overrides target");
        face.hit(102, random); check(shape(face,102) == ClockworkBossBrows.Expression.SAD, "hit overrides attack");
        face.hit(115, random); check(shape(face,122) == ClockworkBossBrows.Expression.ANGRY, "cooldown does not extend hit; returns to still-active attack");
        face.hit(123, random); face.cancel(melee); check(shape(face,124) == ClockworkBossBrows.Expression.SAD, "cancelled attack does not cancel active hit");
        check(face.packed(143) == base.packed(), "hit returns to base rather than cancelled attack");
        long old = face.begin(ClockworkBossFaceEvents.Attack.MELEE, 200, 100, random);
        long sonic = face.begin(ClockworkBossFaceEvents.Attack.SONIC_BOOM, 201, 100, random);
        face.cancel(old); face.finish(old,202,1); check(shape(face,212) == ClockworkBossBrows.Expression.CURIOUS, "old callback cannot clear or shorten a new attack");
        face.finish(sonic,213,20); check(shape(face,232) == ClockworkBossBrows.Expression.CURIOUS && face.packed(233) == base.packed(), "exact attack recovery deadline");
        long stale = face.begin(ClockworkBossFaceEvents.Attack.MELEE, 300, 100, random);
        face.configure(ClockworkBossFaceEvents.Settings.read(config));
        long fresh = face.begin(ClockworkBossFaceEvents.Attack.SONIC_BOOM,301,100,random);
        face.cancel(stale); check(shape(face,302) == ClockworkBossBrows.Expression.CURIOUS, "reload rejects old tokens");
        var other = new ClockworkBossFaceEvents(); other.configure(ClockworkBossFaceEvents.Settings.read(config));
        long replacement = other.begin(ClockworkBossFaceEvents.Attack.SONIC_BOOM,301,100,random);
        other.cancel(fresh); check(replacement != fresh && shape(other,302) == ClockworkBossBrows.Expression.CURIOUS, "replacement controller rejects old tokens");
        face.clear(); check(face.packed(303) == base.packed(), "cleanup clears all temporary poses");
        var events = config.getAsJsonObject("skin").getAsJsonObject("glowing_eyebrows").getAsJsonObject("events");
        events.addProperty("random_between_attacks",true); events.getAsJsonObject("random_pool").keySet().forEach(key -> events.getAsJsonObject("random_pool").addProperty(key,key.equals("sad")));
        face.configure(ClockworkBossFaceEvents.Settings.read(config)); face.afterAttack(random);
        check(shape(face,400) == ClockworkBossBrows.Expression.SAD && shape(face,40000) == ClockworkBossBrows.Expression.SAD, "rest changes once and stays without a random timer");
        events.addProperty("enabled",false); face.configure(ClockworkBossFaceEvents.Settings.read(config));
        check(face.begin(ClockworkBossFaceEvents.Attack.MELEE,500,100,random) == 0, "disabled rules issue no attack token");
        face.hit(500,random); face.targetChanged(true,500,random); face.afterAttack(random);
        check(face.packed(501) == base.packed(), "disabled events keep static appearance");
    }
    private static ClockworkBossBrows.Expression shape(ClockworkBossFaceEvents face,long now) { return ClockworkBossBrows.unpack(face.packed(now)).expression(); }
    private static void check(boolean value,String message) { assertions++; if (!value) throw new AssertionError(message); }
    private static void rejects(Runnable action,String message) { try { action.run(); } catch (IllegalArgumentException expected) { assertions++; return; } throw new AssertionError(message); }
}
