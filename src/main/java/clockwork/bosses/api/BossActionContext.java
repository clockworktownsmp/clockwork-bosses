package clockwork.bosses.api;

import java.util.List;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/** Live, server-thread-only access for one action. Never retain this context in a
 * static collection or worker job. Live access rejects a different thread;
 * mutating helpers also reject use after interruption. Immutable settings may
 * be copied, and an immutable snapshot may be captured on the server thread. */
public interface BossActionContext {
    BossMobController controller();
    BossActionSettings settings();
    BossEncounterSnapshot encounter();
    ServerLevel level();
    long gameTime();
    long elapsedTicks();
    /** Current native target, checked for life, dimension and encounter eligibility. */
    Optional<LivingEntity> target();
    /** Query the core's current arena membership; no chunks are loaded. */
    List<ServerPlayer> participants();
    boolean isParticipant(ServerPlayer player);
    /** Callbacks run in reverse acquisition order after stop, including a failed
     * start. Register restoration before changing a temporary flag/modifier. */
    void onCleanup(Runnable cleanup);
    /** Adopt an entity created by this action, preferably before adding it to the
     * world. This disables its native persistence and discards it on cleanup if
     * still loaded; an earlier chunk unload cannot leave a saved orphan. Never
     * adopt players, bosses, another action's entity or somebody else's existing
     * mob. At most 64 resources per execution; ownership cannot be transferred.
     * Owned entities cannot use portals or teleport to another dimension: native
     * dimension transfer creates a replacement outside the original cleanup scope. */
    void ownEntity(Entity entity);
    /** Apply native server damage to an eligible participant, report accepted hits,
     * and reuse core target rotation/voice handling. This does not bypass armor,
     * other mods' damage vetoes, or the player's native invulnerability. */
    boolean damage(ServerPlayer target, DamageSource source, float amount);
}
