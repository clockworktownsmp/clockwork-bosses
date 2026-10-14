# Clockwork Bosses: playtest checklist and fight simulation plan

Prepared October 13, 2026. Build-status notes describe the state when this checklist was prepared.

## One-hour checklist: everything added since 0.2.0

The changes since the 0.2.0 checkpoint fall into **PvP protection, Better Combat/potion compatibility, Workshop channels, and publishing refresh**.

**Build note:** automatic publishing refresh is prepared in **beta.5**, but has not been deployed or approved by the beta website yet. Mark that section **Not run** until both support it.

### 0–5 minutes: setup

- [ ] Use two Survival players, with server PvP enabled.
- [ ] Record the server/client Clockwork versions and Better Combat version. The inspected version was **3.2.2+26.3-fabric**.
- [ ] Make a disposable template, enable PvP protection, and configure its arena and reusable potion.

### 5–20 minutes: friendly-fire protection

- [ ] Before activation, confirm ordinary PvP works. Activate the boss: player harm should now be blocked.
- [ ] Test melee, crits and sweeps. Protected players should receive **no damage, knockback or Fire Aspect**; the boss should still take damage.
- [ ] Test arrows, flaming/tipped arrows, tridents and harmful splash/lingering potions. Player-caused harm should be blocked; healing and helpful buffs should work.
- [ ] Test an outsider attacking someone inside, then the reverse. Both should be blocked.
- [ ] Cross the boundary and immediately attack. Normal PvP should resume once **neither player is inside a protected encounter**.
- [ ] Confirm boss attacks and environmental damage still work.
- [ ] Disable protection and repeat a hit. Also check an older template without the setting: it should retain its previous behavior.

### 20–30 minutes: Better Combat damage

- [ ] Attack with fast, slow, dual-wield and longer-reach weapons.
- [ ] Test crits and sweeps against a scaled boss.
- [ ] Attack through a health-phase transition.
- [ ] Watch for doubled damage, missed valid hits or attacks reaching farther than the weapon permits.
- [ ] Repeat protected sweeps near another player: the boss takes damage, the player does not.

### 30–40 minutes: reusable potion and equipment

- [ ] Empty usable offhand: potion goes there.
- [ ] Occupied offhand: equipment stays; potion goes into an empty inventory slot.
- [ ] Equip a two-handed weapon with a shield stored in the offhand. The shield must remain untouched; drink the potion from the main hand.
- [ ] Fill every slot. No item should be overwritten or dropped; a useful message should explain the failure.
- [ ] Interrupt drinking, finish drinking, swap weapons and try alternating hands. Healing occurs only on completion, the bottle remains reusable, and cooldown is shared.
- [ ] Leave/reenter, then `/boss remove` the boss. Temporary potions disappear; personal equipment remains.
- [ ] If practical, die and retrieve your gravestone after the encounter ends. No usable encounter potion should remain.

### 40–50 minutes: Workshop channels and access

- [ ] Open `/boss workshop open`. For beta, the browser should use **beta.clockworkboss.com**.
- [ ] Check that displayed connected-mod version/channel matches the server.
- [ ] Save and publish a draft with an authorized account; confirm an unauthorized player cannot edit or publish.
- [ ] On the test setup, disable Workshop during a fight. Gameplay should continue normally.
- [ ] Verify there is no silent switch to the stable website when beta is unavailable.

### 50–55 minutes: beta.5 publishing refresh

- [ ] Save a new draft: it should **not** appear in game yet.
- [ ] Publish it: it should appear in template autocomplete without `/boss reload`.
- [ ] Publish changed settings while that template's boss is running. The running boss stays unchanged; a future spawn uses the new settings.
- [ ] Run `/boss reload`: existing bosses receive the changes.
- [ ] Confirm the website explains that reload is required for already spawned bosses.

### 55–60 minutes: cleanup

- [ ] End one encounter by death and another with `/boss remove`.
- [ ] Check for leftover protection, potions, extra health, effects, music or bosses.
- [ ] Reconnect and inspect client/server logs for errors.

## Results

Record **Pass / Fail / Not run**. For failures, include the time, template, exact mod versions, reproduction steps and relevant client/server logs.

| Area | Result | Notes |
| --- | --- | --- |
| Friendly-fire protection | | |
| Better Combat damage | | |
| Reusable potion and equipment | | |
| Workshop channels and access | | |
| Beta.5 publishing refresh | | |
| Encounter cleanup | | |

Separate follow-up runs should cover overlapping encounters, disconnected projectile owners, clients without Clockwork, no Better Combat, high latency, and legacy 0.2.0 Workshop connections.

## Proposed testing mod: repeatable fights and balance simulations

A separate testing mod could provide **repeatable regression tests and batches of fights for balance**.

Carpet already provides fake server players with attack, movement and item-use controls. The testing mod would add the fight runner and behavior around those. See the [Carpet action-control source](https://github.com/gnembon/fabric-carpet/blob/master/src/main/java/carpet/helpers/EntityPlayerActionPack.java).

For example, the testing mod could:

- Run the same template with 1, 2, 4 or 6 players.
- Give them different equipment and behaviors: melee, ranged, kiting, slow reactions and potion use.
- Repeat each combination many times.
- Report win rate, fight duration, deaths, damage, time spent in each phase, target distribution and server tick time.
- Remove its bosses, fake players and temporary items after each run.

That would help answer questions such as **“Is this six-player preset consistently killing everyone?”** or **“Does one player receive nearly all the attacks?”**

The behavior matters: bots with perfect reactions would give misleading balance results. Several skill levels should be simulated. Carpet's ordinary attacks would not automatically reproduce Better Combat combos, and fake players do not test real network connections or client rendering.

**This testing mod is a proposal; it has not been implemented.**
