package spellcrafter.hytale.casting;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import spellcrafter.core.eval.CastInput;
import spellcrafter.core.eval.CastResult;
import spellcrafter.core.eval.Evaluator;
import spellcrafter.core.eval.Resolve;
import spellcrafter.core.eval.Facts;
import spellcrafter.core.json.Json;
import spellcrafter.core.model.Condition;
import spellcrafter.core.model.Model;
import spellcrafter.core.model.Model.Spell;
import spellcrafter.core.model.Model.Vessel;
import spellcrafter.core.registry.Registries;

/**
 * SpellCrafter 1.0's casting rules (release-1.0.md section 4a), without the game: the adapter tells it what happened
 * and does what it says. Times are in ms from any steady clock.
 *
 * <ul>
 *   <li><b>Start</b> ({@link #begin}): evaluate; Unavailable or Fizzle stops here, nothing spent, no cooldown. A wand
 *       also needs the mana now (it's spent at completion).</li>
 *   <li><b>Casting</b>: {@link #watch} interrupts a cast whose caster moved more than half a block or changed what
 *       they hold. Damage doesn't interrupt.</li>
 *   <li><b>Completion</b> ({@link #complete}): evaluate again with the facts as they are now; a FALSE requirement
 *       fizzles; a wand short of mana stops. Otherwise {@link Outcome.Go}: the adapter spends the mana or the
 *       scroll and sends the delivery, and the cooldown (per caster and spell) starts.</li>
 *   <li><b>Potions</b> ({@link #drink}): no cast time; a FALSE requirement, or the spell's cooldown (SC-D016), means
 *       it isn't drunk.</li>
 *   <li><b>Once per cast</b>: Hytale runs each interaction step twice (SC-004). Every step is keyed by its chain and
 *       answered once; a repeat gets the same outcome back with {@link Step#first()} false, and does nothing.</li>
 * </ul>
 */
public final class Casting {
    /** The default interrupt distance; the server's {@code InterruptDistance} setting replaces it. */
    public static final double INTERRUPT_DISTANCE = 0.5;
    /** How early a completion may come and still count: the game's ticks aren't exact. */
    public static final long EARLY_MS = 100;
    /** A cast that's neither completed nor interrupted this long after its cast time ended is dropped. */
    public static final long STALE_MS = 2000;
    /** How late a bolt may land and still count: one game tick or so. Later is past its range. */
    public static final long LATE_MS = 40;
    /** How long a step's outcome is remembered for its repeat. */
    public static final long MEMO_MS = 30_000;

    /** The caster as the game sees them now. {@code itemId} is what they hold, in {@code slot}. */
    public record Caster(String id, String name, double x, double y, double z, int slot, String itemId) {
        double distance(Caster o) {
            double dx = x - o.x, dy = y - o.y, dz = z - o.z;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
    }

    /** A line for the caster: a lang key ({@code spellcrafter.cast.*}) and its parameters. */
    public record Line(String key, Map<String, String> params) {
        public static Line of(String key, String... kv) {
            Map<String, String> m = new LinkedHashMap<>();
            for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
            return new Line(key, m);
        }
    }

    /** What a step decided. */
    public sealed interface Outcome {
        /** Stop: nothing is spent and the vessel is kept. {@code line} is null for a silent stop. */
        record Refused(Line line, String why) implements Outcome {
        }

        /** The cast has begun and lasts {@code castMs}. */
        record Started(Spell spell, Vessel vessel, long castMs, Line line) implements Outcome {
        }

        /** Go ahead: spend {@code mana} (wands) or one vessel (scrolls, potions), then deliver {@code plan}. */
        record Go(Spell spell, Vessel vessel, CastResult.CastPlan plan, long mana, Line line) implements Outcome {
        }
    }

    /** A step's outcome, and whether this is its first run (act) or its repeat (don't). */
    public record Step(Outcome outcome, boolean first) {
    }

    /** A cast in progress. */
    record Pending(Caster at, Spell spell, Vessel vessel, String chain, long startedMs, long castMs) {
    }

    /** A bolt in flight, waiting for its hit or its lifetime. */
    public record Flight(String casterId, Spell spell, Vessel vessel, CastResult.CastPlan plan, long mana,
            String sourceId, long expiresMs) {
    }

    /** An interrupted cast, for the adapter to tell the caster. */
    public record Interrupt(String casterId, Line line) {
    }

    private final Content content;
    private final ConditionPort conditions;
    private final Consumer<String> log;
    private final Map<String, Pending> pending = new HashMap<>();
    private final Map<String, Long> cooldownUntil = new HashMap<>();
    private final Map<String, Memo> memo = new HashMap<>();
    private final Map<String, List<InFlight>> flights = new LinkedHashMap<>();

    private record Memo(Outcome outcome, long atMs) {
    }

    public Casting(Content content, ConditionPort conditions, Consumer<String> log) {
        this.content = content;
        this.conditions = conditions;
        this.log = log;
    }

    // ------------------------------------------------------------------------------------------------ start

    /**
     * The caster used a scroll or wand. {@code chain} identifies this use's interaction chain; {@code mana} is the
     * caster's current mana (null if they have no mana stat).
     */
    public synchronized Step begin(Caster c, Vessel v, String chain, Double mana, long now) {
        return once(chain + "#begin", now, () -> {
            Spell s = content.spell(v.spell());
            if (s == null) return new Outcome.Refused(Line.of("spellcrafter.cast.unavailable", "spell", v.spell(),
                    "reason", "it isn't loaded"), "unknown spell");
            Long until = cooldownUntil.get(key(c.id(), s.id()));
            if (until != null && now < until) {
                return new Outcome.Refused(Line.of("spellcrafter.cast.cooldown", "spell", s.name(),
                        "seconds", seconds(until - now)), "cooldown");
            }
            if (pending.containsKey(c.id())) return new Outcome.Refused(null, "already casting");
            Outcome bad = refusal(s, evaluate(s, c, null));
            if (bad != null) return bad;
            if (v.kind().equals("wand") && (mana == null || mana < s.costMana())) {
                return new Outcome.Refused(Line.of("spellcrafter.cast.no_mana", "spell", s.name(),
                        "cost", Long.toString(s.costMana())), "no mana");
            }
            pending.put(c.id(), new Pending(c, s, v, chain, now, s.castTime()));
            return new Outcome.Started(s, v, s.castTime(), Line.of("spellcrafter.cast.begin", "spell", s.name()));
        });
    }

    // ------------------------------------------------------------------------------------------------ casting

    /**
     * Checks the casts in progress of the casters {@code mine} accepts (one world's, say) against where they are now
     * ({@code where} returns null for a caster who's gone). Returns the casts interrupted.
     */
    public synchronized List<Interrupt> watch(java.util.function.Predicate<String> mine, Function<String, Caster> where,
            long now) {
        List<Interrupt> out = new ArrayList<>();
        for (Iterator<Pending> it = pending.values().iterator(); it.hasNext();) {
            Pending p = it.next();
            if (!mine.test(p.at().id())) continue;
            Caster c = where.apply(p.at().id());
            if (c == null || now > p.startedMs() + p.castMs() + STALE_MS) {
                it.remove();
                continue;
            }
            String why = null;
            if (c.distance(p.at()) > content.interruptDistance()) why = "moved";
            else if (c.slot() != p.at().slot() || !java.util.Objects.equals(c.itemId(), p.at().itemId())) why = "item";
            if (why == null) continue;
            it.remove();
            log.accept("interrupted " + p.spell().id() + " for " + c.id() + " (" + why + ": "
                    + String.format(java.util.Locale.ROOT, "%.2f blocks, from %.2f %.2f %.2f to %.2f %.2f %.2f; slot %d %s -> %d %s",
                            c.distance(p.at()), p.at().x(), p.at().y(), p.at().z(), c.x(), c.y(), c.z(),
                            p.at().slot(), p.at().itemId(), c.slot(), c.itemId()) + ")");
            out.add(new Interrupt(c.id(), Line.of("spellcrafter.cast.interrupted", "spell", p.spell().name())));
        }
        return out;
    }

    /** Whether the caster has a cast in progress. */
    public synchronized boolean casting(String casterId) {
        return pending.containsKey(casterId);
    }

    // ------------------------------------------------------------------------------------------------ completion

    /** The cast time is over: {@code chain} is the same chain {@link #begin} saw. */
    public synchronized Step complete(Caster c, String chain, Double mana, long now) {
        Pending p = pending.get(c.id());
        if (p != null && p.chain().equals(chain) && now + EARLY_MS < p.startedMs() + p.castMs()) {
            // Not this step's real run: the cast time isn't over. Answer it without remembering.
            log.accept("completion " + (p.startedMs() + p.castMs() - now) + " ms early for " + c.id() + "; ignored");
            return new Step(new Outcome.Refused(null, "early"), false);
        }
        return once(chain + "#complete", now, () -> {
            Pending q = pending.get(c.id());
            if (q == null || !q.chain().equals(chain)) return new Outcome.Refused(null, "not casting (interrupted)");
            pending.remove(c.id());
            Spell s = q.spell();
            CastResult r = evaluate(s, c, null);
            Outcome bad = refusal(s, r);
            if (bad != null) return bad;
            long cost = q.vessel().kind().equals("wand") ? s.costMana() : 0;
            if (cost > 0 && (mana == null || mana < cost)) {
                return new Outcome.Refused(Line.of("spellcrafter.cast.no_mana", "spell", s.name(),
                        "cost", Long.toString(cost)), "no mana");
            }
            if (s.cooldown() > 0) cooldownUntil.put(key(c.id(), s.id()), now + s.cooldown());
            return new Outcome.Go(s, q.vessel(), (CastResult.CastPlan) r, cost, null);
        });
    }

    // ------------------------------------------------------------------------------------------------ potions

    /** Drinking a potion: no cast time, no mana, but the spell's cooldown (SC-D016). A refusal means it isn't drunk. */
    public synchronized Step drink(Caster c, Vessel v, String chain, long now) {
        return once(chain + "#drink", now, () -> {
            Spell s = content.spell(v.spell());
            if (s == null) return new Outcome.Refused(Line.of("spellcrafter.cast.unavailable", "spell", v.spell(),
                    "reason", "it isn't loaded"), "unknown spell");
            Long until = cooldownUntil.get(key(c.id(), s.id()));
            if (until != null && now < until) {
                return new Outcome.Refused(Line.of("spellcrafter.cast.cooldown", "spell", s.name(),
                        "seconds", seconds(until - now)), "cooldown");
            }
            CastResult r = evaluate(s, c, null);
            Outcome bad = refusal(s, r);
            if (bad != null) return bad;
            if (s.cooldown() > 0) cooldownUntil.put(key(c.id(), s.id()), now + s.cooldown());
            return new Outcome.Go(s, v, (CastResult.CastPlan) r, 0, null);
        });
    }

    // ------------------------------------------------------------------------------------------------ bolts

    /**
     * A bolt was launched. A landing bolt names only its caster (Hytale's projectiles carry no id of their own, SC-004),
     * so a caster's bolts land oldest first. {@code handle} is the adapter's own reference, for removing it.
     */
    public synchronized void launched(Flight f, Object handle) {
        flights.computeIfAbsent(f.casterId(), k -> new ArrayList<>()).add(new InFlight(f, handle));
    }

    /**
     * A bolt of {@code casterId} landed (hit or miss): its flight, or null if none is in flight. {@code chain}
     * identifies the landing's interaction chain: its second run gets null.
     */
    public synchronized Flight landed(String casterId, String chain, long now) {
        Step s = once(chain + "#land", now, () -> {
            List<InFlight> l = flights.get(casterId);
            if (l == null || l.isEmpty()) return new Outcome.Refused(null, "no bolt in flight");
            InFlight f = l.remove(0);
            if (l.isEmpty()) flights.remove(casterId);
            if (now > f.flight().expiresMs() + LATE_MS) {
                // Past its range: the sweep that removes spent bolts runs every tick, so this one got there first.
                log.accept("landed " + (now - f.flight().expiresMs()) + " ms after its lifetime: out of range, a miss");
                return new Outcome.Refused(null, "out of range");
            }
            return new Landed(f.flight());
        });
        return s.first() && s.outcome() instanceof Landed l ? l.flight() : null;
    }

    /** Bolts past their lifetime ({@code range / speed}): a miss. Returns their handles, to remove from the world. */
    public synchronized List<Object> expired(long now) {
        List<Object> out = new ArrayList<>();
        for (Iterator<List<InFlight>> it = flights.values().iterator(); it.hasNext();) {
            List<InFlight> l = it.next();
            for (Iterator<InFlight> j = l.iterator(); j.hasNext();) {
                InFlight f = j.next();
                if (now >= f.flight().expiresMs()) {
                    out.add(f.handle());
                    j.remove();
                }
            }
            if (l.isEmpty()) it.remove();
        }
        return out;
    }

    private record InFlight(Flight flight, Object handle) {
    }

    /** Internal: a landing's outcome, remembered for its repeat. */
    private record Landed(Flight flight) implements Outcome {
    }

    /** A bolt's lifetime in ms: the delivery's range over its speed (release-1.0.md 4a). */
    public static long lifetimeMs(CastResult.Planned delivery, long defaultSpeedMilli) {
        Long range = delivery.params().get("range");
        Long speed = delivery.params().get("speed");
        long sp = speed != null && speed > 0 ? speed : defaultSpeedMilli;
        return range == null ? 2000 : range * 1000 / sp;
    }

    // ------------------------------------------------------------------------------------------------ payloads

    /** The plan's payloads as applications to {@code target}, with no burst (seed 1: chances and picks still roll). */
    public static List<PayloadPort.Apply> applications(CastResult.CastPlan plan, String target, String actor,
            String sourceId) {
        return applications(Resolve.resolve(plan, actor, target, List.of(), 1, Model.DEFAULT_SETTINGS.caps()), actor,
                sourceId);
    }

    /**
     * A resolved cast's applications (spell-format 7b): who gets which payload, after the burst, the chances and the
     * picks. {@code actor} is the caster.
     */
    public static List<PayloadPort.Apply> applications(Resolve.Resolution res, String actor, String sourceId) {
        List<PayloadPort.Apply> out = new ArrayList<>();
        for (Resolve.Application app : res.applications()) {
            CastResult.Planned p = app.payload();
            String target = app.entity();
            Json.Obj f = p.fields();
            String effect = f.get("effect") instanceof Json.Str s ? s.value() : null;
            boolean suppress = f.get("suppress_on_apply") instanceof Json.Bool b && b.value();
            out.add(new PayloadPort.Apply(p.provider(), effect, target, actor, sourceId, num(p.params().get("strength")),
                    num(p.params().get("duration")), suppress, f.get("messages")));
        }
        return out;
    }

    private static Double num(Long milli) {
        return milli == null ? null : milli / 1000.0;
    }

    // ------------------------------------------------------------------------------------------------ inside

    /** {@code /spell cast}: the spell evaluated now for {@code c} at {@code target}, with no vessel, mana or cooldown. */
    public CastResult evaluateNow(Spell s, Caster c, String target) {
        return evaluate(s, c, target);
    }

    /** Evaluates the spell for this caster, asking the condition port for every leaf the core needs. */
    CastResult evaluate(Spell s, Caster c, String target) {
        CastInput in = new CastInput(s, List.of(), List.of(), Registries.EMPTY, content.profile(),
                content.settings(), Map.of(), Facts.NONE, null);
        Facts.Builder b = new Facts.Builder();
        for (Condition.Leaf leaf : Evaluator.neededFacts(in)) {
            ConditionPort.Answer a = conditions.answer(leaf.key(), c.id(), target);
            b.put(leaf, a.value(), a.reason());
        }
        return Evaluator.evaluate(in.withFacts(b.build()));
    }

    /** The player-facing stop for a result that isn't a plan, or null for a plan. */
    private static Outcome refusal(Spell s, CastResult r) {
        if (r instanceof CastResult.Fizzle f) {
            return new Outcome.Refused(Line.of("spellcrafter.cast.fizzle", "spell", s.name(), "reason",
                    reasons(f.reasons())), "fizzle");
        }
        if (r instanceof CastResult.Unavailable u) {
            return new Outcome.Refused(Line.of("spellcrafter.cast.unavailable", "spell", s.name(), "reason",
                    reasons(u.reasons())), "unavailable");
        }
        if (r instanceof CastResult.Invalid i) {
            return new Outcome.Refused(Line.of("spellcrafter.cast.unavailable", "spell", s.name(), "reason",
                    "this game can't cast it (" + i.problems().get(0).code() + ")"), "invalid");
        }
        return null;
    }

    private static String reasons(List<CastResult.Reason> rs) {
        StringBuilder b = new StringBuilder();
        for (CastResult.Reason r : rs) {
            if (b.length() > 0) b.append("; ");
            b.append(r.why() != null ? r.why() : r.id());
        }
        return b.toString();
    }

    private Step once(String key, long now, java.util.function.Supplier<Outcome> decide) {
        memo.values().removeIf(m -> now - m.atMs() > MEMO_MS);
        Memo m = memo.get(key);
        if (m != null) return new Step(m.outcome(), false);
        Outcome o = decide.get();
        memo.put(key, new Memo(o, now));
        return new Step(o, true);
    }

    /**
     * {@code /spell cooldowns}: each spell this caster is waiting on, spell id to ms left, soonest first. Spent
     * cooldowns are dropped as they're read.
     */
    public synchronized Map<String, Long> cooldowns(String casterId, long now) {
        List<Map.Entry<String, Long>> left = new ArrayList<>();
        String prefix = casterId + "|";
        for (Iterator<Map.Entry<String, Long>> it = cooldownUntil.entrySet().iterator(); it.hasNext();) {
            Map.Entry<String, Long> e = it.next();
            if (e.getValue() <= now) {
                it.remove();
                continue;
            }
            if (e.getKey().startsWith(prefix)) left.add(Map.entry(e.getKey().substring(prefix.length()), e.getValue() - now));
        }
        left.sort(Map.Entry.comparingByValue());
        Map<String, Long> out = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : left) out.put(e.getKey(), e.getValue());
        return out;
    }

    private final java.util.Set<String> debugging = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** {@code /spell debug on|off}: whether this caster's casts print their report, seed and rolls. */
    public void debug(String casterId, boolean on) {
        if (on) debugging.add(casterId);
        else debugging.remove(casterId);
    }

    public boolean debugging(String casterId) {
        return debugging.contains(casterId);
    }

    /** Seconds as the cooldown line writes them: tenths, rounded up, no trailing ".0". */
    public static String secondsText(long ms) {
        return seconds(ms);
    }

    private static String key(String caster, String spell) {
        return caster + "|" + spell;
    }

    private static String seconds(long ms) {
        long tenths = (ms + 99) / 100;
        return tenths % 10 == 0 ? Long.toString(tenths / 10) : (tenths / 10) + "." + (tenths % 10);
    }
}
