# Template tooling foundation

## What this provides

This is experimental tooling, added after release 0.1 and after the
`0.1-client-optional.1` test jar was packaged. It has offline test evidence and
is now included in the installed `0.2.beta.1` test build. The running server
command has not had a new runtime check for this packaging pass.
An experimental browser editor now uses the exported schema. It is a separate
website, with no connection to a running Minecraft server; it is not part of the
mod jar. Its first version edits template JSON and downloads files locally.

A **template** describes one encounter. A **schema** describes what its settings
mean: their types, creation defaults, limits, options, labels and help. A website
can read the schema to build controls without keeping a second list of settings.
It does not need a running Minecraft server to edit those JSON files.

The schema uses [JSON Schema Draft 2020-12](https://json-schema.org/draft/2020-12/json-schema-core).
Ordinary validators understand its types, ranges, choices and structure. The
`x-clockwork` annotations add information for an editor: inheritance paths,
override scopes, ordered lists, percentages and registered action definitions.
These annotations are data, not Java code or instructions to execute.

## Export the definitions

From the project, with its configured Java/Gradle environment:

```text
gradlew exportTemplateSchema
```

Use `./gradlew` on Linux/macOS. The result is `build/template-schema.json`.
Gradle compiles/loads the mod's build classes to generate it, but launches no
Minecraft client or server. This standalone export contains the core actions.
The file is a generated build artifact; regenerate it when definitions change.

In a server running a jar built from this source, an operator can use:

```text
/boss template schema
```

This writes `config/clockwork-bosses/template-schema.json`, including the
installed addons' registered actions and settings. The destination is fixed and
uses the existing bounded, atomic config writer. It exports definitions only;
it does not read private templates, include media bytes or expose machine paths.
There is no new watcher or recurring export task.

## First browser editor

The Template Workshop uses the exported core schema for labels, help, ranges,
choices, conditional controls and registered action settings. Its expandable
contents index opens setting forms, health phases, subphases, roar interludes
and reward entries. New files can start from the defaults or bundled presets.
An operator can load a server-exported schema to expose installed addon fields.

Import reads JSON or JSON with comments. The browser keeps an ordered document
tree and original number tokens, rather than rebuilding data from visible
fields. Missing imported settings stay missing; hidden and unknown addon
settings stay stored. Downloads are plain JSON with format revision 1.

Edits live in the current tab until downloaded. This first website does not
store shared templates, edit server files, upload audio/skins, render a 3D skin
preview or provide the game's visual item picker. Server resource checks remain
authoritative. The core schema bundled with the website works without a server;
the live schema command still requires a jar built from this later source.

## Where the information comes from

| Source | Responsibility | Reused by |
| --- | --- | --- |
| `default-clockwork-boss.json` | Creation defaults and section/field explanations | Generated comments, GUI help and schema |
| `ClockworkBossEditorSchema` | Core field types, choices, bounds, labels and form prototypes | Client GUI, server edit validation and schema |
| `ClockworkBossTemplateText` | Text lengths, username/resource patterns and media basenames | GUI validation, media filename guards and schema |
| `ClockworkBossTemplateVisibility` | Which controls apply to the selected action or geometry | GUI visibility and schema `visible_when` |
| `BossSetting` and `BossActionRegistry` | Addon setting declarations and current action catalog | Action compiler, GUI, file comments and schema |
| `ClockworkBossTemplateFormat` | Version, bounded JSON parsing and preservation | Template documents and independent tooling |
| `ClockworkBossTemplateDocument` | Human-readable comment generation | Server template files |
| Gameplay compilers and Minecraft registries | Effective behavior, cross-field/domain checks and resource availability | Final server validation |

The schema exporter adapts those sources; it does not maintain another list of
core limits or addon controls. A new addon setting appears through its normal
`BossSetting` declaration. Core phase/subphase/roar/reward definitions come from
the same prototypes the GUI uses to add entries.

The portable format class uses only the JDK, Gson and the existing decimal helper.
An isolated-classloader test loads it with Minecraft and Fabric absent. It can
read commented templates and write plain JSON. The comment writer and full schema
generator still use the mod's build classpath; they are not a separate web package.

`x-clockwork.numeric_input` exports the same decimal input limits as the real
parser: 128 text characters, 64 significant digits and scale -64 to 64. Apply
them to declared numeric edits; leave unknown imported number tokens alone.
Ordinary JSON Schema checks numeric values, so these lexical input limits are
additional editor rules rather than standard validation keywords.

## Format and schema revisions

New and saved documents contain:

```json
"format_version": 1
```

This number describes the template format, not the mod's release number or API
revision. An omitted version means an older revision-1 file. Reading it does not
change the file; saving adds the version. Unsupported explicit revisions,
`null`, strings and fractional versions are rejected. Do not change this field
to force a future template to load. The GUI hides this automatically managed field.

The schema separately declares `x-clockwork.schema_version`. Future tooling must
check both revisions before editing. Its current revision is **2**; the template
format remains **1**. No stable public schema/API promise is made
yet; a future format change needs an explicit conversion policy and tests.
Existing installed presets are preserved; bundled presets now declare revision 1.

## Defaults and inheritance

Schema `default` values describe creation/form defaults. They are
[annotations](https://json-schema.org/understanding-json-schema/reference/annotations),
not instructions to fill every absent field during an import or export. Older
documents can intentionally omit settings; backfilling them can change behavior.

Each field also has `x-clockwork.omitted`, separately from its creation default.
For example, `loot.source.default` is `entries`, while its omitted `value` is
`table`. Absent health/potion support sections stay disabled even though fresh
templates enable them. The omission annotation uses `value`, `inherit` (with
`from`), `disabled`, `empty`, `declared_action_defaults`, or `server_default`.
`server_default` deliberately promises no portable literal: keep the field absent
instead of inventing one from `default`. Addon setting omissions use their own
declared defaults. These annotations never alter imported template data.

The schema identifies base, phase and subphase fields, plus which base settings
can be overridden at each level. `inherits_from` gives the corresponding path.
Named phase/subphase/roar/reward collections use reusable `$defs`, bounded counts
and name patterns rather than assuming four phases.

- Null/missing phase combat overrides use the corresponding base setting.
- Attack-subphase overrides use their parent phase, then the base setting.
  Health thresholds, scale, visuals and darkness remain owned by the main phase.
- Dormant-darkness null fields inherit the first phase's compiled darkness.
  Phase darkness uses the legacy base-darkness fallback when unset; it does not
  inherit the dormant section.
- Registered `action_settings` may be omitted. If present, it must be an object.
  Missing/null fields inside it use that action's declared defaults, not a parent
  action object. The GUI labels this reset choice **Action default**.
- An empty sound filename is a value, not null inheritance. Event sounds become
  silent; an empty roar filename keeps the native roar.
- Null `subphase_order` selects sequential order; null `roar_between_subphases`
  enables interludes when roars exist. Neither inherits a parent's value.
- An absent or null `loot` section disables template rewards and keeps vanilla drops.

## Conditional controls

`x-clockwork.visible_when` describes a selector path, its missing/null fallback,
an `in` or `not_in` membership test, and `values`. Resolve `<phase>` and
`<subphase>` against the current entry. The game editor evaluates the same rule
against pending dropdown input; a website can evaluate the exported descriptor.

- Legacy `attack` exposes root `attacks_before_next`; legacy `recovery` exposes
  root `duration_ticks`. Registered actions use their own `action_settings`.
- Melee-only actions hide Sonic Boom overrides; Sonic-only actions hide melee.
  Recovery, roar and burrow hide combat overrides they do not apply.
- Activation/arena geometry and reward source also expose their relevant controls.

Visibility changes presentation only. Keep hidden values in the original tree
when importing or saving; switching actions is a separate explicit operation.
The registered core roar's `action_settings.file` uses the same lowercase OGG
basename pattern and 68-character limit as other sounds, including blank/native
roar and null/action-default cases. Other addons' arbitrary text fields retain
their own declarations; a field named `file` is not automatically audio.

## Spatial sound distance, included in 0.2.beta.3

Event, voice-line and custom-roar range fields now allow 0–512. Zero means
automatic arena/boss-bar distance: the radius, or the full 3D box diagonal using
inclusive axis lengths `max - min + 1`. The server resolves geometry when the
sound triggers and sends a positive 1–512 playback distance; missing/invalid
geometry or an arena in another dimension falls back to 32. Activation geometry
is excluded. Music retains its existing audience rules.

These fields export `x-clockwork.automatic` with the sentinel, geometry source,
fallback and cap. Their creation `default` is 0, while base `omitted.value` is
32 for imported legacy documents. Null phase/legacy roar overrides inherit;
explicit 0 selects automatic distance. Registered core roar `action_settings.range`
has `default_role: creation_and_omission` and default 0, including scalar null.
Only the core roar descriptor gets this audio annotation; addon fields retain
their own definitions. Preserve imported missing/hidden values rather than
copying creation defaults into them. The native editor uses the same distinction.

Offline common/client compilation, 189 focused distance assertions and the
existing 5,216-assertion regression suite passed for this change. No in-game
audio, network-latency or runtime schema-command checks were run in this pass.
The companion chat owns adoption of the export and browser/combined-jar tests.
Independent Draft 2020-12 validation accepted 14 existing fixtures and rejected
11 invalid ones; the additional audio-distance cases accepted 28 and rejected 18.

## Import/export preservation contract

Import/export treats the original JSON tree as the document. It must retain
unknown addon fields, objects, arrays, strings, booleans, nulls and number values.
An addon being absent is not permission to delete its configuration. The server
still refuses to run an encounter with an unknown action.

Preserve object member order. The order of `phases`, `subphases` and `roars`
defines the encounter sequence, and reward-entry order is retained too. Duplicate
member names are rejected instead of silently discarding an earlier value.

Only these root names are documentation/editor transport metadata and excluded
from saved template data: `_documentation`, `_action_catalog`, `_action_types`,
`_phase_order`, `_subphase_orders`, `_roar_orders`, `_loot_entry_order`. The same
names inside addon objects are ordinary data and are preserved.

Comment wording, whitespace and indentation are not preserved. Commented saves
regenerate grouped explanations; portable plain-JSON exports omit comments.
Explicitly deleting a phase/entry or changing an action type is an intentional
edit, not a promise to retain that deleted entry or the old action's settings.

Browser implementation details matter here:

- Use a parser/model that preserves unedited number tokens. Ordinary JavaScript
  `Number` can round a value such as `9007199254740993`.
- Retain ordered member lists. JavaScript object enumeration can reorder
  integer-looking phase names such as `10`, `2`, `1`, even without sorting.
- Treat unknown settings as opaque data. Never rebuild a template from only
  the visible controls or apply validator options that prune unknown properties.
- Render labels/help as plain text. Template JSON is configuration, never code.

The format bounds reads and plain exports to 512 KiB of UTF-8 and caps nesting
at 32 levels. Known edited numeric values use bounded exact decimal validation;
unknown imported numbers are retained without expanding huge exponents.

## Validation boundary

The schema provides portable authoring checks. It does not prove an encounter
can run on a particular server. Cross-field checks include descending non-final
health cutoffs, inherited targeting minimum/maximum budgets, reward quantity
relationships, usable geometry, available item/loot-table IDs and safe media.
Addon authors can also have domain checks beyond their scalar setting definitions.

These remaining checks are listed in `x-clockwork.runtime_checks`. The existing
server save/load/compile path stays authoritative. A future website should keep
unrecognized settings intact and explain checks that require the destination
server rather than claim to have verified that server's resources.

## Check the foundation

The existing offline suite includes the new production-reader/writer tests:

```text
gradlew auditChecks
```

For an independent standards check, generate fixtures and install the optional
Python test tool into the disposable build directory:

```text
gradlew exportTemplateSchema exportSchemaFixtures
python -m pip install --target build/schema-validator -r tools/schema-check-requirements.txt
python tools/check_template_schema.py
```

Python is not a mod/runtime dependency. Its validator uses
[the library's Draft 2020-12 validator](https://python-jsonschema.readthedocs.io/en/v4.25.1/validate/).
These tests validate the schema itself and check accepted/rejected documents;
Java separately checks lossless preservation and the isolated portable codec.

Recorded on 2026-10-11 after review fixes: common/client compilation passed;
**5,013 offline assertions**, including **628 template-contract assertions**,
passed. Both exported schemas passed the independent validator with **14 accepted**
and **11 rejected** fixtures, including supported nulls, legacy omissions and roar
filenames. All **29 native Minecraft GameTests** passed in the isolated test world,
including potion removal, orphan copies and full inventory refresh delivery.
Two older Sonic Boom tests were aligned with the API's existing interruption
contract: phase changes/reloads cancel pending action damage; a new charge gets
its full wind-up. No combat implementation was changed by that test correction.
These results do not establish actual client rendering, browser behavior, live
schema-command execution, modpack compatibility or deployment. The schema's
`server_only_checks` explicitly labels resources/addons/registries/world checks
that cannot be fully verified by a standalone browser.
