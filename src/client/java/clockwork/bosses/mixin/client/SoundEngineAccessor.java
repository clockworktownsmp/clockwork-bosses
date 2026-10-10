package clockwork.bosses.mixin.client;

import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundEngineExecutor;
import net.minecraft.sounds.SoundSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Exposes the vanilla-owned device, channel pool and gains for bounded server OGG effects and streaming music. */
@Mixin(SoundEngine.class)
public interface SoundEngineAccessor {
    /** Music must wait until native device initialization/reload has completed. */
    @Accessor("loaded")
    boolean clockworkBosses$isLoaded();
    /** Borrow Minecraft's pool; sources still follow its native release/scheduleTick path. */
    @Accessor("channelAccess")
    ChannelAccess clockworkBosses$getChannelAccess();
    /** Audio objects must be released on the same native executor that uploads them. */
    @Accessor("executor")
    SoundEngineExecutor clockworkBosses$getExecutor();

    /** Includes native master/category sliders and temporary category gain changes. */
    @Invoker("calculateVolume")
    float clockworkBosses$calculateVolume(float volume, SoundSource source);
}

