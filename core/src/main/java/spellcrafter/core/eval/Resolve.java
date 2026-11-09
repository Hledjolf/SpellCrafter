package spellcrafter.core.eval;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import spellcrafter.core.eval.CastResult.CapHit;
import spellcrafter.core.eval.CastResult.CastPlan;
import spellcrafter.core.eval.CastResult.Planned;
import spellcrafter.core.model.Model.Pick;
import spellcrafter.core.model.Model.ServerCaps;
import spellcrafter.core.model.Model.Targeting;

/**
 * Who gets which payload (design/spell-format.md 7b): from a {@link CastPlan}, the entity the delivery reached, the
 * entities the burst caught, and the cast's seed. Rolls each entity's chances and picks on its own stream, in the
 * order 7b fixes. The adapter finds the entities (the spatial query and {@code affects} are game questions) and makes
 * the seed; everything after that is here, so a cast replays exactly from its seed.
 */
public final class Resolve {
    private Resolve() {
    }

    /** An entity the burst caught, {@code distance} from the burst's centre in thousandths of a block. */
    public record Caught(String id, long distance) {
    }

    /** One payload for one entity. */
    public record Application(String entity, Planned payload) {
    }

    /**
     * The outcome. {@code seed} is the cast's (recorded so the cast can be replayed); {@code entities} the burst's list
     * after the cap, nearest first, each entity's place being its stream; {@code capped} the entities past
     * {@code burst_targets}.
     */
    public record Resolution(long seed, List<String> entities, List<Application> applications, List<CapHit> capped) {
    }

    /**
     * Resolves a cast. {@code struck} is the entity the delivery reached (null when a projectile hit a block or, for a
     * burst, nothing); {@code caught} the burst's entities as the adapter found them, already filtered by
     * {@code affects} and the radius (ignored without a burst). The caster's own payloads ({@code target: caster}) roll
     * on the cast seed itself, the stream before entity 0.
     */
    public static Resolution resolve(CastPlan plan, String caster, String struck, List<Caught> caught, long seed,
            ServerCaps caps) {
        String kind = plan.delivery().op();
        List<CapHit> capped = new ArrayList<>();
        List<String> order = new ArrayList<>();
        Set<String> inBurst = new HashSet<>();
        if (plan.burst() != null) {
            List<Caught> sorted = new ArrayList<>(caught);
            sorted.sort(Comparator.comparingLong(Caught::distance).thenComparing(Caught::id));
            for (Caught c : sorted) {
                if (!inBurst.add(c.id())) continue;
                if (order.size() < caps.burstTargets()) order.add(c.id());
                else {
                    inBurst.remove(c.id());
                    capped.add(new CapHit("burst_targets", c.id(), null, null));
                }
            }
        }
        // The entity the delivery reached gets its target payloads even if the burst didn't list it.
        if (struck != null && !order.contains(struck)) order.add(0, struck);

        List<Application> out = new ArrayList<>();
        for (int i = 0; i < order.size(); i++) {
            String e = order.get(i);
            boolean isStruck = e.equals(struck);
            boolean area = inBurst.contains(e);
            roll(plan, kind, e, Xorshift32.forEntity(seed, i), t -> (t.equals("target") && isStruck) || (t.equals("area") && area), out);
        }
        if (caster != null) roll(plan, kind, caster, new Xorshift32(seed), t -> t.equals("caster"), out);
        return new Resolution(seed & 0xFFFFFFFFL, List.copyOf(order), List.copyOf(out), List.copyOf(capped));
    }

    private interface Aim {
        boolean takes(String target);
    }

    /** One entity's rolls: chances in authored order, then each pick group in the order of {@code picks}. */
    private static void roll(CastPlan plan, String kind, String entity, Xorshift32 rng, Aim aim, List<Application> out) {
        for (Planned p : plan.payloads()) {
            if (p.isReroll() || Targeting.pick(p.fields()) != null) continue;
            if (!aim.takes(Targeting.target(p.fields(), kind))) continue;
            long chance = Targeting.chance(p.fields());
            if (chance >= 100 || rng.next() % 100 < chance) out.add(new Application(entity, p));
        }
        for (Pick g : plan.picks()) {
            List<Planned> options = new ArrayList<>();
            boolean any = false;
            for (Planned p : plan.payloads()) {
                if (!g.id().equals(Targeting.pick(p.fields()))) continue;
                if (p.isReroll()) options.add(p);
                else if (aim.takes(Targeting.target(p.fields(), kind))) {
                    options.add(p);
                    any = true;
                }
            }
            if (!any) continue;
            // Each pending roll carries the reroll options it must ignore.
            Deque<Set<String>> pending = new ArrayDeque<>();
            for (long r = 0; r < g.rolls(); r++) pending.add(Set.of());
            while (!pending.isEmpty()) {
                Set<String> ignore = pending.poll();
                long total = 0;
                for (Planned o : options) if (!ignore.contains(o.id())) total += Targeting.weight(o.fields());
                if (total == 0) continue;
                long at = rng.next() % total;
                for (Planned o : options) {
                    if (ignore.contains(o.id())) continue;
                    at -= Targeting.weight(o.fields());
                    if (at >= 0) continue;
                    if (o.isReroll()) {
                        Set<String> more = new HashSet<>(ignore);
                        more.add(o.id());
                        for (long r = 0; r < Targeting.reroll(o.fields()); r++) pending.add(Set.copyOf(more));
                    } else {
                        out.add(new Application(entity, o));
                    }
                    break;
                }
            }
        }
    }
}
