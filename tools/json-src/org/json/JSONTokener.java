package org.json;

/**
 * Minimal but correct JSON reader, API-compatible with Android's JSONTokener
 * for the operations this project uses.
 */
public class JSONTokener {
    private final String in;
    private int pos;

    public JSONTokener(String in) {
        this.in = in == null ? "" : in;
    }

    public Object nextValue() throws JSONException {
        skipWhitespace();
        if (pos >= in.length()) {
            throw new JSONException("End of input at character " + pos);
        }
        char c = in.charAt(pos);
        switch (c) {
            case '{':
                pos++;
                return readObject();
            case '[':
                pos++;
                return readArray();
            case '"':
            case '\'':
                pos++;
                return nextString(c);
            default:
                return readLiteral();
        }
    }

    private void skipWhitespace() {
        while (pos < in.length()) {
            char c = in.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f') {
                pos++;
                continue;
            }
            // tolerate // and /* */ comments, like Android's tokener
            if (c == '/' && pos + 1 < in.length()) {
                char n = in.charAt(pos + 1);
                if (n == '/') {
                    pos += 2;
                    while (pos < in.length() && in.charAt(pos) != '\n') {
                        pos++;
                    }
                    continue;
                }
                if (n == '*') {
                    int end = in.indexOf("*/", pos + 2);
                    if (end < 0) {
                        pos = in.length();
                    } else {
                        pos = end + 2;
                    }
                    continue;
                }
            }
            break;
        }
    }

    public String nextString(char quote) throws JSONException {
        StringBuilder sb = new StringBuilder();
        while (pos < in.length()) {
            char c = in.charAt(pos++);
            if (c == quote) {
                return sb.toString();
            }
            if (c == '\\') {
                if (pos >= in.length()) {
                    throw new JSONException("Unterminated escape sequence");
                }
                char e = in.charAt(pos++);
                switch (e) {
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u':
                        if (pos + 4 > in.length()) {
                            throw new JSONException("Invalid unicode escape");
                        }
                        sb.append((char) Integer.parseInt(in.substring(pos, pos + 4), 16));
                        pos += 4;
                        break;
                    default: sb.append(e);
                }
                continue;
            }
            sb.append(c);
        }
        throw new JSONException("Unterminated string");
    }

    private Object readLiteral() throws JSONException {
        int start = pos;
        while (pos < in.length() && "{}[]/\\:,=;# \t\f\r\n".indexOf(in.charAt(pos)) < 0) {
            pos++;
        }
        String literal = in.substring(start, pos).trim();
        if (literal.isEmpty()) {
            throw new JSONException("Expected literal value at " + start);
        }
        if ("null".equalsIgnoreCase(literal)) {
            return JSONObject.NULL;
        }
        if ("true".equalsIgnoreCase(literal)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(literal)) {
            return Boolean.FALSE;
        }
        try {
            if (literal.indexOf('.') < 0 && literal.indexOf('e') < 0 && literal.indexOf('E') < 0) {
                long value = Long.parseLong(literal);
                if (value == (int) value) {
                    return Integer.valueOf((int) value);
                }
                return Long.valueOf(value);
            }
            return Double.valueOf(Double.parseDouble(literal));
        } catch (NumberFormatException ignored) {
            return literal;
        }
    }

    private JSONObject readObject() throws JSONException {
        JSONObject result = new JSONObject();
        skipWhitespace();
        if (pos < in.length() && in.charAt(pos) == '}') {
            pos++;
            return result;
        }
        while (true) {
            skipWhitespace();
            if (pos >= in.length()) {
                throw new JSONException("Unterminated object");
            }
            char q = in.charAt(pos);
            String name;
            if (q == '"' || q == '\'') {
                pos++;
                name = nextString(q);
            } else {
                Object bare = readLiteral();
                name = String.valueOf(bare);
            }
            skipWhitespace();
            if (pos >= in.length() || (in.charAt(pos) != ':' && in.charAt(pos) != '=')) {
                throw new JSONException("Expected ':' after " + name);
            }
            pos++;
            if (pos < in.length() && in.charAt(pos) == '>') {
                pos++;
            }
            result.put(name, nextValue());
            skipWhitespace();
            if (pos >= in.length()) {
                throw new JSONException("Unterminated object");
            }
            char sep = in.charAt(pos++);
            if (sep == '}') {
                return result;
            }
            if (sep != ',' && sep != ';') {
                throw new JSONException("Unterminated object at " + pos);
            }
        }
    }

    private JSONArray readArray() throws JSONException {
        JSONArray result = new JSONArray();
        skipWhitespace();
        if (pos < in.length() && in.charAt(pos) == ']') {
            pos++;
            return result;
        }
        while (true) {
            result.put(nextValue());
            skipWhitespace();
            if (pos >= in.length()) {
                throw new JSONException("Unterminated array");
            }
            char sep = in.charAt(pos++);
            if (sep == ']') {
                return result;
            }
            if (sep != ',' && sep != ';') {
                throw new JSONException("Unterminated array at " + pos);
            }
        }
    }
}
