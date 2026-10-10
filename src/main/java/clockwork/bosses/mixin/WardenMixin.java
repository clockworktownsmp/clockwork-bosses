package clockwork.bosses.mixin;

import clockwork.bosses.ClockworkBossManager;
import clockwork.bosses.ClockworkBossRewardData;
import clockwork.bosses.ClockworkBossRewards;
import clockwork.bosses.ClockworkBossDiagnostics;
import clockwork.bosses.ClockworkBossHealthAttribute;
import clockwork.bosses.ClockworkBossSettings;
import clockwork.bosses.ClockworkBossMobSounds;
import clockwork.bosses.ClockworkBossLegacyData;
import clockwork.bosses.ClockworkBossChargeVisual;
import net.minecraft.world.entity.ai.attributes.Attributes;
import clockwork.bosses.ClockworkBossTemplateNames;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import net.minecraft.world.phys.Vec3;
import clockwork.bosses.ClockworkBossMarker;
import clockwork.bosses.CustomWardenSkinPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.warden.WardenAi;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.util.Unit;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Adds persistent Clockwork Boss identity and dormant state to vanilla Wardens. */
@Mixin(Warden.class)
public abstract class WardenMixin implements ClockworkBossMarker, ClockworkBossRewardData {
    // Only a marked boss allocates a pending reward. Taking it releases damage-source/attacker references.
    @Unique private boolean clockworkBosses$rewardClaimed;
    @Unique private ClockworkBossRewards.DeathReward clockworkBosses$pendingReward;
    @Override public void clockworkBosses$prepareReward(ClockworkBossRewards.DeathReward reward) {
        if (!clockworkBosses$rewardClaimed && clockworkBosses$pendingReward == null) clockworkBosses$pendingReward = reward;
    }
    @Override public ClockworkBossRewards.DeathReward clockworkBosses$takeReward() {
        var reward = clockworkBosses$pendingReward;
        clockworkBosses$pendingReward = null;
        return reward;
    }
    @Override public boolean clockworkBosses$claimReward() {
        if (clockworkBosses$rewardClaimed) return false;
        clockworkBosses$rewardClaimed = true;
        return true;
    }
    @Override public boolean clockworkBosses$rewardClaimed() { return clockworkBosses$rewardClaimed; }

    @Unique private boolean clockworkBosses$isClockworkBoss;
    // Injected instance fields must not depend on a mixin constructor's object
    // initializer being copied. Allocate only when a marked boss needs a ledger.
    @Unique private java.util.Set<java.util.UUID> clockworkBosses$healedPlayers;
    @Unique private java.util.Set<java.util.UUID> clockworkBosses$healLedger() {
        if (clockworkBosses$healedPlayers == null) clockworkBosses$healedPlayers = new java.util.HashSet<>();
        return clockworkBosses$healedPlayers;
    }
    /** Store UUIDs only, capped at 4096; an exhausted ledger refuses new free
     * heals while still allowing health floors and potions. No Player is retained. */
    @Override public boolean clockworkBosses$claimInitialHeal(java.util.UUID player) {
        var ledger = clockworkBosses$healLedger();
        return ledger.size() < 4096 && ledger.add(player);
    }
    @Unique private String clockworkBosses$name;
    @Unique private String clockworkBosses$template;
    // Local copies are serialized to entity NBT; the matching entity-data fields sync to clients.
    @Unique private String clockworkBosses$skinPlayerName;
    @Unique private String clockworkBosses$skinType;
    @Unique private String clockworkBosses$customSkinFile;
    @Unique private boolean clockworkBosses$isDormant;
    @Unique private boolean clockworkBosses$isEmerging;
    @Unique private BlockPos clockworkBosses$emergencePos;
    // JVM defaults are reliable for injected fields: zero is a valid world
    // time, so a separate flag distinguishes "never hit" from a hit at tick 0.
    @Unique private long clockworkBosses$lastMeleeTick;
    @Unique private boolean clockworkBosses$hasMeleeHit;
    // Set only after our native-melee gates pass; cancelled cooldown checks are not attacks.
    @Unique private boolean clockworkBosses$meleeAttempt;
    @Unique private long clockworkBosses$meleeGeneration;
    @Unique private int clockworkBosses$lastMeleeDelay, clockworkBosses$attemptCooldown, clockworkBosses$attemptRecovery;
    @Unique private String clockworkBosses$observedMeleeVisual;
    @Unique private clockwork.bosses.ClockworkBossMeleeVisual clockworkBosses$cachedMeleeVisual;
    @Override public void clockworkBosses$beginMeleeVisual(long now, int charge, int recovery) {
        ((Warden) (Object) this).getEntityData().set(clockworkBosses$MELEE_VISUAL,
                new clockwork.bosses.ClockworkBossMeleeVisual(now, charge, recovery, -1).encode());
    }
    @Override public void clockworkBosses$finishMeleeVisual(long now) {
        var data = ((Warden) (Object) this).getEntityData();
        var visual = clockwork.bosses.ClockworkBossMeleeVisual.decode(data.get(clockworkBosses$MELEE_VISUAL));
        if (visual != null) data.set(clockworkBosses$MELEE_VISUAL, visual.hit(now).encode());
    }
    @Override public void clockworkBosses$clearMeleeVisual() { ((Warden) (Object) this).getEntityData().set(clockworkBosses$MELEE_VISUAL, ""); }
    @Override public long clockworkBosses$meleeVisualMillis(long now, float partial) {
        String text = ((Warden) (Object) this).getEntityData().get(clockworkBosses$MELEE_VISUAL);
        if (!text.equals(clockworkBosses$observedMeleeVisual)) {
            clockworkBosses$observedMeleeVisual = text;
            clockworkBosses$cachedMeleeVisual = clockwork.bosses.ClockworkBossMeleeVisual.decode(text);
        }
        return clockworkBosses$cachedMeleeVisual == null ? -1 : clockworkBosses$cachedMeleeVisual.millis(now, partial);
    }
    @Unique private int clockworkBosses$shotSequence;
    @Unique private String clockworkBosses$observedSonicVisual;
    @Unique private ClockworkBossChargeVisual clockworkBosses$cachedSonicVisual;
    // Per-entity client observation avoids a global entity cache and survives
    // renderer snapshot reuse. Initial tracking establishes a silent baseline.
    @Unique private boolean clockworkBosses$flashBaseline;
    @Unique private int clockworkBosses$observedPhaseTick;
    @Unique private int clockworkBosses$localFlashTick;

    /** Seed non-JVM defaults on the actual native entity constructor. The mixin
     * is not itself instantiated; using this callback makes fresh save metadata
     * and the first client flash baseline independent of initializer merging. */
    @Inject(method = "<init>", at = @At("TAIL"))
    private void clockworkBosses$initializeMetadata(CallbackInfo ci) {
        clockworkBosses$name = "";
        clockworkBosses$template = "";
        clockworkBosses$skinPlayerName = "";
        clockworkBosses$skinType = "";
        clockworkBosses$customSkinFile = "";
        clockworkBosses$emergencePos = BlockPos.ZERO;
        clockworkBosses$observedPhaseTick = Integer.MIN_VALUE;
        clockworkBosses$localFlashTick = Integer.MIN_VALUE;
    }

    // These fields are synchronised with the client through vanilla entity metadata.
    @Unique private static final EntityDataAccessor<Boolean> clockworkBosses$IS_CLOCKWORK_BOSS =
            SynchedEntityData.defineId(Warden.class, EntityDataSerializers.BOOLEAN);
    @Unique private static final EntityDataAccessor<Integer> clockworkBosses$EFFECT_COLOR =
            SynchedEntityData.defineId(Warden.class, EntityDataSerializers.INT);
    @Unique private static final EntityDataAccessor<Integer> clockworkBosses$PHASE_CHANGE_TICK =
            SynchedEntityData.defineId(Warden.class, EntityDataSerializers.INT);
    @Unique private static final EntityDataAccessor<String> clockworkBosses$SKIN_PLAYER_NAME =
            SynchedEntityData.defineId(Warden.class, EntityDataSerializers.STRING);
    @Unique private static final EntityDataAccessor<String> clockworkBosses$SKIN_TYPE =
            SynchedEntityData.defineId(Warden.class, EntityDataSerializers.STRING);
    @Unique private static final EntityDataAccessor<String> clockworkBosses$CUSTOM_SKIN_FILE =
            SynchedEntityData.defineId(Warden.class, EntityDataSerializers.STRING);

    @Unique private static final EntityDataAccessor<String> clockworkBosses$TEMPLATE =
            SynchedEntityData.defineId(Warden.class, EntityDataSerializers.STRING);
    @Unique private static final EntityDataAccessor<Float> clockworkBosses$MOB_SOUND_VOLUME =
            SynchedEntityData.defineId(Warden.class, EntityDataSerializers.FLOAT);
    // One field keeps start/duration/fire state atomic even during rapid restarts.
    @Unique private static final EntityDataAccessor<String> clockworkBosses$SONIC_VISUAL =
            SynchedEntityData.defineId(Warden.class, EntityDataSerializers.STRING);
    @Unique private static final EntityDataAccessor<Integer> clockworkBosses$EYES =
            SynchedEntityData.defineId(Warden.class, EntityDataSerializers.INT);
    // Eyebrows move independently from eyes; appended metadata is synchronized by vanilla tracking.
    @Unique private static final EntityDataAccessor<Integer> clockworkBosses$BROWS =
            SynchedEntityData.defineId(Warden.class, EntityDataSerializers.INT);
    // Append metadata so existing face/skin field IDs do not move.
    @Unique private static final EntityDataAccessor<String> clockworkBosses$MELEE_VISUAL =
            SynchedEntityData.defineId(Warden.class, EntityDataSerializers.STRING);
    @Override public int clockworkBosses$getBrows() { return ((Warden) (Object) this).getEntityData().get(clockworkBosses$BROWS); }
    @Override public void clockworkBosses$setBrows(int coordinates) {
        ((Warden) (Object) this).getEntityData().set(clockworkBosses$BROWS, clockwork.bosses.ClockworkBossBrows.unpack(coordinates).packed());
    }

    @Override public int clockworkBosses$getEyes() { return ((Warden) (Object) this).getEntityData().get(clockworkBosses$EYES); }
    @Override public void clockworkBosses$setEyes(int coordinates) {
        ((Warden) (Object) this).getEntityData().set(clockworkBosses$EYES, clockwork.bosses.ClockworkBossEyes.unpack(coordinates).packed());
    }

    @Override public String clockworkBosses$getSonicVisual() {
        return ((Warden) (Object) this).getEntityData().get(clockworkBosses$SONIC_VISUAL);
    }
    @Override public void clockworkBosses$beginSonicVisual(long now, int duration, int recoveryTicks) {
        var visual = new ClockworkBossChargeVisual(++clockworkBosses$shotSequence, now, duration, -1, recoveryTicks);
        ((Warden) (Object) this).getEntityData().set(clockworkBosses$SONIC_VISUAL, visual.encode());
    }
    @Override public void clockworkBosses$finishSonicVisual(long now) {
        var visual = ClockworkBossChargeVisual.decode(clockworkBosses$getSonicVisual());
        if (visual != null) ((Warden) (Object) this).getEntityData().set(clockworkBosses$SONIC_VISUAL, visual.fired(now).encode());
    }
    @Override public void clockworkBosses$clearSonicVisual() {
        ((Warden) (Object) this).getEntityData().set(clockworkBosses$SONIC_VISUAL, "");
    }
    @Override public long clockworkBosses$sonicVisualMillis(long now, float partialTick) {
        String value = clockworkBosses$getSonicVisual();
        if (!value.equals(clockworkBosses$observedSonicVisual)) {
            clockworkBosses$observedSonicVisual = value;
            clockworkBosses$cachedSonicVisual = ClockworkBossChargeVisual.decode(value);
        }
        return clockworkBosses$cachedSonicVisual == null ? -1 : clockworkBosses$cachedSonicVisual.millis(now, partialTick);
    }

    @Override public float clockworkBosses$getMobSoundVolume() {
        return ((Warden) (Object) this).getEntityData().get(clockworkBosses$MOB_SOUND_VOLUME);
    }
    @Override public void clockworkBosses$setMobSoundVolume(float volume) {
        float safe = Float.isFinite(volume) ? Math.clamp(volume, 0, 1) : 0.25F;
        ((Warden) (Object) this).getEntityData().set(clockworkBosses$MOB_SOUND_VOLUME, safe);
    }

    /** Heartbeat uses client-local playback instead of Entity.playSound; apply the same synced multiplier once. */
    @WrapOperation(method = "tick", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/world/level/Level;playLocalSound(DDDLnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FFZ)V"))
    private void clockworkBosses$heartbeatVolume(Level level, double x, double y, double z,
            SoundEvent sound, SoundSource category, float volume, float pitch, boolean distanceDelay,
            Operation<Void> original) {
        float adjusted = ClockworkBossMobSounds.volume((Warden) (Object) this, sound, volume);
        if (adjusted == 0 && volume != 0) return;
        original.call(level, x, y, z, sound, category, adjusted, pitch, distanceDelay);
    }

    @Override public boolean clockworkBosses$isClockworkBoss() {
        return clockworkBosses$isClockworkBoss;
    }
    @Override public boolean clockworkBosses$isClockworkBossSynced() {
        return ((Warden) (Object) this).getEntityData().get(clockworkBosses$IS_CLOCKWORK_BOSS);
    }
    @Override public void clockworkBosses$setClockworkBoss(boolean value) {
        clockworkBosses$isClockworkBoss = value;
        clockworkBosses$setHealthRange(value);
        ((Warden) (Object) this).getEntityData().set(clockworkBosses$IS_CLOCKWORK_BOSS, value);
    }

    /** Touch only this Warden's MAX_HEALTH instance, never the shared native attribute definition. */
    @Unique private void clockworkBosses$setHealthRange(boolean enabled) {
        var health = ((Warden) (Object) this).getAttribute(Attributes.MAX_HEALTH);
        if (health != null) ((ClockworkBossHealthAttribute) health).clockworkBosses$setBossHealth(enabled);
    }

    /** Marked clients use the same range even if attributes arrive before the boss metadata packet. */
    @Inject(method = "onSyncedDataUpdated", at = @At("TAIL"))
    private void clockworkBosses$syncHealthRange(EntityDataAccessor<?> key, CallbackInfo ci) {
        if (key.equals(clockworkBosses$IS_CLOCKWORK_BOSS)) clockworkBosses$setHealthRange(clockworkBosses$isClockworkBossSynced());
    }

    /** Opt in before native parent loading restores attributes and clamps saved Health; no second HP save field. */
    @Inject(method = "readAdditionalSaveData", at = @At("HEAD"))
    private void clockworkBosses$loadHealthRange(ValueInput input, CallbackInfo ci) {
        clockworkBosses$setHealthRange(ClockworkBossLegacyData.isBoss(input));
    }
    @Override public String clockworkBosses$getName() { return clockworkBosses$name; }
    @Override public void clockworkBosses$setName(String value) { clockworkBosses$name = value == null ? "" : value; }
    /** Native metadata scopes client custom textures without a separate tracking packet or map. */
    @Override public String clockworkBosses$getTemplate() {
        return ((Warden) (Object) this).getEntityData().get(clockworkBosses$TEMPLATE);
    }
    @Override public void clockworkBosses$setTemplate(String value) {
        clockworkBosses$template = ClockworkBossTemplateNames.isValid(value) ? value : "";
        ((Warden) (Object) this).getEntityData().set(clockworkBosses$TEMPLATE, clockworkBosses$template);
    }
    @Override public String clockworkBosses$getSkinPlayerName() {
        return ((Warden) (Object) this).getEntityData().get(clockworkBosses$SKIN_PLAYER_NAME);
    }
    @Override public void clockworkBosses$setSkinPlayerName(String value) {
        String name = value == null ? "" : value.trim();
        if (!name.matches("[A-Za-z0-9_]{0,16}")) {
            name = "";
        }
        clockworkBosses$skinPlayerName = name;
        ((Warden) (Object) this).getEntityData().set(clockworkBosses$SKIN_PLAYER_NAME, name);
    }
    @Override public String clockworkBosses$getSkinType() {
        return ((Warden) (Object) this).getEntityData().get(clockworkBosses$SKIN_TYPE);
    }
    @Override public void clockworkBosses$setSkinType(String value) {
        String type = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
        if (!type.isEmpty() && !type.equals("default") && !type.equals("player") && !type.equals("custom")) {
            type = "";
        }
        clockworkBosses$skinType = type;
        ((Warden) (Object) this).getEntityData().set(clockworkBosses$SKIN_TYPE, type);
    }
    @Override public String clockworkBosses$getCustomSkinFile() {
        return ((Warden) (Object) this).getEntityData().get(clockworkBosses$CUSTOM_SKIN_FILE);
    }
    @Override public void clockworkBosses$setCustomSkinFile(String value) {
        String file = value == null ? "" : value.trim();
        // Sync one filename as the cache key; only the server reads the skins folder.
        // Share the same lowercase basename policy used for PNG transfer and
        // template validation; never silently change case on a Linux filesystem.
        if (!CustomWardenSkinPayload.isValidFileName(file)) {
            file = "";
        }
        clockworkBosses$customSkinFile = file;
        ((Warden) (Object) this).getEntityData().set(clockworkBosses$CUSTOM_SKIN_FILE, file);
    }
    @Override public boolean clockworkBosses$isEmerging() { return clockworkBosses$isEmerging; }
    @Override public void clockworkBosses$setEmerging(boolean value) { clockworkBosses$isEmerging = value; }
    @Override public boolean clockworkBosses$isDormant() { return clockworkBosses$isDormant; }
    @Override public void clockworkBosses$setDormant(boolean value) { clockworkBosses$isDormant = value; }
    @Override public BlockPos clockworkBosses$getEmergencePos() { return clockworkBosses$emergencePos; }
    @Override public void clockworkBosses$setEmergencePos(BlockPos value) {
        clockworkBosses$emergencePos = value == null ? BlockPos.ZERO : value.immutable();
    }

    @Override
    public int clockworkBosses$getEffectColor() {
        return ((Warden) (Object) this).getEntityData().get(clockworkBosses$EFFECT_COLOR);
    }

    @Override
    public void clockworkBosses$setEffectColor(int color) {
        ((Warden) (Object) this).getEntityData().set(clockworkBosses$EFFECT_COLOR, color);
    }

    @Override
    public int clockworkBosses$getPhaseChangeTick() {
        return ((Warden) (Object) this).getEntityData().get(clockworkBosses$PHASE_CHANGE_TICK);
    }

    @Override
    public void clockworkBosses$setPhaseChangeTick(int tick) {
        ((Warden) (Object) this).getEntityData().set(clockworkBosses$PHASE_CHANGE_TICK, tick);
    }

    @Override
    public int clockworkBosses$getLocalFlashStartTick() {
        int token = clockworkBosses$getPhaseChangeTick();
        if (!clockworkBosses$flashBaseline) {
            clockworkBosses$flashBaseline = true;
            clockworkBosses$observedPhaseTick = token;
        } else if (clockworkBosses$observedPhaseTick != token) {
            clockworkBosses$observedPhaseTick = token;
            clockworkBosses$localFlashTick = token == Integer.MIN_VALUE
                    ? Integer.MIN_VALUE : ((Warden) (Object) this).tickCount;
        }
        return clockworkBosses$localFlashTick;
    }

    /**
     * The configured per-phase aura replaces vanilla's fixed 20-block/260-tick
     * aura for marked bosses. Keep the original call for every ordinary Warden.
     * Without this narrow condition, darkness.enabled=false would still darken players.
     */
    @WrapWithCondition(method = "customServerAiStep", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/monster/warden/Warden;applyDarknessAround("
                    + "Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/phys/Vec3;"
                    + "Lnet/minecraft/world/entity/Entity;I)V"))
    private boolean clockworkBosses$useVanillaDarkness(ServerLevel level, Vec3 position,
                                                      Entity source, int radius) {
        return !clockworkBosses$isClockworkBoss;
    }

    /** Defines the network-synchronised Clockwork Boss visual state. */
    @Inject(method = "defineSynchedData", at = @At("TAIL"))
    private void clockworkBosses$defineVisualData(SynchedEntityData.Builder builder, CallbackInfo ci) {
        builder.define(clockworkBosses$IS_CLOCKWORK_BOSS, false);
        builder.define(clockworkBosses$EFFECT_COLOR, 0xFFFFFFFF);
        builder.define(clockworkBosses$PHASE_CHANGE_TICK, Integer.MIN_VALUE);
        builder.define(clockworkBosses$SKIN_PLAYER_NAME, "");
        builder.define(clockworkBosses$SKIN_TYPE, "");
        builder.define(clockworkBosses$CUSTOM_SKIN_FILE, "");
        builder.define(clockworkBosses$TEMPLATE, "");
        builder.define(clockworkBosses$MOB_SOUND_VOLUME, 0.25F);
        builder.define(clockworkBosses$SONIC_VISUAL, "");
        builder.define(clockworkBosses$EYES, clockwork.bosses.ClockworkBossEyes.DEFAULT.packed());
        builder.define(clockworkBosses$BROWS, clockwork.bosses.ClockworkBossBrows.DEFAULT.packed());
        builder.define(clockworkBosses$MELEE_VISUAL, "");
    }

    /** Establish phase attributes before Brain.tick can attempt a native melee hit. */
    @Inject(method = "customServerAiStep", at = @At("HEAD"))
    private void clockworkBosses$prepareCombat(ServerLevel level, CallbackInfo ci) {
        if (clockworkBosses$isClockworkBoss) ClockworkBossManager.prepareCombatPhase((Warden) (Object) this);
    }

    /**
     * Keeps marked bosses out of native DIG activity, then runs encounter logic after normal
     * target/navigation AI. The native brain still owns targeting and movement.
     */
    @Inject(method = "customServerAiStep", at = @At("TAIL"))
    private void clockworkBosses$serverStep(ServerLevel level, CallbackInfo ci) {
        Warden warden = (Warden) (Object) this;
        ClockworkBossMarker marker = (ClockworkBossMarker) (Object) this;
        if (!marker.clockworkBosses$isClockworkBoss()) return;

        // Vanilla prioritizes DIG ahead of FIGHT whenever DIG_COOLDOWN is absent.
        // DiggingMixin prevents the digging behavior itself, but without this
        // memory the Warden can remain stuck in the DIG activity with no movement.
        var brain = warden.getBrain();
        if (!brain.hasMemoryValue(MemoryModuleType.DIG_COOLDOWN)) {
            brain.setMemoryWithExpiry(
                    MemoryModuleType.DIG_COOLDOWN,
                    Unit.INSTANCE,
                    WardenAi.DIGGING_COOLDOWN
            );
            if (brain.isActive(Activity.DIG)) {
                WardenAi.updateActivity(brain);
            }
        }

        ClockworkBossManager.tickClockworkBossLogic(warden);
    }

    /** Observe the actual assignment after native/other-mod work; local state also handles nested calls safely. */
    @WrapMethod(method = "setAttackTarget")
    private void clockworkBosses$targetChanged(LivingEntity target, Operation<Void> original) {
        if (!clockworkBosses$isClockworkBoss) { original.call(target); return; }
        Warden boss = (Warden) (Object) this;
        LivingEntity previous = boss.getTarget();
        if (ClockworkBossManager.keepCurrentTarget(boss, previous, target)) {
            if (ClockworkBossDiagnostics.enabled()) ClockworkBossDiagnostics.event(boss, target, "target_assignment_rejected", "reason", "minimum_hold");
            return;
        }
        java.util.UUID before = previous == null ? null : previous.getUUID();
        original.call(target);
        LivingEntity actual = boss.getTarget();
        java.util.UUID current = actual == null ? null : actual.getUUID();
        if (!java.util.Objects.equals(before, current)) ClockworkBossManager.onTargetChanged(boss, actual);
    }

    /**
     * Gates native melee only for marked bosses and uses elapsed world time to enforce the configured
     * captured successful-hit cooldown and swing recovery. Minecraft still
     * owns melee eligibility and its native minimum attack cadence.
     */
    @Inject(method = "doHurtTarget", at = @At("HEAD"), cancellable = true)
    private void clockworkBosses$meleeControl(ServerLevel level, Entity target, CallbackInfoReturnable<Boolean> cir) {
        clockworkBosses$meleeAttempt = false;
        Warden warden = (Warden) (Object) this;
        if (!ClockworkBossManager.isClockworkBoss(warden)) return;
        if (warden.isNoAi()) {
            if (ClockworkBossDiagnostics.enabled()) ClockworkBossDiagnostics.event(warden, target, "melee_rejected", "reason", "no_ai");
            cir.setReturnValue(false);
            return;
        }
        // Also covers direct doHurtTarget calls outside Brain.tick: apply the
        // phase's native damage/knockback first, then gate that same snapshot.
        ClockworkBossSettings.Phase phase = ClockworkBossManager.prepareCombatPhase(warden);
        if (phase == null || !phase.meleeEnabled()) {
            if (ClockworkBossDiagnostics.enabled()) ClockworkBossDiagnostics.event(warden, target, "melee_rejected", "reason", "locked_or_disabled");
            cir.setReturnValue(false);
            return;
        }
        long now = level.getGameTime();
        if (clockworkBosses$hasMeleeHit && now - clockworkBosses$lastMeleeTick < clockworkBosses$lastMeleeDelay) {
            if (ClockworkBossDiagnostics.enabled()) ClockworkBossDiagnostics.event(warden, target, "melee_rejected", "reason", "captured_cooldown", "ready_tick", clockworkBosses$lastMeleeTick + clockworkBosses$lastMeleeDelay);
            cir.setReturnValue(false);
            return;
        }
        if (clockwork.bosses.ClockworkBossMelee.postpone(warden, target, phase)) {
            cir.setReturnValue(false); return;
        }
        var state = ClockworkBossManager.stateFor(warden);
        clockworkBosses$attemptCooldown = phase.meleeCooldown(); clockworkBosses$attemptRecovery = phase.meleeRecoveryTicks();
        if (state != null) {
            clockworkBosses$meleeGeneration = state.subphaseGeneration();
        }
    }

    /** Count only calls that reach the native hit body. A later HEAD veto from
     * another mod must not advance a child merely because our gate accepted it.
     * This is an invokespecial super call: MixinExtras requires the enclosing
     * Warden as receiver, even though the called implementation belongs to Monster. */
    @WrapOperation(method = "doHurtTarget", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/world/entity/monster/Monster;doHurtTarget(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/entity/Entity;)Z"))
    private boolean clockworkBosses$nativeMelee(Warden boss,
            ServerLevel level, Entity target, Operation<Boolean> original) {
        boolean capture = ClockworkBossDiagnostics.enabled() && clockworkBosses$isClockworkBoss;
        float before = capture && target instanceof LivingEntity living ? living.getHealth() : 0;
        net.minecraft.world.phys.Vec3 velocity = capture ? target.getDeltaMovement() : null;
        if (clockworkBosses$isClockworkBoss) {
            clockworkBosses$meleeAttempt = true;
            var state = ClockworkBossManager.stateFor((Warden) (Object) this);
            if (state != null && !state.resolvingMelee()) {
                if (capture) ClockworkBossDiagnostics.begin(boss, target, "melee", "charge", 0,
                        "cooldown", clockworkBosses$attemptCooldown, "recovery_ticks", clockworkBosses$attemptRecovery,
                        "damage_attribute", boss.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE));
                clockworkBosses$beginMeleeVisual(level.getGameTime(), 0, clockworkBosses$attemptRecovery);
            }
        }
        if (capture) ClockworkBossDiagnostics.event(boss, target, "melee_native_attempt", "hp_before", before);
        boolean accepted = original.call(boss, level, target);
        if (capture) ClockworkBossDiagnostics.event(boss, target, "melee_native_result", "accepted", accepted,
                "hp_before", before, "hp_after", target instanceof LivingEntity living ? living.getHealth() : 0,
                "velocity_delta_x", target.getDeltaMovement().x - velocity.x,
                "velocity_delta_y", target.getDeltaMovement().y - velocity.y,
                "velocity_delta_z", target.getDeltaMovement().z - velocity.z);
        return accepted;
    }

    /** A completed blocked swing still recovers and counts toward its child;
     * only successful damage consumes the extra configured hit cooldown. */
    @Inject(method = "doHurtTarget", at = @At("RETURN"))
    private void clockworkBosses$recordMeleeHit(ServerLevel level, Entity target,
                                              CallbackInfoReturnable<Boolean> cir) {
        boolean completed = clockworkBosses$meleeAttempt;
        clockworkBosses$meleeAttempt = false;
        if (completed) {
            clockworkBosses$finishMeleeVisual(level.getGameTime());
            clockworkBosses$lastMeleeTick = level.getGameTime(); clockworkBosses$hasMeleeHit = true;
            clockworkBosses$lastMeleeDelay = Math.max(cir.getReturnValueZ() ? clockworkBosses$attemptCooldown : 0, clockworkBosses$attemptRecovery);
        }
        if (completed && cir.getReturnValueZ()) {
            ClockworkBossManager.onSuccessfulAttack((Warden) (Object) this);
        }
        // A swung attack can be blocked by the victim and still chooses a next
        // opponent. Disabled/cooldown-rejected calls never reach this event.
        if (completed) ClockworkBossManager.onAttackCompleted((Warden) (Object) this, target, clockworkBosses$meleeGeneration);
    }

    /**
     * Rejects damage only while a marked encounter is dormant; normal Wardens retain their native damage
     * rules.
     */
    @Inject(method = "isInvulnerableTo", at = @At("HEAD"), cancellable = true)
    private void clockworkBosses$dormantInvulnerability(
            ServerLevel level, DamageSource source, CallbackInfoReturnable<Boolean> cir) {
        if (clockworkBosses$isClockworkBoss && clockworkBosses$isDormant) {
            cir.setReturnValue(true);
        }
    }

    /**
     * Persists encounter identity, selected skin and dormant/emerging locks with native ValueOutput;
     * transient caches are never saved.
     */
    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void clockworkBosses$saveBossData(ValueOutput output, CallbackInfo ci) {
        if (!clockworkBosses$isClockworkBoss) return;
        output.putBoolean("ClockworkBosses_Boss", true);
        output.putBoolean("ClockworkBosses_RewardClaimed", clockworkBosses$rewardClaimed);
        if (clockworkBosses$healedPlayers != null && !clockworkBosses$healedPlayers.isEmpty()) {
            var healed = output.list("ClockworkBosses_HealedPlayers", net.minecraft.core.UUIDUtil.CODEC);
            for (var player : clockworkBosses$healedPlayers) healed.add(player);
        }
        output.putString("ClockworkBosses_Name", clockworkBosses$name);
        output.putString("ClockworkBosses_Template", clockworkBosses$template);
        output.putString("ClockworkBosses_SkinPlayer", clockworkBosses$skinPlayerName);
        output.putString("ClockworkBosses_SkinType", clockworkBosses$skinType);
        output.putString("ClockworkBosses_CustomSkinFile", clockworkBosses$customSkinFile);
        output.putBoolean("ClockworkBosses_Dormant", clockworkBosses$isDormant);
        output.putBoolean("ClockworkBosses_Emerging", clockworkBosses$isEmerging);
        output.putInt("ClockworkBosses_EmergenceX", clockworkBosses$emergencePos.getX());
        output.putInt("ClockworkBosses_EmergenceY", clockworkBosses$emergencePos.getY());
        output.putInt("ClockworkBosses_EmergenceZ", clockworkBosses$emergencePos.getZ());
    }

    /**
     * Restores saved metadata with native ValueInput, including legacy identity and emergence-lock
     * recovery, then resets transient hit timing.
     */
    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void clockworkBosses$loadBossData(ValueInput input, CallbackInfo ci) {
        clockworkBosses$pendingReward = null;
        clockworkBosses$rewardClaimed = input.getBooleanOr("ClockworkBosses_RewardClaimed", false);
        clockworkBosses$healedPlayers = null;
        for (var player : input.listOrEmpty("ClockworkBosses_HealedPlayers", net.minecraft.core.UUIDUtil.CODEC)) {
            var ledger = clockworkBosses$healLedger();
            if (ledger.size() >= 4096) break;
            ledger.add(player);
        }
        clockworkBosses$setClockworkBoss(ClockworkBossLegacyData.isBoss(input));
        clockworkBosses$name = ClockworkBossLegacyData.string(input, "Name", "");
        clockworkBosses$setTemplate(ClockworkBossLegacyData.string(input, "Template", ""));
        clockworkBosses$setSkinPlayerName(ClockworkBossLegacyData.string(input, "SkinPlayer", ""));
        clockworkBosses$setSkinType(ClockworkBossLegacyData.string(input, "SkinType", ""));
        clockworkBosses$setCustomSkinFile(ClockworkBossLegacyData.string(input, "CustomSkinFile", ""));
        clockworkBosses$isDormant = ClockworkBossLegacyData.bool(input, "Dormant", false);
        // Recover the emergence-only invulnerability lock of very old saved bosses.
        clockworkBosses$isEmerging = ClockworkBossLegacyData.bool(input, "Emerging",
                clockworkBosses$isClockworkBoss && !clockworkBosses$isDormant
                        && ((Warden) (Object) this).isPermanentlyInvulnerable());
        clockworkBosses$emergencePos = new BlockPos(
                ClockworkBossLegacyData.integer(input, "EmergenceX", 0),
                ClockworkBossLegacyData.integer(input, "EmergenceY", 0),
                ClockworkBossLegacyData.integer(input, "EmergenceZ", 0));
        clockworkBosses$hasMeleeHit = false;
        clockworkBosses$meleeAttempt = false;
        clockworkBosses$clearSonicVisual();
        clockworkBosses$lastMeleeTick = Long.MIN_VALUE;
    }
}
