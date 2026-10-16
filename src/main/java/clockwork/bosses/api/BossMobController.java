package clockwork.bosses.api;

import java.util.Set;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Mob;

/** Mob boundary for action authors. Capabilities describe real adapter support;
 * requiring an animation capability must not imply every mob implements it.
 * The first core adapter is Warden-only; arbitrary mob registration is not yet public. */
public interface BossMobController {
    Mob mob();
    Set<Identifier> capabilities();
    default boolean supports(Identifier capability) { return capabilities().contains(capability); }
    /** Optional server-thread-only target boundary. Existing adapters remain
     * unsupported until they implement their own native policy. */
    default BossTargetRequest requestTarget(net.minecraft.server.level.ServerPlayer target) {
        if (!(mob().level() instanceof net.minecraft.server.level.ServerLevel level) || !level.getServer().isSameThread())
            throw new IllegalStateException("Target requests require the owning server thread");
        return BossTargetRequest.UNSUPPORTED;
    }
    /** Request one native roar from start(), requiring clockwork_bosses:roar and
     * an action that leaves native behavior execution enabled. The controller
     * waits for a valid target/recoil and owns interruption cleanup. */
    default void startRoar(BossRoarSound sound) { throw new UnsupportedOperationException("Mob does not support roar"); }
    default boolean roarFinished() { throw new UnsupportedOperationException("Mob does not support roar"); }
    /** Request one native burrow/emergence from start(), requiring
     * clockwork_bosses:burrow and a paused native brain. Bounds: 1–12000 ticks,
     * depth 1–32 blocks. The Warden backend emerges near its eligible player target
     * using loaded, collision-free terrain, or at its original surface when no
     * safe destination exists. It owns pose/flags/surface-position restoration;
     * native attacks resume when the sequence selects a combat action. */
    default void startBurrow(int undergroundTicks, double depth) { throw new UnsupportedOperationException("Mob does not support burrow"); }
    default boolean burrowFinished() { throw new UnsupportedOperationException("Mob does not support burrow"); }
}
