package clockwork.bosses;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.warden.Warden;

/** One sound-volume rule for server entity sounds and the client's native heartbeat. */
public final class ClockworkBossMobSounds {
    private ClockworkBossMobSounds() {}

    /** Only marked Wardens' minecraft:entity.warden.* events are changed; custom OGG playback is separate. */
    public static float volume(Entity entity, SoundEvent sound, float original) {
        if (!(entity instanceof Warden) || !((ClockworkBossMarker) entity).clockworkBosses$isClockworkBossSynced()) return original;
        var id = sound.location();
        if (!id.getNamespace().equals("minecraft") || !id.getPath().startsWith("entity.warden.")) return original;
        return original * ((ClockworkBossMarker) entity).clockworkBosses$getMobSoundVolume();
    }
}
