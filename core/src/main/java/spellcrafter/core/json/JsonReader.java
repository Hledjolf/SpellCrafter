package spellcrafter.core.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A small strict JSON reader (RFC 8259), written by hand so every port reads the same way. */
final class JsonReader {
    private static final int MAX_DEPTH = 64;

    private final String s;
    private int pos;
    private String error;
    private int errorAt;

    JsonReader(String text) {
        this.s = text;
    }

    Json.Read document() {
        skipWs();
        Json v = value(0);
        if (v != null) {
            skipWs();
            if (pos != s.length()) fail("unexpected text after the document");
        }
        if (error != null) return new Json.Read.Error(error, errorAt);
        return new Json.Read.Ok(v);
    }

    private Json fail(String message) {
        if (error == null) {
            error = message;
            errorAt = pos;
        }
        return null;
    }

    private void skipWs() {
        while (pos < s.length()) {
            char c = s.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++;
            else break;
        }
    }

    private Json value(int depth) {
        if (depth > MAX_DEPTH) return fail("nested too deeply");
        if (pos >= s.length()) return fail("unexpected end of text");
        char c = s.charAt(pos);
        if (c == '{') return object(depth);
        if (c == '[') return array(depth);
        if (c == '"') {
            String str = string();
            return str == null ? null : new Json.Str(str);
        }
        if (c == '-' || (c >= '0' && c <= '9')) return number();
        if (s.startsWith("true", pos)) {
            pos += 4;
            return new Json.Bool(true);
        }
        if (s.startsWith("false", pos)) {
            pos += 5;
            return new Json.Bool(false);
        }
        if (s.startsWith("null", pos)) {
            pos += 4;
            return new Json.Null();
        }
        return fail("unexpected character '" + c + "'");
    }

    private Json object(int depth) {
        pos++;
        Map<String, Json> members = new LinkedHashMap<>();
        skipWs();
        if (pos < s.length() && s.charAt(pos) == '}') {
            pos++;
            return new Json.Obj(members);
        }
        while (true) {
            skipWs();
            if (pos >= s.length() || s.charAt(pos) != '"') return fail("expected a key");
            int keyAt = pos;
            String key = string();
            if (key == null) return null;
            if (members.containsKey(key)) {
                pos = keyAt;
                return fail("duplicate key \"" + key + "\"");
            }
            skipWs();
            if (pos >= s.length() || s.charAt(pos) != ':') return fail("expected ':'");
            pos++;
            skipWs();
            Json v = value(depth + 1);
            if (v == null) return null;
            members.put(key, v);
            skipWs();
            if (pos >= s.length()) return fail("unexpected end of text");
            char c = s.charAt(pos);
            pos++;
            if (c == '}') return new Json.Obj(members);
            if (c != ',') {
                pos--;
                return fail("expected ',' or '}'");
            }
        }
    }

    private Json array(int depth) {
        pos++;
        List<Json> items = new ArrayList<>();
        skipWs();
        if (pos < s.length() && s.charAt(pos) == ']') {
            pos++;
            return new Json.Arr(items);
        }
        while (true) {
            skipWs();
            Json v = value(depth + 1);
            if (v == null) return null;
            items.add(v);
            skipWs();
            if (pos >= s.length()) return fail("unexpected end of text");
            char c = s.charAt(pos);
            pos++;
            if (c == ']') return new Json.Arr(items);
            if (c != ',') {
                pos--;
                return fail("expected ',' or ']'");
            }
        }
    }

    private String string() {
        pos++;
        StringBuilder b = new StringBuilder();
        while (true) {
            if (pos >= s.length()) {
                fail("unterminated string");
                return null;
            }
            char c = s.charAt(pos++);
            if (c == '"') return b.toString();
            if (c < 0x20) {
                pos--;
                fail("control character in a string");
                return null;
            }
            if (c != '\\') {
                b.append(c);
                continue;
            }
            if (pos >= s.length()) {
                fail("unterminated string");
                return null;
            }
            char e = s.charAt(pos++);
            switch (e) {
                case '"': b.append('"'); break;
                case '\\': b.append('\\'); break;
                case '/': b.append('/'); break;
                case 'b': b.append('\b'); break;
                case 'f': b.append('\f'); break;
                case 'n': b.append('\n'); break;
                case 'r': b.append('\r'); break;
                case 't': b.append('\t'); break;
                case 'u': {
                    if (pos + 4 > s.length()) {
                        fail("bad \\u escape");
                        return null;
                    }
                    int code = 0;
                    for (int i = 0; i < 4; i++) {
                        int d = hex(s.charAt(pos + i));
                        if (d < 0) {
                            fail("bad \\u escape");
                            return null;
                        }
                        code = code * 16 + d;
                    }
                    pos += 4;
                    b.append((char) code);
                    break;
                }
                default:
                    pos--;
                    fail("bad escape '\\" + e + "'");
                    return null;
            }
        }
    }

    private static int hex(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }

    private Json number() {
        int start = pos;
        if (s.charAt(pos) == '-') pos++;
        if (pos >= s.length()) return fail("bad number");
        if (s.charAt(pos) == '0') {
            pos++;
        } else if (s.charAt(pos) >= '1' && s.charAt(pos) <= '9') {
            while (pos < s.length() && isDigit(s.charAt(pos))) pos++;
        } else {
            return fail("bad number");
        }
        if (pos < s.length() && s.charAt(pos) == '.') {
            pos++;
            int digits = pos;
            while (pos < s.length() && isDigit(s.charAt(pos))) pos++;
            if (pos == digits) return fail("bad number");
        }
        if (pos < s.length() && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
            pos++;
            if (pos < s.length() && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) pos++;
            int digits = pos;
            while (pos < s.length() && isDigit(s.charAt(pos))) pos++;
            if (pos == digits) return fail("bad number");
        }
        return new Json.Num(s.substring(start, pos));
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }
}
