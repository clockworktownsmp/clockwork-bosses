# Template loot - 0.1

Use **Entry editor** for ordinary item rewards or **Datapack table** for an
existing loaded server table. New templates use disabled loot with an empty
entry list. Older files missing loot keep their existing behavior.
## Add item rewards

Open `/boss template edit <template>` or `/boss template edit preset <preset>`.
Choose **Loot**, set **Source: Entry editor**, then open **Reward entries**.

1. **Add item** opens a gray creative-style window with native item icons.
2. Search by localized item name or `namespace:item` ID. Category cycles through
   native creative tabs; All items includes registered mod items too.
3. Click an icon. The entry page has minimum/maximum quantity and drop chance.
   **Pick** changes the item visually; its ID can also be entered manually.
4. Use Up/Down to order entries or Remove to delete one. Order is editorial:
   every entry rolls independently once, so several rewards can drop together.
5. Enable loot, choose Keep or Replace, then **Save**. **Reload saved** applies
   template changes to loaded bosses. Presets remain separate inactive copies.

Escape/Back cancels the item window, retaining unsaved text. Closing the editor
uses the existing unsaved-change confirmation. Adding an item does not give it
to the operator or change the actual inventory. The picker selects default
items, without custom components, potion variants, enchantments or item NBT.
Use datapack tables for advanced native loot behavior.

## Stored definition and limits

Put this fragment at the template root:

```json
"loot": {
  "enabled": true,
  "source": "entries",
  "loot_table": "",
  "vanilla_drops": "replace",
  "entries": {
    "reward_1": {
      "type": "item",
      "item": "minecraft:diamond",
      "min_count": 2,
      "max_count": 5,
      "chance": 0.25
    }
  }
}
```

- 0–32 named entries; keys use 1–48 lowercase letters/numbers/underscores/hyphens.
- Registered, non-air vanilla or mod item IDs; explicit lowercase namespace:path,
  at most 256 characters.
- Quantities: whole numbers 1–64, minimum no greater than maximum. Native uniform
  count includes both endpoints. Minecraft splits items into valid stack sizes.
- Chance: 0–100% in the GUI, 0–1 on disk. Zero never drops; 100% always drops.
- Enabled empty entry tables are valid: Keep retains vanilla; Replace gives no
  configured or vanilla table items. XP/equipment/independent mod drops remain native.

## Existing datapack tables and data preservation

**Source: Datapack table** retains the original loaded-table ID workflow.
Existing RC7 files without source retain that behavior; opening or saving an
unrelated setting never opts them into entry rewards or rewrites missing loot
fields. New templates/presets default to a disabled, empty entry editor.
Switching sources retains both the ID and entry definitions. It does not edit
the referenced datapack table. Entry tables are saved inside the template and
are not globally registered IDs; each template/preset owns its definitions.

Unchanged entries, extension fields and unrelated config values survive edits
and reordering. Structural patches contain validated keys only; the server
creates trusted defaults and accepts bounded scalar changes. Unknown objects
cannot be inserted by a client. Item existence, count pairs and chance are
checked before backup/publication. Invalid saves preserve exact file bytes and
revision; stale saves are rejected. Valid saves retain the rolling GUI backup.
Use matching client/server jars.

## Death behavior and extension boundary

Both sources use the existing native death eligibility and persisted exactly-once
claim. They respect doMobLoot, Fabric damage/death vetoes and native kill credit.
Environmental deaths may pay; administrative /kill, removal, reset and unload
never issue configured rewards. Keep rolls vanilla plus configured rewards;
Replace skips only the vanilla table. Partial failures never retry.

ClockworkBossLootEntries is the bounded definition/compiler boundary. It creates
native 26.3 pools/chance/count functions and uses the native entity loot context
and stack splitting, without mutating registries/datapacks or adding ticks.
ClockworkBossRewards owns committed payout; AI does not grant rewards. New entry
kinds can extend this boundary without replacing the lifecycle or editor save
transaction. The picker uses native creative categories, stacks and widgets;
all selection is local draft work until the existing authenticated Save.

## Datapack requirements

The table must already be loaded on the server, with an explicit lowercase
namespace/path of at most 256 characters. It must require only parameters
available on native entity death; block/tool-only contexts are rejected.
A registered empty table is valid.

Use Minecraft's `/reload` after datapack edits, then `/boss reload` for template
settings. Omitted `source` retains legacy `table` mode; explicit choices are
`table` and `entries`. Selecting the same table for vanilla and configured slots
with Keep gives two rolls. If a selected table becomes unavailable after loading,
the death-time check warns and retains vanilla table drops.

An invalid live reload keeps the boss's previous valid settings.
## Recorded checks and remaining work

The 2026-10-10 release recorded 4384 audit assertions, 1038 native GUI assertions
and 28/28 server tests with and without the supplied pack, plus a local
production-jar spawn/activate/save/remove/shutdown pass.
Evidence: `../work/release01-results.json` in the original workspace.

Real network/high-latency and full-pack client play remain untested.
The stale-table test simulates a removed registry key, not concurrent live
datapack reload. See [modpack testing](MODPACK_TESTING.md) and
[compatibility testing](COMPATIBILITY_TESTING.md) for limits/manual scenarios.
## Earlier build records

These notes preserve the original build and review evidence. Version, installation,
publication and pending-test statements describe when each note was written.
Use the current sections above for setup and behavior. A later pass does not
turn an earlier compile-only check into a playtest.

Local `../work/` and `../outputs/` paths refer to the original development
workspace. They are outside version control and may be absent in a clone.

<details>
<summary>Expand the dated notes and technical evidence</summary>

## RC8 local verification — 2026-10-10

| Check | Result |
|---|---|
| Native server suite | 28/28 passed |
| Supplied server-pack suite | 28/28 passed, including a registered mod item |
| Config/media audit | 4366 assertions passed |
| World-backed native GUI controls/layout | 1023 assertions passed at 320×240, 640×360 and 1280×720 GUI units |
| Client integrations | Sodium, Lithium, ETF and EMF in the isolated GUI run |
| Rendered picker screenshots | Captured and visually inspected |
| Test Client and remote test server deployment | Installed; both copies hash-verified; RC7 backed up |
| Live network / full-pack client play | Not performed |

New cases exercise guaranteed/zero chances, quantity ranges, native nonstackable
splitting, empty/disabled policies, exactly once, gamerules/admin cleanup, native
mod items, template/preset add/edit/reorder/remove, invalid/stale byte preservation,
legacy fields, retained table IDs/extensions and valid/invalid live reload.
The existing table-mode lifecycle/attribution regressions still pass.
GUI checks exercise search, selection, change, Escape/cancel, removal, percentage
conversion, widget bounds and unrelated pending-text preservation. Screenshots
also exercise actual native icon rendering/creative-tab construction in a world.

Evidence: `../work/rc8-*.log/.xml`, `../work/rc8-loot-editor-results.json`.
Test source sets/fixtures are excluded from the release jar. Server-pack isolation
and known nonfatal warnings retain the limits in MODPACK_TESTING.md.

## Historical RC7 implementation and checks

The following records the previous table-ID feature and its tests.

### Template loot — Beta RC 7

RC7 adds template-wide native item loot, with no new watcher or loot packets.
The universal jar is installed in Test Client and the remote test server.
Both copies match the built SHA-256; RC5 jars were backed up before replacement.
Deployment record: `../work/beta-rc7-deployment.json`. In-game loot/editor checks
remain pending.

## In-game editor

Open `/boss template edit <template>` or
`/boss template edit preset <preset>`, then choose **Loot** in the left index.
Set Enabled, Loot table and Vanilla drops. Save writes the document; Reload
applies it to loaded bosses. Invalid references leave the previous file intact.
Existing templates without loot settings stay disabled. Opening an old file
shows virtual disabled fields without changing its behavior.

```json
"loot": {
  "enabled": true,
  "loot_table": "your_datapack:boss/rewards",
  "vanilla_drops": "replace"
}
```

The referenced table must already be loaded on the server. Use Minecraft's
`/reload` after adding or editing a datapack, then `/boss reload` for template
settings. IDs require a lowercase namespace and path. Tables must require only
parameters available on every native entity death; block/tool-only contexts are
rejected. A registered empty table is valid and produces no items.

- `keep`: the native Warden table once, plus the configured table once. Choosing
  the same table in both slots intentionally gives two rolls; use `replace` for
  one roll of that table.
- `replace`: skip the native Warden loot-table invocation and roll the configured
  table once. XP, equipment and other mods' independent death drops retain their
  normal behavior.
- Environmental/no-player deaths are eligible. Native player-only conditions can
  restrict table contents; the service does not invent a killer.
- Removal, discard, unload, reset and administrative `/kill` never grant custom
  rewards. Native `doMobLoot` and Fabric damage/death vetoes are respected.
- A datapack can remove a table after a boss loads. The death-time check then logs
  a warning and retains vanilla table drops instead of silently losing them.

## Implementation and extension boundary

`ClockworkBossLoot` parses template settings and validates loaded registry keys.
Editor saves capture immutable table metadata on the server thread; the existing
bounded I/O transaction validates the merged document before backup/publication.
Spawn and reload resolve only the selected table. An invalid reload retains the
live boss's previous settings.

`BossLootMixin` wraps only the native table invocation inside `dropAllDeathLoot`.
Unmarked Wardens and all other entities call the original operation. The wrapper
uses the native loot eligibility branch, preserving `doMobLoot` and player credit.
It hands a single immutable death reward to the marked Warden; it does not pay
during attempted damage or run a global reward queue.

`ClockworkBossRewards` pays from Fabric `AFTER_DEATH`, using 26.3's public
`LivingEntity.dropFromLootTable` overload. Minecraft supplies the boss, origin,
damage source, attacker, direct attacker, eligible last player and luck.
The entity claims before rolling. A persisted boolean prevents repeated rewards
after duplicate callbacks or revival/loading of the same entity. The transient
handoff is consumed immediately, releasing attacker/source references; no pending
reward is saved. A partially failed roll is logged and never retried, preventing
duplicate items. Ordinary entities allocate no reward state.

Future reward types belong beside this service/config boundary. This release
does not add a loot-table editor, contribution tracking, economy integrations or
new reward networking.

## Verification — 2026-10-10

| Check | Result |
|---|---|
| Universal jar assembly and source/client compilation | Passed |
| Native suite without third-party pack | 24/24 passed: 23 project cases plus vanilla control |
| Same suite with supplied server pack | 24/24 passed |
| Offline config/media audit | 4332 assertions passed |
| Real client GUI interaction, real multiplayer network/latency, actual external combat mods | Not run for RC7 |
| RC7 production-mode launch without development fixtures | Not run |
| Deployment | Not performed |

Seven new native reward cases cover absent/disabled settings, ordinary Warden
scope, valid/empty/missing/malformed/incompatible tables, keep/replace, exactly
once, native NBT claim persistence, damage/death vetoes, admin kill/removal/discard,
native gamerules, editor template/preset saving, byte-for-byte invalid-save
preservation, valid/invalid reload and two independent native player kill contexts.
The stale-key case simulates an already-loaded table removed by a datapack reload;
it is not a live concurrent datapack-reload test.

Embedded players provide server-logic attribution checks, not real packet or
latency coverage. Server-pack isolation, excluded Discord integration and
disabled network services retain the scope described in `MODPACK_TESTING.md`.
Non-fatal pack messages remain; these results are not a full-pack guarantee.
Local evidence is outside version control in `../work/rc7-loot-*.log/.xml` and
`../work/rc7-loot-results.json`.

</details>
