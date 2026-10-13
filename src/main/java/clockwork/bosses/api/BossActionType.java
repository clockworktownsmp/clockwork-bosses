package clockwork.bosses.api;

import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.resources.Identifier;

/** Registration is immutable; its factory creates isolated runtime instances.
 * IDs deliberately use flat lowercase names so field paths remain unambiguous. */
public record BossActionType(Identifier id, String label, String help, List<BossSetting> settings,
                             Set<Identifier> requiredCapabilities, Supplier<? extends BossAction> factory) {
    public BossActionType {
        if (id == null || !id.getPath().matches("[a-z][a-z0-9_-]{0,63}")
                || !id.getNamespace().matches("[a-z][a-z0-9_]{0,63}") || label == null || label.isBlank()
                || label.length() > 64 || help == null || help.length() > 512 || factory == null)
            throw new IllegalArgumentException("Invalid action registration");
        settings = List.copyOf(settings); requiredCapabilities = Set.copyOf(requiredCapabilities);
        if (settings.size() > 16 || requiredCapabilities.size() > 16
                || settings.stream().map(BossSetting::key).distinct().count() != settings.size())
            throw new IllegalArgumentException("Use at most 16 unique settings/capabilities per action");
        settings.forEach(field -> field.parse(field.defaultValue()));
    }
}
