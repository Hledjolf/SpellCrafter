package spellcrafter.core.eval;

/**
 * The core's random generator (design/spell-format.md 7b): Marsaglia's xorshift32, shifts 13, 17, 5. Shifts and
 * exclusive-or on 32 bits only, so every port gets the same numbers. Values are unsigned, held in a {@code long}.
 */
public final class Xorshift32 {
    private static final long MASK = 0xFFFFFFFFL;
    /** The golden-ratio constant that separates each entity's stream, and stands in for a zero seed. */
    public static final long GOLDEN = 0x9E3779B9L;

    private long x;

    /** A generator from {@code seed} (its low 32 bits; 0 becomes {@link #GOLDEN}). */
    public Xorshift32(long seed) {
        x = seed & MASK;
        if (x == 0) x = GOLDEN;
    }

    /** The next value, 1 to 2^32 − 1. */
    public long next() {
        x ^= (x << 13) & MASK;
        x ^= x >>> 17;
        x ^= (x << 5) & MASK;
        return x;
    }

    /** Entity {@code i}'s stream: {@code seed XOR ((0x9E3779B9 × (i + 1)) mod 2^32)}, or {@link #GOLDEN} if that's 0. */
    public static long streamSeed(long seed, long i) {
        long s = ((seed & MASK) ^ ((GOLDEN * (i + 1)) & MASK)) & MASK;
        return s == 0 ? GOLDEN : s;
    }

    /** A generator for entity {@code i}'s stream. */
    public static Xorshift32 forEntity(long seed, long i) {
        return new Xorshift32(streamSeed(seed, i));
    }
}
