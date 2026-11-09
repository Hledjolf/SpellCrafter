package spellcrafter.core.editor;

import static spellcrafter.core.power.Fixed.ONE;
import static spellcrafter.core.power.Fixed.div;
import static spellcrafter.core.power.Fixed.mul;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import spellcrafter.core.eval.CastResult;
import spellcrafter.core.eval.CastResult.Planned;
import spellcrafter.core.json.Json;
import spellcrafter.core.model.Model.Pick;
import spellcrafter.core.model.Model.Targeting;
import spellcrafter.core.power.Fixed;
import spellcrafter.core.power.Gta;
import spellcrafter.core.power.Placement;

/**
 * Turns a cast's payloads into GTA's terms (design/editor.md section 4a): a value per cast, and what kind of spell it
 * is for placement. One payload at a time, with its final params (after riders and caps); values add.
 */
final class Rater {
    private Rater() {
    }

    /** Thousandths to billionths. */
    static long fx(long milli) {
        return milli * 1_000_000L;
    }

    /**
     * A cast's worth. {@code value} is the best case (every chance at 100, the best pick: it prices the per-cast
     * grade), {@code expected} the expected value (chance × value, each pick option by its weight share: it prices the
     * cost), both null for a cliff (an unbounded rating). {@code unrated} lists why some payload couldn't be rated at
     * all (then the readout has no grade); {@code flags} are warnings that still allow a grade.
     */
    record Worth(Long value, Long expected, Placement.Kind kind, List<String> unrated, List<String> flags) {
    }

    /** GTA's expected targets for a burst of radius {@code r} (Fixed): {@code min(pack, 1 + 0.15 r²)}, pack 5. */
    static long areaTargets(long r) {
        return Fixed.min(Fixed.of(5), ONE + mul(Fixed.dec(15, 2), mul(r, r)));
    }

    /**
     * Rates a cast plan (spell-format 7b, "Rating"): each payload for whoever it lands on ({@code caster} payloads,
     * and a self spell's, for the caster), an {@code area} payload times the burst's expected targets.
     */
    static Worth worth(CastResult.CastPlan plan, Map<String, Effect> catalog, Gta.Reference ref) {
        String kindOfDelivery = plan.delivery().op();
        Long radius = plan.delivery().params().get("burst.radius");
        long targets = radius == null ? ONE : areaTargets(fx(radius));
        Placement.Kind kind = null;
        Set<String> unrated = new LinkedHashSet<>();
        Set<String> flags = new LinkedHashSet<>();
        Map<String, Long> value = new LinkedHashMap<>(); // payload id -> value × targets; null for a cliff
        for (Planned p : plan.payloads()) {
            if (p.isReroll()) continue;
            if (!"BuffEffects".equals(p.provider()) || !"apply".equals(p.op())) {
                unrated.add("unrated: " + p.provider() + " " + p.op() + " (only BuffEffects' apply is rated)");
                continue;
            }
            String id = p.fields().get("effect") instanceof Json.Str s ? s.value() : null;
            Effect e = id == null ? null : catalog.get(id);
            if (e == null) {
                unrated.add("unrated: " + id + " isn't in BuffEffects' catalog");
                continue;
            }
            if (!e.described()) {
                unrated.add("unrated: " + id + " has no parts (BuffEffects didn't describe it)");
                continue;
            }
            String target = Targeting.target(p.fields(), kindOfDelivery);
            One one = one(e, p.params(), target.equals("caster"), ref, flags);
            value.put(p.id(), one.value == null ? null : target.equals("area") ? mul(one.value, targets) : one.value);
            if (kind == null && one.kind != null) kind = one.kind;
        }
        Long best = 0L, expected = 0L;
        for (Planned p : plan.payloads()) {
            if (p.isReroll() || Targeting.pick(p.fields()) != null || !value.containsKey(p.id())) continue;
            Long v = value.get(p.id());
            best = add(best, v);
            expected = add(expected, v == null ? null : v * Targeting.chance(p.fields()) / 100);
        }
        for (Pick g : plan.picks()) {
            List<Planned> options = new ArrayList<>();
            for (Planned p : plan.payloads()) {
                if (g.id().equals(Targeting.pick(p.fields())) && (p.isReroll() || value.containsKey(p.id()))) options.add(p);
            }
            Long b = pickBest(options, Set.of(), value);
            Long x = pickExpected(options, Set.of(), value);
            best = add(best, b == null ? null : b * g.rolls());
            expected = add(expected, x == null ? null : x * g.rolls());
        }
        if (kind instanceof Placement.Movement) flags.add("mobility");
        if (best == null) flags.add("cliff");
        return new Worth(best, expected, kind == null ? new Placement.Ordinary() : kind, List.copyOf(unrated),
                List.copyOf(flags));
    }

    private static Long add(Long a, Long b) {
        return a == null || b == null ? null : a + b;
    }

    /** One roll's best outcome: the best option, a reroll worth its count of the best of the rest. */
    private static Long pickBest(List<Planned> options, Set<String> ignore, Map<String, Long> value) {
        Long best = 0L;
        for (Planned o : options) {
            if (ignore.contains(o.id())) continue;
            Long v;
            if (o.isReroll()) {
                Set<String> more = new java.util.HashSet<>(ignore);
                more.add(o.id());
                Long r = pickBest(options, more, value);
                v = r == null ? null : r * Targeting.reroll(o.fields());
            } else {
                v = value.get(o.id());
            }
            if (v == null) return null;
            best = Math.max(best, v);
        }
        return best;
    }

    /** One roll's expected outcome: each option by its weight share, a reroll as its count of expected rolls. */
    private static Long pickExpected(List<Planned> options, Set<String> ignore, Map<String, Long> value) {
        long total = 0;
        for (Planned o : options) if (!ignore.contains(o.id())) total += Targeting.weight(o.fields());
        if (total == 0) return 0L;
        long sum = 0;
        for (Planned o : options) {
            if (ignore.contains(o.id())) continue;
            Long v;
            if (o.isReroll()) {
                Set<String> more = new java.util.HashSet<>(ignore);
                more.add(o.id());
                Long r = pickExpected(options, more, value);
                v = r == null ? null : r * Targeting.reroll(o.fields());
            } else {
                v = value.get(o.id());
            }
            if (v == null) return null;
            sum += v * Targeting.weight(o.fields());
        }
        return sum / total;
    }

    private record One(Long value, Placement.Kind kind) {
    }

    private static One one(Effect e, Map<String, Long> params, boolean onSelf, Gta.Reference ref, Set<String> flags) {
        long s = params.containsKey("strength") ? fx(params.get("strength")) : ONE;
        Long durMs = params.containsKey("duration") ? params.get("duration") : e.durationMs();
        Long dur = durMs == null ? null : fx(durMs); // null: until removed, so the whole fight
        long fight = ref.fightS();
        long direct = 0;
        List<Gta.Part> instant = new ArrayList<>();
        List<Gta.Part> lasting = new ArrayList<>();
        boolean lockAll = false, disablesAttack = false;
        Long speed = null;
        long resource = 0;
        for (Json.Obj part : e.parts()) {
            String type = str(part, "type");
            if (type == null) continue;
            switch (type) {
                case "hit" -> direct += mul(num(part, "amount"), s);
                case "damage_over_time" -> {
                    long interval = num(part, "interval");
                    long d = dur == null ? fight : Fixed.min(dur, fight);
                    long ticks = interval > 0 ? Fixed.floor(div(d, interval) + 1) : 0; // +1e-9, as make_samples
                    direct += ticks * mul(num(part, "amount"), s);
                }
                case "instant" -> {
                    String stat = str(part, "stat");
                    long amount = mul(num(part, "amount"), s);
                    boolean pct = "percent".equals(str(part, "unit"));
                    if ("health".equals(stat)) {
                        instant.add(Gta.maxHealth(pct ? mul(div(amount, Fixed.of(100)), ref.hp()) : amount, ref));
                    } else if ("mana".equals(stat)) {
                        resource += div(amount, Gta.MANA_PER_REFERENCE_CAST); // a 100 pool: percent and points agree
                    } else if ("stamina".equals(stat) && !pct) {
                        instant.add(Gta.maxHealth(amount, ref));
                    } else {
                        flags.add("unrated part: instant " + stat + (pct ? " (percent)" : ""));
                    }
                }
                case "resist" -> lasting.add(Gta.resistPct(mul(num(part, "amount"), s) * 100, ref, 0L, ONE));
                case "outgoing" -> {
                    long m = num(part, "multiplier");
                    if (onSelf) {
                        lasting.add(Gta.damagePct(mul(m - ONE, s) * 100));
                    } else {
                        long weakened = ONE - mul(ONE - m, s);
                        if (weakened > 0) lasting.add(new Gta.Part(false, div(ONE, weakened)));
                        else lasting.add(Gta.enemyCannotAttack());
                    }
                }
                case "lock_movement" -> {
                    if (part.get("all") instanceof Json.Bool b && b.value()) lockAll = true;
                    else flags.add("unrated part: lock_movement without all");
                }
                case "disable_actions" -> {
                    if (part.get("actions") instanceof Json.Arr a && a.items().contains(new Json.Str("attack"))) {
                        disablesAttack = true;
                    } else {
                        flags.add("unrated part: disable_actions without attack");
                    }
                }
                case "speed" -> speed = num(part, "multiplier");
                case "stat" -> {
                    String stat = str(part, "stat");
                    long amount = num(part, "amount");
                    boolean mult = "multiply".equals(str(part, "op"));
                    if ("health".equals(stat)) {
                        long points = mult ? mul(amount - ONE, ref.hp()) : amount;
                        lasting.add(Gta.maxHealth(mul(points, s), ref));
                    } else if ("speed".equals(stat) && mult) {
                        speed = amount;
                    } else {
                        flags.add("unrated part: stat " + stat);
                    }
                }
                case "regen" -> {
                    long interval = num(part, "interval");
                    long amount = mul(num(part, "amount"), s);
                    if ("health".equals(str(part, "stat")) && interval > 0) {
                        boolean pct = "percent".equals(str(part, "unit"));
                        long points = pct ? mul(div(amount, Fixed.of(100)), ref.hp()) : amount;
                        lasting.add(Gta.regen(div(points, interval), ref));
                    } else {
                        flags.add("unrated part: regen " + str(part, "stat"));
                    }
                }
                case "invulnerable" -> {
                    if (onSelf) lasting.add(Gta.cannotBeHurt());
                }
                case "intangible", "message", "light", "knockback", "behavior" -> {
                    // Cosmetic, or not in GTA's model; nothing to add.
                }
                default -> flags.add("unrated part: " + type);
            }
        }
        Placement.Kind kind = null;
        if (lockAll && disablesAttack && !onSelf) {
            kind = new Placement.CrowdControl(dur == null ? fight : dur);
            lasting.add(Gta.enemyCannotAttack());
        } else if (!onSelf && (speed != null || lockAll)) {
            kind = new Placement.Movement(dur == null ? fight : dur, lockAll ? 0 : speed);
        } else if (onSelf && speed != null) {
            kind = new Placement.Movement(dur == null ? fight : dur, speed);
        } else if (resource > 0) {
            kind = new Placement.Resource(resource);
        }
        Long value = direct;
        Long a = rated(instant, null, ref);
        Long b = rated(lasting, dur, ref);
        if (a == null || b == null) value = null;
        else value += a + b;
        return new One(value, kind);
    }

    /** The value of parts active {@code dur} (null: the whole fight); 0 for none, null for a cliff. */
    private static Long rated(List<Gta.Part> parts, Long dur, Gta.Reference ref) {
        if (parts.isEmpty()) return 0L;
        Gta.Rating r = Gta.rate(parts, dur, ref, null);
        return Gta.effectValue(r, ref);
    }

    private static String str(Json.Obj o, String key) {
        return o.get(key) instanceof Json.Str s ? s.value() : null;
    }

    private static long num(Json.Obj o, String key) {
        return o.get(key) instanceof Json.Num n ? fx(Effect.milli(n)) : 0;
    }
}
