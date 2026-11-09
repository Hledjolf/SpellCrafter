package spellcrafter.core.eval;

import java.util.List;
import java.util.Map;

import spellcrafter.core.format.Problem;
import spellcrafter.core.json.Json;
import spellcrafter.core.model.Model.Component;
import spellcrafter.core.model.Model.Pick;

/**
 * What {@link Evaluator#evaluate} returns (design/spell-format.md, section 8): a plan to carry out, a fizzle, a spell
 * that's off here, or a spell this game refuses. Never an exception.
 */
public sealed interface CastResult permits CastResult.CastPlan, CastResult.Fizzle, CastResult.Unavailable,
        CastResult.Invalid {

    /** The rider report, for every result that got that far: id → entry, in a fixed order. */
    Map<String, ReportEntry> report();

    /**
     * The spell goes ahead. {@code payloads} are base payloads in authored order, then additions (pick options and
     * reroll options among them: {@link Resolve} decides who gets what); {@code consume} lists the components the
     * adapter removes; {@code capped} every cap that changed a number or dropped something. {@code burst} is the burst's
     * {@code affects} (null for none; its radius is the delivery's {@code burst.radius}), {@code picks} the pick groups.
     */
    record CastPlan(Planned delivery, List<Planned> payloads, List<Component> consume, String profile,
            Map<String, ReportEntry> report, List<CapHit> capped, RecheckPlan recheck, String burst,
            List<Pick> picks) implements CastResult {
    }

    /** A gate answered FALSE, or a component is missing. Nothing is spent. */
    record Fizzle(List<Reason> reasons, Map<String, ReportEntry> report) implements CastResult {
    }

    /** A gate answered UNKNOWN: the spell is off here, greyed out with the reason. */
    record Unavailable(List<Reason> reasons, Map<String, ReportEntry> report) implements CastResult {
    }

    /** This game refuses the spell (a rider field its profile doesn't support). */
    record Invalid(List<Problem> problems) implements CastResult {
        @Override
        public Map<String, ReportEntry> report() {
            return Map.of();
        }
    }

    /**
     * A payload or the delivery, with its final numbers. For the delivery, {@code id} is "delivery" and
     * {@code provider} and {@code op} are its kind. {@code fields} is the payload as written (the provider's keys).
     * {@code from} is the rider that added it, or null for a base payload.
     */
    record Planned(String id, String provider, String op, Map<String, Long> params, Json.Obj fields, String from) {
        /** A pick group's reroll option: nothing to apply. */
        public boolean isReroll() {
            return fields != null && fields.has("reroll");
        }
    }

    /** Report statuses. */
    enum Status { ACTIVE, INACTIVE, UNKNOWN, IGNORED, CAPPED, OFF, REFUSED }

    /** One line of the rider report. {@code kind}: gate, component, rider or circumstance; {@code source} its origin. */
    record ReportEntry(String kind, Status status, String reason, String source) {
    }

    /**
     * A cap that applied. {@code cap}: {@code bonus_pct}, {@code more_pct.each}, {@code more_pct.per_param},
     * {@code added_payloads}, {@code params.<name>}, {@code provider.min}, {@code provider.max},
     * {@code circumstances_per_cast}, {@code riders_per_cast}. {@code target} is {@code <payload id>.<param>},
     * {@code delivery.<param>}, or the id dropped. {@code before} and {@code after} are null when something was dropped.
     */
    record CapHit(String cap, String target, Long before, Long after) {
    }

    /** Why a spell fizzled or is off: the gate or component, and what it answered. */
    record Reason(String id, String kind, Tri answer, String why) {
    }

    /**
     * Which items are rechecked while the cast's payloads run, and how often (section 11). {@code everyMs} is each
     * item's interval, {@code max(recheck_every, recheck_min_ms)}; {@code intervalMs} is the shortest of them (0 when
     * nothing is rechecked), the adapter's tick.
     */
    record RecheckPlan(List<String> ids, long intervalMs, Map<String, Long> everyMs) {
    }
}
