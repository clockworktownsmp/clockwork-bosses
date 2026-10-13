package clockwork.bosses;

import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

/** Small recovery journal for the core burrow action. A chunk may be saved before
 * its unload callback runs, so scoped cleanup alone cannot restore a saved hidden
 * position after a restart. Origin is the latest committed surface: the starting
 * position until emergence, then the selected destination. No live world/entity
 * reference enters this journal. */
public record ClockworkBossBurrowCheckpoint(Vec3 origin, String dimension,
                                          boolean invisible, boolean noGravity, boolean silent) {
    public static ClockworkBossBurrowCheckpoint capture(Warden boss) {
        return new ClockworkBossBurrowCheckpoint(boss.position(), boss.level().dimension().identifier().toString(),
                boss.isInvisible(), boss.isNoGravity(), boss.isSilent());
    }
    /** Once a safe emergence is committed, cleanup/restart must retain that new
     * surface, rather than undoing the relocation at normal action completion. */
    public ClockworkBossBurrowCheckpoint atSurface(Vec3 position) {
        return new ClockworkBossBurrowCheckpoint(position, dimension, invisible, noGravity, silent);
    }
    /** Restore only in the original, already-loaded chunk; never load chunks as
     * part of cleanup. Flags are restored even after native removal began. */
    public void restore(Warden boss) {
        if (dimension.equals(boss.level().dimension().identifier().toString())
                && boss.level().getChunkSource().hasChunk(((int) Math.floor(origin.x)) >> 4, ((int) Math.floor(origin.z)) >> 4))
            boss.snapTo(origin.x, Math.clamp(origin.y, boss.level().getMinY(), boss.level().getMaxY() - 1),
                    origin.z, boss.getYRot(), boss.getXRot());
        boss.setInvisible(invisible); boss.setNoGravity(noGravity); boss.setSilent(silent);
        boss.getBrain().eraseMemory(MemoryModuleType.IS_EMERGING);
        if (boss.hasPose(Pose.DIGGING) || boss.hasPose(Pose.EMERGING)) boss.setPose(Pose.STANDING);
        ((ClockworkBossMarker) boss).clockworkBosses$setBurrowCheckpoint(null);
    }
    public void write(ValueOutput output) {
        output.putBoolean("ClockworkBosses_ActionBurrow", true);
        output.putDouble("ClockworkBosses_BurrowX", origin.x);
        output.putDouble("ClockworkBosses_BurrowY", origin.y);
        output.putDouble("ClockworkBosses_BurrowZ", origin.z);
        output.putString("ClockworkBosses_BurrowDimension", dimension);
        output.putBoolean("ClockworkBosses_BurrowInvisible", invisible);
        output.putBoolean("ClockworkBosses_BurrowNoGravity", noGravity);
        output.putBoolean("ClockworkBosses_BurrowSilent", silent);
    }
    public static ClockworkBossBurrowCheckpoint read(ValueInput input) {
        if (!input.getBooleanOr("ClockworkBosses_ActionBurrow", false)) return null;
        double x = input.getDoubleOr("ClockworkBosses_BurrowX", Double.NaN);
        double y = input.getDoubleOr("ClockworkBosses_BurrowY", Double.NaN);
        double z = input.getDoubleOr("ClockworkBosses_BurrowZ", Double.NaN);
        String dimension = input.getStringOr("ClockworkBosses_BurrowDimension", "");
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || Math.abs(x) > 30_000_000 || Math.abs(y) > 30_000_000 || Math.abs(z) > 30_000_000
                || Identifier.tryParse(dimension) == null) return null;
        return new ClockworkBossBurrowCheckpoint(new Vec3(x, y, z), dimension,
                input.getBooleanOr("ClockworkBosses_BurrowInvisible", false),
                input.getBooleanOr("ClockworkBosses_BurrowNoGravity", false),
                input.getBooleanOr("ClockworkBosses_BurrowSilent", false));
    }
}
