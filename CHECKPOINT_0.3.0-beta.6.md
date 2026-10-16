# Clockwork Bosses 0.3.0-beta.6 checkpoint

Prepared October 13, 2026. Existing features only: encounter friendly fire,
Better Combat/reusable-potion compatibility, and static/event-driven face controls.
No stagger, vulnerability, combat reactions or additional roadmap mechanics.

## Artifact

One universal client/server jar: `build/libs/clockwork_bosses-0.3.0-beta.6.jar`.
The source version, filename and Fabric metadata all identify **0.3.0-beta.6**;
the packaged Workshop build channel is explicitly **beta**, independent of the
version spelling. API revision remains experimental revision 1.

SHA-256:

```text
d917fbe4af296e5b54d839b6c04f0db9336f98549b1eff508f5f56b6f58473de
```

Packaging inspection confirmed every main/client class matches the checked output,
the exact exported schema is included, all seven presets contain face defaults,
and Better Combat is optional. Test mods/classes, private config/world data,
credentials and website assets are absent. Only the existing JOrbis library is
bundled. No manual player `doTick()` call or embedded web server enters the jar.
Older artifacts remain separate; `main` and `v0.2.0` remain at the same commit.
No deployment, server-setting change, commit or push was performed.

## Versions exercised

| Component | Version |
| --- | --- |
| Minecraft / Java | 26.3 / Temurin 25.0.4 |
| Fabric Loader / API | 0.19.5 / 0.161.0+26.3 |
| Gradle / Loom | 9.7.0 / 1.18.2 |
| Better Combat | 3.2.2+26.3-fabric, embedded version 3.2.2 |
| Cloth Config | 26.3.158 |
| Player Animation Library | 1.2.8+mc.26.3 |
| Lithium | 0.26.2+mc26.3 |
| Sodium | 0.9.2+mc26.3 |
| EMF / ETF, final native client and GUI | 3.3.11 / 7.2.5 |

The first GUI run also passed with EMF 3.3.8 / ETF 7.2.4; final GUI checks were
repeated with the current versions above. This is a focused fixture environment,
not a claim of complete modpack compatibility.

## Results

| Check | Result |
| --- | --- |
| Offline full build/check | Passed, including 74,241 audit assertions and all registered Workshop/file-worker suites |
| Native server without Better Combat | All 56 required GameTests passed |
| Native server with Better Combat | All 63 required GameTests passed |
| Native server with Better Combat + Lithium | All 63 required GameTests passed |
| Native client | 2,700 model comparisons, 65,536 emissive pixels, 393,217 face-mask assertions passed |
| Native editor | 1,104 loot-GUI and 130 face-GUI assertions passed; detached Warden preview rendered |
| Exact packaged Workshop API | 23 assertions passed with isolated owner sessions and a test-only compatibility declaration |
| Local canonical website | 68 regression checks passed; actual browser Import/Download/edit/reimport preserved exact data |
| Exact packaged dedicated-server boots | Passed without/with Better Combat, with Workshop disabled/unreachable, clean shutdown and old-companion rejection |
| Actual jar HTTPS beta declaration check | Passed; correctly reports beta.6 editing/publishing blocked by the current hosted declaration |

### What these checks cover

- Friendly fire: both directions, participants/outsiders, melee/sweeps, damage,
  knockback, Fire Aspect, projectiles, harmful effects, entry/exit and cleanup.
  Boss damage, environmental hazards and helpful effects remain separate.
- Potion handling: actual backing offhand occupancy, hidden two-handed equipment,
  inventory fallback, full-inventory safe failure, native use/interruption,
  reusable bottles, both-hand cooldown, ownership, swaps, death, arena exit and
  removal. Better Combat server attacks exercise committed native damage at
  different speeds/reach, critical hits, sweeps, scaled bosses and phase crossing.
- Faces: all shape/rotation packing pairs, exact legacy pixels, clipping, null
  inheritance, random-pool fallbacks, priority/cooldowns, return from a hit to an
  active attack, stale tokens, reload/phase cleanup and actual native damage/start
  hooks. Native atlases preserve outside-face coverage and rebuild after clearing.
  Real editor inputs at 320×240, 640×360 and 1280×720 preserve draft values and
  distinguish local pose selectors from saved fields.
- Website: new fields, bounds/nullable inheritance, masks/preview, unknown addon
  data and precise numbers, Import/Download and isolated Save/Publish guards.
  Authenticated website calls use test fixtures, not live account authentication.

An initial new face fixture failed because it captured a cow target then hit the
boss from a player, allowing ordinary retaliation to cancel the old target's
wind-up. The final fixture uses the current player opponent to isolate expression
priority; its preservation/expiry assertions remain intact. No gameplay logic
was changed to accommodate that fixture. Initial test compilation/classpath
setup errors were corrected, and the final checks were rerun successfully.

## Workshop beta limitation

The current [public beta declaration](https://beta.clockworkboss.com/build-info.json)
identifies website **0.3.0-rc.3**, build
`c68627b1c236c96db30529b964e62d2ed8d976b29ceac834ac921e4b4b65386a`.
It declares only `0.3.0-beta.4`, `0.3.0-beta.3` and `0.2.0`.

Therefore **hosted beta is not yet declared compatible with beta.6**, and its
published face editor/schema do not include these new controls. The real jar's
bounded HTTPS metadata client confirmed `writable=false`. No origin/capability
guard was bypassed, no host fallback was added, and no production connection or
credential was copied to beta. Local website tests using an explicit synthetic
beta.6 declaration demonstrate code readiness; they do not update public support.

The website version can remain independent of the mod version. A separately
approved, tested beta website release must publish the current controls/schema
and explicitly declare beta.6 support before testing hosted edits/publishing.
Stable/dev sites and all live server settings remain unchanged.

## Remaining manual playtest

These native server fixtures use embedded players, not real networked clients.
There was no real multiplayer session, latency/packet-loss test, complete live
modpack run, heap/TPS benchmark, or actual gravestone-mod interaction in this
checkpoint. Real Clockwork-optional login also remains untested here. Better
Combat's own client requirements still apply when it is installed on the server.

1. With two Survival players, check protected participants and outsiders in both
   directions: direct attacks, sweeps, Fire Aspect, bows and harmful potions.
   Leave the arena and end/remove the boss; ordinary PvP should resume.
2. Enter with empty offhand, shield, dual weapons and a two-handed weapon hiding
   a shield. Personal equipment must stay put. Drink the fallback potion from
   the main hand; interrupt, swap weapons, test cooldown and full inventory.
3. Test death/grave recovery and `/boss remove`: leftover bottles must be removed
   or unusable. Check overlap with another encounter and reentry.
4. Hit normal/scaled bosses with fast/slow weapons and crits, crossing a phase.
   Confirm one damage result per accepted hit and sensible reach/animation.
5. In the native face editor, try shapes/independent rotations at your preferred
   GUI scale. Enable event rules and give melee/sonic/hurt/target distinct shapes.
   A hit should briefly override an attack, then restore it only if still active.
   Check random pools, interruption, reload and removal.
6. Repeat with realistic ping and clients with/without Clockwork. Hosted beta
   editing/publishing waits for its separate compatibility update.

Detailed reproduction/evidence stays in the local checkpoint receipt. Existing
[Better Combat notes](BETTER_COMBAT_TESTING.md) explain the fixture launchers and
the distinction between native automation and a real multiplayer playtest.
