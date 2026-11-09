package spellcrafter.core.editor;

import java.util.ArrayList;
import java.util.List;

import spellcrafter.core.json.Json;
import spellcrafter.core.json.JsonWriter;
import spellcrafter.core.power.Fixed;
import spellcrafter.core.power.Gta;
import spellcrafter.core.power.Placement;

/**
 * The live readout (design/editor.md section 4), shown after every command. Numbers are {@link Fixed} billionths,
 * already rounded as {@link Placement} rounds them; null where there's nothing to show.
 *
 * @param rated     false when some payload couldn't be rated ({@code unrated} says why): then only the warnings show
 * @param best      the value per cast with every rider active, after caps (prices the per-cast grade); for movement
 *                  and resources, the value their placement rule gives
 * @param expected  world-controlled riders at their frequency (prices the mana)
 * @param placement grade, fight grade, what placed it, zone, cost and mana; null when unrated
 * @param optIn     the zone is above 4: "needs an operator's opt-in from 1.1"
 */
public record Readout(boolean rated, List<String> unrated, Long best, Long expected, Placement.Placed placement,
        boolean optIn, List<String> warnings) {

    public Readout {
        unrated = List.copyOf(unrated);
        warnings = List.copyOf(warnings);
    }

    public static final String OPT_IN = "needs an operator's opt-in from 1.1";

    /** The mana the method prices the spell at; null when unrated. */
    public Long mana() {
        return placement == null ? null : placement.mana();
    }

    /** As JSON, for adapters and tests: numbers as decimal text. */
    public Json toJson() {
        Placement.Placed p = placement;
        return JsonWriter.obj(
                "rated", new Json.Bool(rated),
                "unrated", strs(unrated),
                "value_best", num(best, 2),
                "value_expected", num(expected, 2),
                "grade", p == null ? new Json.Null() : p.grade() != null ? num(p.grade(), 2)
                        : new Json.Str(best == null ? "unbounded" : Placement.COSMETIC),
                "fight_grade", p == null || p.fightGrade() == null ? new Json.Null()
                        : p.fightGrade() == Gta.INF ? new Json.Str("unbounded") : num(p.fightGrade(), 2),
                "placed_by", p == null ? new Json.Null() : new Json.Str(p.placedBy()),
                "zone", p == null ? new Json.Null() : new Json.Str(p.zone()),
                "opt_in", new Json.Bool(optIn),
                "cost", p == null ? new Json.Null() : num(p.cost(), 3),
                "mana", p == null || p.mana() == null ? new Json.Null() : new Json.Num(Long.toString(p.mana())),
                "casts_in_fight", p == null || p.castsInFight() == null ? new Json.Null()
                        : new Json.Num(Long.toString(p.castsInFight())),
                "warnings", strs(warnings));
    }

    static Json strs(List<String> v) {
        List<Json> out = new ArrayList<>();
        for (String s : v) out.add(new Json.Str(s));
        return new Json.Arr(out);
    }

    /** A billionths value as decimal text, to {@code decimals} places, trailing zeros dropped. */
    static Json num(Long fixed, int decimals) {
        if (fixed == null) return new Json.Null();
        long units = Fixed.round(fixed, decimals);
        long a = Math.abs(units);
        long scale = 1;
        for (int i = 0; i < decimals; i++) scale *= 10;
        String s = Long.toString(a / scale);
        String f = Long.toString(a % scale);
        while (f.length() < decimals) f = "0" + f;
        while (f.endsWith("0")) f = f.substring(0, f.length() - 1);
        if (!f.isEmpty()) s += "." + f;
        return new Json.Num(units < 0 ? "-" + s : s);
    }
}
