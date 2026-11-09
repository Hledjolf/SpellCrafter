package spellcrafter.core.json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Writes JSON. {@link #canonical} gives one fixed text per value (keys sorted by code unit, no spaces, numbers
 * normalised), so a condition leaf has the same key in every port.
 */
public final class JsonWriter {
    private JsonWriter() {
    }

    /** Compact JSON in the value's own key order. */
    public static String write(Json v) {
        StringBuilder b = new StringBuilder();
        write(v, b, false);
        return b.toString();
    }

    /** Compact JSON with object keys sorted, and numbers in their shortest exact form. */
    public static String canonical(Json v) {
        StringBuilder b = new StringBuilder();
        write(v, b, true);
        return b.toString();
    }

    private static void write(Json v, StringBuilder b, boolean canonical) {
        if (v instanceof Json.Obj o) {
            List<String> keys = new ArrayList<>(o.members().keySet());
            if (canonical) keys.sort(null);
            b.append('{');
            boolean first = true;
            for (String k : keys) {
                if (!first) b.append(',');
                first = false;
                string(k, b);
                b.append(':');
                write(o.members().get(k), b, canonical);
            }
            b.append('}');
        } else if (v instanceof Json.Arr a) {
            b.append('[');
            for (int i = 0; i < a.items().size(); i++) {
                if (i > 0) b.append(',');
                write(a.items().get(i), b, canonical);
            }
            b.append(']');
        } else if (v instanceof Json.Str s) {
            string(s.value(), b);
        } else if (v instanceof Json.Num n) {
            b.append(canonical ? normalNumber(n.text()) : n.text());
        } else if (v instanceof Json.Bool bo) {
            b.append(bo.value() ? "true" : "false");
        } else {
            b.append("null");
        }
    }

    /** Builds an object from alternating keys and values. */
    public static Json.Obj obj(Object... keysAndValues) {
        Map<String, Json> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            m.put((String) keysAndValues[i], (Json) keysAndValues[i + 1]);
        }
        return new Json.Obj(m);
    }

    static void string(String s, StringBuilder b) {
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') b.append("\\\"");
            else if (c == '\\') b.append("\\\\");
            else if (c == '\n') b.append("\\n");
            else if (c == '\r') b.append("\\r");
            else if (c == '\t') b.append("\\t");
            else if (c < 0x20) {
                String h = Integer.toHexString(c);
                b.append("\\u");
                for (int p = h.length(); p < 4; p++) b.append('0');
                b.append(h);
            } else b.append(c);
        }
        b.append('"');
    }

    /**
     * The shortest exact decimal text of a JSON number: no exponent, no trailing fractional zeros, no "-0".
     * Exact for any value; it works on the digits, never through floating point.
     */
    static String normalNumber(String text) {
        boolean neg = text.startsWith("-");
        String t = neg ? text.substring(1) : text;
        int exp = 0;
        int e = t.indexOf('e');
        if (e < 0) e = t.indexOf('E');
        if (e >= 0) {
            exp = Integer.parseInt(t.substring(e + 1).startsWith("+") ? t.substring(e + 2) : t.substring(e + 1));
            t = t.substring(0, e);
        }
        int dot = t.indexOf('.');
        String digits = dot < 0 ? t : t.substring(0, dot) + t.substring(dot + 1);
        int pointAt = (dot < 0 ? t.length() : dot) + exp;
        // Pad so the point falls inside or at the end of the digits.
        StringBuilder d = new StringBuilder(digits);
        while (pointAt < 0) {
            d.insert(0, '0');
            pointAt++;
        }
        while (pointAt > d.length()) d.append('0');
        String intPart = d.substring(0, pointAt);
        String frac = d.substring(pointAt);
        int i = 0;
        while (i < intPart.length() - 1 && intPart.charAt(i) == '0') i++;
        intPart = intPart.isEmpty() ? "0" : intPart.substring(i);
        int j = frac.length();
        while (j > 0 && frac.charAt(j - 1) == '0') j--;
        frac = frac.substring(0, j);
        String out = frac.isEmpty() ? intPart : intPart + "." + frac;
        if (neg && !out.equals("0")) out = "-" + out;
        return out;
    }
}
