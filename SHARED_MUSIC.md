# Shared music candidate 0.2.2

This is the music-only backport onto released 0.2.1. It adds the shared song
library, supplied boss1.ogg and boss2.ogg, native source/file controls, and the
storage, transfer, permission and relay integration they need. Existing gameplay,
targeting, arenas and other editor settings keep the 0.2.1 behavior. The addon API
remains experimental revision 1. No commit, push, tag, installation or deployment
has been made for this candidate.

Use matching 0.2.2 jars on client and server, or in an isolated single-player
profile. Minecraft 26.3, Fabric Loader 0.19.5 and Fabric API 0.161.0+26.3 are the
baseline. The exact jar also passed a normal dedicated-server boot with Fabric
API 0.163.0+26.3. These boot checks did not include a remote player login.

Start the isolated server/world once. The existing file worker validates and
copies the two bundled songs into `config/clockwork-bosses/music/` only when the
corresponding file is missing. Existing administrator files are preserved. No song
is selected automatically.

Open `/boss template edit <template>`, choose Sounds, expand Music, and select
Library and boss1.ogg or boss2.ogg. Enable music and Save. Keep the selected native
sound category and Master volume unmuted. Music volume, channel, loop and fade are
per-template settings. Set a boss-bar arena box or radius and enter that arena
during an active encounter; music uses the same arena membership. An unset box
does not admit music listeners. For a disposable template, `/boss create new music_test` creates it and `/boss spawn music_test Music Test` spawns an encounter.
Configure the arena and activation normally before expecting playback.

`music.source` accepts `template` or `library`. Missing or null retains the
original template-local source. Template files come from that template's `sounds/`
folder; library files come from the shared folder above. There is no fallback
between sources. Blank filenames disable music. Missing or invalid shared files
remain visible as diagnostics. Drafts and exports store references, not copies of
the songs. Selecting an existing track in the native editor does not grant global
library management permission.

The matching Workshop candidate is a private local preview. The public Workshop
has not been changed. Shared-library mutations retain existing server-owner
authentication, origin/CSRF and permission rechecks, revision-bound confirmation,
complete reference impact and required backups. In-use deletion is refused.
Uploads require lowercase OGG basenames and fully valid mono/stereo Vorbis at
8–96 kHz, no more than ten minutes and at most 8 MiB minus 1 KiB. Opus is not
accepted. Complete limits and failure cases are bound in the candidate receipts.

The inherited 0.2.1 web JSON policy still rejects a giant exponent in an unknown
addon value before writing a draft. Native Save and complete library reference
scans preserve that value. Supported precise integers and tiny numeric tokens
remain preserved through web Save/Publish. This candidate does not import the
newer 0.3 validation policy.

Fresh core evidence passed: all local build/regression tasks, 77 native client
assertions, two server negotiation GameTests, 71 Linux library/storage/HTTP checks,
and both exact packaged-jar boots. Actual editor Save, unknown-value retention,
shared versus legacy identity, partial transfer cancellation, and native
JOrbis/OpenAL streaming were exercised. The native run used software graphics and
a null audio device. Physical speakers, full-song/loop listening, Windows graphics,
remote multiplayer, full modpack behavior and the hosted real-owner Workshop flow
remain for playtesting. Browser results are recorded separately in TEST_REPORT.md.

The low-resolution generic section footer can still mention a template sounds
folder. The Library file field's detailed help names the correct shared folder;
the source selector and missing-file diagnostic show the actual source.

All test-owned processes were stopped and the existing shared execution lock was
free at final verification. Released 0.2.1, the original dirty development tree,
frozen beta.9/beta.10 artifacts and both supplied Downloads originals were
hash-verified unchanged. See ARTIFACT_RECEIPT.json and TEST_REPORT.md for exact
artifact pins, checks and remaining limits.
