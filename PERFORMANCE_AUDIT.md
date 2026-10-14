# Clockwork Bosses performance audit

This describes current code and resource bounds. It is a source and ownership
review. The recorded release has no TPS benchmark, heap profile or long-running
load test. [Preflight](PREFLIGHT.md) records the checks; [modpack testing](MODPACK_TESTING.md)
records pack isolation and warnings.

## Publish suggestions — prepared 0.3.0-beta.5, 2026-10-13

After publication, the existing API file worker checks one owned template file
and adds its name to a sorted immutable autocomplete snapshot. It avoids a full
directory scan per publish and adds no worker, tick callback, watcher, listener
or native encounter update. Snapshot reads stay lock-free. Full cache refresh
and this notification share the existing file-writer monitor to avoid losing
an update when they overlap. Publication retries add no duplicate suggestions.
If notification fails, the already committed files remain published and manual
`/boss reload` is available. Compilation passed; no runtime/performance test was
run for this change.

Beta.4 explicitly packages the beta channel. This changes resource metadata and
default host selection, without adding tasks, listeners or network operations.
Pinned URLs and separate testing identities retain the channel implementation below.

## Workshop release channels — 0.3.0-beta.3, 2026-10-13

Selection is a bounded settings snapshot with explicit packaged channel data.
It adds no tick listener, game state, fallback routing or new relay protocol.
Browser/websocket/metadata use one exact origin; origin/identity changes close
the old connection and revoke sessions through the existing generation guard.
Existing credentials stay in place. A non-secret origin marker prevents later
reuse on another host; separate test keys are random, not production derivatives.
Binding/key file work runs on the existing single configuration worker.

One compatibility object per connection owns a 30-second cache. Credential-free
GET uses the existing bounded relay network executor, a five-second total wait,
16 KiB body subscriber and no redirects. Metadata is refreshed on connect and
relevant status/mutation requests, never from ticks or a periodic poll.
Requirements are five exact existing logical capability names; unknown names
and undeclared mod versions deny writes. Permission/CSRF checks precede the
gate; file commit guards recheck cached, unexpired approval without network I/O
under the writer lock. Closing invalidates even a late metadata completion.
API/login/read-only recovery remain usable.

The prepared client validates mutation paths, including separate XHR sound
uploads. A mismatch pauses existing autosave and sends no new write; no mutation
is replayed and local recovery is retained. Old servers without metadata keep
the prior SDK path without new metadata requests. Routing, released API, worker
and browser regressions pass; no latency/TPS/heap benchmark or new cloud-runtime
end-to-end test was performed.

## Better Combat and safe potion slots — 0.3.0-beta.2, 2026-10-12

The only new integration reads selected weapon attributes when an owned potion
is granted/refreshed. No new tick callback, listener, task, queue, damage bridge
or animation controller is introduced. Better Combat stays compile-only; the
typed lookup loads only when installed and a linkage failure warns once before
choosing a free inventory slot. Normal servers without it pass native tests.

The existing bounded inventory scan uses native backing slots. It also recognizes
an owned cursor bottle on refresh, avoiding another gift while that bottle is
being moved. Personal offhand equipment stays put. The former displaced-slot
index, copied item snapshot, content-comparison helper and restoration path are
removed; there is no retained personal-item copy to reconcile at cleanup. This
supersedes the historical displacement/restoration descriptions below.

Cleanup still runs on existing encounter/player lifecycle boundaries and sends
the native inventory state. Hidden offhand slots are scanned directly; there is
no inventory polling or global container scan. Potion metadata/policy/deadlines
and existing membership ownership/cleanup remain unchanged. A full inventory
fails without moving or dropping gear; reentry retries after freeing space.

The native compatibility regression failed before the placement change and
passes afterward. All 54 tests with Better Combat and 47 without pass, including
committed damage and existing PvP behavior. This establishes correctness in the
fixtures, not a measured TPS improvement or a heap/latency benchmark. Real
multiplayer/animations and the full live modpack remain manual.

The additional Better Combat/Lithium fixture also passes all 54 tests. An
intermittent existing fixed-box test was isolated by keeping its displaced boss
in the same loaded chunk and asserting native lookup/alive status; production
PvP logic was not changed. The catalog fixture's accelerated-loop wait yields
only in development tests; no park/sleep or manual `doTick()` enters gameplay.

## Encounter PvP protection — 0.3.0-beta.1 source, 2026-10-12

Damage, projectile collision and harmful-effect attempts query loaded encounter
states and native arena geometry. This is O(loaded encounters) per relevant harm
attempt, with fast exits for non-player victims and absent player attribution.
It adds no tick callback, audience scan, queue, scheduled task, scoreboard team
or separate membership map. The existing 10-tick support watcher is unchanged;
PvP boundaries do not wait for it. No chunks are loaded to decide protection.

Each projectile/cloud/TNT may own one player UUID, captured on native owner/load
events and saved alongside its existing owner. A changed native owner clears
the UUID. There are no strong player references or global projectile indexes.
Native/addon ownership traversal stops after eight steps to bound cycles.
Helpful effects and non-player recipients bypass the effect scope allocation.
Harmful player effects use one temporary thread-local record; nested calls
restore their outer record and `finally` removes the last record on exceptions.

Fabric's cancellable damage/effect events remain authoritative, including other
mods' vetoes. Native explosions calculate push independently of accepted damage,
so one wrapper also returns zero PvP knockback without replacing explosion logic.
Lightning's native bolt argument supplies Channeling's player cause before
`thunderHit` ignites or creates unattributed damage; no new owner record is added.
Isolated native and config tests cover these paths. This is a work/ownership
review, not a TPS benchmark or long-running heap profile. Real latency and
third-party combat-mod playtesting remains outstanding.

## Moving melee wind-up — 0.2.0, 2026-10-12

The existing per-boss AI callback still owns one pending UUID, immutable settings
and a world-time deadline. Movement keeps that same record; no new listener,
queue, timer, target registry or navigation controller was added. Target/phase
validity is checked throughout the wait. Native reach and visibility checks now
run at impact instead of on every wind-up callback. An invalid impact clears
the record, so returning cannot revive it. Phase/action/unload cleanup is unchanged.

The new movement regression failed before the fix and passes afterward. All 40
native tests pass without and with Lithium, plus the offline/full packaging
checks recorded in [Preflight](PREFLIGHT.md). This removes repeated reach/sight
work from the waiting path; it is not a measured TPS or heap improvement.

The client tendril tint also removes body/face coverage from its shared base
atlas at mask creation. UV checks happen only on bounded cache misses (32 masks,
1024x1024 dimension / 4 MiB source limits); no per-frame pixel processing was
added. Masks still belong to the texture manager and existing eviction/reload
cleanup. Native pixel/model checks pass with tested EMF/ETF versions; the user's
whole-skin flash requires a rendered encounter confirmation.

## Merged workshop and file ownership — 2026-10-12

The optional workshop has no tick callback or inbound HTTP listener. Disabled
access resolves/migrates settings on one config worker and skips identity,
registry capture and network/upload workers. Its idle config thread can time
out. Enabled item capture reads at most 64 items per native task, with a soft
2 ms budget and 30-second total deadline; sorting/hashing stays off-thread. A
third-party name getter cannot be preempted: this is not a proven per-tick cap.
Permission requests run on Minecraft's thread; file/network workers wait at
most two seconds and cancel their native future on failure.

Core create/setspawn/spawn/reload/selection and voice-folder reads now use one
file worker plus 32 waiting slots. Command return values report admission; later
chat reports the actual result. Native completion rechecks permission, identity,
deletion and loot registries. Restoration temporarily pauses AI and releases
that pause on completion, unload or shutdown. Even reader Errors settle their
future before propagating. The existing editor's four jobs and one preset copy
retain separate bounded Minecraft I/O-pool ownership. Synchronous public
spawn/reload/store helpers remain compatible; live commands use async paths.

Native editor transactions and web publication share a worker-only writer gate
with revision rechecks. Native completions never take it, avoiding a lock cycle
while publication asks native permission. One pure filesystem guard checks both
modules, including Windows junctions. The offline schema fallback is generated
from core definitions; runtime export includes installed addons. No filesystem
poller, combat timer or per-tick workshop allocation was added.

Relay limits: four file workers + queue 12, two network workers + queue 128,
16 operations, two credited 32 KiB upload streams and one decoder. Config admits
one reload. Retry retains one timer and backs off 1/2/4/8/16/32/60 seconds plus
at most one second of jitter. Connect/ready limits are 10/15 seconds; heartbeat
is 30 seconds, stale cutoff 85. Canceled handshakes, readiness tasks, streams
and queued operations are removed. Existing storage/media/backup quotas remain.
Logout uses a retained owner reference even while transport readiness is absent.

Verified: disabled preparation count is zero; queue overflow rejects; config
self-close takes less than 500 ms instead of waiting two seconds for itself;
reader failures settle; reconnect does not replay writes; outage logout revokes;
native commands/fights progress with unreachable relay; local transport shutdown
leaves no relay workers. These are bounded regressions and ownership checks,
not TPS/heap benchmarks. Rendered mixed-client latency, live TPS and long-running
heap behavior remain unmeasured. See [Preflight](PREFLIGHT.md) for test evidence.

## Automatic spatial custom-sound distance — 2026-10-11

Distance resolves only when an event, custom roar or voice line triggers, using
the encounter's compiled arena geometry. Native AABBs already include the maximum
block, so the diagonal needs no config reparse, player scan, chunk load, watcher,
deadline map or retained resource. Auto float conversion rounds outward only
when required to retain the computed boundary. Music is unchanged.

Playback packets and their native recipient queries are bounded to 1–512 blocks.
Larger manual/automatic ranges can include more loaded player sections and more
listeners than the prior 128-block cap; they are not free. Existing spatial AABB
queries, exact squared-distance filtering, capability checks, shared payloads,
media caches, transfer windows/expiry and per-player budgets remain in use.
No transfer size, decode limit, channel limit or protocol layout was increased.
The same cached sound path owns audio cleanup; no additional allocation is kept
per tick or per encounter. Old clients can retain their 128-block attenuation cap.

Offline compilation and bounded geometry/settings/packet assertions passed.
No TPS, heap, network latency, actual client audio or modpack benchmark was run.

## Pupil settings — 2026-10-11, source only

`ClockworkBossPupils` is an immutable bounded value: four eye-local offsets,
independent dimensions and visibility fit in one metadata integer. Configuration
and the existing GUI input callbacks update it; vanilla tracking sends changes.
The optional-client layout includes the appended field. No watcher, recurring job,
executor, media transfer or additional handshake was added.

Pupil identity extends the existing 32-entry emissive mask key and texture ID.
Non-face masks remain shared. Clipped rectangles are measured on cache misses,
then existing eviction/resource-reload/disconnect cleanup releases the textures.
The website draws on its existing input/resize path without adding an animation
loop. Compilation/schema export and JavaScript syntax checks passed; runtime
performance and in-game verification remain pending.

## Compact editor presentation — 2026-10-11, source only

The shared client theme holds only immutable color/size constants. Field
measurement and optional two-column placement run on existing rebuild events;
rendering does not measure the whole form or search the section catalog. Measured
rows also determine scroll bounds, and only visible controls own widgets. Layout
records are temporary to a rebuild. Help remains available without permanently
allocating screen space. Compact item grids retain native 16-pixel icons.

There is no new tick callback, job, thread, packet, texture, permission cache or
server work. Existing screen/session and preview cleanup owns all retained state.
Common/client compilation passed; no runtime performance or GUI-scale measurement
was performed. This presentation pass is now packaged and installed as
`0.2.beta.1`; installation hash checks do not verify runtime behavior.

## Fabric v1 permissions — 2026-10-11, source only

Sixteen immutable typed nodes and two fixed definition arrays hold no players,
grants or server state. The permission provider owns caching and contexts.
Visibility checks short-circuit; execution/editor/selector checks run only on
their existing interaction callbacks. No watcher, recurring job, thread,
database connection or additional per-player registry is created. Client selector
previews still bypass server authorization queries.

Editor jobs retain existing global/session limits. Permission checks stay on the
server thread, before I/O scheduling and before document/preview delivery. Busy
state is cleared before a denied completion; queued previews check relevance
before transfer start. Authorized in-flight writes/transfers may finish. Existing
session identity, leave/shutdown cleanup and own-session cancellation own resource
release. Common/client compilation succeeded; no runtime provider or performance
measurement was performed. See [PERMISSIONS.md](PERMISSIONS.md).

## Potion death boundary — 2026-10-11, source only

One native committed-death hook releases this player's temporary potion grants
before inventory capture. It checks the bounded native inventory/cursor, restores
the unchanged live offhand, and drops member references once. Later death/copy
cleanup has no remaining potion member to release. Ordinary players receive no
inventory refresh when no marked bottle changed. Totem survival and canceled
death skip this boundary. Other participants' overlapping grants stay intact.
The existing use callback clears an older orphan on attempted use; it adds no
watcher, scheduled task, grave/container scan, registry or optional dependency.
Non-potion slots are rejected before inspecting custom marker data.

Common/client compilation, 5,013 offline checks and 33 native tests passed with
and without the supplied server pack, including actual grave recovery. The
early pack runs stalled at shutdown when the native respawn fixture fell back
from the occupied grave block to remote world spawn. A separate checkpoint in
the loaded test area corrected the fixture; the final supplied-pack and narrower
Graves/Lithium/Polymer runs passed all 33 tests and shut down normally. No
gameplay chunk-ticket workaround was added. No heap, load or real-client latency
measurements were performed for this change.

## Removal cleanup and shared form rules — 2026-10-11, source only

Explicit boss removal performs one bounded inventory/cursor pass for online
players without potion membership. It does not scan world containers or add a
timer. Members of other encounters keep their existing bottle. A cleaned member
receives a native full inventory refresh; the orphan pass sends nothing to players
whose inventory is unaffected. Saved personal offhand contents still move from
the live recorded slot rather than being recreated from a snapshot.
Action/geometry visibility is one immutable rule shared by the GUI/schema;
hiding controls does not allocate encounter state or mutate saved values.
Common/client compilation, 5,013 offline checks, independent schema validation
and all 29 isolated native tests passed. No heap profile, load benchmark or real
client/high-latency network test was performed for this pass.

## Template tooling foundation — 2026-10-11, source only

Schema generation occurs only in the explicit build task/operator command. It
reuses bounded settings/descriptors and private immutable form prototypes; it
holds no entities, players, worlds or connections and adds no watcher/timer.
The operator export uses the same 512 KiB atomic config writer and fixed path.
The standalone parser bounds streamed UTF-8 input and nesting, rejects duplicate
members, and retains unknown numbers without decimal expansion. Both writers
copy document data and release all temporary state after the call. Descriptor
help is exported as text data, not executable code.
Common/client compilation and 4,947 offline assertions passed, plus independent
schema validation. No runtime export timing, browser performance or heap/load
measurements were performed. See [template tooling](TEMPLATE_SCHEMA.md).

## Pending optional-client source — 2026-10-11

One immutable array of twelve actual Warden accessor IDs defines the wire adapter.
It retains no world, entity, player or connection references. Pairing uses the native
method's recipient; dirty updates use Minecraft 26.3's existing filtered tracking
broadcast. Unchanged metadata reuses the original list/packet. Changed metadata
allocates one small fallback list and at most one packet per update shared by all
recipients without the mod; visual-only updates send them no empty packet. A changed
update can traverse the existing Warden tracking set twice, with a negotiated-channel
membership check per recipient. Native metadata limits bound the field list.

No presence registry, retry queue, handshake timer, thread or watcher is added.
Modded recipients keep original metadata; unrelated entities/attribute packets
retain their original calls. Existing media channel guards skip unsupported clients.
Java 25 / Minecraft 26.3 common/client compilation passed; mixed-client runtime,
modpack compatibility and actual network/performance measurements remain pending.

## Pending API source review

The 2026-10-10 safety pass adds one primitive server-thread ID per runtime and
one stop-depth counter per encounter. Replacement startup waits for outgoing
cleanup; native behavior/combat stays blocked during restoration. No extra
watcher, timer, worker or global ownership map was added. Temporary entities
reject portal work/cross-dimension replacement, preventing escape from cleanup.
Snapshot failure is inside the protected cleanup scope. Fabric listeners remain
process-wide: addons must register once and avoid capturing live encounter
objects. Slow callbacks can still stall the server; this pass includes no heap
profile or measured load test. Common/client compilation succeeded. See
[API safety review](API_SECURITY_REVIEW.md) for findings and remaining limits.

Experimental [API revision 1](API.md) attaches one action runtime to each loaded
encounter and uses the existing boss AI callback. No additional watcher,
executor or global entity queue is introduced. Settings compile on load/reload;
editor descriptors are bounded and startup-frozen. Scope cleanup retains at
most 64 resources per execution, releases references on interruption, and
explicitly owned entities cannot be saved as chunk entities or passengers.
Core burrow saves a bounded restoration journal rather than an action object.
Event snapshots contain UUIDs, and listener failure logging is limited to once
per minute. This is source/compilation evidence, not a runtime leak profile or
TPS result; the API-specific preflight checks remain pending.

## Work that repeats

| Work | Cadence and purpose |
| --- | --- |
| Empty dispatcher | Native end-server-tick, immediate idle guard; no encounter scan while empty |
| Dormant activation/aura and active arena membership | Shared 10-tick scan; moving players/redstone need current results |
| Active phase/emergence/attacks | Existing marked Warden AI step and native game time |
| Active Darkness query | Every 10 ticks or shorter refresh interval; per-player deadlines limit application |
| DIG cooldown | Renew when its expiring native memory is absent |
| Selector preview | Native end-client-tick, immediate held-tool guard |
| Glow/flash | Existing render layer; local age, no server flash countdown |
| Event/voice cleanup | One pause-aware timeout plus native audio lifecycle |
| Music refills/fades | Native sound-engine tick; one stream, sixteen requests maximum |

Settings/regions compile on load/reload. Attacks and autocomplete use cached
values. Queries use loaded sections and do not force chunks. Total work still
grows with the administrator's number of bosses and players.

## Resource bounds

| Resource | Bound |
| --- | --- |
| Phases / subphases / roars | 1-16 phases; 0-16 children and 0-16 roars per phase |
| Arena box / radius | 256 blocks per axis / 1-256 blocks |
| Darkness | 0-256 blocks, 1-12000 ticks, amplifier 0-4 |
| Beam / phase particles | 64 samples / 128 particles |
| Saved first-heal ledger | 4096 UUIDs per boss |
| Server media workers | Four running / 64 pending identities per cache |
| Event/skin snapshots | Sixteen positive / sixteen failed per cache |
| Per-player transport | One assembly; 16 KiB chunks, 64 KiB window, 64 waiting identities / 8 MiB |
| Skin textures | 64 entries, eight outstanding jobs across reconnects |
| Custom PNG | Exactly 128x128, at most 512 KiB |
| Server / client compressed PNG | Sixteen server files; client 64 files or 32 MiB |
| Glow masks | 32 owned textures / 32 failed-source entries |
| Event/voice compressed cache | Sixteen files; each at most 1 MiB minus 1 KiB |
| Decoded clip cache | Sixteen clips or 32 MiB; one clip at most 8 MiB / 30 seconds |
| Event/voice jobs | Four decoders, sixteen waiting callbacks, eight active/pending-cleanup channels |
| Voice discovery | 4096 directory entries / 64 candidate basenames |
| OGG guards | 4096 event/voice pages; 128 KiB comment/setup headers; 1,000,000 codebook and mode-residue cells each |
| Music file | 8 MiB minus 1 KiB; ten minutes |
| Server music snapshots | Four files, roughly 64 MiB compressed plus encoded |
| Client music | Four compressed files under 32 MiB; sixteen requests, two initial jobs, one stream |
| Editor | 64 sessions, one pending job each, 30-minute lazy expiry; four global I/O jobs |
| Save patch / response | 24,000 UTF-8 bytes / 512 fields; 65,536 response JSON characters |
| Template read/write | 512 KiB, depth 32 |
| Preset copy | One pending native I/O job, 96 media files / 32 MiB |
| Entry loot | 32 pools, 64 items each: 2048 configured items maximum |
| Diagnostics | 2048 events / 128 active attack IDs; config fingerprints up to 2 MiB each |
| Edited numbers | 128 characters, 64 significant digits, decimal scale -64 to 64 |

Current music and loop buffers can retain compressed bytes beyond the four-file
cache. A complete PCM track is not retained. Per-loop PCM output caps at ten
minutes plus one second.

Queue saturation can suppress media. Waiting audio expires after eight seconds
before transfer starts; first-use audio can still arrive late. A stalled peer
can retain an in-flight bounded reservation until receipt/disconnect.

## Work reviewed for 0.1

Numeric guards precede expansion/formatting, and exact decimal comparisons
enforce field bounds. Imported extremes display compactly. Save serializes once
and uses the same client/server patch bound. Empty rewards skip table/context work.

Nonempty entry tables build only at committed payout. Picker stacks/names/categories
cache per opening; filtering runs on input and only visible icons own widgets.
The detached preview renders only on its page, refreshes files on explicit
request, and shares bounded mask/texture caches.

Target rotation runs at native assignment/completion boundaries. Holds/counts
use one UUID, timestamp and saturating count per encounter. Cheap arena/range
tests precede raycasts. Suppressed titles are not queued.

Support shares arena queries. Native effect/attribute events reconcile grants
and equipment changes. Offhand restoration checks the recorded slot.
Lifecycle bottle cleanup does not scan world containers.

## Cleanup and practical limits

Exit/death/unload/disconnect/respawn/server-stop paths release the relevant
encounters, bars, grants, selections, sessions and queues. First-heal ledgers
and potion cooldowns are deliberate saved gameplay state.

Generation/session checks prevent old callbacks publishing into replacement
sessions. Media completion runs outside cache locks, including rejection.
Temporary music capacity retries through existing membership scans; invalid
files wait for reload.

Textures release on the client thread. Native channel removal precedes buffer
deletion on the audio executor. Upload memory, decoders and image streams have
explicit cleanup. Resource/device reload drains owned work before teardown.

Stale/invalid saves preserve the prior file. One rolling GUI backup is kept.
Deleted templates remain recoverable whole-folder archives and are not pruned.
Staged writes prefer atomic replacement with rename fallback; remote filesystem
transaction behavior is outside that guarantee.

The 2026-10-10 release recorded 4384 audit assertions, 1038 native GUI assertions
and 28/28 server tests with/without the supplied pack, plus production-jar local
startup/spawn/activate/save/remove/shutdown. These are correctness/layout/startup
results. They do not measure TPS, allocations, long-duration memory, real
multiplayer latency or full-pack client play. Source review cannot prove all
runtime leaks or third-party interactions are covered.

## Earlier build records

These notes preserve the original build and review evidence. Version, installation,
publication and pending-test statements describe when each note was written.
Use the current sections above for setup and behavior. A later pass does not
turn an earlier compile-only check into a playtest.

Local `../work/` and `../outputs/` paths refer to the original development
workspace. They are outside version control and may be absent in a clone.

<details>
<summary>Expand the dated notes and technical evidence</summary>

## Beta RC 8 entry-editor costs — 2026-10-10

No new watcher, scheduled task, packet type or global reward queue. Entry data
is parsed on settings changes; item lookup validates spawn/reload. Native table
construction occurs once on committed payout and is capped at 32 pools and 64
items per entry (2048 total configured items, with native stack splitting).
The existing exactly-once claim/death/gamerule/removal gates are shared by both
sources. Editor catalog snapshots add a bounded copy of registered item IDs to
the existing file transaction. Search names/stacks/categories are cached per
picker opening; filtering happens on search/category input, not on frames.
Only a visible page owns icon widgets; closing/removal releases the picker.
Native and pack suites passed 28 cases, audits 4366 assertions, native GUI 1023
assertions. These are correctness/layout results, not heap/network/performance
benchmarks. RC8 is installed with verified copies and RC7 backups recorded in
`../work/beta-rc8-deployment.json`. No production startup benchmark was run.

## Beta RC 7 rewards — 2026-10-10

Loot is compiled on config changes and resolved at native death boundaries;
there is no new watcher or network payload. Spawn/reload check one table.
Editor saves snapshot immutable registry metadata on the server thread, then
reuse the existing bounded file worker. The native invocation wrapper passes
ordinary entities through without allocating reward state. Marked Wardens hold
one transient handoff, consumed on committed death, plus one durable claim bit.
There is no global queue retaining bosses or players. Partial failed rolls are
not retried. Both native/pack suites passed 24 cases; audit passed 4332 assertions.
These are correctness results, not a server performance benchmark.

## Beta RC 6 inventory ownership — 2026-10-09

RC6 is a local build; RC5 remains installed. Native inventory transfer replaces
sharing one mutable stack between two slots. One comparison snapshot is retained
per player whose offhand was displaced, released with the existing encounter
membership. Cleanup inspects that recorded slot and transfers the live contents;
it does not scan inventories for personal items or restore from snapshot bytes.

Count/item/other-component changes block restoration. Capacity alone may change
under stackable-item integrations; a comparison copy is allocated only when
capacity differs. These operations remain event-boundary work. No new watcher,
background worker or network payload was introduced.

All 17 native tests passed with the server pack and without it. Audit passed
4316 assertions, and the actual release jar passed production startup and the
spawn/activate/save/remove/shutdown sequence. These are correctness checks, not
TPS, allocation or long-duration memory benchmarks. Scope and pack warnings:
[MODPACK_TESTING.md](MODPACK_TESTING.md).

## Beta RC 5 bounded compatibility capture — 2026-10-09

RC5 is installed in Test Client and the test server. Capture defaults
to off, checks its flag before creating event data, and adds no tick callback,
worker, client packet or live-entity cache. Existing event observers return
without changing native damage decisions. Enabled capture retains at most 2048
events and 128 attack IDs. Completion, cancellation and boss unload/death release
attack IDs; clear/server stop releases all captured data. Operator dump performs
bounded synchronous local IO, including a runtime mod inventory and hashes of
known integration configs up to 2 MiB each. It does not copy config contents.

Recovery guards the native Brain calls that start/tick behaviors. Lithium's own
method implementations remain intact; ordinary Wardens bypass boss state lookup.
Memory expiry and sensors still run during boss Recovery. A health-phase change
cancels pending melee before resetting the state so cancellation stays observable.

4316 audit assertions and all 15 project native tests plus the vanilla control
passed with and without Lithium 0.26.2+mc26.3. The client model check passed with
Sodium/ETF/EMF/Lithium loaded. These checks establish invariants, not performance
benchmarks or proof of every mod interaction. See
[COMPATIBILITY_TESTING.md](COMPATIBILITY_TESTING.md) for remaining integration work.

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

## Recovery and arena player support — 2026-10-09, pending source

Recovery stores one deadline per boss. Existing AI checks it; native behavior
boundaries use a cached state lookup only for Wardens. Native memory expiry and
sensors continue, so cooldowns are not frozen. No new watcher or scheduler.
Each load-time child snapshot is bounded by the existing 16-entry catalog.

Health/potion membership shares the existing ten-tick arena query and diff.
Exact potion geometry checks use cached bounds/distance and loaded UUID lookup;
they scan no players and load no chunks. Native MAX_HEALTH dirty events allocate
one scratch attribute to reuse native modifier math on equipment changes,
not every player tick. Only the named owned modifier is changed. A revision guard
reconciles synchronous membership removal inside another mod's health callback.

Player maps retain connected participants only and release on exit/death/unload/
disconnect/respawn/server stop. Heal ledgers retain at most 4096 UUIDs per saved
boss, never Player references. Potion cooldowns save one native game-time long
per player, with no offline registry. Inventory scans occur at membership/config/
lifecycle boundaries only; ordinary ticks and rendering do not scan inventories.
World containers are not scanned; any stored bottle is inactive outside its
owner's valid arena and shares that owner's deadline. Ordinary mob/item paths
pass through. Runtime profiling, latency and gameplay checks remain pending.

## Sequence/random ordering and separate roar catalogs — 2026-10-09, pending source

Each compiled health-phase sequence now owns immutable attack/roar lists and its ordering policy. Each boss owns indices, counters, an interlude flag and at most sixteen shuffled integers. Fisher–Yates runs only on first selection and after a round completes. Roars use an independent index in sequential mode or one native random draw per transition in random mode. No disk reads, attribute merging or new tick task are added. Parent changes/reload discard the bag and reset both cycles; removal releases the existing state owner.

GUI dragging retains only a root/key and position, not widgets or entities. Mouse input alone scrolls/rebuilds; rendering only draws handles and an insertion marker. Release changes draft insertion order; save uses bounded known catalog order arrays and existing scalar/session/permission/concurrency validation. Resize/page change/busy/removal/Escape release drag state. Up/Down controls remain available without mouse dragging. Catalogs retain 0–16 entry limits and existing total document/patch bounds. Empty default catalogs preserve existing behavior. Runtime profiling and interactive GUI/gameplay checks remain pending; installed jars are unchanged.

## Subphase ownership and attack timing — 2026-10-09, pending source

Subphase policies/attributes are compiled once on load/reload, bounded to 16 health phases with at most 16 children each. Runtime uses a child index, completed-attack count and generation. Completion callbacks advance children; only the existing Warden AI callback resolves pending melee and requests native roar. No new entity sweep, scheduler, thread or directory scan is added. Main-phase ownership keeps health/scale/aura/glow/events separate from behavior sequences.

Pending melee retains one UUID, deadline, generation and immutable policy, never a target entity. Native loaded-entity lookup does not load chunks. Target replacement, disconnect, death, phase change, reload and boss cleanup discard it immediately. Movement retains its timer; native range/visibility at impact determine whether the hit can land. A missed impact clears the record. The eventual hit goes through native `doHurtTarget`; a vetoed hit releases its visual in `finally`. Melee starts cancel unfinished beams, avoiding two pending attacks sharing the callback. Timelines contain bounded primitives and update metadata at start/impact/cancel, not every tick; clients parse only on changes and sample native keyframes from shared world time.

Roar reuses Minecraft's native activity/memory/pose/duration. Its mixin owns primitive tokens only; token checks stop interrupted roars from advancing replacement sequences. Ordinary Wardens retain native behavior. Attack recoil finishes before a configured roar starts. Native stop's target assignment would install a new 200-tick Sonic Boom delay; the hook restores the naturally elapsed prior cooldown for configured roars only. Custom roar files are warmed on load/reload through the existing bounded cache, with normal basename/OGG validation and transfer limits.

Editor saves permit only bounded phase/child ordering operations plus known scalar edits. Server-side permission, session/version checks, numeric bounds, document/patch size limits and atomic saves remain in use. New defaults/presets contain empty sequences. Common/client compilation and lexical import review completed; no runtime profiling, tests or game launch occurred. Installed Beta RC 1 is unchanged.

## Beta RC 1 packaging — 2026-10-09

The targeting, saturated preview and lifecycle review source changes below are now packaged in the universal Beta RC 1 jar. Offline `assemble` succeeded, and the output/Test Client/freshly downloaded installed server copy share SHA256 `05EBD18B5009CF1DA0E55B1EC8BE29F970C82411C831E7C9D3D3CF36B9D1759B`. Both prior jars were backed up. No runtime profiling, tests or game launch were performed; existing configs/media were preserved. Earlier pending-source wording records the state during those reviews.

## Tightening and lifecycle review — 2026-10-09, pending source

Reviewed runtime state owners and their failure/teardown paths: loaded bosses and watcher, player membership/effect ownership, target state, server media snapshots/transfers, editor sessions, client skins/masks, decoded clips and streaming music. Rechecked spawn failure, removal, unload, death, reload, disconnect and shutdown cleanup. Static import/reference checks found no unused import candidates in production source; framework/mixin callbacks remain runtime entry points.

### Fixes

- Music now distinguishes a rejected file from a full file-work queue. Temporary capacity exhaustion retries through the existing arena scan; failed files remain suppressed until reload. A rejected outgoing transfer clears the listener's started flag and cancels its already-sent start control, allowing a later retry.
- Media-cache executor rejection returns worker capacity and removes pending identities. Rejected backlog jobs complete instead of remaining stranded. All future completions, including scheduling failures, occur outside the cache lock so consumer callbacks cannot introduce a lock-order dependency. Error bookkeeping allocates only on rejection.
- Closing/replacing the editor through another screen invalidates its token and closes its server session, then releases preview textures/content/entity references. Its own picker-to-document navigation keeps the session. Late preview packets cannot recreate content after an external exit. A player who loses operator permission may still close their own session; file operations retain permission checks.
- Preview skin/face/tint metadata is parsed and applied only after an edit, page rebuild, explicit refresh or entity recreation. Rotation still uses Minecraft's fresh render snapshots. Dirty checks compare JSON trees plus phase order without serializing two full documents. Mask-cache hits avoid rebuilding paths, identifiers and normalized face records; the existing 32-texture ownership bound remains.
- Music submission rejection releases the decoding reservation or unattached decoder and clears the preparation latch. Failed channel futures terminate their request instead of repeatedly retrying every audio tick; temporary native pool exhaustion can still retry.
- Target candidates are filtered by arena/range before an optional collision raycast. Removed a redundant UUID predicate. No target behavior or new timer is introduced by this ordering change.

### Retained bounds and limits

The existing 10-tick boss watcher is absent when no bosses are loaded. Media work uses Minecraft's workers: four server reads with at most 64 pending file identities, one receipt-driven assembly per player with a 64 KiB window and at most 64 waiting identities / 8 MiB, and two client music preparations across reconnects. Skin/mask/clip/music caches keep their existing count/byte bounds and generation checks. Native pooled channels and explicitly closed decoder/image/buffer resources retain their existing owners. Unloaded bosses saved by Minecraft are persistent encounters, not abandoned runtime registrations; unloaded chunks are not force-loaded during this review.

No new watcher, thread pool or migration was added. Common/client compilation uses the configured Java 25, Fabric Loom 1.18.2 and Minecraft 26.3 dependencies. No tests, game run, heap profiling or simulated connection/device failures were run. Source review and successful compilation do not prove absence of runtime leaks or compatibility problems. Existing installed jars/templates are unchanged.

## Phase filename validation — 0.1-preview.8

- One precompiled sound basename pattern now permits ordinary ASCII spaces. Shared server/client validation remains bounded to the same filename length and fixed sound folders. Separators, colons, leading dots and control characters remain disallowed; no additional read, scan, scheduling or transport work is introduced.
- Fresh logs confirm phase event dispatch followed by invalid-name rejection, so attack, phase selection, sound transport and OGG decode were not rewritten. Warning/config guidance now matches the allowed character set. Compilation/package only; audible phase playback remains pending.

## Folder layout — 0.1-preview.7

- Three fixed config paths change: `Boss Templates`, `Boss Presets` and `sounds/voice lines`. Directory initialization/name-cache discovery retains the same lifecycle; no migration scan, polling, event listener or cache is added.
- Phase-sound diagnosis identified configured filenames without spaces and server files containing spaces. Existing phase inheritance/playback logic is retained, with clearer null/empty/explicit-file documentation. Matching files and `/boss reload` clear the existing negative cache; no broad sound/decoder rewrite is warranted by those missing-file warnings.
- Compiled/package confirmation only. User reports smooth preview.6 behavior and working activation/death clips; phase-file retest is in progress.

## Entire codebase review — 0.1-preview.6

| Work/resource | Bound and owner |
| --- | --- |
| Encounter watcher | Existing ten-tick loaded-boss scan; inactive with no loaded bosses. Native AI still handles active Wardens. |
| Activation / visibility | Native region boxes and dimension keys compiled on load/reload; one existing spatial query/membership diff. |
| Area buffs | Five whitelisted native effects; reconcile on membership/config/effect events, with exact player/effect mutation scope. |
| Server media reads | Minecraft I/O pool; four running / 64 pending files per cache; duplicate filenames share work. |
| Server snapshots | Sixteen positive and sixteen failed file entries per cache; reload invalidates generations without bypassing worker limits. |
| Per-player media transport | One file in progress; 16 KiB chunks / 64 KiB send window; 64 waiting identities / 8 MiB wire data. Shared immutable bytes avoid per-recipient file copies. |
| Client assembly | One buffer, at most 1 MiB; only known skin/sound codecs; reset on session teardown. |
| Client skins | Existing 64-entry texture bound / eight running jobs, bounded PNG dimensions/bytes and HTTP time. |
| Client audio | Sixteen compressed files; sixteen decoded / 32 MiB PCM; four decoders, sixteen waiting callbacks, eight active channels/voice leases. |
| Decoder safety | Existing file/page/CRC/duration bounds plus 128 KiB comment/setup headers, <=1,000,000 codebook cells and <=1,000,000 mode-residue cells. Native JOrbis remains the decoder. |
| Native upload memory | Allocated after channel acquisition; freed after OpenAL copies it or idempotent cleanup; cached PCM is heap-owned. |

File edits require reload. Failed files are negatively cached instead of retried/logged per hit.
No filesystem work, directory discovery or JSON region reconstruction remains in attacks,
watcher checks or autocomplete. Audio/skin disk work is asynchronous; queued audio coalesces
and expires before starting after eight seconds. A file already in flight remains bounded
until receipt/disconnect; a stalled peer can retain that reservation, but cannot grow it
through duplicate receipts or reload. Queue saturation can suppress media; first-use audio
may arrive late on a poor connection. These are explicit limits, not a no-lag guarantee.

Reentrant native effect callbacks use boss/player ownership checks and snapshot iteration.
Disconnect/respawn/shutdown release references; stale workers/connection callbacks cannot
publish new-session state. Render resource release stays on the client thread, sound cleanup
on the native audio executor. The phase flash uses a local entity clock and foreign emissive
textures are left to their owners. No new periodic transport or file watcher is installed.

Static source/native API review and compile/package only. No new tests or gameplay were
run, and complete modpack/poor-connection compatibility remains unconfirmed. Historical
results below must not be read as validation of these changes.

## Historical build records

## Attack voice lines — 0.1-preview.5

- Two existing successful-hit hooks read one immutable policy and one primitive per-boss deadline; no voice tick listener, repeating task, queue or extra AI behavior exists. Randomness and file work are skipped while disabled/busy or the folder is empty. Native audio continues to own clip cleanup through the existing pause-aware one-shot timeout.
- File discovery is bounded to 4096 entries / 64 candidate basenames on server start/reload. Streams close with try-with-resources. Invalid selected files leave the catalog until reload, suppressing repeated disk/log errors. Shared event/voice audio caching remains sixteen files, with separate filename identities. OGG duration extraction is part of the existing bounded CRC/header scan, rather than server codec decoding.
- At most eight client voice leases can be outstanding, storing only UUID/ownership state. Decode/waiting/channel/memory bounds are unchanged. All failure/stop/session paths release the same idempotent lease; delayed callbacks recheck ownership, and native buffer cleanup releases the lease after stopping its channel. Busy/rejected delivery still touches compressed-cache LRU state.
- Real-time deadlines are owned only by loaded boss state and never saved; entity unload/removal/shutdown releases that state. The native server-start hook repopulates the catalog after an integrated world is reopened. No client acknowledgement or client-chosen filename can request a server file.
- Compilation/package confirmation only; no new runtime or test execution was performed for this feature.

## Activation sound and scaled dormancy — 0.1-preview.4

- Spawn audio now uses the existing one-time activation transition. No added sound timer, tick listener or spatial scan; phase/death event wiring stays unchanged. Exact sound filenames were corrected on the test server after its log identified missing files. OGG validation, payload bounds and caches remain intact.
- Dormant holding depth is calculated only at initial/changed phase assignment or reload/rehydration. Unchanged phase checks return before positioning. Initial placement precedes entity tracking; the native SCALE attribute includes effective modifiers without an extra dimension-refresh call.
- Grouped config comments are generated only on file writes. All six preset resources use the shorter catalog; no documentation processing is added to gameplay checks. The production parser/writer reformatted documents; no new runtime or test execution was performed.

## Bundled presets — 0.1-preview.3

- Six fixed classpath resources are considered only during mod initialization. Existing preset entries prevent installation; missing resources are read with the 512 KiB template bound, parsed through the normal document reader and saved to the separate `presets/` folder through the no-replacement path. Resource streams/readers close promptly; no extra cache retains preset trees.
- Gameplay and autocomplete scan/load only `templates/`. Copying a preset there and reloading uses the same immutable name cache and ordinary template workflow, with no flags, special extension handling or additional suggestion/gameplay checks.
- Complete-sibling-file writes preserve existing edits and avoid exposing a partial generated template. Missing examples are recreated only in `presets/` on startup. Active templates are independent; no player-count query, tick listener, timer or separate preset runtime is added.
- The universal jar packages all six resources with the configured 26.3 build. Runtime startup/gameplay confirmation remains pending.

## Configuration layout — 0.1-preview.2

- A single shared `configRoot()` defines `config/clockwork-bosses/`; templates use `templates/`, with sibling `skins/` and `sounds/`. Current folders are created through native filesystem operations during initialization/config commands; no folder check is added to gameplay ticks.
- Removed legacy directory scans/moves and global-spawn archival checks from `ensureDirectory()`, along with their unused logger, flag and methods. No migration state remains. Template atomic-save and bounded media validation behavior continue to use the current folders.
- Compiled/package confirmation uses the configured 26.3 environment; no new runtime test was run for the path change.

## Sonic Boom melee priority — 0.1-preview.1

- The existing AI step reads one compiled phase snapshot and computes one close-range priority boolean. Native melee reach is queried only when melee is enabled and a target exists. Both charge entry and continuation use this value.
- Melee-disabled encounters can attack at close range with Sonic Boom. Configured cooldown/charge/range/line-of-sight behavior stays in the existing path. No additional query, timer, tick hook or target-selection behavior is introduced.
- Compilation/package verification uses the configured 26.3 environment; gameplay confirmation remains pending.

## Selector input — 1.0.5

- Native client pre-attack events replace the selector's vanilla punching gesture. One fresh click sends one native predicted action; a boolean suppresses continued input until a fresh press even when selection removes the arrow. No player/world reference is retained, and disconnect clears the flag.
- Changing the common block callback to `CONSUME` avoids mining-success feedback. Cancelling vanilla start/continue attacks prevents separate punch packets and their block-hit particles/sounds. Ordinary attacks and right-click selection retain their existing paths.
- No additional tick event registration, timer, mixin or custom network payload is added. Compilation succeeded with the configured 26.3 dependencies; gameplay verification remains pending.

## Player area effects — 1.0.4

- Buffs share the existing ten-tick boss-bar spatial query, membership snapshots and entry/exit diff. Equal membership returns before effect processing. No new tick callback, watcher, area query or periodic effect refresh exists.
- Effect settings are parsed only on template load/reload. Entry, exit and reload reconcile five fixed types, using the maximum contribution from overlapping active bosses. Empty/disabled settings do not retain players.
- Native Fabric effect-add/remove events protect external effect timers and resume eligible grants after expiry/removal. Each player/type has at most one pending native server task; it runs once outside the effect iterator and checks that the original membership still exists.
- Cleanup releases boss contributions and player references on boss death/unload/removal/shutdown and player death/leave/respawn. Saved native tags recover matching grants after a crash. Native effect permission callbacks remain respected; a mod that explicitly vetoes removal can prevent cleanup, with recovery metadata retained.
- Native Regeneration and the other effects still execute Minecraft's ordinary effect/attribute behavior. Infinite duration removes mod-driven refresh traffic, not the native gameplay processing of status effects.
- Compiled with the installed Minecraft 26.3 and Fabric API 0.161.0+26.3 dependencies. No new runtime gameplay test was run for this feature.

The phase visual system is deliberately event-driven.

Release 1.0.2 removes all AI tracing code, including the command, toggle state, target-history fields, periodic snapshots and activation/emergence diagnostic messages. Trace descriptions and checks below document earlier builds. The functional Warden AI hook and encounter watcher retain their existing behavior.

- Effect color is synchronized through vanilla entity metadata only when it changes.
- Phase-change flash is a local client calculation for 8 ticks; the server does not tick a flash counter down.
- Particle explosion is emitted once at the phase transition and is not part of the normal tick loop.
- The emissive tint is applied during the existing Warden emissive-layer render path; no additional model pass is added.

## Runtime tick and lifecycle audit

- A single Fabric tick dispatcher remains registered because Fabric's event API does not support unregistering callbacks. It immediately returns while the lazily created Clockwork Boss watcher is absent, so an empty server does no manager work. The watcher exists only while at least one loaded Clockwork Boss has runtime state. Every ten ticks it checks dormant activation, refreshes dormant Darkness, and reconciles active boss-bar viewers; boss-bar progress and membership are sent only when they change.
- Active phase changes, emergence completion, active Darkness, and Sonic Boom charge are handled from the Clockwork Boss's existing AI step. This removes the old every-server-tick manager sweep for those active-entity tasks. Emergence and Sonic Boom cooldowns use the Warden brain's expiring memories instead of duplicate countdown fields.
- The ten-tick activation scan is intentionally retained. Arbitrary player entry into configured cuboids and redstone signal edges do not have a reliable Fabric callback in this project; replacing them would require broad movement/redstone mixins with more compatibility risk. Dormant aura checks share this watcher cadence. Active aura spatial queries run every ten ticks, or sooner when a configured player refresh interval is shorter; per-player deadlines prevent effects from being reapplied on every scan.
- Vanilla Warden AI, Sonic Boom charge, melee cooldowns, and Darkness effect durations use game-tick semantics. The client selection preview also uses an end-client-tick callback, but it returns immediately unless the player has the active selector held. A render callback would run at frame rate and would not reduce this work. The short flash is calculated during rendering rather than counted down by a tick task.
- `/boss trace` enables the detailed AI logs on demand. It is disabled by default, avoiding periodic diagnostic log traffic during normal play.

## Resource and lifecycle checks

- Player skin textures use a 64-entry least-recently-used cache; obsolete dynamic textures are released. Skin image dimensions are checked before pixel decoding, and downloaded bytes are capped.
- Emissive tint masks use a 32-entry cache, preventing arbitrary Warden texture variants from growing the client texture cache without limit.
- The former per-Warden client color-log map was removed; it retained UUIDs indefinitely. Normal rendering no longer creates diagnostic map entries.
- Clockwork Bosses are rehydrated when loaded from saved chunks. Runtime state and boss-bar references are cleared when entities disappear from the loaded world and when the server stops. Spawn failure rolls back any partially added Warden.
- Entity unload now releases runtime state and boss-bar player references immediately. Named removal and `/boss remove all` also find loaded Clockwork Bosses by their saved marker, even if a template is missing or invalid. A Warden in an unloaded chunk must be loaded before a removal command can reach it.
- Dormant Clockwork Bosses intentionally persist in saved chunks. A Warden in an unloaded chunk is not a live entity leak; it is restored when that chunk loads. Removal commands can act only on loaded entities.
- Darkness radius, refresh timings, durations, and amplifiers are bounded when read from template data. A stronger Darkness effect is no longer replaced by a weaker configured effect.

## Compatibility review

- Common Warden mixins are restricted to entities marked as Clockwork Bosses. Client tint hooks additionally require the custom Warden render-state marker, leaving normal Wardens and unrelated living-entity emissive layers unchanged.
- Client-only mixins remain in the client mixin config. Network state uses vanilla entity metadata, and texture downloads remain restricted to Mojang's texture host.
- Remaining compatibility risk is concentrated in the version-specific Warden AI and emissive-render mixin targets. The project pins Minecraft 26.3; a Minecraft or renderer update should be validated with the project's mixin smoke run.

## Skin and configuration boundaries

- Player-skin generation begins with a transparent 128x128 base atlas and draws only mapped skin UVs into it. Only the requested moving rib plates use vanilla UV pixels. Obsolete jaw-removal/filtering helpers are deleted. Separate white-alpha masks preserve phase glow and pulse, with angry eyes replacing the mouth.
- Per-template spawn data avoids one global position being shared by unrelated bosses. Missing/unset per-template spawn now uses the command source; global spawn is no longer loaded.
- Template-folder migration moves JSON files only when the destination name is free. Conflicts and other files remain untouched in the old folder; new template reads and writes use `arena-bosses/`.
- Generated templates use JSON with `//` comments from one bundled catalog; the comment-aware reader also accepts existing strict JSON.

## Scale and custom sound resource bounds

- Warden scale uses the vanilla `Attributes.SCALE` attribute and is applied only when the selected phase changes, when a boss is created/rehydrated, or during template reload. The entity dimensions are refreshed at the same time; no new per-tick scale work is added. Base and phase values are clamped to 0.25-4.0.
- Custom sounds are server-selected OGG Vorbis files identified by a filename only. The resolved file must remain directly inside `config/arena-boss-fight/sounds/`, be a regular file, and be no larger than 1 MiB minus 1 KiB reserved for packet metadata. The server checks OGG page bounds/sequencing, CRCs, and the mono/stereo Vorbis identification header before sending bytes.
- Clients verify the SHA-256 revision and OGG structure again before decode. Decoding runs off the game thread, accepts only mono/stereo 16-bit audio at 8-192 kHz, and is capped at 8 MiB decoded data and 30 seconds. At most four decodes can be pending, compressed/decoded caches have fixed bounds, and no more than eight custom sounds can occupy vanilla static channels at once.
- Audio bytes are sent only to nearby modded clients, are never written to client disk, and are cleared from per-session caches on disconnect. Playback uses vanilla's existing channel pool and Hostile volume slider; delayed cleanup releases its temporary channel and OpenAL buffer without adding an audio tick loop.

## 2026-10-06 server smoke test

- Gradle 9.7.0 `build --offline --no-daemon` succeeds with Java 25.
- An isolated Minecraft 26.3/Fabric server loaded the mod with 44 mods and reached `Done` without a mod or mixin startup failure.
- `/boss trace` was confirmed to toggle on and off. Template creation, dormant spawn, list, named removal, and remove-all were exercised in the isolated world.
- A saved Clockwork Boss whose template was removed logged one rehydration error, remained removable by name, and was absent from the loaded entity query afterward.
- Manual activation confirmed the brain-memory emergence timer ends and hands control back to normal Warden AI; the trace showed the transition from `emerge` to `sniff`/`investigate`.
- The fresh server printed Windows OSHI performance-counter warnings while building Minecraft's system report; they were non-fatal and unrelated to this mod.

## Comprehensive audit — 2026-10-07, version 1.0.1

This section supersedes older implementation descriptions above. Earlier smoke results remain historical evidence.

### Changes and bounds

- Removed `ClockworkBossConfig`, global spawn loading/fallback, the broad LivingEntity death mixin, obsolete jaw/blur/averaging image helpers, one-time render diagnostic flags, duplicate tracking registries, unused selector state/constants, and unused publication/data-generation build setup. Existing global config is archived without overwriting a backup. Used legacy saved-entity/template/Darkness compatibility remains deliberate.
- Native 26.3 permissions gate the entire command tree and selector edits after permission revocation. Native AFTER_DEATH handles the committed death event. Resource-loader-v1 orders cleanup before native texture/sound reload. MixinExtras v2 WrapWithCondition avoids the deprecated v1 hook. Loaded-chunk redstone checks do not force chunks.
- Compiled immutable settings eliminate JSON traversal/allocation in phase/attack/aura hot paths. One ten-tick watcher exists only while loaded runtime bosses exist. Its permanently registered dispatcher has an immediate idle guard. No custom targeter or extra model rendering pass is added.
- World-time deadlines replace Sonic Boom charge countdowns. Native brain memories own cooldown/emergence. Persisted emergence locks prevent a restart leaving an invulnerable boss after its transient brain state is gone. Native scale notification already refreshes dimensions; the redundant refresh call is removed.
- Darkness uses native spatial queries, bounded radius (256), effect duration/interval (12000 ticks), amplifier (0-4), reusable scratch membership, and per-player deadlines pruned on range exit/disconnect. The marked boss's fixed vanilla aura is suppressed, so disabled phase Darkness really stops new applications.
- Beam particles cap at 64 samples for extreme ranges; ordinary ranges preserve one sample per block. Phase bursts cap at 128 and run only on transitions. Activation/bar boxes cap at 256 blocks per axis. These are individual bounds, not a global limit on the administrator's number of bosses or players.
- Player/custom texture LRU: 64 entries. Server custom PNG cache: 16 files, <=512 KiB each. Client compressed custom PNG cache: 64 files or 32 MiB. At most eight skin loads remain outstanding across reconnects, with timeouts/backoff and unique generation texture ownership. Native images are closed on failed registration; all generated player/custom textures clear on disconnect/resource reload.
- Glow masks: 32 owned textures and 32 failed-source entries. Broken packs do not trigger repeated decode/logging each frame; reload clears failures. Native texture dimensions outside the expected 128x128 face layout keep their original glow coverage.
- Server/client sound file caches: 16 files, each <=1 MiB minus 1 KiB. Client decoded cache: 16 files or 32 MiB; one decode <=8 MiB PCM and 30 seconds. At most four decoder jobs, sixteen waiting decode/play callbacks, and eight active/pending-cleanup channels survive across session changes. Decoded direct buffers are JVM-managed and bounded; OpenAL buffers and generated textures have explicit ownership cleanup.
- Audio cleanup now uses native stop/removal before buffer deletion, and teardown drains pending work before the audio context is destroyed. Cancellable timers stop retaining clips after disconnect and pause with native playback. Native Master/Hostile/category gain and stop controls are mirrored without an additional mod tick loop.
- Template reads/writes cap at 512 KiB and depth 32. Complete temporary writes protect an existing file from partial-save failure. Atomic replacement is preferred, with ordinary rename fallback on providers such as mounted SFTP. This cannot promise transactionality for a remote filesystem implementation.

### Remaining repeating work and why

| Work | Cadence | Reason |
| --- | --- | --- |
| Empty server dispatcher | Native end-server-tick event, immediate null guard | Fabric listeners cannot unregister themselves. No watcher/state scan exists when empty. |
| Dormant activation/aura, active bar viewers | One shared scan every 10 game ticks | Arbitrary cuboid entry and redstone edges lack a narrow event here. Short pulses can be missed. |
| Active phase/charge/emergence | Existing marked Warden AI step | Native health/brain/target state is already available; game time respects pause/server tick rate. |
| Active Darkness query | Every 10 ticks, or shorter configured eligibility interval | Refresh deadlines still need a spatial result for moving players. |
| Native DIG cooldown renewal | Only when expiring brain memory is missing | Prevents native DIG activity suppressing FIGHT/navigation. |
| Optional AI trace | Target change and one 100-tick snapshot | Off by default, command-toggleable. |
| Held selector preview | Native end-client-tick, immediate held-tool guard | A frame-rate callback would perform more work; native Gizmos manages drawing. |
| Glow/pulse/flash | Existing render layer | No extra model pass or server flash countdown. |
| Sound cleanup | One cancellable event timeout/native lifecycle | No mod audio tick watcher. Native channel housekeeping remains Minecraft-owned. |

### Verification and limits

The regression suite checks production commented-template round trips, explicit null fields, malformed/trailing/deep JSON, exact health boundaries, configuration bounds/inheritance, immutable snapshots, PNG/OGG truncation and corruption, revision verification and packet limits. The isolated client probe checks actual native textures/pixels, an actual vanilla Vorbis clip, audio ownership/pause/resume/stop, and native resource reload. An initial probe reproduced a double-release shutdown crash; the final ownership path removes it.

Dedicated-server smoke checks creation/setspawn/activation/emergence, active and dormant persistence, removal while another boss exists, empty cleanup and a saved boss with a missing template. Missing-template restoration logs once for that load and the boss remains removable by its saved marker.

Compilation is checked against the pinned 26.3 jars with deprecation warnings enabled. Static unused-import/private-reference candidates are reviewed; the remaining single-reference private methods are annotated Mixin entry points. No whole-modpack compatibility or long-duration load benchmark is claimed. Administrator-created extreme boss counts, third-party mixins/resource packs, and real multiplayer media delivery still require the normal in-game check.

## Mono authoring guidance — 2026-10-07, 0.1-preview.9

Directional boss clips should be mono OGG Vorbis. Existing stereo acceptance is unchanged; conversion is an offline administrator task, with no extra server/client decoding or tick work. Five event clips were converted and installed after retaining originals and checking source/destination hashes. Mono reduces these clips' encoded sizes and halves their decoded PCM channel storage. Native playback still uses the initial boss position; a moving-source follower was not added.

## Dormant Darkness settings — 0.1-preview.11

An optional, bounded immutable dormant aura snapshot selects underground settings with one branch in the existing ten-tick watcher. Active phase selection stays on the existing AI path. The shared parser runs only on config load/reload; no additional repeating task, spatial query, lifetime collection or network channel is introduced. Activation already clears the aura schedule; existing native effects expire naturally.



## Template tuning and lowercase paths — 0.1-preview.13

Path/filename rules are evaluated only during configuration/media lookup. Aura tuning reuses the existing immutable settings and ten-tick watcher/AI scans. Underground pulses use the existing per-player eligibility deadlines; Fury refreshes a bounded native effect rather than adding a repeating task. Config editing, backup/hash comparison and folder rename are offline installation work. No runtime migration, extra listener, timer or collection is introduced.


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


## Local health/combat follow-up — 2026-10-08

The health extension adds one boolean to native attribute instances; only marked
boss health instances opt in. It retains native caching and dirty notifications,
without a detached health pool, entity map or recurring scan. Client metadata and
native saved-marker loading configure the same range.

An in-progress beam owns one immutable UUID/settings/deadline record per boss,
allocated only when charging starts and released on fire/cancellation/unload.
It retains no target/entity object. Phase preparation reuses the current runtime
snapshot, exits when unchanged and adds no tick watcher. Melee successful-hit
ownership uses one flag plus a world-time timestamp. Combat keeps native damage,
knockback attributes, target selection and cooldown memories.

4253 audit assertions and 8 native GameTests passed in the isolated world. This
verification establishes scoped functional behavior, not measured TPS/memory
performance under load or compatibility with every mod/network condition.

## Preview.16 — allocation and query scope

Native sound volume adds one synced float per Warden and no separate packet,
cache or periodic task. Native entity metadata sends only changed values. The
sound wrapper exits immediately for ordinary entities and unrelated sounds.

Target rotation allocates only a short-lived candidate list on a completed attack,
using Minecraft's loaded spatial entity index, the visibility region and follow
range (bounded to 256). No target-history map, scheduler or recurring targeting
scan is added. Vanilla anger management owns opponent references and expiration.

Dormant radius query boxes are cached on configuration assignment. Moving-bar
radius checks reuse the existing watcher and entry/exit diff, including buff
ownership. Squared-distance comparisons avoid square roots. Neither query loads
chunks. This is a code inspection and compile/package pass, not a measured load
or gameplay benchmark.

## Preview.17 — bounded event-driven presentation

Target switching adds no periodic task. Failed/never rolls query no players.
Nearest/farthest choose in one linear pass over a bounded native spatial result;
random selection uses native RNG. Candidate identity excludes both current and
attacked player UUIDs, including replacement instances. Invalid configured regions
produce no candidates instead of a wider fallback. Existing cooldown memory survives
native target assignment, retaining rapid cadence.

Announcements send three small native title packets only on actual UUID changes.
A shared timestamp/cooldown pair per recipient bounds multiple-boss/rapid-attack
traffic. No delayed-title queue or player entity cache is retained. Leave, shutdown
and last-boss cleanup release timestamps; encounter viewers are position-filtered
before delivery. This uses the same HUD as other title features and is toggleable.

Charge visuals add one bounded native metadata string per Warden. It changes on
start/fire/cancel, not per tick; shot sequence makes same-tick restarts distinct.
Parsing is cached per entity, render snapshots hold primitives, and local recoil
expiry allocates no timer or packet. Existing baked Minecraft keyframes receive a
sampled time, without a custom animation/asset system. Late tracking can reconstruct
progress from the current snapshot; native game-clock estimates and packet delay
remain practical limits. No live latency/load benchmark was performed.

## Preview.19 music resource ownership

Music membership shares the existing arena scan; settings compile on config load. Disabled/blank music creates no encounter audio work. File validation and shared wire encoding run on native I/O workers. Four server music snapshots retain roughly 64 MiB maximum for compressed plus encoded copies, with bounded jobs and the existing per-player 8 MiB waiting queue/64 KiB in-flight window. The client retains four compressed files (under 32 MiB), up to sixteen request records, at most two initial stream jobs across reconnects and one native streaming channel; loop buffering/current-track references can add compressed-file memory beyond the cache. Complete PCM tracks are not retained. Native refills/fades own bounded reads; PCM output per loop is limited to ten minutes plus one second. Pause/device/reload/disconnect/death/exit paths stop or invalidate requests and close decoders/channels. Muted Music/Master skips decoder/channel work. These are code/resource bounds, not measured latency/TPS/heap results; no live stress or audio tests performed in this batch.


## Preview.20 editor resource bounds

No additional server tick watcher. Editor sessions: 64 maximum, one pending job per session, 30-minute lazy expiry plus leave/stop cleanup. Global native I/O jobs: four, including deletion. Inbound save patch: at most 24,000 UTF-8 bytes and 512 fields; responses: 65,536 JSON characters. Config reads/writes retain 512 KiB and nesting limits; changed structure is limited to explicit 1–16 phase ordering/add/remove operations. One rolling backup per edited file avoids a backup per Save. Deleted templates are intentionally retained whole as operator recoverable archives, not automatically pruned.

GUI creates controls only for visible rows, preserves raw drafts across resize/scroll, and uses native input/narration/tooltip APIs. Skin preview renders only while that page is visible. The detached Warden is never registered or ticked; full-light native render snapshots supply the model. No file or profile request occurs per keystroke: custom previews require explicit refresh and reuse PNG transport limits; player skins reuse the existing eight-pending/64-entry shared cache. Session-specific custom keys prevent modifying live texture identities and are released on close; old packets are ignored after session teardown. Eye masks retain the 32-entry LRU even across many coordinate combinations. Actual frame time, GUI-scale readability, modpack interactions and network latency have not been measured in this batch.

## Preview.21 GUI/music refinement scope

Collapsed groups and conditional fields reduce visible widgets; collapsed state changes only on user navigation/input. Virtual coordinate defaults are not durable unless changed or required by a submitted geometry mode. Percent formatting/parsing occurs at widget creation/save rather than fight ticks. Music still owns at most one stream and scans at most sixteen requests in the existing native audio tick; per-request category gain replaces the former hardcoded Music gain. The native category list is bounded. No new server scan, timer, thread or unbounded preview resource was added. Compilation is recorded in PREFLIGHT; live rendering/audio-category checks remain pending.

## Pending source: face controls and inline Darkness descriptions

Independent face controls add one synchronized integer for brows and reuse the existing eye integer for dimensions. No new tick watcher or scheduled job is introduced. Eye/brow/preview mask variants share the existing 32-texture cache bound. Steady preview brightness changes rendering arguments only; the detached entity remains unticked. No runtime or performance measurements were performed for these pending changes.

## Pending targeting safeguards

Target holds/counts use one UUID, timestamp and saturating integer per loaded encounter; state disposal releases them. The existing completion/assignment hooks check deadlines with no additional watcher, scheduled task, player registry or tick scan. Automatic arena follow range uses cached dimensions only when attributes are applied. Candidate queries remain native loaded-section queries bounded at 256 blocks; ranged candidates reuse the configured collider raycast only when required. Personal/audience throttle stamps remain one map entry per connected recipient with existing disconnect/server-stop cleanup. No runtime measurements or tests were performed; common/client compilation succeeded.

</details>

## Darkness diagnosis and regression coverage — 2026-10-13

No new production timer, query, cache, listener or retained state was introduced.
The existing ten-tick aura scan and per-player deadlines remain unchanged. Six
new native tests and their empty test environments live only in the GameTest
source set. Embedded players advance native gameplay clocks only inside fixtures;
those manual calls and test resources are excluded from the distributable jar.

Different native test environments separate long-lived aura timing checks from
fixtures that clear the shared manager, and separate overlapping large-radius
auras. Each fixture releases its own boss/player on completion or failure.
All 53 required native tests and the full offline check passed. This verifies
application/timing/settings preservation, not renderer behavior, network latency
or a performance benchmark. Workshop revision protection was retained; it must
not be bypassed to make a stale browser draft appear to save successfully.

## Eyebrow expression resource ownership — 2026-10-13, development source

Positions, expression and exact one-degree rotations fit in the existing eyebrow
metadata integer. Its lower 16 position bits retain the legacy layout. The two
upper rotation codes may make the integer negative; native integer tracking and
unsigned decoding retain the complete pose without adding an accessor or packet.

The rasterizer computes trigonometry once per brow on face-mask cache misses,
samples a clipped 6x6 window and returns at most 36 face-local pixel indices.
Nearest-neighbor inverse sampling preserves sharp pixels and clips before atlas
writes. Existing 32-entry texture-cache eviction, texture disposal and resource
reload/disconnect cleanup cover the additional variants. Rasterization is never
server-tick work, and preview entities remain unregistered and unticked. This is
code-bound resource reasoning, not a measured performance result or runtime test.

Dynamic expression rules compile once on configuration changes. Each loaded state
owns a fixed set of primitive poses/deadlines and five immutable rules; it retains
no entity/player references, growing collection, queue, scheduled task or extra
spatial query. One Fabric damage observer gates by Warden/player ownership, accepted
nonfatal damage and enabled encounter settings. Existing native attack/roar/target
hooks choose poses. The four-shape pool is bounded; disabled rules do not draw randomness.
Random resting faces draw only on real completed swings/beams.

Expiry shares the existing ten-tick loaded-boss visit. It compares primitives and
writes existing metadata only when the selected integer differs. Event selection
does allocate a small immutable pose record, but no allocation is added to expiry.
Hit/target cooldowns prevent every rapid hit/assignment from rebuilding a face mask.
The existing 32-texture bound covers variants across all bosses; many differently
configured bosses can still cause eviction/recreation and need in-world profiling.
Token checks reject stale finishes/cancellations. Reload, phase changes and encounter
end clear transient poses; unload/shutdown release the owning state without leaving
tasks/listeners per boss. Server thread ownership matches native combat hooks.
No runtime performance benchmark or multiplayer test was performed for this change.
