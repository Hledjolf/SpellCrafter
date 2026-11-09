package spellcrafter.core.model;

import java.util.List;

import spellcrafter.core.json.Json;

/**
 * A condition in CommandDeck's format (conditions 1.0; design/spell-format.md, section 4.1). The core never
 * answers a leaf itself: it lists the leaves it needs and takes TRUE, FALSE or UNKNOWN back, keyed by
 * {@link Leaf#key()}.
 */
public sealed interface Condition permits Condition.All, Condition.Any, Condition.Not, Condition.Leaf {

    record All(List<Condition> of) implements Condition {
        public All {
            of = List.copyOf(of);
        }
    }

    record Any(List<Condition> of) implements Condition {
        public Any {
            of = List.copyOf(of);
        }
    }

    record Not(Condition of) implements Condition {
    }

    /**
     * One check: {@code type} and its fields, kept as written. {@code key} is the leaf's canonical JSON (sorted keys,
     * normalised numbers), the same text in every port, and is how answers are matched to leaves.
     */
    record Leaf(String type, Json.Obj json, String key) implements Condition {
    }
}
