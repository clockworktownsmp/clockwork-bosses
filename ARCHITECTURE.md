# Clockwork Bosses architecture

This document describes the main code paths and why they are structured this way. The bundled strict JSON supplies one defaults/comment catalog; generated templates place that documentation in concise section blocks, with direct field notes grouped above the values.

## Version 0.1 numeric boundary — 2026-10-10

ClockworkBossNumbers shares bounded exact decimal parsing between the editor and
reward definitions. Text/precision/scale checks precede potentially expensive
formatting. Existing extreme values can display compactly without rewriting
files; edits still require valid bounds. The editor compares BigDecimal values
to exact field limits. Shared MAX_PATCH_BYTES keeps client/server limits aligned.
Empty configured entry lists avoid building a table but keep the existing
exactly-once death gate and vanilla Keep/Replace behavior.

## Beta RC 8 template-owned reward tables — 2026-10-10

ClockworkBossLoot retains the RC7 datapack path; absent source still means table.
ClockworkBossLootEntries parses immutable item definitions and validates strict
keys/IDs/counts/chance. At one committed death it builds at most 32 native pools
using 26.3 ContextIntProvider holders, UniformGenerator, SetItemCountFunction and
LootItemRandomChanceCondition. Each pool rolls once independently. Unregistered
template tables use native LootParams with the same entity/attacker/player/luck
fields as LivingEntity's public table-key overload, then native generation,
stack splitting and entity spawning. No registry mutation or datapack writes.

The editor extends its bounded structural protocol with _loot_entry_order.
The server preserves existing entries/unknown fields and constructs new entries
from trusted defaults, then applies only validated scalar patches. Snapshot
catalogs contain immutable item/table IDs; no live registry reaches the file
worker. Invalid or stale saves publish nothing. The item picker stays within the
same screen/session, caches default stacks/search names per opening, filters on
input and renders only visible icon widgets. Removal releases its caches.
Future reward kinds can extend the dedicated entry/service boundary without AI,
new ticks, global tables or reward transport. Full behavior is in LOOT.md.

## Beta RC 7 native item rewards — 2026-10-10

`ClockworkBossLoot` defines the separate template-wide config/validation boundary;
`ClockworkBossRewards` owns committed-death payout. `BossLootMixin` wraps the
single native loot-table invocation and leaves unmarked entities unchanged.
Entity-owned pending data captures native kill credit; Warden persistence stores
the exactly-once claim. Fabric AFTER_DEATH consumes the pending data, claims,
then uses the native public loot-table overload. More reward types can extend
this service without adding encounter AI, a global scheduler or an economy API.
The existing editor/schema receives one Loot page. Invalid file saves and live
reloads preserve previous valid state. See [LOOT.md](LOOT.md) for the exact policy
and tested/unverified integration boundaries.

## Beta RC 6 personal-item transfers — 2026-10-09

The potion gift uses native `Inventory.add` into an available slot, consuming the
held input before issuing a bottle. Refused/partial transfers, a newly occupied
offhand or an empty destination prevent issuance. The member stores a comparison
snapshot of actual stored contents; copies and stack-capacity normalization by
inventory integrations do not make that ownership depend on object identity.

Cleanup requires an empty offhand and the recorded item's count and remaining
components. Capacity changes alone are permitted, and all current live data is
preserved. The live inventory slot is removed and transferred back; snapshots
never become items. Moved or otherwise changed items remain where they are.
Tests cover these cases with native players, inventories and the supplied pack.

Embedded tests explicitly model LuckPerms user preloading and CCA channel state;
these fixture-only integrations never enter the release jar. A separate Knot
production launch exercises the actual jar without any development source output.
See [MODPACK_TESTING.md](MODPACK_TESTING.md) for evidence and limitations.

## Beta RC 5 compatibility boundaries — 2026-10-09

`BrainRecoveryMixin` guards two calls inside native `Brain.tick`, rather than
cancelling the methods Lithium replaces. The guard pauses only marked recovering
Wardens; native sensors and memory expiry continue. The native test starts probe
behaviors through Brain's scheduler to exercise Lithium's actual running-task
cache, then verifies pause/resume and ordinary-Warden behavior.

`ClockworkBossDiagnostics` is a server-thread, opt-in observer. Existing native
attack, damage, target, phase/subphase, player session and support boundaries
append bounded primitive snapshots. Per-capture salted aliases correlate UUIDs
without retaining player names, full UUIDs or entity objects. The operator-only
`/boss debug` commands control capture/export. No diagnostic callback approves
damage, selects targets or adds gameplay timers. Shutdown exports an active
capture if reached normally and clears memory at server stop.

Native call acceptance, immediate HP/velocity observation and completed attack
accounting are separate evidence. External synchronous callbacks can affect
observations; missing Fabric callbacks cannot prove no damage. The development
GameTests and client model check are separate mods/source sets excluded from the
universal jar. See [COMPATIBILITY_TESTING.md](COMPATIBILITY_TESTING.md) for tested
versions and the limits of simulations and client startup/model checks.

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

## Recovery and player support ownership — 2026-10-09, pending source

`ClockworkBossSubphases.Step` identifies attack/recovery and compiles a bounded
duration. `ClockworkBossState` owns the deadline/generation; Manager handles
entry/completion using existing AI callbacks. `BrainRecoveryMixin` pauses only
behavior start/tick for a recovering marked Warden. Native memory expiry/sensors
remain active; native stopAll handles old behavior pose cleanup on entry.

`ClockworkBossPlayerSupport` compiles template-local health/potion values.
Manager shares arena membership with beacon effects and releases all three
support owners together. `ClockworkBossPlayerHealth` owns one named native
modifier, strongest floor across overlaps, and proportional transitions.
`PlayerSupportMixin` observes native MAX_HEALTH updates and substitutes completion
only for marked fight potions. Warden metadata saves the bounded first-heal UUID
ledger independently of runtime state, preserving it across unload/reload.

`ClockworkBossFightPotion` owns connected participant policies and temporary
offhand displacement. Vanilla components define appearance/drinking/cooldown;
server-side start/completion checks authorize doses. `ServerPlayerDataMixin`
saves one shared deadline; native COPY_FROM transfers it on respawn. All lifecycle
paths remove player references/grants/bottles. No background job owns an entity.
Existing installed RC1 and configuration files have not been deployed/edited.

## Subphases and captured attack timing (pending source)

- `ClockworkBossSubphases` compiles bounded attack and roar catalogs on load/reload. It overlays non-null child combat fields onto the parent, then reuses `ClockworkBossSettings` for base inheritance and bounds. Roar entries inherit phase/base audio defaults only. Children never choose by health or recursively nest. Parent phases continue to own health, scale, Darkness, glow, bar and transition effects.
- `ClockworkBossState` owns attack/roar indices, count, interlude flag and generation. Completed attacks enter one roar when enabled; matching native roar completion selects the next attack child. Sequential cycles both catalogs independently. Random attack order uses at most sixteen shuffled indices with no consecutive repeat at the round boundary; roar selection is independent and may repeat. Main-phase entry/reload resets both catalogs; random starts with a fresh shuffle. No transition resets a completed attack's captured cooldown. Old charged beams can finish without advancing a replacement sequence.
- `ClockworkBossMelee` postpones the existing native hit with one UUID/deadline/policy. The existing AI callback rechecks target identity, eligibility, range and native cached visibility, then invokes `doHurtTarget`. Invalid targets cancel; no chunks or new targeting AI are introduced. Starting a wind-up cancels unfinished Sonic Boom. Finally cleanup releases a vetoed hit's pose.
- `ClockworkBossMeleeVisual` and `ClockworkBossChargeVisual` synchronize bounded atomic timelines. Renderer state samples native 26.3 keyframes using shared game time, so late tracking does not restart the wind-up. Configured recovery gates the next same-kind attack; native melee retains its own minimum cadence.
- `RoarMixin` observes native start/continuation/stop and native sound playback only for a configured interlude token. The manager requests Minecraft's ROAR activity after attack recovery. Native roar owns target memory, navigation stop, pose and duration; completion advances the attack child once. Token changes interrupt obsolete roars. Native stop's target assignment retains the previous elapsed beam cooldown instead of adding a fresh default delay. A custom OGG replaces the native once-only audio cue (about 25 ticks in) through the normal template-local validated media path; blank uses vanilla audio. Clip length never controls attack resumption.
- The editor exposes separate attack and roar catalogs beneath each phase with sequential/random and interlude controls. Drag handles and native Up/Down buttons change draft insertion order; pointer events provide scrolling and Escape/outside drops cancel. Screen/page/size/request boundaries discard dragging. Structural order operations remain separate from validated scalar patches; arbitrary client object insertion is rejected. All resource defaults/presets leave both catalogs empty until edited. This source is not yet packaged/deployed.

## Server encounter lifecycle

- `ClockworkBosses` registers the template store, commands, selector callbacks, and server lifecycle listeners.
- `ClockworkBossMarker` and `WardenMixin` attach the Clockwork Boss identity and saved template/name/emergence information to vanilla Warden entities. A marker is required before gameplay hooks affect a Warden, so ordinary Wardens keep vanilla behavior.
- `ClockworkBossManager` creates dormant Wardens, loads their template settings, handles activation, applies attribute/phase changes, updates the boss bar, and cleans transient state when entities unload or servers stop. It listens for entity load/unload events to avoid retaining removed entities.
- Dormant bosses have AI disabled, so a lazily created manager watcher checks their activation and Darkness aura every ten server ticks. The watcher is cleared when the last loaded Clockwork Boss unloads or is removed. Active bosses already enter `WardenMixin.customServerAiStep`; phase, Darkness, and the custom Sonic Boom work are attached there to avoid a second active-entity sweep. Active Darkness spatial queries are limited to ten ticks, or the shorter configured refresh interval.
- Dormant depth is `ceil(5 * max(1, bounded native SCALE))`; five normal-size blocks include horn clearance. Initial phase/depth assignment happens before entity registration. Phase assignment/reload/rehydration repositions dormant entities; unchanged phase checks exit immediately. Spawn-height validation reserves initial phase depth. Activation teleports to the saved emergence point, clears dormancy and plays the configured spawn sound once for all trigger types.
- `ClockworkBossState` contains transient data such as the current phase, activation edge history, per-player Darkness refresh deadlines, and boss-bar membership. Chunk data remains on the Warden; transient references are removed on unload and shutdown.
- `ClockworkBossAreaEffects` consumes the same boss-bar membership transitions after activation. Immutable effect settings are compiled on load/reload. Entry/exit/configuration events reconcile at most five whitelisted native effects; unchanged membership performs no effect work. Contributions are grouped by player and boss UUID, with the maximum requested level winning across overlapping encounters. No extra watcher, movement mixin, spatial query or duration refresh is added.
- External effects take precedence: Fabric 26.3 `ServerMobEffectEvents.BEFORE_ADD` removes an owned grant before native effect merging, avoiding hidden infinite grants and swallowed potion timers. `AFTER_REMOVE` resumes eligible grants through a deduplicated native server task outside effect iteration. These tasks are single event responses, not repeating tick checks. Object identity prevents deleting externally replaced effects. Vanilla entity tags identify matching grants saved by a crashed session; `ServerPlayerEvents.JOIN` cleans them before membership is reestablished.
- Player `LEAVE` cleanup runs before native saving. Death and `COPY_FROM` release old entity references/membership; the existing scan admits eligible replacement players. Boss death immediately releases contributions, and dying bosses cannot regrant effects. Boss unload/removal/shutdown releases the same requests. Native Fabric effect permission callbacks remain respected.
- `ClockworkBossPhase` translates health percentage and configured transition cutoffs into ANNOYED, AGITATED, ENRAGED, or FURY. `applyPhaseIfChanged` applies attributes and synced visuals only when the phase actually changes.
- `ClockworkBossSonicBoom` handles the custom attack after vanilla target selection. It lets normal Warden navigation and melee AI run, and uses the Warden brain's cooldown memories to avoid duplicate tick countdowns.
- `ClockworkBossSonicCharge` captures every Sonic Boom setting and its melee-yield rule at charge entry. The immutable definition survives threshold crossings, healing and reload, including disabling the next charge. Native target eligibility and the captured range/obstruction rules can still cancel it. A switched target cancels rather than receiving an old telegraph; unload releases the record.

## Attack voice lines

- `voice_lines` is an immutable per-template policy in `ClockworkBossSettings`, defaulting to enabled with chance 0.25, volume/pitch 1 and range 32. The successful native melee-return hook and successful custom Sonic Boom hit call one manager function. Dormant/emerging bosses, disabled/zero-volume policies, an empty catalog and an unexpired voice deadline exit before randomness or file work. No cancelled charge or unsuccessful hit rolls a voice.
- `ClockworkBossSoundNetwork` lists the fixed `sounds/voice-lines/` folder once on server start and `/boss reload`, inspecting at most 4096 directory entries and selecting up to 64 sorted, regular, safe OGG basenames. The catalog resets across integrated-world shutdown/start. Selected invalid files are removed until reload; no directory walk occurs during attacks. Event and voice basenames use separate cache identities in the shared sixteen-file LRU.
- The shared OGG parser validates page bounds/sequence/CRCs, identification and comment/setup allocation limits, then converts the final granule's sample count to a bounded duration using the sample rate. The server performs no codec decoding. One real-time per-boss deadline covers duration/pitch plus a small delivery margin; it is consulted only by attacks and disappears with runtime state.
- The sound payload includes the boss UUID and a voice flag, using bounded media chunks for first-use bytes and versioned `boss_sound_v2` for cached playback metadata. Native `canSend` negotiation skips clients with an older codec. Client file caches are touched even when playback is suppressed so the delivery LRUs remain aligned. Up to eight UUID reservations cover decode, callback/channel acquisition, playing and pause. Idempotent owner removal releases them on failure, disconnect, native stop/reload or existing pitch-aware clip cleanup. Old callbacks cannot start a released voice or remove a replacement reservation. Existing event clips do not reserve voices.

## Commands and template data

- `ClockworkBossSelector` temporarily marks a vanilla arrow with owner, session, template, target, and mode data. A server-side session validates those values before accepting clicks. This prevents ordinary arrows or another player's arrow from editing templates.
- `ClockworkBossSelectorClient` consumes selector block gestures through Fabric 26.3 `ClientPreAttackCallback`. One fresh click calls native `startDestroyBlock`, whose common selector callback returns `CONSUME` to send the predicted selection action without a mining success. Cancelling normal input prevents the separate `ServerboundPunchPacket` from vanilla `startAttack`/`continueAttack`. A single boolean suppresses the held gesture after tool removal, resets on a fresh press and clears on disconnect. No extra tick listener, mixin, timer or custom packet is added; server permission/session validation remains authoritative.
- A normal activation-area selection writes an activation box. `/boss select activation <template> true` stores the same box and dimension in `boss_bar.visibility_area` with one save. The combined mode stays in AREA because a redstone point cannot describe a visibility cuboid.
- `/boss setspawn <template>` writes position and dimension into that template's `spawn` object. New templates start with `spawn.set: false`, which means use the command source position. Older templates without `spawn` use the command source position. No global spawn file is loaded, generated, moved or archived.
- `ClockworkBossTemplateStore` validates template names, owns `config/clockwork-bosses/boss-templates/`, and refreshes cached names for autocomplete. Its shared `configRoot()` supplies the root used by skin and sound loading. Startup creates `boss-templates`, `boss-presets`, `skins`, `sounds` and `sounds/voice-lines`; the previous folder migration and global-spawn archive code have been removed because the mod is unreleased. Earlier development files can be moved manually.
- `installBundledPresets()` runs once during mod initialization before the template-name cache is scanned. It reads six fixed `presets/*.json` resources (`1_player` through `6_players`) with the normal size bound/parser and writes normal JSON files into the separate `config/clockwork-bosses/boss-presets/` folder. Existing preset files, including links, are skipped; the final move does not replace a concurrently created file. Administrators copy a preset into `boss-templates/` and reload to activate it. The cache and loader continue to use only `boss-templates/`, so no enabled flags, extension rules, runtime player-count query or extra repeating task are needed. Comments use the current catalog without changing preset values; temporary writes stage beside each destination.
- `ClockworkBossTemplateDocument` converts the bundled catalog into `//` comments before sections/fields. Gson's comment-aware reader also accepts existing strict JSON. Null phase overrides are serialized explicitly, and actual values use Gson escaping with readable apostrophes/angle brackets. Reads are capped at 512 KiB/32 nesting levels; saves write a complete sibling temporary file and prefer atomic replacement. Unknown settings survive; comments are regenerated from the catalog.

## Client texture and visual path

- `WardenRendererMixin` copies the server-synced Clockwork Boss marker, skin name, emissive color, and phase-flash time into the client render state.
- `WardenSkinTextureCache` resolves and bounds cached player skins, normalizes legacy 64x32 skins, merges outer skin layers onto base pixels, and maps head/body/limb UV boxes with nearest-neighbor sampling. It also loads exact-size 128x128 custom Warden atlases received from the server directly, without remapping their UVs. Nearest-neighbor scaling preserves Minecraft pixel-art edges.
- The generated 128x128 Warden base atlas starts transparent. Only mapped player-skin pixels are drawn into it. Only the specifically requested moving rib-plate UVs retain original Warden pixels. The old jaw-removal/filtering code is deleted because a transparent player atlas contains no vanilla jaw to remove. No ordinary Warden texture is blended over the mapped head/body/limbs.
- `WardenEmissiveMaskTexture` turns each vanilla emissive texture's alpha into a white mask. The render hook tints only those separate glow layers; it does not recolor the player skin. Keeping source alpha and per-texture shape preserves the Warden's pulsing layers; the 128x128 face mask replaces the mouth with the accepted angry-eye pattern.
- Client render hooks check the custom render-state marker. A normal Warden and unrelated living-entity emissive layers continue to use their vanilla textures and colors.
- Skin images are rendered only on the client. A server stores the selected skin type plus its player username or custom PNG filename and sends those identifiers as entity metadata. It sends a validated, bounded custom atlas only when a client starts tracking a boss that uses it; `/boss reload` refreshes changed files for existing trackers. Player skins resolve through Minecraft; custom 128x128 atlases are loaded from the server's `config/clockwork-bosses/skins/` folder and received through a server-to-client payload. Both use the same bounded texture cache, and custom image bytes are discarded when the client disconnects. `default` leaves the vanilla Warden texture in place.

## Diagnostics and safeguards

- Version 1.0.2 removes the AI diagnostic command, trace flag, target-history fields, periodic snapshots and activation/emergence log messages from the source. The Warden AI hook contains only encounter work; native targeting and navigation retain their existing behavior.
- Spawn creation rolls back a partially created entity and its boss-bar/runtime references if initialization fails.
- Texture and mask caches have explicit size bounds, image dimensions are checked before decode, and selectors/runtime state are released at lifecycle boundaries.
- `README.md` documents player workflows. `PREFLIGHT.md` records build/server smoke results; `PERFORMANCE_AUDIT.md` records the main hot paths and cleanup behavior.

## Compiled settings and native ownership

- `ClockworkBossSettings` validates and compiles phase thresholds, melee permission/cooldown, Sonic Boom and Darkness once per load/reload. Repeating AI reads immutable primitives. Damage/movement/scale attributes change only on assignment/transition/reload. Native attribute notification handles scale dimension refresh.
- The vanilla Warden brain acquires the initial target and owns navigation/attack activities. Completed boss attacks roll the current phase's switch probability, then choose a nearest/random/farthest eligible alternative through native anger and attack-target memory; Sonic Boom cooldown is preserved across this rotation. Native expiring memories own emergence and Sonic Boom cooldown; one world-time deadline owns a charge, with a target UUID preventing a changed target from receiving an old charge. The native chest attachment accounts for scale. Collider raycasting deliberately matches the configured solid-obstruction rule. Extreme-range beam visuals cap at 64 samples.
- Fabric native entity load/unload, death, disconnect, tracking and resource-reload events replace redundant registries and broad death interception. Per-phase Darkness suppresses only the marked boss's vanilla fixed aura. SynchedEntityData still owns visual/skin metadata.
- Minecraft's native profile cache resolves player skins. A bounded Mojang-only HTTPS fetch remains necessary to obtain CPU pixels for UV remapping; its built-in texture downloader exposes a registered GPU texture, not a reusable CPU image. The mod borrows Minecraft's I/O pool and never creates its own download thread pool.

## Server sound files

`ClockworkBossSoundNetwork` validates a direct server filename, bounded OGG pages/checksums and Vorbis header allocation limits, then sends bytes on a per-client cache miss and small event metadata otherwise. Native spatial queries limit recipients. SHA-256 revisions, bounded sixteen-file delivery histories, and one current revision per filename handle edited/restored files. Disconnect clears delivery history.

`ClockworkBossSoundClient` independently validates hash/container, decodes through native JOrbis on Minecraft's background executor, and borrows vanilla static channels. Dynamic server files have no resource-pack SoundInstance, so this small adapter explicitly owns their temporary SoundBuffer and forwards native pause/category-volume/stop events. All native source/buffer operations run on Minecraft's audio executor.

A clip is stopped through ChannelHandle.execute and native ChannelAccess.scheduleTick, which removes the stopped handle as well as releasing the source. Direct ChannelHandle.release is not used because it leaves the handle in the native set. Buffer deletion runs afterward even when native playback already released the handle. Stop/device reload drains pending acquisition and deletion before native teardown. A cancellable, pitch-aware timeout replaces any mod audio tick watcher and freezes during native pause. Resource reload retains server file bytes; disconnect forgets them.

## Review ownership and transport — 0.1-preview.6

- `ClockworkBossState.Areas` stores validated native AABBs, immutable block positions and dimension keys. The manager's existing ten-tick scan uses these directly. No repeated JSON region parsing or directory check occurs in watcher/autocomplete paths. The watcher remains inactive with no loaded bosses; native AI/brain processing still follows Minecraft ticks.
- Manager iteration snapshots and ownership checks protect against native effect callbacks unloading/removing a boss during reconciliation. Area-effect suppression is scoped to the exact player/effect operation, so nested unrelated mod callbacks retain their events. Native player LEAVE/COPY_FROM and server STOPPING release all owned state.
- `ClockworkBossMediaCache` coalesces filenames on Minecraft's I/O pool: four workers, 64 pending files, sixteen positive and sixteen negative snapshots per cache. Reload invalidates generations while retaining running worker reservations. Late results cannot repopulate new caches or deliver into replacement connections. Failed filenames are retried after reload; file edits are reload-driven rather than checked on every attack.
- `ClockworkBossMediaTransfers`, `ClockworkBossMediaChunk` and `ClockworkBossMediaReceipt` use negotiated Fabric channels, not an external connection. An immutable encoded file is shared across recipients. One assembly per player accepts 16 KiB chunks with a 64 KiB cumulative-receipt window. Exact token/sent boundaries reject stale/foreign receipts. Waiting identities coalesce, with 64-file / 8 MiB limits and skin priority over optional waiting audio. Reload discards waiting obsolete media but preserves the in-flight reservation. LEAVE/STOPPING release the queue; no mod tick, timeout loop or executor drives transport.
- `ClockworkBossMediaClient` reassembles at most 1 MiB on the client thread and decodes only the two known production codecs. Receipts cannot choose paths or increase allocations. Full payloads enter existing bounded caches before the final receipt; compressed decoding remains asynchronous. First-use sounds may be delayed by transfer; queued events expire before starting after eight seconds, while started transfers finish. Metadata-only playback follows the same native packet order and file LRU.
- Client JOIN, DISCONNECT and shutdown share session cleanup; render resource release is dispatched to the client thread. Connection identity/generation checks prevent a delayed old disconnect from clearing a new session. Resource reload keeps compressed server files and a connected partial transfer, but resets GPU masks/textures and native playback.
- `WardenMixin` observes a server phase-change token and records a local entity-age start. First observation is a silent baseline; later changes start the eight-tick flash. Renderer tint changes only when the shared mask flag confirms our own white mask was supplied. The mask cache accepts five exact vanilla Warden source identifiers; foreign textures and failed loads retain original texture/color/alpha. Resource PNG reads are bounded before native allocation.
- Skin HTTP work uses Mojang-only HTTPS, connection/read limits and a monotonic overall deadline. CPU image streams are bounded and use per-stream in-memory ImageIO input rather than a process-wide ImageIO setting. Server files are direct regular children, opened without following a final symbolic link.
- `ClockworkBossVorbisHeaders` follows the [Vorbis I specification](https://xiph.org/vorbis/doc/Vorbis_I_spec.html), checked against bundled JOrbis 0.0.17. Comment lengths, codebook dimensions/entries, floor storage, mappings and per-mode residue lookup cells are bounded before the native decoder allocates them. It is an allocation guard, not another audio decoder; unusual but valid metadata/setup profiles may be rejected. CRC/header checks cannot guarantee that every audio packet is playable; decode failures remain contained by the existing client work limits.
- Decoded PCM belongs to the bounded Java heap LRU. A temporary MemoryUtil upload buffer exists only after native channel acquisition, is freed after OpenAL attachment, and has idempotent cleanup if the channel callback is skipped. Native SoundBuffer/ChannelAccess deletion still runs on Minecraft's audio executor. Clip reservations and native resources are released across failure, stop, disconnect, pause/resume and device reload.
- No dependency upgrade, extra AI targeter, new periodic watcher, trace logger, custom animation or configuration migration is added. Compile/package confirmation is recorded in PREFLIGHT; gameplay, adversarial files, simulated latency and complete modpack interaction remain untested in this review.

## Directional sound authoring — 0.1-preview.9

Event and voice documentation recommends mono OGG Vorbis. Native channels already use absolute boss coordinates and linear attenuation. Mono permits positional OpenAL playback; accepted stereo buffers lack normal 3D positioning. Long sounds keep the coordinates captured at event time. Administrator conversion runs offline, preserving sample rate/duration and leaving native runtime ownership unchanged.

## Startup identity — 0.1-preview.10

Fabric mod ID and runtime resource/packet/logger namespace are clockwork_bosses. MOD_ID is the shared constant. Existing Java packages, mixin filenames and persisted entity/selector keys remain as referenced; there is no schema or folder migration. Both sides must install the same new build to negotiate the renamed channels.


## Dormant Darkness policy — 0.1-preview.11

ClockworkBossSettings compiles phase auras and an optional top-level dormant_darkness snapshot using one bounded reader. A missing/null section falls back to the current phase while dormant; a present partial section inherits ANNOYED fields. ClockworkBossManager selects the dormant snapshot only while state.dormant is true, reusing the existing aura path. Emergence remains aura-free; activation resets deadlines, and later AI scans use active phase settings. No destructive effect cleanup is added because native Darkness can also belong to another encounter or mod.


## Sound basename policy — 0.1-preview.12

The shared sound/voice filename validator once again rejects every space/whitespace character. Accepted direct basenames use ASCII letters, digits, dots, underscores and hyphens, beginning with a letter/digit and ending in .ogg. Runtime wire layout, caches and directory confinement are unchanged. Directory names can contain spaces; that is distinct from a media basename. This supersedes the temporary Preview.8 space allowance.


## Lowercase configuration layout — 0.1-preview.13 installed

The canonical roots are config/clockwork-bosses/boss-templates, boss-presets, skins, sounds and sounds/voice-lines. One shared template/media root owns them. Template/PNG/OGG basenames are lowercase ASCII with no whitespace. Config generation exposes independent underground Darkness in every default/preset, using the same compiled reader and watcher as active phases. Existing files were explicitly backed up, reformatted and moved during the administrator-requested deployment; startup still preserves existing preset/templates rather than migrating them.



## Local health/combat review — 2026-10-08

`BossHealthAttributeMixin` wraps the final native attribute sanitation step only
on a marked Warden's MAX_HEALTH instance. Native modifier calculation/cache,
attribute packets and native health remain authoritative. The 1–1,000,000 bound
does not mutate the global attribute registry. Saved markers enable the range
before parent health loading; synced boss metadata enables the matching client range.

`prepareCombatPhase` commits changed phase attributes before Brain.tick and before
direct melee attempts. It returns the same phase permission/cooldown snapshot.
Successful-hit timing uses a separate default-false flag, so tick zero cannot be
confused with an existing hit. No new global tick callback or entity-reference cache.

## Preview.16 — native sound volume and encounter regions

- `sounds.mob_volume` is compiled once on template load/reload and synced through
  one native Warden float metadata field. A composable Entity.playSound wrapper
  changes only marked Wardens' minecraft:entity.warden.* events. Warden.tick's
  client-local heartbeat uses the same multiplier; custom OGG playback is separate.
- `ClockworkBossTargets` queries native loaded entity sections only on completed
  attacks. It excludes the previous victim and native-ineligible players, chooses
  uniformly, raises native anger and uses setAttackTarget. Its native 200-tick
  acquisition delay is replaced with the existing Sonic Boom cooldown, preventing
  target rotation from imposing another delay. Solo targets remain selected.
- Radius activation caches its query box around the saved emergence point. Radius
  visibility follows the Warden's position and filters the native spatial query
  with squared 3D distance. Both share the existing ten-tick watcher; area buffs
  follow the same viewer membership. Configuration is compiled on load/reload.
- Selecting a box or redstone trigger retains the inactive radius field. Generated
  templates and all bundled presets expose and document the new options.

## Preview.17 — native target events and sampled windup

- ClockworkBossSettings compiles targeting probability, mode and announcement
  controls per phase, including independent per-field inheritance. Never/failed
  rolls exit before querying players. Alternatives use native loaded spatial
  sections, follow range, encounter region and UUID exclusion. Nearest/farthest
  ties use UUID ordering. Random mode uses the native random source uniformly.
- WardenMixin wraps setAttackTarget compositionally and compares actual UUIDs
  before/after native and other-mod work. Local call state avoids an injected
  previous-target field. Real changes cancel an old telegraph and may emit native
  title packets; repeated assignments do neither. Title timestamps are bounded
  to connected recipients and removed on leave/last-boss cleanup/server stop.
- ClockworkBossChargeVisual encodes sequence/start/duration/fire in one native
  STRING metadata field, so partial multi-field updates cannot mix shots. It is
  transient, parsed only when changed, and sampled using native game time.
  Windup maps to the native 1700 ms shot keyframe; fired recoil advances normally
  through 3000 ms. Cancel clears the snapshot, a rapid start replaces it, and
  local expiry needs no extra metadata packet or timer.
- WardenRendererMixin copies sampled milliseconds into pooled render snapshots.
  WardenModelMixin changes only the marked boss's native Sonic Boom keyframe
  application. Other animations and ordinary Wardens use the original calls.
- ClockworkBossLegacyData contains old save/item spellings deliberately. It reads
  native ValueInput before parent health load, writes canonical keys on native
  save, and normalizes only copied owned selector tags. The established public
  mod namespace, packet channel IDs and per-template filesystem layout remain
  stable; Java class/package/resource naming is independent of those identities.

## Encounter music (Preview.19)

ClockworkBossMusic compiles root music settings in ClockworkBossState and borrows updateBossBar's existing arena query. UUID/token listener records stop on native death/unload/removal/leave/copy hooks; reload refreshes music without another server watcher. Native I/O workers validate OGG files and pre-encode shared immutable transfers. The existing receipt transport adds a MUSIC kind with an 8 MiB total bound while retaining one-MiB skin/effect limits and a 64 KiB window. Four music assets retain at most about 64 MiB of compressed plus encoded snapshots, with separate bounded pending work and per-player transfer queues.

ClockworkBossMusicClient stores four compressed tracks and sixteen encounter requests, but borrows only one native streaming channel. SoundEngine's existing tick advances fades; its device, volume, pause and stop hooks release/resume music. SafeStream catches decoder failures; BoundedStream caps per-read and per-loop PCM output. Native streaming buffers and decoder teardown follow ChannelAccess ownership. MusicManagerMixin holds the native ambient scheduler only while an encounter stream/fade is actually playing. Death/exit controls invalidate tokens before late file assembly; no network response can choose a server path.


## Preview.20 editor and phase ownership

ClockworkBossTemplateEditor owns one token/revision session per operator (64 cap, 30-minute lazy expiry). Native play networking carries catalog/doc responses and small typed scalar save patches; no arbitrary path or file bytes are accepted from a client. At most four editor/delete I/O jobs are active globally. Disconnect/server stop removes session records. Reads, validation, backup and writes run on Minecraft I/O workers, with server-thread callbacks checked against session identity and current permission. Saves check the original file hash, validate field types/bounds and descending phase cutoffs, preserve untouched missing fields/unknown data, and retain one rolling backup. The existing staged-file writer publishes the durable document; a successful response is sent after rereading it. No new server tick listener is registered. Reload saved deliberately calls the existing global reload command policy.

ClockworkBossEditorSchema shares dropdown choices/ranges/parsing between the client and server. The editor's virtual form backfills missing fields for visibility, but only changed fields and explicit phase-order edits are written. New phase skeletons inherit base settings via nulls. ClockworkBossPhase is now a config-key record, with an immutable ordered Thresholds list capped at sixteen stages; compiled ClockworkBossSettings maps each identity to attacks/targeting/darkness. Existing name constants and the three-cutoff fixture constructor retain original fixture vocabulary. Health selection returns an existing phase identity without allocation. Sound prewarming and initial burial use the actual configured stages/first phase.

ClockworkBossTemplateScreen owns its detached preview entity and drafts. Responsive GUI-coordinate layout and visible-row widgets avoid building hundreds of active controls per frame. Custom skin previews use a session-specific __gui_ identity and the existing bounded PNG/chunk path; late preview packets are rejected after closing. Closing releases preview bytes/textures. ClockworkBossEyes packs four 0–14 face coordinates into one native entity-data integer. Configure/load/reload restores it from the template. WardenRenderer copies it into the existing render-state interface, and the face-mask cache keys by coordinates with the existing 32-texture bound. Brows and non-face layers are unchanged; ordinary Wardens remain excluded.

## Preview.21 presentation and native music categories

Editor grouping is a view over existing field paths, not a config migration. Conditional geometry fields come from an editable-only schema; the server still persists explicit patches. Mode changes to box/redstone include the displayed coordinates. Percent targeting conversion happens only at the client input boundary, then shared validation checks the unchanged probability unit. Nested widgets remain visibility-bounded; page navigation resets folds while retaining draft values.

The preview uses fresh native inventory-style entity snapshots in a foreground PiP stratum. Detached entity ownership, bounded texture/media caches and teardown stay unchanged. Preview failures log once and can retry through explicit refresh. Music settings compile a native SoundSource once on template load. Control v2 sends its canonical name, shared validation rejects unknown wire names, and the client uses the corresponding native gain helper for selection/playback/slider updates. Explicit pause is required because borrowed file streams have no SoundInstance entry in the native pauseAllExcept map; native resume still resumes pooled channels. No new game watcher is added.

## Pending source: independent face controls and Darkness guidance

Eyes retain one packed integer, now including width/height derived from independent X/Y scales. Brows use a second packed integer. Native synchronized entity data carries both; configure/load/reload restores the template values. Face coordinates are normalized before mask writes. The renderer's editor-preview flag applies steady opaque face glow only to the detached GUI snapshot; world snapshots explicitly clear it. Both positions and preview brightness participate in the existing bounded face-mask cache key. Darkness descriptions recommend amplifier 0 and distinguish overlap priority within Darkness from other effect types.

## Pending target rotation policy

ClockworkBossState owns transient target UUID/time/attack count. ClockworkBossTargets counts completed attacks and applies minimum time, minimum count, chance, then forced maximum selection. Fired beams supply captured reach/line-of-sight; swings retain follow-range selection. Native setAttackTarget guards short retention of a valid marked-boss target, then observes actual assignment for reset/telegraph cancellation/announcement. Native cooldown memory remains authoritative. Announcement stamps distinguish audience messages from personal warnings in the existing bounded recipient map. Automatic follow range is derived once per attribute application from cached arena geometry; explicit positive range values retain precedence.

## Pending source: lifecycle tightening

Server media-cache reservations are acquired under its lock; future consumers run outside it, even when worker submission is rejected. A bounded rejected backlog is drained with failed completions. Encounter music uses its existing membership scan to retry temporary file/transport capacity, cancelling any start control whose transfer was refused. File-validation failures still require reload.

The editor invalidates its active token on external screen removal, sends its session close and releases detached preview state. Internal picker/document replacement retains the session. Preview metadata uses an edit/refresh invalidation flag; render snapshots remain native and fresh each frame. Mask cache keys store source/alignment/preview identity and cache their registered identifiers, with ownership-checked eviction. Music submission rejection releases reservations/unattached streams; failed channel futures stop the request. None of these changes creates another recurring callback or executor.
