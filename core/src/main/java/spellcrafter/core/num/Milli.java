package spellcrafter.core.num;

/**
 * The core's numbers (design/spell-format.md, section 2): integers only. Authored numbers have at most 3 decimals
 * and are stored as thousandths ("milli"); percentages are whole numbers. Both are bounded so every intermediate
 * product stays within ±2^53, where Lua's doubles are still exact.
 */
public final class Milli {
    /** The largest magnitude of a milli value: 10^11. */
    public static final long MAX_MILLI = 100_000_000_000L;
    /** The largest magnitude of a percentage: 10,000. */
    public static final long MAX_PCT = 10_000L;
    /** The largest magnitude of a whole number that isn't a milli value or a percentage (counts, ms): 2^53. */
    public static final long MAX_WHOLE = 9_007_199_254_740_992L;

    private Milli() {
    }

    /** A parsed number, or why it can't be one. */
    public record Parsed(long value, String problem) {
        public boolean ok() {
            return problem == null;
        }
    }

    /**
     * Parses a JSON number's text as thousandths. Trailing zeros don't count as decimals ({@code 1.2000} is fine);
     * {@code 1.2345} is {@code too_many_decimals}; beyond ±10^11 milli is {@code out_of_range}.
     */
    public static Parsed parseMilli(String text) {
        return parseScaled(text, 3, MAX_MILLI, "too_many_decimals");
    }

    /** Parses a whole percentage within ±10,000; a fraction is {@code not_whole}. */
    public static Parsed parsePct(String text) {
        return parseScaled(text, 0, MAX_PCT, "not_whole");
    }

    /** Parses a whole number within ±2^53; a fraction is {@code not_whole}. */
    public static Parsed parseWhole(String text) {
        return parseScaled(text, 0, MAX_WHOLE, "not_whole");
    }

    /** Parses {@code text} × 10^scale as an exact integer, within ±max. Works on the digits, never through floats. */
    static Parsed parseScaled(String text, int scale, long max, String fractionProblem) {
        boolean neg = text.startsWith("-");
        String t = neg ? text.substring(1) : text;
        int exp = 0;
        int e = t.indexOf('e');
        if (e < 0) e = t.indexOf('E');
        if (e >= 0) {
            String es = t.substring(e + 1);
            if (es.startsWith("+")) es = es.substring(1);
            boolean eneg = es.startsWith("-");
            if (eneg) es = es.substring(1);
            if (es.length() > 4) return new Parsed(0, "out_of_range");
            exp = Integer.parseInt(es);
            if (eneg) exp = -exp;
            t = t.substring(0, e);
        }
        int dot = t.indexOf('.');
        String intDigits = dot < 0 ? t : t.substring(0, dot);
        String fracDigits = dot < 0 ? "" : t.substring(dot + 1);
        String digits = intDigits + fracDigits;
        // value = digits × 10^(exp − fracDigits.length); scaled = digits × 10^(exp − frac + scale)
        int shift = exp - fracDigits.length() + scale;
        // Drop trailing zeros while the shift is negative (they aren't real decimals).
        int end = digits.length();
        while (shift < 0 && end > 0 && digits.charAt(end - 1) == '0') {
            end--;
            shift++;
        }
        digits = digits.substring(0, end);
        int start = 0;
        while (start < digits.length() && digits.charAt(start) == '0') start++;
        digits = digits.substring(start);
        if (digits.isEmpty()) return new Parsed(0, null);
        if (shift < 0) return new Parsed(0, fractionProblem);
        if (digits.length() + shift > 18) return new Parsed(0, "out_of_range");
        long v = 0;
        for (int i = 0; i < digits.length(); i++) v = v * 10 + (digits.charAt(i) - '0');
        for (int i = 0; i < shift; i++) v *= 10;
        if (v > max) return new Parsed(0, "out_of_range");
        return new Parsed(neg ? -v : v, null);
    }

    /** {@code div_round(q, d) = sign(q) × floor((|q| + d/2) / d)}: round half away from zero. {@code d} is positive. */
    public static long divRound(long q, long d) {
        long a = q < 0 ? -q : q;
        long r = (a + d / 2) / d;
        return q < 0 ? -r : r;
    }

    /** Thousandths as the shortest exact decimal text: 12500 is "12.5", -3 is "-0.003". */
    public static String format(long milli) {
        long a = milli < 0 ? -milli : milli;
        String s = Long.toString(a / 1000);
        long f = a % 1000;
        if (f != 0) {
            String fs = Long.toString(f);
            while (fs.length() < 3) fs = "0" + fs;
            while (fs.endsWith("0")) fs = fs.substring(0, fs.length() - 1);
            s = s + "." + fs;
        }
        return milli < 0 ? "-" + s : s;
    }
}
