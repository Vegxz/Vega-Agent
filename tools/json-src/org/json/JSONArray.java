package org.json;

import java.util.ArrayList;

/** Test-harness stand-in for Android's org.json.JSONArray. */
public class JSONArray {
    private final ArrayList<Object> values = new ArrayList<Object>();

    public JSONArray() {
    }

    public JSONArray(String source) throws JSONException {
        Object parsed = new JSONTokener(source).nextValue();
        if (!(parsed instanceof JSONArray)) {
            throw new JSONException("Value is not a JSONArray: " + parsed);
        }
        this.values.addAll(((JSONArray) parsed).values);
    }

    public JSONArray put(Object value) { values.add(value); return this; }
    public JSONArray put(int value) { values.add(Integer.valueOf(value)); return this; }
    public JSONArray put(long value) { values.add(Long.valueOf(value)); return this; }
    public JSONArray put(boolean value) { values.add(Boolean.valueOf(value)); return this; }

    public int length() { return values.size(); }

    public Object opt(int index) {
        return (index < 0 || index >= values.size()) ? null : values.get(index);
    }

    public Object get(int index) throws JSONException {
        Object value = opt(index);
        if (value == null) { throw new JSONException("No value at " + index); }
        return value;
    }

    public String optString(int index) { return optString(index, ""); }

    public String optString(int index, String fallback) {
        Object value = opt(index);
        if (value == null || value == JSONObject.NULL) { return fallback; }
        return value instanceof String ? (String) value : String.valueOf(value);
    }

    public String getString(int index) throws JSONException {
        Object value = get(index);
        if (value instanceof String) { return (String) value; }
        return String.valueOf(value);
    }

    public int optInt(int index, int fallback) {
        Object value = opt(index);
        return value instanceof Number ? ((Number) value).intValue() : fallback;
    }

    public int getInt(int index) throws JSONException {
        Object value = get(index);
        if (value instanceof Number) { return ((Number) value).intValue(); }
        throw new JSONException(index + " is not an int");
    }

    public long optLong(int index, long fallback) {
        Object value = opt(index);
        return value instanceof Number ? ((Number) value).longValue() : fallback;
    }

    public long optLong(int index) { return optLong(index, 0L); }

    public boolean optBoolean(int index, boolean fallback) {
        Object value = opt(index);
        return value instanceof Boolean ? ((Boolean) value).booleanValue() : fallback;
    }

    public JSONObject optJSONObject(int index) {
        Object value = opt(index);
        return value instanceof JSONObject ? (JSONObject) value : null;
    }

    public JSONObject getJSONObject(int index) throws JSONException {
        Object value = get(index);
        if (value instanceof JSONObject) { return (JSONObject) value; }
        throw new JSONException(index + " is not a JSONObject");
    }

    public JSONArray optJSONArray(int index) {
        Object value = opt(index);
        return value instanceof JSONArray ? (JSONArray) value : null;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) { sb.append(','); }
            sb.append(JSONObject.encode(values.get(i)));
        }
        return sb.append(']').toString();
    }
}
