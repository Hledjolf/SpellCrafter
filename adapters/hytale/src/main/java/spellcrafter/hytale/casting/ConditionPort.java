package spellcrafter.hytale.casting;

import spellcrafter.core.eval.Tri;

/**
 * Answers one condition leaf for a cast. Shaped like CommandDeck's condition library
 * ({@code hledjolf.conditions.api.Conditions.evaluate(conditionJson, Context(actor, target, ...))}), which answers
 * it in 1.0. Until that library ships, {@link FakeConditions} stands in.
 */
public interface ConditionPort {
    /** The answer and, for an UNKNOWN, why. */
    record Answer(Tri value, String reason) {
    }

    /**
     * @param leafJson the leaf's canonical JSON (the core's {@code Condition.Leaf.key()})
     * @param actor    the caster's id
     * @param target   the target's id, or null before there is one
     */
    Answer answer(String leafJson, String actor, String target);

    /** A short name for logs: which library answers. */
    String name();
}
