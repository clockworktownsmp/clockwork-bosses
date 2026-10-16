package clockwork.bosses.client;

import clockwork.bosses.ClockworkBossEditorPayload;
import clockwork.bosses.ClockworkBossEditorSchema;
import clockwork.bosses.ClockworkBossTemplateDocument;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonInfo;

/** Existing native editor, actual EditBox responders/dropdown keys/Save button.
 * Only the fixture token's outgoing payload is captured; no server save claimed. */
public final class ClockworkBossBurrowGuiChecks {
    private static UUID token;
    private static ClockworkBossEditorPayload packet;
    private static int checks;
    private static String path;
    private ClockworkBossBurrowGuiChecks() {}
    public static boolean capture(ClockworkBossEditorPayload payload) {
        if (token == null || !token.equals(payload.token())) return false;
        if (payload.action().equals("save")) packet = payload;
        return true;
    }
    public static int run(Minecraft client) throws Exception {
        checks = 0;
        try {
            for (int[] size : new int[][]{{320,240}, {1280,720}}) for (boolean explicitNull : new boolean[]{false,true}) {
                var stored = ClockworkBossTemplateDocument.defaults();
                String phase = stored.getAsJsonObject("phases").keySet().iterator().next();
                path = "phases." + phase + ".subphases.dig.action_settings.";
                var child = JsonParser.parseString("{\"type\":\"clockwork_bosses:burrow\",\"action_settings\":{\"underground_ticks\":5,\"depth\":5,\"future_opaque\":123456789012345678901234567890.123456789}} ").getAsJsonObject();
                if (explicitNull) for (String key : new String[]{"emerge_damage","emerge_radius","emerge_particles"})
                    child.getAsJsonObject("action_settings").add(key, com.google.gson.JsonNull.INSTANCE);
                var sequence = new JsonObject(); sequence.add("dig", child); stored.getAsJsonObject("phases").getAsJsonObject(phase).add("subphases", sequence);
                var snapshot = stored.deepCopy();
                var screen = open(stored, size);
                number(screen, "emerge_damage").setValue("2.5");
                number(screen, "emerge_radius").setValue("1.25");
                toggle(screen, false);
                press(screen, "Save");
                check(packet != null, "native Save produced a packet");
                var patch = JsonParser.parseString(packet.json()).getAsJsonObject();
                check(patch.get(path+"emerge_damage").getAsDouble()==2.5 && patch.get(path+"emerge_radius").getAsDouble()==1.25
                        && !patch.get(path+"emerge_particles").getAsBoolean(), "fractional values and false survive actual Save");
                check(patch.size()==3, "Save preserves omitted/null and opaque fields through a minimal patch");
                check(stored.equals(snapshot), "source document unchanged by editing");
                var reopened = stored.deepCopy(); patch.entrySet().forEach(entry -> ClockworkBossEditorSchema.put(reopened, entry.getKey(), entry.getValue()));
                screen.removed(); screen = open(reopened, size);
                check(number(screen,"emerge_damage").getValue().equals("2.5") && number(screen,"emerge_radius").getValue().equals("1.25"), "fractional values reopen");
                number(screen,"emerge_damage").setValue("0"); number(screen,"emerge_radius").setValue("0");
                press(screen,"Save"); patch=JsonParser.parseString(packet.json()).getAsJsonObject();
                check(patch.size()==2 && patch.get(path+"emerge_damage").getAsDouble()==0 && patch.get(path+"emerge_radius").getAsDouble()==0,
                        "zero controls stay editable with particles false");
                check(ClockworkBossEditorSchema.get(reopened,path+"future_opaque").toString().equals("123456789012345678901234567890.123456789"), "opaque decimal survives reopen");
                screen.removed();
            }
            return checks;
        } finally { token=null; packet=null; }
    }
    private static ClockworkBossTemplateScreen open(JsonObject stored, int[] size) throws Exception {
        token=UUID.randomUUID(); packet=null;
        var screen = new ClockworkBossTemplateScreen(new ClockworkBossEditorPayload(token,"document",false,"burrow_fixture","revision",""),ClockworkBossEditorSchema.editable(stored));
        screen.init(size[0],size[1]);
        invoke(screen,"selectPage",new Class<?>[]{String.class},path.substring(0,path.indexOf(".action_settings.")));
        ((java.util.Set<?>)value(screen,"collapsed")).clear(); invoke(screen,"rebuild",new Class<?>[0]);
        check(screen.children().stream().filter(AbstractWidget.class::isInstance).map(AbstractWidget.class::cast)
                .allMatch(widget->widget.getX()>=0&&widget.getY()>=0&&widget.getRight()<=size[0]&&widget.getBottom()<=size[1]), "native widgets fit GUI size "+size[0]);
        return screen;
    }
    @SuppressWarnings("unchecked") private static EditBox number(ClockworkBossTemplateScreen screen,String key) throws Exception {
        for(int scroll=0;scroll<=((Integer)value(screen,"fullHeight"))+48;scroll+=16) {
            set(screen,"scroll",scroll); invoke(screen,"rebuild",new Class<?>[0]);
            var paths=(Map<EditBox,String>)value(screen,"editPaths");
            for(var entry:paths.entrySet()) if(entry.getValue().equals(path+key)) return entry.getKey();
        }
        throw new AssertionError("Visible native numeric control missing: "+key);
    }
    private static void toggle(ClockworkBossTemplateScreen screen,boolean wanted) throws Exception {
        for(int scroll=0;scroll<=((Integer)value(screen,"fullHeight"))+48;scroll+=16) {
            set(screen,"scroll",scroll); invoke(screen,"rebuild",new Class<?>[0]);
            var choices=screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                    .filter(button->button.getMessage().getString().endsWith(" ▾")).toList();
            for(var button:choices) {
                button.onPress(new MouseButtonInfo(0,0)); var dropdown=value(screen,"dropdown");
                if(dropdown!=null) {
                    var field=dropdown.getClass().getDeclaredField("path"); field.setAccessible(true);
                    if(field.get(dropdown).equals(path+"emerge_particles")) {
                        var all=dropdown.getClass().getDeclaredField("choices"); all.setAccessible(true);
                        var cursor=dropdown.getClass().getDeclaredField("cursor"); cursor.setAccessible(true);
                        var options=(java.util.List<?>)all.get(dropdown); int target=options.indexOf(Boolean.toString(wanted));
                        check(target>=0,"native dropdown exposes false/true");
                        for (int step=0;step<=options.size();step++) {
                            dropdown=value(screen,"dropdown");
                            int current=cursor.getInt(dropdown);
                            if(current==target) break;
                            screen.keyPressed(new KeyEvent(current<target ? com.mojang.blaze3d.platform.InputConstants.KEY_DOWN
                                    : com.mojang.blaze3d.platform.InputConstants.KEY_UP,0,0));
                        }
                        check(cursor.getInt(value(screen,"dropdown"))==target,"native dropdown reached requested option within bounded key steps");
                        screen.keyPressed(new KeyEvent(com.mojang.blaze3d.platform.InputConstants.KEY_RETURN,0,0)); return;
                    }
                    screen.keyPressed(new KeyEvent(com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE,0,0));
                }
            }
        }
        throw new AssertionError("Visible native ground-burst dropdown missing");
    }
    private static void press(ClockworkBossTemplateScreen screen,String label) {
        var button=screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast).filter(b->b.getMessage().getString().equals(label)).findFirst().orElseThrow();
        check(button.active,"native "+label+" enabled"); button.onPress(new MouseButtonInfo(0,0));
    }
    private static Object value(Object screen,String name) throws Exception {var f=ClockworkBossTemplateScreen.class.getDeclaredField(name);f.setAccessible(true);return f.get(screen);}
    private static void set(Object screen,String name,Object next) throws Exception {var f=ClockworkBossTemplateScreen.class.getDeclaredField(name);f.setAccessible(true);f.set(screen,next);}
    private static Object invoke(Object screen,String name,Class<?>[] types,Object...args) throws Exception {var m=ClockworkBossTemplateScreen.class.getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(screen,args);}
    private static void check(boolean condition,String message) {checks++;if(!condition)throw new AssertionError(message);}
}
