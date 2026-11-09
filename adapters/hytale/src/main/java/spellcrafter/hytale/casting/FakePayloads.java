package spellcrafter.hytale.casting;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The payload port SpellCrafter casts through: records and logs every application, then hands it to the real
 * provider ({@link #useProvider}, BuffEffects' API when it's installed). A test's answer ({@link #answerWith}) wins;
 * with no provider, an application is logged and answered "applied" without doing anything.
 */
public final class FakePayloads implements PayloadPort {
    private final List<Apply> applied = new CopyOnWriteArrayList<>();
    private final List<Result> results = new CopyOnWriteArrayList<>();
    private final Consumer<String> log;
    private volatile Function<Apply, Result> answer;
    private volatile PayloadPort provider;

    public FakePayloads(Consumer<String> log) {
        this.log = log;
    }

    /** A test's answer for every application from now on; null to clear. */
    public void answerWith(Function<Apply, Result> f) {
        answer = f;
    }

    /** The real provider, or null for none. */
    public void useProvider(PayloadPort p) {
        provider = p;
    }

    @Override
    public Result apply(Apply a) {
        applied.add(a);
        Function<Apply, Result> f = answer;
        PayloadPort p = provider;
        Result r = f != null ? f.apply(a) : p != null ? p.apply(a) : Result.ok();
        results.add(r);
        log.accept("payload " + a.provider() + " " + a.effect() + " -> " + a.target() + " strength=" + a.strength()
                + " duration=" + a.durationSeconds() + " source=" + a.sourceId() + ": "
                + (r.applied() ? "applied" : "refused " + r.code() + " (" + r.message() + ")")
                + (f == null && p == null ? " [no provider: nothing done]" : ""));
        return r;
    }

    /** Every application asked for, in order. */
    @Override
    public spellcrafter.core.editor.Effect describe(String effect) {
        PayloadPort p = provider;
        return p == null ? null : p.describe(effect);
    }

    @Override
    public List<String> list() {
        PayloadPort p = provider;
        return p == null ? List.of() : p.list();
    }

    public List<Apply> applied() {
        return List.copyOf(applied);
    }

    /** The answer to each, in the same order. */
    public List<Result> results() {
        return List.copyOf(results);
    }

    @Override
    public String name() {
        PayloadPort p = provider;
        return p != null ? p.name() : "none (BuffEffects isn't installed: payloads are logged, not applied)";
    }
}
