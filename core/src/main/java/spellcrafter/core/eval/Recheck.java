package spellcrafter.core.eval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import spellcrafter.core.eval.CastResult.CastPlan;
import spellcrafter.core.eval.CastResult.Planned;

/**
 * Rechecking a lasting cast (design/spell-format.md, section 11). The adapter answers the leaves of the items marked
 * {@code recheck: tick} ({@link Evaluator#neededFacts(CastInput, boolean)}), evaluates again with
 * {@link CastInput#recheck}, and hands the provider this diff against the plan it's carrying out.
 */
public final class Recheck {
    private Recheck() {
    }

    /**
     * What changed. {@code ended}: a rechecked gate turned FALSE or UNKNOWN, so every lasting payload ends.
     * Otherwise the payloads that left, arrived, or whose params changed (by id; a param that disappeared is
     * {@code after: null}).
     */
    public record Diff(boolean ended, List<String> removed, List<String> added, List<ParamChange> changed) {
    }

    public record ParamChange(String payload, String param, Long before, Long after) {
    }

    public static Diff diff(CastPlan before, CastResult after) {
        if (!(after instanceof CastPlan next)) {
            List<String> all = new ArrayList<>();
            for (Planned p : before.payloads()) all.add(p.id());
            return new Diff(true, all, List.of(), List.of());
        }
        Map<String, Planned> was = byId(before.payloads());
        Map<String, Planned> now = byId(next.payloads());
        List<String> removed = new ArrayList<>();
        List<String> added = new ArrayList<>();
        List<ParamChange> changed = new ArrayList<>();
        for (Planned p : before.payloads()) {
            if (!now.containsKey(p.id())) removed.add(p.id());
        }
        for (Planned p : next.payloads()) {
            Planned old = was.get(p.id());
            if (old == null) {
                added.add(p.id());
                continue;
            }
            for (Map.Entry<String, Long> e : old.params().entrySet()) {
                Long v = p.params().get(e.getKey());
                if (!e.getValue().equals(v)) changed.add(new ParamChange(p.id(), e.getKey(), e.getValue(), v));
            }
            for (Map.Entry<String, Long> e : p.params().entrySet()) {
                if (!old.params().containsKey(e.getKey())) changed.add(new ParamChange(p.id(), e.getKey(), null, e.getValue()));
            }
        }
        return new Diff(false, removed, added, changed);
    }

    private static Map<String, Planned> byId(List<Planned> ps) {
        Map<String, Planned> m = new LinkedHashMap<>();
        for (Planned p : ps) m.put(p.id(), p);
        return m;
    }
}
