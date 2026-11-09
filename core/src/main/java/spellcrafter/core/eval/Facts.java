package spellcrafter.core.eval;

import java.util.LinkedHashMap;
import java.util.Map;

import spellcrafter.core.model.Condition;

/**
 * The adapter's answers to the leaves the core asked for ({@link Evaluator#neededFacts}), keyed by
 * {@link Condition.Leaf#key()}. A leaf with no answer is UNKNOWN ("not answered"); the core never guesses.
 */
public record Facts(Map<String, Answer> answers) {

    /** One leaf's answer. {@code reason} explains an UNKNOWN for the tooltip ("BuffEffects isn't installed"). */
    public record Answer(Tri value, String reason) {
    }

    public Facts {
        answers = Map.copyOf(answers);
    }

    public static final Facts NONE = new Facts(Map.of());

    public Answer get(Condition.Leaf leaf) {
        Answer a = answers.get(leaf.key());
        return a != null ? a : new Answer(Tri.UNKNOWN, leaf.type() + ": not answered");
    }

    /** A small builder for adapters and tests. */
    public static final class Builder {
        private final Map<String, Answer> m = new LinkedHashMap<>();

        public Builder put(Condition.Leaf leaf, Tri value, String reason) {
            m.put(leaf.key(), new Answer(value, reason));
            return this;
        }

        public Builder put(String key, Tri value, String reason) {
            m.put(key, new Answer(value, reason));
            return this;
        }

        public Facts build() {
            return new Facts(m);
        }
    }
}
