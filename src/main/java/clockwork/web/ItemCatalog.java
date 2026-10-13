package clockwork.web;

import com.google.gson.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.security.MessageDigest;
import java.util.*;

/** Immutable registry metadata captured on SERVER_STARTED. Request workers only
 * filter this bounded snapshot; no registry/item mutation, files or client models. */
final class ItemCatalog {
    static final int PAGE_SIZE = 64, MAX_ITEMS = 65536, MAX_NAMESPACES = 1024, MAX_RESPONSE = 128 * 1024;
    static final String ROUTE = "/api/items/(?:0|[1-9][0-9]{0,3})/[A-Za-z0-9_-]{1,86}/[A-Za-z0-9_-]{1,172}";
    record Entry(String id, String displayName) {}
    private final List<Entry> items;
    private final List<String> namespaces;
    private final String revision;
    ItemCatalog(List<Entry> source) throws IOException {
        if (source.size() > MAX_ITEMS) throw new IOException("Item registry exceeds the catalog limit");
        var sorted = new TreeMap<String, Entry>(); var mods = new TreeSet<String>();
        for (var entry : source) {
            if (entry.id() == null || entry.id().length() > 256 || !entry.id().matches("[a-z0-9_.-]{1,64}:[a-z0-9/._-]+")) throw new IOException("Invalid registered item ID");
            String label = entry.displayName() == null ? entry.id() : entry.displayName().replaceAll("[\\p{Cntrl}]", " ").strip();
            label = label.codePoints().limit(128).collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
            sorted.put(entry.id(), new Entry(entry.id(), label.isEmpty() ? entry.id() : label));
            mods.add(entry.id().split(":", 2)[0]);
        }
        if (mods.size() > MAX_NAMESPACES) throw new IOException("Item namespace count exceeds the catalog limit");
        items = List.copyOf(sorted.values()); namespaces = List.copyOf(mods);
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (var item : items) digest.update((item.id()+"\0"+item.displayName()+"\n").getBytes(StandardCharsets.UTF_8));
            revision = HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException unavailable) { throw new IOException(unavailable); }
    }
    JsonObject page(String path) throws IOException {
        if (!path.matches(ROUTE)) throw new IOException("Invalid item catalog request");
        String[] parts = path.split("/"); int page = Integer.parseInt(parts[3]);
        String namespace = decoded(parts[4], 64), query = decoded(parts[5], 128).strip().toLowerCase(Locale.ROOT);
        if (!namespace.isEmpty() && !namespace.matches("[a-z0-9_.-]{1,64}")) throw new IOException("Invalid item namespace");
        var filtered = items.stream().filter(item -> namespace.isEmpty() || item.id().startsWith(namespace+":"))
            .filter(item -> (item.id()+" "+item.displayName()).toLowerCase(Locale.ROOT).contains(query)).toList();
        int pages = Math.max(1, (filtered.size()+PAGE_SIZE-1)/PAGE_SIZE);
        if (page >= pages) throw new IOException("Item page is out of range");
        var result = new JsonObject(); result.addProperty("revision", revision); result.addProperty("page", page);
        result.addProperty("pages", pages); result.addProperty("total", filtered.size()); result.addProperty("registry_total", items.size());
        result.addProperty("page_size", PAGE_SIZE); result.addProperty("namespace", namespace); result.addProperty("query", query);
        var rows = new JsonArray();
        for (var item : filtered.subList(page*PAGE_SIZE, Math.min(filtered.size(), (page+1)*PAGE_SIZE))) {
            var row = new JsonObject(); row.addProperty("id", item.id()); row.addProperty("display_name", item.displayName()); rows.add(row);
        }
        result.add("items", rows); var mods = new JsonArray(); namespaces.forEach(mods::add); result.add("namespaces", mods);
        if (result.toString().getBytes(StandardCharsets.UTF_8).length > MAX_RESPONSE) throw new IOException("Item response exceeds limit");
        return result;
    }
    private static String decoded(String text, int limit) throws IOException {
        if (text.equals("_")) return "";
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(text);
            if (bytes.length > limit || !Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(text)) throw new IOException("Invalid item search encoding");
            String value = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if (value.codePoints().anyMatch(Character::isISOControl)) throw new IOException("Invalid item search text");
            return value;
        } catch (IllegalArgumentException | CharacterCodingException invalid) { throw new IOException("Invalid item search encoding"); }
    }
}
