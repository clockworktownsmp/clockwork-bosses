package clockwork.bosses.client;

/** Client-side access to Clockwork Boss visual data stored on a Warden render state. */
public interface WardenRenderStateVisuals {
    long clockworkBosses$getMeleeMillis();
    void clockworkBosses$setMeleeMillis(long millis);
    int clockworkBosses$getEyes();
    void clockworkBosses$setEyes(int coordinates);
    int clockworkBosses$getBrows();
    void clockworkBosses$setBrows(int coordinates);
    /** A detached editor snapshot may show steady bright face glow; live entities never set this flag. */
    boolean clockworkBosses$isEditorPreview();
    void clockworkBosses$setEditorPreview(boolean preview);
    /** Native keyframe time in milliseconds, or -1 when cancelled/idle/finished. */
    long clockworkBosses$getSonicMillis();
    void clockworkBosses$setSonicMillis(long millis);
    /** Only a server-marked boss opts into our texture and glow changes. */
    boolean clockworkBosses$isClockworkBoss();
    void clockworkBosses$setClockworkBoss(boolean clockworkBoss);

    /** Opaque phase RGB; the native layer supplies its own pulsing alpha. */
    int clockworkBosses$getEffectColor();
    void clockworkBosses$setEffectColor(int color);

    /** Client-local entity age at observed transition, or MIN_VALUE for no flash. */
    int clockworkBosses$getPhaseChangeTick();
    void clockworkBosses$setPhaseChangeTick(int tick);

    /** Validated server metadata chooses a source; it never supplies a client filesystem path. */
    String clockworkBosses$getSkinPlayerName();
    void clockworkBosses$setSkinPlayerName(String playerName);

    String clockworkBosses$getSkinType();
    void clockworkBosses$setSkinType(String skinType);

    /** Template identity isolates custom skin files with identical names. */
    String clockworkBosses$getTemplate();
    void clockworkBosses$setTemplate(String templateName);

    String clockworkBosses$getCustomSkinFile();
    void clockworkBosses$setCustomSkinFile(String fileName);
}
