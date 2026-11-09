package spellcrafter.core.power;

/**
 * Fixed-point reals for GTA's method: a {@code long} in billionths ({@link #ONE} = 1.0). The core has no floating
 * point (design/spell-format.md section 12), so logarithms and powers are computed here by series, to about 1e-9,
 * which is far finer than the method's printed precision (2 decimals for grades, 3 for costs).
 *
 * <p>Products and quotients are split so nothing overflows for magnitudes up to about 10^9 (a billion, in real
 * terms); GTA's quantities stay below 10^4.
 */
public final class Fixed {
    private Fixed() {
    }

    public static final long ONE = 1_000_000_000L;
    /** ln 2, rounded to billionths. */
    static final long LN2 = 693_147_181L;
    /** ln 1.25, by the same series as {@link #ln}, so a ratio of exactly 1.25 is exactly one step. */
    static final long LN_STEP = ln(1_250_000_000L);

    /** {@code n} as a fixed-point real. */
    public static long of(long n) {
        return n * ONE;
    }

    /** {@code num / den} as a fixed-point real, rounded. */
    public static long ratio(long num, long den) {
        return div(of(num), of(den));
    }

    /** A decimal given as {@code units / 10^decimals} (12.67 is {@code dec(1267, 2)}). */
    public static long dec(long units, int decimals) {
        long p = 1;
        for (int i = 0; i < decimals; i++) p *= 10;
        return div(of(units), of(p));
    }

    /** a × b, rounded half away from zero. */
    public static long mul(long a, long b) {
        boolean neg = (a < 0) != (b < 0);
        long x = Math.abs(a), y = Math.abs(b);
        long xh = x / ONE, xl = x % ONE, yh = y / ONE, yl = y % ONE;
        long r = xh * y + xl * yh + (xl * yl + ONE / 2) / ONE;
        return neg ? -r : r;
    }

    /** a ÷ b, rounded half away from zero. b must not be 0. */
    public static long div(long a, long b) {
        boolean neg = (a < 0) != (b < 0);
        long x = Math.abs(a), y = Math.abs(b);
        long q = x / y, r = x % y;
        // q * ONE + r * ONE / y, three decimal digits at a time so r * 1000 stays small.
        long frac = 0;
        for (int i = 0; i < 3; i++) {
            r *= 1000;
            frac = frac * 1000 + r / y;
            r %= y;
        }
        long out = q * ONE + frac + (2 * r >= y ? 1 : 0);
        return neg ? -out : out;
    }

    /** The natural logarithm of x (x > 0). */
    public static long ln(long x) {
        if (x <= 0) throw new IllegalArgumentException("ln of " + x);
        int k = 0;
        long m = x;
        while (m >= 2 * ONE) {
            m = (m + 1) / 2;
            k++;
        }
        while (m < ONE) {
            m *= 2;
            k--;
        }
        // ln m = 2 atanh(t), t = (m - 1) / (m + 1), 0 <= t < 1/3
        long t = div(m - ONE, m + ONE);
        long t2 = mul(t, t);
        long term = t;
        long sum = 0;
        for (int n = 1; n < 60 && term != 0; n += 2) {
            sum += term / n;
            term = mul(term, t2);
        }
        return 2 * sum + k * LN2;
    }

    /** e to the power y. */
    public static long exp(long y) {
        long k = Math.floorDiv(y, LN2);
        long r = y - k * LN2; // 0 <= r < ln 2
        long sum = ONE, term = ONE;
        for (int n = 1; n < 40 && term != 0; n++) {
            term = div(mul(term, r), of(n));
            sum += term;
        }
        if (k >= 0) {
            for (long i = 0; i < k; i++) sum *= 2;
        } else {
            for (long i = 0; i < -k; i++) sum = (sum + 1) / 2;
        }
        return sum;
    }

    /** x to the power a (x > 0). */
    public static long pow(long x, long a) {
        return exp(mul(a, ln(x)));
    }

    /** Steps: log base 1.25 of a ratio (GTA: one step is ×1.25). */
    public static long steps(long ratio) {
        return div(ln(ratio), LN_STEP);
    }

    /** The largest whole number at most x. */
    public static long floor(long x) {
        return Math.floorDiv(x, ONE);
    }

    /** x rounded to {@code decimals} places, half away from zero, as a whole count of 10^-decimals. */
    public static long round(long x, int decimals) {
        long unit = 1;
        for (int i = decimals; i < 9; i++) unit *= 10;
        long q = Math.abs(x) / unit, r = Math.abs(x) % unit;
        if (2 * r >= unit) q++;
        return x < 0 ? -q : q;
    }

    /** x rounded to a whole number, ties away from zero. */
    public static long roundHalfAway(long x) {
        return round(x, 0);
    }

    /** x rounded to a whole number, ties to even (as Python's {@code round}). */
    public static long roundHalfEven(long x) {
        long f = Math.floorDiv(x, ONE), r = x - f * ONE;
        if (2 * r > ONE || (2 * r == ONE && (f & 1) == 1)) f++;
        return f;
    }

    /** x rounded to {@code decimals} places, back as a fixed-point real. */
    public static long rounded(long x, int decimals) {
        long unit = 1;
        for (int i = decimals; i < 9; i++) unit *= 10;
        return round(x, decimals) * unit;
    }

    public static long min(long a, long b) {
        return a < b ? a : b;
    }

    public static long max(long a, long b) {
        return a > b ? a : b;
    }
}
