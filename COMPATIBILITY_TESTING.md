# Combat compatibility checks

RC5 is installed in Test Client and the test server, with RC4 backed up. RC5
adds opt-in diagnostics and a Recovery hook that preserves Lithium's optimized
Brain implementations. Deployment record: `../work/beta-rc5-deployment.json`.
The supplied integration jars were used only in isolated tests; no third-party
mods were installed by this deployment.

## Capture a fight

RC6 local server-pack results and offhand safeguards are documented separately in
[MODPACK_TESTING.md](MODPACK_TESTING.md); RC5 remains the last installed build.

Operators use these commands on the server running RC5:

1. `/boss debug on` starts a new capture and clears the previous one.
2. Reproduce one scenario from the checklist below.
3. `/boss debug off` freezes the capture.
4. `/boss debug dump` writes a JSON report in `logs/clockwork-bosses-debug/`.
5. `/boss debug clear` discards the in-memory capture and leaves debugging off.

`/boss debug status` reports whether capture is on, retained events, and overwritten
events. Capture is server-wide, disabled by default, and not saved as a setting.
It uses existing event boundaries, with no added tick watcher. The last 2048 events
and at most 128 active attack IDs are retained. No entity/player references are
stored. Shutdown attempts one export if capture is still on, then clears memory;
a forced process kill cannot flush the buffer. Save a dump after reproducing.

Reports contain exact runtime mod IDs/versions, debug status, tick/sequence/attack
IDs, phase/subphase generations, and relevant snapshotted numeric settings.
Ephemeral salted aliases correlate bosses and players without account names or
full UUIDs. No chat, IP addresses, credentials, skin/audio bytes or whole template
files are exported. Known Lithium/Better Combat/Combat Roll config paths are
fingerprinted, not copied; absent fingerprints do not establish default settings.
Record external mod options explicitly when their config paths differ.

## Interpret the evidence

- `*_start`: captured attack settings and, for delayed melee, its due tick.
- `*_native_attempt` and `*_native_result`: native call attempted, returned
  acceptance, and immediate observed HP/velocity changes. Acceptance can be true
  with zero HP loss. An observed delta can include synchronous external callbacks;
  it is not exclusive damage attribution or a prediction of later client motion.
- `damage_callback_*`: Fabric observations of player attacks/combos against the
  boss and boss damage against players. ALLOW_DAMAGE always returns true from our
  observer. An earlier mod veto can skip it; AFTER_DAMAGE omits fatal hits.
  Missing callbacks do not establish that an attack never occurred.
- `*_completed` versus `*_cancelled`: a completed blocked swing or rejected fired
  beam still consumes its configured attack budget. A cancelled wind-up does not.
  `successful_attack_feedback` is emitted only along the native accepted-hit path.
- `retarget_*`, `target_changed`, `target_assignment_rejected`: attack counts,
  hold/chance decisions, candidate selection, and the observed target assignment.
- `health_phase_changed`, `subphase_enter`, `recovery_finished`, `roar_native_*`:
  generation/behavior transitions and native roar boundaries. Recovery's deadline
  uses world time; its guards leave Brain memory expiry and sensors running.
- `potion_*` and `offhand_*`: native use/interruption, rejection, one claimed saved
  cooldown, observed heal/food changes, and original item displacement/restoration.
- `player_session_*`, `player_entity_replaced`, `*_request_added/removed`:
  session boundaries, the saved potion deadline and encounter support ownership.
  These observations do not themselves simulate a reconnect or grave recovery.
- `external_max_health_update`, `health_floor_reconciled`, `area_effect_*`,
  `owned_effect_removed`: ownership and native changes. Only this mod's health
  modifier/effect grants are removed on cleanup.

Cancellation reasons group target, phase, range, visibility and state changes.
Compare adjacent events/generations; they do not attribute the change to a
particular external mod. No diagnostic callback approves damage or changes AI.

## Automated coverage and limitations

The clean RC4 baseline passed 11 project native tests plus the vanilla control,
and 4316 offline audit assertions. RC5 adds four native compatibility checks:

| Check | What it proves | What it does not prove |
|---|---|---|
| Damage veto / bounded diagnostics | A Fabric veto prevents beam HP loss and additional knockback; completed accounting differs from success; export/ring bounds | Actual Combat Roll invulnerability or an external mod's event ordering |
| Delayed melee movement / phase | Leaving melee range or crossing health phase cancels a scheduled hit | Real roll timing, client rendering or latency |
| Recovery / Brain timers | Native scheduled work pauses, cooldown memory expires, work resumes; an unmarked Warden runs normally | Every Lithium option/version or other Brain replacement |
| Potion / inventory / external health | Non-operator command rejection; full-inventory safety; interruption; owner/copy/cooldown checks; original offhand and external modifier preservation | Better Combat weapon handling, graves, or real reconnect timing |

Run from this project with the configured Java 25/Gradle environment:

```text
gradle --offline auditChecks runGametest
gradle --offline -I ../work/lithium-check.init.gradle runGametest
```

The second run uses the already-provided `Lithium 0.26.2+mc26.3` jar and config
in a separate `build/compat-lithium` directory. It does not add a release dependency
or change game/server profiles. The provided config has zero option overrides.
Test logs confirm Lithium is loaded with its optimizations enabled. Recovery
guards calls in `Brain.tick`, avoiding injection into the implementation Lithium
overwrites. Native probes start through Brain's scheduler so Lithium's task cache
is populated normally; direct `Behavior.tryStart` was an invalid fixture.

Native reports: `build/combat-gametest.xml` and `build/compat-lithium/gametest.xml`.
Keep tests in development source sets; test fixtures must never ship in the jar.

Verified RC5 server results on 2026-10-09: 15 project GameTests plus one vanilla
control passed both without Lithium and with Lithium 0.26.2+mc26.3. The offline
audit passed 4316 assertions. Logs and preserved reports are in `../work/`:
`compatibility-native-final.log/.xml` and `compatibility-lithium-final.log/.xml`.

## Client rendering integrations

The supplied snapshot includes Sodium 0.9.2+mc26.3, ETF 7.2.4 and EMF 3.3.8.
Use the existing development client check with these supplied jars and Lithium:

```text
gradle --offline -I ../work/render-compat-check.init.gradle runClientcheck
```

This launcher uses `build/client-check`, not an installed Modrinth profile. Its
native model comparisons exercise the transformed Warden animation paths and
ordinary-Warden opt-out. They do not render an encounter, prove pixel colors,
or exercise custom entity models from resource packs. Keep the startup/model
result separate from the visual scenarios below; server tests cannot cover them.

On 2026-10-09 this combined client run passed all 2700 native model component
comparisons and exited normally. The log confirms all four supplied mod versions
were loaded: `../work/compatibility-render-client.log`. No resource pack with a
custom Warden model was supplied for this isolated run. The visual scenarios
below remain **not run**.

| Scenario | Steps | Expected result |
|---|---|---|
| Sodium | Render a boss in every health phase, flash and particle burst; change GUI scale and open the editor | Skin pixels retain their colors; eyes/glow pulse with the configured hue; preview remains visible |
| ETF | Repeat with default, player and custom atlas skins; reload resources and leave/reenter tracking range | Boss skin and glow remain correct; an ordinary Warden retains its normal texture |
| EMF | Repeat animation, roar, recovery and preview checks with default models, then the actual server-pack models | Animations and preview remain correct; document any resource-pack model/UV changes |
| Combined stack | Repeat with Sodium + ETF + EMF + Lithium, two clients and delayed connections | No startup/render exception, stale skin or duplicate effect; record exact versions and pack/options |

Do not infer support for every custom EMF model: player remapping and emissive
masks expect the native 128×128 Warden atlas layout. Test actual resource packs.

## Manual combat integration checklist

Better Combat and Combat Roll are absent from the supplied pack snapshot; no
actual integration with either has been tested. Do not infer compatibility from
the simulations. For each run, record actual mod versions, relevant options,
template settings, player count, ping/loss conditions and a diagnostic dump.
Use a disposable test server/world, matching client/server mod versions, and
start from the clean baseline before changing one integration at a time.

| Scenario | Steps | Expected result / evidence |
|---|---|---|
| Better Combat wind-up / combos | Attack with one-handed and two-handed weapons; repeat full combos; hit multiple bosses/mobs | Callback and observed HP match actual landed strikes; cancelled wind-up does not create an extra hit |
| Better Combat offhand potion | Drink while idle, during weapon wind-up and immediately after a combo; interrupt use | A full allowed drink grants one dose; interruption grants none; personal offhand item remains recoverable |
| Combat Roll drinking | Roll before, during and immediately after drinking | Capture actual start/stop/completion; an interrupted drink grants neither dose nor cooldown |
| Combat Roll impact | Roll out of reach during delayed melee; repeat with optional invulnerability both off and on | Out-of-range hit cancels; external damage rejection grants no extra knockback or success feedback |
| Phase / subphase boundaries | Land simultaneous player hits across a threshold; transition attack → roar → recovery → attack | One current generation; no stale delayed hit; native roar finishes; timers expire; recovery resumes once |
| Multiple players / retargeting | Melee and ranged fight with 2–3 players; kill/disconnect the current target | Holds and attack budgets are honored; a removed target is not retained; no pending hit transfers to the new target |
| Connection quality | Repeat above with actual high latency and packet-loss clients | Server grants remain exactly once; client delay does not become an extra server hit/dose; record measured conditions |
| Reconnect / death / full inventory | Fill inventory, enter/leave, drink then reconnect within cooldown, die with displaced offhand | No personal-item loss, repeated initial heal or usable extra dose; saved cooldown remains authoritative |
| Universal Graves | Die after receiving bottle with personal offhand displaced; recover grave contents, move/copy stored bottle | Original personal item exists exactly once; transferred bottle unusable by wrong owner; owned copies share one cooldown |

## Secondary shared hooks

- Lithium 0.26.2 shares Brain behavior methods with Recovery. The call guards and
  native scheduling tests address this overlap; an overlap alone is not a conflict.
- Sodium, EMF and ETF can alter rendering/model/texture paths. The client model
  checks and visual scenarios above cover different boundaries; a startup pass
  alone does not establish correct texture/glow output.
- Universal Graves captures inventory before this mod's AFTER_DEATH cleanup.
  Container contents are not globally scanned; stored marked bottles remain
  owner-bound and share the saved cooldown. The manual grave test is required.
- External player-health modifiers remain authoritative; the native armor math
  check verifies ownership cleanup, not every third-party attribute pipeline.

There is no demonstrated cross-mod combat conflict or universal compatibility
guarantee. Record passed, failed and not-run scenarios separately.
