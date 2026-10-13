# Clockwork Bosses playtest checklist

Use this for a full evening of testing or after changing the mod, a template,
or the modpack. Work through the sections in order and keep the template settings
with your results. Each checkbox describes a test and its expected result.
Mark results **Pass**, **Fail**, or **Not run**; a feature you did not exercise
does not count as passed. Repeat relevant sections after fixing a failure.

## Prepare the session

- Use a separate test world/server and copies of templates you can change freely.
  Several checks deliberately kill players, remove bosses or restart the server.
- Install matching core builds on the server and modded clients. The current
  core is `0.2.0-beta.3`. Keep one core jar in each mods folder.
- Use Survival for players taking part in the fight. An operator can switch to
  Creative for setup, but Creative/Spectator players are not normal attack targets.
- Bring a second player or account for targeting, late joins and shared-state
  checks. Mark those checks Not run if you only have one player.
- Have working event/voice clips ready. Mono OGG is needed to judge directional
  sound. A music track and a custom 128x128 Warden PNG are optional resources.
- Write down the core/client builds, modpack/resource packs, GUI scale, player
  count and approximate ping. Reuse the same setup before changing one variable.

Create the first test template in-game:

```text
/boss create preset 2_players playtest
/boss setspawn playtest
/boss select activation playtest true
```

Click the two opposite arena corners with the supplied arrow. Make the vertical
bounds tall enough for jumping and the scaled boss. Then open:

```text
/boss template edit playtest
```

Save your changes. **Reload saved** or `/boss reload` applies saved settings to
loaded bosses. Save alone does not apply them. Spawn the test encounter with:

```text
/boss spawn playtest testboss
```

For a sound example, make a separate copy with
`/boss create preset boss-sounds-example playtest_audio`. Give that template
its own spawn and areas before spawning it. The example copies its event and
voice clips; no music track is bundled. Use autocomplete for other preset names.

## Tonight session

This is a full session rather than a quick smoke test. Use fresh encounters for
tests that end or remove the boss. Work through the editor, activation, phases,
combat, actions, player support, visuals, audio and loot sections, then exercise
permissions, optional clients and restart/cleanup if your setup supports them.
The lightning addon and connected workshop sections are separate optional runs.

When a result is wrong, note the section and test name immediately. Capture the
failure before changing the settings; changing several fields at once makes it
hard to tell which change helped.

## Templates and editor

- [ ] **Create from a preset.** Create `playtest` from `2_players` and open
  `/boss template list`. **Pass:** the new template is listed; unused presets
  stay in the preset picker rather than appearing as active templates.
- [ ] **Create from scratch.** Run `/boss create new playtest_new` and open it.
  **Pass:** all expected sections and defaults are available, including dormant
  Darkness, music, voice lines, player support, loot and face controls.
- [ ] **Keep presets independent.** Edit a copied template and reopen its source
  preset. **Pass:** the preset is unchanged. Editing a preset affects future
  copies, not an existing copied template.
- [ ] **Save versus reload.** Change something obvious, such as scale, and Save.
  Reopen the editor, then use Reload saved. **Pass:** Save persists the field;
  reload applies it to the loaded test boss.
- [ ] **Discard unsaved changes.** Change a value, close without saving, and
  reopen. **Pass:** the saved value returns.
- [ ] **Reject invalid inputs.** Try negative health, scale above 4, descending
  quantity bounds in reverse, and a filename with spaces. **Pass:** the editor
  reports the invalid setting and does not overwrite the valid template.
- [ ] **Detect a stale editor.** Two operators open the same template. One
  saves; the other attempts a different save from the old view. **Pass:** the
  second save reports that the document changed; it does not erase the first.
- [ ] **Check layout.** Try your smallest and largest usable GUI scales, resize
  the window, scroll long pages and reopen collapsed sections. **Pass:** labels,
  values, Save/Reload and navigation remain readable and reachable.

## Selection and activation

- [ ] **Selection arrow.** Select an area, switch to redstone mode, then cancel
  or finish. **Pass:** area mode is yellow, redstone mode is red, and the active
  arrow has a glint. After completion, ordinary clicks stop producing selection
  particles, block-punch sounds or unwanted mining.
- [ ] **Dormant boss.** Spawn while already standing inside the activation box.
  **Pass:** the boss stays dormant; its head does not protrude through the floor,
  no boss bar/player buffs appear, and the activation sound does not play yet.
  Configured dormant Darkness can still apply.
- [ ] **Box entry.** Leave completely, then walk back in. **Pass:** the boss
  emerges and activates once, with the configured activation/spawn sound.
- [ ] **Radius entry.** Change activation to player-enter-radius. Try outside,
  inside and above/below the sphere. **Pass:** entry activates it using 3D
  distance; being inside before spawn does not count as a new entry.
- [ ] **Redstone edge.** Configure a block using the selector. Start with it
  powered, turn it off, then on. **Pass:** the off-to-on change activates once;
  a continuously powered block does not repeatedly activate it.
- [ ] **Manual activation.** Spawn another dormant test boss and use
  `/boss activate testboss`. **Pass:** normal emergence, audio and encounter
  support begin once.
- [ ] **Independent arenas.** Set different activation and arena/boss-bar areas.
  **Pass:** activation uses its trigger; boss-bar visibility and player support
  use the arena. The optional `true` selection copies an area when requested.

## Health phases and Darkness

- [ ] **Every threshold.** With the standard 75/50/25% cutoffs, damage the boss
  through each boundary. For 1,000 max HP these are 750, 500 and 250 HP.
  **Pass:** the labels and behavior progress once through Annoyed, Agitated,
  Enraged and Fury. Equality advances to the next phase.
- [ ] **Glow and transitions.** Observe every phase in a dark area. **Pass:** the
  configured colors and pulse appear; the usual palette is cyan, yellow, orange
  and red. Transition flash and the configured small-particle burst occur once
  and use the entering phase's settings.
- [ ] **Skip a threshold.** Deliver enough damage to cross more than one cutoff
  in one hit. **Pass:** the resulting health selects the right final phase; the
  boss does not stick in the first crossed phase or spam transitions afterward.
- [ ] **Inheritance.** Set a base scale/attack value, leave one phase at Inherit,
  and give another an explicit override. **Pass:** inherited and explicit values
  behave differently as configured; editing one phase does not alter another.
- [ ] **Dormant versus emerged Darkness.** Give dormant Darkness a different
  interval/duration from phase 1. **Pass:** the underground aura follows dormant
  settings; emergence/active phase 1 uses phase 1's Darkness.
- [ ] **Pulsed and continuous Darkness.** Try about 20–40 duration ticks with
  200 interval ticks, then a final phase whose duration overlaps its interval.
  **Pass:** the first setup clears between pulses; the last stays continuous.
  Keep both amplifiers at 0; higher amplifiers are not a darkness-brightness dial.
- [ ] **Darkness boundaries and disabled state.** Walk between the configured
  inner/edge ranges, then disable Darkness and reload. **Pass:** timing follows
  the selected range settings. Disabling stops new applications; an existing
  effect can remain until its already-applied duration expires.
- [ ] **Change phase count.** Add, remove and reorder phases, correct their
  descending cutoffs, Save and reload. **Pass:** the sidebar follows the actual
  list and combat follows the saved phase order. Invalid cutoff order is reported.

## Attacks and targeting

- [ ] **Melee only.** Disable Sonic Boom and stand near the boss. **Pass:** it
  approaches eligible players and uses melee without needing to be hit first.
- [ ] **Sonic only at close range.** Disable melee and stand next to it, then
  farther away. **Pass:** Sonic Boom can fire in both positions when the target
  satisfies its configured range/sight rules; disabled melee does not block it.
- [ ] **Mixed attacks.** Enable both and move between melee and ranged distance.
  **Pass:** enabled native attacks occur according to range and cooldowns.
- [ ] **Wind-up and recovery.** Make melee and Sonic charge/recovery times
  noticeably longer, then shorter. **Pass:** animation and hit timing follow the
  settings, and attacks do not ignore their cooldown/recovery periods.
- [ ] **Escape a pending hit.** Leave melee range during a long melee wind-up.
  **Pass:** the timer continues, but being out of reach or behind a wall at impact
  avoids damage. Returning afterward cannot revive that hit. A new target never
  receives the old hit. Repeat by leaving the arena and disconnecting.
- [ ] **Wind-up while chasing.** Use a 40-tick melee wind-up and move while the
  Warden follows. Briefly leave reach, then return before impact. **Pass:** the
  original two-second timer continues and hits once if reach/sight are valid at
  impact; neither actor's movement restarts it. Repeat at scale 1 and scale 2,
  with and without the client mod, then try a higher-latency player.
- [ ] **Glow stays on glow layers.** Watch the boss while players move nearby,
  with EMF/ETF enabled if installed. **Pass:** eyes, tendrils, heart and spots may
  pulse in the phase color; the whole base skin never becomes a solid phase-color
  flash. Check player/custom skins and each phase, then compare without EMF/ETF.
- [ ] **Guaranteed target changes.** With two eligible players nearby, use
  switch chance 100%, minimum target time 0, and min/max attacks both 1.
  **Pass:** completed melee and fired Sonic attacks both trigger the configured
  rotation; the boss does not repeatedly choose its current player when another
  valid candidate exists.
- [ ] **Forced maximum and minimum hold.** Use chance 0%, min attacks 2, max
  attacks 4 and a visible hold such as 60 ticks. **Pass:** rotation waits for the
  minimum gates, then happens at the maximum once the hold allows it. The time
  gate can postpone switching beyond four attacks; that alone is not a failure.
- [ ] **Invalid and distant targets.** Kill/disconnect the current player, walk
  outside the arena, and test a second player far across it. **Pass:** invalid
  players are released promptly and valid distant players are considered within
  the configured follow/Sonic ranges. Test automatic follow range 0 as well.
- [ ] **Warnings.** Watch target-change titles during rapid changes and across
  two bosses. **Pass:** warnings correspond to actual assignments and respect
  their cooldown rather than flooding the screen.

Blocked completed swings and fired-but-rejected beams can count toward attack
budgets. Cancelled wind-ups do not. Voice-line chance is checked on successful
hits, which is a different condition from an attack completing.

## Subphases and native actions

- [ ] **Sequential attacks.** Create clearly different slow/heavy and fast/light
  children with small attack budgets. **Pass:** they run in saved order, inherit
  their parent, and loop without adding extra health thresholds.
- [ ] **Random attacks.** Add at least three children and select random order.
  **Pass:** each runs once per shuffled round; with multiple children, the round
  boundary does not immediately repeat the same child.
- [ ] **Recovery.** Add a recovery child of about 60 ticks. **Pass:** attacks and
  movement pause for roughly three seconds at normal server speed, then resume.
- [ ] **Roar interludes.** Add several roars and reorder them. **Pass:** native
  roar animation and chosen sound occur once between children, attacks pause,
  and the next child waits for the roar to finish. Sequential mode cycles the
  shorter roar list independently; random mode chooses a roar independently.
- [ ] **Burrow toward another player.** Place two players apart and make the
  next target different from the current one before burrow finishes. **Pass:**
  the boss digs, waits underground, then emerges safely near the eligible target
  and proceeds to the following action. It does not fabricate an immediate hit.
- [ ] **Interrupt an action.** In separate encounters, reload, cross a health
  cutoff, kill or remove the boss during charge, roar, recovery and burrow.
  **Pass:** old delayed hits/warnings stop, no old action resumes later, and a
  surviving boss regains its proper position/visibility/movement state.
- [ ] **Reorder and save.** Drag children/roars, try keyboard reorder controls,
  cancel a drag, then Save/reopen. **Pass:** saved order matches the editor;
  cancelling a drag preserves the old order.

## Player health and arena effects

- [ ] **First entry and late arrival.** Start while one player is outside, then
  have them enter injured. **Pass:** enabled minimum max health and first-entry
  healing apply to both initial participants and late arrivals.
- [ ] **Leave and reenter injured.** Take damage, leave, then return to the same
  encounter. **Pass:** leaving releases encounter support; reentry restores
  eligibility without another free first-entry full heal.
- [ ] **Armor health.** Repeat wearing armor that already grants health at or
  above the configured floor. **Pass:** the encounter establishes a minimum,
  does not multiply the armor bonus, and removes only its own contribution.
  Leaving clamps HP to the remaining maximum without killing the player.
- [ ] **Beacon effects.** Enable speed, jump boost, regeneration, strength and
  resistance one at a time. **Pass:** they apply only after activation and while
  inside the arena, then stop on exit. No Haste is added by these settings.
- [ ] **Existing effects.** Enter with a stronger potion/beacon effect already
  active, then leave. **Pass:** Clockwork Bosses does not strip an unrelated
  stronger effect when removing its area contribution.
- [ ] **Overlapping encounters.** Activate two bosses with overlapping arenas,
  remove one, then leave the other. **Pass:** support required by the remaining
  encounter survives; the final exit releases encounter-owned support.

## Reusable fight potion and graves

- [ ] **Personal offhand.** Enter carrying a shield or valuable named offhand
  item. **Pass:** the fight potion is supplied, and the displaced item is
  recoverable exactly once when support ends.
- [ ] **One completed drink.** Take damage and reduce hunger, then finish one
  drink. **Pass:** the configured healing/food/saturation is granted once. The
  default healing is 20 HP and the default cooldown is 15 seconds.
- [ ] **Cooldown and interruption.** Attempt another drink immediately; also
  interrupt a fresh drink before it finishes. **Pass:** cooldown blocks another
  dose; an interrupted use grants no dose or fresh successful-use cooldown.
- [ ] **Reconnect during cooldown.** Drink, disconnect and return before the
  cooldown expires. **Pass:** reconnect does not grant a fresh dose or reset the
  cooldown/first-entry heal for the same encounter.
- [ ] **Moved and shared bottles.** Move the potion to another slot, try to
  obtain multiple copies, and give a copy to another player. **Pass:** owned
  copies share one cooldown; another player cannot use your owner-bound bottle.
- [ ] **Remove while holding it.** Use `/boss remove testboss` with the potion
  in the offhand, then repeat with it in inventory. **Pass:** after the final
  eligible encounter ends, temporary bottles are removed and the original
  offhand item remains exactly once. Reopen inventory to check the real contents.
- [ ] **Full inventory.** Repeat exit/removal with all slots occupied.
  **Pass:** your original personal item is preserved through the supported
  inventory/drop/recovery path rather than silently disappearing or duplicating.
- [ ] **Death and grave recovery.** With the actual grave mod, die with a
  personal offhand displaced, end the encounter, then recover the grave.
  **Pass:** the grave has your personal items, not a usable temporary fight
  potion; recovery does not duplicate the original offhand.
- [ ] **KeepInventory and a totem.** If used by your server, repeat with
  keepInventory, and separately with a totem preventing death. **Pass:** a real
  death cleans up support; a cancelled death does not prematurely discard the
  active encounter state or personal items.

## Skins and face controls

- [ ] **Default and player skin.** Switch between vanilla and a known player
  skin such as ClockworkSpirit. **Pass:** player colors remain recognizable,
  base pixels have no Warden stripe overlay, and opening ribs retain their
  intended separate Warden texture.
- [ ] **Two clients and late tracking.** Have the other player join after spawn,
  move out of tracking range and return, then reconnect. **Pass:** both modded
  clients show the same skin/glow rather than one retaining a vanilla fallback.
- [ ] **Eye and eyebrow controls.** Move each independently, narrow/widen eye
  spacing and change X/Y eye size. Save/reload. **Pass:** the full model preview
  is visible, controls affect the intended feature, and the boss matches it.
- [ ] **Pupil controls.** Try independent X/Y size, Both/Left/Right movement,
  Auto offsets, Reset and disabled pupils. **Pass:** pupils move with their
  eyes, stay clipped inside them and keep the darker glow hue. Explicit values
  survive Save/reopen. Auto one-pixel pupils can be hidden on one-pixel eyes.
- [ ] **Custom atlas and fallback.** If you have a valid server PNG, load it as
  custom. Also try an unavailable filename. **Pass:** a valid 128x128 Warden
  atlas is used as supplied; failure is reported/falls back without crashing.
- [ ] **Ordinary Warden and resource reload.** Compare a normal Warden, reload
  resources and repeat with your Sodium/ETF/EMF/resource-pack combination.
  **Pass:** normal Wardens retain normal visuals; marked bosses keep their
  intended appearance. Custom model/UV packs need their own alignment check.

## Event audio and voice lines

- [ ] **Activation, all transitions and death.** Choose distinct known files
  for activation, each entering phase and death. **Pass:** each event plays its
  own clip once; command spawn and initial phase assignment remain silent.
- [ ] **Inherited versus disabled transition.** Give the base phase-change
  sound a file, leave one phase at Inherit and set another filename empty.
  **Pass:** the first uses the shared clip; the empty override mutes that phase.
  Custom roar blank filenames keep the native roar.
- [ ] **Direction and movement.** Listen to a mono clip while walking around
  the boss and turning away. **Pass:** direction and distance are perceptible.
  A long clip remains at its starting position rather than following the boss.
- [ ] **Automatic large range.** Use an arena radius above 128, sound range 0,
  and listen well inside it at about 150 blocks. Repeat with a large box.
  **Pass:** updated clients can hear beyond the old 128-block cap when inside
  the resolved playback range; volume falls toward the boundary.
- [ ] **Manual range.** Set range 32, then a larger positive distance. Walk
  across those distances and reload between changes. **Pass:** manual range
  stays manual; it does not change into arena range. Music remains independent.
- [ ] **Roar and voice range.** Give a custom roar and voice lines range 0, then
  a positive override. **Pass:** both use the selected spatial rules; phase/roar
  Inherit keeps its parent setting.
- [ ] **Voice chance and overlap.** Use chance 100% with a long voice clip and
  frequent successful attacks; then set chance 0%. **Pass:** at most one voice
  for the same boss is pending/playing; zero prevents new voices. Event sounds
  can still play independently.
- [ ] **First transfer and cached playback.** Join with a second client that
  has not heard these files, then replay them. **Pass:** files eventually play
  without disconnects; cached playback works. Slow first transfers can delay
  playback, and a waiting stale event can expire instead of playing very late.
- [ ] **Vanilla sound volume.** Set the boss's mob volume to 0, then 0.25.
  **Pass:** its regular Warden sounds/heartbeat follow the setting; custom clips
  use their own volume and an ordinary Warden is unaffected.

Automatic event audio is a sphere around the captured sound origin, not an
arena-membership filter. Radius uses radius, not diameter; a box uses its full
inclusive 3D diagonal. A boss underground/outside a box can put a boundary
listener outside that sphere. No usable arena falls back to 32; the cap is 512.

## Encounter music

- [ ] **Start, loop and fade.** Configure a short track with loop enabled and
  two-second fade. **Pass:** activation/active-arena entry starts it, it loops,
  and boss death ends it with the fade.
- [ ] **Exit, reconnect and removal.** Leave the arena, disconnect and remove
  the boss in separate runs. **Pass:** no orphan music continues after its
  request ends. Reentry starts an eligible track without layered copies.
- [ ] **Channels.** Test Music, Jukebox/Note Blocks and Master with their sliders
  changed separately. **Pass:** the selected slider and Master control it.
  Test `/stopsound` for the chosen category; reentry or reload restores eligible
  playback rather than immediately defeating the stop command.
- [ ] **Two arenas and resource reload.** Enter overlapping musical encounters,
  then reload resources. **Pass:** only one encounter track plays; the most
  recently entered ready encounter has priority. Eligible music can restart
  from its beginning after resource reload without overlapping old channels.

## Rewards

- [ ] **Visual item picker.** Search for a vanilla and an installed mod item,
  add/remove/reorder entries, then Save/reopen. **Pass:** item, quantity and
  chance survive exactly as entered; the picker remains readable at your GUI scales.
- [ ] **Deterministic reward.** Set one diamond entry to min/max 1 and chance
  100%, kill the boss and count the resulting reward items. **Pass:** the
  configured reward rolls once, with exactly that quantity.
- [ ] **Keep versus replace.** Repeat with vanilla drops kept, then replaced.
  **Pass:** native drops follow that mode while configured rewards still roll.
- [ ] **Removal is not a kill.** Remove or unload a reward-enabled boss instead
  of killing it, and restart after a completed kill. **Pass:** removal/unload
  does not award death loot; a completed death does not roll rewards again.
- [ ] **Disabled and invalid rewards.** Disable loot, then try an invalid item
  ID and min greater than max in a test draft. **Pass:** disabled behavior keeps
  the normal native rules; invalid saves are rejected without losing old entries.

## Permissions

Use an operator and a separate non-operator. The provider-specific checks require
a LuckPerms or other provider build that implements Fabric Permission API v1.
See [permission nodes and commands](PERMISSIONS.md) for the grants.

- [ ] **Ordinary participant.** Join without admin grants. **Pass:** you can
  fight, get support and earn rewards, but cannot create/edit/reload/delete bosses.
- [ ] **Spawn-only grant.** Grant only `clockwork_bosses.command.spawn`.
  **Pass:** spawning works; editing, reload, removal and preset access remain denied.
- [ ] **Separate editors and removal scopes.** Try template-only versus preset-only
  grants, and single removal versus remove-all. **Pass:** each grants only its
  stated operation. Combined activation/arena selection requires both permissions.
- [ ] **Revoke while open.** Revoke edit/reload/selection permission with its
  screen/tool open, then attempt the operation. **Pass:** new unauthorized
  actions are denied and existing settings remain intact.
- [ ] **Explicit denial and fallback.** Deny an operation to an operator; also
  check your intended setup without a provider answering. **Pass:** explicit
  denial overrides fallback; native OP access works when the provider abstains.

## Optional clients and combat mods

- [ ] **Client without the core.** Have one client without Clockwork Bosses
  track the same normal Warden and boss as a matching modded client. **Pass:**
  both stay connected and see native movement, particles, bar, scale and gameplay.
  The client without the core has vanilla visuals and no custom audio/editor.
  Other mandatory mods can still be required by the pack.
- [ ] **Late join and dimension change.** Join an active fight, reconnect,
  leave tracking range and change dimensions. **Pass:** no metadata/packet crash,
  stale bar, duplicate support item or old encounter audio follows you.
- [ ] **Combat integrations in use.** With your actual combat mods, repeat
  weapon combos, roll out of a pending melee hit, and drink before/during/after
  attacks or a roll. **Pass:** cancelled use grants no potion dose; cancelled
  or rejected hits do not create extra damage/knockback. Record mod options.
- [ ] **Actual poor connection.** If available, repeat with a genuinely slow
  client. **Pass:** delayed visual/audio updates do not become duplicate damage,
  potion doses or rewards. Record measured ping; a normal client does not prove
  high-latency behavior.

## Lightning example addon

Install the separately built `clockwork_lightning_example-0.1.1.jar` on the test
server with the core version it requires. The packaged example currently pins
`0.2.0-beta.3`; rebuild it after changing the core build. A matching core client
can use the editor without a client copy of this addon.

- [ ] **Discover and configure.** Add a subphase of **Example lightning**, set
  warning to 40 ticks, damage to 8 and target mode to current. **Pass:** its
  settings appear, validate, save and reopen through the ordinary editor.
- [ ] **Execute and repeat.** Activate it with a Survival participant.
  **Pass:** sparks warn for about two seconds, one visual bolt appears, native
  damage is attempted once, no fire is placed, and the next action starts.
  Armor/other damage rules can reduce or reject the configured 8 HP.
- [ ] **Random and lost targets.** Repeat in random mode with two players; have
  the warned player leave or disconnect before impact. **Pass:** only eligible
  arena players are chosen; an invalid target is not hit by a stale execution.
- [ ] **Interrupt and cleanup.** Remove, kill, reload or change phase during
  the warning and just after the bolt. **Pass:** no delayed strike survives the
  old execution and no permanent lightning entity or paused boss remains.
- [ ] **Missing addon.** After removing its test boss, stop the server, remove
  the addon and restart with its saved template still present. **Pass:** settings
  are preserved, unsupported action use reports the missing registration, and
  the core does not silently replace it with ordinary combat.

## Connected workshop

If the server runs the web companion, use `/bossworkshop open` with the authorized
owner account and the shared website. This is separate from the core GUI.

- [ ] **Round trip.** Edit eye/pupil positions, a phase override and sound range
  0 in the website. Save draft, Publish, then open the same template in-game.
  **Pass:** saved values match; reload applies them to the boss. Saving a browser
  draft alone does not apply changes to an active encounter.
- [ ] **Preset media.** Create a sound example draft, Save draft, Publish and
  activate it. **Pass:** copied clips are available to the boss as well as the
  authenticated preview; publishing does not leave missing event/voice files.
- [ ] **Manual versus inherited range.** Save a positive manual range and a
  phase Inherit override, then reopen in both editors. **Pass:** manual stays
  manual and inherited stays inherited rather than becoming a creation default.

## Persistence and repeated cleanup

- [ ] **Restart an active fight.** Stop normally and restart with a live boss.
  **Pass:** one boss returns with the correct identity/configuration, not a
  duplicate; bars/media and support recover without duplicating rewards/items.
  Loading resets action sequencing rather than restoring a half-finished hit.
- [ ] **Save during burrow.** Repeat a normal restart while underground.
  **Pass:** saved recovery returns the boss to a usable surface/flag state;
  it is not permanently invisible, invulnerable, underground or unable to move.
- [ ] **Chunk unload and reload.** Leave sufficiently far away to unload the
  test boss's chunks, then return. **Pass:** no duplicate saved boss, orphan
  temporary entity, stale hit or repeated death reward appears.
- [ ] **Remove one of two bosses.** Spawn two test encounters and remove one.
  **Pass:** the other continues operating. Remove the last; bars, support,
  temporary bottles and audio tied to it end.
- [ ] **Repeat encounters.** Run several spawn/activate/end cycles, alternate
  death and command removal, and reopen the editor repeatedly. **Pass:** no
  accumulation of visible bosses, bars, potions, effects, audio or consistent
  worsening of responsiveness. This can expose a problem; it is not a heap benchmark.
- [ ] **Delete the test template.** Try `/boss template delete playtest` while
  its boss is loaded, then after removing its instances. **Pass:** deletion is
  refused while in use, succeeds afterward and removes it from the template
  list. Presets are unaffected; the deleted folder is retained as an archive.

Only use `/boss remove all` in the isolated test setup where every loaded boss
belongs to this session. Removing a loaded instance is different from deleting
its reusable template; remove any saved test instances before archiving it.

## Record failures

For a combat/targeting/action/potion issue, optionally start the bounded capture
before reproducing, then stop it and export:

```text
/boss debug on
```

Reproduce the issue once, then run:

```text
/boss debug off
/boss debug dump
```

Keep the reported dump and the client/server log around that time. Turn capture
off after the reproduction. For visual/audio problems, a screenshot or short
video is useful alongside the exact skin/phase/sound settings.

Copy this result form for anything that fails:

```text
Test section and name:
Date and approximate time:
Core/server build and client build:
Modpack and resource pack versions:
Template, phase and action:
Relevant settings:
Players and approximate ping:
Steps to reproduce:
Expected result:
Actual result:
Repeatable? How many times?
Screenshot/video, debug dump and log:
```
