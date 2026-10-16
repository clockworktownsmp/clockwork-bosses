# Clockwork Bosses 0.3.0-rc.3 checkpoint

Prepared October 16, 2026. The owner confirmed the isolated ten-block-radius
playtest works. This checkpoint keeps that exact tested universal jar; it does
not rename it to a stable version, change its Workshop channel, or deploy it.
The addon API remains experimental revision 1.

## Jar

`clockwork_bosses-0.3.0-rc.3.jar` (1,471,154 bytes), for Minecraft 26.3.

```text
SHA-256: 7bbfa07c1c34c720fc835b03c696fab49ec5b734b92f43a13744e12946202b7a
```

Minimum dependencies remain Fabric Loader 0.19.5 and Fabric API 0.161.0+26.3.
The isolated owner playtest server used Loader 0.19.5, API 0.163.0+26.3 and
Java 25. The packaged Workshop channel is beta. Hosted editing still requires
a separately compatible website declaration; this checkpoint does not alter
origins, credentials, server settings or the website.

## Burrow behavior

- Returning from a burrow plays the native emergence at three times normal
  speed, finishing after 34 ticks when both feet are planted. Initial activation
  retains its normal 134 ticks. Matching client code is required for the changed
  animation timing and the earlier digging-animation cleanup.
- `emerge_damage` defaults to 20 raw HP, before native armor and defenses.
- `emerge_radius` defaults to 10 blocks when omitted or null. Saved explicit
  radii remain unchanged. It is an inclusive 3D feet-to-feet distance, independent
  of boss scale. Damage or radius 0 disables emergence damage.
- `emerge_particles` defaults to true. It sends one native mace-style ground
  burst without breaking blocks. Its decorative ring does not show damage range.
- The impact commits once and uses eligible current arena participants and
  native damage handling. Interruption cleanup prevents stale or repeated hits.

## Validation and limits

- The rc.3 offline build/check and native-test compilation passed, including
  74,265 audit assertions and the registered Workshop checks. The packaged ZIP
  integrity, schema, metadata and unchanged minimum dependencies were inspected.
- The earlier rc.2 native work passed 13 required server checks, 6,588 native
  model comparisons and 52 native editor assertions. Those results belong to
  rc.2; they are not a full native-regression result for rc.3.
- The rc.3 radius-only playtest used a fresh isolated dedicated server and the
  exact jar above. Its template omitted the radius; optional controls held a
  player at eight or twelve blocks. Explicit ten- and four-block control
  templates were available. The owner reported success. This is owner-reported
  acceptance, not a captured per-case automated test transcript.
- The controlled fixture disabled ordinary attacks and natural regeneration;
  native armor, Protection, shields, Resistance and hurt cooldowns remained.
  It does not certify ordinary fight balance, real multiplayer, weak connections,
  all modpacks, or a performance/soak run.
- An exact beta.6 addon binary fixture was not supplied for the rc.3 build, so
  this checkpoint does not claim renewed binary-addon certification. A Windows
  file-symlink case was unavailable; linked-folder/junction cases ran.

Test source and existing ignore rules are preserved. Private worlds, runtime
logs, credentials, generated output and acceptance archives remain ignored.
Historical checkpoint reports retain their original version-specific limits.
