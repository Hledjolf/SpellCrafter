package spellcrafter.core.format;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import spellcrafter.core.json.Json;
import spellcrafter.core.json.JsonWriter;
import spellcrafter.core.model.Condition;
import spellcrafter.core.model.Model.AppliesTo;
import spellcrafter.core.model.Model.Caps;
import spellcrafter.core.model.Model.Circumstance;
import spellcrafter.core.model.Model.Component;
import spellcrafter.core.model.Model.Delivery;
import spellcrafter.core.model.Model.Gate;
import spellcrafter.core.model.Model.Group;
import spellcrafter.core.model.Model.Overrides;
import spellcrafter.core.model.Model.Payload;
import spellcrafter.core.model.Model.Pick;
import spellcrafter.core.model.Model.Recipe;
import spellcrafter.core.model.Model.RecipeInput;
import spellcrafter.core.model.Model.Profile;
import spellcrafter.core.model.Model.Registry;
import spellcrafter.core.model.Model.RegistryEntry;
import spellcrafter.core.model.Model.Rider;
import spellcrafter.core.model.Model.ServerCaps;
import spellcrafter.core.model.Model.ServerSettings;
import spellcrafter.core.model.Model.Spell;
import spellcrafter.core.model.Model.Targeting;
import spellcrafter.core.model.Model.Vessel;

/**
 * Reads and validates SpellCrafter's documents (design/spell-format.md): spells and spell packs, circumstances,
 * groups, registries, combining profiles and server settings. Results are values: {@link Parsed.Ok} or
 * {@link Parsed.Invalid} with every problem and its JSON Pointer.
 */
public final class Formats {
    private Formats() {
    }

    /** What validation depends on: the condition vocabulary and the server's caps (condition depth, riders per cast). */
    public record Options(Vocabulary vocabulary, ServerCaps caps) {
        public static final Options DEFAULT = new Options(Vocabulary.COMMANDDECK_1_0, ServerCaps.DEFAULTS);
    }

    /** A spell pack (section 3.1): one mod's spells, groups and circumstances in one file. */
    public record Pack(String formatVersion, String modId, String game, List<Spell> spells, List<Group> groups,
            List<Circumstance> circumstances) {
    }

    private static final Set<String> SPELL_KEYS = Set.of("format", "format_version", "id", "name", "origin",
            "categories", "groups", "cast", "delivery", "components", "payloads", "requires", "riders", "caps",
            "provenance", "cost", "bindings", "uses", "picks");
    /** Deliveries that can burst (section 7b): a projectile where it lands, a touch at the target, self around the caster. */
    private static final Set<String> BURST_KINDS = Set.of("projectile", "touch", "self");
    private static final Set<String> AFFECTS = Set.of("others", "all", "enemies");
    private static final Set<String> TARGETS = Set.of("target", "caster", "area");
    private static final Set<String> DELIVERY_KINDS = Set.of("self", "touch", "projectile", "area", "aura", "placed");
    private static final Set<String> RIDER_KEYS = Set.of("id", "when", "plus", "bonus_pct", "more_pct", "add",
            "only_tags", "recheck", "recheck_every");
    private static final Set<String> CIRC_KEYS = Set.of("format", "format_version", "id", "name", "applies_to", "when",
            "requires", "plus", "bonus_pct", "more_pct", "add", "only_tags", "recheck", "recheck_every", "provenance");
    private static final List<String> STAGES = List.of("flat", "increase", "more");
    private static final Map<String, String> FIELD_STAGE = Map.of("plus", "flat", "bonus_pct", "increase", "more_pct",
            "more");

    // ------------------------------------------------------------------------------------------------- text entry

    /** Reads JSON text; {@code not_json} if it isn't JSON. */
    public static Parsed<Json> json(String text) {
        Json.Read r = Json.read(text);
        if (r instanceof Json.Read.Ok ok) return new Parsed.Ok<>(ok.value(), List.of());
        Json.Read.Error e = (Json.Read.Error) r;
        return new Parsed.Invalid<>(List.of(new Problem("not_json", "", e.message() + " at character " + e.offset())));
    }

    // ------------------------------------------------------------------------------------------------- spells

    public static Parsed<Spell> spell(Json doc, Options opt) {
        Check c = new Check();
        Spell s = spell(doc, "", opt, c, false);
        return result(s, c);
    }

    public static Parsed<Pack> pack(Json doc, Options opt) {
        Check c = new Check();
        Json.Obj o = c.obj(doc, "");
        if (o == null) return result(null, c);
        c.keys(o, "", Set.of("format", "format_version", "mod", "game", "note", "spells", "groups", "circumstances"));
        String version = format(o, "", "spellcrafter.spells", true, c);
        String modId = null;
        Json.Obj mod = c.obj(c.req(o, "mod", ""), "/mod");
        if (mod != null) {
            c.keys(mod, "/mod", Set.of("id", "name", "version"));
            modId = c.nonEmptyStr(c.req(mod, "id", "/mod"), "/mod/id");
        }
        String game = o.has("game") ? c.str(o.get("game"), "/game") : null;
        if (o.has("note")) c.str(o.get("note"), "/note");
        List<Spell> spells = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<Json> items = c.arr(c.req(o, "spells", ""), "/spells");
        if (items != null) {
            for (int i = 0; i < items.size(); i++) {
                String p = Check.at("/spells", i);
                Spell s = spell(items.get(i), p, opt, c, true);
                if (s == null) continue;
                if (!seen.add(s.id())) c.add("duplicate_id", p + "/id", "spell \"" + s.id() + "\" appears twice");
                inMod(s.id(), modId, p, c);
                spells.add(s);
            }
        }
        List<Group> groups = new ArrayList<>();
        if (o.has("groups")) {
            List<Json> gs = c.arr(o.get("groups"), "/groups");
            if (gs != null) {
                groups = groups(gs, "/groups", opt, c, true);
                for (int i = 0; i < gs.size(); i++) {
                    Json id = gs.get(i) instanceof Json.Obj go ? go.get("id") : null;
                    if (id instanceof Json.Str str) inMod(str.value(), modId, Check.at("/groups", i), c);
                }
            }
        }
        List<Circumstance> circs = new ArrayList<>();
        if (o.has("circumstances")) {
            List<Json> cs = c.arr(o.get("circumstances"), "/circumstances");
            Set<String> circIds = new HashSet<>();
            if (cs != null) {
                for (int i = 0; i < cs.size(); i++) {
                    String p = Check.at("/circumstances", i);
                    Circumstance ci = circumstance(cs.get(i), p, opt, c, true);
                    if (ci == null) continue;
                    if (!circIds.add(ci.id())) {
                        c.add("duplicate_id", p + "/id", "circumstance \"" + ci.id() + "\" appears twice");
                    }
                    inMod(ci.id(), modId, p, c);
                    circs.add(ci);
                }
            }
        }
        return result(new Pack(version, modId, game, spells, groups, circs), c);
    }

    // ------------------------------------------------------------------------------------------------- vessels

    /** A vessels file (release-1.0.md section 2): one mod's vessels. */
    public record VesselPack(String formatVersion, String modId, String game, List<Vessel> vessels) {
    }

    private static final Set<String> VESSEL_KINDS = Set.of("scroll", "potion", "wand");

    /** Reads a {@code spellcrafter.vessels} file. Which spells the vessels name is {@link #checkVessels}. */
    public static Parsed<VesselPack> vessels(Json doc) {
        Check c = new Check();
        Json.Obj o = c.obj(doc, "");
        if (o == null) return result(null, c);
        c.keys(o, "", Set.of("format", "format_version", "mod", "game", "note", "vessels"));
        String version = format(o, "", "spellcrafter.vessels", true, c);
        String modId = null;
        Json.Obj mod = c.obj(c.req(o, "mod", ""), "/mod");
        if (mod != null) {
            c.keys(mod, "/mod", Set.of("id", "name", "version"));
            modId = c.nonEmptyStr(c.req(mod, "id", "/mod"), "/mod/id");
        }
        String game = o.has("game") ? c.str(o.get("game"), "/game") : null;
        if (o.has("note")) c.str(o.get("note"), "/note");
        List<Vessel> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        List<Json> items = c.arr(c.req(o, "vessels", ""), "/vessels");
        if (items != null) {
            for (int i = 0; i < items.size(); i++) {
                String p = Check.at("/vessels", i);
                Vessel v = vessel(items.get(i), p, c);
                if (v == null) continue;
                if (!seen.add(v.id())) c.add("duplicate_id", p + "/id", "vessel \"" + v.id() + "\" appears twice");
                inMod(v.id(), modId, p, c);
                out.add(v);
            }
        }
        return result(new VesselPack(version, modId, game, out), c);
    }

    private static Vessel vessel(Json doc, String p, Check c) {
        int before = c.problems.size();
        Json.Obj o = c.obj(doc, p);
        if (o == null) return null;
        c.keys(o, p, Set.of("id", "kind", "spell", "recipe", "bindings"));
        String id = qualified(o, "id", p, c);
        String kind = c.str(c.req(o, "kind", p), Check.at(p, "kind"));
        if (kind != null && !VESSEL_KINDS.contains(kind)) {
            c.add("invalid", Check.at(p, "kind"), "vessel kind is \"scroll\", \"potion\" or \"wand\"");
        }
        String spell = qualified(o, "spell", p, c);
        Recipe recipe = o.has("recipe") ? recipe(o.get("recipe"), Check.at(p, "recipe"), c) : null;
        Json.Obj bindings = o.has("bindings") ? c.obj(o.get("bindings"), Check.at(p, "bindings")) : null;
        return c.problems.size() == before ? new Vessel(id, kind, spell, recipe, bindings) : null;
    }

    private static Recipe recipe(Json v, String p, Check c) {
        Json.Obj o = c.obj(v, p);
        if (o == null) return null;
        c.keys(o, p, Set.of("bench", "category", "inputs", "output"));
        String bench = c.nonEmptyStr(c.req(o, "bench", p), Check.at(p, "bench"));
        String category = o.has("category") ? c.nonEmptyStr(o.get("category"), Check.at(p, "category")) : null;
        List<RecipeInput> inputs = new ArrayList<>();
        List<Json> items = c.arr(c.req(o, "inputs", p), Check.at(p, "inputs"));
        if (items != null) {
            if (items.isEmpty()) c.add("invalid", Check.at(p, "inputs"), "a recipe has at least one input");
            for (int i = 0; i < items.size(); i++) {
                String ip = Check.at(Check.at(p, "inputs"), i);
                Json.Obj in = c.obj(items.get(i), ip);
                if (in == null) continue;
                c.keys(in, ip, Set.of("item", "count"));
                String item = c.nonEmptyStr(c.req(in, "item", ip), Check.at(ip, "item"));
                if (item != null && !Check.namespaced(item)) {
                    c.add("bad_id", Check.at(ip, "item"), "\"" + item + "\" isn't <namespace>:<item>");
                }
                Long count = positiveWhole(in, "count", ip, c);
                if (item != null && count != null) inputs.add(new RecipeInput(item, count));
            }
        }
        Long output = positiveWhole(o, "output", p, c);
        return output == null ? null : new Recipe(bench, category, inputs, output);
    }

    private static Long positiveWhole(Json.Obj o, String key, String p, Check c) {
        Long n = c.whole(c.req(o, key, p), Check.at(p, key));
        if (n != null && n < 1) {
            c.add("invalid", Check.at(p, key), "\"" + key + "\" is a whole number, at least 1");
            return null;
        }
        return n;
    }

    /**
     * Checks vessels against the spells they hold: each names a known spell ({@code unknown_spell}), and a potion's
     * spell is delivered to {@code self} ({@code vessel_delivery}), since drinking applies it to the drinker.
     */
    public static List<Problem> checkVessels(VesselPack pack, Map<String, Spell> spells) {
        List<Problem> out = new ArrayList<>();
        for (int i = 0; i < pack.vessels().size(); i++) {
            Vessel v = pack.vessels().get(i);
            String p = Check.at("/vessels", i);
            Spell s = spells.get(v.spell());
            if (s == null) {
                out.add(new Problem("unknown_spell", p + "/spell", "no spell \"" + v.spell() + "\" is loaded"));
            } else if (v.kind().equals("potion") && !"self".equals(s.delivery().kind())) {
                out.add(new Problem("vessel_delivery", p + "/spell", "a potion holds a self-delivered spell; \""
                        + v.spell() + "\" is delivered by " + s.delivery().kind()));
            }
        }
        return out;
    }

    /** Every id in a pack starts with the pack's {@code mod.id}. */
    private static void inMod(String id, String modId, String p, Check c) {
        if (modId != null && !id.startsWith(modId + "/")) {
            c.add("mod_mismatch", p + "/id", "\"" + id + "\" isn't in the pack's mod \"" + modId + "\"");
        }
    }

    static Spell spell(Json doc, String p, Options opt, Check c, boolean inPack) {
        int before = c.problems.size();
        Json.Obj o = c.obj(doc, p);
        if (o == null) return null;
        c.keys(o, p, SPELL_KEYS);
        String version = format(o, p, "spellcrafter.spell", !inPack, c);
        String id = qualified(o, "id", p, c);
        String name = c.nonEmptyStr(c.req(o, "name", p), Check.at(p, "name"));

        String originKind = null;
        String originFrom = null;
        Json.Obj origin = c.obj(c.req(o, "origin", p), Check.at(p, "origin"));
        if (origin != null) {
            String op = Check.at(p, "origin");
            c.keys(origin, op, Set.of("kind", "from"));
            originKind = c.str(c.req(origin, "kind", op), Check.at(op, "kind"));
            if (originKind != null && !originKind.equals("native") && !originKind.equals("derived")) {
                c.add("invalid", Check.at(op, "kind"), "origin kind is \"native\" or \"derived\"");
            }
            if (origin.has("from")) originFrom = c.nonEmptyStr(origin.get("from"), Check.at(op, "from"));
            if ("derived".equals(originKind) && originFrom == null && !origin.has("from")) {
                c.add("invalid", Check.at(op, "from"), "a derived spell names what it's derived from");
            }
        }

        List<String> categories = namespacedList(o, "categories", p, c);
        List<String> groups = c.optStrList(o, "groups", p);
        for (int i = 0; i < groups.size(); i++) {
            if (!Check.qualifiedId(groups.get(i))) badId(c, Check.at(Check.at(p, "groups"), i), groups.get(i));
        }

        long castTime = 0;
        long cooldown = 0;
        Json.Obj cast = c.obj(c.req(o, "cast", p), Check.at(p, "cast"));
        if (cast != null) {
            String cp = Check.at(p, "cast");
            c.keys(cast, cp, Set.of("time", "cooldown"));
            castTime = nonNegative(c.milli(c.req(cast, "time", cp), Check.at(cp, "time")), Check.at(cp, "time"), c);
            cooldown = nonNegative(c.milli(c.req(cast, "cooldown", cp), Check.at(cp, "cooldown")),
                    Check.at(cp, "cooldown"), c);
        }

        Delivery delivery = null;
        Json.Obj d = c.obj(c.req(o, "delivery", p), Check.at(p, "delivery"));
        if (d != null) {
            String dp = Check.at(p, "delivery");
            c.keys(d, dp, Set.of("kind", "params", "burst"));
            String kind = c.str(c.req(d, "kind", dp), Check.at(dp, "kind"));
            if (kind != null && !DELIVERY_KINDS.contains(kind)) {
                c.add("invalid", Check.at(dp, "kind"), "unknown delivery kind \"" + kind + "\"");
            }
            Map<String, Long> params = d.has("params") ? c.milliMap(d.get("params"), Check.at(dp, "params"))
                    : new LinkedHashMap<>();
            String burst = null;
            if (d.has("burst")) {
                String bp = Check.at(dp, "burst");
                Json.Obj b = c.obj(d.get("burst"), bp);
                if (b != null) {
                    c.keys(b, bp, Set.of("affects"));
                    burst = b.has("affects") ? c.str(b.get("affects"), Check.at(bp, "affects")) : "others";
                    if (burst != null && !AFFECTS.contains(burst)) {
                        c.add("invalid", Check.at(bp, "affects"), "a burst affects \"others\", \"all\" or \"enemies\"");
                    }
                }
                if (kind != null && !BURST_KINDS.contains(kind)) {
                    c.add("invalid", bp, "only a projectile, touch or self delivery can burst");
                }
                Long r = params.get("burst.radius");
                if (r == null || r <= 0) c.add("invalid", Check.at(Check.at(dp, "params"), "burst.radius"),
                        "a burst needs a burst.radius above 0");
            } else if (params.containsKey("burst.radius")) {
                c.add("invalid", Check.at(Check.at(dp, "params"), "burst.radius"), "burst.radius without a burst");
            }
            delivery = new Delivery(kind, params, burst);
        }

        List<Component> components = new ArrayList<>();
        if (o.has("components")) {
            List<Json> items = c.arr(o.get("components"), Check.at(p, "components"));
            Set<String> ids = new HashSet<>();
            if (items != null) {
                for (int i = 0; i < items.size(); i++) {
                    Component comp = component(items.get(i), Check.at(Check.at(p, "components"), i), c);
                    if (comp == null) continue;
                    if (!ids.add(comp.id())) {
                        c.add("duplicate_id", Check.at(Check.at(Check.at(p, "components"), i), "id"),
                                "component \"" + comp.id() + "\" appears twice");
                    }
                    components.add(comp);
                }
            }
        }

        List<Payload> payloads = payloads(c.req(o, "payloads", p), Check.at(p, "payloads"), true, c);

        Set<String> ruleIds = new HashSet<>();
        List<Gate> gates = gates(o, p, opt, c, ruleIds);
        List<Rider> riders = new ArrayList<>();
        if (o.has("riders")) {
            List<Json> items = c.arr(o.get("riders"), Check.at(p, "riders"));
            if (items != null) {
                for (int i = 0; i < items.size(); i++) {
                    String rp = Check.at(Check.at(p, "riders"), i);
                    Json.Obj ro = c.obj(items.get(i), rp);
                    if (ro == null) continue;
                    c.keys(ro, rp, RIDER_KEYS);
                    String rid = localId(ro, rp, c);
                    if (rid != null && !ruleIds.add(rid)) {
                        c.add("duplicate_id", Check.at(rp, "id"), "\"" + rid + "\" is already a gate or rider");
                    }
                    Condition when = condition(c.req(ro, "when", rp), Check.at(rp, "when"), opt, c);
                    riders.add(riderBody(rid, when, ro, rp, opt, c));
                }
            }
        }
        if (gates.size() + riders.size() > opt.caps().ridersPerCast()) {
            c.add("too_many_riders", p.isEmpty() ? "/riders" : Check.at(p, "riders"), (gates.size() + riders.size())
                    + " gates and riders, more than the server's riders_per_cast (" + opt.caps().ridersPerCast() + ")");
        }

        List<Pick> picks = picks(o, p, c);
        if (delivery != null) {
            Set<String> pickIds = new HashSet<>();
            for (Pick k : picks) pickIds.add(k.id());
            targeting(payloads, Check.at(p, "payloads"), delivery, pickIds, c);
            for (int i = 0; i < riders.size(); i++) {
                targeting(riders.get(i).add(), Check.at(Check.at(Check.at(p, "riders"), i), "add"), delivery, pickIds, c);
            }
            for (int i = 0; i < picks.size(); i++) {
                boolean option = false;
                for (Payload pl : payloads) option |= !pl.isReroll() && picks.get(i).id().equals(Targeting.pick(pl.fields()));
                if (!option) c.add("invalid", Check.at(Check.at(p, "picks"), i), "pick group \"" + picks.get(i).id()
                        + "\" has no payload among its options");
            }
        }

        Caps caps = Caps.NONE;
        if (o.has("caps")) caps = spellCaps(o.get("caps"), Check.at(p, "caps"), c);

        Json.Obj provenance = c.obj(c.req(o, "provenance", p), Check.at(p, "provenance"));
        if (provenance != null) {
            c.nonEmptyStr(c.req(provenance, "author", Check.at(p, "provenance")),
                    Check.at(Check.at(p, "provenance"), "author"));
        }
        long costMana = cost(c.req(o, "cost", p), Check.at(p, "cost"), c);
        Json.Obj bindings = o.has("bindings") ? c.obj(o.get("bindings"), Check.at(p, "bindings")) : null;
        Map<String, String> uses = new LinkedHashMap<>();
        if (o.has("uses")) {
            Json.Obj u = c.obj(o.get("uses"), Check.at(p, "uses"));
            if (u != null) {
                for (Map.Entry<String, Json> e : u.members().entrySet()) {
                    String v = c.nonEmptyStr(e.getValue(), Check.at(Check.at(p, "uses"), e.getKey()));
                    if (v != null) uses.put(e.getKey(), v);
                }
            }
        }
        if (c.problems.size() > before) return null;
        return new Spell(version, id, name, originKind, originFrom, categories, groups, castTime, cooldown, delivery,
                components, payloads, gates, riders, caps, provenance, costMana, bindings, uses, picks);
    }

    /** {@code picks}: pick groups, {@code {id, rolls}} (section 7b); {@code rolls} is a whole number from 1, default 1. */
    private static List<Pick> picks(Json.Obj o, String p, Check c) {
        List<Pick> out = new ArrayList<>();
        if (!o.has("picks")) return out;
        String pp = Check.at(p, "picks");
        List<Json> items = c.arr(o.get("picks"), pp);
        if (items == null) return out;
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < items.size(); i++) {
            String ip = Check.at(pp, i);
            Json.Obj k = c.obj(items.get(i), ip);
            if (k == null) continue;
            c.keys(k, ip, Set.of("id", "rolls"));
            String id = localId(k, ip, c);
            if (id != null && !ids.add(id)) c.add("duplicate_id", Check.at(ip, "id"), "pick group \"" + id + "\" appears twice");
            long rolls = 1;
            if (k.has("rolls")) {
                Long r = c.whole(k.get("rolls"), Check.at(ip, "rolls"));
                if (r != null && r < 1) c.add("out_of_range", Check.at(ip, "rolls"), "rolls is a whole number from 1");
                if (r != null) rolls = r;
            }
            if (id != null) out.add(new Pick(id, rolls));
        }
        return out;
    }

    /**
     * Section 7b's payload fields: {@code target} ({@code area} only with a burst), {@code chance} 1–100, {@code pick}
     * naming a declared group, {@code weight} from 1 and {@code reroll} from 1 only with {@code pick}; a pick option
     * has no chance of its own (the pick is its roll).
     */
    private static void targeting(List<Payload> payloads, String p, Delivery d, Set<String> pickIds, Check c) {
        for (int i = 0; i < payloads.size(); i++) {
            Json.Obj f = payloads.get(i).fields();
            String pp = Check.at(p, i);
            if (f.has("target")) {
                String t = c.str(f.get("target"), Check.at(pp, "target"));
                if (t != null && !TARGETS.contains(t)) {
                    c.add("invalid", Check.at(pp, "target"), "a payload's target is \"target\", \"caster\" or \"area\"");
                } else if ("area".equals(t) && d.burst() == null) {
                    c.add("invalid", Check.at(pp, "target"), "an area payload needs a delivery with a burst");
                }
            }
            if (f.has("chance")) {
                Long n = c.whole(f.get("chance"), Check.at(pp, "chance"));
                if (n != null && (n < 1 || n > 100)) c.add("out_of_range", Check.at(pp, "chance"), "chance is a whole percent, 1 to 100");
            }
            String pick = null;
            if (f.has("pick")) {
                pick = c.str(f.get("pick"), Check.at(pp, "pick"));
                if (pick != null && !pickIds.contains(pick)) {
                    c.add("unknown_pick", Check.at(pp, "pick"), "no pick group \"" + pick + "\" in picks");
                }
                if (f.has("chance")) c.add("invalid", Check.at(pp, "chance"), "a pick option has no chance of its own: the pick is its roll");
            }
            for (String key : List.of("weight", "reroll")) {
                if (!f.has(key)) continue;
                Long n = c.whole(f.get(key), Check.at(pp, key));
                if (n != null && n < 1) c.add("out_of_range", Check.at(pp, key), key + " is a whole number from 1");
                if (!f.has("pick")) c.add("invalid", Check.at(pp, key), key + " needs a pick group (\"pick\")");
            }
        }
    }

    /** 1.0's authored cost: {@code { "mana": N }}, a whole number, at least 0 (section 3; from 1.1 it's derived). */
    private static long cost(Json v, String p, Check c) {
        if (v == null) return 0;
        Json.Obj o = c.obj(v, p);
        if (o == null) return 0;
        c.keys(o, p, Set.of("mana"));
        Long mana = c.whole(c.req(o, "mana", p), Check.at(p, "mana"));
        if (mana == null) return 0;
        if (mana < 0) c.add("out_of_range", Check.at(p, "mana"), "a mana cost can't be negative");
        return mana;
    }

    private static List<Gate> gates(Json.Obj o, String p, Options opt, Check c, Set<String> ruleIds) {
        List<Gate> gates = new ArrayList<>();
        if (!o.has("requires")) return gates;
        List<Json> items = c.arr(o.get("requires"), Check.at(p, "requires"));
        if (items == null) return gates;
        for (int i = 0; i < items.size(); i++) {
            String gp = Check.at(Check.at(p, "requires"), i);
            Json.Obj go = c.obj(items.get(i), gp);
            if (go == null) continue;
            c.keys(go, gp, Set.of("id", "when", "recheck", "recheck_every"));
            String gid = localId(go, gp, c);
            if (gid != null && !ruleIds.add(gid)) {
                c.add("duplicate_id", Check.at(gp, "id"), "\"" + gid + "\" is already a gate or rider");
            }
            Condition when = condition(c.req(go, "when", gp), Check.at(gp, "when"), opt, c);
            boolean tick = recheck(go, gp, c);
            gates.add(new Gate(gid, when, tick, recheckEvery(go, gp, tick, c)));
        }
        return gates;
    }

    private static Rider riderBody(String id, Condition when, Json.Obj o, String p, Options opt, Check c) {
        Map<String, Long> plus = o.has("plus") ? c.milliMap(o.get("plus"), Check.at(p, "plus")) : new LinkedHashMap<>();
        Map<String, Long> bonus = o.has("bonus_pct") ? c.pctMap(o.get("bonus_pct"), Check.at(p, "bonus_pct"))
                : new LinkedHashMap<>();
        Map<String, Long> more = o.has("more_pct") ? c.pctMap(o.get("more_pct"), Check.at(p, "more_pct"))
                : new LinkedHashMap<>();
        List<Payload> add = o.has("add") ? payloads(o.get("add"), Check.at(p, "add"), false, c) : new ArrayList<>();
        List<String> onlyTags = c.optStrList(o, "only_tags", p);
        boolean tick = recheck(o, p, c);
        return new Rider(id, when, plus, bonus, more, add, onlyTags, tick, recheckEvery(o, p, tick, c));
    }

    /**
     * {@code recheck_every}, in seconds, as ms: above 0. Null when not set. Only meaningful with {@code recheck: tick},
     * so it's a warning otherwise (section 5, SC-002 gap 7).
     */
    private static Long recheckEvery(Json.Obj o, String p, boolean tick, Check c) {
        if (!o.has("recheck_every")) return null;
        String rp = Check.at(p, "recheck_every");
        Long ms = c.milli(o.get("recheck_every"), rp);
        if (ms == null) return null;
        if (ms <= 0) {
            c.add("out_of_range", rp, "recheck_every is a number of seconds above 0");
            return null;
        }
        if (!tick) c.warn("recheck_every_unused", rp, "recheck_every only applies with \"recheck\": \"tick\"");
        return ms;
    }

    private static boolean recheck(Json.Obj o, String p, Check c) {
        if (!o.has("recheck")) return false;
        String r = c.str(o.get("recheck"), Check.at(p, "recheck"));
        if (r == null) return false;
        if (!r.equals("cast") && !r.equals("tick")) {
            c.add("invalid", Check.at(p, "recheck"), "recheck is \"cast\" or \"tick\"");
        }
        return r.equals("tick");
    }

    private static List<Payload> payloads(Json v, String p, boolean atLeastOne, Check c) {
        List<Payload> out = new ArrayList<>();
        List<Json> items = c.arr(v, p);
        if (items == null) return out;
        if (atLeastOne && items.isEmpty()) c.add("invalid", p, "a spell has at least one payload");
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < items.size(); i++) {
            String pp = Check.at(p, i);
            Json.Obj o = c.obj(items.get(i), pp);
            if (o == null) continue;
            String id = localId(o, pp, c);
            if (id != null && !ids.add(id)) c.add("duplicate_id", Check.at(pp, "id"), "payload \"" + id + "\" appears twice");
            if (o.has("reroll")) {
                // A pick group's reroll option (section 7b): no provider, op or params.
                c.keys(o, pp, Set.of("id", "pick", "weight", "reroll"));
                out.add(new Payload(id, null, null, new LinkedHashMap<>(), List.of(), o));
                continue;
            }
            String provider = c.nonEmptyStr(c.req(o, "provider", pp), Check.at(pp, "provider"));
            String op = c.nonEmptyStr(c.req(o, "op", pp), Check.at(pp, "op"));
            Map<String, Long> params = o.has("params") ? c.milliMap(o.get("params"), Check.at(pp, "params"))
                    : new LinkedHashMap<>();
            List<String> tags = c.optStrList(o, "tags", pp);
            out.add(new Payload(id, provider, op, params, tags, o));
        }
        return out;
    }

    private static Component component(Json v, String p, Check c) {
        Json.Obj o = c.obj(v, p);
        if (o == null) return null;
        c.keys(o, p, Set.of("id", "item", "tag", "count", "consumed"));
        String id = localId(o, p, c);
        String item = o.has("item") ? c.nonEmptyStr(o.get("item"), Check.at(p, "item")) : null;
        String tag = o.has("tag") ? c.nonEmptyStr(o.get("tag"), Check.at(p, "tag")) : null;
        if (o.has("item") == o.has("tag")) c.add("invalid", p, "a component has exactly one of \"item\" and \"tag\"");
        Long count = c.whole(c.req(o, "count", p), Check.at(p, "count"));
        if (count != null && count < 1) c.add("out_of_range", Check.at(p, "count"), "count is at least 1");
        Boolean consumed = c.bool(c.req(o, "consumed", p), Check.at(p, "consumed"));
        if (id == null || count == null || consumed == null) return null;
        return new Component(id, item, tag, count, consumed);
    }

    private static Caps spellCaps(Json v, String p, Check c) {
        Json.Obj o = c.obj(v, p);
        if (o == null) return Caps.NONE;
        c.keys(o, p, Set.of("params", "bonus_pct", "more_pct", "added_payloads"));
        Map<String, Long> params = o.has("params") ? c.milliMap(o.get("params"), Check.at(p, "params")) : Map.of();
        Long bonus = o.has("bonus_pct") ? c.pct(o.get("bonus_pct"), Check.at(p, "bonus_pct")) : null;
        Long each = null;
        Long perParam = null;
        if (o.has("more_pct")) {
            Json.Obj m = c.obj(o.get("more_pct"), Check.at(p, "more_pct"));
            if (m != null) {
                String mp = Check.at(p, "more_pct");
                c.keys(m, mp, Set.of("each", "per_param"));
                if (m.has("each")) each = c.pct(m.get("each"), Check.at(mp, "each"));
                if (m.has("per_param")) perParam = c.whole(m.get("per_param"), Check.at(mp, "per_param"));
            }
        }
        Long added = o.has("added_payloads") ? c.whole(o.get("added_payloads"), Check.at(p, "added_payloads")) : null;
        return new Caps(params, bonus, each, perParam, added);
    }

    // ------------------------------------------------------------------------------------------------- conditions

    /** Reads one condition. Depth counts the combinators on the path from the top. */
    public static Parsed<Condition> condition(Json v, Options opt) {
        Check c = new Check();
        Condition cond = condition(v, "", opt, c);
        return result(cond, c);
    }

    static Condition condition(Json v, String p, Options opt, Check c) {
        if (v == null) return null;
        return condition(v, p, 0, opt, c);
    }

    private static Condition condition(Json v, String p, int depth, Options opt, Check c) {
        Json.Obj o = c.obj(v, p);
        if (o == null) return null;
        String type = c.nonEmptyStr(c.req(o, "type", p), Check.at(p, "type"));
        if (type == null) return null;
        if (type.equals("all") || type.equals("any") || type.equals("not")) {
            if (depth + 1 > opt.caps().conditionDepth()) {
                c.add("depth_exceeded", p, "conditions nest deeper than the server's condition_depth ("
                        + opt.caps().conditionDepth() + ")");
                return null;
            }
            c.keys(o, p, Set.of("type", "of"));
            Json of = c.req(o, "of", p);
            if (of == null) return null;
            if (type.equals("not")) {
                if (of instanceof Json.Arr) {
                    c.add("invalid", Check.at(p, "of"), "\"not\" takes one condition, not a list");
                    return null;
                }
                Condition inner = condition(of, Check.at(p, "of"), depth + 1, opt, c);
                return inner == null ? null : new Condition.Not(inner);
            }
            List<Json> items = c.arr(of, Check.at(p, "of"));
            if (items == null) return null;
            if (items.isEmpty()) {
                c.add("invalid", Check.at(p, "of"), "\"" + type + "\" needs at least one condition");
                return null;
            }
            List<Condition> kids = new ArrayList<>();
            boolean bad = false;
            for (int i = 0; i < items.size(); i++) {
                Condition k = condition(items.get(i), Check.at(Check.at(p, "of"), i), depth + 1, opt, c);
                if (k == null) bad = true;
                else kids.add(k);
            }
            if (bad) return null;
            return type.equals("all") ? new Condition.All(kids) : new Condition.Any(kids);
        }
        if (type.indexOf('.') < 0 && !opt.vocabulary().knows(type)) {
            c.add("unknown_type", Check.at(p, "type"), "\"" + type + "\" isn't a condition type in conditions "
                    + opt.vocabulary().version() + "; another mod's type has a namespace (\"buffeffects.has_effect\")");
            return null;
        }
        int before = c.problems.size();
        c.numbersIn(o, p);
        if (c.problems.size() > before) return null;
        return new Condition.Leaf(type, o, JsonWriter.canonical(o));
    }

    // ------------------------------------------------------------------------------------------------- circumstances

    public static Parsed<Circumstance> circumstance(Json doc, Options opt) {
        Check c = new Check();
        return result(circumstance(doc, "", opt, c, false), c);
    }

    private static Circumstance circumstance(Json doc, String p, Options opt, Check c, boolean inPack) {
        int before = c.problems.size();
        Json.Obj o = c.obj(doc, p);
        if (o == null) return null;
        c.keys(o, p, CIRC_KEYS);
        format(o, p, "spellcrafter.circumstance", !inPack, c);
        String id = qualified(o, "id", p, c);
        String name = c.nonEmptyStr(c.req(o, "name", p), Check.at(p, "name"));
        AppliesTo applies = null;
        String ap = Check.at(p, "applies_to");
        Json.Obj a = c.obj(c.req(o, "applies_to", p), ap);
        if (a != null) {
            c.keys(a, ap, Set.of("ids", "categories", "groups", "exclude"));
            List<String> ids = qualifiedList(a, "ids", ap, c);
            List<String> cats = namespacedList(a, "categories", ap, c);
            List<String> groups = qualifiedList(a, "groups", ap, c);
            List<String> exclude = qualifiedList(a, "exclude", ap, c);
            if (ids.isEmpty() && cats.isEmpty() && groups.isEmpty() && c.problems.size() == before) {
                c.add("invalid", ap, "applies_to names at least one of ids, categories and groups");
            }
            applies = new AppliesTo(ids, cats, groups, exclude);
        }
        if (o.has("provenance")) c.obj(o.get("provenance"), Check.at(p, "provenance"));
        Set<String> ruleIds = new HashSet<>();
        List<Gate> gates = gates(o, p, opt, c, ruleIds);
        Condition when = o.has("when") ? condition(o.get("when"), Check.at(p, "when"), opt, c) : null;
        Rider rider = riderBody(id, when, o, p, opt, c);
        if (c.problems.size() > before) return null;
        return new Circumstance(id, name, applies, gates, rider);
    }

    // ------------------------------------------------------------------------------------------------- groups

    public static Parsed<Group> group(Json doc) {
        Check c = new Check();
        Group g = group(doc, "", c, false);
        return result(g, c);
    }

    private static Group group(Json doc, String p, Check c, boolean inPack) {
        int before = c.problems.size();
        Json.Obj o = c.obj(doc, p);
        if (o == null) return null;
        c.keys(o, p, Set.of("format", "format_version", "id", "name", "members", "include", "exclude"));
        format(o, p, "spellcrafter.group", !inPack, c);
        String id = qualified(o, "id", p, c);
        String name = c.nonEmptyStr(c.req(o, "name", p), Check.at(p, "name"));
        List<String> members = qualifiedList(o, "members", p, c);
        List<String> incCats = new ArrayList<>();
        List<String> incGroups = new ArrayList<>();
        if (o.has("include")) {
            Json.Obj inc = c.obj(o.get("include"), Check.at(p, "include"));
            if (inc != null) {
                c.keys(inc, Check.at(p, "include"), Set.of("categories", "groups"));
                incCats = namespacedList(inc, "categories", Check.at(p, "include"), c);
                incGroups = qualifiedList(inc, "groups", Check.at(p, "include"), c);
            }
        }
        List<String> exclude = qualifiedList(o, "exclude", p, c);
        if (c.problems.size() > before) return null;
        return new Group(id, name, members, incCats, incGroups, exclude);
    }

    /**
     * Reads a set of groups together and checks what only the set shows: duplicate ids, cycles through
     * {@code include.groups}, and nesting deeper than {@code group_depth}.
     */
    public static Parsed<List<Group>> groups(List<Json> docs, Options opt) {
        Check c = new Check();
        List<Group> out = groups(docs, "", opt, c, false);
        return result(out, c);
    }

    private static List<Group> groups(List<Json> docs, String base, Options opt, Check c, boolean inPack) {
        List<Group> out = new ArrayList<>();
        Map<String, Group> byId = new LinkedHashMap<>();
        for (int i = 0; i < docs.size(); i++) {
            Group g = group(docs.get(i), Check.at(base, i), c, inPack);
            if (g == null) continue;
            if (byId.containsKey(g.id())) {
                c.add("duplicate_id", Check.at(Check.at(base, i), "id"), "group \"" + g.id() + "\" appears twice");
                continue;
            }
            byId.put(g.id(), g);
            out.add(g);
        }
        // Cycles, then depth: the longest chain of include.groups from each group.
        // One cycle is reported (the first found, in document order); that's enough to refuse the set.
        Map<String, Integer> state = new HashMap<>();
        for (int i = 0; i < out.size(); i++) {
            List<String> path = new ArrayList<>();
            String cycle = findCycle(out.get(i).id(), byId, state, path);
            if (cycle != null) {
                c.add("group_cycle", Check.at(Check.at(base, indexOf(out, cycle)), "include"),
                        "groups include each other: " + String.join(" → ", cycleText(path, cycle)));
                break;
            }
        }
        if (c.ok()) {
            Map<String, Integer> depthMemo = new HashMap<>();
            for (int i = 0; i < out.size(); i++) {
                int depth = depth(out.get(i).id(), byId, depthMemo);
                if (depth > opt.caps().groupDepth()) {
                    c.add("depth_exceeded", Check.at(Check.at(base, i), "include"), "group \"" + out.get(i).id()
                            + "\" nests " + depth + " deep, more than the server's group_depth (" + opt.caps().groupDepth() + ")");
                }
            }
        }
        return out;
    }

    private static int indexOf(List<Group> groups, String id) {
        for (int i = 0; i < groups.size(); i++) {
            if (groups.get(i).id().equals(id)) return i;
        }
        return 0;
    }

    private static List<String> cycleText(List<String> path, String start) {
        List<String> out = new ArrayList<>(path.subList(path.indexOf(start), path.size()));
        out.add(start);
        return out;
    }

    /** Depth-first search: 1 = on the current path, 2 = done. Returns a group on a cycle, or null. */
    private static String findCycle(String id, Map<String, Group> byId, Map<String, Integer> state, List<String> path) {
        Integer s = state.get(id);
        if (s != null && s == 2) return null;
        if (s != null && s == 1) return id;
        Group g = byId.get(id);
        if (g == null) return null;
        state.put(id, 1);
        path.add(id);
        for (String inc : g.includeGroups()) {
            String found = findCycle(inc, byId, state, path);
            if (found != null) return found;
        }
        path.remove(path.size() - 1);
        state.put(id, 2);
        return null;
    }

    /** 1 for a group that includes no groups; 1 + the deepest included group otherwise. */
    private static int depth(String id, Map<String, Group> byId, Map<String, Integer> memo) {
        Integer m = memo.get(id);
        if (m != null) return m;
        Group g = byId.get(id);
        int d = 1;
        if (g != null) {
            for (String inc : g.includeGroups()) d = Math.max(d, 1 + depth(inc, byId, memo));
        }
        memo.put(id, d);
        return d;
    }

    // ------------------------------------------------------------------------------------------------- registries

    /**
     * A registry file in CommandDeck's format, {@code hledjolf.conditions.registry} 1.0 (its {@code conditions.md},
     * "Registries"): one registry per file. {@code source} names who supplied it, for the vocabulary report.
     * {@code same_as} is reserved, so a 1.0 file that uses it is refused.
     */
    public static Parsed<Registry> registry(Json doc, String source) {
        Check c = new Check();
        Json.Obj o = c.obj(doc, "");
        if (o == null) return result(null, c);
        c.keys(o, "", Set.of("format", "format_version", "registry", "entries"));
        format(o, "", "hledjolf.conditions.registry", true, c);
        String name = c.nonEmptyStr(c.req(o, "registry", ""), "/registry");
        if (name != null && !Check.registryName(name)) {
            c.add("bad_id", "/registry", "\"" + name + "\": a registry name is lower case, [a-z][a-z0-9_]*");
        }
        List<RegistryEntry> entries = new ArrayList<>();
        List<Json> items = c.arr(c.req(o, "entries", ""), "/entries");
        Set<String> ids = new HashSet<>();
        if (items != null) {
            for (int i = 0; i < items.size(); i++) {
                String ep = Check.at("/entries", i);
                Json.Obj e = c.obj(items.get(i), ep);
                if (e == null) continue;
                c.keys(e, ep, Set.of("id", "in", "same_as"));
                if (e.has("same_as")) {
                    c.add("reserved", Check.at(ep, "same_as"), "same_as is reserved for the equivalence pass; "
                            + "a 1.0 registry file can't use it");
                }
                String id = c.nonEmptyStr(c.req(e, "id", ep), Check.at(ep, "id"));
                if (id != null && !Check.namespaced(id)) {
                    c.add("bad_id", Check.at(ep, "id"), "\"" + id + "\" needs a namespace (\"core:rain\", \"hytale:...\")");
                }
                if (id != null && !ids.add(id)) c.add("duplicate_id", Check.at(ep, "id"), "\"" + id + "\" appears twice");
                List<String> in = namespacedList(e, "in", ep, c);
                if (id != null) entries.add(new RegistryEntry(id, in));
            }
        }
        return result(new Registry(name, source, entries), c);
    }

    // ------------------------------------------------------------------------------------------------- profiles

    public static Parsed<Profile> profile(Json doc) {
        Check c = new Check();
        Json.Obj o = c.obj(doc, "");
        if (o == null) return result(null, c);
        c.keys(o, "", Set.of("format", "format_version", "profile", "game_version", "stages", "unsupported"));
        format(o, "", "spellcrafter.profile", true, c);
        String id = c.nonEmptyStr(c.req(o, "profile", ""), "/profile");
        String gameVersion = o.has("game_version") ? c.str(o.get("game_version"), "/game_version") : null;
        List<String> stages = c.strList(c.req(o, "stages", ""), "/stages");
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < stages.size(); i++) {
            String s = stages.get(i);
            if (!STAGES.contains(s)) c.add("invalid", Check.at("/stages", i), "unknown stage \"" + s + "\"");
            else if (!seen.add(s)) c.add("invalid", Check.at("/stages", i), "stage \"" + s + "\" appears twice");
        }
        Map<String, String> unsupported = new LinkedHashMap<>();
        if (o.has("unsupported")) {
            Json.Obj u = c.obj(o.get("unsupported"), "/unsupported");
            if (u != null) {
                for (Map.Entry<String, Json> e : u.members().entrySet()) {
                    String up = Check.at("/unsupported", e.getKey());
                    String field = e.getKey();
                    String to = c.str(e.getValue(), up);
                    if (!FIELD_STAGE.containsKey(field)) {
                        c.add("unknown_key", up, "\"" + field + "\" isn't a rider field (plus, bonus_pct, more_pct)");
                        continue;
                    }
                    if (to == null) continue;
                    if (stages.contains(FIELD_STAGE.get(field))) {
                        c.add("invalid", up, "\"" + field + "\"'s stage is in the profile, so it isn't unsupported");
                    } else if (to.equals("refuse")) {
                        unsupported.put(field, to);
                    } else if (field.equals("plus") || !(to.equals("increase") || to.equals("more"))
                            || !stages.contains(to)) {
                        c.add("invalid", up, "\"" + field + "\" can be refused, or folded into increase or more when "
                                + "the profile runs that stage");
                    } else {
                        unsupported.put(field, to);
                    }
                }
            }
        }
        return result(new Profile(id, gameVersion, stages, unsupported), c);
    }

    /**
     * Where a rider field goes under a profile: its own stage, the stage it folds into, or {@code refuse}. A field
     * whose stage is left out and that the profile doesn't mention is refused.
     */
    public static String stageFor(Profile profile, String field) {
        String own = FIELD_STAGE.get(field);
        if (profile.stages().contains(own)) return own;
        String to = profile.unsupported().get(field);
        return to == null ? "refuse" : to;
    }

    /** The problems a spell has under a profile: rider fields whose stage this game refuses (section 9). */
    public static List<Problem> checkProfile(Spell spell, Profile profile) {
        List<Problem> out = new ArrayList<>();
        for (int i = 0; i < spell.riders().size(); i++) {
            Rider r = spell.riders().get(i);
            unsupported(r, "/riders/" + i, profile, out);
        }
        return out;
    }

    /** As {@link #checkProfile}, for a circumstance's rider. */
    public static List<Problem> checkProfile(Circumstance circ, Profile profile) {
        List<Problem> out = new ArrayList<>();
        unsupported(circ.rider(), "", profile, out);
        return out;
    }

    private static void unsupported(Rider r, String p, Profile profile, List<Problem> out) {
        if (!r.plus().isEmpty() && stageFor(profile, "plus").equals("refuse")) {
            out.add(new Problem("unsupported_stage", p + "/plus", "profile " + profile.id() + " doesn't support plus"));
        }
        if (!r.bonusPct().isEmpty() && stageFor(profile, "bonus_pct").equals("refuse")) {
            out.add(new Problem("unsupported_stage", p + "/bonus_pct", "profile " + profile.id() + " doesn't support bonus_pct"));
        }
        if (!r.morePct().isEmpty() && stageFor(profile, "more_pct").equals("refuse")) {
            out.add(new Problem("unsupported_stage", p + "/more_pct", "profile " + profile.id() + " doesn't support more_pct"));
        }
    }

    // ------------------------------------------------------------------------------------------------- settings

    /** Server settings; anything left out takes its default (section 10). */
    public static Parsed<ServerSettings> settings(Json doc) {
        Check c = new Check();
        Json.Obj o = c.obj(doc, "");
        if (o == null) return result(null, c);
        c.keys(o, "", Set.of("format", "format_version", "caps", "overrides"));
        format(o, "", "spellcrafter.settings", true, c);
        ServerCaps d = ServerCaps.DEFAULTS;
        ServerCaps caps = d;
        if (o.has("caps")) {
            Json.Obj k = c.obj(o.get("caps"), "/caps");
            if (k != null) {
                c.keys(k, "/caps", Set.of("params", "bonus_pct", "more_pct", "added_payloads", "riders_per_cast",
                        "circumstances_per_cast", "condition_depth", "group_depth", "recheck_min_ms", "burst_targets"));
                // The defaults' params caps stay unless the server sets the same param.
                Map<String, Long> params = new LinkedHashMap<>(d.params());
                if (k.has("params")) params.putAll(c.milliMap(k.get("params"), "/caps/params"));
                long each = d.moreEach();
                long perParam = d.morePerParam();
                if (k.has("more_pct")) {
                    Json.Obj m = c.obj(k.get("more_pct"), "/caps/more_pct");
                    if (m != null) {
                        c.keys(m, "/caps/more_pct", Set.of("each", "per_param"));
                        each = orDefault(m.has("each") ? c.pct(m.get("each"), "/caps/more_pct/each") : null, each);
                        perParam = orDefault(m.has("per_param") ? c.whole(m.get("per_param"), "/caps/more_pct/per_param")
                                : null, perParam);
                    }
                }
                caps = new ServerCaps(params,
                        wholeOr(k, "bonus_pct", d.bonusPct(), c, true),
                        each, perParam,
                        wholeOr(k, "added_payloads", d.addedPayloads(), c, false),
                        wholeOr(k, "riders_per_cast", d.ridersPerCast(), c, false),
                        wholeOr(k, "circumstances_per_cast", d.circumstancesPerCast(), c, false),
                        wholeOr(k, "condition_depth", d.conditionDepth(), c, false),
                        wholeOr(k, "group_depth", d.groupDepth(), c, false),
                        wholeOr(k, "recheck_min_ms", d.recheckMinMs(), c, false),
                        wholeOr(k, "burst_targets", d.burstTargets(), c, false));
            }
        }
        Overrides ov = Overrides.NONE;
        if (o.has("overrides")) {
            Json.Obj v = c.obj(o.get("overrides"), "/overrides");
            if (v != null) {
                String p = "/overrides";
                c.keys(v, p, Set.of("ignore_gates", "ignore_gate_types", "ignore_unknown_gates", "ignore_components",
                        "ignore_component_items", "ignore_foci", "ignore_all_components", "circumstances_off",
                        "circumstance_mods_off"));
                ov = new Overrides(c.optStrList(v, "ignore_gates", p), c.optStrList(v, "ignore_gate_types", p),
                        boolOr(v, "ignore_unknown_gates", p, c), c.optStrList(v, "ignore_components", p),
                        c.optStrList(v, "ignore_component_items", p), boolOr(v, "ignore_foci", p, c),
                        boolOr(v, "ignore_all_components", p, c), c.optStrList(v, "circumstances_off", p),
                        c.optStrList(v, "circumstance_mods_off", p));
            }
        }
        return result(new ServerSettings(caps, ov), c);
    }

    private static long orDefault(Long v, long d) {
        return v == null ? d : v;
    }

    private static long wholeOr(Json.Obj o, String key, long d, Check c, boolean pct) {
        if (!o.has(key)) return d;
        Long v = pct ? c.pct(o.get(key), Check.at("/caps", key)) : c.whole(o.get(key), Check.at("/caps", key));
        if (v != null && v < 0) c.add("out_of_range", Check.at("/caps", key), "a cap can't be negative");
        return v == null ? d : v;
    }

    private static boolean boolOr(Json.Obj o, String key, String p, Check c) {
        if (!o.has(key)) return false;
        Boolean b = c.bool(o.get(key), Check.at(p, key));
        return b != null && b;
    }

    // ------------------------------------------------------------------------------------------------- helpers

    private static <T> Parsed<T> result(T value, Check c) {
        if (!c.ok() || value == null) {
            List<Problem> problems = c.problems.isEmpty()
                    ? List.of(new Problem("invalid", "", "not a valid document")) : c.problems;
            return new Parsed.Invalid<>(problems);
        }
        return new Parsed.Ok<>(value, c.warnings);
    }

    /**
     * A document's {@code format} and {@code format_version} (section 3.1). Required unless the document sits inside
     * a pack, where they're optional but must be right if given. Returns the version.
     */
    private static String format(Json.Obj o, String p, String expected, boolean required, Check c) {
        if (!required && !o.has("format") && !o.has("format_version")) return null;
        String format = c.str(c.req(o, "format", p), Check.at(p, "format"));
        if (format != null && !format.equals(expected)) {
            String hint = format.equals(expected + "/1")
                    ? " (the /1 form is gone: write \"format\": \"" + expected + "\", \"format_version\": \"1.0\")" : "";
            c.add("unsupported_format", Check.at(p, "format"), "expected \"" + expected + "\", found \"" + format + "\""
                    + hint);
        }
        return formatVersion(o, p, c);
    }

    private static String formatVersion(Json.Obj o, String p, Check c) {
        String v = c.str(c.req(o, "format_version", p), Check.at(p, "format_version"));
        if (v == null) return null;
        int major = Check.major(v);
        if (major < 0) c.add("invalid", Check.at(p, "format_version"), "format_version is MAJOR.MINOR");
        else if (major != 1) c.add("unsupported_version", Check.at(p, "format_version"), "this core reads major version 1");
        return v;
    }

    private static String qualified(Json.Obj o, String key, String p, Check c) {
        String id = c.nonEmptyStr(c.req(o, key, p), Check.at(p, key));
        if (id != null && !Check.qualifiedId(id)) {
            badId(c, Check.at(p, key), id);
            return null;
        }
        return id;
    }

    private static void badId(Check c, String p, String id) {
        c.add("bad_id", p, "\"" + id + "\" isn't <mod id>/<name>, with a lower-case name");
    }

    private static String localId(Json.Obj o, String p, Check c) {
        String id = c.nonEmptyStr(c.req(o, "id", p), Check.at(p, "id"));
        if (id != null && !Check.localName(id)) {
            c.add("bad_id", Check.at(p, "id"), "\"" + id + "\": lower-case letters, digits, _ . - only");
            return null;
        }
        return id;
    }

    private static List<String> qualifiedList(Json.Obj o, String key, String p, Check c) {
        List<String> out = c.optStrList(o, key, p);
        for (int i = 0; i < out.size(); i++) {
            if (!Check.qualifiedId(out.get(i))) badId(c, Check.at(Check.at(p, key), i), out.get(i));
        }
        return out;
    }

    private static List<String> namespacedList(Json.Obj o, String key, String p, Check c) {
        List<String> out = c.optStrList(o, key, p);
        for (int i = 0; i < out.size(); i++) {
            if (!Check.namespaced(out.get(i))) {
                c.add("bad_id", Check.at(Check.at(p, key), i), "\"" + out.get(i) + "\" needs a namespace (\"spellcrafter:fire\")");
            }
        }
        return new ArrayList<>(new LinkedHashSet<>(out));
    }

    private static long nonNegative(Long v, String p, Check c) {
        if (v == null) return 0;
        if (v < 0) c.add("out_of_range", p, "can't be negative");
        return v;
    }
}
