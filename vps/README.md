# Isolated core API candidate checks

Historical developer tooling for the fixed beta.7 API candidate below. These
Linux-only helpers are outside the normal Gradle build graph and the shipped
jar; the rc.3 radius playtest does not certify this separate execution plan.

This is a fixed plan for the four experimental encounter API additions. It adapts
Observatory's existing resource guard without changing that project's launcher.
It accepts only `build`, `native`, or an exact `--kill-owned <execution UUID>`.
It does not deploy or install a jar, start a prepared playtest, or change SSH trust.

Use a fresh `clockwork-core-api4-*` Linux source snapshot with its complete
`work/api4-input.json` manifest. Configure `JAVA_HOME` and `GRADLE_USER_HOME` to the
already approved build runtimes/cache, and `CLOCKWORK_CORE_BASELINE` to the
read-only preserved beta.6 jar. Its exact hash is checked before launch and it
supplies the old API for a binary compatibility fixture. Run `python3 vps/api_guard.py build`, then
`python3 vps/api_guard.py native` only after the build passes. Input hashes are
checked on each launch. Source changes require a separately preserved snapshot.

The guard takes the same nonblocking user lock at
`~/.local/state/clockwork-observatory/execution.lock`, respecting unchanged
`XDG_STATE_HOME`. A refusal means wait. Never unlink/replace/chown the lock.
Its scope prefix `clockwork-observatory-core-` is deliberate: Observatory's
existing admission check sees an orphaned core scope too. Exact UUID, UID and
systemd InvocationID must match before an owned stop can signal that scope.
Owner records use the shared owners directory; copies and measured samples go
under this snapshot's `vps/evidence`. A resource receipt alone never proves
Minecraft cleanup.

Build limits: four CPUs/400%, two Gradle workers, 1 GiB Gradle heap, 512 MiB check
heaps, 2.5 GiB memory high/3 GiB max and 600 seconds. Native checks: 3 GiB game
heap, 512 MiB Gradle heap, up to 5 GiB high/5.5 GiB max and 900 seconds. Native
admission may reduce max to 4.5 GiB while retaining host headroom. Both retain
measured 1.5 GiB host RAM and 10 GiB disk headroom, disable workload swap, and
cap tasks at 512. The guard verifies actual cgroups, watches headroom, pressure,
ownership and deadlines, and retains failure evidence. The test-only native
heartbeat allows 150 seconds startup and 20 seconds without ticks. Native stop
is requested first; only a verified owned scope may be killed after 25 seconds.

The fixed candidate is `0.3.0-beta.7-api.1` with archive base
`clockwork_bosses_api4_candidate` and output under `build/api4-candidate`. The
canonical `gradle.properties` and original beta.6 output remain unchanged.
This candidate includes the explicitly recorded preexisting validation WIP;
the build plan runs its prepared audit/core-schema/API checks too. It compiles
main/client/GameTest code, checks API values, and packages a separate jar.
The native plan selects only the API GameTests and requires an independent
real-shutdown PASS receipt. Its heartbeat/stop lifecycle belongs to the test mod
and is absent from the core jar. Passing server checks does not prove human
login, rendering, Better Combat combos, performance or Observatory integration.
