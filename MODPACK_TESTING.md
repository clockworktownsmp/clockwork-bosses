# Supplied server pack — 0.1

## Version 0.1 — 2026-10-10

All 28 server GameTests passed in the isolated supplied pack and without the
pack. The packaged 0.1 production jar reached normal readiness, spawned and
activated a boss, saved and removed it, then shut down normally with exit code 0.
Reports: `../work/release01-modpack.xml`, `../work/release01-native.xml`,
`../work/release01-production-result.json`, `../work/release01-results.json`.
The native GUI run separately passed 1038 assertions with Sodium, Lithium, ETF
and EMF. Audit checks passed 4384 assertions.

Production fixture: `../work/release01-modpack-release-server`. Existing pack
isolation and network-service exclusions below still apply. Original server
ZIPs are not a full client pack; real multiplayer latency/full-pack client play
and heap/performance profiling are not covered. Existing known nonfatal pack
messages, including BMarker save warnings, remain in saved logs.
The isolated checks did not modify installed data. A subsequent authorized
deployment installed matching 0.1 jars in Test Client and the test server, with
RC8 backups and verified hashes in `../work/release01-deployment.json`. Existing
templates, media and worlds were preserved.

## Historical RC6 checks

Tested on 2026-10-09 with the original `mods (1).zip` and `config.zip`.
All 136 top-level jar hashes and all 306 original config files matched the
provided archives before test-only overrides. Archive hashes and release results:
`../work/rc6-modpack-results.json`. RC5 remains installed; RC6 is a local build.

## Results

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

## RC6 offhand ownership safeguards

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
