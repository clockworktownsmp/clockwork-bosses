package clockwork.bosses.mixin.client;

import clockwork.bosses.client.WardenRenderStateVisuals;

import net.minecraft.client.renderer.entity.state.WardenRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Adds Clockwork Boss visual state to the client render snapshot. */
@Mixin(WardenRenderState.class)
public abstract class WardenRenderStateMixin implements WardenRenderStateVisuals {
    @Unique private long clockworkBosses$meleeMillis = -1;
    @Override public long clockworkBosses$getMeleeMillis() { return clockworkBosses$meleeMillis; }
    @Override public void clockworkBosses$setMeleeMillis(long millis) { clockworkBosses$meleeMillis = millis; }
    @Unique private int clockworkBosses$brows = clockwork.bosses.ClockworkBossBrows.DEFAULT.packed();
    @Override public int clockworkBosses$getBrows() { return clockworkBosses$brows; }
    @Override public void clockworkBosses$setBrows(int coordinates) { clockworkBosses$brows = coordinates; }
    @Unique private boolean clockworkBosses$editorPreview;
    @Override public boolean clockworkBosses$isEditorPreview() { return clockworkBosses$editorPreview; }
    @Override public void clockworkBosses$setEditorPreview(boolean preview) { clockworkBosses$editorPreview = preview; }
    @Unique private int clockworkBosses$eyes = clockwork.bosses.ClockworkBossEyes.DEFAULT.packed();
    @Override public int clockworkBosses$getEyes() { return clockworkBosses$eyes; }
    @Override public void clockworkBosses$setEyes(int coordinates) { clockworkBosses$eyes = coordinates; }
    @Unique private long clockworkBosses$sonicMillis;
    @Override public long clockworkBosses$getSonicMillis() { return clockworkBosses$sonicMillis; }
    @Override public void clockworkBosses$setSonicMillis(long millis) { clockworkBosses$sonicMillis = millis; }
    // One render snapshot carries primitives/filenames only. The renderer copies
    // every field each extraction, so pooled snapshots retain no entity references.
    @Unique private boolean clockworkBosses$clockworkBoss;
    @Unique private int clockworkBosses$effectColor = 0xFFFFFFFF;
    @Unique private int clockworkBosses$phaseChangeTick = Integer.MIN_VALUE; // Client-local flash origin.
    @Unique private String clockworkBosses$skinPlayerName = "";
    @Unique private String clockworkBosses$skinType = "";
    @Unique private String clockworkBosses$customSkinFile = "";
    @Unique private String clockworkBosses$template = "";

    @Override
    public boolean clockworkBosses$isClockworkBoss() {
        return clockworkBosses$clockworkBoss;
    }

    @Override
    public void clockworkBosses$setClockworkBoss(boolean clockworkBoss) {
        clockworkBosses$clockworkBoss = clockworkBoss;
    }

    @Override
    public int clockworkBosses$getEffectColor() {
        return clockworkBosses$effectColor;
    }

    @Override
    public void clockworkBosses$setEffectColor(int color) {
        clockworkBosses$effectColor = color;
    }

    @Override
    public int clockworkBosses$getPhaseChangeTick() {
        return clockworkBosses$phaseChangeTick;
    }

    @Override
    public void clockworkBosses$setPhaseChangeTick(int tick) {
        clockworkBosses$phaseChangeTick = tick;
    }

    @Override
    public String clockworkBosses$getSkinPlayerName() {
        return clockworkBosses$skinPlayerName;
    }

    @Override
    public void clockworkBosses$setSkinPlayerName(String playerName) {
        clockworkBosses$skinPlayerName = playerName == null ? "" : playerName;
    }

    @Override
    public String clockworkBosses$getSkinType() {
        return clockworkBosses$skinType;
    }

    @Override
    public void clockworkBosses$setSkinType(String skinType) {
        clockworkBosses$skinType = skinType == null ? "" : skinType;
    }

    @Override public String clockworkBosses$getTemplate() { return clockworkBosses$template; }
    @Override public void clockworkBosses$setTemplate(String templateName) {
        clockworkBosses$template = templateName == null ? "" : templateName;
    }

    @Override
    public String clockworkBosses$getCustomSkinFile() {
        return clockworkBosses$customSkinFile;
    }

    @Override
    public void clockworkBosses$setCustomSkinFile(String fileName) {
        clockworkBosses$customSkinFile = fileName == null ? "" : fileName;
    }
}
