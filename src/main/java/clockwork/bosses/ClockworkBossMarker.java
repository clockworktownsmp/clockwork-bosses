package clockwork.bosses;

import net.minecraft.core.BlockPos;

/**
 * Mixin contract for encounter metadata attached to vanilla Warden entities.
 * Persistent identity survives chunk saves; synced visual fields let clients
 * render the same skin, glow color, and phase flash as the server selected.
 */
public interface ClockworkBossMarker {
    /** Internal metadata layout shared by Wardens; tracking uses it for clients without this mod. */
    ClockworkBossClientData clockworkBosses$getClientDataLayout();
    /** Recovery journal for an interrupted core burrow, saved independently of runtime actions. */
    ClockworkBossBurrowCheckpoint clockworkBosses$getBurrowCheckpoint();
    void clockworkBosses$setBurrowCheckpoint(ClockworkBossBurrowCheckpoint checkpoint);
    /** Transient post-burrow visual time; initial activation keeps native playback. */
    void clockworkBosses$beginBurrowEmergence(long now);
    void clockworkBosses$clearBurrowEmergence();
    long clockworkBosses$burrowEmergenceMillis(long now, float partialTick);
    /** Saved, bounded UUID ledger prevents reentry/reconnect/reload full-heal farming. */
    boolean clockworkBosses$claimInitialHeal(java.util.UUID player);
    /** Capture melee wind-up/recovery once per swing, never per render frame. */
    void clockworkBosses$beginMeleeVisual(long now, int chargeTicks, int recoveryTicks);
    void clockworkBosses$finishMeleeVisual(long now);
    void clockworkBosses$clearMeleeVisual();
    long clockworkBosses$meleeVisualMillis(long now, float partial);
    /** Packed eye coordinates are template settings; vanilla Wardens never use the custom face mask. */
    int clockworkBosses$getEyes();
    void clockworkBosses$setEyes(int coordinates);
    int clockworkBosses$getBrows();
    void clockworkBosses$setBrows(int coordinates);
    /** Pupil rectangles are relative to the eyes and synchronized only when settings change. */
    int clockworkBosses$getPupils();
    void clockworkBosses$setPupils(int settings);
    /** Server-owned identity; the synced accessor reads the corresponding client metadata field. */
    boolean clockworkBosses$isClockworkBoss();
    boolean clockworkBosses$isClockworkBossSynced();
    void clockworkBosses$setClockworkBoss(boolean value);

    /** Template-selected multiplier for native Warden sounds, synced for client-local heartbeat playback. */
    float clockworkBosses$getMobSoundVolume();
    void clockworkBosses$setMobSoundVolume(float volume);

    /** One atomic timing snapshot syncs charge/recoil for normal, late-tracking and slow clients. */
    String clockworkBosses$getSonicVisual();
    default void clockworkBosses$beginSonicVisual(long now, int duration) { clockworkBosses$beginSonicVisual(now, duration, 26); }
    void clockworkBosses$beginSonicVisual(long now, int duration, int recoveryTicks);
    void clockworkBosses$finishSonicVisual(long now);
    void clockworkBosses$clearSonicVisual();
    long clockworkBosses$sonicVisualMillis(long now, float partialTick);

    /** Saved command/display identity, independent of the native entity name. */
    String clockworkBosses$getName();
    void clockworkBosses$setName(String value);

    /** Saved template filename stem; reload/rehydration resolves settings from it. */
    String clockworkBosses$getTemplate();
    void clockworkBosses$setTemplate(String value);

    /** Username whose Minecraft skin is used for this Clockwork Boss, or empty for vanilla. */
    String clockworkBosses$getSkinPlayerName();
    void clockworkBosses$setSkinPlayerName(String value);

    /** Source selected in the template: default, player, or custom. */
    String clockworkBosses$getSkinType();
    void clockworkBosses$setSkinType(String value);

    /** Local PNG filename for a custom Warden atlas, or empty when not applicable. */
    String clockworkBosses$getCustomSkinFile();
    void clockworkBosses$setCustomSkinFile(String value);

    /** Saved encounter locks; emerging survives restart even if brain memory/pose does not. */
    boolean clockworkBosses$isEmerging();
    void clockworkBosses$setEmerging(boolean value);

    /** Saved hidden waiting state; only activation removes its no-AI/invulnerability locks. */
    boolean clockworkBosses$isDormant();
    void clockworkBosses$setDormant(boolean value);

    /** Immutable native destination used to restore the entity after its underground holding offset. */
    BlockPos clockworkBosses$getEmergencePos();
    void clockworkBosses$setEmergencePos(BlockPos value);

    /** Returns the ARGB tint used for this Clockwork Boss's emissive effects. */
    int clockworkBosses$getEffectColor();

    /** Sets the ARGB tint used for this Clockwork Boss's emissive effects. */
    void clockworkBosses$setEffectColor(int color);

    /** Returns the entity tick at which the last real phase-change flash began. */
    int clockworkBosses$getPhaseChangeTick();

    /** Changes the server transition token; client/server entity ages are not comparable. */
    void clockworkBosses$setPhaseChangeTick(int tick);

    /** Observes a transition token and returns its start in this client entity's local clock. */
    int clockworkBosses$getLocalFlashStartTick();

}
