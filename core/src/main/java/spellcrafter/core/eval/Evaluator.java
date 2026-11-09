package spellcrafter.core.eval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import spellcrafter.core.eval.CastInput.Bound;
import spellcrafter.core.eval.CastInput.InForce;
import spellcrafter.core.eval.CastResult.CapHit;
import spellcrafter.core.eval.CastResult.CastPlan;
import spellcrafter.core.eval.CastResult.Fizzle;
import spellcrafter.core.eval.CastResult.Invalid;
import spellcrafter.core.eval.CastResult.Planned;
import spellcrafter.core.eval.CastResult.Reason;
import spellcrafter.core.eval.CastResult.RecheckPlan;
import spellcrafter.core.eval.CastResult.ReportEntry;
import spellcrafter.core.eval.CastResult.Status;
import spellcrafter.core.eval.CastResult.Unavailable;
import spellcrafter.core.format.Formats;
import spellcrafter.core.format.Problem;
import spellcrafter.core.json.Json;
import spellcrafter.core.json.JsonWriter;
import spellcrafter.core.model.Condition;
import spellcrafter.core.model.Model.Circumstance;
import spellcrafter.core.model.Model.Component;
import spellcrafter.core.model.Model.Gate;
import spellcrafter.core.model.Model.Group;
import spellcrafter.core.model.Model.Overrides;
import spellcrafter.core.model.Model.Payload;
import spellcrafter.core.model.Model.Profile;
import spellcrafter.core.model.Model.Rider;
import spellcrafter.core.model.Model.ServerCaps;
import spellcrafter.core.model.Model.Spell;
import spellcrafter.core.num.Milli;

/**
 * Evaluation (design/spell-format.md, section 8), as a pure function: the same {@link CastInput} gives the same
 * result in every port. Two phases: {@link #neededFacts} lists the condition leaves to answer, and
 * {@link #evaluate} takes the answers. Nothing calls back into the game.
 *
 * <p>Ids in the report carry their source: {@code spell/<gate or rider id>}, {@code component/<id>},
 * {@code circ:<circumstance id>}, and {@code circ:<circumstance id>#<gate id>} for a circumstance's gates.
 */
public final class Evaluator {
    /** The registry spell categories are matched through. */
    public static final String SPELL_CATEGORY_REGISTRY = "spell_category";

    private Evaluator() {
    }

    // ------------------------------------------------------------------------------------------------- phase 1

    /** Every leaf a cast needs answered, each once, in a fixed order: gates, components, riders. */
    public static List<Condition.Leaf> neededFacts(CastInput in) {
        return neededFacts(in, false);
    }

    /** With {@code tickOnly}, just the leaves of items marked {@code recheck: tick} (section 11). */
    public static List<Condition.Leaf> neededFacts(CastInput in, boolean tickOnly) {
        Collected k = collect(in);
        Map<String, Condition.Leaf> out = new LinkedHashMap<>();
        if (k.invalid != null) return List.of();
        for (Item g : k.gates) {
            if (g.ignored || (tickOnly && !g.tick)) continue;
            leaves(g.when, out);
        }
        if (!tickOnly) {
            for (CompItem c : k.components) {
                if (!c.ignored) out.putIfAbsent(c.leaf.key(), c.leaf);
            }
        }
        for (Item r : k.riders) {
            if (tickOnly && !r.tick) continue;
            leaves(r.when, out);
        }
        return new ArrayList<>(out.values());
    }

    private static void leaves(Condition c, Map<String, Condition.Leaf> out) {
        if (c == null) return;
        if (c instanceof Condition.Leaf l) out.putIfAbsent(l.key(), l);
        else if (c instanceof Condition.All a) for (Condition x : a.of()) leaves(x, out);
        else if (c instanceof Condition.Any a) for (Condition x : a.of()) leaves(x, out);
        else if (c instanceof Condition.Not n) leaves(n.of(), out);
    }

    // ------------------------------------------------------------------------------------------------- phase 2

    public static CastResult evaluate(CastInput in) {
        Collected k = collect(in);
        if (k.invalid != null) return new Invalid(k.invalid);
        boolean recheck = in.tickFacts() != null;
        Overrides ov = in.settings().overrides();

        // Step 2: gates and components.
        Map<String, ReportEntry> gateRep = new LinkedHashMap<>();
        List<Reason> unknown = new ArrayList<>();
        List<Reason> failed = new ArrayList<>();
        for (Item g : k.gates) {
            if (g.ignored) {
                gateRep.put(g.id, new ReportEntry("gate", Status.IGNORED, "operator", g.source));
                continue;
            }
            Answer a = answer(g.when, facts(in, g.tick));
            if (a.value == Tri.TRUE) {
                gateRep.put(g.id, new ReportEntry("gate", Status.ACTIVE, null, g.source));
            } else if (a.value == Tri.FALSE) {
                gateRep.put(g.id, new ReportEntry("gate", Status.INACTIVE, null, g.source));
                failed.add(new Reason(g.id, "gate", Tri.FALSE, null));
            } else if (ov.ignoreUnknownGates()) {
                gateRep.put(g.id, new ReportEntry("gate", Status.IGNORED, "operator: " + a.reason, g.source));
            } else {
                gateRep.put(g.id, new ReportEntry("gate", Status.UNKNOWN, a.reason, g.source));
                unknown.add(new Reason(g.id, "gate", Tri.UNKNOWN, a.reason));
            }
        }
        Map<String, ReportEntry> compRep = new LinkedHashMap<>();
        List<Component> consume = new ArrayList<>();
        if (!recheck) {
            for (CompItem c : k.components) {
                if (c.ignored) {
                    compRep.put(c.id, new ReportEntry("component", Status.IGNORED, "operator", "spell"));
                    continue;
                }
                Facts.Answer a = in.facts().get(c.leaf);
                if (a.value() == Tri.TRUE) {
                    compRep.put(c.id, new ReportEntry("component", Status.ACTIVE, null, "spell"));
                    if (c.component.consumed()) consume.add(c.component);
                } else if (a.value() == Tri.FALSE) {
                    compRep.put(c.id, new ReportEntry("component", Status.INACTIVE, "missing", "spell"));
                    failed.add(new Reason(c.id, "component", Tri.FALSE, "missing"));
                } else {
                    compRep.put(c.id, new ReportEntry("component", Status.UNKNOWN, a.reason(), "spell"));
                    unknown.add(new Reason(c.id, "component", Tri.UNKNOWN, a.reason()));
                }
            }
        }

        // Step 3: riders.
        Map<String, ReportEntry> riderRep = new LinkedHashMap<>();
        List<Item> active = new ArrayList<>();
        for (Item r : k.riders) {
            Answer a = r.when == null ? new Answer(Tri.TRUE, null) : answer(r.when, facts(in, r.tick));
            Status s = a.value == Tri.TRUE ? Status.ACTIVE : a.value == Tri.FALSE ? Status.INACTIVE : Status.UNKNOWN;
            riderRep.put(r.id, new ReportEntry(r.kind, s, s == Status.UNKNOWN ? a.reason : null, r.source));
            if (s == Status.ACTIVE) active.add(r);
        }
        active.sort(Comparator.comparing((Item i) -> i.id));

        Map<String, ReportEntry> report = new LinkedHashMap<>();
        report.putAll(gateRep);
        report.putAll(compRep);
        report.putAll(riderRep);
        report.putAll(k.unused);

        if (!unknown.isEmpty()) {
            List<Reason> all = new ArrayList<>(unknown);
            all.addAll(failed);
            return new Unavailable(all, report);
        }
        if (!failed.isEmpty()) return new Fizzle(failed, report);

        // Steps 4 and 5: combine each param of each base payload, and of the delivery.
        Eff caps = k.caps;
        List<CapHit> capped = new ArrayList<>(k.capped);
        Spell spell = in.spell();
        List<Planned> payloads = new ArrayList<>();
        for (Payload p : spell.payloads()) {
            Map<String, Long> params = new LinkedHashMap<>();
            for (Map.Entry<String, Long> e : p.params().entrySet()) {
                params.put(e.getKey(), combine(e.getValue(), p.id() + "." + e.getKey(), e.getKey(), p.tags(), active,
                        caps, in.profile(), capped));
            }
            payloads.add(new Planned(p.id(), p.provider(), p.op(), params, p.fields(), null));
        }
        Map<String, Long> dparams = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : spell.delivery().params().entrySet()) {
            String key = "delivery." + e.getKey();
            dparams.put(e.getKey(), combine(e.getValue(), key, key, null, active, caps, in.profile(), capped));
        }

        // Step 6: added payloads, by rider id, then by their order in the rider. Not combined.
        int kept = 0;
        for (Item r : active) {
            for (Payload p : r.rider.add()) {
                String id = r.id + "/" + p.id();
                if (kept < caps.addedPayloads) {
                    payloads.add(new Planned(id, p.provider(), p.op(), new LinkedHashMap<>(p.params()), p.fields(), r.id));
                    kept++;
                } else {
                    capped.add(new CapHit("added_payloads", id, null, null));
                }
            }
        }

        // Step 7: clamp. Floor 0, the params caps, then the providers' bounds.
        List<Planned> finalPayloads = new ArrayList<>();
        for (Planned p : payloads) {
            Map<String, Long> out = new LinkedHashMap<>();
            Map<String, Bound> bounds = in.providerBounds().getOrDefault(p.id(), Map.of());
            for (Map.Entry<String, Long> e : p.params().entrySet()) {
                out.put(e.getKey(), clamp(e.getValue(), p.id() + "." + e.getKey(), e.getKey(),
                        caps.params.get(e.getKey()), bounds.get(e.getKey()), capped));
            }
            finalPayloads.add(new Planned(p.id(), p.provider(), p.op(), out, p.fields(), p.from()));
        }
        Map<String, Long> dout = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : dparams.entrySet()) {
            String key = "delivery." + e.getKey();
            dout.put(e.getKey(), clamp(e.getValue(), key, key, caps.params.get(key), null, capped));
        }
        Planned delivery = new Planned("delivery", "delivery", spell.delivery().kind(), dout, null, null);

        // Step 8: the plan.
        // Rechecks: each item every max(recheck_every, recheck_min_ms); the adapter ticks at the shortest.
        List<String> tick = new ArrayList<>();
        Map<String, Long> every = new LinkedHashMap<>();
        long minMs = in.settings().caps().recheckMinMs();
        long interval = 0;
        List<Item> ticking = new ArrayList<>();
        for (Item g : k.gates) if (g.tick && !g.ignored) ticking.add(g);
        for (Item r : k.riders) if (r.tick) ticking.add(r);
        for (Item i : ticking) {
            long ms = i.everyMs == null ? minMs : Math.max(i.everyMs, minMs);
            tick.add(i.id);
            every.put(i.id, ms);
            interval = interval == 0 ? ms : Math.min(interval, ms);
        }
        RecheckPlan rp = new RecheckPlan(tick, interval, every);
        return new CastPlan(delivery, finalPayloads, consume, in.profile().id(), report, capped, rp,
                spell.delivery().burst(), spell.picks());
    }

    // ------------------------------------------------------------------------------------------------- step 1

    private static final class Item {
        String id;
        String kind;
        String source;
        Condition when;
        boolean tick;
        Long everyMs;
        boolean ignored;
        Rider rider;
    }

    private static final class CompItem {
        String id;
        Component component;
        Condition.Leaf leaf;
        boolean ignored;
    }

    /** The effective caps: for each, the stricter of the spell's and the server's. */
    private static final class Eff {
        long bonusPct;
        long moreEach;
        long morePerParam;
        long addedPayloads;
        Map<String, Long> params = new LinkedHashMap<>();
    }

    private static final class Collected {
        List<Problem> invalid;
        List<Item> gates = new ArrayList<>();
        List<CompItem> components = new ArrayList<>();
        List<Item> riders = new ArrayList<>();
        Map<String, ReportEntry> unused = new LinkedHashMap<>();
        List<CapHit> capped = new ArrayList<>();
        Eff caps;
    }

    private static Collected collect(CastInput in) {
        Collected k = new Collected();
        Spell spell = in.spell();
        List<Problem> problems = Formats.checkProfile(spell, in.profile());
        if (!problems.isEmpty()) {
            k.invalid = problems;
            return k;
        }
        ServerCaps sc = in.settings().caps();
        Overrides ov = in.settings().overrides();
        k.caps = effective(spell, sc);

        // Which circumstances reach this spell.
        Set<String> cats = categories(spell, in);
        Set<String> groups = memberGroups(spell, cats, in.groups());
        List<InForce> candidates = new ArrayList<>();
        for (InForce f : in.circumstances()) {
            Circumstance c = f.circumstance();
            if (!applies(c, spell, cats, groups)) continue;
            String rid = "circ:" + c.id();
            if (ov.circumstancesOff().contains(c.id()) || ov.circumstanceModsOff().contains(modOf(c.id()))) {
                k.unused.put(rid, new ReportEntry("circumstance", Status.OFF, "operator", f.kind()));
                continue;
            }
            List<Problem> cp = Formats.checkProfile(c, in.profile());
            if (!cp.isEmpty()) {
                k.unused.put(rid, new ReportEntry("circumstance", Status.REFUSED, cp.get(0).message(), f.kind()));
                continue;
            }
            candidates.add(f);
        }
        candidates.sort(Comparator.comparingInt(InForce::rank).thenComparing(f -> f.circumstance().id()));

        // Spell items, then circumstances in the fixed order, within the caps.
        for (Gate g : spell.gates()) {
            k.gates.add(gate("spell/" + g.id(), spell.id() + "#" + g.id(), g, "spell", ov));
        }
        for (Component c : spell.components()) k.components.add(component(spell.id(), c, ov));
        for (Rider r : spell.riders()) k.riders.add(rider("spell/" + r.id(), "rider", "spell", r));
        long rules = spell.gates().size() + spell.riders().size();
        int count = 0;
        boolean ridersFull = false;
        for (InForce f : candidates) {
            Circumstance c = f.circumstance();
            String rid = "circ:" + c.id();
            if (count >= sc.circumstancesPerCast()) {
                k.unused.put(rid, new ReportEntry("circumstance", Status.CAPPED, "circumstances_per_cast", f.kind()));
                k.capped.add(new CapHit("circumstances_per_cast", rid, null, null));
                continue;
            }
            long cost = c.gates().size() + 1;
            if (ridersFull || rules + cost > sc.ridersPerCast()) {
                ridersFull = true;
                k.unused.put(rid, new ReportEntry("circumstance", Status.CAPPED, "riders_per_cast", f.kind()));
                k.capped.add(new CapHit("riders_per_cast", rid, null, null));
                continue;
            }
            rules += cost;
            count++;
            for (Gate g : c.gates()) {
                k.gates.add(gate(rid + "#" + g.id(), c.id() + "#" + g.id(), g, f.kind(), ov));
            }
            k.riders.add(rider(rid, "circumstance", f.kind(), c.rider()));
        }
        return k;
    }

    private static Item gate(String id, String ref, Gate g, String source, Overrides ov) {
        Item i = new Item();
        i.id = id;
        i.kind = "gate";
        i.source = source;
        i.when = g.when();
        i.tick = g.recheckTick();
        i.everyMs = g.recheckEveryMs();
        i.ignored = ov.ignoreGates().contains(ref) || usesType(g.when(), ov.ignoreGateTypes());
        return i;
    }

    private static Item rider(String id, String kind, String source, Rider r) {
        Item i = new Item();
        i.id = id;
        i.kind = kind;
        i.source = source;
        i.when = r.when();
        i.tick = r.recheckTick();
        i.everyMs = r.recheckEveryMs();
        i.rider = r;
        return i;
    }

    private static CompItem component(String spellId, Component c, Overrides ov) {
        CompItem i = new CompItem();
        i.id = "component/" + c.id();
        i.component = c;
        i.ignored = ov.ignoreAllComponents() || (ov.ignoreFoci() && !c.consumed())
                || ov.ignoreComponents().contains(spellId + "#" + c.id())
                || ov.ignoreComponentItems().contains(c.item() != null ? c.item() : c.tag());
        Json.Obj leaf = c.item() != null
                ? JsonWriter.obj("type", new Json.Str("item_count"), "subject", new Json.Str("{self}"), "item",
                        new Json.Str(c.item()), "op", new Json.Str("ge"), "value", new Json.Num(Long.toString(c.count())))
                : JsonWriter.obj("type", new Json.Str("item_count"), "subject", new Json.Str("{self}"), "tag",
                        new Json.Str(c.tag()), "op", new Json.Str("ge"), "value", new Json.Num(Long.toString(c.count())));
        i.leaf = new Condition.Leaf("item_count", leaf, JsonWriter.canonical(leaf));
        return i;
    }

    private static boolean usesType(Condition c, List<String> types) {
        if (types.isEmpty() || c == null) return false;
        if (c instanceof Condition.Leaf l) return types.contains(l.type());
        if (c instanceof Condition.All a) {
            for (Condition x : a.of()) if (usesType(x, types)) return true;
            return false;
        }
        if (c instanceof Condition.Any a) {
            for (Condition x : a.of()) if (usesType(x, types)) return true;
            return false;
        }
        return usesType(((Condition.Not) c).of(), types);
    }

    private static Eff effective(Spell spell, ServerCaps sc) {
        Eff e = new Eff();
        e.bonusPct = stricter(spell.caps().bonusPct(), sc.bonusPct());
        e.moreEach = stricter(spell.caps().moreEach(), sc.moreEach());
        e.morePerParam = stricter(spell.caps().morePerParam(), sc.morePerParam());
        e.addedPayloads = stricter(spell.caps().addedPayloads(), sc.addedPayloads());
        e.params.putAll(sc.params());
        for (Map.Entry<String, Long> p : spell.caps().params().entrySet()) {
            Long s = e.params.get(p.getKey());
            e.params.put(p.getKey(), s == null ? p.getValue() : Math.min(s, p.getValue()));
        }
        return e;
    }

    private static long stricter(Long spell, long server) {
        return spell == null ? server : Math.min(spell, server);
    }

    /** The spell's categories, with everything they're in through the spell-category registry. */
    private static Set<String> categories(Spell spell, CastInput in) {
        Set<String> out = new LinkedHashSet<>();
        for (String c : spell.categories()) out.addAll(in.registries().closure(SPELL_CATEGORY_REGISTRY, c));
        return out;
    }

    /**
     * The groups the spell is in (section 6.1): listed by the group, listing the group itself, matching its
     * {@code include}, or in a group it includes; never a group whose {@code exclude} names it.
     */
    static Set<String> memberGroups(Spell spell, Set<String> cats, List<Group> groups) {
        Map<String, Group> byId = new LinkedHashMap<>();
        for (Group g : groups) byId.put(g.id(), g);
        Set<String> in = new LinkedHashSet<>();
        for (String g : spell.groups()) {
            Group grp = byId.get(g);
            if (grp == null || !grp.exclude().contains(spell.id())) in.add(g);
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Group g : groups) {
                if (in.contains(g.id()) || g.exclude().contains(spell.id())) continue;
                boolean member = g.members().contains(spell.id());
                for (String c : g.includeCategories()) if (cats.contains(c)) member = true;
                for (String sub : g.includeGroups()) if (in.contains(sub)) member = true;
                if (member) {
                    in.add(g.id());
                    changed = true;
                }
            }
        }
        return in;
    }

    private static boolean applies(Circumstance c, Spell spell, Set<String> cats, Set<String> groups) {
        if (c.appliesTo().exclude().contains(spell.id())) return false;
        if (c.appliesTo().ids().contains(spell.id())) return true;
        for (String cat : c.appliesTo().categories()) if (cats.contains(cat)) return true;
        for (String g : c.appliesTo().groups()) if (groups.contains(g)) return true;
        return false;
    }

    private static String modOf(String qualifiedId) {
        int slash = qualifiedId.lastIndexOf('/');
        return slash < 0 ? qualifiedId : qualifiedId.substring(0, slash);
    }

    // ------------------------------------------------------------------------------------------------- answers

    private static Facts facts(CastInput in, boolean tick) {
        return tick && in.tickFacts() != null ? in.tickFacts() : in.facts();
    }

    private static final class Answer {
        final Tri value;
        final String reason;

        Answer(Tri value, String reason) {
            this.value = value;
            this.reason = reason;
        }
    }

    /** Evaluates a condition with Kleene logic; an UNKNOWN carries the reason of its first UNKNOWN leaf. */
    private static Answer answer(Condition c, Facts f) {
        if (c instanceof Condition.Leaf l) {
            Facts.Answer a = f.get(l);
            return new Answer(a.value(), a.value() == Tri.UNKNOWN ? (a.reason() != null ? a.reason() : l.type()) : null);
        }
        if (c instanceof Condition.Not n) {
            Answer a = answer(n.of(), f);
            return new Answer(a.value.not(), a.reason);
        }
        boolean all = c instanceof Condition.All;
        List<Condition> of = all ? ((Condition.All) c).of() : ((Condition.Any) c).of();
        Tri v = all ? Tri.TRUE : Tri.FALSE;
        String reason = null;
        for (Condition x : of) {
            Answer a = answer(x, f);
            v = all ? v.and(a.value) : v.or(a.value);
            if (a.value == Tri.UNKNOWN && reason == null) reason = a.reason;
        }
        return new Answer(v, v == Tri.UNKNOWN ? reason : null);
    }

    // ------------------------------------------------------------------------------------------------- steps 4, 5, 7

    /**
     * One param through the profile's stages. Every stage rounds with div_round, and the value is held within
     * ±10^11 thousandths after each, so the next product stays inside ±2^53.
     */
    private static long combine(long base, String target, String key, List<String> tags, List<Item> active, Eff caps,
            Profile profile, List<CapHit> capped) {
        long flat = 0;
        long bonus = 0;
        long bonusToMore = 0;
        boolean anyBonus = false;
        boolean anyBonusToMore = false;
        List<String> moreIds = new ArrayList<>();
        List<Long> more = new ArrayList<>();
        String bonusStage = Formats.stageFor(profile, "bonus_pct");
        String moreStage = Formats.stageFor(profile, "more_pct");
        for (Item r : active) {
            Rider rider = r.rider;
            if (tags != null && !rider.onlyTags().isEmpty() && !overlaps(tags, rider.onlyTags())) continue;
            Long pl = rider.plus().get(key);
            if (pl != null) flat += pl;
            Long bo = rider.bonusPct().get(key);
            if (bo != null) {
                if (bonusStage.equals("more")) {
                    bonusToMore += bo;
                    anyBonusToMore = true;
                } else {
                    bonus += bo;
                    anyBonus = true;
                }
            }
            Long mo = rider.morePct().get(key);
            if (mo != null) {
                if (mo > caps.moreEach) {
                    capped.add(new CapHit("more_pct.each", target + ":" + r.id, mo, caps.moreEach));
                    mo = caps.moreEach;
                }
                moreIds.add(r.id);
                more.add(mo);
            }
        }
        if (more.size() > caps.morePerParam) {
            capped.add(new CapHit("more_pct.per_param", target, (long) more.size(), caps.morePerParam));
            more = new ArrayList<>(more.subList(0, (int) caps.morePerParam));
        }
        if (moreStage.equals("increase")) {
            for (long m : more) bonus += m;
            if (!more.isEmpty()) anyBonus = true;
            more = new ArrayList<>();
        }
        if (anyBonus) bonus = clampBonus(bonus, target, caps, capped);
        if (anyBonusToMore) more.add(0, clampBonus(bonusToMore, target, caps, capped));

        long x = base;
        for (String stage : profile.stages()) {
            if (stage.equals("flat")) {
                x = hold(x + flat);
            } else if (stage.equals("increase")) {
                x = hold(Milli.divRound(x * (100 + bonus), 100));
            } else {
                for (long m : more) x = hold(Milli.divRound(x * (100 + Math.max(m, -100)), 100));
            }
        }
        return x;
    }

    private static long clampBonus(long b, String target, Eff caps, List<CapHit> capped) {
        if (b > caps.bonusPct) {
            capped.add(new CapHit("bonus_pct", target, b, caps.bonusPct));
            return caps.bonusPct;
        }
        return Math.max(b, -100);
    }

    private static long hold(long x) {
        return Math.max(-Milli.MAX_MILLI, Math.min(Milli.MAX_MILLI, x));
    }

    private static boolean overlaps(List<String> a, List<String> b) {
        Set<String> s = new HashSet<>(a);
        for (String x : b) if (s.contains(x)) return true;
        return false;
    }

    private static long clamp(long v, String target, String name, Long cap, Bound bound, List<CapHit> capped) {
        long x = Math.max(0, v);
        if (cap != null && x > cap) {
            capped.add(new CapHit("params." + name, target, x, cap));
            x = cap;
        }
        if (bound != null) {
            if (bound.min() != null && x < bound.min()) {
                capped.add(new CapHit("provider.min", target, x, bound.min()));
                x = bound.min();
            }
            if (bound.max() != null && x > bound.max()) {
                capped.add(new CapHit("provider.max", target, x, bound.max()));
                x = bound.max();
            }
        }
        return x;
    }
}
