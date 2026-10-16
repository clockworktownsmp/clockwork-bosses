package clockwork.bosses.api;

import clockwork.bosses.ClockworkBossManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.warden.Warden;

/** Experimental live encounter operations. Call on the owning server thread;
 * never retain a mob/player in a worker or schedule a captured live context. */
public final class BossEncounters {
    private BossEncounters() {}
    /** Request an eligible arena player, without clearing/reselecting a target.
     * The same eligible entity instance is a no-op. A respawn/rejoin replacement
     * with the same UUID still needs native assignment. The Warden backend retains its
     * existing hold, native assignment, telegraph and cooldown rules. */
    public static BossTargetRequest requestTarget(Mob boss, ServerPlayer target) {
        if (!(boss.level() instanceof ServerLevel level) || !level.getServer().isSameThread())
            throw new IllegalStateException("Target requests require the owning server thread");
        return boss instanceof Warden warden ? ClockworkBossManager.requestTarget(warden, target) : BossTargetRequest.UNSUPPORTED;
    }
}
