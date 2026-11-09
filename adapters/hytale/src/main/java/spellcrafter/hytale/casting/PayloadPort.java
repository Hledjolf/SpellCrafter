package spellcrafter.hytale.casting;

import java.util.List;

import spellcrafter.core.json.Json;

/**
 * Carries out one payload on one target. Shaped like BuffEffects' API
 * ({@code buffeffects.api.BuffEffects.apply(target, effect, ApplyOptions)} returning {@code ApplyResult}), which
 * carries every 1.0 payload. Until SpellCrafter is wired to it, {@link FakePayloads} stands in.
 */
public interface PayloadPort {
    /**
     * One application. Numbers are as the core planned them, converted from thousandths. {@code duration} and
     * {@code strength} are null when the spell doesn't set them (the effect's own default).
     */
    record Apply(String provider, String effect, String target, String actor, String sourceId, Double strength,
            Double durationSeconds, boolean suppressOnApply, Json messages) {
    }

    /** The result: applied, or refused with BuffEffects' code (such as {@code capped:...}) and message. */
    record Result(boolean applied, String code, String message) {
        public static Result ok() {
            return new Result(true, null, null);
        }

        /**
         * A refusal that refunds the cast when it hits every payload: a cap ({@code capped:...}, release-1.0.md 4a), or
         * the effect already on the target where the game ignores a recast ({@code already_active}, vanilla's
         * {@code Ignore}, such as {@code Slow}: release-1.0.md section 3, BFX-016), or an effect BuffEffects hasn't
         * loaded ({@code unknown_effect}: its definition was refused, as Shadow Bolt's and Magelight's are until BuffEffects
         * adds shadow damage and light; SC-015). Nothing happened, so nothing is spent.
         */
        public boolean refunds() {
            return !applied && code != null
                    && (code.startsWith("capped") || code.equals("already_active") || code.equals("unknown_effect"));
        }
    }

    Result apply(Apply a);

    /** A short name for logs. */
    String name();

    /**
     * Whether the provider has the effect loaded. False only when it's sure it hasn't (BuffEffects refused the
     * definition, as Shadow Bolt's until it has shadow damage): a cast made only of such effects is stopped before it
     * spends anything, so a bolt that misses doesn't use up its scroll (the author's fourth pass, 2026-11-08).
     */
    default boolean loaded(String effect) {
        return true;
    }

    /** The effect as its provider describes it (parts, duration, bounds), for the stat block; null if unknown. */
    default spellcrafter.core.editor.Effect describe(String effect) {
        return null;
    }

    /** Every effect id the provider lists (BuffEffects' {@code list}), for the editor's catalog; empty if unknown. */
    default java.util.List<String> list() {
        return java.util.List.of();
    }

    /** Whether every payload of a cast was refused by a cap or as already active: then the mana is refunded and the scroll kept. */
    static boolean refund(List<Result> results) {
        if (results.isEmpty()) return false;
        for (Result r : results) if (!r.refunds()) return false;
        return true;
    }
}
