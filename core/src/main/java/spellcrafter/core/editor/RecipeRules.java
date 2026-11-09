package spellcrafter.core.editor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import spellcrafter.core.format.Parsed;
import spellcrafter.core.format.Problem;
import spellcrafter.core.json.Json;
import spellcrafter.core.json.JsonWriter;
import spellcrafter.core.num.Milli;

/**
 * How the editor writes a vessel's recipe (design/editor.md 5a, item 8; release-1.0.md section 2's table): by vessel
 * kind and tier, the tier from the spell's zone, the school from its first {@code spellcrafter:} category the table
 * knows. Game data, so it's a file per game ({@code spellcrafter.recipe_rules} 1.0, in the adapter), not code.
 *
 * <p>An input's {@code item} is a game id, a list of ids by tier, or {@code "essence"} / {@code "crystal"} for the
 * school's; its {@code count} is a number or a list by tier. A count of 0 leaves the input out, and the same item
 * twice is one input with the counts added (a water-school potion's water).
 */
public final class RecipeRules {

    public static final String CATEGORY_PREFIX = "spellcrafter:";

    private record In(List<String> items, List<Long> counts) {
    }

    private record Kind(String bench, String category, long output, List<In> inputs) {
    }

    private final Map<String, Integer> tiers;
    private final Map<String, String[]> schools;
    private final String defaultSchool;
    private final Map<String, Kind> kinds;

    private RecipeRules(Map<String, Integer> tiers, Map<String, String[]> schools, String defaultSchool,
            Map<String, Kind> kinds) {
        this.tiers = tiers;
        this.schools = schools;
        this.defaultSchool = defaultSchool;
        this.kinds = kinds;
    }

    /** The school a spell's categories name: the first {@code spellcrafter:<school>} the table knows, else the default. */
    public String school(List<String> categories) {
        for (String c : categories) {
            if (c.startsWith(CATEGORY_PREFIX) && schools.containsKey(c.substring(CATEGORY_PREFIX.length()))) {
                return c.substring(CATEGORY_PREFIX.length());
            }
        }
        return defaultSchool;
    }

    /** The recipe for a {@code kind} vessel of a spell placed in {@code zone}; null when the zone has no tier (above 4). */
    public Json.Obj recipe(String kind, String zone, List<String> categories) {
        Kind k = kinds.get(kind);
        Integer t = zone == null ? null : tiers.get(zone);
        if (k == null || t == null) return null;
        String[] school = schools.get(school(categories));
        Map<String, Long> inputs = new LinkedHashMap<>();
        for (In in : k.inputs()) {
            String item = in.items().size() == 1 ? in.items().get(0) : in.items().get(t);
            if (item.equals("essence")) item = school[0];
            else if (item.equals("crystal")) item = school[1];
            long n = in.counts().size() == 1 ? in.counts().get(0) : in.counts().get(t);
            if (n > 0) inputs.merge(item, n, Long::sum);
        }
        List<Json> list = new ArrayList<>();
        for (Map.Entry<String, Long> e : inputs.entrySet()) {
            list.add(JsonWriter.obj("item", new Json.Str(e.getKey()), "count", new Json.Num(Long.toString(e.getValue()))));
        }
        return JsonWriter.obj("bench", new Json.Str(k.bench()), "category", new Json.Str(k.category()),
                "inputs", new Json.Arr(list), "output", new Json.Num(Long.toString(k.output())));
    }

    /** Reads a {@code spellcrafter.recipe_rules} 1.0 file. */
    public static Parsed<RecipeRules> parse(Json doc) {
        List<Problem> problems = new ArrayList<>();
        if (!(doc instanceof Json.Obj o)) return bad("", "a recipe rules file is an object");
        if (!(o.get("format") instanceof Json.Str f) || !f.value().equals("spellcrafter.recipe_rules")) {
            problems.add(new Problem("unsupported_format", "/format", "expected \"spellcrafter.recipe_rules\""));
        }
        if (!(o.get("format_version") instanceof Json.Str v) || !v.value().startsWith("1.")) {
            problems.add(new Problem("unsupported_version", "/format_version", "this core reads major version 1"));
        }
        Map<String, Integer> tiers = new LinkedHashMap<>();
        int nTiers = 0;
        if (o.get("tiers") instanceof Json.Obj t) {
            for (Map.Entry<String, Json> e : t.members().entrySet()) {
                Long n = whole(e.getValue());
                if (n == null || n > 64) problems.add(new Problem("invalid", "/tiers/" + e.getKey(), "a tier number from 0"));
                else {
                    tiers.put(e.getKey(), n.intValue());
                    nTiers = Math.max(nTiers, n.intValue() + 1);
                }
            }
        } else {
            problems.add(new Problem("invalid", "/tiers", "an object of zone: tier"));
        }
        Map<String, String[]> schools = new LinkedHashMap<>();
        if (o.get("schools") instanceof Json.Obj s) {
            for (Map.Entry<String, Json> e : s.members().entrySet()) {
                Json.Obj so = e.getValue() instanceof Json.Obj x ? x : null;
                String ess = so == null ? null : str(so, "essence");
                String cry = so == null ? null : str(so, "crystal");
                if (ess == null || cry == null) {
                    problems.add(new Problem("invalid", "/schools/" + e.getKey(), "a school has an essence and a crystal"));
                } else {
                    schools.put(e.getKey(), new String[] {ess, cry});
                }
            }
        } else {
            problems.add(new Problem("invalid", "/schools", "an object of school: {essence, crystal}"));
        }
        String def = str(o, "default_school");
        if (def == null || !schools.containsKey(def)) {
            problems.add(new Problem("invalid", "/default_school", "one of the schools"));
        }
        Map<String, Kind> kinds = new LinkedHashMap<>();
        if (o.get("kinds") instanceof Json.Obj ks) {
            for (Map.Entry<String, Json> e : ks.members().entrySet()) {
                String p = "/kinds/" + e.getKey();
                if (!Template.VESSEL_KINDS.contains(e.getKey()) || !(e.getValue() instanceof Json.Obj k)) {
                    problems.add(new Problem("invalid", p, "a vessel kind (scroll, potion, wand) and its recipe"));
                    continue;
                }
                Long output = whole(k.get("output"));
                if (str(k, "bench") == null || str(k, "category") == null || output == null || output < 1) {
                    problems.add(new Problem("invalid", p, "a kind has a bench, a category and an output from 1"));
                    continue;
                }
                List<In> ins = new ArrayList<>();
                List<Json> items = k.get("inputs") instanceof Json.Arr a ? a.items() : List.of();
                if (items.isEmpty()) problems.add(new Problem("invalid", p + "/inputs", "at least one input"));
                for (int i = 0; i < items.size(); i++) {
                    String ip = p + "/inputs/" + i;
                    Json.Obj io = items.get(i) instanceof Json.Obj x ? x : null;
                    List<String> ids = new ArrayList<>();
                    List<Long> counts = new ArrayList<>();
                    if (io != null && io.get("item") instanceof Json.Str s1) ids.add(s1.value());
                    else if (io != null && io.get("item") instanceof Json.Arr ia) {
                        for (Json j : ia.items()) if (j instanceof Json.Str s2) ids.add(s2.value());
                    }
                    if (io != null && whole(io.get("count")) != null) counts.add(whole(io.get("count")));
                    else if (io != null && io.get("count") instanceof Json.Arr ca) {
                        for (Json j : ca.items()) if (whole(j) != null) counts.add(whole(j));
                    }
                    boolean fits = !ids.isEmpty() && !counts.isEmpty() && (ids.size() == 1 || ids.size() == nTiers)
                            && (counts.size() == 1 || counts.size() == nTiers);
                    if (!fits) problems.add(new Problem("invalid", ip, "an item (or one per tier) and a count (or one per tier)"));
                    else ins.add(new In(ids, counts));
                }
                kinds.put(e.getKey(), new Kind(str(k, "bench"), str(k, "category"), output, ins));
            }
        } else {
            problems.add(new Problem("invalid", "/kinds", "an object of kind: recipe"));
        }
        return problems.isEmpty() ? new Parsed.Ok<>(new RecipeRules(tiers, schools, def, kinds), List.of())
                : new Parsed.Invalid<>(problems);
    }

    private static Parsed<RecipeRules> bad(String p, String m) {
        return new Parsed.Invalid<>(List.of(new Problem("invalid", p, m)));
    }

    private static String str(Json.Obj o, String key) {
        return o.get(key) instanceof Json.Str s && !s.value().isEmpty() ? s.value() : null;
    }

    private static Long whole(Json v) {
        if (!(v instanceof Json.Num n)) return null;
        Milli.Parsed m = Milli.parseWhole(n.text());
        return m.problem() == null && m.value() >= 0 ? m.value() : null;
    }
}
