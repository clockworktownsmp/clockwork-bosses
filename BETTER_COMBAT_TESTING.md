# Better Combat compatibility — 0.3.0-beta.2

## Scope and versions

This is basic compatibility, not a new combat API. Existing PvP protection,
server damage, native drinking and the shared potion cooldown are preserved.
No combo reactions, stagger, vulnerability, custom weapon combos or Combat Roll
integration are added.

| Component | Tested version |
| --- | --- |
| Minecraft / Java | 26.3 / 25.0.4 |
| Fabric Loader / API | 0.19.5 / 0.161.0+26.3 |
| Gradle / Loom | 9.7.0 / 1.18.2 |
| Better Combat | 3.2.2+26.3-fabric; embedded metadata 3.2.2 |
| Cloth Config | 26.3.158 |
| Player Animation Library | 1.2.8+mc.26.3 |
| Additional server fixture | Lithium 0.26.2+mc26.3 |

The exact [official Better Combat artifact](https://modrinth.com/mod/better-combat/version/8Vyq1h9a)
was inspected, including its hand mask, weapon registry, server attack handler,
native damage/knockback path and configuration handshake. Its SHA-256 is
`e56eb58bfef120e11a2028b4a59b15b1f43a14a1a08862166f10ad351bc5e2d8`.
An older GitHub branch was not treated as the tested 26.3 implementation.

## Why one optional integration is needed

Better Combat can report an empty offhand while a two-handed weapon hides a
stored shield. `Inventory.getItem(Inventory.SLOT_OFFHAND)` reads the actual
26.3 equipment storage. Potion placement and cleanup use that backing inventory.
An empty offhand must also be usable: the isolated
`compat/ClockworkBossBetterCombat.java` queries the selected weapon's attributes
only at potion grant/refresh. All typed Better Combat references are in a nested
class loaded only when that mod is installed.

`WeaponRegistry.getAttributes` is a public implementation method used by that
version of Better Combat; it is not a promised stable addon API. If a later
version breaks linkage, Clockwork warns once and uses a free inventory slot.
There is no new tick listener, combat callback, packet, animation or damage replay.

Personal offhand equipment is never displaced. Empty usable offhand: give the
bottle there. Occupied or unusable offhand: give it in a free inventory slot;
select it in the main hand to drink. No free slot: leave all items alone and
tell the player to free a slot and reenter. Cleanup removes marked bottles from
backing inventory/cursor storage and keeps personal gear in its existing slot.

## Automated evidence and limits

- **54 required native dedicated-server GameTests passed with Better Combat:**
  47 existing regressions plus seven compatibility cases. Coverage includes
  masked shields/empty hands, full inventories, interruption and the real native
  drink duration, reusable bottles, both-hand shared cooldown, weapon swaps,
  dual-wield attacks, confirmed death and arena-exit cleanup.
- Actual Better Combat `ServerNetwork.handleAttackRequest` invokes the shipped
  server path with target IDs. Tests verify committed native damage and one
  `AFTER_DAMAGE` event per accepted hit at different attack speeds, server crits,
  scaled reach, rejected distant targets and a health-phase crossing. Protected
  multi-target melee preserves player health/velocity and blocks Fire Aspect;
  the same swing still damages an ordinary mob. Both directions are tested.
- **54 required tests also pass with Better Combat and Lithium together.**
  An earlier combined run had an intermittent fixed-box overlap failure; its
  diagnostic rerun passed without a gameplay change. The fixture now moves the
  boss vertically in the same loaded chunk and asserts it remains loaded/alive,
  so the box test does not depend on a random origin crossing a chunk boundary.
  Existing PvP production code remains unchanged.
- **47 required native GameTests passed without Better Combat**, plus the full
  build, **5,257** audit assertions, bounded file-worker checks, all **13** workshop
  suites and **644** portable template contract assertions.
- The exact packaged jar boots dedicated servers both without and with Better
  Combat, creates/activates/removes a fight and shuts down cleanly. Workshop
  disabled/unreachable, fixture migration and old-companion rejection pass.
  The separate native client recheck without Better Combat passes 2,700 model
  comparisons and 65,536 emissive pixels with Sodium/Lithium/EMF/ETF.
- Test-only embedded players use native `ServerPlayer.doTick()` to advance
  drinking/attack charge. These fixtures have no real client connection driving
  that path. The distributable contains no manual tick calls or GameTest classes.
  The phase-crossing fixture disables AI and explicitly calls the existing phase
  boundary after the committed hit; it does not reproduce client swing timing.

A repeat run found a preexisting asynchronous catalog fixture race: 300 native
GameTest ticks passed in 34 ms. That fixture now briefly yields to the real IO
worker while pending; production batching and real timeout limits are unchanged.
The final native suites pass without skipping required tests or removing assertions.

These are native server fixtures, not a real multiplayer session. Client input,
animation, packet delay/loss, the full live pack and the actual gravestone mod
with Better Combat still need playtesting. Combat Roll was not part of this run.
No TPS/heap benchmark was performed. Packaged boot/metadata results are recorded
separately in [Preflight](PREFLIGHT.md).

Clockwork remains optional on clients. Better Combat's own configuration task
disconnects clients without its channel: a Better Combat server needs Better
Combat clients, even when they omit Clockwork. This is verified from its exact
configuration-task implementation; a real mixed-client login remains untested.

## Reproduce the tests

Use Java 25. Obtain the three optional mods above from their official downloads
and place them in a local fixture directory, outside the source tree. Better
Combat is not included in this repository or the Clockwork jar. Gradle uses a
pinned, non-transitive compile-only artifact for the small hand query.

```powershell
.\gradlew.bat runGametest build
.\gradlew.bat -I tools/bettercombat-check.init.gradle "-PbetterCombatTestMods=C:/path/to/test-mods" runBettercombatcheck
```

After initial dependency downloads, `--offline` can be used. Reports are
`build/combat-gametest.xml` and `build/compat-bettercombat/gametest.xml`.
Both runs use disposable build worlds; normal client/server run folders are
not modified. Do not mix additional mods into the fixture directory or call
`doTick()` manually during live play.

## Short multiplayer checklist

Use a test server and two Survival players. Record versions and Pass/Fail/Not run.

1. Enter with an empty offhand, then with a named shield, dual weapons and a
   two-handed weapon hiding a shield. Confirm potion placement and unchanged gear.
2. Select an inventory potion in the main hand; finish a drink, interrupt another,
   swap weapons and try a copied bottle in both hands during cooldown.
3. Fill every slot: entry must explain the failure and move/drop nothing. Free
   one slot and reenter. Leave/remove the boss during drinking; recover a grave
   after death and confirm no usable temporary bottle or duplicated equipment.
4. Hit a normal/scaled boss with fast and slow weapons, crits and dual wielding.
   Cross a phase threshold. Check one health loss per accepted hit and no hits
   beyond reach; observe normal client animations.
5. With protection enabled, try melee/sweeps, Fire Aspect and projectiles between
   participants and outsiders in both directions. No protected damage, push or
   harmful effects; boss/environmental damage and healing should still work.
6. Repeat arena exit, overlapping encounters and cleanup. Test a client with
   Better Combat but without Clockwork. Separately test a server without Better
   Combat using a client without Clockwork, then repeat at realistic player ping.

This preview is built locally only. No deployment, publication, commit or push
is part of this compatibility task; the `0.2.0` release snapshot stays separate.
