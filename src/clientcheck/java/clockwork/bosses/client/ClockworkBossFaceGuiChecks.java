package clockwork.bosses.client;

import clockwork.bosses.ClockworkBossBrows;
import clockwork.bosses.ClockworkBossEditorPayload;
import clockwork.bosses.ClockworkBossEditorSchema;
import clockwork.bosses.ClockworkBossTemplateDocument;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;

/** Test-only access to the real editor at representative GUI sizes. No public
 * production test hooks, external server, authentication or saved template. */
public final class ClockworkBossFaceGuiChecks {
    private static int checks;
    public static int run(Minecraft client) throws Exception {
        checks = 0;
        for (int[] size : new int[][]{{320,240},{640,360},{1280,720}}) {
            var document = ClockworkBossEditorSchema.editable(ClockworkBossTemplateDocument.defaults());
            document.getAsJsonObject("skin").addProperty("type","default");
            var brows = document.getAsJsonObject("skin").getAsJsonObject("glowing_eyebrows");
            brows.addProperty("expression","neutral");
            brows.getAsJsonObject("events").getAsJsonObject("hurt").addProperty("expression","sad");
            var snapshot = document.deepCopy();
            var screen = new ClockworkBossTemplateScreen(new ClockworkBossEditorPayload(UUID.randomUUID(),"document",false,"face_test","revision",""),document);
            screen.init(size[0],size[1]); call(screen,"selectPage",new Class<?>[]{String.class},"__preview");
            check(collapsed(screen).contains("skin.glowing_eyebrows.events"),"event settings start folded");
            collapsed(screen).remove("skin.glowing_eyebrows"); collapsed(screen).remove("skin.glowing_eyebrows.events");
            collapsed(screen).remove("skin.glowing_eyebrows.events.hurt"); call(screen,"rebuild",new Class<?>[0]);
            check(value(screen,"rows").toString().contains("skin.glowing_eyebrows.events.hurt.left_rotation"),"nested nullable angle exposed");
            choose(screen,"__face_event","hurt");
            check(pose(screen).expression()==ClockworkBossBrows.Expression.NEUTRAL,"disabled event preview remains base");
            inputs(screen).put("skin.glowing_eyebrows.events.enabled","true");
            inputs(screen).put("skin.glowing_eyebrows.left_rotation","15");
            inputs(screen).put("skin.glowing_eyebrows.right_rotation","-10");
            inputs(screen).put("skin.glowing_eyebrows.events.hurt.left_rotation","<inherit>");
            check(pose(screen).expression()==ClockworkBossBrows.Expression.SAD && pose(screen).leftRotation()==15
                    && pose(screen).rightRotation()==-10,"rule preview inherits live draft base angles");
            inputs(screen).put("skin.glowing_eyebrows.events.hurt.expression","<inherit>");
            check(pose(screen).expression()==ClockworkBossBrows.Expression.NEUTRAL,"pending null expression means base, not angry");
            inputs(screen).put("skin.glowing_eyebrows.events.hurt.expression","random");
            for (String shape : new String[]{"angry","neutral","curious","sad"}) inputs(screen).put("skin.glowing_eyebrows.events.random_pool."+shape,"false");
            inputs(screen).put("skin.glowing_eyebrows.events.hurt.right_rotation","45");
            choose(screen,"__face_sample","sad");
            check(pose(screen).expression()==ClockworkBossBrows.Expression.NEUTRAL && pose(screen).rightRotation()==45,"empty-pool preview retains explicit angle");
            call(screen,"rotateBrows",new Class<?>[]{int.class},5);
            check(inputs(screen).get("skin.glowing_eyebrows.left_rotation").equals("20")
                    && inputs(screen).get("skin.glowing_eyebrows.right_rotation").equals("-5"),"Both rotation shortcut preserves independent angles");
            check(document.equals(snapshot),"view selectors and pending controls do not mutate saved input");
            check(inputs(screen).keySet().stream().noneMatch(path->path.startsWith("__face")),"view selectors are not save fields");
            for (var child : screen.children()) if (child instanceof AbstractWidget widget)
                check(widget.getX()>=0 && widget.getY()>=0 && widget.getRight()<=size[0] && widget.getBottom()<=size[1],"face widgets fit GUI size");
            screen.removed();
        }
        return checks;
    }
    public static void preview(Minecraft client) {
        try {
            var document=ClockworkBossEditorSchema.editable(ClockworkBossTemplateDocument.defaults());
            document.getAsJsonObject("skin").addProperty("type","default");
            var events=document.getAsJsonObject("skin").getAsJsonObject("glowing_eyebrows").getAsJsonObject("events");
            events.addProperty("enabled",true); events.getAsJsonObject("hurt").addProperty("expression","sad");
            var screen=new ClockworkBossTemplateScreen(new ClockworkBossEditorPayload(UUID.randomUUID(),"document",false,"face_test","revision",""),document);
            client.gui.setScreen(screen); call(screen,"selectPage",new Class<?>[]{String.class},"__preview");
            set(screen,"headPreview",true); choose(screen,"__face_event","hurt");
            collapsed(screen).remove("skin.glowing_eyebrows"); call(screen,"rebuild",new Class<?>[0]);
        } catch (Exception failure) { throw new AssertionError("Face GUI preview failed",failure); }
    }
    public static void assertRendered(Minecraft client) throws Exception {
        check(client.gui.screen() instanceof ClockworkBossTemplateScreen,"real native face screen visible");
        var screen=(ClockworkBossTemplateScreen)client.gui.screen();
        check(value(screen,"previewEntity")!=null && !((Boolean)value(screen,"previewFailed")),"detached Warden renders without preview failure");
    }
    private static ClockworkBossBrows pose(Object screen) throws Exception { return (ClockworkBossBrows)call(screen,"previewBrows",new Class<?>[0]); }
    private static void choose(Object screen,String path,String value) throws Exception { call(screen,"choose",new Class<?>[]{String.class,String.class},path,value); }
    @SuppressWarnings("unchecked") private static Map<String,String> inputs(Object screen) throws Exception { return (Map<String,String>)value(screen,"inputs"); }
    @SuppressWarnings("unchecked") private static Set<String> collapsed(Object screen) throws Exception { return (Set<String>)value(screen,"collapsed"); }
    private static Object value(Object screen,String name) throws Exception { var field=ClockworkBossTemplateScreen.class.getDeclaredField(name); field.setAccessible(true); return field.get(screen); }
    private static void set(Object screen,String name,Object value) throws Exception { var field=ClockworkBossTemplateScreen.class.getDeclaredField(name); field.setAccessible(true); field.set(screen,value); }
    private static Object call(Object screen,String name,Class<?>[] types,Object...args) throws Exception { var method=ClockworkBossTemplateScreen.class.getDeclaredMethod(name,types); method.setAccessible(true); return method.invoke(screen,args); }
    private static void check(boolean value,String message) { checks++; if(!value)throw new AssertionError(message); }
}
