package clockwork.bosses.api;

/** Declare a "clockwork_bosses" entrypoint in fabric.mod.json. Registration runs
 * once before templates are loaded, after core registrations, and freezes after
 * all addons finish. No core source edit is required to install an addon. */
@FunctionalInterface
public interface BossAddon {
    /** Experimental contract revision, independent of the mod's release number. */
    int API_REVISION = 1;
    void register(BossActionRegistry.Registrar registry);
}
