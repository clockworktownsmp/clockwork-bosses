package clockwork.bosses;

import net.minecraft.sounds.SoundSource;
import java.util.Arrays;
import java.util.List;

/** Native sound-category names are shared by config, GUI and the negotiated wire format. */
public final class ClockworkBossMusicChannels {
    private ClockworkBossMusicChannels() {}
    public static final List<String> NAMES = Arrays.stream(SoundSource.values()).map(SoundSource::getName).toList();
    public static SoundSource require(String name) {
        for (SoundSource source : SoundSource.values()) if (source.getName().equals(name)) return source;
        throw new IllegalArgumentException("Unknown music channel");
    }
    /** Missing/invalid hand-edited settings retain the previous Music-category behavior. */
    public static SoundSource orDefault(String name) {
        try { return require(name); } catch (IllegalArgumentException invalid) { return SoundSource.MUSIC; }
    }
}
