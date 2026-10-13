# Optional hosted workshop

Clockwork Bosses **0.2.0-beta.4** includes the workshop and the companion's 0.3.7
sound-upload work. Install **one Clockwork Bosses jar**, with Fabric API. Clients
can install that same jar for the native editor/visuals. Workshop initialization
runs only on dedicated servers; players without the mod retain vanilla gameplay
fallback. No website is bundled and no inbound HTTP listener is opened.

The editor stays at https://workshop.clockworkboss.com. The mod connects through
an outbound TLS WebSocket. Failed connections pause browser access while fights
continue. Publishing the mod does not publish the separate Cloudflare website.

## Upgrade and settings

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
