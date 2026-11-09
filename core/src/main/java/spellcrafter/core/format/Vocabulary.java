package spellcrafter.core.format;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The condition types a spell may use without a namespace (design/spell-format.md, section 4.1). A type with no dot
 * that isn't listed is refused at load; a namespaced type ({@code buffeffects.has_effect}) is always accepted, and is
 * UNKNOWN at evaluation if nothing answers it.
 */
public record Vocabulary(String version, Set<String> types) {

    public Vocabulary {
        types = Set.copyOf(types);
    }

    /** The combinators, which are part of every vocabulary. */
    public static final List<String> LOGIC = List.of("all", "any", "not");

    /**
     * CommandDeck's conditions 1.0 (CommandDeck {@code specs\conditions.md}), less {@code counter}, which is local to a
     * macro run and means nothing in a spell.
     */
    public static final Vocabulary COMMANDDECK_1_0 = new Vocabulary("1.0", Set.of(
            "item_count", "stat", "health", "time_of_day", "distance", "in_area", "player_online", "is_night",
            "weather", "zone", "in_fluid", "near_fluid", "near_entity", "wielding", "wearing"));

    public boolean knows(String type) {
        return types.contains(type);
    }

    /** The types in sorted order, for messages. */
    public List<String> sorted() {
        return List.copyOf(new TreeSet<>(types));
    }
}
