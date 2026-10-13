# Permissions and LuckPerms

## Status

This feature is packaged in the `0.2.beta.1` test build after release 0.1.
Common/server and client sources compile against Minecraft 26.3 and Fabric API
0.161.0+26.3. Matching beta jars are installed in the test environment;
permission-provider playtests remain pending. Earlier 0.1 jars retain their
operator-only checks.

## How access works

Clockwork Bosses asks **Fabric Permission API v1**, included in the required
Fabric API. LuckPerms builds that implement that API, and other providers of the
same API, can grant or deny the nodes below. LuckPerms is optional: there is no
LuckPerms-specific library or separate integration jar to install in this mod.
Providers that implement only the older Fabric permissions API v0 are not covered.

Without a provider answer, administrative operations require Minecraft's native
game-master permission, normally operator level 2. An explicit provider grant
allows a non-operator; an explicit denial overrides operator fallback. Minecraft
still owns console, command-block and operator permissions. The installed provider
owns groups, wildcards, inheritance and contextual grants.

A grant for one child command makes `/boss` available without granting its other
operations. Playing in an encounter, receiving its rewards and using its configured
fight potion do not require an administrative node. These grants apply across
templates; this version does not provide per-template ownership restrictions.

## Permission nodes

LuckPerms uses the dotted names below. Fabric's underlying identifier replaces
the first dot with `:` and later dots with `/`; for example,
`clockwork_bosses:command/template/edit`.

| LuckPerms node | Allows |
| --- | --- |
| `clockwork_bosses.command.create` | `/boss create new` and `/boss create preset` |
| `clockwork_bosses.command.spawn` | `/boss spawn` |
| `clockwork_bosses.command.activate` | `/boss activate` |
| `clockwork_bosses.command.remove` | `/boss remove <name>` |
| `clockwork_bosses.command.remove.all` | `/boss remove all` |
| `clockwork_bosses.command.list` | `/boss list` |
| `clockwork_bosses.command.template.list` | `/boss template list` |
| `clockwork_bosses.command.template.schema` | `/boss template schema` |
| `clockwork_bosses.command.template.edit` | Open, preview and save active templates in the editor |
| `clockwork_bosses.command.preset.edit` | Open, preview and save reusable presets in the editor |
| `clockwork_bosses.command.template.delete` | `/boss template delete`; never deletes presets |
| `clockwork_bosses.command.setspawn` | `/boss setspawn` |
| `clockwork_bosses.command.select.activation` | Select an activation area or redstone block |
| `clockwork_bosses.command.select.arena` | Select an arena/boss-bar area |
| `clockwork_bosses.command.reload` | `/boss reload` and the editor's **Reload saved** button |
| `clockwork_bosses.command.debug` | All `/boss debug` operations, including capture export |

The template and preset edit grants each open the picker; it lists only the file
kind the player may edit. Saving does not require reload permission. If that
permission is absent, **Reload saved** reports a denial instead of applying edits.
The combined `/boss select activation <template> true` needs **both** selection
nodes, since it changes both areas.

Removing one boss and removing all bosses are independent operations. Granting
`clockwork_bosses.command.remove` alone does not grant `remove.all`. A provider's
wildcard such as `clockwork_bosses.command.remove.*` follows that provider's rules.

## LuckPerms examples

Allow a group to create and edit templates without allowing it to edit presets,
delete templates, export diagnostics or reload all live encounters:

```text
/lp group boss_builders permission set clockwork_bosses.command.create true
/lp group boss_builders permission set clockwork_bosses.command.template.list true
/lp group boss_builders permission set clockwork_bosses.command.template.edit true
/lp group boss_builders permission set clockwork_bosses.command.setspawn true
/lp group boss_builders permission set clockwork_bosses.command.select.activation true
/lp group boss_builders permission set clockwork_bosses.command.select.arena true
```

Allow another group to host encounters:

```text
/lp group boss_hosts permission set clockwork_bosses.command.spawn true
/lp group boss_hosts permission set clockwork_bosses.command.activate true
/lp group boss_hosts permission set clockwork_bosses.command.remove true
/lp group boss_hosts permission set clockwork_bosses.command.list true
```

Grant the full command family to administrators, or deny a particular operation:

```text
/lp group boss_admins permission set clockwork_bosses.command.* true
/lp user ExamplePlayer permission set clockwork_bosses.command.template.delete false
```

Create/assign groups using your existing LuckPerms workflow. Operator fallback
still applies to unanswered nodes; use non-operator accounts for limited roles,
or configure explicit denials through your provider. Do not give template editing
to untrusted players: templates control encounter behavior, rewards and media.

LuckPerms' [Fabric v1 listener](https://github.com/LuckPerms/LuckPerms/blob/master/fabric/src/main/java/me/lucko/luckperms/fabric/listeners/FabricPermissionsApiV1Listener.java)
documents its conversion from Fabric identifiers to these dotted Boolean nodes.

## Revocation and cleanup

The server checks permissions when parsing commands and again in their execution
handlers. Every editor file operation checks its own node and its server-issued
session token. Loading a document and delivering a preview check again after I/O;
queued previews check before transmission starts. Clients cannot grant themselves
access by changing a packet's preset flag or a selector item's tags.

Selection clicks use the server's stored session to choose permissions. Losing
either required grant cancels that selection on the next interaction and removes
its arrow. `/boss select cancel` can still clean up the player's own session after
revocation, and an editor **Close** request can still discard its own session.
Neither cleanup operation writes a template.

A disk write or media transfer already accepted while authorized may finish;
revocation does not roll back an in-flight save or retract delivered bytes. The
permission provider controls command-menu refresh; enforcement uses current grants
even if a client still shows stale suggestions.

No permission watcher, grant cache, database connection, extra thread or player
registry is added. Existing editor job/session bounds and disconnect/shutdown
cleanup remain responsible for editor resources.

## Maintainer map and remaining checks

`ClockworkBossPermissions.Action` defines immutable nodes, their dotted names and
native fallback. Command registration uses the same definitions for visibility.
Manager/diagnostic handlers, editor requests and selector interactions use them
for authorization. Keep a new command's registration and execution checks together;
an editor button must check the operation it actually performs.

Before release, check with a Minecraft 26.3 permission-provider build:

1. Without a provider, OP level 2 retains administrative access and non-operators do not.
2. A non-operator granted only `spawn` can spawn but cannot edit, reload or remove.
3. Preset-only editing cannot read/save templates; template-only editing cannot read/save presets.
4. `remove` cannot remove all bosses, and `remove.all` works without a `remove` grant.
5. Revocation while an editor/selector is open denies new operations and allows own-session cleanup.
6. Combined selection needs both grants; an explicit denial also blocks an operator.
7. Provider menu refresh and contextual grants match your server setup.

These are pending runtime checks, not recorded test results.
