# Optional hosted workshop

Clockwork Bosses **0.2.0** includes the workshop and the companion's 0.3.7
sound-upload work. Install **one Clockwork Bosses jar**, with Fabric API. Clients
can install that same jar for the native editor/visuals. Workshop initialization
runs only on dedicated servers; players without the mod retain vanilla gameplay
fallback. No website is bundled and no inbound HTTP listener is opened.

Released 0.2.0 uses https://workshop.clockworkboss.com. The current beta defaults
to https://beta.clockworkboss.com with build-channel selection enabled. The mod connects through
an outbound TLS WebSocket. Failed connections pause browser access while fights
continue. Publishing the mod does not publish the separate Cloudflare website.

## Upgrade and settings

### Explicit release channels — beta preview 0.3.0-beta.4

`workshop_build_channel` in `gradle.properties` is packaged into Fabric metadata.
Allowed values are **stable**, **beta**, **development**. This checkout explicitly
uses beta. Version spelling, prerelease suffixes and Git branches never
choose a host. The immutable released 0.2.0 jar retains its existing stable
behavior and protocol; it was not rebuilt or modified.

| Channel | Browser and outbound relay origin |
| --- | --- |
| stable | `https://workshop.clockworkboss.com` |
| beta | `https://beta.clockworkboss.com` |
| development | `https://dev.clockworkboss.com` |

Selection order in `workshop.properties`:

1. Nonempty `testing_channel` deliberately selects one of those channels.
2. Otherwise an existing explicit `relay_url` stays pinned to its exact origin.
3. Otherwise `use_build_channel=true` uses the packaged channel.
4. Legacy files missing both fields retain the old stable default.

New default files include `use_build_channel=true`, blank `testing_channel`,
and disabled access flags. They do not create an identity or open a connection
until enabled. Existing files are not rewritten. Browser links, websocket and
metadata reads use one normalized exact origin; no host fallback or release
routing negotiation exists. Invalid reloads retain previous settings with an
error; a valid selection with unavailable metadata stays on that selected host
and pauses writes.

**Credential/trust behavior:** first upgrade preserves the old key and records
its currently configured origin in the non-secret `relay-server.origin` beside
it. That adoption trusts the owner's existing explicit `relay_url`. Later
attempts to reuse that identity on another origin are refused. Testing overrides
and build-selected non-stable hosts generate independent keys under
`clockwork-bosses-web/relay-identities/<origin-hash>/`; they never copy, derive
from, rotate or migrate the production key. Changing the connection revokes old
links/sessions. Use a separate online-mode test instance with separate configs
and worlds: different cloud namespaces do not separate a shared filesystem.
No actual server credentials/settings were touched by this development task.

Example for an **isolated test instance**, applied manually with
`/boss workshop reload`:

```properties
enabled=true
template_access_enabled=true
use_build_channel=true
testing_channel=beta
```

### Website compatibility

The new `/api/status` adds exact installed `mod_version`, packaged `mod_channel`,
selected `workshop_channel` (including `custom` for self-hosted URLs),
`workshop_origin` and `workshop_compatibility`. The latter reports state,
`writable`, reason, `web_version` and build. Website metadata channel names
`production/beta/dev` map to mod names `stable/beta/development`. An intentional
override may make the mod and selected workshop channels differ.

Before server saves, copies/renames, publishing or sound uploads, the mod checks
the selected host's credential-free `/build-info.json`. The installed version
must appear **exactly** in `compatible_mod_versions`; all `required_capabilities`
must be available. Website and mod patch versions are independent: website
0.2.1 can support mod 0.2.0. Unknown requirements fail closed. Existing feature
flags/codec checks, permission, CSRF, revision and commit guards stay in place.
Checks are cached for at most 30 seconds, bounded to 16 KiB/five seconds, run on
existing workers and never follow redirects. No tick watcher is added.

A stable or existing custom relay returning 404 for metadata retains legacy
protocol v1 with an unverified diagnostic. Missing/malformed/timed-out metadata
on beta/dev never becomes legacy access; malformed-present stable metadata also
pauses writes. Login/logout and authenticated downloads/previews remain usable
for recovery. The prepared browser SDK also revalidates before writes, pauses
autosave and checks separately before raw XHR uploads. Local Import/Download
JSON and local recovery remain available.

**Current beta deployment:** website **0.3.0-rc.1** (build `3c900f27`)
explicitly supports mod **0.3.0-beta.4** and the independently tested legacy
**0.2.0** protocol. Required capabilities are empty; optional features retain
their existing capability gates. Website/mod version equality is not required.
The packaged beta.4 Java HTTP client reads the public declaration and reports
compatible/writable. Owner authentication, CSRF, exact origins, session
isolation, schema validation and revision checks still control publication.

Use a fresh `/boss workshop open` link on the deliberately selected beta
connection. An existing production URL remains pinned unless the owner sets
`testing_channel=beta` and runs `/boss workshop reload`; that override uses a
separate identity. Server settings and credentials were not changed for this
publication. Production's deployed version and all 27 public file hashes remain
unchanged. Dev was not deployed; frozen 0.2.0/0.2.1 artifacts are retained.
Actual-account/cloud relay end-to-end playtesting remains to be done.

### Existing installation upgrade

1. Stop Minecraft; back up both old jars **outside `mods/`**.
2. Remove the old core and companion jars, then install the merged core jar.
3. Restart the server yourself. Open a new workshop login after restart.

Fabric rejects an old `clockwork_bosses_web` companion before either initializer
runs. The adapter has a matching explicit upgrade guard. Keep its config/data.

On its configuration worker, startup copies an existing
`config/clockwork-bosses-web.properties` into
`config/clockwork-bosses/workshop.properties` **byte for byte**. Comments, unknown
properties, flags and secrets survive. The original remains; an existing
canonical file wins. Invalid, oversized, linked or concurrently changed source
files are rejected without replacing settings. Reload does not resurrect a
deleted canonical file. Fresh installations default both access flags to false.

Identity, drafts, draft media, backups and publication journals remain under
`config/clockwork-bosses-web/`. Keeping those paths preserves the relay address
and recoverable publications. Templates, presets and active media remain under
`config/clockwork-bosses/`. No media is bulk-moved or reset. Browser sessions and
one-use links are memory-only; restart invalidates them as before.

To enable access, edit the canonical server settings:

```properties
enabled=true
template_access_enabled=true
relay_url=https://workshop.clockworkboss.com
```

## Commands and permissions

| Command | Purpose |
| --- | --- |
| `/boss workshop open` | Private one-use login link; expires after two minutes. |
| `/boss workshop logout` | Revoke your links/sessions, including during an outage. |
| `/boss workshop reload` | Apply workshop settings asynchronously. |

Run Open in game. Minecraft's native **owner** permission, normally operator
level four, and online-mode are required. Boss permission nodes do not grant
workshop ownership. Console can reload, but cannot issue a player login. Hosts
need outbound TLS on port 443; no extra inbound web port is needed.

The old `/bossworkshop open`, `logout` and `reload` commands are temporary aliases
with identical handlers/permissions. `/boss reload` applies **gameplay settings
and media**, separately from the connection settings above.

Unchanged settings keep valid sessions. Disabling access or changing origin
revokes them. Invalid reloads retain the previous settings. Transient reconnects
retain sessions but never replay an interrupted save/upload/publish; review the
draft before retrying a failed mutation. Logout revokes even while disconnected.

## Drafts and media

### Publication and autocomplete — prepared 0.3.0-beta.5

**Save draft** keeps a draft on the server. **Publish draft** commits its
validated template/media and automatically updates the game's template
autocomplete list. Future spawns read the published settings from disk.

Publish uses the **saved server draft**, not unsaved browser fields. Wait for a
successful **Save draft**, then a successful **Publish draft**, before reloading.
An error saying **Active template changed** means that operation was rejected;
reloading cannot apply unsaved or unpublished values. In-game commands such as
`/boss setspawn` or area selection can change
the active template after a draft was loaded. Download JSON to back up browser
edits, load and review the **Active** template under Saved templates, and reapply
the desired fields before saving/publishing again. This preserves newer spawn
and arena coordinates; importing the entire old download can undo those changes.

**Run `/boss reload` to apply these changes to bosses that are already spawned.**
Publishing preserves running encounters' current settings. `/boss workshop
reload` reloads the workshop connection settings; it does not reload boss settings.

The existing web file worker refreshes one owned template's name in an immutable
suggestion snapshot after the publication commits. It adds no tick scan and
never reloads a running boss. If refreshing suggestions fails, publication is
still saved successfully; run `/boss reload` to refresh the list manually.
Older mod versions, including beta.4, require that manual reload for autocomplete.

This behavior is compiled/prepared, not deployed or regression-tested. The
current beta website declares beta.4 and 0.2.0; beta.5 support has not been
declared. Website/mod release and installation are separate steps.

Save creates a complete immutable JSON/media draft from its selected preset,
active template or prior draft. Publish rechecks draft/active revisions,
validates with the **live core/addon schema**, backs up replaced files, then
commits. Unknown addon properties and precise number tokens survive. Native
editor saves and web publication share a worker-only lock to reject stale edits.

Uploads target drafts only. Event/voice files accept path-free, lowercase OGG
Vorbis names, at most **1 MiB minus 1 KiB / 30 seconds**. Music accepts at most
**8 MiB minus 1 KiB / 10 minutes**. Use mono event/voice clips for positional
playback; music can be mono or stereo. Full bounded decode, CRC, duration and
filename checks validate contents. Confirmed replacements get backups;
publication journals recover interrupted writes. The hosted UI also needs the
matching upload capability; a jar upgrade does not update that UI.

Sound uploads are included in merged Clockwork Bosses; no separate companion is
required. Upload availability comes from the running server opened by your login
link. If the page reports missing upload support, install Clockwork Bosses
0.2.0-beta.4 or newer and restart that Minecraft server. Live and test workshop
links access separate server installations.

The relay refreshes older connection metadata automatically when an upload first
needs a capability added by a website update. This reconnect leaves boss fights
running. Review the saved draft before retrying any interrupted save or upload;
the relay never replays those operations automatically. Explicitly unsupported
servers remain connected and receive the normal upgrade message.

## Privacy, ownership and limits

Keep `clockwork-bosses-web/relay-server.key` private and backed up. Losing it
changes the server address; copying it to another running server lets that
server replace the connection. Login secrets stay in private chat fragments and
bounded memory, never logs. API operations and commits recheck current native
ownership. Cookies are scoped, secure and HttpOnly; mutations require CSRF.

File guards reject linked ancestors, including Windows junctions. OS permissions
must still exclude untrusted local writers: addons/processes with server
privileges are not sandboxed. Queues, deadlines and retries are bounded; shutdown
cancels requests, streams, workers and timers. No workshop tick watcher exists.
Minecraft state stays on its thread; blocking file/network work stays on workers.

See [Performance audit](PERFORMANCE_AUDIT.md) for exact limits and
[Preflight](PREFLIGHT.md) for test evidence and remaining playtest limits.
Automated codec/relay checks do not replace rendered multiplayer tests or a
live-server TPS profile.
