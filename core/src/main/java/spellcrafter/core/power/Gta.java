package spellcrafter.core.power;

import static spellcrafter.core.power.Fixed.ONE;
import static spellcrafter.core.power.Fixed.div;
import static spellcrafter.core.power.Fixed.mul;

import java.util.List;

/**
 * Game Tactics Analysis's method for effect and spell power (GTA {@code tools/effect_spell_power.py}, method in its
 * 05-effect-and-spell-power.md), ported to fixed point ({@link Fixed}). Function for function with the reference, so
 * the port reproduces GTA's test vectors ({@code effect-spell-vectors.json}) to their printed precision.
 *
 * <p>Units: <b>steps</b>; one step is ×1.25 in time-to-kill terms. All reals are {@link Fixed} billionths; times are
 * in seconds as reals; {@link #INF} stands for an unbounded ratio (a cliff).
 */
public final class Gta {
    private Gta() {
    }

    /** An unbounded ratio: an effect that removes all incoming damage while it lasts. */
    public static final long INF = Long.MAX_VALUE;

    /** The reference fight for a zone: health, resistance, fight length (s) and a reference hit. */
    public record Reference(long hp, long resist, long fightS, long hit) {
    }

    /** Zone 1: a full iron set and an iron sword's light hit (GTA's calibration point). */
    public static final Reference ZONE_1 = new Reference(Fixed.of(146), Fixed.dec(25, 2), Fixed.of(20), Fixed.dec(1267, 2));

    public static final long CAST_HITS = Fixed.of(2);
    public static final long FIGHT_CASTS = Fixed.of(10);
    public static final long POOL_CASTS = Fixed.of(8);
    public static final long DR_WINDOW_S = Fixed.of(15);
    /** Oblivion's magnitude exponent: one big spell costs more than two small ones. */
    public static final long ALPHA = Fixed.dec(128, 2);
    /** SpellCrafter's price of a reference cast in Hytale mana (design/samples, release-1.0.md section 3). */
    public static final long MANA_PER_REFERENCE_CAST = Fixed.dec(125, 1);

    public static long steps(long ratio) {
        return ratio == INF ? INF : Fixed.steps(ratio);
    }

    /** Damage per second the reference player takes after mitigation: half their health over the fight. */
    public static long incomingDps(Reference ref) {
        return div(mul(ONE / 2, ref.hp()), ref.fightS());
    }

    /** Share of the fight an effect is active. {@code durationS} null is the whole fight; a cooldown limits reapplying. */
    public static long uptime(Long durationS, Reference ref, Long cooldownS) {
        long t = ref.fightS();
        if (durationS == null) return ONE;
        if (cooldownS != null && cooldownS > 0) {
            long active = 0, start = 0;
            while (start < t) {
                active += Fixed.min(Fixed.min(durationS, cooldownS), t - start);
                start += cooldownS;
            }
            return Fixed.min(ONE, div(active, t));
        }
        return Fixed.min(ONE, div(durationS, t));
    }

    static long fightRatioOutput(long active, long u) {
        return ONE + mul(u, active - ONE);
    }

    static long fightRatioSurvival(long active, long u) {
        long inv = active == INF ? 0 : div(ONE, active);
        long denom = (ONE - u) + mul(u, inv);
        return denom <= 0 ? INF : div(ONE, denom);
    }

    // --------------------------------------------------------------------------------------------- effect parts

    /** One part of an effect on its bearer: what it changes ({@code output} or {@code survival}) and by how much. */
    public record Part(boolean output, long ratio) {
    }

    /** +pct% damage dealt. */
    public static Part damagePct(long pct) {
        return new Part(true, ONE + div(pct, Fixed.of(100)));
    }

    /** +points per hit. */
    public static Part damageFlat(long points, Reference ref) {
        return new Part(true, ONE + div(points, ref.hit()));
    }

    /** +pct% resistance to one cause; {@code share} of incoming damage is that cause; {@code base} null is the zone's. */
    public static Part resistPct(long pct, Reference ref, Long base, long share) {
        long r0 = base == null ? ref.resist() : base;
        long r1 = r0 + div(pct, Fixed.of(100));
        if (r1 >= ONE) return new Part(false, INF);
        long perCause = div(ONE - r0, ONE - r1);
        return new Part(false, div(ONE, (ONE - share) + div(share, perCause)));
    }

    public static Part maxHealth(long points, Reference ref) {
        return new Part(false, div(ref.hp() + points, ref.hp()));
    }

    public static Part regen(long hpPerS, Reference ref) {
        long d = incomingDps(ref);
        if (hpPerS >= d) return new Part(false, INF);
        return new Part(false, div(d, d - hpPerS));
    }

    public static Part cannotBeHurt() {
        return new Part(false, INF);
    }

    /** Stun, pacify, disarm on the enemy: its damage stops. */
    public static Part enemyCannotAttack() {
        return new Part(false, INF);
    }

    /** A fight-level rating. {@code survivalRatio} and {@code steps} are null for a cliff. */
    public record Rating(long uptime, long outputRatio, Long survivalRatio, Long steps, boolean cliff) {
    }

    /** Rates an effect of {@code parts}, active {@code durationS} (null: the whole fight), recast every {@code cooldownS}. */
    public static Rating rate(List<Part> parts, Long durationS, Reference ref, Long cooldownS) {
        long u = uptime(durationS, ref, cooldownS);
        long out = ONE, surv = ONE;
        for (Part p : parts) {
            if (p.output()) {
                out = mul(out, fightRatioOutput(p.ratio(), u));
            } else {
                long s = fightRatioSurvival(p.ratio(), u);
                surv = surv == INF || s == INF ? INF : mul(surv, s);
            }
        }
        // As the reference: the printed ratios are rounded first, steps come from the unrounded product.
        if (surv == INF) return new Rating(u, out, null, null, true);
        return new Rating(u, out, surv, Fixed.steps(mul(out, surv)), false);
    }

    // --------------------------------------------------------------------------------------------- spells

    /** Expected targets of an area of this radius (blocks), capped at the pack. */
    public static long areaTargets(long radius, long pack) {
        return Fixed.min(pack, ONE + mul(Fixed.dec(15, 2), mul(radius, radius)));
    }

    public static long gateMultiplier(long p) {
        return Fixed.pow(p, ONE / 2);
    }

    public static long referenceCast(Reference ref) {
        return mul(CAST_HITS, ref.hit());
    }

    /**
     * Damage-equivalent value of an effect: the share of a reference fight it buys. GTA's reference uses the rating's
     * ratios as printed (to 4 decimals), so this does too. Null for a cliff.
     */
    public static Long effectValue(Rating r, Reference ref) {
        if (r.cliff()) return null;
        long rho = mul(Fixed.rounded(r.outputRatio(), 4), Fixed.rounded(r.survivalRatio(), 4));
        return mul(mul(rho - ONE, FIGHT_CASTS), referenceCast(ref));
    }

    /** The per-cast grade, in steps: a cast worth one reference cast is grade 0. */
    public static long spellGrade(long value, Reference ref) {
        return Fixed.steps(div(value, referenceCast(ref)));
    }

    /** Mana cost in reference casts: (value / reference cast)^1.28, discounted by each gate's probability. */
    public static long spellCost(long value, Reference ref, List<Long> gatePs) {
        long c = Fixed.pow(div(value, referenceCast(ref)), ALPHA);
        for (long p : gatePs) c = mul(c, gateMultiplier(p));
        return c;
    }

    // --------------------------------------------------------------------------------------------- placement

    /** How many times a spell is cast in the reference fight: by cooldown (or cast time) and by the mana pool. */
    public static long castsInFight(Reference ref, Long cost, Long cooldownS, long castS) {
        long t = ref.fightS();
        long byTime = cooldownS != null && cooldownS > 0 ? 1 + Fixed.floor(div(t, cooldownS)) : Fixed.floor(div(t, castS));
        long byMana = cost != null && cost > 0 ? Fixed.floor(div(POOL_CASTS, cost)) : byTime;
        return Math.max(1, Math.min(byTime, byMana));
    }

    /** Steps a spell adds to the reference fight: casts × value as a share of the reference enemy's health. */
    public static long fightGrade(long valuePerCast, long casts, Reference ref) {
        long w = mul(FIGHT_CASTS, referenceCast(ref));
        return Fixed.steps(ONE + div(casts * valuePerCast, w));
    }

    /** Seconds of the fight crowd control holds one target, recast every {@code cooldownS}, with diminishing returns. */
    public static long ccUptime(long durationS, Reference ref, Long cooldownS, boolean diminishing) {
        long t = ref.fightS();
        if (cooldownS == null || cooldownS <= 0) return Fixed.min(durationS, t);
        long total = 0, now = 0;
        int chain = 0;
        Long last = null;
        long step = Fixed.max(cooldownS, ONE / 1000);
        while (now < t) {
            if (diminishing && last != null && now - last < DR_WINDOW_S) chain++;
            else chain = 0;
            if (chain <= 2) {
                long d = durationS >> chain;
                total += Fixed.min(d, t - now);
                last = now;
            }
            now += step;
        }
        return Fixed.min(total, t);
    }

    /** Crowd control's placement: the share of the pack's damage it stops, times its uptime. INF for all of it. */
    public static long ccFightGrade(long durationS, Reference ref, Long cooldownS, long shareCaught, boolean diminishing) {
        long u = div(ccUptime(durationS, ref, cooldownS, diminishing), ref.fightS());
        long removed = mul(u, Fixed.min(ONE, shareCaught));
        return removed >= ONE ? INF : Fixed.steps(div(ONE, ONE - removed));
    }

    public static long expectedShareCaught(long targets, long pack) {
        return Fixed.min(ONE, div(targets, pack));
    }
}
