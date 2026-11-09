package spellcrafter.core.power;

import static spellcrafter.core.power.Fixed.ONE;
import static spellcrafter.core.power.Fixed.div;
import static spellcrafter.core.power.Fixed.mul;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import spellcrafter.core.format.Parsed;
import spellcrafter.core.format.Problem;
import spellcrafter.core.json.Json;
import spellcrafter.core.model.Condition;
import spellcrafter.core.num.Milli;

/**
 * How often world-controlled conditions hold (design/release-1.0.md section 3; spell-format 13): the {@code p} that
 * prices riders at their expected value and discounts gates. Read from a {@code spellcrafter.frequencies} 1.0 file (the
 * adapter's defaults per game), with a server's settings laid over it by {@link #over}.
 *
 * <p>{@link #p} answers null for "best case, no discount": a caster-arrangeable condition ({@code wielding},
 * {@code near_fluid}, an effect on yourself: always {@code p = 1}) and a world-controlled one with no default
 * ({@code zone}, moon phase, season) price the same way.
 */
public final class Frequencies {

    /** No table: everything at the best case. */
    public static final Frequencies NONE = new Frequencies(Map.of(), false, null);

    private final Map<String, Long> weather;
    private final boolean timeOfDayHours;
    private final Long isNight;

    private Frequencies(Map<String, Long> weather, boolean timeOfDayHours, Long isNight) {
        this.weather = Map.copyOf(weather);
        this.timeOfDayHours = timeOfDayHours;
        this.isNight = isNight;
    }

    /** Each weather category's share, as {@link Fixed} billionths. */
    public Map<String, Long> weather() {
        return weather;
    }

    /** Night's share, as {@link Fixed} billionths, or null with no default. */
    public Long isNight() {
        return isNight;
    }

    /** This table with {@code top}'s values in place of its own wherever {@code top} gives one. */
    public Frequencies over(Frequencies top) {
        Map<String, Long> w = new LinkedHashMap<>(weather);
        w.putAll(top.weather);
        return new Frequencies(w, timeOfDayHours || top.timeOfDayHours, top.isNight != null ? top.isNight : isNight);
    }

    /**
     * The share of the time {@code c} holds, in billionths; null for the best case. Compound conditions treat their
     * parts as independent: {@code all} multiplies, {@code any} is {@code 1 − Π(1 − p)}, {@code not} is {@code 1 − p}.
     * A part priced at the best case counts as always holding inside {@code all} and {@code any}, and {@code not} of it
     * stays at the best case (the caster can arrange it either way, or nobody knows how often).
     */
    public Long p(Condition c) {
        if (c instanceof Condition.All a) {
            Long out = null;
            for (Condition part : a.of()) {
                Long q = p(part);
                if (q != null) out = out == null ? q : mul(out, q);
            }
            return out;
        }
        if (c instanceof Condition.Any a) {
            long none = ONE;
            for (Condition part : a.of()) {
                Long q = p(part);
                if (q == null) return null;
                none = mul(none, ONE - q);
            }
            return ONE - none;
        }
        if (c instanceof Condition.Not n) {
            Long q = p(n.of());
            return q == null ? null : ONE - q;
        }
        return leaf((Condition.Leaf) c);
    }

    private Long leaf(Condition.Leaf l) {
        Json.Obj j = l.json();
        switch (l.type()) {
            case "weather" -> {
                // A leaf listing several categories matches any of them: the same independence rule as any.
                if (!(j.get("category") instanceof Json.Arr cats) || cats.items().isEmpty()) return null;
                long none = ONE;
                for (Json k : cats.items()) {
                    Long q = k instanceof Json.Str s ? weather.get(s.value()) : null;
                    if (q == null) return null;
                    none = mul(none, ONE - q);
                }
                return ONE - none;
            }
            case "time_of_day" -> {
                if (!timeOfDayHours) return null;
                Long from = hours(j.get("from"));
                Long to = hours(j.get("to"));
                if (from == null || to == null) return null;
                long day = Fixed.of(24);
                long span = Math.floorMod(to - from, day);
                if (span == 0) span = day; // from == to: the whole day
                return div(span, day);
            }
            case "is_night" -> {
                return isNight;
            }
            default -> {
                return null;
            }
        }
    }

    private static Long hours(Json v) {
        if (!(v instanceof Json.Num n)) return null;
        Milli.Parsed m = Milli.parseMilli(n.text());
        return m.problem() == null ? m.value() * 1_000_000L : null;
    }

    /** Reads a {@code spellcrafter.frequencies} 1.0 file (release-1.0.md section 3). */
    public static Parsed<Frequencies> parse(Json doc) {
        List<Problem> problems = new ArrayList<>();
        if (!(doc instanceof Json.Obj o)) {
            return new Parsed.Invalid<>(List.of(new Problem("invalid", "", "a frequencies file is an object")));
        }
        if (!(o.get("format") instanceof Json.Str f) || !f.value().equals("spellcrafter.frequencies")) {
            problems.add(new Problem("unsupported_format", "/format", "expected \"spellcrafter.frequencies\""));
        }
        if (!(o.get("format_version") instanceof Json.Str v) || !v.value().startsWith("1.")) {
            problems.add(new Problem("unsupported_version", "/format_version", "this core reads major version 1"));
        }
        Map<String, Long> weather = new LinkedHashMap<>();
        if (o.has("weather")) {
            if (o.get("weather") instanceof Json.Obj w) {
                for (Map.Entry<String, Json> e : w.members().entrySet()) {
                    Long q = share(e.getValue());
                    if (q == null) problems.add(new Problem("invalid", "/weather/" + e.getKey(), "a share from 0 to 1"));
                    else weather.put(e.getKey(), q);
                }
            } else {
                problems.add(new Problem("invalid", "/weather", "an object of category: share"));
            }
        }
        boolean hours = false;
        if (o.has("time_of_day")) {
            if (o.get("time_of_day") instanceof Json.Str s && s.value().equals("hours")) hours = true;
            else problems.add(new Problem("invalid", "/time_of_day", "\"hours\" (the window's share of 24 hours)"));
        }
        Long night = null;
        if (o.has("is_night")) {
            night = share(o.get("is_night"));
            if (night == null) problems.add(new Problem("invalid", "/is_night", "a share from 0 to 1"));
        }
        return problems.isEmpty() ? new Parsed.Ok<>(new Frequencies(weather, hours, night), List.of())
                : new Parsed.Invalid<>(problems);
    }

    private static Long share(Json v) {
        if (!(v instanceof Json.Num n)) return null;
        Milli.Parsed m = Milli.parseMilli(n.text());
        if (m.problem() != null || m.value() < 0 || m.value() > 1000) return null;
        return m.value() * 1_000_000L;
    }
}
