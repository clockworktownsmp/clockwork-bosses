# Independent lightning addon

This example uses experimental Clockwork Bosses API revision 1 for Minecraft
26.3. It requires the new development core build; the previously deployed 0.1
jar has no action API. Source imports only `clockwork.bosses.api` and native
Minecraft classes. No core edit is needed to register this mechanic.

## Package

From the core project, using Java 25:

```powershell
.\gradlew.bat jar sourcesJar
.\gradlew.bat -p examples/lightning-addon jar sourcesJar
```

These commands compile/package without running tests. The example reads
`mod_version` and `archives_base_name` from the core's `gradle.properties` and
uses that exact jar in `../../build/libs/`. For the current core this is
`clockwork_bosses-0.2.0.jar`; a missing matching jar stops the build rather
than falling back to an older file.

Addon `0.1.3` also writes that exact core version into its runtime dependency.
The API is experimental, so the example does not claim that a different core
version is compatible. After changing the core version, build the core and then
rebuild the addon. It creates `clockwork_lightning_example-0.1.3.jar` in
`examples/lightning-addon/build/libs/`. The addon and its source are not bundled
into the core jar.

## Try later in an isolated instance

Install the latest development core and example addon on the server. Install a
matching core client for the editor and enhanced boss visuals; the example needs
no addon client installation. The optional-client server source also lets players
without the core client see its native warning particles and lightning. That
mixed-client runtime behavior remains untested. Start the server, edit a template
from a modded client, add a subphase and choose **Example lightning**.
Configure warning time, damage and current/random participant selection. Save
and reload the template. Normal sequence/roar controls still apply.

```json
"subphases": {
  "lightning": {
    "type": "clockwork_lightning_example:lightning",
    "action_settings": {
      "warning_ticks": 40,
      "damage": 8,
      "target_mode": "current"
    }
  }
}
```

The action pauses native combat, warns a still-eligible player, then shows one
visual-only bolt and attempts native mob damage through the API. It creates no
fire and releases its temporary bolt on completion or interruption. It retains
a target UUID instead of a player entity. It also demonstrates an activation
event listener using an immutable snapshot.

In-game behavior and cleanup still need a playtest. Use the addon section of
[the in-game checklist](../../PLAYTEST_CHECKLIST.md#lightning-example-addon).
The example is installed separately on the server; a matching core client can
edit its advertised settings without installing the addon on that client.
