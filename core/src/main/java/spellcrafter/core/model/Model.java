package spellcrafter.core.model;

import java.util.List;
import java.util.Map;

import spellcrafter.core.json.Json;

/**
 * The parsed forms of SpellCrafter's documents (design/spell-format.md). All numbers are integers: params, caps and
 * times in thousandths (seconds become milliseconds), percentages whole. Lists and maps keep their authored order.
 */
public final class Model {
    private Model() {
    }

    /**
     * A spell (section 3). {@code castTime} and {@code cooldown} are in ms. {@code costMana} is the authored mana cost
     * (1.0, section 3); {@code bindings} is the game-specific looks, opaque to the core (null when absent).
     */
    public record Spell(
            String formatVersion,
            String id,
            String name,
            String originKind,
            String originFrom,
            List<String> categories,
            List<String> groups,
            long castTime,
            long cooldown,
            Delivery delivery,
            List<Component> components,
            List<Payload> payloads,
            List<Gate> gates,
            List<Rider> riders,
            Caps caps,
            Json.Obj provenance,
            long costMana,
            Json.Obj bindings,
            Map<String, String> uses,
            List<Pick> picks) {
    }

    /** A pick group (section 7b): each payload with {@code "pick": id} is one of its options; {@code rolls} per entity. */
    public record Pick(String id, long rolls) {
    }

    /**
     * How a spell reaches its target. {@code params} in thousandths ({@code burst.radius} among them). {@code burst} is
     * the burst's {@code affects} ({@code others}, {@code all}, {@code enemies}; section 7b), null for no burst.
     */
    public record Delivery(String kind, Map<String, Long> params, String burst) {
    }

    /**
     * Something a spell does, handed to its provider. {@code fields} holds every key as written (the provider's own
     * keys pass through untouched); {@code params} are the numbers riders may change, in thousandths.
     */
    public record Payload(String id, String provider, String op, Map<String, Long> params, List<String> tags,
            Json.Obj fields) {

        /** A pick group's reroll option (section 7b): no provider, only {@code pick}, {@code weight} and {@code reroll}. */
        public boolean isReroll() {
            return fields.has("reroll");
        }
    }

    /** Section 7b's payload fields, read from a payload as written (the validator has checked them). */
    public static final class Targeting {
        private Targeting() {
        }

        /** {@code target}, {@code caster} or {@code area}; the default is {@code caster} for a self spell, else {@code target}. */
        public static String target(Json.Obj fields, String deliveryKind) {
            if (fields != null && fields.get("target") instanceof Json.Str s) return s.value();
            return "self".equals(deliveryKind) ? "caster" : "target";
        }

        /** Whole percent, 1–100; 100 when absent. */
        public static long chance(Json.Obj fields) {
            return whole(fields, "chance", 100);
        }

        /** The pick group's id, or null. */
        public static String pick(Json.Obj fields) {
            return fields != null && fields.get("pick") instanceof Json.Str s ? s.value() : null;
        }

        /** The option's weight in its pick group; 1 when absent. */
        public static long weight(Json.Obj fields) {
            return whole(fields, "weight", 1);
        }

        /** How many more rolls a reroll option makes; 0 for a payload. */
        public static long reroll(Json.Obj fields) {
            return whole(fields, "reroll", 0);
        }

        private static long whole(Json.Obj fields, String key, long d) {
            if (fields == null || !(fields.get(key) instanceof Json.Num n)) return d;
            spellcrafter.core.num.Milli.Parsed m = spellcrafter.core.num.Milli.parseWhole(n.text());
            return m.problem() == null ? m.value() : d;
        }
    }

    /** A material component (section 7). Exactly one of {@code item} and {@code tag} is set. */
    public record Component(String id, String item, String tag, long count, boolean consumed) {
    }

    /**
     * A gate (section 5): must hold for the spell to be usable. {@code recheckEveryMs} is its own recheck interval
     * ({@code recheck_every}), null when not set.
     */
    public record Gate(String id, Condition when, boolean recheckTick, Long recheckEveryMs) {
    }

    /**
     * A rider (section 5), or the rider part of a circumstance. {@code when} is null only for a circumstance that is
     * always in force. {@code plus} is in thousandths; {@code bonusPct} and {@code morePct} are whole percentages.
     * Param names are a payload's param name, or {@code delivery.<name>}. {@code recheckEveryMs} as for {@link Gate}.
     */
    public record Rider(String id, Condition when, Map<String, Long> plus, Map<String, Long> bonusPct,
            Map<String, Long> morePct, List<Payload> add, List<String> onlyTags, boolean recheckTick,
            Long recheckEveryMs) {

        /** Whether the rider changes numbers or adds payloads at all. */
        public boolean modifies() {
            return !plus.isEmpty() || !bonusPct.isEmpty() || !morePct.isEmpty() || !add.isEmpty();
        }
    }

    /**
     * Caps (section 10). A null field is "not set". {@code params} are in thousandths; {@code moreEach} is the largest
     * single multiplier and {@code morePerParam} the most multipliers per param.
     */
    public record Caps(Map<String, Long> params, Long bonusPct, Long moreEach, Long morePerParam, Long addedPayloads) {
        public static final Caps NONE = new Caps(Map.of(), null, null, null, null);
    }

    /** A spell group (section 6.1). */
    public record Group(String id, String name, List<String> members, List<String> includeCategories,
            List<String> includeGroups, List<String> exclude) {
    }

    /** A circumstance (section 6.2): a rider, with gates, that reaches spells from outside. */
    public record Circumstance(String id, String name, AppliesTo appliesTo, List<Gate> gates, Rider rider) {
    }

    /** Which spells a circumstance reaches: any of {@code ids}, {@code categories}, {@code groups}, minus {@code exclude}. */
    public record AppliesTo(List<String> ids, List<String> categories, List<String> groups, List<String> exclude) {
    }

    /**
     * A registry file (CommandDeck's {@code hledjolf.conditions.registry}, section 4.2). {@code source} is who
     * supplied it (an adapter, a mod, the server): given by the loader, not written in the file.
     */
    public record Registry(String registry, String source, List<RegistryEntry> entries) {
    }

    /** One registry entry: its id and the categories it's in. */
    public record RegistryEntry(String id, List<String> in) {
    }

    /**
     * A combining profile (section 9). {@code stages} is the order of {@code flat}, {@code increase} and {@code more};
     * {@code unsupported} maps a rider field ({@code plus}, {@code bonus_pct}, {@code more_pct}) whose stage is left out
     * to {@code refuse} or to the stage it folds into.
     */
    public record Profile(String id, String gameVersion, List<String> stages, Map<String, String> unsupported) {
    }

    /** A server's settings: its caps and the operator's overrides (section 10). */
    public record ServerSettings(ServerCaps caps, Overrides overrides) {
    }

    /** The server's caps. {@code params} in thousandths; {@code recheckMinMs} in ms. */
    public record ServerCaps(Map<String, Long> params, long bonusPct, long moreEach, long morePerParam,
            long addedPayloads, long ridersPerCast, long circumstancesPerCast, long conditionDepth, long groupDepth,
            long recheckMinMs, long burstTargets) {
        /** The starting defaults (sections 7b and 10): a burst reaches at most 16 entities within 8 blocks. */
        public static final ServerCaps DEFAULTS = new ServerCaps(Map.of("delivery.burst.radius", 8000L), 200, 50, 2, 3,
                16, 8, 4, 3, 1000, 16);
    }

    /**
     * Operator overrides (section 10). Gate and component references are {@code <spell or circumstance id>#<id>}.
     */
    public record Overrides(List<String> ignoreGates, List<String> ignoreGateTypes, boolean ignoreUnknownGates,
            List<String> ignoreComponents, List<String> ignoreComponentItems, boolean ignoreFoci,
            boolean ignoreAllComponents, List<String> circumstancesOff, List<String> circumstanceModsOff) {
        public static final Overrides NONE = new Overrides(List.of(), List.of(), false, List.of(), List.of(), false,
                false, List.of(), List.of());
    }

    /**
     * A vessel (release-1.0.md section 2, {@code spellcrafter.vessels} 1.0): an item that holds one spell. {@code kind}
     * is {@code scroll}, {@code potion} or {@code wand}; {@code recipe} is null when the vessel has none;
     * {@code bindings} is the game-specific item, opaque to the core.
     */
    public record Vessel(String id, String kind, String spell, Recipe recipe, Json.Obj bindings) {
    }

    /** How a vessel is made: at {@code bench} (in its {@code category}), from {@code inputs}, making {@code output}. */
    public record Recipe(String bench, String category, List<RecipeInput> inputs, long output) {
    }

    /** One recipe input: a namespaced item id and how many. */
    public record RecipeInput(String item, long count) {
    }

    /** Server settings with the defaults and no overrides. */
    public static final ServerSettings DEFAULT_SETTINGS = new ServerSettings(ServerCaps.DEFAULTS, Overrides.NONE);
}
