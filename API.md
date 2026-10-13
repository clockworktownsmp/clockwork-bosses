# Clockwork Bosses addon API

## Status and scope

This is **experimental API revision 1**, developed after the recorded 0.1 release.
It is packaged in the `0.2.beta.1` test build; beta deployment is verified by
installed file hashes, with new runtime/API playtests still pending.
Use a core jar built from this source on the server. Clients installing the core
must use a matching build; the latest source also provides a native Warden fallback
for players without it (see [installation limits](README.md#development-source-players-without-the-client-mod)).
The previously deployed 0.1 jar does not contain this API or that fallback. No stable binary compatibility promise
is made yet. `BossAddon.API_REVISION` and the core jar's
`clockwork_bosses:api_revision` custom metadata identify the contract revision.

The public Java package is `clockwork.bosses.api`. This first contract provides:

- Registered actions with a per-execution lifecycle and owned cleanup scope.
- Data-only setting descriptions, used by the compiler, editor and file comments.
- Encounter notifications with immutable UUID-based snapshots.
- A mob controller and capability boundary. The current backend controls Wardens.

Core combat, melee, sonic boom, recovery, roar and burrow are registrations in
the same action catalog. Their action classes use the public context/controller;
Warden-specific animation details stay in the controller backend.
Legacy `attack` and `recovery` subphases still work.
No new health phases, subphases or actions are inserted into an existing template.

Custom targeting strategies, conditions/triggers, arbitrary mob registration,
custom client controls and a separate published API dependency are future work.
Prove another mob adapter before promising arbitrary mob support. Addons can
already reuse the existing phases, arena membership, native targeting, support
and rewards without reimplementing an encounter.

## Register an addon

An addon is a separate Fabric mod with its own namespace. Declare a dependency
on Clockwork Bosses, Minecraft 26.3, Fabric API and Java 25. Add this entrypoint
to its `fabric.mod.json`:

```json
"entrypoints": {
  "clockwork_bosses": ["your.addon.YourBossAddon"]
}
```

Implement `BossAddon.register(BossActionRegistry.Registrar)`. The core invokes
that entrypoint during initialization, before loading templates, then freezes
the catalog. Use the provided registrar; do not register asynchronously or keep
it for later. Duplicate IDs and IDs outside the provided addon namespace fail
initialization visibly. This avoids silently replacing another mod's mechanic.

```java
public final class YourBossAddon implements BossAddon {
    @Override public void register(BossActionRegistry.Registrar actions) {
        actions.register(new BossActionType(
            Identifier.fromNamespaceAndPath("your_addon", "wait"),
            "Example pause", "Pause native behavior for the chosen duration.",
            List.of(BossSetting.number("duration_ticks", "Duration ticks",
                "Pause length; 20 ticks = one second.", 40, 1, 200, true)),
            Set.of(), Wait::new));
    }
    private static final class Wait implements BossAction {
        @Override public Result tick(BossActionContext context) {
            return context.elapsedTicks() >= context.settings().integer("duration_ticks")
                ? Result.COMPLETE : Result.RUNNING;
        }
    }
}
```

Factories must return a **fresh instance for every execution**. Never return a
singleton containing a target, countdown, world, cleanup callback or entity.
Imports in addon code should use the public API, not `ClockworkBossManager`,
`ClockworkBossState`, mixin contracts or other implementation classes.

## Configure it in a subphase

Select the registered action in the subphase's **Type** dropdown. The GUI shows
its declared **Action settings** section automatically. No custom screen,
packet handler or core edit is needed for a scalar setting.

```json
"subphases": {
  "short_pause": {
    "type": "your_addon:wait",
    "action_settings": { "duration_ticks": 40 }
  }
}
```

Subphases still use the parent's sequential/random order and independent roar
interludes. Returning `COMPLETE` enters that same transition path. For registered
actions, `action_settings` is optional: omit it or use `{}` to use the declared
defaults. If present, it must be an object, not `null`, an array or a scalar.
Within that object, missing or `null` declared scalar fields use their defaults.
Existing native
combat overrides continue inheriting from the parent phase; custom action
settings do not silently inherit a different action's values.

Changing Type resets `action_settings` to the new action's defaults. Other stored
combat overrides are preserved, although inactive controls are hidden and do
not affect noncombat actions. Unknown addon setting keys survive saves, but
cannot become arbitrary editable/executable fields. A missing action addon
produces a validation error rather than silently replacing that mechanic.

## Settings and editor contract

`BossSetting` declares a key, readable label, help, default and validation:

| Factory | Control | Validation |
| --- | --- | --- |
| `number(..., min, max, integer)` | Number field | Exact inclusive limits; optionally whole numbers |
| `bool(...)` | True/false dropdown | A JSON boolean |
| `text(..., choices, maxLength)` | Dropdown when choices exist, otherwise text | Declared choices and length |

Numeric parsing reuses the core's bounded decimal parser: at most 128 text
characters, 64 significant digits and scale -64 to 64. JSON strings are not
coerced into numbers or booleans. `integer(key)` returns an exact 32-bit value;
whole-number definitions must have bounds that fit it. `number(key)`, `bool(key)` and `text(key)` read compiled
values. Tick callbacks do not parse JSON.

Limits: 64 total registered actions, 16 settings and 16 required capabilities
per action, 32 choices per setting, text length up to 512. Action IDs use flat
lowercase names; setting keys use lowercase letters, digits and underscores.
The total editor catalog is capped at 20,000 characters and the existing
document/save packet size limits still apply.

Descriptors sent to operators contain data, never factories or executable Java.
A server-only addon can therefore expose standard controls to a client that
has Clockwork Bosses but not the addon. Addons with new entities, renderers,
assets or their own network channels can still require installation on clients.
Native-only addon actions can also run for players without the core client mod,
but those players have no Clockwork Bosses editor, custom media or enhanced visuals.
The core's adapter covers its own Warden fields through native entity tracking;
it cannot make an addon's custom registries or direct packet formats optional.
The server rebuilds and validates action changes against its own catalog; a
client cannot replace descriptors. Virtual catalog fields are never saved.

Generated file comments group setting descriptions above `action_settings`,
including defaults and limits. An addon that opens files or accepts identifiers
must implement its own domain validation; a bounded text field alone does not
validate a filesystem path or authorize access to it.

The development [template schema export](TEMPLATE_SCHEMA.md) also uses these
registered descriptors. An operator's `/boss template schema` export includes
installed addon definitions; `gradlew exportTemplateSchema` generates a core
schema without launching Minecraft. Addon settings need no second website list.
The portable codec preserves unknown addon data during import/export, including
when the addon is absent. Running the encounter still requires that action.
The GUI's **Action default** choice writes null, matching the runtime rule that
missing/null setting values use their declared defaults.

## Action lifecycle

1. The controller creates the instance and establishes its ownership.
2. It samples `pausesNativeBehaviors()` once; default true pauses native behavior
   execution and navigation. Native sensors and memory expiry still run.
3. `start(context)` acquires resources and registers restoration.
4. `tick(context)` runs from the existing marked boss AI callback. Return
   `RUNNING` or `COMPLETE`. There is no additional global action watcher.
5. `stop(context, reason)` runs once, even after a partially failed start.
6. Cleanup callbacks run in reverse acquisition order; runtime references to
   entities, worlds, action objects and state are then cleared.

The stop reasons distinguish completion, phase/subphase changes, reload, death,
removal, unload, shutdown and failure. An old generation cannot complete a later
phase. A failed execution remains stopped until a transition or reload; it is
not restarted and logged every tick. Runtime exceptions, linkage errors and assertion failures are
isolated and cleanup continues. Fatal VM errors are not swallowed.

All callbacks and live context access belong on the server thread; live context
getters enforce this before accessing the world or encounter. Immutable settings
may be copied to a worker, and snapshots may be captured on the server thread.
Do not put a
context in a static collection, future, timer or background job. In `stop`,
read-only settings/snapshots and direct restoration are available, but mutating
helpers reject starting new work in a closed action. Cleanup callbacks must be
short and safe when entities are dead or already removed. Fatal process stops
cannot execute callbacks; journal persistent mutations or keep them transient.

`allowsNativeAttack(id)` is a cheap, side-effect-free permission query. To allow
native combat alongside an action, also return false from
`pausesNativeBehaviors()`. The controller services pending melee and sonic
work for permitted actions. Phase enable flags, charge/recovery/cooldown rules
and native target checks still apply. Core native action IDs are:

| ID | Behavior |
| --- | --- |
| `clockwork_bosses:combat` | Both enabled native attacks; configured attack budget |
| `clockwork_bosses:melee` | Melee only; configured attack budget |
| `clockwork_bosses:sonic_boom` | Sonic only; close range does not yield to disallowed melee |
| `clockwork_bosses:recovery` | Timed movement/attack pause |
| `clockwork_bosses:roar` | Native roar, optional sound; waits for native completion |
| `clockwork_bosses:burrow` | Dig, hold underground, emerge near an eligible player target |

Roar waits for a valid native target and the preceding attack's recoil. It does
not fabricate a target when no player is available. Burrow uses bounded depth
and duration, captures original flags/position, and saves a small surface-position
journal. When the hold ends, it resolves the current target's latest position;
an invalid target falls back to an eligible arena player using the existing
targeting policy. It searches at most eighty nearby floor candidates, using the
scaled standing body, loaded chunks, world border, arena bounds, native walkable
terrain classification, solid support, collision and liquid checks. No safe
destination means emergence at the starting surface. No blocks are destroyed.

The boss faces its target, performs the native emergence, and advances the
subphase sequence. Put a combat action after burrow to attack after emergence;
burrow does not fabricate a hit or override a following recovery/roar action.
Native targeting vetoes and attack cooldowns are retained. While underground,
radius-arena membership stays anchored at the saved surface. Once emergence
commits its destination, the radius follows that new surface. Cleanup and saved
interruption recovery use the latest committed surface and original flags, so
normal completion does not teleport the boss back. Neither action replaces
general Warden AI. In-game verification remains pending.

## Context, capabilities and resource ownership

`context.controller()` exposes a `Mob` and declared capability IDs. Registrations
requiring unsupported capabilities cannot compile for the current Warden backend.
The Warden declares `clockwork_bosses:melee`, `sonic_boom`, `roar` and `burrow`.
An empty requirement set is appropriate for an action using only generic mob
and participant operations. Do not infer a Warden animation from a generic mob.

The public controller also provides `startRoar(BossRoarSound)`/`roarFinished()`
and `startBurrow(undergroundTicks, depth)`/`burrowFinished()`. Core actions use
these same operations. Acquire one native animation per action, normally from
`start()`. Roar requires an unpaused native brain; burrow requires a paused one.
`BossRoarSound.VANILLA` keeps the default roar. A custom cue uses the selected
template's validated OGG delivery. Custom range 0 resolves the arena radius or
full inclusive box diagonal at the sound trigger (32 blocks without a usable
arena, capped at 512). A positive range is manual, with a one-block playback
minimum. Registered core roar defaults use 0; legacy inherited roar lists without
any range retain 32. This does not alter vanilla roar audio or encounter music.
Animation interruption is owned by the
controller, including burrow's saved return-position journal.

`target()` validates the current native target. `participants()` resolves live,
eligible players from the existing arena membership; `isParticipant(player)`
also rechecks current position, life and dimension at use time. Store UUIDs and
re-resolve them before delayed hits. The helpers do not load chunks.

Register `onCleanup(...)` **before** changing a temporary flag or attribute.
Prefer namespaced native attribute modifiers and remove only your own modifier;
blindly restoring an attribute's old base can overwrite another mod's changes.

Call `ownEntity(entity)` only for an entity your action created, preferably
before `addFreshEntity`. Ownership excludes native persistence, including
passenger saves, so an earlier chunk unload cannot leave a saved minion or
projectile orphan. Cleanup discards that exact entity if still loaded and never
loads its chunk. It rejects players, bosses and already-owned entities. Scope
capacity is 64 resources per execution; it is not a persistent minion system.
Owned entities cannot use portals or teleport across dimensions. Minecraft
creates a replacement entity during dimension transfer, which would escape the
original cleanup identity. Same-world teleports remain available.

`damage(player, source, amount)` revalidates arena eligibility, uses native
server damage and reports an accepted hit. Armor, invulnerability and other
mods' damage vetoes still apply. It also uses existing voice/target-rotation
handling for that attempt. Health damage in the hit notification is the observed
health delta, not requested damage. An action decides its own completion rather
than advancing its subphase once for every victim of an area attack.

## Encounter notifications

Register Fabric callbacks on `BossEncounterEvents` during addon initialization.
They are synchronous notifications, not cancellation or mutation hooks. Keep
them short. Register once: Fabric keeps listeners for the process lifetime.
Never register per action or capture live encounter/world/player objects in a
process-wide listener. Immutable snapshots contain boss/template/phase identity, game time
and participant UUIDs without retaining an entity or world.
The snapshot UUID set represents arena viewers; live context queries additionally
filter untargetable players before an addon attempts damage.

| Event | When it fires |
| --- | --- |
| `STARTED` | Dormant boss activates, before initial arena joins; not command spawn |
| `PLAYER_JOINED` | Membership/support and boss bar are committed by the existing scan |
| `PLAYER_LEFT` | Arena exit, disconnect, player death/respawn or encounter cleanup |
| `PHASE_CHANGED` | A real health phase transition commits; not initial assignment/reload |
| `ATTACK_LANDED` | Accepted native or context-helper damage; no event for rejected attacks |
| `ENDED` | Loaded encounter terminates or suspends, with an explicit reason |

`ENDED(DIED)` is committed boss death. `UNLOADED` means suspension, not a reward
or quest completion. Loaded-state end emits once; its snapshot retains the final
audience before removal, while departure snapshots show cleared membership.
Loading the boss creates a fresh runtime; it does not repeat activation or
restore an interrupted action mid-flight. Native saves retain boss identity and
existing player-heal/reward ledgers.

A callback failure cannot skip later listeners. Failure logging is globally
limited to once per minute. Installed Java addons are trusted code: this API's
validation and scopes are not a security sandbox for a malicious mod.
Slow or looping callbacks can still stall the server. Factories must provide
fresh instances; the core does not prove their freshness. Each addon must validate
its own file/URL inputs and register cleanup for resources it creates.
See [the API safety review](API_SECURITY_REVIEW.md) for safeguards and limits.

## Standalone example and verification

[The lightning example](examples/lightning-addon/README.md) is its own Gradle
project. It registers one action using only the public API, supplies number and
dropdown fields, uses native warning particles and a visual-only lightning bolt,
and routes damage through the encounter context. It has no mixins or client
entrypoint and is not bundled or automatically installed with the core.

The example reads the parent's configured core version/archive name, so it
compiles against that matching build rather than an old 0.1 jar in `build/libs`.
Addon 0.1.1 pins its runtime dependency to the same exact version. This matches
the experimental compatibility policy; rebuild the example when the core
version changes. Use [the lightning playtest checks](PLAYTEST_CHECKLIST.md#lightning-example-addon)
to verify registration, server-only editor settings, attacks and interruption.

Current verification: core common/client Java, existing development sources and
the independent addon compile against the configured Java 25 / 26.3 environment.
Development jars and source jars are packaged. Runtime regressions, editor
interaction with a server-only addon, interruption/save recovery and a second
mob adapter remain unverified. Recorded 0.1 release results do not cover this API.
