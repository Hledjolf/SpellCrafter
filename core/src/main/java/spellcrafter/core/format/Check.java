package spellcrafter.core.format;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import spellcrafter.core.json.Json;
import spellcrafter.core.num.Milli;

/** Collects problems while a document is read. Each helper returns a usable value even when it records a problem. */
final class Check {
    final List<Problem> problems = new ArrayList<>();
    final List<Problem> warnings = new ArrayList<>();

    void add(String code, String pointer, String message) {
        problems.add(new Problem(code, pointer, message));
    }

    void warn(String code, String pointer, String message) {
        warnings.add(new Problem(code, pointer, message));
    }

    boolean ok() {
        return problems.isEmpty();
    }

    static String at(String pointer, String key) {
        StringBuilder b = new StringBuilder(pointer).append('/');
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c == '~') b.append("~0");
            else if (c == '/') b.append("~1");
            else b.append(c);
        }
        return b.toString();
    }

    static String at(String pointer, int index) {
        return pointer + "/" + index;
    }

    Json.Obj obj(Json v, String p) {
        if (v == null) return null; // missing: req() has reported it
        if (v instanceof Json.Obj o) return o;
        add("invalid", p, "expected an object, found " + Json.kind(v));
        return null;
    }

    /** Records unknown keys of {@code o}. */
    void keys(Json.Obj o, String p, Set<String> allowed) {
        for (String k : o.members().keySet()) {
            if (!allowed.contains(k)) add("unknown_key", at(p, k), "unknown key \"" + k + "\"");
        }
    }

    /** A required key; records {@code invalid} if missing. */
    Json req(Json.Obj o, String key, String p) {
        Json v = o.get(key);
        if (v == null) add("invalid", at(p, key), "\"" + key + "\" is required");
        return v;
    }

    String str(Json v, String p) {
        if (v == null) return null; // missing: req() has reported it
        if (v instanceof Json.Str s) return s.value();
        add("invalid", p, "expected a string, found " + Json.kind(v));
        return null;
    }

    String nonEmptyStr(Json v, String p) {
        String s = str(v, p);
        if (s != null && s.isEmpty()) {
            add("invalid", p, "must not be empty");
            return null;
        }
        return s;
    }

    Boolean bool(Json v, String p) {
        if (v == null) return null; // missing: req() has reported it
        if (v instanceof Json.Bool b) return b.value();
        add("invalid", p, "expected true or false, found " + Json.kind(v));
        return null;
    }

    List<Json> arr(Json v, String p) {
        if (v == null) return null; // missing: req() has reported it
        if (v instanceof Json.Arr a) return a.items();
        add("invalid", p, "expected an array, found " + Json.kind(v));
        return null;
    }

    List<String> strList(Json v, String p) {
        List<String> out = new ArrayList<>();
        List<Json> items = arr(v, p);
        if (items == null) return out;
        for (int i = 0; i < items.size(); i++) {
            String s = nonEmptyStr(items.get(i), at(p, i));
            if (s != null) out.add(s);
        }
        return out;
    }

    /** An optional list of strings: empty when absent. */
    List<String> optStrList(Json.Obj o, String key, String p) {
        Json v = o.get(key);
        return v == null ? new ArrayList<>() : strList(v, at(p, key));
    }

    Long milli(Json v, String p) {
        if (v == null) return null; // missing: req() has reported it
        if (!(v instanceof Json.Num n)) {
            add("invalid", p, "expected a number, found " + Json.kind(v));
            return null;
        }
        Milli.Parsed r = Milli.parseMilli(n.text());
        if (!r.ok()) {
            add(r.problem(), p, message(r.problem(), n.text()));
            return null;
        }
        return r.value();
    }

    Long pct(Json v, String p) {
        if (v == null) return null; // missing: req() has reported it
        if (!(v instanceof Json.Num n)) {
            add("invalid", p, "expected a whole percentage, found " + Json.kind(v));
            return null;
        }
        Milli.Parsed r = Milli.parsePct(n.text());
        if (!r.ok()) {
            add(r.problem(), p, message(r.problem(), n.text()));
            return null;
        }
        return r.value();
    }

    Long whole(Json v, String p) {
        if (v == null) return null; // missing: req() has reported it
        if (!(v instanceof Json.Num n)) {
            add("invalid", p, "expected a whole number, found " + Json.kind(v));
            return null;
        }
        Milli.Parsed r = Milli.parseWhole(n.text());
        if (!r.ok()) {
            add(r.problem(), p, message(r.problem(), n.text()));
            return null;
        }
        return r.value();
    }

    /** A map of names to milli numbers. */
    Map<String, Long> milliMap(Json v, String p) {
        Map<String, Long> out = new LinkedHashMap<>();
        Json.Obj o = obj(v, p);
        if (o == null) return out;
        for (Map.Entry<String, Json> e : o.members().entrySet()) {
            Long n = milli(e.getValue(), at(p, e.getKey()));
            if (n != null) out.put(e.getKey(), n);
        }
        return out;
    }

    /** A map of names to whole percentages. */
    Map<String, Long> pctMap(Json v, String p) {
        Map<String, Long> out = new LinkedHashMap<>();
        Json.Obj o = obj(v, p);
        if (o == null) return out;
        for (Map.Entry<String, Json> e : o.members().entrySet()) {
            Long n = pct(e.getValue(), at(p, e.getKey()));
            if (n != null) out.put(e.getKey(), n);
        }
        return out;
    }

    /** Checks every number anywhere inside {@code v} against the 3-decimal rule and the range. */
    void numbersIn(Json v, String p) {
        if (v instanceof Json.Num) {
            milli(v, p);
        } else if (v instanceof Json.Obj o) {
            for (Map.Entry<String, Json> e : o.members().entrySet()) numbersIn(e.getValue(), at(p, e.getKey()));
        } else if (v instanceof Json.Arr a) {
            for (int i = 0; i < a.items().size(); i++) numbersIn(a.items().get(i), at(p, i));
        }
    }

    private static String message(String problem, String text) {
        if (problem.equals("too_many_decimals")) return text + " has more than 3 decimals";
        if (problem.equals("not_whole")) return text + " must be a whole number";
        return text + " is out of range";
    }

    /** {@code <mod id>/<name>}: the mod id runs to the last '/', and the name is lower-case letters, digits, _ . - */
    static boolean qualifiedId(String id) {
        int slash = id.lastIndexOf('/');
        if (slash <= 0 || slash == id.length() - 1) return false;
        return localName(id.substring(slash + 1));
    }

    /** A local id: lower-case letters, digits, {@code _ . -}. */
    static boolean localName(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '.' || c == '-';
            if (!ok) return false;
        }
        return true;
    }

    /** A namespaced registry entry: {@code <namespace>:<name>}, both parts non-empty. */
    static boolean namespaced(String s) {
        int colon = s.indexOf(':');
        return colon > 0 && colon < s.length() - 1;
    }

    /** A registry name: {@code [a-z][a-z0-9_]*} (CommandDeck's conditions.md, "Registries"). */
    static boolean registryName(String s) {
        if (s.isEmpty() || s.charAt(0) < 'a' || s.charAt(0) > 'z') return false;
        for (int i = 1; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (!((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9') || ch == '_')) return false;
        }
        return true;
    }

    /** {@code MAJOR.MINOR}, both whole numbers. Returns the major, or -1. */
    static int major(String version) {
        int dot = version.indexOf('.');
        if (dot <= 0 || dot == version.length() - 1) return -1;
        String maj = version.substring(0, dot);
        String min = version.substring(dot + 1);
        if (!digits(maj) || !digits(min) || maj.length() > 6) return -1;
        return Integer.parseInt(maj);
    }

    private static boolean digits(String s) {
        if (s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) < '0' || s.charAt(i) > '9') return false;
        }
        return true;
    }
}
