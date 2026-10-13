package clockwork.bosses;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** One bounded search when a burrow ends. Minecraft owns collision, terrain and
 * hazard classification; there is no path search, chunk loading or recurring
 * destination scan. Standing dimensions include the boss's configured scale. */
final class ClockworkBossBurrowDestination {
    private static final int DIRECTIONS = 8;
    private static final int[] FLOOR_OFFSETS = {0, 1, -1, 2, -2};
    private ClockworkBossBurrowDestination() {}

    /** Try two close rings around the player's latest position, with enough
     * separation for both bodies. At most eighty floor candidates are visited;
     * null means the caller must use its saved surface position. */
    static Vec3 find(Warden boss, ClockworkBossState state, ServerPlayer target, Vec3 surface) {
        ServerLevel level = (ServerLevel) boss.level();
        var dimensions = boss.getDimensions(Pose.STANDING);
        double distance = Math.max(2.5, (dimensions.width() + target.getBbWidth()) * .5 + .75);
        double angle = boss.getRandom().nextDouble() * Math.PI * 2;
        int floorY = (int) Math.floor(target.getY()) - 1;
        var terrain = new PathfindingContext(level, boss);
        for (int ring = 0; ring < 2; ring++) {
            double radius = distance + ring * 1.5;
            for (int direction = 0; direction < DIRECTIONS; direction++) {
                double heading = angle + direction * Math.PI * 2 / DIRECTIONS;
                double x = target.getX() + Math.cos(heading) * radius;
                double z = target.getZ() + Math.sin(heading) * radius;
                for (int offset : FLOOR_OFFSETS) {
                    BlockPos floor = BlockPos.containing(x, floorY + offset, z);
                    // Check a halo before any block/path/collision access: native
                    // hazard checks inspect neighbors, including chunk edges.
                    AABB possible = dimensions.makeBoundingBox(x, floor.getY() + 1, z).inflate(2);
                    if (!loaded(level, possible) || floor.getY() < level.getMinY()
                            || possible.maxY >= level.getMaxY()) continue;
                    var shape = level.getBlockState(floor).getCollisionShape(level, floor);
                    if (shape.isEmpty()) continue;
                    Vec3 position = new Vec3(x, floor.getY() + shape.max(Direction.Axis.Y), z);
                    AABB body = dimensions.makeBoundingBox(position);
                    if (!insideArena(state, level, target, position, surface) || body.minY < level.getMinY()
                            || body.maxY > level.getMaxY() || !level.getWorldBorder().isWithinBounds(body)) continue;
                    if (WalkNodeEvaluator.getPathTypeStatic(terrain, BlockPos.containing(position).mutable()) != PathType.WALKABLE)
                        continue; // Reject liquids, fire, damaging floors and nearby native hazards.
                    if (!level.noCollision(boss, body) || level.containsAnyLiquid(body)
                            || level.noBlockCollision(boss, body.move(0, -.0625, 0))) continue;
                    return position;
                }
            }
        }
        return null;
    }

    /** A fixed arena limits destinations; a moving radius is anchored at the
     * saved surface while underground, then follows the new surface position. */
    private static boolean insideArena(ClockworkBossState state, ServerLevel level, ServerPlayer target,
                                       Vec3 position, Vec3 surface) {
        var areas = state.areas();
        if (areas.visibilityDimension() != null && !areas.visibilityDimension().equals(level.dimension())) return false;
        if (areas.visibilityUsesRadius()) {
            double squaredRadius = areas.visibilityRadius() * areas.visibilityRadius();
            // A tiny moving radius must still contain its target after relocation.
            return position.distanceToSqr(surface) <= squaredRadius
                    && position.distanceToSqr(target.position()) <= squaredRadius;
        }
        return areas.visibilityBox() != null && areas.visibilityBox().contains(position);
    }

    private static boolean loaded(ServerLevel level, AABB box) {
        for (int x = ((int) Math.floor(box.minX)) >> 4; x <= ((int) Math.floor(box.maxX)) >> 4; x++)
            for (int z = ((int) Math.floor(box.minZ)) >> 4; z <= ((int) Math.floor(box.maxZ)) >> 4; z++)
                if (!level.getChunkSource().hasChunk(x, z)) return false;
        return true;
    }
}
