package clockwork.bosses;

import clockwork.bosses.api.*;
import java.util.*;

/** Value/old-constructor compatibility checks; native hooks have GameTests. */
public final class ClockworkBossApiChecks {
    public static void main(String[] args) throws Exception {
        UUID boss = UUID.randomUUID(), player = UUID.randomUUID();
        Set<UUID> membership = new HashSet<>(Set.of(player));
        // Compile the original eight-argument constructor and callback types.
        var encounter = new BossEncounterSnapshot(boss, "test", "test", "minecraft:overworld", "annoyed", "combat", 1, membership);
        BossEncounterEvents.Hit hit = (snapshot, action, target, damage) -> {};
        BossEncounterEvents.End end = (snapshot, reason) -> {};
        membership.clear();
        check(encounter.participants().equals(Set.of(player)), "legacy membership defensively copied");
        check(BossMobController.class.getMethod("requestTarget", net.minecraft.server.level.ServerPlayer.class).isDefault(), "old controller implementors inherit an optional method");
        check(BossActionContext.class.getMethod("requestTarget", net.minecraft.server.level.ServerPlayer.class).isDefault(), "old contexts inherit an optional method");
        check(!BossEncounterEvents.hasVitalsListeners() && !BossEncounterEvents.hasTerminalListeners() && !BossEncounterEvents.hasCleanupListeners(), "new observation gates start empty");
        check(ClockworkBossVitals.begin(null, BossVitalsChange.Field.HEALTH) == null && !ClockworkBossVitals.hasTemporaryState(), "zero listeners return before entity access/allocation");
        var vitals = new BossVitals(player, 50, 100, 4, true, false);
        var players = new HashMap<>(Map.of(player, vitals));
        var unavailable = new HashSet<UUID>();
        var terminal = new BossTerminalSnapshot(encounter, BossAction.StopReason.REMOVED, Optional.empty(), players, unavailable);
        players.clear(); unavailable.add(player);
        check(terminal.players().get(player).equals(vitals) && terminal.unavailablePlayers().isEmpty(), "terminal retains immutable captured values");
        rejects(() -> terminal.players().clear()); rejects(() -> terminal.encounter().participants().clear());
        var changes = new ArrayList<BossVitalsChange.Moment>();
        var change = new BossVitalsChange(vitals, vitals, BossVitalsChange.Field.HEALTH, BossVitalsChange.Cause.UNKNOWN,
                Optional.empty(), Optional.empty(), OptionalDouble.empty(), changes, false, 0, 0, true, true);
        changes.add(new BossVitalsChange.Moment(boss, "test", "test", "minecraft:overworld", "x", "y", 2));
        check(change.encounters().isEmpty() && change.damage().isEmpty() && change.action().isEmpty(), "copied encounters and explicit unknown source");
        rejects(() -> change.encounters().clear());
        var owners = new HashSet<>(Set.of(boss));
        var health = new BossSupportSnapshot.Health(owners, 20, 20, 20, 10, true, new ArrayList<>());
        var effects = new BossSupportSnapshot.Effects(owners, new HashMap<>(), new HashSet<>());
        var potion = new BossSupportSnapshot.Potion(owners, 1, true, 100);
        owners.clear();
        check(health.owners().contains(boss) && effects.owners().contains(boss) && potion.owners().contains(boss), "support ownership copied");
        var support = new BossSupportSnapshot(vitals, health, effects, potion);
        var cleanupPlayers = new HashMap<>(Map.of(player, new BossCleanupSnapshot.Player(Optional.of(support), Optional.empty())));
        var cleanup = new BossCleanupSnapshot(encounter, BossAction.StopReason.REMOVED, true, true, true, false, cleanupPlayers, Set.of(player));
        cleanupPlayers.clear();
        check(!cleanup.completedNormally() && cleanup.players().get(player).after().isEmpty() && cleanup.unavailablePlayers().contains(player), "missing cleanup never fabricates success");
        rejects(() -> cleanup.players().clear()); rejects(() -> health.owners().clear());
        legacyBinary();
        System.out.println("PASS: additive API values, defensive copies, empty gates and legacy source signatures");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void rejects(Runnable mutation) {
        try { mutation.run(); throw new AssertionError("Mutable snapshot collection"); }
        catch (UnsupportedOperationException expected) {}
    }
    /** Compile an unchanged old interface/constructor/callback client against
     * the exact preserved jar, then load that bytecode with the candidate API.
     * Optional outside the fixed VPS plan; a skip never claims binary evidence. */
    private static void legacyBinary() throws Exception {
        String baseline = System.getProperty("clockwork.api.legacyJar");
        String dependencies = System.getProperty("clockwork.api.compileClasspath");
        if (baseline == null || dependencies == null) {
            System.out.println("NOT_TESTED: beta.6 binary fixture (no exact baseline/classpath supplied)"); return;
        }
        var jar = java.nio.file.Path.of(baseline);
        String hash = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(java.nio.file.Files.readAllBytes(jar)));
        check(hash.equals("d917fbe4af296e5b54d839b6c04f0db9336f98549b1eff508f5f56b6f58473de"), "exact old API artifact");
        var directory = java.nio.file.Files.createTempDirectory("clockwork-legacy-api-");
        try {
            var source = directory.resolve("LegacyApiProbe.java");
            java.nio.file.Files.writeString(source, """
                    import clockwork.bosses.api.*;
                    import java.util.*;
                    import net.minecraft.resources.Identifier;
                    import net.minecraft.world.entity.Mob;
                    public final class LegacyApiProbe implements BossMobController {
                        public Mob mob() { return null; }
                        public Set<Identifier> capabilities() { return Set.of(); }
                        public static BossEncounterSnapshot snapshot(UUID id) {
                            return new BossEncounterSnapshot(id,"legacy","legacy","minecraft:overworld","annoyed","combat",1,Set.of());
                        }
                        public static BossEncounterEvents.Hit hit() { return (encounter,action,target,damage) -> {}; }
                        public static BossEncounterEvents.End end() { return (encounter,reason) -> {}; }
                    }
                    """);
            var compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
            check(compiler != null && compiler.run(null, null, null, "-proc:none", "--release", "25", "-classpath",
                    jar + java.io.File.pathSeparator + dependencies, "-d", directory.toString(), source.toString()) == 0, "old client compiles against beta.6");
            try (var loader = new java.net.URLClassLoader(new java.net.URL[]{directory.toUri().toURL()}, ClockworkBossApiChecks.class.getClassLoader())) {
                var old = loader.loadClass("LegacyApiProbe");
                check(old.getConstructor().newInstance() instanceof BossMobController, "old implementation links to new interface");
                UUID id = UUID.randomUUID();
                check(((BossEncounterSnapshot) old.getMethod("snapshot", UUID.class).invoke(null, id)).bossId().equals(id), "old constructor bytecode links");
                check(old.getMethod("hit").invoke(null) instanceof BossEncounterEvents.Hit
                        && old.getMethod("end").invoke(null) instanceof BossEncounterEvents.End, "old callback bytecode links");
                check(old.getMethod("requestTarget", net.minecraft.server.level.ServerPlayer.class).isDefault(), "old bytecode inherits new optional method");
            }
            System.out.println("PASS: exact beta.6 compiled client links to candidate interface, constructor and callbacks");
        } finally {
            java.nio.file.Files.deleteIfExists(directory.resolve("LegacyApiProbe.class"));
            java.nio.file.Files.deleteIfExists(directory.resolve("LegacyApiProbe.java"));
            java.nio.file.Files.deleteIfExists(directory);
        }
    }
}
