# Preflight — Clockwork Bosses

Release source/build and installed client/server: **0.1**. Older results apply only to their named builds.

## Version 0.1 release checks — 2026-10-10

Numeric input is limited to 128 characters, 64 significant digits and decimal
scale/exponents within -64 to 64. Normal decimal/scientific notation stays exact;
this guard applies to edited numeric values and reward definitions. Imported
malformed percentages display compactly and must validate before edits save.
Exact range comparisons reject values just outside limits even when double
rounding would previously have accepted them. Added audit and native GUI cases.

Passed: 4384 audit assertions, 1038 native GUI assertions, 28/28 server tests
without the pack and 28/28 with the supplied server pack. The isolated packaged
production server reached readiness, spawned/activated/saved/removed a boss and
exited normally (code 0). GUI checks included Sodium, Lithium, ETF and EMF.
Evidence and verified artifact hashes: `../work/release01-results.json`.
Verified jar metadata identifies version 0.1, includes seven presets and excludes
all development fixtures. Artifact: `../outputs/clockwork_bosses-0.1.jar`, 914046
bytes; SHA-256 `A62B75E8DD3F28E5286010A6E6350F3FED0CE25144BC37892ED283CF42DDA3B6`.
Real multiplayer latency, full-pack client play and heap profiling remain untested.
Installed 0.1 in Test Client and the stopped remote test server after backing
up both RC8 jars. Independently verified both installed hashes and exactly one
boss jar per mods folder. Record: `../work/release01-deployment.json`. Existing
templates, media and worlds were preserved; neither application was started.
Nothing was committed, pushed or uploaded to GitHub.

## Beta RC 8 local loot editor checks — 2026-10-10

RC8 is installed in Test Client and the stopped remote test server. Both RC7
jars were backed up, and both installed RC8 copies were independently verified
against the tested build's SHA-256. Record: `../work/beta-rc8-deployment.json`.
Existing configurations, media and worlds were preserved. Deployment did not
start either application or upload anything to GitHub.
All 28 native GameTests passed both with and without the supplied server pack.
Config/media checks passed 4366 assertions. The world-backed native client GUI
runner passed 1023 assertions with Sodium, Lithium, ETF and EMF, including three
GUI sizes, search, add/change/remove, cancellation, percentage conversion and
pending-text preservation. Actual picker screenshots were captured and inspected.

The client fixture initially ran before 26.3 bound item components, then used an
obsolete Escape number. Both test issues were fixed: Fabric now creates/closes
a temporary world, and input uses the native InputConstants key. Production
also refuses picker creation after a disconnect and retains the manual ID/draft
if a mod fails during creative-tab construction. Release jar excludes test mods.
Real remote-network/high-latency play and full-pack client rendering remain
untested. Existing server-pack exclusions/warnings are in MODPACK_TESTING.md.
Fresh evidence: ../work/rc8-*.log/xml and rc8-loot-editor-results.json.

## Beta RC 7 loot checks — 2026-10-10

RC7 is installed in the stopped Test Client and remote test server. Backed-up
RC5 jars and independently hash-verified RC7 paths are recorded in
`../work/beta-rc7-deployment.json`. Existing configs, media and worlds were
preserved. Neither application was restarted; GitHub was untouched.
Universal assembly and the generated
Gradle wrapper passed. All 24 native cases passed without the server pack and
with it; 4332 audit assertions passed. New death/reward cases verify table
validation, native contexts, keep/replace, exactly once, vetoes and cleanup,
gamerules, persistence and editor/reload transactions. See [LOOT.md](LOOT.md).
Real client GUI interaction, network latency and RC7 production-mode launch
remain untested; no external combat-mod compatibility guarantee is claimed.

## Beta RC 6 supplied pack — 2026-10-09

Source/local jar: RC6. Installed jar: RC5. All 17 native checks passed with the
provided server pack and without third-party mods; 4316 audit assertions passed.
The actual release jar ran in production mode with the supplied pack, reached
readiness, spawned/activated/saved/removed a boss and stopped with exit code 0.
Development fixtures and third-party jars are excluded from the universal jar;
all seven bundled presets remain present.

RC6 artifact SHA-256:
`DF9B2D19751A8ED9CB64E42762033EF1223C01248577075738B7AAFBB866E1C7`.
Details, test-only overrides and remaining warnings:
[MODPACK_TESTING.md](MODPACK_TESTING.md). This is not a multiplayer latency,
visual rendering or enabled external-service test.

## Beta RC 5 local compatibility checks — 2026-10-09

RC5 is installed in Test Client and the stopped remote test server. RC4 backups
and hash-verified installation paths are in `../work/beta-rc5-deployment.json`.
Exact final source passed:

- 4316 offline audit assertions and universal-jar assembly.
- 15 project native GameTests plus the vanilla control, without Lithium.
- The same 16 native tests with supplied Lithium 0.26.2+mc26.3, optimizations
  active and zero config overrides, in `build/compat-lithium`.
- 2700 native client model comparisons with supplied Sodium 0.9.2+mc26.3,
  ETF 7.2.4, EMF 3.3.8 and Lithium, in `build/client-check`.

Logs/reports: `../work/compatibility-native-final.log/.xml`,
`../work/compatibility-lithium-final.log/.xml` and
`../work/compatibility-render-client.log`. Development fixture classes and
third-party test jars are excluded from the release jar.

Client startup/model checks do not prove rendered glow/skin output or custom
resource-pack model support. Better Combat and Combat Roll are absent from the
provided snapshot. Full multiplayer latency/reconnect/grave and visual tests
remain in [COMPATIBILITY_TESTING.md](COMPATIBILITY_TESTING.md).

## Beta RC 4 activation/save fix — 2026-10-09

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

## Beta RC 3 startup fix — 2026-10-09

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

## Beta RC 2 deployment — 2026-10-09

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

## Recovery and player health/potion support — 2026-10-09, pending source

- Recovery is a timed child (`type: recovery`, `duration_ticks: 1–12000`, default
  60). Native `Brain.stopAll` finishes running behaviors once on entry;
  narrowly guarded behavior hooks pause execution while native sensors and
  memory expiry continue. Navigation stops; health phases/aura/physics continue.
  Existing Warden AI checks one deadline, then uses the existing order/roar path.
  Direct melee and custom beam paths are also gated. Generation reset cancels
  the pause on main-phase changes/reload. Ordinary mobs/Wardens pass through.
- Added independently enabled arena health floor and reusable potion. Native
  MAX_HEALTH dirty updates reconcile armor changes; no additional tick watcher.
  Health fractions survive floor changes. A saved bounded boss UUID ledger grants
  one initial full heal per participant, including late arrivals. Removal,
  unload, death, disconnect, respawn and server stop release owned grants.
- Vanilla potion components/drinking/cooldown presentation are reused. Server
  checks owner, physical arena and a saved shared deadline at start/completion;
  marked completion neither consumes the bottle nor creates glass. Defaults:
  20 HP, 15 seconds, 20 hunger, 20 saturation. Inventory-full behavior is safe;
  original offhand restoration uses exact stack identity and never overwrites.
- GUI hides attack fields for Recovery and groups player support under Arena
  effects. Old missing support sections stay virtually disabled; new defaults
  and all seven bundled presets explicitly include the recommended values.
  Comments explain inheritance, floors, once-only healing and cooldown behavior.
- Common/client compilation and conservative unused-import inspection completed.
  No packaged/deployed jar, new tests, game launches or runtime verification.
  Next gameplay checks: recovery timing/phase interruption; 20/100+ armor floors;
  border/reconnect/reload/respawn; late heal; offhand full/moved inventory; drink
  interrupted by exit/death; copies/shared cooldown; overlapping encounters.

## Sequence/random ordering and roar interludes — 2026-10-09, pending source

- Replaced the previous unpublished mixed attack/roar child list with attack `subphases` and a separate `roars` catalog. Per-phase sequential/random ordering and an interlude on/off control live beside those lists. Sequential cycles the roar list independently; random uses a bounded per-boss attack shuffle bag and picks a roar independently at each transition. Every child runs once per random round, with no repeat across a round boundary when there are at least two. First entry has no preceding roar; the loop boundary does have an interlude when enabled.
- Added GUI drag/drop handles for phases, subphases and roars, with an insertion marker, mouse-input edge/wheel scrolling, cancellation outside the list or with Escape, and existing native Up/Down keyboard alternatives. Reordering changes only the draft until Save. Resize, screen removal, page changes and busy server requests discard a drag.
- Extended server order/scalar validation and sound prewarming to roar catalogs. Bounds remain 16 entries per catalog with existing document/patch limits. Updated generated comments, all seven bundled preset resources and project documentation. Existing installed configs/media and Beta RC 1 jars remain unchanged; no jar was packaged or deployed and no tests/game launch were run. Gameplay and GUI interaction need runtime confirmation.
- Custom roar audio replaces Minecraft's own once-only audio cue, about 25 ticks into its native animation; animation completion selects the next attack child independently of clip length. Common/client Java 25 compilation and conservative import review completed successfully.

The earlier subphase record below describes the intermediate source before the separate roar catalog.

## Subphases and delayed melee — 2026-10-09, pending source

- Added bounded, ordered child sequences with parent inheritance, attack-count advancement and native roar completion. Main health transitions/reload reset the sequence; children change combat attributes/policies without changing health, scale, aura, bar or phase effects.
- Added captured melee wind-up/recovery, delayed native damage with UUID/range/visibility/phase validation, and configurable captured Sonic Boom recovery. A delayed swing cancels an unfinished beam; old beam completions cannot advance a replacement sequence. Roar waits for attack recovery and preserves the elapsed native Sonic Boom cooldown when native roar stop reassigns its target.
- Added child management/editing and validated scalar/order patches to the existing GUI, plus grouped timing/inheritance/sound comments in defaults and all seven bundled presets. Empty child maps keep their current balances/behavior. Custom roar media uses existing validated OGG readers, queues and transfer bounds.
- Inspected configured 26.3 native Roar, Warden AI, melee and model bytecode for the hooks/keyframes. Common/client compilation succeeded with Java 25 / Gradle 9.7.0. Conservative import inventory found no unused production import candidates after cleanup. No tests or client/server launch were performed; runtime animation, roar interruption and multiplayer behavior still need gameplay confirmation.
- No new jar was packaged or deployed. Installed Beta RC 1 and server/client configs/media remain unchanged. The next packaged client/server pair must be updated together because attack entity metadata changed.

## Beta RC 1 built and deployed — 2026-10-09

- Java 25 / Gradle 9.7.0 offline `assemble` succeeded with configured Minecraft 26.3 / Loom 1.18.2 dependencies. This includes the saturated preview face, targeting hold/attack budget/automatic range and lifecycle tightening described in the earlier pending-source records. No tests or game launch were run.
- Universal jar: `../outputs/clockwork_bosses-0.1-beta-rc.1.jar`, 792897 bytes. Packaged metadata names Clockwork Bosses, version `0.1-beta-rc.1`, environment `*`. Seven preset configs are included; no AI trace, regression fixture, client-check fixture or audit-check classes were found in the production jar.
- Fresh remote log confirms server shutdown; exact `--gameDir` inspection confirms Test Client closed. The running `Clockwork Town 26.3 2.0.0` profile was not changed.
- Backed up both Preview.23 jars under `../work/install-backups/beta-rc1-20261009-195118/`, then replaced only the boss jars in Test Client and `minecraft:/mods`. Staged and installed remote bytes were downloaded through the configured connection and hash-checked, without trusting the S-drive cache. Remote inventory contains only the new boss jar. Client/server/output SHA256: `05EBD18B5009CF1DA0E55B1EC8BE29F970C82411C831E7C9D3D3CF36B9D1759B`.
- Existing configs/media were preserved. New source defaults and preset resources are packaged; existing positive follow-range overrides still retain precedence. Neither service was started. Runtime confirmation awaits restart and gameplay.

## Beta RC 1 naming — 2026-10-09

Changed the Gradle version to `0.1-beta-rc.1`. Fabric metadata uses the existing version substitution; the next universal jar will be `clockwork_bosses-0.1-beta-rc.1.jar`. Current release references are updated, and historical build records retain their original version names. No compilation, packaging or deployment was performed for this naming change.

## Tightening review — 2026-10-09, pending source

- Fixed music file/transport capacity handling, media-job rejection cleanup and completion outside cache locks, editor external-exit/session cleanup, and music decoder/channel submission failures.
- Reduced repeated preview config parsing, dirty-check document serialization and emissive-mask lookup allocations. Cheap arena/range candidate checks now precede optional target raycasts. Explicit preview refresh invalidates the metadata snapshot so changed skin settings still apply.
- Reviewed loaded-boss removal/death/unload/spawn failure and watcher disposal, player/effect lifecycle, media/texture/audio bounds, stale async generations and native resource ownership. No unused production import candidates were found by the conservative lexical inventory. Preserved the full project and historical documentation.
- Java 25 / Gradle 9.7.0 offline common/client compilation succeeded against the configured Minecraft 26.3 / Fabric dependencies. No tests, client/server launch or runtime memory/latency trial was performed. No new jar was packaged or installed; Preview.23 remains deployed. Pending targeting and saturated-preview changes are still source-only too.

## Phase sound filenames with spaces — version 0.1-preview.8

- Fresh server log lines at 01:46:31/34/39 confirm the phase event is reached but rejects `phase 2.ogg`, `phase 3.ogg` and `phase 4.ogg` as invalid names. The first diagnosis identified a missing-file mismatch but omitted that filenames containing spaces were also rejected. Spawn/death files do not contain spaces and the user reports both play.
- Sound validation now permits ordinary ASCII spaces within the same bounded OGG basename on both sides. Path separators, colons, control characters and leading dots remain rejected. The server still requires a direct regular file in the selected fixed folder, without following a final symlink. Packet sizes, codecs, decoder limits, hashes and transport are unchanged. Folder changes from preview.7 are included.
- Updated normal warnings and config/preset/README filename guidance. No new AI tracing or sound polling was added. Compiled/package confirmation uses Java 25 / Gradle 9.7.0 offline `assemble`; no local game run or automated tests were performed for this change.
- The mounted template copy still selects filenames without spaces, while fresh runtime warnings name files with spaces. That discrepancy requires resolving the mount/panel view before overwriting existing template contents. Both jar installations need updating/restarting to accept spaced filenames on both ends; phase playback remains unconfirmed until retested.
- User confirmed edits were through the hosting panel and then confirmed client/server stopped. Installed preview.8 into both authorized mod folders; source/output/installed hashes match `1E04BF03F0D6E82D25D046A90B1C76021438CD3EAC085060ED1E78D19941AB9C`. Preview.6 backups are under `work/install-backups/preview8-20261008-015626/`. Moved the existing server/client `templates`, `presets` and root voice folders to `Boss Templates`, `Boss Presets` and `sounds/voice lines` through directory moves, without regenerating template contents. No service was started by the agent.
- After moving, the mounted `Boss Templates/1_player.json` still selected the three old unspaced names. Backed it up, hash-checked concurrent edits, and changed only its three file fields to `phase 2.ogg`, `phase 3.ogg`, `phase 4.ogg`; a text diff confirms no other lines changed. Template SHA-256 is `82B3A2DCDB2B90B1E3B0BCE1CAF7F3874C5B88FBF7E44E66A6D6D380DB392076`. A panel refresh is needed before editing the moved file. Restart and audible phase playback confirmation remain pending.

## Folder names and phase-sound diagnosis — version 0.1-preview.7

- Active files now use `config/clockwork-bosses/Boss Templates/`; optional examples use `Boss Presets/`. Voices are inside `sounds/voice lines/`. The packaged preset resource names stay internal; only the administrator-facing config folders change. No automatic migration is added. Current README, generated comment catalog and all six preset comments use the new layout.
- The mounted server's template selects `phase2.ogg`, `phase3.ogg` and `phase4.ogg`, while its files are `phase 2.ogg`, `phase 3.ogg` and `phase 4.ogg`. Server warnings confirm the configured files are missing. Shared `sounds.phase_change.file` is currently empty. The phase-change code correctly selects the entering phase and overlays non-null fields; an explicit filename takes precedence even when missing. Null/missing fields inherit, and empty filenames disable the transition. Clarified that behavior in source/config/README comments.
- User reports preview.6 spawn/death sound playback and smooth behavior otherwise. User is checking phase sounds after matching filenames and reloading; this is not yet recorded as a successful phase-sound test. No audio decoder, transport or AI code was changed for the filename diagnosis.
- Java 25 / Gradle 9.7.0 offline `assemble` compiles the source sets and packages the universal preview. No automated tests or local game run were performed for this folder-only update.

## Entire production-code review — 2026-10-07, version 0.1-preview.6

- Reviewed all 37 production Java files, entry points/mixin resources, defaults/preset catalog and build setup. Rechecked touched native method signatures/thread ownership against installed Minecraft 26.3 bytecode and Fabric API 0.161.0+26.3 source. Static import/reference inventory reports no unused imports; sole-reference Mixin callbacks remain required runtime entry points. Removed the obsolete autocomplete exception path. Terminal Fury has no generated unused exit cutoff; existing unknown config values remain preserved.
- Fixed disconnect render cleanup/thread ownership and rapid reconnect generation checks; local-clock phase flashes; mask-source/tint ownership; bounded CPU image reads and HTTP deadlines; exact player/effect callback scoping; reentrant boss cleanup; native selector player lifecycle; cached region parsing; non-following template reads.
- Added bounded native-I/O server media snapshots and negotiated receipt-driven media chunks. Per player: 16 KiB chunks, 64 KiB send window, one assembly, 64 waiting identities / 8 MiB. No new transport tick listener/executor. Reload/disconnect cleanup and actual packet-order audio LRU ownership prevent stale delivery/cache drift. Waiting audio expires before first send after eight seconds; first-use transfer can still delay playback.
- Guarded Vorbis comment/setup allocation fields before Minecraft/JOrbis decode; retained CRC, duration, hash, decoded-memory and channel bounds. Unusually complex valid OGG profiles may be rejected. PCM cache now uses heap memory; temporary native OpenAL upload memory has explicit idempotent release.
- Updated purpose/method comments and current README/architecture/resource documentation. Kept README, PREFLIGHT, PERFORMANCE_AUDIT, full project and historical audit records. AI trace stays removed. No custom animation, folder migration or target-selection redesign was introduced.
- Java 25 / Gradle 9.7.0 offline `assemble` compiles common and client source sets and packages the universal jar. No tests, gameplay, simulated latency/disconnect trial, adversarial decoder trial or full live-modpack runtime check were run. Install this version on both sides and restart; runtime confirmation remains necessary.
- Inspected the packaged metadata/classes: version `0.1-preview.6`, both entry points, all six presets and no trace classes. Installed the same jar into `S:\mods\` and the authorized `Clockwork Town 26.3` Modrinth profile, with preview.4 backups under `work/install-backups/preview6-20261007-234725/`. Local Minecraft was not running; neither service was started/stopped. Source, output and both installed jars match SHA-256 `66EEF0E68714A6A85D12327FD969C2EDD4F5942D497A83851128BC562C4F6050`. Server and client restart are still required.

## Historical build records

## Attack voice lines — 2026-10-07, version 0.1-preview.5

- Added per-template `voice_lines` (enabled, chance 0.25, volume 1, pitch 1, range 32) to defaults and all six presets. Missing settings use those defaults. Successful melee/Sonic Boom hits call one manager function; busy/dormant/emerging/disabled encounters skip the roll. No extra tick watcher is added.
- Server startup/reload snapshots up to 64 safe direct OGG filenames from `config/clockwork-bosses/voice lines/`, bounded to 4096 scanned entries. Invalid selected files are suppressed until reload. The shared validator reads final sample count/rate for a pitch-adjusted real-time delivery deadline and rejects empty/overlong clips; normal OGG CRC, page, packet and decode bounds remain enforced.
- Client UUID reservations cover pending decoding/acquisition, native playback and pauses, with idempotent cleanup. File LRU access precedes busy/rejected suppression. Event/voice cache namespaces are distinct. Both sides need this build because the sound payload adds UUID/category fields. Its `boss_sound_v2` channel uses native capability negotiation to avoid sending the new layout to an older client.
- Reformatted defaults/presets through the production config writer. Created the mounted server's empty voice folder and added the default voice section to its existing template, preserving its previous settings with a backup and concurrent-edit hash check.
- Java 25 / Gradle 9.7.0 offline `assemble` compiles both source sets and packages the universal preview. No tests or in-game voice encounter were run; audible playback and chance behavior await gameplay confirmation.

## Activation sound, scaled dormancy and config layout — 2026-10-07, version 0.1-preview.4

- Server `latest.log` reports missing `spawn.ogg`, `phase2.ogg`, `phase3.ogg` and `phase4.ogg`. The mounted sound folder contained `Spawn.ogg`, `Phase 2.ogg`, `phase 3.ogg` and `Phase 4.ogg`; `death.ogg` already matched. Backed up the four files and template locally, then renamed the sounds to the configured names. Validation remains unchanged; invalid names and missing files now produce clearer warnings.
- Moved the spawn event from dormant creation to shared activation, after teleporting to the visible emergence point. Area, redstone and manual activation share that one guarded call. Phase transitions still use the entering phase's override; initial assignment/reload remains silent.
- Holding depth is `ceil(5 * max(1, bounded native scale))`, including the initial phase override. Initial phase/depth assignment occurs before adding the entity to the world. Reassignment/reload/rehydration repositions dormant entities without extra periodic work. Spawn height reserves the initial depth; a later reload beside minimum build height clamps the holding position to that minimum.
- Concise comments are grouped above each section, with scalar values kept together. Reformatted all six preset resources and the server's `1_player.json` through the production writer; gameplay values are preserved. Checked the server template hash before replacement to avoid overwriting concurrent edits.
- Java 25 / Gradle 9.7.0 offline `assemble` compiles and packages the universal preview. No tests or in-game sound/scale encounter were run for this change.

## Bundled player-count presets — 2026-10-07, version 0.1-preview.3

- Added the six supplied maze presets as `presets/1_player.json` through `6_players.json` jar resources. The supplied gameplay values are preserved; resource comments reference the current config folder. Maximum health is 500/900/1300/1700/2100/2500 respectively.
- Mod initialization places normal `.json` files in the separate `config/clockwork-bosses/presets/` folder. Existing entries are skipped with no link following; no-replacement sibling-file saves also preserve a file created concurrently. Presets use the normal bounded parser and generated documentation.
- Presets do not enter the template list, which scans only `templates/`. Copy a desired file there and run `/boss reload` to activate it as `1_player`, `2_players`, ..., `6_players`, or rename the copy to another valid template name. Supplied arena coordinates are preserved and can be reselected per template. Missing preset copies are recreated in `presets/` at the next startup; active templates are never installed or replaced by preset initialization.
- Java 25 / Gradle 9.7.0 offline `assemble` compiles and packages the universal preview with all six resources. No new runtime test of startup installation or gameplay was run.

## Configuration folder layout — 2026-10-07, version 0.1-preview.2

- Configuration now uses `config/clockwork-bosses/templates/`, `skins/` and `sounds/`. Skin and sound paths derive from the template store's single config root. Template creation messages, media errors and generated config comments use the new paths.
- Removed automatic legacy-template migration, its migration flag/logger, and obsolete global-spawn archiving. Earlier development files require a manual move; no old folder is scanned or modified. Historical migration records below describe previous builds.
- Source and current README/architecture documentation use the new layout. Java 25 / Gradle 9.7.0 offline `assemble` compiles and packages the universal preview jar. No new runtime test was run for this folder change.

## Sonic Boom with disabled melee — 2026-10-07, version 0.1-preview.1

- Fixed both close-range conditions in `ClockworkBossSonicBoom.tick`: starting a charge and cancelling an existing charge now yield to native melee reach only if the current phase enables melee. A melee-disabled phase can use enabled Sonic Boom against close targets and continue charging when they approach.
- Uses the existing immutable phase snapshot and native reach method. No new AI behavior, target selection, loop or scheduler is added. Config comments and README explain per-phase overrides and attack interaction.
- Version numbering now uses a 0.1 preview pending user gameplay confirmation; historical 1.0.x build records remain unchanged.
- Java 25 / Gradle 9.7.0 offline `assemble` compiles and packages the universal preview jar. No new in-game encounter or runtime regression check was run for this fix.

## Selector block-hit feedback fix — 2026-10-07, version 1.0.5

- Inspected the installed Minecraft 26.3 client bytecode and Fabric interaction module 5.3.6+3434d6d95d. `startAttack` sends a punch packet after `startDestroyBlock` regardless of its result; successful held continuation sends further punch packets. Cancelling only the block callback therefore leaves native swing feedback active.
- Selector attacks now return `CONSUME`. The native `ClientPreAttackCallback` sends one predicted selection action on a fresh press and cancels vanilla start/continue attacks. Holding the click remains suppressed after the server removes the arrow, and the next fresh click restores normal attacks. Air/miss hits, spectators and ordinary items do not trigger selection.
- No new tick listener, mixin, timer or custom packet was introduced. Source comments and README explain the client/server path. Both client and server should use the updated universal jar.
- Java 25 / Gradle 9.7.0 offline `assemble` compiles and packages 1.0.5. No in-game reproduction or new runtime test was run for this fix.

## Player area effects — 2026-10-07, version 1.0.4

- Added optional, documented `boss_bar.area_effects` settings for Speed, Jump Boost, Regeneration, Strength and Resistance; no Haste. Human-readable levels are bounded to I-V. Missing settings and the new-template master default disable grants.
- Effects begin only after activation and follow the selected visibility area. The existing ten-tick query and membership diff are reused; no additional tick listener, watcher or spatial query is introduced. Reload updates unchanged viewers as a configuration event.
- Native infinite effects are granted without repeated refresh. Overlap chooses the strongest boss contribution. External effects take precedence and remain intact; Fabric effect events resume area grants when external effects end. One-time native queued tasks avoid mutation during effect iteration.
- Player death/leave/respawn and boss death/unload/removal/shutdown release requests. Saved vanilla tags permit cleanup of matching grants after a crash. Effect operations respect Fabric permission callbacks from other mods.
- Java 25 / Gradle 9.7.0 offline `assemble` compiles and packages the universal jar. This feature has not been exercised in a running Minecraft encounter; historical regression/gameplay results below are not tests of 1.0.4.

## Clockwork Bosses naming update — 2026-10-07, version 1.0.3

- Displayed mod name is Clockwork Bosses. The command root is `/boss` throughout registration, user messages, source comments, README, architecture notes, and generated template documentation.
- Inspected the installed Minecraft 26.3 command-registration bytecode: vanilla registers `BossBarCommands` with the literal `bossbar`; it has no `/boss` root. The supplied pack's command-class scan found only Carpet's argument-type handling using the exact `boss` string.
- Archive name is `clockwork_bosses`; the Gradle project is named `ClockworkBosses`. The existing IntelliJ workspace directory remains `ClockworkBosses`.
- Technical mod/packet identifiers, saved entity/selector fields, and the existing config directory retain their original values for upgrade compatibility. Historical command names below refer to the builds checked at that time.
- AI tracing remains completely removed from the release source.
- Java 25 / Gradle 9.7.0 offline `assemble` compiled both source sets and produced `clockwork_bosses-1.0.3.jar`. Packaged metadata reports Clockwork Bosses 1.0.3 with the universal environment. Packaged command registration contains `boss`, has no `arenaboss` root, and the default documentation has no old command examples. No new gameplay test was run for this naming update.

## Release packaging — 2026-10-07, version 1.0.2

- Java 25 / Gradle 9.7.0 offline `assemble` compiled both source sets and packaged the universal release jar successfully.
- Inspected the packaged classes: the AI trace toggle methods, flag, target-history fields, periodic snapshot messages, and activation/emergence diagnostic messages are absent. The trace command registration is removed from source.
- This release changes diagnostic code and version metadata only. The earlier 1.0.1 regression and modpack checks below are historical results; no new runtime encounter was run for this packaging request.
- The 1.0.1 jar remains in `outputs/`; the separate release is `../work/preview16-build-history/libs/arena_boss_fight-1.0.2.jar` (historical binary; its original saved branding is preserved) for both client and server.

Older entries below record earlier implementations and tests. The current audit section at the end supersedes their pending checks, five-tick cadence, grayscale/jaw-mask and global-spawn descriptions. AI tracing mentioned in historical checks was removed for the 1.0.2 release.

Checked after adding phase-colored Warden emissive effects, an 8-tick client-side phase-change flash, and optional one-time explosion particles.

- All JSON resources parse successfully.
- Java delimiter/brace/parenthesis scan passes.
- Client-only mixins are in the client mixin config.
- Normal Wardens receive white/default visual data and are not tinted or flashed.
- Visual color/flash state uses vanilla SynchedEntityData, so updates are sent only when the server changes the state.
- Phase-change explosion particles are emitted only on real phase changes, never in the recurring tick loop.
- No `getEntityInAnyDimension` references remain.

Gradle 9.7.0 offline `build` succeeds with the project's Fabric Loom 1.18.2 setup and Java 25.

- Health-boundary checks pass at 100%, 75%, 74.9%, 50%, 49.9%, 25%, and 0.1%: ANNOYED, AGITATED, AGITATED, ENRAGED, ENRAGED, FURY, and FURY.
- A client smoke run loads the mod and applies the emissive-layer, Warden renderer, and render-state mixins. The emissive-layer hook matches Minecraft 26.3's 9-argument `submitModel` call.
- User run confirmed the boss bar advances through ENRAGED and FURY and the optional `EXPLOSION` particle burst appears. Its TNT-like look is Minecraft's vanilla explosion particle.
- The vanilla Warden emissive texture has cyan RGB baked into it. Clockwork Bosses now render a grayscale copy preserving source luminance and alpha, so orange/red tint channels are not suppressed; normal Wardens keep the vanilla texture.
- The custom Clockwork Boss Sonic Boom now yields when its target is in melee range, allowing the normal Warden brain to approach and use melee.
- In-game confirmation of the new grayscale tint and melee-range behavior is still pending; the computer-use bridge exposes no app windows in this session.
- The computer-use bridge exposes no app windows in this session, so IntelliJ itself could not be inspected or operated. The project and its Gradle build are available in this workspace.

## Runtime audit and smoke results — 2026-10-06

- Darkness settings are now defined per phase, with compatibility fallback to a legacy top-level `darkness` object and bounded runtime values.
- `/boss trace` is disabled by default and toggles detailed AI logging on or off. Both command responses were verified on the dedicated server.
- The 5-tick manager pulse remains for dormant trigger checks, dormant aura refresh, and boss-bar visibility reconciliation. Active phase/emergence/aura/attack logic uses the existing Warden AI step. Emergence and Sonic Boom cooldown use the Warden brain's expiring memories instead of duplicate countdown fields. These tasks depend on game ticks or player/block state changes for which this project has no narrow Fabric event; broad movement/redstone mixins would add compatibility risk.
- The client selector preview retains an end-client-tick callback only while checking for the held selection arrow; using a render callback would run more often and would not remove the cursor-preview update.
- Entity unload releases manager and boss-bar state. Named removal and remove-all scan loaded Clockwork Boss markers even when template rehydration fails; unloaded chunks must be loaded before a command can remove their entities.
- Gradle 9.7.0 offline `build` succeeds. An isolated Minecraft 26.3/Fabric dedicated server loaded 44 mods, reached `Done`, exercised spawn/list/remove commands, toggled AI trace, and removed a saved Clockwork Boss after its template was made unavailable. No loaded Clockwork Boss remained after cleanup.
- Manual activation verified that the Warden brain's emergence memory expires and normal Warden AI resumes. The isolated trace confirmed the `emerge` activity ended and `sniff`/`investigate` began.
- The server emitted non-fatal OSHI Windows performance-counter warnings from Minecraft's environment report; the mod loaded and ran normally.

## Clockwork Boss rename, template settings, and skin atlas — 2026-10-06

- Offline Gradle 9.7.0 `build` succeeds with Java 25 after renaming the feature classes and Fabric entrypoints to Clockwork Boss.
- An isolated 26.3 dedicated server loaded the built mod and reached `Done`. Template creation, template-specific `/boss setspawn`, dormant spawn, list, and removal succeeded.
- Startup migration moved legacy JSON templates from `config/arena-boss-fight/wardens/` into `config/arena-boss-fight/arena-bosses/`; the old folder was removed after it became empty. Existing same-name files are preserved instead of overwritten.
- `/boss select activation <template> true` was accepted by Brigadier on the dedicated server and returned the expected player-only response. Actual two-corner selection and copy-to-boss-bar still need an in-game player check.
- The player-skin builder now starts from a fully transparent Warden atlas and uses the vanilla atlas only as a mask for removing the dark base jaw. The glowing emissive layers remain a separate render path. The new no-base-texture result still needs an in-game visual check.
- The client and dedicated-server jars are the same Fabric artifact because the mod metadata includes both common and client entrypoints in one `environment: "*"` jar.

## Current audit — 2026-10-07, version 1.0.1

- Common/client compilation succeeds against Java 25, Minecraft 26.3, Fabric Loader 0.19.5, API 0.161.0+26.3 and pinned Loom 1.18.2. Gradle 9.7.0 deprecation checks are enabled for the audit.
- The dependency-free production-helper suite passes 4161 assertions. Generated templates preserve explicit phase sound `file: null` and `scale: null`, emit explanatory `//` comments before fields, round-trip unknown values and readable punctuation, and reject the previously reported missing-colon sound syntax.
- Isolated native client tests verify shader-mask textures, angry-eye alpha, and every mapped head/torso pixel on a flat-color player skin. Colors remain unchanged, while moving rib plates retain the requested vanilla UVs. Required renderer/audio mixins apply in the actual 26.3 client.
- Real vanilla Vorbis bytes pass validation and decode through JOrbis. Native static playback, pause/resume, category volume, stop, resource reload and buffer deallocation are exercised. An initial direct-release crash is reproduced and repaired through native channel removal. The complete final isolated client rerun passes 708 assertions, including cache revision replacement/restoration and resource reload/disconnect cleanup.
- Isolated dedicated-server boots reach Done. New template creation, per-template setspawn, spawn/list/activate, emergence unlock, active/dormant save/restart, named removal while another boss remains, remove-all and empty reload succeed. The loaded marked-entity query is empty after cleanup.
- A deliberately missing saved template produces one restoration error at that load; the Warden remains removable by name, and the loaded marked-entity query is empty afterward. Test-only worlds/configs are separate from the user's game and server files.
- Native resource-loader-v1, tracking lookup, committed-death event, permissions and scale notification are used. No deprecated Java/Minecraft/Fabric/Mixin calls are reported by the final compile audit.
- Source/documentation comments explain ownership, thread boundaries, inheritance and retained native game-time work. The obsolete global config implementation and unused jaw/filter/death/build fragments are removed; legacy keys still needed for saved bosses/templates remain documented.
- Runtime watcher cadence is ten ticks and it exists only while loaded boss state exists. Idle event dispatch retains only an immediate pointer guard.
- The single environment-* jar serves both client and server. Restart both sides to load code changes; template/media reload does not reload Java code.
- Non-fatal OSHI Windows system-report warnings and development asset/classpath warnings are distinguished from mod failures. No manual visual multiplayer or long-duration modpack load claim is made.

## Mono audio conversion — 2026-10-07, 0.1-preview.9

- All five server event clips were stereo. Offline conversion averaged left/right channels, preserved 44.1 kHz sample rate and exact decoded frame counts, and encoded mono Vorbis at quality 0.7 without gain normalization. Original files are retained in ../work/audio-backups/mono-20261007/; converted clips and a hash/format manifest are in ../outputs/mono-sounds/.
- Each output was decoded to confirm one channel, matching duration/rate and the compressed-size limit. The current production OGG validator methods and Vorbis header guard accepted all five clips. Installed S-drive copies match the converted SHA-256 hashes. The voice-lines folder was empty. No in-game audio playback check was performed in this change.
- README and generated sound/voice comments recommend mono for directional audio. Stereo remains supported; long clips remain fixed at the boss position where playback starts. No audio transport, decoder or game hook changed.
- /boss reload applies the replaced server sound files to the existing preview.8 installation. Preview.9 packages updated documentation/preset comments only; it is provided separately and has not replaced either installed jar.

### Preview.9 client installation — 2026-10-07

Modrinth's display name `test client` maps to the physical folder `profiles/Clockwork Town 26.3`; the full Clockwork Town instance maps to a different folder (`Clockwork Town 26.3 2.0.0`). Confirmed through the launcher's read-only instance metadata and the Test Client's three installed mods (Clockwork Bosses, Axiom, Fabric API). Preview.9 replaced Preview.8 only in Test Client after confirming that exact profile was not running. Backup retained; installed SHA-256 is 259445BA5F4708C16BEAF3716892782264DB57E54A3BD770563F476217607DA3. The hosted server jar remains staged pending confirmation of server shutdown. The full Clockwork Town installation was not changed.

### Preview.9 server installation — 2026-10-07

After the user confirmed server shutdown, Preview.9 replaced Preview.8 in S:\mods. Both server and Test Client contain exactly one Clockwork Bosses jar, with matching SHA-256 259445BA5F4708C16BEAF3716892782264DB57E54A3BD770563F476217607DA3. Original jars are backed up in ../work/install-backups/preview9-20261008-023247/. Installation was verified by file hashes; server/client startup has not been performed by the agent.


## Startup identity — 2026-10-07, 0.1-preview.10

Fabric startup lists technical mod IDs, not the display name. The ID is now clockwork_bosses, matching the existing Clockwork Bosses display name. Loom's development grouping, packet namespaces, generated mask/skin identifiers, resource reloader and logger names use the same ID. Java package/entrypoint names, mixin configuration filenames and persisted entity/selector keys retain their existing references; no folder/world migration occurs.

Java 25/Gradle offline assemble succeeded for common/client sources. The packaged fabric.mod.json declares id clockwork_bosses, name Clockwork Bosses and version 0.1-preview.10. No regression tests or game launches were performed in this change. Client and server must update together because negotiated packet namespaces changed. Deployment targets remain the hosted test server and Modrinth display name test client.

## Separate underground Darkness — 2026-10-07, 0.1-preview.11

- Top-level dormant_darkness controls the hidden boss before activation; phases.<phase>.darkness controls the fully emerged boss, including ANNOYED. A missing/null dormant section preserves old templates' current-phase fallback; partial dormant sections inherit ANNOYED values. New generated templates expose all eight fields, with grouped comments and explicit inheritance/timing/amplifier explanations. All six bundled presets copy their previous phase-1 aura into the independent section.
- Both aura modes compile once on load/reload through one bounded settings reader. Runtime selects between immutable settings on the existing watcher/AI scan. No new tick listener, timer, player collection or effect-removal hook is added. Existing activation clears eligibility deadlines; emergence remains aura-free. Applied Darkness expires normally, avoiding removal of another boss/mod's effect. Native Darkness amplifier changes priority rather than visual opacity.
- Common and client compilation/package assembly succeeded offline with Java 25. No regression tests or game runs were performed for this addition. Preview.11 includes the pending startup ID fix from Preview.10 and will replace Preview.9 on both sides together when the user confirms shutdown. Existing templates need a manually added dormant_darkness section to configure separate values; /boss reload applies the edited section without recreating the boss.

### Preview.11 installed — 2026-10-07

After the user confirmed Test Client and the hosted server were stopped, Preview.11 replaced Preview.9 in both confirmed mod folders. Each contains exactly one Clockwork Bosses jar. Installed SHA-256 matches on both sides: 3FDBD50AE8220CF4E910D6E012BBC4AFCC65637A7DE4EC1C4F47081B31518C6D. Preview.9 originals are backed up in ../work/install-backups/preview11-20261008-030124/. Preview.10 was superseded before installation. No active template file was overwritten; existing templates can add dormant_darkness manually and reload. Installed files were verified; the agent did not start either game process.

## Sound filename rule restored — 2026-10-07, 0.1-preview.12

Sound/voice basenames now accept only ASCII letters, digits, dots, underscores and hyphens, starting with a letter/digit and ending in .ogg; whitespace is rejected by the shared server/client validator. Server warning text, README, default comment catalog and all six bundled preset comments use the same rule. Config directory names such as Boss Templates and voice lines remain as requested.

Offline Java 25/Gradle assemble succeeded; no tests or game launches were run for this change. Test Client remained closed and the server log showed shutdown after the prior user confirmation. Preview.12 replaced Preview.11 on both sides; SHA-256 is 39B302CF602811526BFAA1625504A2992ADFAE80E1CD7C5FE3D8EAE34BB3AF82. Original jars are backed up in ../work/install-backups/preview12-20261008-032847/.

The administrator-requested one-time edit renamed phase 2/3/4.ogg to phase2/3/4.ogg on S:. Exact template file references were substituted without rewriting other settings, with source hashes checked before replacing files. Sound bytes remain the verified mono clips; no re-encoding occurred. Audio/template originals are retained in ../work/audio-backups/no-spaces-20261008-032849/. No automatic config migration was added.

## Pending lowercase config paths — 2026-10-07, build on hold

At the user's request, source now resolves config/clockwork-bosses/boss-templates, boss-presets, skins, sounds and sounds/voice-lines. Template names and custom PNG/OGG basenames accept lowercase ASCII only, without spaces. Existing custom-skin marker/template validation now delegates to the shared PNG filename validator, preventing mixed naming policies. Minecraft account names remain unchanged because they are identifiers, not filesystem names. Current source comments, preset comments and README use the new paths.

No build, tests or deployment was run. Installed Preview.12 and its folders remain unchanged until the coordinated next deployment; there is no automatic folder migration. Health/knockback/darkness balance edits and adding dormant_darkness to already existing active/preset files are separately queued in ../work/pending_release_changes.json.


## Queued changes assembled — 2026-10-07, 0.1-preview.13

- Mod-owned config folders now use boss-templates, boss-presets and sounds/voice-lines. Template and custom PNG/OGG filenames require lowercase ASCII without spaces. Custom-skin marker/template checks delegate to the shared PNG validator. Account/display names are not filesystem names and are preserved.
- Default health is 1000 HP. The optional one-to-six-player presets use 1000, 1400, 1800, 2200, 2600 and 3000 HP, preserving their previous +400 HP/player increment. Existing named one-to-six-player files receive the matching values. Other template health values are preserved.
- Dormant and active phase-1 Darkness is enabled for 20-40 ticks every 200 ticks. Agitated uses 40-60/160, Enraged 80-120/100, and Fury 120/20 for continuous coverage at all distances inside the aura. Existing radii are retained and bounded; effect priority remains normal. A stronger existing native effect remains respected. No aura during emergence.
- Phase-1/2 Sonic Boom knockback increases by 0.2 horizontal and 0.05 vertical (presets: 0.6/0.15 then 0.9/0.20); positive melee knockback increases by 0.2. Disabled melee flags/damage and every other combat/media/arena setting are preserved. Zero first-phase melee knockback stays zero because that preset attack is disabled.
- A one-time requested deployment snapshots existing configs, adds the actual dormant_darkness section to active and preset files, regenerates grouped comments, and renames the folders together with the jar replacement. Missing optional preset 1 is restored to the inactive preset folder. No startup migration or automatic overwrite is added. A loose copied template remains outside the active list and receives only a lowercase filename without spaces.

### Preview.13 build and installation confirmed — 2026-10-07

Java 25/Gradle offline assemble succeeded. Preview.13 is installed on the hosted test server and Modrinth display name test client. Both mod folders contain exactly one Clockwork Bosses jar, SHA-256 6698E9D2DC8B1A5168E36E7862852F48FB7937D4ADE6FA76E8ED79F3375E8E04. Previous jars are backed up in ../work/install-backups/preview13-20261008-041708/.

The coordinated config deployment updated 12 existing active/preset JSON files, added one missing inactive solo preset and renamed six directories. Config/media originals are backed up in ../work/install-backups/preview13-configs-20261008-041228/. All installed formatted config hashes match their prepared copies. Read-back through the production parser confirmed the new sections/values and equality of unrelated settings after excluding only requested health/aura/knockback fields and documentation. Both mod-owned config roots were checked recursively: no names with capitals or whitespace remain, and no previous folder names remain.

The server's log showed it stopped and Test Client was not running before installation. No regression suite or game launch was performed. Start both to load the jar. Existing saved Wardens retain native saved attributes across startup; /boss reload reapplies new maximum health/attributes to already-spawned bosses while preserving their health ratio. Newly spawned bosses use the new template immediately.


## 0.1-preview.14 — template-local media

- Each active/preset template folder contains template.json, skins/, sounds/, and sounds/voice-lines/.
- File lookups, payload identities, server snapshots and client caches include the template name.
- Native entity metadata supplies the template to rendering; no extra tracking registry or tick task.
- Voice catalogs prepare only for loaded templates and release with the last instance; reload clears snapshots.
- Linked config/template/media path components are rejected; existing file/decode/queue limits are retained.
- Sound, custom PNG and chunk channels are versioned for the new codecs. Update both sides together.
- Offline assemble compiles/packages the change; no automated checks or game launch were run.

Sample audio is bundled/extracted once in boss-presets/boss-sounds-example only; the six balance presets are silent until configured.

Preset creation uses cached suggestions, one native I/O job, 96 media files/32 MiB maximum, and staged folder publication. No additional tick watcher; new command forms are create new and create preset.


## Local health/combat follow-up — 2026-10-08 (not deployed)

- Reviewed installed Minecraft 26.3 bytecode for native attribute sanitation,
  dirty-cache notification, attribute loading, Warden saved-health order and target memory.
- Boss-specific native maximum health supports 1–1,000,000; ordinary native ranges are unchanged.
- Native tests cover all six preset HP values, generic damage/healing, save/load,
  health modifiers, ratio-preserving reload, boundary values and backward phases.
- Combat tests cover direct melee threshold ordering, first hits near world tick zero,
  real cooldown deadlines, backward healing, Sonic charge timing, old damage after
  phase/reload changes, target switching/removal and boss-unload cleanup.
- Offline `build runGametest` succeeded: 4253 audit assertions and 8 native tests
  (7 custom regressions plus Minecraft's built-in always_pass). The default Gradle
  test task contains no JUnit tests; auditChecks is the mandatory standalone gate.
- Original audit failure at the old line 37 was a stale Darkness prose assertion;
  replaced with compiled defaults and continuous Fury-duration checks. Earlier
  assemble-only entries are historical packaging results, not test evidence.
- All seven preset comment rewrites parsed back with identical gameplay values.
- Universal jar inspection excludes audit/GameTest classes and the test entrypoint.
- Native test worlds/configs are isolated under build/combat-gametest. No live client,
  hosted server, S drive, or installed jar was touched. No connected-client latency,
  visuals or full-modpack test was performed. Windows performance-counter warnings
  occurred during SystemReport collection but did not fail the checks.

## 0.1-preview.15 — deployment 2026-10-08

User authorized deployment and confirmed the hosted test server stopped; Test Client's
log ends with Stopping and no running Test Client Java process was found. Installed
Preview.15 in S:/mods and the Test Client profile's mods folder. Each contains exactly
one Clockwork Bosses jar, with matching SHA-256
ADFF9CAB7BE640F1F88FA8C9B686BB1CF1505EB8D474868AFE1F8CFC096DAB51.

Preview.15 matches the native-tested review candidate byte-for-byte for every jar
entry except fabric.mod.json's version label. Its offline build also passed all
4253 audit assertions. Native GameTests were run on that identical code before
the version bump; no connected-client or hosted in-game test was run afterward.
Previous installed Preview.14 jars are backed up in
../work/install-backups/preview15-20261008/. Template files and world saves were not
edited by deployment. Start both applications to load this release. Saved bosses
keep their existing HP; newly spawned bosses use full configured health.

## 0.1-preview.16 — local build 2026-10-08

- Implemented configurable native Warden sound volume (default 0.25), including
  synced client heartbeat. Custom OGG events/voice lines retain their own settings.
- Completed melee and Sonic Boom attacks rotate to a uniformly random alternative
  native-eligible connected player in the visibility region/follow range; solo
  targets remain. Disabled/cooldown-rejected melee and cancelled beams do not
  trigger rotation. Native target-setting cooldown insertion is compensated by
  retaining the existing Sonic Boom cooldown.
- Added moving 3D boss-bar radius and emergence-centered activation radius, both
  1-256 blocks, on the existing ten-tick watcher. Box/redstone selection preserves
  the inactive radius option. Generated templates and seven bundled presets expose
  the new settings and section comments.
- Inspected exact locally mapped 26.3 sound, metadata, anger, target setter and
  spatial-query APIs. Offline Gradle assemble succeeded; packaged mod name/version,
  sound-mixin registration, seven presets and absence of development test entries
  were inspected. No new tests or game/client/server launch were performed for
  these changes. Runtime mixin integration and multiplayer behavior need gameplay
  confirmation; earlier Preview.15 test results do not cover the new features.
- Universal client/server artifact: ../outputs/clockwork_bosses-0.1-preview.16.jar.
  This build adds a native metadata field: update and restart every client and
  the server together. Not deployed; live templates/worlds were not edited.


## 0.1-preview.17 — targeting, charge visuals and naming (2026-10-08)

- Final universal artifact: ../outputs/clockwork_bosses-0.1-preview.17.jar
- SHA-256: D1B8211A5BE277C1639D9F7D32703E6553B5F2DD041AFEF52BFAF34DDA84C328
- Project root: ../clockwork-bosses/; open build.gradle in IntelliJ and refresh
  Gradle after the directory/package rename. Full runtime folders were moved,
  preserving existing worlds/configs. Source/IDE/root-doc backup is
  ../work/review-fix-backups/preview17-before-naming.zip; earlier build outputs
  are preserved at ../work/preview16-build-history/.
- Base/per-phase targeting controls default to a 0.5 switch chance and random
  mode, with nearest/farthest alternatives. Zero/one are exact never/always
  endpoints; the attacked/current opponent is excluded by UUID. Native valid
  player, follow-range and configured region checks remain; malformed configured
  regions do not widen selection. Native Sonic cooldown survives target changes.
- Native titles name the actual newly assigned account, including initial native
  acquisition. Same UUID assignments stay silent. Audience is target/current bar
  viewers, position filtered. Global per-recipient throttling covers rapid/multiple
  bosses and never changes attack timing. Controls inherit independently by phase.
- Native Sonic keyframes follow the captured shot duration via atomic entity
  metadata. Cancels clear it; fire uses normal-speed recoil; rapid starts replace
  it. Snapshot reload/phase changes cannot retime a shot. Late tracking samples
  elapsed native game time. The native charge sound stays normal pitch/duration.
- Classes/packages/resources/project configuration now use Clockwork naming.
  Public clockwork_bosses mod/packet IDs, /boss command and established template
  paths retain their identities. Native save reads old entity/item aliases and
  writes canonical ClockworkBosses_ keys. Old literal spellings remain only for
  compatibility fixtures/adapter and historical migration evidence.
- Offline full build passed 4316 standalone audit assertions. All 10 required
  native GameTests passed (nine project cases plus native always_pass), including
  three simulated native players, probability endpoints, nearest/farthest, blocked
  swing completion, solo/spectator filtering, malformed regions, shared title
  recipients/throttling across two bosses, rapid zero-windup beams, late metadata,
  saved-key/health compatibility and existing health/combat regression cases.
- Client tests passed 2700 native model-component comparisons over captured
  windups, zero/short durations, recoil/cancel, preserved melee and an ordinary
  Warden receiving a deliberately conflicting custom animation time. These are
  actual transformed client model checks, not image mockups or a manual playtest.
- Iterations corrected two fixture issues: initial assets were not ready at the
  client-start event, and rapid-fire assertions competed with automatic AI/shared
  reload-all scenarios. The fixture now waits for models, persists its scenario,
  freezes automatic AI during waits and drives native memory before a controlled
  shot. Explicit client result validation prevents a normal shutdown from masking
  assertion failure. No production attack delay was introduced for these tests.
- Packaged metadata/entrypoints, expected classes, renamed mixin/default resources
  and seven presets were inspected. No old entry names or development test
  classes/mod manifests are included in the universal jar.
- Limits: no hosted-server deployment, real two-client network/latency simulation,
  full-modpack compatibility run or TPS/memory benchmark. Startup Windows OSHI/
  performance-counter warnings remain nonfatal. Native titles share the HUD with
  other mods; disabling announcement.enabled is available. Very fast/late packets
  can suppress or delay visible warnings; no guaranteed reaction window is added.

## Preview.17 deployment — 2026-10-08

User confirmed the hosted test server stopped. Installed the universal Preview.17 jar in S:\mods and the Test Client profile's mods directory (physical profile directory: Clockwork Town 26.3). Both installs contain exactly one Clockwork Bosses jar and match SHA256 D1B8211A5BE277C1639D9F7D32703E6553B5F2DD041AFEF52BFAF34DDA84C328. Previous Preview.15 jars backed up under ../work/install-backups/preview17-20261008. No live config/world edits or automatic restart performed. All connecting clients need Preview.17 because native Warden metadata changed.


## Preview.18 — selector and command readability (2026-10-08)

Selection arrow names and mode messages use native yellow for AREA and red for REDSTONE. Command/selection confirmations are green, template/instance names aqua, instructions and list headings gold, and inactive entries gray. Command errors retain native sendFailure red; selector system-message errors explicitly use red. Uses standard chat component styles only, with no extra tick work or packets.

Renamed the public command to /boss select arena <template>; this writes the existing shared boss_bar.visibility_area for boss-bar visibility and activated player effects. Saved selector target identifiers and config fields are unchanged. Updated selector labels, README, default comment catalog and seven bundled preset comment references. The existing select activation boolean still copies the activation box to the same arena area.

Compiled and packaged with Java 25, Gradle 9.7.0, Fabric Loom 1.18.2 and cached Minecraft 26.3 dependencies. Offline assemble succeeded. No tests or game sessions were run for this presentation-only change. Universal artifact: ../outputs/clockwork_bosses-0.1-preview.18.jar. SHA256: 67EB6EC325D29ED53A5470430AD268F10F72292DD51E27529B432F7D9BDA3126. Not deployed; installed client/server remain Preview.17.


### Preview.18 deployed — 2026-10-08

Following the user's deployment request, confirmed stopping entries in both client/server latest logs and no matching local Minecraft process. Installed Preview.18 in Test Client (physical Modrinth folder Clockwork Town 26.3) and S:\mods. Each mods folder contains exactly one Clockwork Bosses jar. Both installed SHA256 hashes match 67EB6EC325D29ED53A5470430AD268F10F72292DD51E27529B432F7D9BDA3126. Preview.17 jars backed up under ../work/install-backups/preview18-20261008. No live template/world edits or automatic restart performed.


## Preview.19 — encounter music and compact eyes (2026-10-08)

Implemented root per-template music settings: enabled, file, volume, loop, fade_out_seconds. Defaults use an empty file, volume 0.75, looping enabled and a two-second fade. Activation/arena entry starts music once bytes are ready; death/removal/unload/arena exit ends it. Uses the existing ten-tick arena membership query, with no extra server watcher. Client fades borrow the native SoundEngine audio tick; Music category/master, pause, stopsound and device/disconnect hooks are respected. Only one encounter track streams at once; the most recent ready encounter takes priority (sixteen request cap). Ambient MusicManager scheduling yields only during actual playback/fade. Muted Music/Master avoids decoding and resumes eligible requests from the start when unmuted.

Music uses safe OGG Vorbis files directly in the template sounds folder: lowercase basename, at most 8 MiB minus 1 KiB and ten minutes; mono/stereo accepted. Event/voice sound limits remain unchanged. Shared receipt transport adds MUSIC and media_chunk_v3, retains 16 KiB chunks/64 KiB window and bounds assembly per kind. Server file validation/encoding happens on native I/O workers, with four cached assets. Client hashes/container checks and initial stream setup use bounded background workers, compressed cache is four entries, native PCM reads/loop output are bounded. Invalid or empty decoded music terminates its request instead of retrying every frame. Native channel teardown closes streams even when attachment callbacks were skipped. Start/stop listener tokens prevent delayed content beginning after death/exit. All clients need Preview.19 for the updated media channel.

The angry-eye mask now has two 2x2 atlas-pixel eyes centered at front-face x offsets 3 and 11, y offset 7 (one atlas pixel below the old top row). Inner-bottom pupil pixels use opaque neutral gray 0x666666, multiplying the phase tint into a darker shade. Eyebrow positions and alpha are unchanged. Ordinary Wardens remain excluded by the existing renderer marker.

Regenerated all seven bundled preset documents with production comment writer and exported ../outputs/clockwork_bosses-preview19-example-template.json. Music file defaults blank; no track was bundled, no live configuration rewritten. Java 25/Gradle 9.7.0/Fabric Loom 1.18.2 offline assemble succeeded. No tests, live audio/game sessions, network-latency simulation or new runtime mixin checks were run for this change. Jar: ../outputs/clockwork_bosses-0.1-preview.19.jar. SHA256: E9CC9E49D8620411CB60DF85F6B348131AF7EC89D2E640A88E06615905D4B078. Not deployed; installed Test Client/server remain Preview.18.


### Preview.19 deployed — 2026-10-08

User authorized deployment. Confirmed stopping entries in both latest logs and no matching local Minecraft process. Installed Preview.19 in Test Client (physical Modrinth folder Clockwork Town 26.3) and S:\mods. Each mods directory contains exactly one Clockwork Bosses jar. Both installed SHA256 hashes match E9CC9E49D8620411CB60DF85F6B348131AF7EC89D2E640A88E06615905D4B078. Previous Preview.18 jars are backed up under ../work/install-backups/preview19-20261008. Existing live configs/worlds were not edited and neither application was restarted. Music still needs a configured OGG filename in the template; no audio was invented or bundled. Live audio/visual testing remains outstanding.


### Installed preset music sections repaired — 2026-10-08

After the user reported missing music in boss-sounds-example and requested all presets checked, found 19 server presets and 7 Test Client presets without music. The jar's seven bundled presets already included it; initialization intentionally preserves pre-existing documents. Prepared additive documented music sections with the production config writer, retaining original comments/text except correcting old /boss select bossbar references to /boss select arena. Production parser comparison confirmed every previous config value remained unchanged. Backed up all 26 original files under ../work/preset-music-update-20261008/backups; checked original hashes again before writes, published staged files and verified each resulting hash. Installed manifest: ../work/preset-music-update-20261008/installed.json. No active boss templates, audio files, worlds or jars were modified. Music filenames remain blank until chosen. No rebuild needed.

### Installed preset folder layout repaired — 2026-10-08

Added the missing skins/, sounds/ and sounds/voice-lines/ directories to the twelve server leather/netherite presets: 36 empty folders created. Verified all 19 server presets have the complete layout and music section; all seven Test Client presets already had both. Template SHA256 hashes before/after folder creation were unchanged. No audio copies were added. Report: ../work/preset-folder-repair-20261008.json.

The two pasted documents matched the original server boss-sounds-example and 1_player backups after normalizing line endings. The mounted server boss-sounds-example still matched the previously verified updated hash, and all mounted presets contained music. This establishes that the pasted documents predated the update; it does not independently prove the hosting panel's cache/sync mechanism. Existing active templates remain separate copies and currently have no explicit music section.

## Preview.20 — template editor, phase management and skin preview (2026-10-09)

Added /boss template edit for active templates and reusable presets, plus /boss template delete for active templates only. Deletion archives the template folder and blocks templates used by loaded bosses; remove unloaded boss instances before deleting their template too. The plain gray editor uses native input widgets, collapsible sections, a dynamic index, validated values, explicit Save/Cancel and a Reload saved button that invokes the existing global reload behavior. Saving presets affects future copies. Saving templates does not automatically apply settings to loaded encounters.

Phases now follow the configured file order, with 1–16 stages and descending health cutoffs; the final stage covers remaining health. The existing four-stage defaults remain. The detached 3D preview uses the live skin/rendering path without adding or ticking an entity in the world. Eye coordinates move the separate 2x2 glow eyes while keeping eyebrows fixed. All seven bundled presets include the default eye settings and concise production comments; no installed configurations were changed.

Server edits require native operator permissions, session identity, bounded patches and matching file revisions. Disk work uses bounded native I/O workers; saves preserve omitted inherited fields and keep one rolling backup. Custom preview PNGs reuse the bounded media transport and isolated texture identities. No additional server watcher was introduced.

Java 25 / Gradle 9.7.0 / Fabric Loom 1.18.2 offline assemble succeeded. No tests, live game sessions, GUI-scale visual checks or latency simulations were run. Jar: ../outputs/clockwork_bosses-0.1-preview.20.jar. Bytes: 764609. SHA256: 67A0BB8FB2AFC2565146C4AF71119DE28BF1303EB654A0B91CF7A56FC8FB6688. Not deployed; Test Client and the test server remain Preview.19. Client and server must both use Preview.20 for its new payload and synchronized eye metadata.

### Preview.20 deployed — 2026-10-09

User authorized deployment and confirmed the test server stopped. Test Client had a stopping log entry and no matching local Java process. Backed up both Preview.19 jars under ../work/install-backups/preview20-20261009, installed Preview.20 and removed only the backed-up old jars. Both installed copies match SHA256 67A0BB8FB2AFC2565146C4AF71119DE28BF1303EB654A0B91CF7A56FC8FB6688. Independently listed the actual SFTP mods directory and downloaded its Preview.20 jar through the existing rclone connection: the remote hash matches, and its listing contains only Preview.20 for the boss mod. This confirms upload beyond the S-drive cache. No live templates, presets, media or worlds were changed. Neither application was restarted. In-game editor/preview verification remains outstanding.

## Preview.21 — editor refinements and music channels (2026-10-09)

Implemented the requested navigation/grouping, bottom Reload button, initially collapsed nested groups, indented children/highlighted open parents, unified skin/eyes page and cross positioning pad with center reset. Base contains attacks/targeting/Warden effects. All audio including per-phase sound overrides is on Sounds. Dormant darkness, Activation and arena sections precede Manage phases. Box geometry shows min/max; radius shows radius; redstone activation shows position. Optional geometry is an editable-only view until explicitly changed or submitted with a new geometry mode. Target switch chance is a GUI percentage while persisted config remains probability 0–1.

Music channel uses native SoundSource names (music by default, record for Jukebox/Note Blocks, master for Master only). Native gain helper, selected-category stopsound and menu exemptions follow the chosen category. Borrowed streams now explicitly pause because native pauseAllExcept only discovers SoundInstance entries. Control payload is versioned to v2; update client and server together. Regenerated all seven bundled preset files and exported ../outputs/clockwork_bosses-preview21-example-template.json; installed configurations were not edited.

Inspected both supplied logs and local 26.3 inventory/PiP renderer code. No actionable preview stack trace was present; one supplied client log showed an OpenGL invalid-enum message, whose cause is unconfirmed. Adjusted model layout/foreground stratum and inventory-style snapshot extraction. Preview starts in full-body view and logs a single failure per refresh. These are source changes, not proof of successful runtime rendering; in-game confirmation remains required.

Java 25 / Gradle 9.7.0 / Loom 1.18.2 offline assemble succeeded. No tests, game launches, GUI-scale visual checks or live audio checks were run. Jar: ../outputs/clockwork_bosses-0.1-preview.21.jar. Bytes: 771752. SHA256: 4B7B57EE63D12746960B7AB74A4EF7745C4C431449D79D0B41DD1FA42FE9DC81. Not deployed; installed Test Client/server remain Preview.20.

### Preview.21 deployed — 2026-10-09

User authorized deployment. Test Client and test server logs both contained stopping entries, with no matching local Test Client Java process. Backed up Preview.20 jars under ../work/install-backups/preview21-20261009, installed Preview.21 and removed only the backed-up old jars. Client and mounted-server hashes match 4B7B57EE63D12746960B7AB74A4EF7745C4C431449D79D0B41DD1FA42FE9DC81. Independently listed/downloaded the actual SFTP mods directory: only Preview.21 is present for this mod and its downloaded SHA256 matches. Existing installed configurations, media and worlds were not edited. Neither application was restarted. In-game confirmation of the revised preview/layout and channel behavior remains pending.

## Preview.22 — detached preview entity ID (2026-10-09)

Fresh Test Client Preview.21 log contains IllegalStateException: Tried to access entity ID before ID assignment, originating in Entity.getId -> ItemModelResolver.updateForLiving -> LivingEntityRenderer.extractRenderState -> editor preview. The newly pasted attachment was an older Preview.20 server log; the current local client log supplied the decisive stack trace. Inspected local 26.3 Entity bytecode: ID 0 is INVALID_ENTITY_ID, getId rejects it, setId is the native setter, and base client Level.getNextEntityId returns 0.

Assign -1 with native setId immediately after constructing the one detached preview Warden, before rendering. This nonzero value serves the item-model rendering seed only. The preview stays screen-owned and is never registered in ClientLevel, ticked, or used in network packets. No custom entity allocator, world insertion or additional watcher is introduced. Source comment records both the 26.3 requirement and preview lifecycle. This fixes the demonstrated pre-render exception; visual success still needs confirmation in game.

Java 25 / Gradle 9.7.0 / Loom 1.18.2 offline assemble succeeded. No tests or game launches were run. Jar: ../outputs/clockwork_bosses-0.1-preview.22.jar. SHA256: 21815CBAD56F6DBB333218D9C32AB5A622677FD26D39148A902669987C8F782B. No protocol/server gameplay changes; Preview.22 client remains compatible with Preview.21 server. Not deployed; installed versions remain Preview.21.

### Preview.22 deployed — 2026-10-09

User authorized deployment. Both current logs contained stopping entries and no matching local Test Client process remained. Backed up Preview.21 jars under ../work/install-backups/preview22-20261009, installed Preview.22 in Test Client and S:/mods, verified hashes and removed only the backed-up old jars. Independently listed actual remote SFTP mods and downloaded Preview.22: only the new boss jar is listed and its SHA256 matches 21815CBAD56F6DBB333218D9C32AB5A622677FD26D39148A902669987C8F782B. Existing configurations/worlds/media were not modified and neither application restarted. Actual preview visual confirmation remains pending.

Reviewed runtime source for Realms/authentication/session-service or Microsoft credential hooks. No such hooks or credential modification were found; the editor's random session token is unrelated to account authentication. Earlier archived client log showed native Realms news/invitation/trial/notification queries receiving HTTP 401 Unauthorized. That log does not establish the underlying credential rejection cause. Fresh Preview.21 client log did not repeat those Realms errors; the preview failure is the distinct, demonstrated entity-ID exception.

### Pending source change — template list command (2026-10-09)

Moved the active-template listing from /boss templates to /boss template list, beside edit/delete in the existing administrative template subtree. The old top-level templates literal was removed. Listing implementation and permission requirements are unchanged; /boss list still lists spawned bosses. Updated README command references. User explicitly requested no build yet: no compilation, tests, packaging or deployment performed for this change. Installed jars remain Preview.22.

### Pending source changes — preview controls and Darkness help (2026-10-09)

Added independent eyebrow positioning, eye spacing buttons and independent X/Y eye scales (0.5–4, rounded to 1–8 atlas pixels). Coordinates clamp to keep the shape on the face. Preview face glow is steady and brighter; live glow still follows its existing pulse. Preview keeps its original panel height; the full-body model renders 10% smaller to leave room for the whole Warden. Darkness fields show concise explanations between their labels and validation; amplifier descriptions recommend Keep at 0 in the GUI, generated template documentation and all seven bundled presets.

Eyebrow coordinates add one native entity-data integer, so the next build requires matching client/server jars. These edits have not been compiled, tested, packaged or deployed because the user requested no build yet. Installed jars remain Preview.22.

### Pending source change — nested phase navigation (2026-10-09)

Manage phases starts collapsed in the sidebar. Clicking it opens its management page and toggles the phase-page branch. Child pages have a 12-unit inset, a gray grouping rail/background and an individually highlighted selection; the parent stays highlighted while expanded. Clicking the parent again hides the child pages. The management page retains add/remove/reorder controls; phase values remain on separate phase pages. Direct Edit/add navigation opens the branch, and add/remove/reorder rebuild the index from the current draft. Disclosure state survives resize and is separate from page-section folds. No compilation, tests, packaging or deployment were performed; the user's no-build instruction remains active.

## Preview.23 built and deployed — 2026-10-09

User authorized building/deploying the pending source changes. Java 25 / Gradle 9.7.0 / Loom 1.18.2 offline assemble succeeded, including common/client compilation. No tests or game launches were run. Includes /boss template list, independent eye X/Y scales and eyebrow coordinates/controls, brighter steady GUI face glow, original panel height with a 10% full-body zoom out, concise Darkness descriptions with Keep at 0, and collapsed/indented phase navigation under Manage phases. Dormant/emergence Darkness behavior was inspected and explained, not changed.

Jar: ../outputs/clockwork_bosses-0.1-preview.23.jar. Bytes: 779630. SHA256: 5CA00857B0AC4D5CDD7F161FACD046784640647E1E7DD8FD19338C94AAA6F594. Packaged metadata identifies Clockwork Bosses 0.1-preview.23 for both environments. New eyebrow entity metadata requires matching client/server jars.

Remote latest.log showed server shutdown. User confirmed Test Client closed; exact --gameDir inspection showed the running Minecraft process belonged to Clockwork Town 26.3 2.0.0, not Test Client. Backed up both Preview.22 jars under ../work/install-backups/preview23-20261009. Installed Preview.23 only in Test Client (physical profile folder Clockwork Town 26.3) and directly in minecraft:/mods. Verified the installed client hash and independently downloaded the remote server jar with the same hash. Removed only the backed-up old jars; final listings show only Preview.23 for the boss mod. Clockwork Town pack, existing installed configs, media and worlds were not modified. Neither application was started. GUI rendering/scale/navigation confirmation remains in-game work for the user. Deployment manifest: ../work/preview23-deployment.json.

### Pending source change — saturated preview face (2026-10-09)

Preview eyes and eyebrows now retain the selected phase hue with full saturation, brightness and opacity. The RGB calculation subtracts the minimum channel and normalizes the remaining maximum to 255, removing the pastel white component without shifting hue. Neutral gray has no hue and retains its RGB. Darker pupil pixels remain supplied by the existing mask. Preview face rendering bypasses the white transition flash; other layers and in-world boss colors/pulsing retain their existing behavior. Updated preview help. No new texture, timer, packet or state field is added. Source-only change; not compiled or deployed. Installed jars remain Preview.23. In-game visibility still needs confirmation after the next build.

### Pending source changes — target hold, attack budget and automatic range (2026-10-09)

Source inspection confirmed fired Sonic Booms already called the rotation path, but candidate filtering used follow range. This can exclude players within a configured longer Sonic Boom range; it does not prove the reported live streak's cause without runtime observations/config. Ranged completion now supplies its captured settings for horizontal/vertical candidate bounds and optional native collider raycast. It retains arena/native eligibility and preserves Sonic cooldown memory. Cancelled charges do not rotate.

Added inherited min_target_ticks (default 60, 0-12000), min_attacks (default 1, 1-1000) and max_attacks (default 4, 1-1000). Actual assignments own one UUID/game-time/capped counter per boss; completions count before hold/chance checks. Maximum overrides chance, minimum time overrides maximum, and no alternative keeps the opponent. Native setAttackTarget is gated only for marked active bosses retaining a valid in-range opponent; clearing/dead/disconnected/removed/ineligible targets bypass it. Another mod's veto/redirect is respected before navigation/look reset. GUI cross-field validation rejects max below inherited min; direct file compilation safely raises max to min.

Audience-only title stamps can be replaced by a personal warning; personal warning stamps still share recipient cooldown across encounters. Existing per-connected-player cleanup remains. The existing rapid-rotation fixture was adjusted to opt out of new hold/budget defaults and reflect personal-warning behavior; no tests were added or run.

User agreed to automatic arena sizing. follow_range=0 resolves to moving-radius distance or fixed-box full 3D diagonal, capped at 256, with 24-block unset fallback. Calculated only at phase assignment/reload from cached geometry. New bundled default/presets use zero base and null phase ranges; existing installed templates are not rewritten. Regenerated all seven source presets with grouped section comments and new targeting fields.

Java 25 / Gradle 9.7.0 / Loom 1.18.2 offline compileJava compileClientJava succeeded after the final changes. This also compiled the pending saturated-preview refinement. No tests, game launches, packaging or deployment were performed. Installed jars remain Preview.23; next packaged version should be Preview.24. Runtime targeting/alerts/visual confirmation remains outstanding.
