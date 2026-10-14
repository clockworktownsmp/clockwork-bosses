package clockwork.bosses;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Runtime-only state for one Clockwork Boss. The saved Warden carries identity and
 * emergence data; this object owns transient deadlines, activation edge history,
 * current phase, and per-player boss-bar membership so these can be released when
 * the entity unloads or the server stops. All access is on the server thread:
 * mutable maps intentionally avoid concurrent containers and repeated copies.
 * Sets published for activation/bar membership are immutable snapshots.
 */
public final class ClockworkBossState {
    // Stable identity used for loaded-entity lookup, commands and template reload.
    private final UUID uuid;
    private final String name;
    private final String templateName;
    // Dimension follows the entity; emergencePos is its saved activation destination.
    private ResourceKey<Level> dimension;
    private final BlockPos emergencePos;
    // Encounter locks differ: dormant waits for an edge, emerging waits for native brain memory.
    private boolean dormant;
    private boolean emerging;
    // Edge detection stores UUIDs rather than strong Player references across disconnects.
    private boolean activationBaselineInitialized;
    private Set<UUID> activationInsidePlayerIds = Set.of();
    private boolean previousRedstonePowered;

    // Next game tick at which each nearby player may receive the Darkness aura.
    private final Map<UUID, Long> darknessNextTicks = new HashMap<>();
    private final Set<UUID> darknessNearbyPlayers = new HashSet<>();
    // Limits active-boss spatial queries while allowing short configured intervals.
    private long nextDarknessScanTick;
    // JSON is used on transitions/reload; immutable compiled settings serve repeating AI steps.
    private JsonObject config;
    private ClockworkBossSettings settings;
    private ClockworkBossAreaEffects.Settings areaEffects;
    private ClockworkBossPlayerSupport playerSupport;
    private ClockworkBossPvp.Settings pvpProtection;
    private ClockworkBossMusic.Settings music;
    // One controller per loaded boss; no retained entities or independent timer.
    private final ClockworkBossFaceEvents face = new ClockworkBossFaceEvents();
    // Region parsing is a configuration operation, never watcher work.
    private Areas areas;
    // The dormant trigger stays at the visible emergence point, independent of burial/scale.
    private final Vec3 emergenceCenter;
    private AABB activationRadiusBox;
    // The native event owns connected viewer references; unload/disconnect explicitly remove them.
    private final ServerBossEvent bossBar;
    private Set<UUID> bossBarPlayerIds = Set.of();
    private ClockworkBossPhase currentPhase;
    // A bounded child sequence owns only an index/count and a generation token.
    // Native roar callbacks capture the token so an interrupted old roar cannot
    // advance a replacement phase/reloaded sequence.
    private Map<ClockworkBossPhase, ClockworkBossSubphases.Sequence> subphases = Map.of();
    private int subphaseIndex, subphaseAttacks, nextRoarIndex, activeRoarIndex;
    private boolean subphaseInitialized, inSubphaseRoar;
    // A bounded shuffle bag visits each attack child once per random round.
    // It belongs to this boss only and contains indices, never entity references.
    private int[] shuffledSubphases;
    private int shuffleCursor;
    private long subphaseGeneration;
    // A recovery holds one game-time deadline, checked by the existing native
    // AI step. Resetting/changing the sequence clears it; no scheduled task survives.
    private long recoveryDeadline = -1;
    // Action ownership is attached to this loaded encounter, never a global timer.
    // Manager transitions stop it before replacing phase/config state.
    private ClockworkBossActionRuntime actionRuntime;
    // Stop callbacks may reenter phase/reload code. Defer starting a replacement
    // until every outgoing resource has been restored; no extra task is needed.
    private int stoppingActions;
    boolean actionsStopping() { return stoppingActions != 0; }
    void beginActionStop() { stoppingActions++; }
    void endActionStop() { stoppingActions--; }
    private boolean encounterEnded;
    public ClockworkBossActionRuntime actionRuntime() { return actionRuntime; }
    public void setActionRuntime(ClockworkBossActionRuntime runtime) { actionRuntime = runtime; }
    public boolean claimEncounterEnd() { if (encounterEnded) return false; encounterEnded = true; return true; }
    public boolean encounterEnded() { return encounterEnded; }
    /** A callback can end this encounter or change its generation during stop.
     * The outer transition must then abandon its stale settings/index changes. */
    private boolean stopActionForTransition(clockwork.bosses.api.BossAction.StopReason reason) {
        var previous = actionRuntime;
        long generation = subphaseGeneration;
        if (previous != null) previous.stop(reason);
        return !encounterEnded && actionRuntime == previous && subphaseGeneration == generation;
    }
    public boolean recoverySubphase() {
        var step = subphase();
        return !roarInterlude() && step != null && step.type() == ClockworkBossSubphases.Type.RECOVERY;
    }
    public void beginRecovery(long now) {
        if (recoverySubphase() && recoveryDeadline < 0) recoveryDeadline = now + subphase().durationTicks();
    }
    /** Read-only deadline for diagnostics; native world time remains authoritative. */
    public long recoveryDeadline() { return recoveryDeadline; }
    private ClockworkBossMelee.Pending pendingMelee;
    private ClockworkBossSettings.Phase resolvingMeleeSettings;
    public ClockworkBossMelee.Pending pendingMelee() { return pendingMelee; }
    public void setPendingMelee(ClockworkBossMelee.Pending pending) { pendingMelee = pending; }
    public boolean resolvingMelee() { return resolvingMeleeSettings != null; }
    public void setResolvingMeleeSettings(ClockworkBossSettings.Phase settings) { resolvingMeleeSettings = settings; }

    public ClockworkBossSubphases.Step subphase() {
        var sequence = sequence();
        return sequence == null ? null : sequence.steps().get(subphaseIndex);
    }
    private ClockworkBossSubphases.Sequence sequence() { return currentPhase == null ? null : subphases.get(currentPhase); }
    /** Select the first random child only after the parent is applied. Repeated
     * attribute/AI calls do not redraw it. Native entity randomness stays owned by Minecraft. */
    public void initializeSubphase(net.minecraft.util.RandomSource random) {
        if (subphaseInitialized || sequence() == null) return;
        subphaseInitialized = true;
        if (sequence().order() == ClockworkBossSubphases.Order.RANDOM) subphaseIndex = drawSubphase(random, -1);
    }
    /** AI reads a precompiled child snapshot; aura/health selection keeps using its parent. */
    public ClockworkBossSettings.Phase combatSettings() {
        if (resolvingMeleeSettings != null) return resolvingMeleeSettings;
        var child = subphase();
        return child == null ? settings.phase(currentPhase) : child.behavior();
    }
    public long subphaseGeneration() { return subphaseGeneration; }
    public boolean roarInterlude() { return inSubphaseRoar && sequence() != null; }
    /** Selected roar sound is a load-time snapshot, not a per-tick merge. */
    public clockwork.bosses.api.BossActionSettings roarInterludeSettings() { return roarInterlude() ? sequence().roars().get(activeRoarIndex) : null; }
    /** Real attacks only; counters never depend on target changes or polling. */
    public boolean completeSubphaseAttack(net.minecraft.util.RandomSource random) {
        var step = subphase();
        if (step == null || roarInterlude() || step.type() != ClockworkBossSubphases.Type.ATTACK) return false;
        return completeSubphase(random);
    }
    /** An action signals its own completion; native attack budgets retain their
     * old counting path. Roars still use the independent interlude catalog. */
    public boolean completeAction(net.minecraft.util.RandomSource random) {
        var step = subphase(); if (step == null) return false;
        subphaseAttacks = step.attacks() - 1;
        return completeSubphase(random);
    }
    private boolean completeSubphase(net.minecraft.util.RandomSource random) {
        var step = subphase();
        if (step == null) return false;
        if (!recoverySubphase() && ++subphaseAttacks < step.attacks()) return false;
        if (!stopActionForTransition(clockwork.bosses.api.BossAction.StopReason.COMPLETED)) return false;
        var sequence = sequence();
        if (sequence.roarBetween() && !sequence.roars().isEmpty()) {
            inSubphaseRoar = true; subphaseAttacks = 0; subphaseGeneration++;
            if (sequence.order() == ClockworkBossSubphases.Order.RANDOM) activeRoarIndex = random.nextInt(sequence.roars().size());
            else { activeRoarIndex = nextRoarIndex; nextRoarIndex = (nextRoarIndex + 1) % sequence.roars().size(); }
        } else return advanceSubphase(random);
        return true;
    }
    /** Roar completion, or a direct transition when interludes are disabled.
     * Each sequential list cycles independently; random children use a shuffle bag. */
    public boolean advanceSubphase(net.minecraft.util.RandomSource random) {
        var sequence = sequence();
        if (sequence == null || !stopActionForTransition(clockwork.bosses.api.BossAction.StopReason.COMPLETED)) return false;
        subphaseIndex = sequence.order() == ClockworkBossSubphases.Order.RANDOM ? drawSubphase(random, subphaseIndex)
                : (subphaseIndex + 1) % sequence.steps().size();
        inSubphaseRoar = false; subphaseAttacks = 0; recoveryDeadline = -1;
        subphaseGeneration++;
        return true;
    }
    /** Fisher–Yates, at most 16 indices per refill. Every child runs before
     * reshuffling; swap the first draw to prevent a repeat at a round boundary. */
    private int drawSubphase(net.minecraft.util.RandomSource random, int previous) {
        int count = sequence().steps().size();
        if (shuffledSubphases == null || shuffleCursor >= count) {
            if (shuffledSubphases == null) shuffledSubphases = new int[count];
            for (int i = 0; i < count; i++) shuffledSubphases[i] = i;
            for (int i = count - 1; i > 0; i--) {
                int other = random.nextInt(i + 1), saved = shuffledSubphases[i];
                shuffledSubphases[i] = shuffledSubphases[other]; shuffledSubphases[other] = saved;
            }
            if (count > 1 && shuffledSubphases[0] == previous) {
                int other = 1 + random.nextInt(count - 1), saved = shuffledSubphases[0];
                shuffledSubphases[0] = shuffledSubphases[other]; shuffledSubphases[other] = saved;
            }
            shuffleCursor = 0;
        }
        return shuffledSubphases[shuffleCursor++];
    }
    private void resetSubphase() {
        subphaseIndex = 0; subphaseAttacks = 0; subphaseGeneration++;
        subphaseInitialized = false; inSubphaseRoar = false; nextRoarIndex = 0; activeRoarIndex = 0;
        shuffledSubphases = null; shuffleCursor = 0;
        pendingMelee = null;
        recoveryDeadline = -1;
        face.clear();
    }

    // One UUID/time/count per boss, not per player. Assignment and completed
    // attacks update these; no polling task or entity reference is retained.
    private UUID heldTarget;
    private long targetAssignedTick;
    private int attacksOnTarget;

    /** Real target changes restart the hold and attack budget. Reassigning the
     * same UUID does not restart them, including replacement player entities. */
    public void rememberTarget(UUID target, long now) {
        if (java.util.Objects.equals(heldTarget, target)) return;
        heldTarget = target; targetAssignedTick = now; attacksOnTarget = 0;
    }
    public boolean targetHoldActive(long now, int minimumTicks) {
        return heldTarget != null && now >= targetAssignedTick && now - targetAssignedTick < minimumTicks;
    }
    /** Saturate the counter while there is no eligible alternative; never overflow. */
    public int recordCompletedAttack() { return attacksOnTarget = Math.min(1000, attacksOnTarget + 1); }

    // Runtime-only Sonic Boom charge state. Cooldown is tracked by the Warden brain memory.
    private ClockworkBossSonicCharge sonicCharge;
    // Audio follows real elapsed time, even when the server is lagging. One
    // deadline is checked only on successful attacks; it needs no tick timer.
    private long voiceLineUntilNanos = Long.MIN_VALUE;
    private boolean voiceFilePending;

    /** Copies the immutable destination and validates settings before publishing a runtime record. */
    public ClockworkBossState(
            UUID uuid,
            String name,
            String templateName,
            ResourceKey<Level> dimension,
            BlockPos emergencePos,
            boolean dormant,
            JsonObject config,
            ServerBossEvent bossBar) {
        this.uuid = uuid;
        this.name = name;
        this.templateName = templateName;
        this.dimension = dimension;
        this.emergencePos = emergencePos.immutable();
        this.emergenceCenter = Vec3.atBottomCenterOf(this.emergencePos);
        this.dormant = dormant;
        setConfig(config);
        this.bossBar = bossBar;
    }

    /** Stable lookup identity and display/template names; none retain an entity object. */
    public UUID uuid() { return uuid; }
    public String name() { return name; }
    public String templateName() { return templateName; }
    /** Conservative server delivery gate; clients independently enforce actual playback exclusivity. */
    public boolean voiceLineBusy(long nowNanos) {
        return voiceFilePending || (voiceLineUntilNanos != Long.MIN_VALUE && nowNanos - voiceLineUntilNanos < 0);
    }
    /** A pending file cannot time out into a second concurrent voice request. */
    public void reserveVoiceFile() { voiceFilePending = true; }
    public void beginVoiceLine(long nowNanos, long durationMillis) {
        voiceFilePending = false;
        voiceLineUntilNanos = nowNanos + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(durationMillis);
    }
    /** Cancels a failed/pending voice on reload; clients separately own any audio already playing. */
    public void endVoiceLine() {
        voiceFilePending = false;
        voiceLineUntilNanos = Long.MIN_VALUE;
    }
    /** Current world key follows dimension transfers without copying or loading a world. */
    public ResourceKey<Level> dimension() { return dimension; }
    public void setDimension(ResourceKey<Level> dimension) { this.dimension = dimension; }
    /** Native immutable block position; returning it needs no wrapper or allocation. */
    public BlockPos emergencePos() { return emergencePos; }

    /** Dormant no-AI entities use the ten-tick watcher until activation. */
    public boolean dormant() { return dormant; }
    public void setDormant(boolean dormant) { this.dormant = dormant; }

    /** Transient mirror of the saved emergence lock; native brain expiry ends it. */
    public boolean emerging() { return emerging; }
    public void setEmerging(boolean emerging) { this.emerging = emerging; }

    /** Indicates that spawn/reload sampled current trigger state before watching for a new edge. */
    public boolean activationBaselineInitialized() { return activationBaselineInitialized; }
    public void setActivationBaselineInitialized(boolean initialized) {
        activationBaselineInitialized = initialized;
    }

    /** Previous spatial-query result, used to recognize new player entries rather than occupancy. */
    public Set<UUID> activationInsidePlayerIds() { return activationInsidePlayerIds; }
    public void setActivationInsidePlayerIds(Set<UUID> ids) {
        activationInsidePlayerIds = Set.copyOf(ids);
    }

    /** Previous signal state enables rising-edge activation without a broad redstone mixin. */
    public boolean previousRedstonePowered() { return previousRedstonePowered; }
    public void setPreviousRedstonePowered(boolean powered) {
        previousRedstonePowered = powered;
    }

    /** Server-thread deadlines pruned when players leave range/disconnect or the phase changes. */
    public Map<UUID, Long> darknessNextTicks() { return darknessNextTicks; }
    /** Reuses one scratch set for aura scans; callers consume it before the next pass. */
    public Set<UUID> beginDarknessPass() {
        darknessNearbyPlayers.clear();
        return darknessNearbyPlayers;
    }

    /** Resets all aura deadlines on phase/reload/unload without removing existing potion effects. */
    public void clearDarknessSchedule() {
        darknessNextTicks.clear();
        darknessNearbyPlayers.clear();
        nextDarknessScanTick = 0;
    }

    /** Next world-game-time deadline for the bounded spatial scan. */
    public long nextDarknessScanTick() { return nextDarknessScanTick; }
    public void setNextDarknessScanTick(long tick) { nextDarknessScanTick = Math.max(0, tick); }

    /** Read-only template snapshot shared by bosses using the same template during reload. */
    private ClockworkBossLoot loot;
    /** Compiled once per template change; read only during the native death path. */
    public ClockworkBossLoot loot() { return loot; }
    public JsonObject config() { return config; }
    /** Publishes config/compiled AI values together; the existing immutable Sonic charge keeps its original definition. */
    public boolean setConfig(JsonObject config) {
        ClockworkBossLoot compiledLoot = ClockworkBossLoot.read(config);
        ClockworkBossSettings compiled = ClockworkBossSettings.read(config);
        var compiledSubphases = ClockworkBossSubphases.read(config, compiled);
        ClockworkBossAreaEffects.Settings compiledAreaEffects = ClockworkBossAreaEffects.Settings.read(config);
        var compiledSupport = ClockworkBossPlayerSupport.read(config);
        var compiledPvp = ClockworkBossPvp.Settings.read(config);
        var compiledFace = ClockworkBossFaceEvents.Settings.read(config);
        Areas compiledAreas = ClockworkBossManager.readAreas(config);
        if (!stopActionForTransition(clockwork.bosses.api.BossAction.StopReason.RELOADED)) return false;
        // Editing a trigger/radius starts a fresh occupancy baseline. Players
        // already inside the new region do not count as a new entry on reload.
        if (areas != null && !areas.equals(compiledAreas)) {
            activationBaselineInitialized = false;
            activationInsidePlayerIds = Set.of();
            previousRedstonePowered = false;
        }
        actionRuntime = null;
        this.config = config;
        this.loot = compiledLoot;
        this.settings = compiled;
        this.subphases = compiledSubphases;
        resetSubphase();
        this.areaEffects = compiledAreaEffects;
        this.playerSupport = compiledSupport;
        this.pvpProtection = compiledPvp;
        this.music = ClockworkBossMusic.Settings.read(config);
        face.configure(compiledFace);
        this.areas = compiledAreas;
        double radius = compiledAreas.activationRadius();
        activationRadiusBox = new AABB(emergenceCenter.x - radius, emergenceCenter.y - radius, emergenceCenter.z - radius,
                emergenceCenter.x + radius, emergenceCenter.y + radius, emergenceCenter.z + radius);
        endVoiceLine();
        return true;
    }
    /** Prevalidated primitives avoid JSON traversal on every native AI step. */
    public ClockworkBossSettings settings() { return settings; }
    /** Area buffs are compiled once, then consumed only when viewers enter/leave or templates reload. */
    public ClockworkBossAreaEffects.Settings areaEffects() { return areaEffects; }
    public ClockworkBossPlayerSupport playerSupport() { return playerSupport; }
    /** Damage/effect boundaries read this compiled flag, never template JSON. */
    public ClockworkBossPvp.Settings pvpProtection() { return pvpProtection; }
    /** Music is compiled on config changes, then compared only during the existing arena membership scan. */
    public ClockworkBossMusic.Settings music() { return music; }
    /** Native combat events and the existing watcher share this controller.
     * Its rules are compiled; mutable poses/deadlines belong to this state. */
    public ClockworkBossFaceEvents face() { return face; }
    /** Immutable native boxes/keys keep the ten-tick checks free of JSON parsing and box reconstruction. */
    public Areas areas() { return areas; }
    /** Cached fixed-radius query avoids rebuilding a box on every dormant watcher check. */
    public Vec3 emergenceCenter() { return emergenceCenter; }
    public AABB activationRadiusBox() { return activationRadiusBox; }
    /** Missing dimensions use the boss's world for activation/radius visibility; fixed visibility boxes require a dimension. */
    public record Areas(String activationType, ResourceKey<Level> activationDimension,
                        AABB activationBox, double activationRadius, BlockPos redstonePosition,
                        ResourceKey<Level> visibilityDimension, AABB visibilityBox,
                        boolean visibilityUsesRadius, double visibilityRadius, boolean visibilityConfigured) {}
    /** Owned vanilla bar and UUID membership snapshot; reconcile only actual viewer changes. */
    public ServerBossEvent bossBar() { return bossBar; }
    public Set<UUID> bossBarPlayerIds() { return bossBarPlayerIds; }
    public void setBossBarPlayerIds(Set<UUID> ids) { bossBarPlayerIds = Set.copyOf(ids); }
    /** Null means first assignment; first assignment does not emit transition effects. */
    public ClockworkBossPhase currentPhase() { return currentPhase; }
    public boolean setCurrentPhase(ClockworkBossPhase phase) {
        if (!stopActionForTransition(clockwork.bosses.api.BossAction.StopReason.PHASE_CHANGED)) return false;
        actionRuntime = null; currentPhase = phase; resetSubphase();
        return true;
    }

    /** One complete captured beam until its owning action is interrupted.
     * Phase transitions/reloads stop that action and cancel its pending damage. */
    public ClockworkBossSonicCharge sonicCharge() { return sonicCharge; }
    public void startSonicCharge(UUID target, long now, ClockworkBossSettings.Phase phase) {
        sonicCharge = ClockworkBossSonicCharge.start(target, now, phase, subphaseGeneration,
                phase.meleeEnabled() && (actionRuntime == null || actionRuntime.allows(ClockworkBossActions.MELEE)));
    }

    /** Clears only custom charge state; the native brain remains the authority for cooldown. */
    public void resetSonicBoomCharge() {
        sonicCharge = null;
    }
}
