package clockwork.web;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/** Memory-only capabilities: one-use links, short sessions and bounded request budgets.
 * The Minecraft adapter supplies the live owner-permission guard. No passwords,
 * profile-name lookup or HTTP-supplied identity can create an authenticated owner.
 */
public final class AccessSessions {
    public record Owner(UUID id, String name) { }
    public record Session(Owner owner, String csrf, long expiresAt) { }
    public record Login(String token, Session session) { }
    private record Link(Owner owner, long expiresAt) { }
    private record Budget(long start, int count) { }
    private final Map<String, Link> links = new HashMap<>();
    private final Map<String, Session> sessions = new HashMap<>();
    private final Map<String, Budget> budgets = new HashMap<>();
    private final SecureRandom random;
    private final Clock clock;
    private final Predicate<Owner> guard;
    private long generation;
    private boolean closed;
    public static final class RateLimited extends RuntimeException { }

    public AccessSessions(Predicate<Owner> guard) { this(guard, Clock.systemUTC()); }
    AccessSessions(Predicate<Owner> guard, Clock clock) { this(guard, clock, new SecureRandom()); }
    AccessSessions(Predicate<Owner> guard, Clock clock, SecureRandom random) {
        this.guard = guard; this.clock = clock; this.random = random;
        // Production constructs this API on the config worker. Seed the provider
        // there, instead of making the first native /boss workshop open command
        // own a possible operating-system entropy wait. No warm-up bytes survive.
        random.nextBytes(new byte[32]);
    }

    /** Called only by the owner-only player command after Minecraft authentication. */
    public String issue(Owner owner) {
        long started;
        synchronized (this) { started = generation; if (closed) throw new IllegalStateException("Workshop is stopping"); }
        if (!guard.test(owner)) throw new IllegalStateException("Verified Minecraft owner required");
        synchronized (this) {
        if (closed || generation != started) throw new IllegalStateException("Workshop access was revoked; run the command again");
        cleanup();
        links.values().removeIf(link -> link.owner().id().equals(owner.id()));
        if (links.size() >= 16) throw new IllegalStateException("Too many pending login links");
        String code = secret();
        links.put(hash(code), new Link(owner, clock.millis() + 120_000));
        return code;
        }
    }

    /** Consumed even on a revoked owner; a stolen/old link can never be replayed. */
    public Login redeem(String code) {
        Link link;
        long started;
        synchronized (this) {
        cleanup();
        if (closed || !validSecret(code)) return null;
        link = links.remove(hash(code));
        started = generation;
        }
        if (link == null || !guard.test(link.owner())) return null;
        synchronized (this) {
        cleanup();
        if (closed || generation != started || link.expiresAt() <= clock.millis()) return null;
        if (sessions.size() >= 32) return null;
        String token = secret();
        Session session = new Session(link.owner(), secret(), clock.millis() + 1_800_000);
        sessions.put(hash(token), session);
        return new Login(token, session);
        }
    }

    /** Permission revocation is checked again on every authenticated HTTP request. */
    public Session authenticate(String token) {
        Session session;
        synchronized (this) {
        session = current(token);
        // Invalid tokens never allocate limiter entries. All sessions of one
        // owner share a budget, BEFORE any Minecraft main-thread query.
        if (session != null && !permit("owner:" + session.owner().id(), 60)) throw new RateLimited();
        }
        return recheck(token, session);
    }
    /** Used after upload bodies and before publication commit; saves use at most
     * two permission queries, publishes at most four (including media staging).
     * Revocation during upload/copy must prevent its JSON commit.
     */
    Session recheck(String token, Session session) {
        synchronized (this) { if (session == null || current(token) != session) return null; }
        if (!guard.test(session.owner())) { logout(token); return null; }
        synchronized (this) { return current(token) == session ? session : null; }
    }
    /** Lookup for self-logout only: no permission query or exhausted-budget barrier. */
    synchronized Session current(String token) {
        cleanup();
        return closed || !validSecret(token) ? null : sessions.get(hash(token));
    }
    public synchronized void logout(String token) { sessions.remove(hash(token)); }
    public synchronized void revoke(UUID owner) {
        generation++;
        links.values().removeIf(link -> link.owner().id().equals(owner));
        sessions.values().removeIf(session -> session.owner().id().equals(owner));
    }
    public synchronized void clear() { closed = true; generation++; links.clear(); sessions.clear(); budgets.clear(); }

    /** Fixed global/session budgets keep the limiter itself bounded (no arbitrary IP map). */
    public synchronized boolean permit(String key, int limit) {
        if (closed) return false;
        cleanup();
        long now = clock.millis();
        Budget old = budgets.get(key);
        if (old == null || now - old.start() >= 60_000) old = new Budget(now, 0);
        if (old.count() >= limit) return false;
        if (budgets.size() >= 64 && !budgets.containsKey(key)) return false;
        budgets.put(key, new Budget(old.start(), old.count() + 1));
        return true;
    }
    private void cleanup() {
        long now = clock.millis();
        links.values().removeIf(link -> link.expiresAt() <= now);
        sessions.values().removeIf(session -> session.expiresAt() <= now);
        budgets.values().removeIf(budget -> now - budget.start() >= 60_000);
    }
    private String secret() { byte[] bytes = new byte[32]; random.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private static boolean validSecret(String value) { return value != null && value.matches("[A-Za-z0-9_-]{43}"); }
    static String hash(String value) { return digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    static String digest(byte[] bytes) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
