package clockwork.bosses;

import com.google.gson.JsonObject;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.effect.ServerMobEffectEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.TraceableEntity;

/** Event-time friendly-fire policy. The encounter manager owns arenas/settings;
 * this class owns no membership cache, scheduled work or retained player map. */
public final class ClockworkBossPvp {
    private ClockworkBossPvp() {}

    /** New files explicitly enable protection. Missing/null legacy sections do
     * not change existing encounters merely because their server was upgraded. */
    public record Settings(boolean enabled) {
        public static Settings read(JsonObject template) {
            var bar = template.get("boss_bar");
            var section = bar != null && bar.isJsonObject() ? bar.getAsJsonObject().get("pvp_protection") : null;
            var value = section != null && section.isJsonObject() ? section.getAsJsonObject().get("enabled") : null;
            return new Settings(value != null && value.isJsonPrimitive()
                    && value.getAsJsonPrimitive().isBoolean() && value.getAsBoolean());
        }
    }

    // Fabric's effect context identifies commands but does not expose addEffect's
    // source entity. Carry only the exact call's recipient/instance/owner into
    // ALLOW_ADD. A synchronous nested call restores its outer scope in finally.
    // Nothing survives the operation, including a callback which throws.
    private record EffectSource(ServerPlayer recipient, MobEffectInstance effect, UUID owner) {}
    private static final ThreadLocal<EffectSource> EFFECT_SOURCE = new ThreadLocal<>();

    public static void register() {
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((victim, source, amount) -> !blocks(victim, source));
        ServerMobEffectEvents.ALLOW_ADD.register((effect, recipient, context) -> {
            if (context.isFromCommand() || effect.getEffect().value().getCategory() != MobEffectCategory.HARMFUL) return true;
            var source = EFFECT_SOURCE.get();
            return source == null || source.recipient() != recipient || source.effect() != effect
                    || !blocks(source.recipient(), source.owner());
        });
        // Capture a known player owner once when a projectile/cloud/TNT loads.
        // Its native entity then owns a UUID, allowing attribution after logout
        // without keeping the player alive or scanning entities on disconnect.
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (entity instanceof ClockworkBossPlayerHarmSource source) source.clockworkBosses$playerOrigin();
        });
    }

    /** One damage attempt, before armor and other native damage side effects.
     * A specified causing entity is authoritative; direct-source fallback is
     * needed when a projectile's player has disconnected and cannot resolve. */
    public static boolean blocks(LivingEntity victim, DamageSource source) {
        if (!(victim instanceof ServerPlayer player)) return false;
        return blocks(player, playerOrigin(source.getEntity() == null ? source.getDirectEntity() : source.getEntity()));
    }

    /** Shared by collision/effect hooks. Non-player and self-inflicted harm are
     * deliberately outside PvP; boss attacks and ordinary hazards stay native. */
    public static boolean blocks(Entity victim, Entity source) {
        return victim instanceof ServerPlayer player && blocks(player, playerOrigin(source));
    }

    private static boolean blocks(ServerPlayer victim, UUID attackerId) {
        if (attackerId == null || attackerId.equals(victim.getUUID())) return false;
        if (ClockworkBossManager.hasPvpProtection(victim)) return true;
        var attacker = victim.level().getServer().getPlayerList().getPlayer(attackerId);
        return attacker != null && ClockworkBossManager.hasPvpProtection(attacker);
    }

    /** Follow native ownership, not arbitrary damage IDs or last-hurt-player
     * guesses. Bound addon-created owner cycles; resolution loads no chunks. */
    public static UUID playerOrigin(Entity source) {
        for (int depth = 0; source != null && depth < 8; depth++) {
            if (source instanceof ServerPlayer player) return player.getUUID();
            // Channeling records its player on the bolt, while thunderHit's
            // native DamageSource omits both direct and causing entities.
            if (source instanceof LightningBolt bolt && bolt.getCause() != null) return bolt.getCause().getUUID();
            if (source instanceof ClockworkBossPlayerHarmSource known) {
                var player = known.clockworkBosses$playerOrigin();
                if (player != null) return player;
            }
            if (source instanceof TraceableEntity traceable) source = traceable.getOwner();
            else if (source instanceof OwnableEntity ownable) source = ownable.getOwner();
            else return null;
        }
        return null;
    }

    /** Bridge native attribution to Fabric's cancellable effect event. Helpful
     * effects bypass the bridge; commands retain their explicit native authority. */
    public static boolean withEffectSource(LivingEntity recipient, MobEffectInstance effect, Entity cause, BooleanSupplier original) {
        if (!(recipient instanceof ServerPlayer player) || effect.getEffect().value().getCategory() != MobEffectCategory.HARMFUL)
            return original.getAsBoolean();
        var previous = EFFECT_SOURCE.get();
        EFFECT_SOURCE.set(new EffectSource(player, effect, playerOrigin(cause)));
        try { return original.getAsBoolean(); }
        finally { if (previous == null) EFFECT_SOURCE.remove(); else EFFECT_SOURCE.set(previous); }
    }
}
