package spellcrafter.core.eval;

import java.util.List;
import java.util.Map;

import spellcrafter.core.model.Model.Circumstance;
import spellcrafter.core.model.Model.Group;
import spellcrafter.core.model.Model.Profile;
import spellcrafter.core.model.Model.ServerSettings;
import spellcrafter.core.model.Model.Spell;
import spellcrafter.core.registry.Registries;

/**
 * Everything {@link Evaluator#evaluate} reads (design/spell-format.md, section 8). The adapter gathers it: the
 * circumstances in force for this caster and cast, the game's profile and registries, the server's settings, the
 * providers' bounds, and the facts.
 *
 * @param circumstances  in force for this cast, each with where it comes from. A vessel's riders arrive here, as
 *                       circumstances of kind {@code vessel}.
 * @param providerBounds per payload id (an added payload's id is {@code <rider id>/<payload id>}), per param: the
 *                       provider's bounds, such as BuffEffects' {@code caller_may_set} (section 7a)
 * @param tickFacts      null for a cast. On a recheck (section 11): fresh answers for items marked
 *                       {@code recheck: tick}, while every other item keeps its answer from {@code facts}
 */
public record CastInput(
        Spell spell,
        List<InForce> circumstances,
        List<Group> groups,
        Registries registries,
        Profile profile,
        ServerSettings settings,
        Map<String, Map<String, Bound>> providerBounds,
        Facts facts,
        Facts tickFacts) {

    public CastInput {
        circumstances = List.copyOf(circumstances);
        groups = List.copyOf(groups);
        providerBounds = Map.copyOf(providerBounds);
    }

    /** Where a circumstance in force comes from. The kind decides which are kept first when there are too many. */
    public record InForce(Circumstance circumstance, String kind) {
        /** Kinds in the order they're kept (section 10): vessel, worn, effect, place, server. */
        public static final List<String> KINDS = List.of("vessel", "worn", "effect", "place", "server");

        public int rank() {
            int i = KINDS.indexOf(kind);
            return i < 0 ? KINDS.size() : i;
        }
    }

    /** A provider's bounds on one param, in thousandths. Either may be null. */
    public record Bound(Long min, Long max) {
    }

    /** The same input, rechecked: {@code fresh} answers the items marked {@code recheck: tick}. */
    public CastInput recheck(Facts fresh) {
        return new CastInput(spell, circumstances, groups, registries, profile, settings, providerBounds, facts, fresh);
    }

    /** The same input with other facts. */
    public CastInput withFacts(Facts f) {
        return new CastInput(spell, circumstances, groups, registries, profile, settings, providerBounds, f, tickFacts);
    }
}
