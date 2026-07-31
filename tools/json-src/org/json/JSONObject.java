package org.json;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Test-harness stand-in for Android's org.json.JSONObject. */
public class JSONObject {
    public static final Object NULL = new Object() {
        @Override public boolean equals(Object o) { return o == this || o == null; }
        @Override public int hashCode() { return 0; }
        @Override public String toString() { return "null"; }
    };

    private final LinkedHashMap<String, Object> map = new LinkedHashMap<String, Object>();

    public JSONObject() {
    }

    public JSONObject(String source) throws JSONException {
        Object parsed = new JSONTokener(source).nextValue();
        if (!(parsed instanceof JSONObject)) {
            throw new JSONException("Value is not a JSONObject: " + parsed);
        }
        this.map.putAll(((JSONObject) parsed).map);
    }

    public JSONObject put(String name, Object value) throws JSONException {
        if (name == null) { throw new JSONException("Names must be non-null"); }
        if (value == null) { map.remove(name); return this; }
        map.put(name, value);
        return this;
    }

    public JSONObject put(String name, int value) throws JSONException { return put(name, Integer.valueOf(value)); }
    public JSONObject put(String name, long value) throws JSONException { return put(name, Long.valueOf(value)); }
    public JSONObject put(String name, double value) throws JSONException { return put(name, Double.valueOf(value)); }
    public JSONObject put(String name, boolean value) throws JSONException { return put(name, Boolean.valueOf(value)); }

    public boolean has(String name) { return map.containsKey(name); }
    public Object remove(String name) { return map.remove(name); }
    public int length() { return map.size(); }
    public Iterator<String> keys() { return map.keySet().iterator(); }

    public Object opt(String name) { return name == null ? null : map.get(name); }

    public Object get(String name) throws JSONException {
        Object value = opt(name);
        if (value == null) { throw new JSONException("No value for " + name); }
        return value;
    }

    public String optString(String name) { return optString(name, ""); }

    public String optString(String name, String fallback) {
        Object value = opt(name);
        if (value == null || value == NULL) { return fallback; }
        if (value instanceof String) { return (String) value; }
        return String.valueOf(value);
    }

    public String getString(String name) throws JSONException {
        Object value = get(name);
        if (value instanceof String) { return (String) value; }
        if (value == NULL) { throw new JSONException(name + " is null"); }
        return String.valueOf(value);
    }

    public int optInt(String name, int fallback) {
        Object value = opt(name);
        if (value instanceof Number) { return ((Number) value).intValue(); }
        if (value instanceof String) {
            try { return (int) Double.parseDouble((String) value); } catch (Exception ignored) { }
        }
        return fallback;
    }

    public int optInt(String name) { return optInt(name, 0); }

    public int getInt(String name) throws JSONException {
        Object value = get(name);
        if (value instanceof Number) { return ((Number) value).intValue(); }
        if (value instanceof String) {
            try { return (int) Double.parseDouble((String) value); } catch (Exception ignored) { }
        }
        throw new JSONException(name + " is not an int");
    }

    public long optLong(String name, long fallback) {
        Object value = opt(name);
        if (value instanceof Number) { return ((Number) value).longValue(); }
        if (value instanceof String) {
            try { return (long) Double.parseDouble((String) value); } catch (Exception ignored) { }
        }
        return fallback;
    }

    public long optLong(String name) { return optLong(name, 0L); }

    public long getLong(String name) throws JSONException {
        Object value = get(name);
        if (value instanceof Number) { return ((Number) value).longValue(); }
        throw new JSONException(name + " is not a long");
    }

    public double optDouble(String name, double fallback) {
        Object value = opt(name);
        if (value instanceof Number) { return ((Number) value).doubleValue(); }
        return fallback;
    }

    public boolean optBoolean(String name, boolean fallback) {
        Object value = opt(name);
        if (value instanceof Boolean) { return ((Boolean) value).booleanValue(); }
        if (value instanceof String) {
            String s = (String) value;
            if ("true".equalsIgnoreCase(s)) { return true; }
            if ("false".equalsIgnoreCase(s)) { return false; }
        }
        return fallback;
    }

    public boolean optBoolean(String name) { return optBoolean(name, false); }

    public boolean getBoolean(String name) throws JSONException {
        Object value = get(name);
        if (value instanceof Boolean) { return ((Boolean) value).booleanValue(); }
        throw new JSONException(name + " is not a boolean");
    }

    public JSONObject optJSONObject(String name) {
        Object value = opt(name);
        return value instanceof JSONObject ? (JSONObject) value : null;
    }

    public JSONObject getJSONObject(String name) throws JSONException {
        Object value = get(name);
        if (value instanceof JSONObject) { return (JSONObject) value; }
        throw new JSONException(name + " is not a JSONObject");
    }

    public JSONArray optJSONArray(String name) {
        Object value = opt(name);
        return value instanceof JSONArray ? (JSONArray) value : null;
    }

    public JSONArray getJSONArray(String name) throws JSONException {
        Object value = get(name);
        if (value instanceof JSONArray) { return (JSONArray) value; }
        throw new JSONException(name + " is not a JSONArray");
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) { sb.append(','); }
            first = false;
            sb.append(quote(e.getKey())).append(':').append(encode(e.getValue()));
        }
        return sb.append('}').toString();
    }

    static String encode(Object value) {
        if (value == null || value == NULL) { return "null"; }
        if (value instanceof JSONObject || value instanceof JSONArray) { return value.toString(); }
        if (value instanceof Boolean) { return value.toString(); }
        if (value instanceof Number) {
            double d = ((Number) value).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) { return "null"; }
            if (value instanceof Integer || value instanceof Long) { return value.toString(); }
            if (d == Math.rint(d) && Math.abs(d) < 1e15) {
                long asLong = (long) d;
                return Double.toString(d).endsWith(".0") ? Long.toString(asLong) : value.toString();
            }
            return value.toString();
        }
        return quote(String.valueOf(value));
    }

    static String quote(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 2);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", Integer.valueOf(c)));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.append('"').toString();
    }
}
