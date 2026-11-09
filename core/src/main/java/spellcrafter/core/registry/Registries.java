package spellcrafter.core.registry;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import spellcrafter.core.eval.Tri;
import spellcrafter.core.model.Model.Registry;
import spellcrafter.core.model.Model.RegistryEntry;

/**
 * Registries as a mechanism (design/spell-format.md, section 4.2): open lists that games, mods and servers add to.
 * An entry lists the categories it's in. Several files may give the same id: their memberships are joined.
 * ({@code same_as} is reserved for the equivalence pass: CommandDeck's 1.0 reader refuses it, and so does the core.) The core hard-codes nothing but the {@code core:} weather seed set.
 *
 * <p>The same answer serves conditions (whether this weather is in {@code core:rain}) and spells (whether a spell's
 * {@code spellcrafter:lightning} is in {@code spellcrafter:evocation}).
 */
public final class Registries {
    /** The weather seed set every game maps onto. Known ids, in no category but their own. */
    public static final List<String> CORE_WEATHER = List.of("core:clear", "core:precipitation", "core:rain",
            "core:snow", "core:storm", "core:fog");

    private final Map<String, Map<String, Merged>> byRegistry = new LinkedHashMap<>();

    private static final class Merged {
        final Set<String> in = new LinkedHashSet<>();
        final Set<String> sources = new LinkedHashSet<>();
    }

    /** One id's line in the vocabulary report: everything it's in (directly or not), and where it came from. */
    public record ReportLine(String id, List<String> in, List<String> sources) {
    }

    public Registries(List<Registry> files) {
        for (Registry f : files) {
            Map<String, Merged> m = byRegistry.computeIfAbsent(f.registry(), k -> new LinkedHashMap<>());
            for (RegistryEntry e : f.entries()) {
                Merged me = m.computeIfAbsent(e.id(), k -> new Merged());
                me.in.addAll(e.in());
                me.sources.add(f.source());
            }
        }
    }

    public static final Registries EMPTY = new Registries(List.of());

    /** Whether the registry knows {@code id}: it has an entry, or (for weather) it's in the seed set. */
    public boolean knows(String registry, String id) {
        Map<String, Merged> m = byRegistry.get(registry);
        if (m != null && m.containsKey(id)) return true;
        return registry.equals("weather") && CORE_WEATHER.contains(id);
    }

    /**
     * Everything {@code id} is in, including itself: its own categories, and theirs in turn. Sorted, so every port
     * lists them alike.
     */
    public List<String> closure(String registry, String id) {
        Map<String, Merged> m = byRegistry.getOrDefault(registry, Map.of());
        Set<String> seen = new LinkedHashSet<>();
        Deque<String> todo = new ArrayDeque<>();
        todo.add(id);
        while (!todo.isEmpty()) {
            String cur = todo.poll();
            if (!seen.add(cur)) continue;
            Merged me = m.get(cur);
            if (me != null) todo.addAll(me.in);
        }
        List<String> out = new ArrayList<>(seen);
        out.sort(null);
        return out;
    }

    /**
     * Is {@code id} in {@code category}?
     * <ul>
     * <li>TRUE if the category is the id itself, or in the id's closure.</li>
     * <li>UNKNOWN if the id isn't known (no entry, not a seed): never guessed.</li>
     * <li>FALSE if some known entry is in that category, so this registry can tell it apart.</li>
     * <li>UNKNOWN otherwise: nothing here ever maps into that category (Necesse has no storms), so this game
     * can't say.</li>
     * </ul>
     */
    public Tri member(String registry, String id, String category) {
        if (id.equals(category)) return Tri.TRUE;
        if (!knows(registry, id)) return Tri.UNKNOWN;
        if (closure(registry, id).contains(category)) return Tri.TRUE;
        Map<String, Merged> m = byRegistry.getOrDefault(registry, Map.of());
        for (String other : m.keySet()) {
            if (!other.equals(category) && closure(registry, other).contains(category)) return Tri.FALSE;
        }
        return Tri.UNKNOWN;
    }

    /** {@code id} is in any of the categories: Kleene "any" over {@link #member}. */
    public Tri memberOfAny(String registry, String id, List<String> categories) {
        Tri out = Tri.FALSE;
        for (String c : categories) out = out.or(member(registry, id, c));
        return out;
    }

    /** The vocabulary report for one registry: every id, sorted, with its full closure and its sources. */
    public List<ReportLine> report(String registry) {
        Map<String, Merged> m = byRegistry.getOrDefault(registry, Map.of());
        TreeMap<String, Merged> sorted = new TreeMap<>(m);
        List<ReportLine> out = new ArrayList<>();
        for (Map.Entry<String, Merged> e : sorted.entrySet()) {
            List<String> in = new ArrayList<>(closure(registry, e.getKey()));
            in.remove(e.getKey());
            out.add(new ReportLine(e.getKey(), in, new ArrayList<>(e.getValue().sources)));
        }
        return out;
    }
}
