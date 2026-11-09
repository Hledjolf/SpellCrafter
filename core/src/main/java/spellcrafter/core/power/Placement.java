package spellcrafter.core.power;

import static spellcrafter.core.power.Fixed.ONE;
import static spellcrafter.core.power.Fixed.mul;

import java.util.List;

/**
 * Where a spell sits for 1.0 (release-1.0.md sections 3 and 5): its per-cast grade and mana price, its fight grade,
 * what placed it, and the zone that sets its recipe's tier. The same rule as design/samples/make_samples.py:
 * <ul>
 *   <li><b>crowd control:</b> GTA's ceiling ({@link Gta#ccFightGrade});</li>
 *   <li><b>movement</b> and <b>resources:</b> GTA's interim rules;</li>
 *   <li><b>everything else with a value:</b> the fight grade, but the per-cast grade for spells cast three or more
 *       times a fight (SC-D013);</li>
 *   <li>no value: cosmetic (0) or a cliff (unbounded).</li>
 * </ul>
 * Results are rounded as the samples print them: value to 2 decimals, grades to 2, cost to 3, mana whole (half away
 * from zero, SC-D016: a spell worth exactly one reference cast costs 13).
 */
public final class Placement {
    private Placement() {
    }

    /** What kind of spell, for placement. */
    public sealed interface Kind permits Ordinary, CrowdControl, Movement, Resource {
    }

    /** Placed by its value (damage, heals, buffs, debuffs). */
    public record Ordinary() implements Kind {
    }

    /** Stops the target's damage for {@code durationS}. */
    public record CrowdControl(long durationS) implements Kind {
    }

    /** Changes the target's speed by {@code speedFactor} (0 roots, 0.5 halves, 1.25 hastens) for {@code durationS}. */
    public record Movement(long durationS, long speedFactor) implements Kind {
    }

    /** Restores {@code referenceCasts} worth of mana. */
    public record Resource(long referenceCasts) implements Kind {
    }

    /**
     * A spell to place. {@code value} is its damage-equivalent value per cast (null: a cliff; 0 or less: cosmetic);
     * {@code gatePs} the probabilities of its world-controlled gates; times in seconds.
     */
    public record Input(Long value, List<Long> gatePs, Kind kind, Long cooldownS, long castS) {
        public Input {
            gatePs = List.copyOf(gatePs);
        }
    }

    public static final String CC = "crowd-control ceiling";
    public static final String MOVEMENT = "interim movement rule";
    public static final String RESOURCE = "interim resource rule";
    public static final String PER_CAST = "per-cast ladder (3+ casts a fight, SC-D013)";
    public static final String FIGHT = "fight grade";
    public static final String COSMETIC = "cosmetic";
    public static final String CLIFF = "cliff";

    /**
     * The placement. Fixed-point fields are already rounded (value to 0.01, grades to 0.01, cost to 0.001); null where
     * the samples print null. {@code grade} is null for a cosmetic spell; {@code fightGrade} is {@link Gta#INF} for
     * crowd control that stops everything.
     */
    public record Placed(Long value, Long grade, Long cost, Long mana, Long castsInFight, Long fightGrade,
            String placedBy, String zone) {
    }

    public static Placed place(Input in, Gta.Reference ref) {
        return place(in, ref, null);
    }

    /**
     * As {@link #place(Input, Gta.Reference)}, but priced from {@code expectedValue} (spell-format 13: the per-cast
     * grade is the best case, every rider active; the cost is the expected value, world-controlled riders at their
     * frequency). Null prices from {@code in.value()}.
     */
    public static Placed place(Input in, Gta.Reference ref, Long expectedValue) {
        long rc = Gta.referenceCast(ref);
        // The per-cast summary.
        Long value = null, grade = null, cost = null, mana = null;
        if (in.value() != null && in.value() <= 0) {
            value = 0L;
            cost = Fixed.dec(1, 1);
            mana = Math.max(1, Fixed.roundHalfAway(mul(cost, Gta.MANA_PER_REFERENCE_CAST)));
        } else if (in.value() != null) {
            long priced = expectedValue == null ? in.value() : expectedValue;
            long c = priced > 0 ? Gta.spellCost(priced, ref, in.gatePs()) : Fixed.dec(1, 1);
            value = Fixed.rounded(in.value(), 2);
            grade = Fixed.rounded(Gta.spellGrade(in.value(), ref), 2);
            cost = Fixed.rounded(c, 3);
            mana = Math.max(1, Fixed.roundHalfAway(mul(c, Gta.MANA_PER_REFERENCE_CAST)));
        }
        // The fight grade, and what placed it.
        Long fight;
        Long casts = null;
        String by;
        if (in.kind() instanceof CrowdControl cc) {
            long g = Gta.ccFightGrade(cc.durationS(), ref, in.cooldownS(), ONE, true);
            fight = g == Gta.INF ? Gta.INF : Fixed.rounded(g, 2);
            by = CC;
        } else if (in.kind() instanceof Movement m) {
            long u = Fixed.div(Gta.ccUptime(m.durationS(), ref, in.cooldownS(), false), ref.fightS());
            long removed = mul(mul(u, Math.abs(ONE - m.speedFactor())), ONE / 2);
            fight = Fixed.rounded(Fixed.steps(Fixed.div(ONE, ONE - removed)), 2);
            by = MOVEMENT;
        } else if (in.kind() instanceof Resource r) {
            fight = Fixed.rounded(Gta.fightGrade(mul(r.referenceCasts(), rc), 1, ref), 2);
            by = RESOURCE;
        } else if (value != null && value > 0) {
            casts = Gta.castsInFight(ref, cost, in.cooldownS(), in.castS());
            fight = Fixed.rounded(Gta.fightGrade(value, casts, ref), 2);
            by = casts >= 3 ? PER_CAST : FIGHT;
        } else {
            fight = in.value() == null ? null : 0L;
            by = in.value() == null ? CLIFF : COSMETIC;
        }
        // Movement and resources are priced from a value per cast (SC-D016): a resource by what it restores, movement
        // by the fight-wide ratio its rule gives, as a share of W (the reference enemy's health).
        if (in.kind() instanceof Movement || in.kind() instanceof Resource) {
            long v = in.kind() instanceof Resource r ? mul(r.referenceCasts(), rc)
                    : mul(mul(Gta.FIGHT_CASTS, rc), Fixed.pow(Fixed.dec(125, 2), fight) - ONE);
            value = Fixed.rounded(v, 2);
            cost = Fixed.rounded(Gta.spellCost(v, ref, List.of()), 3);
            mana = Math.max(1, Fixed.roundHalfAway(mul(cost, Gta.MANA_PER_REFERENCE_CAST)));
        }
        Long placing = by.equals(PER_CAST) ? grade : fight;
        String zone = placing == null ? CLIFF : zone(placing);
        return new Placed(value, grade, cost, mana, casts, fight, by, zone);
    }

    /** The zone bands on a placement grade (release-1.0.md section 5). */
    public static String zone(long grade) {
        if (grade == Gta.INF) return "above 4 (operator opt-in)";
        if (grade <= -Fixed.dec(125, 2)) return "start";
        if (grade <= Fixed.dec(25, 2)) return "1";
        if (grade <= Fixed.dec(125, 2)) return "2-3";
        if (grade <= Fixed.dec(225, 2)) return "4";
        return "above 4 (operator opt-in)";
    }
}
