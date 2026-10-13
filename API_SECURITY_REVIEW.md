# Experimental API safety review

Reviewed 2026-10-10. This covers the current API revision 1 source, not the
previously deployed 0.1 jar. Source review and common/client compilation succeeded.
No gameplay, runtime mixin, latency, load or heap tests were run for this review.

## Findings and changes

| Issue | Possible consequence | Change |
| --- | --- | --- |
| Native cross-dimension travel replaces a temporary entity. Its marker and cleanup identity were not transferred. | An action-created entity could escape cleanup and become persistent in another world. | Marked temporary entities reject portals and cross-dimension teleport. Same-world teleport remains available. |
| Live context getters did not consistently check the server thread. | An addon could accidentally read or mutate world state from a worker, causing races or crashes. | Live context access checks the owning server thread before touching encounter state. Immutable settings can be copied; capture snapshots on the server thread. |
| Snapshot capture preceded cleanup's protected block. | An unexpected snapshot failure could skip cleanup and retain entity/world references. | Capture is protected, recoverable failures are logged, and resource release/reference clearing still run. |
| An action's stop callback can synchronously remove, reload or change its boss. | The outer transition could overwrite a newer phase/configuration, or start an action before outgoing restoration finished. | Transitions recheck ownership/generation/end state after stop. A per-encounter stop counter defers replacement creation until cleanup returns. Manager callers abandon cancelled transitions. |
| Burrow continued applying flags after relocation callbacks. | Removal/unload during relocation could be followed by stale visibility/pose changes. | Recheck active ownership after relocation and emergence pose changes. |
| Native permission queries can reenter while the action factory is still running. | Querying a not-yet-attached action could falsely fail the execution. | Reject native attacks until an instance is attached. |
| Whole-number schemas could accept values outside the range of `integer(key)`. | Valid-looking settings could throw during an attack. | Reject integer definitions with bounds outside a signed 32-bit integer. |

These are mostly addon-error and lifecycle risks. The temporary-entity escape
also provides an unintended game-world route to persistent leftovers. They are
not evidence that a normal player can execute addon Java or edit server files.

## Security boundary reviewed

- Installed Fabric addons execute trusted Java with the mod's process privileges.
  The API does not sandbox them or prevent direct Minecraft/filesystem access.
- Editor operations require the player's current session token and authorization.
  The 2026-10-11 permissions change uses the relevant Fabric v1 template/preset
  edit node, with native game-master fallback; global reload has its own node.
  Save operations also require the matching file revision. See [PERMISSIONS.md](PERMISSIONS.md)
  for revocation boundaries and pending provider runtime checks.
- Action catalog descriptions are supplied by the server. Save patches cannot
  replace the catalog; action IDs resolve to already registered definitions.
  No class loading or code execution is selected by a client-supplied class name.
- Bounded scalar parsing, exact ranges, choice lists, packet/document limits,
  bounded editor jobs and existing safe template paths remain in place.
- Built-in roar filenames receive the existing OGG filename validation. A generic
  addon text field does not authorize a path or validate a URL/file's contents.
- Snapshots contain immutable values/UUIDs, not entity or world references.
  Cleanup scopes retain at most 64 resources per execution and release their
  callbacks/live runtime references on closure. No action scheduler was added.
- Native attack hooks remain scoped to marked Clockwork Bosses. The temporary
  entity guards apply only after explicit adoption, not to ordinary entities.

No permission bypass was found in the reviewed editor/API paths. This is a
bounded source review, not proof that every interaction with every addon is safe.

## Addon responsibilities and remaining limits

1. **Short callbacks.** A slow or looping action/event callback can stall the
   server thread. Exception isolation cannot stop arbitrary running Java safely.
2. **Fresh factories.** Each execution needs a fresh instance. The core does not
   prove instance freshness; shared mutable action objects can mix boss state.
3. **Listener lifetime.** Register Fabric listeners once during initialization.
   Registering every fight/action grows the retained listener array. Do not capture
   a live world, player, entity or action context in a process-wide listener.
4. **Owned resources.** Adopt every temporary entity your action creates, including
   separate passengers/minions, before publishing it. Register restoration before
   mutation. Direct entity replacement or persistence overrides in another mod
   can bypass the cooperative native guards; the API cannot police arbitrary code.
5. **Persistent changes.** A hard process stop cannot execute callbacks. Burrow
   has a recovery journal; another persistent mutation needs its own recovery
   plan. The original burrow surface is a fallback, not a guarantee that terrain
   stayed unchanged while underground. Corrupt saved journals also need diagnosis.
6. **Release compatibility.** Corrected on 2026-10-12: example addon 0.1.1 reads
   the core build's configured version and pins its runtime dependency to that
   exact version, rejecting the earlier 0.1 jar without this API. Rebuild the
   example after changing the core version. A shared API revision number alone
   does not promise binary compatibility across experimental builds.

## Verification still needed before release

- Boot with the new Entity mixins in the actual Lithium/Sodium/ETF/EMF environment.
- Exercise temporary entity portal rejection, same-world movement and chunk unload.
- Interrupt actions during start, damage, stop, relocation, reload and boss removal.
- Check simultaneous bosses, server-only addon editor controls, saved burrow recovery
  and repeated encounter cleanup with a heap/load measurement.

The implementation signatures were inspected in the configured Minecraft 26.3
bytecode. Compilation verifies Java calls; it does not verify runtime injection
targets or actual multiplayer behavior. No jar was packaged or deployed here.
