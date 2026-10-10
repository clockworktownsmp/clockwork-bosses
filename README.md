# Clockwork Bosses

Release source/build and installed client/server: **0.1**. Fabric 26.3 boss encounters built from vanilla Wardens, targeting Fabric Loader 0.19.5 and Fabric API 0.161.0+26.3. Earlier builds retain their original filenames and historical notes.

## Version 0.1 — 2026-10-10

The first numbered release retains the tested encounter, media, player support
and template/preset editors, including searchable visual reward selection.
This tightening pass bounds numeric text, precision and exponents before
arithmetic/formatting; exact decimal comparisons enforce editor limits without
floating-point rounding. Extreme imported percentages stay visible as compact
text instead of breaking the editor. Invalid edits still publish nothing.
Empty reward lists skip native table/context construction. Client/server share
one save-size limit, and a save serializes its patch only once.

Reviewed lifecycle cleanup for encounters, player grants, selector/editor
sessions, media transfers and generated client assets, plus bounded worker/cache
ownership and native reward/mixin gates. No new watcher, packet or AI behavior.
No unused explicit production imports or unreferenced private declarations were
found by the source scan. This is a code review, not proof of zero possible leaks.

Release checks passed: **4384 config/media assertions**, **1038 native GUI
assertions**, and **28/28 native server tests both with and without the supplied
server pack**. The packaged production jar also reached readiness and passed
spawn/activate/save/remove in a disposable local pack server, then exited normally.
This does not cover real multiplayer latency or full-pack client play.

Universal jar: `../outputs/clockwork_bosses-0.1.jar`. Installed in Test Client
(physical profile folder `Clockwork Town 26.3`) and the stopped test server. Both
RC8 jars were backed up and both installed copies match the verified build hash.
Record: `../work/release01-deployment.json`. Existing configurations, media and
worlds were preserved. Deployment did not start either application.
Nothing has been committed, pushed or uploaded to GitHub.

## Beta RC 8 loot-entry editor â€” 2026-10-10

Local source/build and installed client/server: **0.1-beta-rc.8**. RC7 jars were
backed up before replacement, and both installed copies match the tested build's
SHA-256. Record: `../work/beta-rc8-deployment.json`. Existing configurations,
media and worlds were preserved. Neither application was started by deployment.
Nothing is committed or pushed to GitHub.

The Loot page now offers Entry editor or Datapack table. **Reward entries â†’
Add item** opens a searchable creative-style icon grid, with native creative
categories and vanilla/mod item IDs. Edit quantity ranges and independent drop
chances, reorder or remove entries, then Save. Reload saved applies changes to
loaded bosses. Templates and presets share the editor. Existing datapack IDs,
entries and unrelated extension data are retained when switching sources.
Basic entries grant default items; enchantments/components are future extensions.
See [LOOT.md](LOOT.md) for limits, persistence and tests.

## Beta RC 7 template loot â€” 2026-10-10

Historical source and installed jar at this release: **0.1-beta-rc.7**.
The editor now has a Loot page for enabled state, server loot-table ID and
keep/replace vanilla table drops. Native rewards run once on committed death;
invalid saves retain the previous file, and missing sections stay disabled.
All **24 native tests** passed both without third-party mods and with the
supplied server pack; **4332 audit assertions** passed. RC7 is installed in
Test Client (physical profile folder `Clockwork Town 26.3`) and the stopped
remote test server. Both RC5 jars were backed up and both installed RC7 copies
match the built SHA-256. Record: `../work/beta-rc7-deployment.json`.
Existing configs, media and worlds were preserved. Neither application was
started, and nothing was committed, pushed or uploaded to GitHub.
See [LOOT.md](LOOT.md) for configuration, implementation, tests and remaining
manual checks.

## Build from source

Requires **Java 25**. The repository includes the **Gradle 9.7.0 wrapper**;
Fabric/Minecraft versions are defined in `gradle.properties`.

Windows: `.\gradlew.bat build`

Linux/macOS: `bash ./gradlew build`

The universal client/server jar is written to `build/libs/`.
Run the portable local checks with `gradlew.bat auditChecks runGametest`
(or `bash ./gradlew auditChecks runGametest`). GameTests create a disposable
world under `build/combat-gametest`; they never require a production server.

Source, bundled assets/presets, all regression fixtures and Gradle wrapper files
belong in Git. Runtime instances, private server configs, worlds, logs, caches,
credentials and generated builds are excluded by `.gitignore`. Historical
`../work/` evidence links refer to local files outside version control.

## Beta RC 6 supplied server-pack checks â€” 2026-10-09

Current source/local artifact: **0.1-beta-rc.6**; RC5 remains installed. The
provided server pack passed all 16 project native checks plus the vanilla control
and 4316 audit assertions. The actual RC6 jar also passed a production-mode
server start, boss spawn/activation/save/removal and normal shutdown.

RC6 strengthens offhand ownership for copied item stacks and native inventory
transfers. It preserves live item data while accommodating stack-capacity changes;
changed/moved personal items remain untouched. Initial pack failures led to
development fixture corrections for LuckPerms pre-login, required networking and
Stackable Totems' clear/give rules. Tests now verify copies, changed counts/data,
new offhand items and ordinary-totem conversion without loss or duplication.

See [MODPACK_TESTING.md](MODPACK_TESTING.md) for exact scope, service isolation,
remaining pack warnings and the multiplayer checks still needed. No installed
profile or remote server was changed during this test.

## Beta RC 5 compatibility work â€” 2026-10-09

RC5 deployment: **0.1-beta-rc.5**. RC4 was backed up before
RC5 was installed in Test Client and the stopped remote test server. Both installed
copies match the built SHA-256. Record: `../work/beta-rc5-deployment.json`.

Recovery now guards behavior calls in native `Brain.tick`, preserving Lithium's
optimized implementations, memory expiry and sensors. Only marked recovering
bosses pause behavior execution. Added operator-only, disabled-by-default
compatibility capture through `/boss debug on|off|status|dump|clear`; it records
bounded attack/support events without a new tick watcher or diagnostic packets.

All 15 project GameTests plus the vanilla control passed with and without
Lithium 0.26.2+mc26.3. Offline audit passed 4316 assertions. The isolated client
passed 2700 native model comparisons with Sodium 0.9.2+mc26.3, ETF 7.2.4,
EMF 3.3.8 and Lithium loaded. These are startup/model and isolated server results;
visual encounters, real connection loss and Better Combat/Combat Roll remain
manual checks. See [COMPATIBILITY_TESTING.md](COMPATIBILITY_TESTING.md) for exact
versions, commands, evidence limits and the integration checklist.

## Beta RC 4 activation/save fix â€” 2026-10-09

RC3 could activate a newly spawned boss with an uninitialized first-heal UUID
set, then fail again while saving that entity. The ledger is now allocated on
first use; native save handles an absent/empty ledger, and native load restores
at most 4096 UUIDs. Other non-JVM metadata defaults are explicitly seeded by
the actual Warden constructor callback. Unmarked Wardens allocate no heal ledger.

Native Minecraft 26.3/Fabric GameTest passed all 11 project checks (plus the
vanilla control): fresh entity creation, empty/populated ledger save/load,
first arena entry, exit/reentry without repeated healing, offhand restoration,
potion healing/food/cooldown, native health/phase behavior, melee cooldown,
beam cancellation and target rotation. Updated older fixtures to honor captured
cooldowns and target holds, use 26.3's separate damageCooldownTime field for a
blocked valid victim, and avoid another fixture's players entering the target pool.
No production combat policy was changed for those fixture corrections.
Report: `build/combat-gametest.xml`; log: `../work/rc4-regression.log`.

Offline auditChecks passed 4316 assertions and assemble succeeded for common
and client sources. Development test classes are excluded from the universal jar.
This verifies an isolated native server, not a full multiplayer or client UI session.
RC3 was backed up and matching RC4 jars installed in Test Client and the stopped
remote test server. Installed local and downloaded remote bytes match SHA-256
`C1C639A0E1FA1F5D0EA51E2D65AAEBA9FA462124A883F66DC46928718E2CF188` (861448 bytes). Deployment record: `../work/beta-rc4-deployment.json`.

## Beta RC 3 startup fix â€” 2026-10-09

RC2 failed during Warden mixin application: the super-call WrapOperation used
`Monster` as its receiver. MixinExtras requires the enclosing `Warden` for this
`invokespecial` call even though the target implementation belongs to Monster.
Corrected that one handler parameter; gameplay policies are unchanged.

An isolated native Fabric 0.19.5/Minecraft 26.3 server loaded RC3, created a
disposable world and reached `Done (0.496s)` with no mixin failure. The diagnostic
used localhost port 25579 and no live/client world or server files. Its stdin was
closed by the process runner, so its verified Java child was terminated explicitly
after startup; Gradle therefore reported a nonzero termination exit. This is a
startup result, not a combat/player/UI regression pass. Log: `../work/rc3-native-startup.log`.

Offline `assemble` then packaged RC3. Backed up RC2 and deployed matching RC3 jars
to Test Client and the offline remote test server. Local and downloaded remote
bytes match SHA-256 `F3036177A2C4D441959C93B0A24F938C7CB0B1FAC8E20AA7A539A7205D93F916` (861276 bytes). Other profiles and existing templates/
media were not changed. Compilation does not validate mixin injection signatures;
future mixin changes need a native startup check before installation.

## Beta RC 2 deployment â€” 2026-10-09

Packaged `0.1-beta-rc.2` using offline `assemble` and installed the universal jar
in Test Client and the stopped remote test server. Both prior RC1 jars were
backed up. SHA-256 verification of the local installed jar and a direct remote
download matched `5EB6BDAAFA096BC3A4F0BD3ABAD4B94083FB85A380EC5B085B8541B054CABB80` (861286 bytes).
The archive contains both client/common classes, all seven preset resources and
the new support/recovery mixins; development test classes are excluded.

Includes subphases, separate roar catalogs, delayed melee, Recovery, fight health
and the reusable potion. Existing templates with missing health/potion sections
remain disabled; enable them through Arena effects in the editor to try them.
The running Clockwork Town 2.0.0 profile was not modified. No tests or game/server
launches were run during this packaging/deployment; runtime confirmation remains
pending. Earlier pending-source entries below record the pre-deployment work.

## Recovery subphases and player support

Included in Beta RC 3 (and the preceding RC2 source).

In **Manage phases â†’ a phase â†’ Subphases**, choose `type: recovery` and set
`duration_ticks` (1â€“12000; default 60 = 3 seconds). Recovery pauses attacks and
navigation, then advances using the same sequence/random and roar-interlude rules
as attack entries. Drag it to the desired position. Its hidden combat controls
are retained for switching back to `attack`, but are not applied during Recovery.
Native sensors, cooldown expiry, physics and phase selection continue. A health
phase change or reload cancels the old pause. No extra watcher is created.

```json
"subphases": {
  "heavy": { "type": "attack", "attacks_before_next": 3 },
  "rest": { "type": "recovery", "duration_ticks": 60 },
  "light": { "type": "attack", "attacks_before_next": 5 }
}
```

**Arena effects** now groups Beacon effects, Fight health and Reusable potion.
Each has its own enable switch. Player support starts after activation for players
inside the boss-bar arena, including during emergence and for late arrivals.
New defaults and bundled presets explicitly enable these settings; older files
without them stay disabled until enabled in the editor or config.

- `boss_bar.player_health.minimum_max_health`: default **100 HP**, a minimum,
  not 100 extra HP. Armor granting 100 or more remains authoritative.
- `heal_on_first_entry`: default true. Full heal once per player per encounter,
  including late arrivals. Reentry, reconnect and reload do not grant another.
  The boss saves a bounded ledger of up to 4096 player UUIDs; additional players
  still receive the floor/potion but no initial heal when that ledger is full.
- Later floor changes, exit and reentry preserve health percentage. For example,
  leaving with 50/100 HP yields 10/20 HP if the player's normal maximum is 20.
  Only this mod's named modifier is removed; armor/base/other modifiers remain.
- The reusable offhand potion defaults to **20 HP, 15 seconds cooldown,
  20 hunger and 20 saturation restored**. Healing is capped at max HP, hunger at
  20, and saturation at the resulting hunger. Normal potion drinking time applies.
  The cooldown begins on successful completion, uses game time, is shared by
  copies/overlapping encounters, and survives reentry, reconnect and respawn.

An occupied offhand moves into a free inventory slot; a full inventory prevents
the gift and displays instructions. On exit the original item is restored only
if its displaced stack is untouched and the offhand is empty. Moving it during
the fight leaves it in the inventory. Nothing is overwritten or duplicated.
Owned bottles are removed on exit, death, disconnect and encounter cleanup;
dropped bottles are discarded. Stored container copies are not globally scanned
and remain owner-locked and unusable outside an active arena. Server validation
checks the exact current arena both when drinking starts and when it completes.
Overlapping fights use the highest health floor and one complete potion policy
(highest healing, stable boss UUID tie break), never stacked doses.

Client and server source compile with Java 25 against local Fabric/Minecraft 26.3.
In-game behavior, UI interaction and mixed-mod equipment changes remain to be
checked after packaging. No tests or game launches were run for this source pass.

## Subphases and attack timing

Included in Beta RC 2. Use matching Beta RC 3 client/server jars for the updated attack metadata and startup fix.

Each health phase can have a `subphases` object. `subphase_order` selects `sequential` or `random`. Sequential follows file order and loops. Random shuffles the children, runs every child once, then reshuffles; with at least two children it prevents a repeat at the round boundary. Missing or empty `subphases` keeps the existing behavior. Entering a different health phase, reloading, or reloading the entity resets both the attack sequence and roar cycle. Sequential starts at its first child; random starts with a fresh shuffle.

Attack children inherit their parent phase, then base values. They can override movement speed, knockback resistance, follow range, melee, Sonic Boom and targeting. They have no health threshold and cannot contain more children. Health, scale, Darkness, glow, boss-bar identity and phase-change effects remain controlled by the health phase.

In the editor, open **Manage Phases**, then a phase's **Subphases** or **Roars between** entry. Add, remove, drag the â†• handle to reorder, and edit entries there. Up/Down buttons provide keyboard alternatives. Drag near the list edge or use the wheel while dragging to reach other rows; Escape or dropping outside the list cancels. Reordering stays in the local draft until Save. Main health phases support dragging too; adjust their descending health cutoffs afterward.

An attack child advances after `attacks_before_next` completed attacks (1â€“1000, default 3). Blocked swings count; cancelled wind-ups and disabled/cooldown-rejected attempts do not. When `roar_between_subphases` is true and `roars` is nonempty, a native roar plays after every child, including the loop boundary. The first child starts without a preceding roar. Roar pauses attacks, waits for attack recovery, uses Minecraft's approximately 84-tick animation, plays its selected sound once and then starts the next child. Turn interludes off or empty the roar list to skip them.

The roar list is independent: in sequential mode one roar repeats, two alternate, and four pair with four subphases. A shorter list cycles; there are no missing slots to inherit. Random mode picks a roar independently at every transition, so roar repeats are allowed. Each roar's null sound fields inherit its phase's `roar.sound`, then base `attacks.roar.sound`. An empty filename uses the normal Warden roar; a custom filename with volume zero gives a silent roar animation. Put custom mono OGGs in that template's `sounds/` folder; volume, pitch and range retain the existing file safeguards. Empty defaults/preset lists do not trigger any new behavior. This replaces the earlier unpublished mixed attack/roar child layout.

For example, put these fields inside `phases.annoyed` to alternate heavy and light melee with the same roar between them:

```json
"subphase_order": "sequential",
"roar_between_subphases": true,
"subphases": {
  "heavy": {
    "attacks_before_next": 2,
    "melee": { "enabled": true, "damage": 60, "charge_time_ticks": 20, "recovery_time_ticks": 12, "cooldown_ticks": 60 },
    "sonic_boom": { "enabled": false }
  },
  "light": {
    "attacks_before_next": 4,
    "melee": { "enabled": true, "damage": 15, "charge_time_ticks": 3, "recovery_time_ticks": 4, "cooldown_ticks": 20 },
    "sonic_boom": { "enabled": false }
  }
},
"roars": {
  "warning": { "sound": { "file": "roar.ogg", "volume": 1, "pitch": 1, "range": 32 } }
}
```

Base attack timing lives in `attacks.melee` and `attacks.sonic_boom`; phase and child fields can override it. Twenty game ticks are one second at normal server speed.

| Setting | Meaning |
| --- | --- |
| Melee `charge_time_ticks` | 0â€“12000; default 0. Stretch the native wind-up and delay the native damage call until impact. |
| Melee `recovery_time_ticks` | 0â€“12000; default 4. Stretch the rest of the native swing after impact. |
| Sonic Boom `charge_time_ticks` | Existing beam wind-up control, 0â€“1000000; zero fires immediately. |
| Sonic Boom `recovery_time_ticks` | 0â€“12000; default 26. Stretch the native recoil after firing. |

Melee recovery and its captured successful-hit cooldown must finish before another swing; blocked swings still recover. Native melee also retains its minimum 18-tick scheduling cadence, so a smaller configured cooldown does not guarantee faster attacks. Beam recovery extends its native cooldown when longer. Pending melee stores a target UUID and deadline; target/phase/reload changes, loss of visibility, or leaving melee range cancel it. No chunk is loaded to resolve a hit. An existing beam keeps its captured definition across health changes/reload unless a roar or melee takes over. Its old completion cannot advance the replacement sequence. No additional watcher is introduced.

## Installation and configuration

The mod is now **Clockwork Bosses**, with `/boss` as its command root. For example,
`/boss create new <template>`, `/boss spawn <template> <name>`, and `/boss setspawn <template>`.
Vanilla Minecraft 26.3 uses `/bossbar`; `/boss` is available.

Install `clockwork_bosses-0.1-beta-rc.1.jar` on both client
and server. Configuration uses `config/clockwork-bosses/`, with `boss-templates` and `boss-presets`. Each named template folder owns its
`template.json`, `skins/`, and `sounds/voice-lines/`. Move any earlier development files into those folders manually;
this unreleased mod has no automatic folder migration. Fabric now lists the mod as `clockwork_bosses`; generated assets and packet namespaces
use that ID too, so update both sides together. Saved entity/selector keys remain stable. The IntelliJ project stays in the `clockwork-bosses`
workspace folder; its Gradle project name is `clockwork-bosses`.

## Commands

All `/boss` commands require Minecraft's native game-master permission (ordinary operator level 2). Selector clicks recheck that permission.

| Command | What it does |
| --- | --- |
| `/boss create new <template>` | Creates a fresh default template and empty media folders. |
| `/boss create preset <preset> <template>` | Copies a preset config and media into a new template; preset names autocomplete. |
| `/boss setspawn <template>` | Saves your current block position and dimension to that template. |
| `/boss spawn <template> <name>` | Creates a dormant Clockwork Boss from the named template. If its spawn is unset, it uses the command source position. |
| `/boss select activation <template> [also_bossbar]` | Selects an activation area or redstone block. Set the optional boolean to `true` to copy an AREA selection to boss-bar visibility too. |
| `/boss select arena <template>` | Selects the arena area used for boss-bar visibility and configured player effects after activation. |
| `/boss select cancel` | Ends the current selector session. |
| `/boss activate <name>` | Manually activates a dormant Clockwork Boss. |
| `/boss list` | Lists loaded Clockwork Bosses. |
| `/boss template list` | Lists available templates. |
| `/boss remove <name>` / `/boss remove all` | Removes one or all loaded Clockwork Bosses. |
| `/boss reload` | Reloads template files and reapplies settings to loaded Clockwork Bosses. |

Selection modes use yellow for AREA and red for REDSTONE in the arrow name and mode messages. Command confirmations are green, names aqua, instructions/headings gold, and inactive list entries gray. Errors use Minecraft's native red feedback. These are native text styles; they do not add particles, sounds or tick checks.

The selector arrow is tagged with `DataComponents.CUSTOM_DATA` and an owner/session id, so normal arrows stay normal and only the active selector works. Left-click sets corner one; right-click sets corner two. For activation selection, sneak + right-click switches between AREA and REDSTONE. A combined activation/boss-bar selection stays in AREA mode because both settings need the same two corners.

Selector left-clicks use Fabric's native client pre-attack event to send one selection action
without Minecraft 26.3's separate punch packet. Holding the button does not repeat selection
or start mining when completion removes the arrow. A fresh click restores ordinary input.
This prevents selection from generating block-hit dust and punching sounds; the existing
area/redstone gizmo preview remains the visual feedback. The fix requires updating and
restarting the client as well as replacing the server jar.

AREA activation triggers only when a player crosses from outside to inside. REDSTONE activation triggers on an off-to-on signal edge. Spawning a boss while players are already inside the area does not activate it. Boss-bar visibility is a separate box unless the activation selector's optional boolean is `true`.

Dormant activation is sampled every ten game ticks (about half a second). Keep redstone pulses on for at least ten ticks so the watcher can observe the rising edge.

Activation and boss-bar selection boxes are limited to 256 blocks on each axis. This keeps the player queries bounded; invalid or oversized boxes in manually edited files are ignored.

## Dormant activation and emergence

A Clockwork Boss spawns dormant below its saved emergence position by a depth scaled to its native size (five blocks at scale 1, ten at scale 2), with AI and visibility disabled. The hidden offset keeps its model from protruding through the arena floor. When activation occurs, the boss returns to the saved position before Minecraft's normal emergence sequence. Dormant bosses have no boss bar; their configured Darkness aura can still affect nearby players. Player area buffs begin only after activation.

## Template files

Templates are stored in `config/clockwork-bosses/boss-templates/<template>/template.json`.
`/boss create new <template>` creates this self-contained folder:

```text
config/clockwork-bosses/boss-templates/my_boss/
  template.json
  skins/
  sounds/
    voice-lines/
```

Sounds, attack voice lines and custom skins belong only to that template. Two templates
can use the same filenames with different content; server and client caches include the
template name. Multiple spawned instances of one template share its files, with separate
voice playback gates for each boss. No media folders are created at the mod config root.
Reload after editing files. Earlier development layouts must be moved manually; this
preview performs no automatic startup migration.

The template's `spawn` object is per-template. `/boss setspawn <template>` stores its coordinates and dimension; spawning that template later uses that saved world position. A template with `spawn.set: false` uses the command source position. Templates without a `spawn` object also use the command source position. Spawn configuration lives only in templates; no separate global spawn file is loaded, generated or archived.

Newly created and saved templates keep the `.json` filename and use concise `//` note blocks above sections. Field notes are grouped there; scalar values inside each section stay together without interleaved comments. The mod reads this JSON-with-comments format using Gson; existing strict JSON templates still work. Each phase sound explicitly includes `"file": null`, meaning inherit the shared sound; `"file": "phase2.ogg"` selects a file and `"file": ""` disables that transition. Phase `"scale": null` inherits the base scale. Saves preserve unknown setting values and regenerate the explanatory comments; hand-edited comment wording is not retained verbatim. The bundled strict JSON resource is the single defaults/comment catalog.

### Bundled player-count presets

The jar includes your six maze boss presets. Startup places them in the separate
`config/clockwork-bosses/boss-presets/` folder as named folders containing `template.json`, `skins/`, and `sounds/voice-lines/`. They are available to
edit but do not appear in `/boss template list` or autocomplete. Existing preset files are
preserved on restart, and active templates are managed independently.

| Players | Template folder / file | Boss health |
| --- | --- | --- |
| 1 player | `1_player/template.json` | 1000 |
| 2 players | `2_players/template.json` | 1400 |
| 3 players | `3_players/template.json` | 1800 |
| 4 players | `4_players/template.json` | 2200 |
| 5 players | `5_players/template.json` | 2600 |
| 6 players | `6_players/template.json` | 3000 |

Use a preset by copying its entire named folder into `config/clockwork-bosses/boss-templates/`, then run
`/boss reload` (or restart). For example, copy the `2_players/` folder and spawn it with
`/boss spawn 2_players maze_boss`. You may rename the copy to another valid template
name and adjust its settings for that encounter. Only named folders containing `template.json` in `boss-templates/` are listed
and loaded; preset folders serve as optional starting points.
Template folder names use lowercase ASCII letters, digits, dots, underscores or hyphens, with no spaces or capitals. Each identifier works as a single command argument.
Each player-count preset preserves the agreed tuning, scale 2.0, Regeneration I area buff,
skin and phase settings and supplied test-arena coordinates. Configure spawn, activation
and boss-bar selection for each template's intended arena with `/boss setspawn`,
`/boss select activation` and `/boss select arena`. Startup renders their comments
from the current documentation catalog. Missing preset documents are recreated in `boss-presets/`
at the next mod startup; this never creates or replaces an active template.

### Sound example preset

`boss-presets/boss-sounds-example/` contains one sound-ready `template.json`, the five
sample event clips, and four attack voice clips in `sounds/voice-lines/`. Audio is
extracted only there; the six player-count presets have empty event filenames and
empty media folders. Copy wanted files from this example into a boss template's own
`sounds/` folder and configure its filenames, or copy the entire example folder into
`boss-templates/` to activate the example as a template. Existing example files are
preserved on restart. `/boss create preset boss-sounds-example my_boss` copies this
example without manual file operations. `/boss create preset 2_players my_boss`
creates a balance preset without audio. `/boss create new my_boss` starts with defaults.
Preset copying uses Minecraft's I/O pool with one pending job, a 96-file/32 MiB bound,
and a temporary folder published only after all validated files are ready. Clients receive the selected audio from the server normally.

### Encounter music

Each template can choose one looping track. Music starts on activation for players
in the arena selected by `/boss select arena <template>` (or the configured boss-bar
radius). Entrants join the music; death, removal, unload and leaving the arena fade
it out. A dormant boss does not play music. The default fade is two seconds; use
zero for an immediate stop.

Put the track in `boss-templates/<template>/sounds/boss_music.ogg`, then set this
root section in the template:

```json
"music": {
  "enabled": true,
  "file": "boss_music.ogg",
  "volume": 0.75,
  "loop": true,
  "fade_out_seconds": 2.0
}
```

A blank filename disables music. Volume is 0â€“4 and uses Minecraft's Music and
Master sliders; fade is 0â€“10 seconds. Mono or stereo OGG Vorbis works for music
because it plays evenly across the arena, without distance attenuation. Event and
voice sounds keep their separate positional behavior. Music must have a lowercase
basename with no spaces or paths, fit within 8 MiB minus 1 KiB, and last at most ten
minutes. Ordinary event/voice files retain their one-MiB/thirty-second limits.

The server validates files on native I/O workers and shares receipt-controlled
16 KiB chunks, with a 64 KiB in-flight window. Clients use Minecraft's native
streaming channel and looping decoder; a complete decoded PCM track is never
allocated. Header/CRC/hash checks, bounded reads, regular-file confinement and
reconnect/death tokens prevent unsafe or stale playback. The first download may
delay music on a slow connection; later playback uses a four-file compressed cache.

Each listener starts at the beginning rather than a shared timestamp. One encounter
track plays per client: the most recently entered ready encounter takes priority,
with up to sixteen overlapping encounter requests. Switching encounters fades the
previous track first. Vanilla ambient music is held while the boss track/fade is
playing and resumes its normal scheduling afterward. `/stopsound @s music` clears
encounter requests until re-entry or `/boss reload`. Device/resource reloads resume
eligible cached music from its beginning; disconnect stops it immediately.

Generated templates and all bundled presets include the documented music section
with an empty filename. Existing templates can add the section above; no track is
bundled and no live config is rewritten automatically. Use Preview.19 on client
and server: the shared media channel version changed for the larger music assembly.

### Skin selection

`skin.type` supports `player`, `default`, and `custom`. `player` maps the named Minecraft account's 64x64 skin onto the Warden model, with nearest-neighbor scaling and no vanilla base-texture overlay. `default` uses Minecraft's normal Warden texture. For `custom`, put the PNG named by `skin.file` in the server's `config/clockwork-bosses/boss-templates/<template>/skins/` folder. The server sends it only to clients tracking a Clockwork Boss that uses that file, so clients do not need separate copies. A custom file must be a PNG measuring exactly 128x128 pixels in the vanilla Warden texture-atlas layout; it is sent and loaded as-is without scaling or remapping. Keep it at or below 512 KiB. Use a lowercase `.png` basename with no spaces, capitals or directory path. Run `/boss reload` after replacing a PNG to refresh the server cache and currently tracking clients.

For example, set `skin.type` to `custom`, leave `skin.name` blank, and set `skin.file` to `clockwork_warden.png`.

The generated player atlas begins transparent and receives mapped skin pixels with no blur, averaging, sharpening, jaw mask or base-texture blend. As requested, the separate moving chest rib plates retain their original Warden pixels so the torso and opening ribs remain distinct. The emissive face uses 2x2 atlas-pixel eyes, lowered one atlas pixel, with a darker inner-bottom pupil pixel and unchanged slanted eyebrows in place of the mouth on the normal 128x128 atlas; glow layers keep their native coverage and pulse. The client render hooks are gated by the Clockwork Boss marker, so normal Wardens keep their original textures and emissive layers.

## Phases and effects

### Player buffs in the boss-bar area

Each template can grant Speed, Jump Boost, Regeneration, Strength and Resistance inside its
selected `boss_bar.visibility_area`. Haste is excluded. Set `boss_bar.area_effects.enabled`
to `true`, then enable/disable each effect and choose its human-readable `level` from 1 to 5.
Level 1 means I, not Minecraft's internal amplifier 1. Resistance is the defense effect;
level V prevents all damage that native Resistance can reduce.

```json
"area_effects": {
  "enabled": true,
  "speed": { "enabled": true, "level": 1 },
  "jump_boost": { "enabled": true, "level": 1 },
  "regeneration": { "enabled": true, "level": 1 },
  "strength": { "enabled": true, "level": 1 },
  "resistance": { "enabled": true, "level": 1 }
}
```

Place this object inside `boss_bar`, alongside `color`, `overlay` and `visibility_area`.
New templates include it with the master switch off; older templates without it grant no
buffs. Edit the template and run `/boss reload` to update current viewers immediately.

After activation, entry applies native infinite-duration effects with icons and no particle
cloud. Exit clears only this mod's grants, normally within the existing ten-tick (half-second)
boss-bar check. No extra timer, spatial query or repeating effect refresh is added. Buffs also
end on player death/disconnect/respawn and boss death/removal/unload/shutdown. Overlapping
active areas use the strongest configured level, and leaving one preserves contributions
from the others.

Existing potions, beacons and other mods' effects of the same type take precedence, even
when weaker than the requested area buff. Native effect events resume the area grant after
the external effect ends; clearing effects while still eligible also resumes the area grant.
Cleanup preserves those external effects. Native saved entity tags with the reserved prefix
`ClockworkBosses_AreaEffect_` allow matching infinite grants to be removed on login after a
crash. Effect application/removal still respects other mods' Fabric permission callbacks.

The default phase cutoffs are above 75% health for ANNOYED, 75%-50% for AGITATED, 50%-25% for ENRAGED, and 25% or less for FURY. Each phase can set its own movement speed, knockback resistance, follow range, scale, melee and Sonic Boom settings, emissive color, and Darkness settings. `scale` is a multiplier from 0.25 to 4.0; `1.0` is the normal Warden size. A phase value of `null` inherits the top-level `scale`. Omitted per-phase attack values inherit from the top-level `attacks` object.

When a phase enables melee, close targets take priority for melee and cancel Sonic Boom
charging. When that phase disables melee, enabled Sonic Boom can start and finish charging
at close range. Its configured cooldown, charge time, maximum ranges and line-of-sight
requirements still apply. Set `phases.<phase>.melee.enabled` to `false` in each desired phase;
the base `attacks.melee.enabled` is inherited only when the phase omits its override.

`warden_effects.color` is a six-digit hex color used to tint only the emissive glow masks. A phase can override it. A real phase transition can show a short white flash and a one-time burst of small dust particles colored to match the phase being entered. The burst is on by default, uses 128 particles of size `0.65`, and is sent only at phase changes (not per tick); its count remains capped at 128 for server and client safety. Each phase can still override the flash and particle settings.

### Attack voice lines

Put your voice clips directly in `config/clockwork-bosses/boss-templates/<template>/sounds/voice-lines/` on the server,
then run `/boss reload`. Use OGG Vorbis filenames such as `voice01.ogg` or `voice_line_1.ogg`, without
directory paths. Use mono OGG for directional playback at the boss. The folder is shared by templates; clips are selected uniformly
from up to 64 valid filenames. Subfolders are not searched. An empty folder is silent.

Each template has:

```json
"voice_lines": {
  "enabled": true,
  "chance": 0.25,
  "volume": 1.0,
  "pitch": 1.0,
  "range": 32.0
}
```

`chance` is 0-1 (`0.25` = 25%). Each successful melee or Sonic Boom hit rolls once
while the boss's voice is idle; failed/blocked hits and cancelled charges do not roll.
Busy attacks are skipped. A slow connection retains at most the latest waiting voice for a boss; a waiting event expires after eight seconds before transfer starts. Only one voice can be pending or
playing for the same boss on a client, including during decoding and pause. Volume
is 0-4, pitch 0.5-2 and audible range 1-128 blocks. Event sounds such as death remain
independent of voice lines. Missing voice settings use these defaults; disabling
voices stops new lines and lets the current clip finish.

The existing audio safeguards apply: bounded file size and OGG structure/checksums,
mono/stereo Vorbis, at most 30 seconds, bounded decoding/caches/channels and native
audio cleanup. The server reads duration from validated OGG metadata to suppress
repeat delivery; the client independently guards actual playback. File discovery
runs when a template is loaded or reloaded, with no extra tick watcher. Its catalog is released when the last loaded instance leaves. Invalid selected clips are
skipped until reload. Install the same jar on both sides and restart: this feature
uses the media chunk/receipt channels plus a boss UUID and voice category in the sound payload. The versioned native channel (`boss_sound_v3`) prevents older clients from decoding that new layout.

### Custom event sounds

Use **mono OGG Vorbis** for event sounds and voice lines so they are positioned at the boss and fade with distance. Stereo files are accepted, but lack normal 3D positioning. A long clip stays at the boss position where playback started.

Place `.ogg` Vorbis files in the server's `config/clockwork-bosses/boss-templates/<template>/sounds/` folder. Set `sounds.spawn.file`, `sounds.phase_change.file`, or `sounds.death.file` in each template to a filename such as `boss_phase.ogg`; leaving the filename blank disables that event. Each `phases.<phase>.sounds.phase_change.file` can override the sound played when entering that phase, with optional per-phase volume, pitch, and range. Null or omitted values inherit from the shared phase-change settings. An explicit phase filename takes precedence even if its file is missing; `""` mutes that transition. Filenames must match the actual files, including case. Spaces are not allowed; use `phase2.ogg` or `phase_2.ogg`. The initial phase at spawn is not a phase transition, so its sound is not played then; the spawn sound plays once when the dormant boss activates and starts emerging, through area entry, redstone or `/boss activate`. `/boss spawn` is silent. The server sends the selected file to nearby clients when that sound first plays, so clients do not need a separate resource pack or local copy. Names must match case exactly on the server. Use lowercase ASCII letters, numbers, dots, underscores or hyphens; no spaces, capitals or directory paths. Only single filenames are accepted; files must be at most 1 MiB minus 1 KiB reserved for packet metadata, contain at most 4096 valid OGG pages, and pass Vorbis-header allocation limits and page-checksum validation. Comment/setup headers are capped at 128 KiB each; unusually complex codebooks, mappings or oversized tags are rejected before Minecraft decodes them. Clients validate them again before decoding, then cap decoded audio at 8 MiB and 30 seconds of mono or stereo playback. Compressed and decoded caches are bounded and cleared on disconnect. Playback follows Minecraft's pause/resume, Master/Hostile sliders, category gain changes, and `/stopsound` for all or Hostile sounds. Volume uses the client's Hostile sound slider, and each event can set its own volume, pitch, and audible range. Run `/boss reload` after replacing a sound file. Generated sound sections group field explanations above their values.

The selector arrow is automatically selected in the hotbar when `/boss select` gives it to you. Its enchantment glint is only a visual inventory marker; it has no gameplay effect.

The release contains no AI tracing command, target-change logging, periodic AI snapshots, or activation/emergence diagnostic messages. Errors and warnings for configuration, skin, sound, and encounter failures still report the information needed to diagnose those failures.

Underground Darkness is configured at the top-level `dormant_darkness` section. After emergence, `phases.<phase>.darkness` controls each health phase, including phase 1. A missing/null dormant section preserves older templates by using the current phase while hidden; missing/null fields within a present dormant section inherit phase 1. No new watcher is added. Older templates with a top-level `darkness` object remain supported when a phase has no Darkness settings. The manager watcher exists only while at least one Clockwork Boss is loaded, and checks dormant activation and boss-bar visibility every ten server ticks. Active bosses update phase, attacks, and emergence from the Warden AI step; active Darkness spatial checks are rate-limited to ten ticks or the shorter configured refresh interval.

## Implementation notes

See [ARCHITECTURE.md](ARCHITECTURE.md) for the responsibilities of the server manager, selector, template store, render hooks, and skin mapping pipeline. The preflight notes and performance audit document the specific server/client checks and lifecycle protections.

## Darkness field guide

Use `dormant_darkness` for the hidden boss waiting underground, and `phases.<phase>.darkness` after emergence. Both sections use the fields below and replace vanilla Warden Darkness only for marked Clockwork Bosses. New templates and bundled presets include the separate dormant section; existing templates can add it manually without recreating a boss. Run `/boss reload` after editing.

| Field | Meaning |
| --- | --- |
| `enabled` | Stops new applications/refreshes when false. Existing effects expire naturally. |
| `max_radius` | Spherical distance in blocks, including height. Zero disables the aura. |
| `edge_interval_ticks` / `center_interval_ticks` | How often a player becomes eligible for a refresh at the edge/center. Values blend with distance. |
| `edge_duration_ticks` / `center_duration_ticks` | How long each effect application lasts at those distances. Shorter than the interval creates gaps. |
| `edge_amplifier` / `center_amplifier` | Keep at 0. Priority between overlapping Darkness effects; zero is level I. Increasing this does not make Darkness visually darker or set priority against Night Vision. |

At normal server speed, 20 ticks is one second. Default templates and presets use:

| State | Interval | Darkness duration, edge to center |
| --- | --- | --- |
| Dormant underground | 10 seconds | 1-2 seconds |
| Annoyed | 10 seconds | 1-2 seconds |
| Agitated | 8 seconds | 2-3 seconds |
| Enraged | 5 seconds | 4-6 seconds |
| Fury | 1-second refresh eligibility | 6 seconds, refreshed for continuous coverage |

Existing effects refresh only when half their configured duration remains. Continuous coverage
uses a duration at least twice the interval plus a scan margin. A stronger existing Darkness
effect is respected. These are game-time intervals; low server tick rates lengthen them.

The aura center is the entity's actual position, including its scaled hidden offset while dormant. Depth is five blocks at normal size, ten at scale 2 and twenty at scale 4. Initial phase assignment, reload and world rehydration update the holding position; unchanged watcher checks do not reposition it. New spawns reserve the initial depth above minimum build height. There is no aura during emergence. Activation clears the underground refresh deadlines so they cannot delay the active aura. Phase changes and disabling the aura stop/reconfigure future refreshes; they do not remove an already applied effect. The watcher samples dormant bosses every ten ticks; active scans can use a shorter configured interval.

## Connection and cleanup safeguards

The server reads skins and audio on Minecraft's I/O pool, with four reads at a time per
media cache and a bounded backlog. File snapshots change on `/boss reload`; attacks
and tracking callbacks do not stat/read files on the server thread. Autocomplete
uses cached template names. Activation and boss-bar boxes are compiled on load/reload.

Custom PNGs and first-use OGGs travel in 16 KiB chunks over Fabric's native connection.
Each player has a 64 KiB send window, one file being assembled, and at most 64 waiting
identities / 8 MiB of waiting wire data. Receipts contain only a transfer UUID and byte
offset: clients cannot choose a file or server path. Skin transfers take priority over
waiting audio when capacity fills. Stale waiting audio is dropped after eight seconds;
an already started transfer finishes normally. Large first-use files can still arrive
late on slow connections. Repeated audio uses small playback metadata while cached.

Client teardown clears render resources on the client thread, and old connection/file
callbacks cannot publish into a new session. PCM caches use bounded Java heap memory;
temporary native upload memory is released after OpenAL copies it. Boss removal/unload,
player leave/respawn and server stop release owned bars, buffs, selections and queues.
Phase flashes use local client age, so server/client tick counters need not match.

Use **0.1-preview.17 on both sides and restart both**. Native Warden metadata added
in these previews requires matching clients; older clients can fail when tracking a boss.
`/boss reload` applies configuration, not code updates.
Player account skins still resolve on each client through Minecraft/Mojang services.

The 26.3 hooks were checked against the installed Minecraft bytecode and Fabric source.
Normal Wardens and foreign emissive textures retain their own rendering/AI paths.
Custom file audio borrows native channels but has no resource-pack SoundInstance:
mods that process only SoundEngine.play may not apply their effects to these clips.
This review does not establish compatibility with every mod or connection condition.

## Audit and builds

`gradlew build` includes the dependency-free `auditChecks` regression suite. `PREFLIGHT.md` records current verification and older results; `PERFORMANCE_AUDIT.md` describes resource bounds and remaining repeating work. One universal jar contains both server and client entry points. Install the same version on both sides and restart them to load code changes; `/boss reload` only rereads template/media files.


## Preview.14 template-local media

Creation, saves, suggestions and reload use `<template>/template.json`. Bundled presets
use the same copyable folder layout. Sounds and PNG transport include a validated
template identity; caches and pending deliveries cannot confuse equal basenames.
Changed sound, skin and chunk channels are versioned, so older clients cannot decode
the new media layout. Both client and server must run this preview and restart together.
Path validation rejects linked template/media parents and linked files. Existing bounded
OGG/PNG reads, chunk backpressure, decoding limits and disconnect cleanup remain in use.
Compilation and packaging succeeded; this change has not been exercised in-game.


## Boss health and attacks during phase changes

Marked bosses support **1â€“1,000,000 maximum health points**, including the bundled
1000/1400/1800/2200/2600/3000 HP presets. Minecraft still owns the single health pool,
attribute modifiers, damage, healing, save data and synchronization. Only each marked
Warden's own MAX_HEALTH instance uses the expanded range; ordinary entities retain
vanilla's limit. The final value after native modifier math is bounded to the boss range.
Reload preserves the current health fraction. Native entity loading preserves saved HP
and does not heal older bosses that were previously capped.

Before native AI or a direct melee attempt, the health-selected phase and current child's
attributes are applied. Melee permission, damage and knockback therefore agree. The
last completed swing retains its captured cooldown/recovery across transitions;
rejected checks do not consume the delay. A separate first-hit flag makes world tick zero valid.

A Sonic Boom captures its whole definition when charging begins: charge deadline,
damage, cooldown, range, line-of-sight requirement, knockback and close-range melee rule.
Phase changes, healing and reload affect the **next charge**. Even disabling Sonic Boom
on reload permits an already charging beam to finish using its original definition.
Lost/switched/invalid targets and the captured obstruction/range rules cancel the charge;
entity unload/removal clears runtime attack state. No extra tick watcher was introduced.

`gradlew runGametest` runs native combat regressions in `build/combat-gametest`, a
disposable server/world. This separate test mod is excluded from the universal jar.
`gradlew build` runs the standalone audit suite; native GameTests are a separate check.


## Preview.16 â€” native sounds, opponent rotation and radius regions

The mandatory random rotation introduced here is superseded by Preview.17's
configurable switch chance and nearest/random/farthest modes below.

`sounds.mob_volume` controls vanilla Warden sounds, from 0 (mute) to 1 (vanilla),
default 0.25. Heartbeat uses the same synced setting on clients. The server's
custom OGG events and voice lines retain their individual volume/pitch settings.
Ordinary Wardens and other mobs retain their normal sounds.

After every completed melee swing or Sonic Boom, the boss picks uniformly among
other eligible connected players in its visibility region and native follow range.
Without a configured visibility region, follow range is used. Creative, spectator,
dead, disconnected, allied and invulnerable players are excluded by native eligibility.
With no alternative player, the current target stays. Cancelled charges and disabled/
cooldown-rejected melee checks do not rotate targets. Native anger, target memory,
navigation and cooldown remain in use; no extra tick targeting task is added.

For a moving boss-bar sphere, edit `boss_bar.visibility_area`:

```json
"visibility_area": { "shape": "radius", "radius": 32.0, "dimension": "" }
```

For an emergence-centered activation sphere, edit `activation`:

```json
"activation": { "type": "player_enter_radius", "radius": 32.0 }
```

Both radii are 3D distances, bounded to 1â€“256 blocks, checked by the existing ten-tick
watcher. Radius boss bars follow the Warden and share membership with area buffs.
Activation keeps outside-to-inside entry semantics; spawning while inside does not
activate immediately. Trigger edits on reload establish a fresh occupancy baseline.
Selected boxes and redstone remain available; the selection arrow saves box mode.
Use `/boss reload` after editing settings. Update every client with the server:
Preview.16 adds native synced metadata for mob sound volume.

## Preview.17 â€” targeting, announcements and charge animation

Base targeting controls are editable in every newly generated template/preset:

```json
"targeting": {
  "switch_chance": 0.5,
  "mode": "random",
  "announcement": {
    "enabled": true,
    "text": "RUN, <player>!",
    "cooldown_ticks": 60,
    "display_ticks": 20
  }
}
```

Existing templates can add the targeting object above; omitted controls use these defaults. The 50% switch chance is the chosen mod default. Set 0 for never or 1 for always
when an alternative exists. Modes are nearest, random and farthest. The previous
victim/current opponent is excluded by UUID, including replacement player entities.
Native eligibility, follow range and the configured encounter region bound the
pool. A failed roll or no alternative retains the target. Invalid/mismatched
configured regions do not broaden the pool; an unset region uses follow range.

Every field supports overrides under phases.<phase>.targeting; null/missing
inherits its base value. The phase at attack completion selects this policy.
A blocked native melee attempt rolls, but a disabled/cooldown-rejected check or
cancelled beam does not. Initial native acquisition remains native behavior.

Titles name the actual new attack target, including first acquisition, and go to
that player plus current boss-bar viewers. Same-target assignments stay silent.
Each recipient has one shared cooldown across bosses; suppressed titles are
never queued and never delay attacks. The default holds for 20 ticks with 2/4-tick
fades, with at least 60 ticks between titles. This uses Minecraft's shared title
HUD; disable announcement.enabled when another feature should own that HUD.

Captured charge_time_ticks drives native Sonic Boom windup keyframes. A 68-tick
charge winds up half as fast as the native 34-tick charge; a 17-tick charge twice
as fast. Zero fires immediately. Post-shot recoil now uses captured
`recovery_time_ticks` and extends the cooldown when longer. A new
shot replaces recoil; retarget/cancel/removal stops a pending windup. Reload or a
phase change cannot alter an already captured shot's timing. Late tracking uses
current synced progress, not a new full-length windup. Native shared game-time
estimation cannot eliminate network delay: late packets can still show feedback
late or skip short intermediate shots. Damage remains server authoritative.

The native charge sound keeps its normal pitch/duration; it is not time stretched.
For long charges the sound may finish first; short charges may fire before it
ends. sounds.mob_volume controls these native sounds. This change adds no attack
recovery, big-attack behavior or animation framework.

### Project naming and saved-data compatibility

The IntelliJ project directory is now **clockwork-bosses/**. Reopen its build.gradle
and refresh Gradle if IntelliJ was open at the old location. Packages are
clockwork.bosses; public classes use ClockworkBoss*, with ClockworkBosses as the
entrypoint. Mixin resources use clockwork_bosses.*.mixins.json and the default
catalog is default-clockwork-boss.json. Native Warden model/class names remain
Minecraft API names. Runtime folders/worlds were preserved by moving the full project.

The public mod ID/packet namespace clockwork_bosses, /boss command, and existing
config/clockwork-bosses/boss-templates/<name>/ layout retain their established
identities. Existing template contents need no conversion. Saved boss and selector
keys now use ClockworkBosses_. ClockworkBossLegacyData reads the old saved spellings
(including the oldest EventWarden marker), with new keys taking precedence; native
save writes new owned keys. Selector normalization preserves unrelated item data.
Old spellings remain only in that compatibility adapter, its regression fixtures,
and historical migration notes. No live world/config migration was performed.

Development checks are separate: Gradle auditChecks, runGametest and runClientcheck.
The client checker loads native model assets, compares transformed poses, reports
an explicit result and closes. Both development test mods are excluded from the
universal jar. These checks do not establish compatibility with every mod or an
actual high-latency multiplayer connection.


## In-game template editor (Preview.20)

- `/boss template edit` opens a picker with separate Templates and Presets tabs.
- `/boss template edit <template>` opens an active template directly.
- `/boss template edit preset <preset>` opens a reusable preset directly.
- `/boss template delete <template>` removes an active template from the list. Presets cannot be deleted by this command. Loaded bosses using the template must be removed first. The whole folder is retained under `config/clockwork-bosses/deleted-templates/`; remove encounter instances before deleting their template, including instances in unloaded chunks.

The gray editor uses native Minecraft text boxes/buttons, dropdowns, collapsible nested sections, and a scrollable section index. Scroll the left column to reach later sections; scroll the form to reach later fields. Narrow GUI layouts stack labels and inputs; wider layouts place them side by side. Numeric fields show inclusive ranges; hover/focus provides the same field descriptions as generated config comments. Base/inherit explicitly stores null for an override; an empty sound filename explicitly disables that event. Unknown structured values are preserved.

Save changes the selected server-owned file. It does not apply settings to running encounters. Reload saved applies saved templates to all loaded bosses, using `/boss reload`; save pending edits first. Preset edits affect future copies, not existing active templates. Cancel/Close warns before discarding edits. Every request requires native gamemaster permission and a server-issued session token. Sessions expire after 30 minutes and are cleared on disconnect. Revision hashes reject stale edits from another admin or hosting-panel changes; one rolling `template.json.gui-backup` retains the prior file. The server acknowledges only after writing and rereading its file. When the mod runs on the authoritative server, this is its local filesystem; inspecting an S-drive rclone cache is not independent remote-persistence verification.

### Adjustable phases

Manage phases adds, removes and reorders 1â€“16 health stages. File insertion order is fight order. Each non-final stage needs a descending leaving-health cutoff in 0â€“100%; equality advances to the next stage. The final stage handles remaining health, with no cutoff. New phases inherit base attacks, targeting, visuals and scale until overridden; editing does not duplicate audio. Adding a final phase gives the previous final stage a cutoff halfway below the preceding cutoff; all other existing cutoffs stay unchanged. Reordering may require editing cutoffs before Save. Combat compiles this list once on spawn/load/reload; no new watcher or per-tick JSON work is added. Existing default templates retain their 75/50/25 transitions.

### Skin and eye preview

Skin preview / eyes uses the same native Warden renderer, player-to-Warden mapping and glow masks as a spawned boss. Enter player/default/custom settings and press Load / refresh skin preview. Player skins use the existing bounded client lookup/cache. A custom PNG is read from the selected template or preset's own skins folder and sent through the existing bounded PNG chunk transport, under an isolated GUI texture identity. PNG rules remain 128x128, at most 512 KiB, lowercase filename only.

Drag the model to rotate it; switch between head and full-body views or reset to face forward. Choose a preview phase to see its glow tint. Select Both/Left/Right eyes and use arrows to move by one atlas pixel, or edit the coordinate boxes. `skin.glowing_eyes` stores left_x/left_y/right_x/right_y, whole numbers 0â€“14 from the 16x16 front face's top-left. Eyes stay 2x2 with darker pupils; eyebrows do not move. The detached preview entity never enters the world or runs AI. Its custom assets are released on closing; player textures retain the existing bounded shared cache.

Preview.20 changes Warden entity metadata for eye coordinates: update the server and every connecting client together. Compile/package verification completed; live GUI-scale, renderer and dynamic-combat testing remains outstanding.

## Preview.21 editor refinements

The left index is now Base settings, Skin preview / eyes, Spawn, Sounds, Dormant darkness, Activation, Arena / boss bar, Arena effects, Manage phases, then the configured phases. Base settings includes Attacks, Targeting and Warden effects. Sounds contains spawn/death/base phase-change sounds, music, voice lines and each phase's sound overrides. Entering any page folds its nested sections. Open parents are highlighted, and their children are indented. Reload saved sits beside Save and Cancel in the bottom action row.

Skin settings and eye positioning share one preview page with separate collapsible Skin and Glowing eyes sections. The eye pad is arranged as a cross with Reset in the middle. Both/Left/Right chooses which eyes move or reset; Reset restores the default coordinates (3,7 and 11,7), leaving eyebrows fixed. Full-body preview is the initial view, with head zoom and rotation controls. The native inventory extraction path and foreground PiP stratum are used; failures now produce one actionable client-log warning per refresh instead of being hidden. Runtime preview behavior still needs an in-game check.

Arena shape box exposes min/max coordinates; radius exposes only radius. Activation exposes min/max coordinates for player_enter_area, radius for player_enter_radius, or position coordinates for redstone. Hidden values are retained when switching modes. Optional coordinates shown in the editor do not become active merely by opening a page or saving unrelated settings. Target switch chance is shown/entered as 0â€“100% in the GUI; the underlying template retains its existing 0â€“1 probability format.

Music now has a channel dropdown using Minecraft's native sound categories. `music` retains the old behavior; `record` follows Jukebox/Note Blocks; `master` uses Master only. Every category still follows Master. The selected category applies to live volume changes, category-specific /stopsound and menu pause exemptions. Missing/invalid hand-edited channel values use music. The changed music control payload is versioned: update client and server together to Preview.21. Existing installed presets/templates are not overwritten; missing channel fields are editable in the GUI and new bundled presets include them.

### Preview.22 preview correction

The detached model now receives a nonzero native entity ID before its render snapshot is extracted. Minecraft 26.3 item-model extraction rejects the default unassigned ID even when a preview has no equipment. This is a render-only object; it is not spawned into the client or server world. The change is client-only and compatible with a Preview.21 server. Compilation succeeded; in-game visual confirmation is pending.

### Preview.23 face controls and navigation

The skin preview adds separate eyebrow controls, Narrower/Wider eye spacing and independent Scale X/Y fields (0.5â€“4; rounded to 1â€“8 pixels). Eye/brow coordinates keep their shapes within the face. The preview keeps its original height, zooms the full-body model out by 10% and displays stronger steady face glow for alignment; live glow keeps its pulse. Darkness fields include brief inline explanations and recommend keeping both amplifiers at 0. Client and server must use matching Preview.23 jars for the new brow metadata.

Manage phases starts collapsed. Click it to show or hide the indented phase pages; the expanded parent and child grouping are highlighted. Each phase still has its own settings page. The Manage phases page contains add/remove/reorder controls.

The pending editor/command changes above are now included in Preview.23. Test Client and the test server received matching jars; gameplay and GUI confirmation remain outstanding.

Beta RC 1 preview refinement: alignment eyes/brows use the selected phase hue at full saturation and brightness, with darker pupils. This makes pastel phase colors easier to distinguish from the skin. In-game glow continues to use the configured RGB and pulse.

## Beta RC 1 targeting safeguards

`follow_range: 0` now means automatic arena sizing: the boss-centered arena radius, or the box's full 3D diagonal, capped at 256 blocks. An unset arena falls back to 24 blocks. Positive values remain explicit overrides. New default/preset phases inherit the base range. Existing installed templates are unchanged: set base follow_range to 0 and clear any positive phase overrides to use automatic sizing, then reload. Follow range controls detection/navigation; Sonic Boom range still independently controls shot reach.

Base `targeting` and each `phases.<phase>.targeting` now support:

| Field | Default | Meaning |
| --- | --- | --- |
| `min_target_ticks` | 60 | Keep a valid target for at least three seconds before switching. 0 disables the time gate. |
| `min_attacks` | 1 | Minimum completed attacks before switch-chance rolls. |
| `max_attacks` | 4 | Force rotation to another eligible player at this count, even if switch_chance is 0. Must be at least min_attacks. |

The time gate takes priority, so fast attacks may exceed the maximum until the next eligible completion. With no alternative, the current opponent stays targeted. Counts reset on actual target changes and carry across phase changes. Fired Sonic Booms and completed melee swings (including blocks) count; cancelled charges and disabled/cooldown-rejected melee do not. Dead/disconnected/removed/ineligible opponents bypass the hold.

Ranged rotation uses the completed beam's captured horizontal/vertical range and optional line-of-sight rule, rather than the follow range. Arena boundaries still apply. The actual new target can receive a personal warning even after a recent audience warning about another player; recent personal warnings remain throttled across bosses. RUN text and its conditions otherwise remain unchanged. No new polling task is introduced.
