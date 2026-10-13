package clockwork.bosses.api;

import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable compiled scalars for one execution. No parsing happens in tick.
 * Missing/null values use the action's declared default; unknown file fields
 * survive editor saves but are not published as executable settings. */
public final class BossActionSettings {
    private final Map<String, Object> values;
    private BossActionSettings(Map<String, Object> values) { this.values = Map.copyOf(values); }
    public static BossActionSettings compile(List<BossSetting> fields, JsonObject input) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (BossSetting field : fields) values.put(field.key(), field.parse(input.get(field.key())));
        return new BossActionSettings(values);
    }
    private Object require(String key) {
        Object value = values.get(key);
        if (value == null) throw new IllegalArgumentException("Undeclared action setting: " + key);
        return value;
    }
    public double number(String key) { return ((BigDecimal) require(key)).doubleValue(); }
    public int integer(String key) { return ((BigDecimal) require(key)).intValueExact(); }
    public boolean bool(String key) { return (Boolean) require(key); }
    public String text(String key) { return (String) require(key); }
    @Override public boolean equals(Object other) { return other instanceof BossActionSettings settings && values.equals(settings.values); }
    @Override public int hashCode() { return values.hashCode(); }
}
