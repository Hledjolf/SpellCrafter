package spellcrafter.core.eval;

/** A condition's answer (design/spell-format.md, section 4.1), with Kleene logic. */
public enum Tri {
    TRUE, FALSE, UNKNOWN;

    /** {@code all}: FALSE if either is FALSE, else UNKNOWN if either is UNKNOWN, else TRUE. */
    public Tri and(Tri o) {
        if (this == FALSE || o == FALSE) return FALSE;
        if (this == UNKNOWN || o == UNKNOWN) return UNKNOWN;
        return TRUE;
    }

    /** {@code any}: TRUE if either is TRUE, else UNKNOWN if either is UNKNOWN, else FALSE. */
    public Tri or(Tri o) {
        if (this == TRUE || o == TRUE) return TRUE;
        if (this == UNKNOWN || o == UNKNOWN) return UNKNOWN;
        return FALSE;
    }

    /** {@code not}: swaps TRUE and FALSE, keeps UNKNOWN. */
    public Tri not() {
        if (this == TRUE) return FALSE;
        if (this == FALSE) return TRUE;
        return UNKNOWN;
    }
}
