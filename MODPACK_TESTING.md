# Supplied server-pack testing - 0.1

These isolated checks verify the tested server logic and production-jar startup.
The actual matching clients, resource packs and multiplayer network conditions
still need an encounter test.

## Development source: potion death and grave recovery — 2026-10-11

The supplied Universal Graves 3.13.0+26.3 saved temporary potion copies before
the prior source's `AFTER_DEATH` cleanup. A regression reproduced this in an
actual grave. Cleanup now precedes native death loot, so the grave captures the
restored personal offhand and ordinary potion without any fight bottle.
Native respawn and grave `quickEquip` recovered those personal items exactly
once after the boss was removed. Test-only reflection uses the grave's public
API; the release source imports no grave classes or optional dependency.

All 33 native tests passed with the isolated supplied pack and without it;
common/client compilation and 5,013 offline assertions also passed. Early pack
JVMs stalled during shutdown after passing assertions. Their thread dumps are
retained. The test fixture's checkpoint was occupied by its new grave, causing
native respawn to fall back to world spawn. Using and asserting a separate
checkpoint in the loaded test area fixed the fixture; all 33 tests passed and
shut down normally both in the supplied pack and a narrower exact
Graves/Lithium/Polymer run. No gameplay chunk-ticket workaround was needed.
Existing service/Discord exclusions below still apply.
No jar was packaged/deployed and no installed files were changed.

## Recorded 0.1 results - 2026-10-10

| Check | Recorded result |
| --- | --- |
| Server GameTests with supplied pack | 28/28 passed |
| Server GameTests without pack | 28/28 passed |
| Config/media audit | 4384 assertions passed |
| Separate GUI run with Sodium, Lithium, ETF and EMF | 1038 assertions passed |
| Packaged production jar | Readiness, spawn/activate/save/remove, normal shutdown code 0 |

Reports in the original development workspace:

- `../work/release01-modpack.xml`
- `../work/release01-native.xml`
- `../work/release01-production-result.json`
- `../work/release01-results.json`

Production fixture: `../work/release01-modpack-release-server`.
These paths are outside version control and may be absent in a clone.

The isolated checks preserved installed data. A subsequent recorded deployment
installed matching 0.1 jars in Test Client/test server, with RC8 backups and
verified hashes in `../work/release01-deployment.json`. Templates/media/worlds
were preserved. This does not establish current running status.

## Scope and remaining work

The server ZIPs are not a full client pack. Real multiplayer latency, full-pack
client play, heap profiling and performance benchmarks were not covered.
The exclusions, fixture adjustments and known nonfatal messages below also
apply to 0.1. BMarker save warnings remain in the logs.

A passing suite does not mean every pack feature or external service passed.
[Compatibility testing](COMPATIBILITY_TESTING.md) has scenarios for actual
clients, resource packs, equipment, graves and combat integrations.
## Historical RC6 checks

Tested on 2026-10-09 with the original `mods (1).zip` and `config.zip`.
All 136 top-level jar hashes and all 306 original config files matched the
provided archives before test-only overrides. Archive hashes and release results:
`../work/rc6-modpack-results.json`. At this point RC5 was installed and RC6 was
a local build.

## RC6 results

| Check | Result | Evidence |
|---|---|---|
| Server pack / native combat and support | 16 project GameTests plus vanilla control passed | `../work/rc6-modpack-gametest-final.log/.xml` |
| Without third-party pack | Same 17 tests passed | `../work/rc6-native-regression-final.log/.xml` |
| Offline audit | 4316 assertions passed | `../work/rc6-modpack-gametest-final.log` and `../work/rc6-native-regression-final.log` |
| Actual RC6 universal jar / production server | Reached readiness; spawned, activated, saved and removed boss; exited normally with code 0 | `../work/rc6-modpack-release-startup.log`, `../work/rc6-modpack-release-result.json` |

The production launch uses the release jar, provided mod jars and configured
Java 25/Fabric Loader 0.19.5 libraries. It excludes development source directories
and regression mods and sets `fabric.development=false`. The API matches the
provided pack: Fabric API 0.161.0+26.3. The disposable origin chunk is forced
loaded during console commands, then its ticket is removed before shutdown.

These are isolated server checks. A multiplayer fight with the actual matching
client, latency, resource packs, rendered skins/glow, music and UI remains to be
tested. The server archive is not evidence of a complete matching client pack.

## Isolation and scope

GameTests use `../work/rc5-modpack-server`; the final production launch uses
`../work/rc6-modpack-release-server-final`. No installed client, remote server,
production world, permissions database or game settings were replaced.

- The Discord bridge (`amcdb` 1.4.0) and its credential config are excluded: it
  needs a live Discord connection. Its integration is not tested.
- In GameTests, the identical Fabric API comes from Loom's runtime. The production
  launch uses the original API jar alongside the release jar.
- BlueMap downloads/metrics/web serving, Polymer hosting and AudioPlayer web
  hosting are disabled. Collective update checking is disabled. Their enabled
  network-service configurations are not tested.
- LuckPerms uses a separate local H2 store without external messaging/transports.
- Production test game/voice ports bind to loopback at 25592/25593; GameTest uses
  its own ephemeral server/voice ports. No RCON endpoint is enabled.

The exact staged jar inventory and overrides are recorded in
`../work/rc5-modpack-environment.json`. Setup/launch scripts are under `../work/`:
`prepare-rc5-modpack.py`, `rc5-modpack-check.init.gradle`,
`export-pack-server.init.gradle`, `run-pack-release-smoke.py`.

## What the first failures revealed

The initial three failures involved embedded test players. They skip the network
login/configuration stages used by real clients:

- LuckPerms rejected unloaded users. Fixture setup now loads users through its
  public API with a bounded wait; its login protection remains enabled.
- StrawberryLib/Cardinal Components rejected an undeclared entity-sync channel.
  Fixtures supply Fabric's pending channel state before listener setup. This
  simulates a matching client for server logic; it does not decode real packets.
- Stackable Totems (`mr_stackable_totems` 1-v1.2.0) uses an inventory advancement
  to clear capacity-1 totems and give capacity-64 replacements. Ownership tests
  now start with pack-compatible totems and compare immutable expected contents.
  A separate ordinary-totem check verifies that transfer/cleanup preserves exactly
  one totem while that external conversion runs.

The minimal Test Client may be rejected by this pack's required networking mods.
Use a matching client for the real multiplayer encounter. Client-only jars in
the server archive are filtered by Fabric on dedicated-server launches.

## Offhand safeguards retained in 0.1

The reusable potion now moves held contents with native `Inventory.add`, which
consumes the input. This avoids sharing a mutable item between inventory and
offhand. Failed/partial transfers or a newly occupied offhand prevent the gift.
Ownership records snapshot the actual stored contents, not a live item object.

Cleanup requires the recorded slot, same item/count and remaining components,
and an empty offhand. Only stack capacity may differ, accommodating storage
normalization by the totem pack. Cleanup removes and transfers the live slot
contents, retaining their current data; the snapshot is never issued to a player.
Changed counts, other component changes, moved items and a new personal offhand
remain untouched. There is no inventory-wide search for the original item.

Native tests cover a copied stack with altered capacity, changed custom name,
changed count, movement to another slot, an occupied offhand, and ordinary totem
conversion. They assert empty source slots or unchanged remaining items/counts
as appropriate, plus removal of extra marked bottles.

## Remaining pack messages

The logs also contain non-fatal Windows system-report failures, ModernFix/Lithium
temperature-cache overlap, Enhanced Groups auto-join loading errors, missing
Core Lib language resources, invalid resource-path messages and BMarker save
failures. BlueMap's missing-download warning is expected from the deliberate
download restriction. The synthetic player/chunk tests also produced a Polymer
packet-context warning. These messages are retained in the logs and are not
covered by a claim that the entire pack is error-free.

The checks above passed and the release server shut down normally. No unrelated
pack config/mod repairs or live Discord/service tests were performed.
