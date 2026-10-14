package clockwork.bosses.mixin;

import clockwork.bosses.ClockworkBossPlayerHarmSource;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.TraceableEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** These three native 26.3 classes share an EntityReference owner and save hooks.
 * One UUID belongs to the source entity, not a global map or a player reference.
 * It is saved in server NBT only, so vanilla clients need no custom metadata. */
@Mixin({Projectile.class, AreaEffectCloud.class, PrimedTnt.class})
public abstract class PlayerHarmSourceMixin implements ClockworkBossPlayerHarmSource {
    // All three 26.3 classes use this exact unobfuscated field name/type. Mixin
    // forbids remappable shadows in a multi-target mixin; no mapping is needed.
    @Shadow(remap = false) private EntityReference<?> owner;
    @Unique private UUID clockworkBosses$playerOrigin;

    @Override public UUID clockworkBosses$playerOrigin() {
        Entity entity = (Entity) (Object) this;
        if (entity.level().isClientSide()) return null;
        var nativeId = owner == null ? null : owner.getUUID();
        if (clockworkBosses$playerOrigin != null && !clockworkBosses$playerOrigin.equals(nativeId))
            clockworkBosses$playerOrigin = null; // Deflection/reassignment cannot retain an old identity.
        if (((TraceableEntity) entity).getOwner() instanceof ServerPlayer player)
            clockworkBosses$playerOrigin = player.getUUID();
        return clockworkBosses$playerOrigin;
    }

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void clockworkBosses$savePlayerOrigin(ValueOutput output, CallbackInfo callback) {
        var id = clockworkBosses$playerOrigin();
        if (id != null) output.store("clockwork_bosses_player_origin", UUIDUtil.CODEC, id);
    }
    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void clockworkBosses$readPlayerOrigin(ValueInput input, CallbackInfo callback) {
        clockworkBosses$playerOrigin = input.read("clockwork_bosses_player_origin", UUIDUtil.CODEC).orElse(null);
        clockworkBosses$playerOrigin(); // Validate against the native owner just read.
    }
}
