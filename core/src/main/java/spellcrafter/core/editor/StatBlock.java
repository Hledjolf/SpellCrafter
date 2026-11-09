package spellcrafter.core.editor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import spellcrafter.core.json.Json;
import spellcrafter.core.model.Condition;
import spellcrafter.core.model.Model.Component;
import spellcrafter.core.model.Model.Gate;
import spellcrafter.core.model.Model.Payload;
import spellcrafter.core.model.Model.Pick;
import spellcrafter.core.model.Model.Rider;
import spellcrafter.core.model.Model.Spell;
import spellcrafter.core.model.Model.Targeting;
import spellcrafter.core.num.Milli;

/**
 * A spell's stat block (design/window.md section 2; commands-settings.md section 5): plain sentences built from the
 * spell's data and its effects' parts, never free text. Every sentence is a lang template ({@code spellcrafter.*}) with
 * its fields filled in, so a new part type needs only its template. The numbers are the spell's own, after strength
 * and before riders; riders are lines of their own.
 *
 * <p>Sections, in order: the header (name, school, cost and times, delivery, burst, components, requirements); one
 * section per payload (pick groups as one section, with each option's odds); the spell-wide riders; and, with a
 * {@link Readout}, the readout at the foot.
 */
public final class StatBlock {
    private StatBlock() {
    }

    /** A {@code .lang} file's {@code key=value} lines ({@code #} starts a comment). */
    public static Map<String, String> parseLang(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        int start = 0;
        while (start <= text.length()) {
            int nl = text.indexOf('\n', start);
            String line = nl < 0 ? text.substring(start) : text.substring(start, nl);
            start = nl < 0 ? text.length() + 1 : nl + 1;
            String l = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
            if (l.isBlank() || l.startsWith("#")) continue;
            int eq = l.indexOf('=');
            if (eq > 0) out.put(l.substring(0, eq).trim(), l.substring(eq + 1));
        }
        return out;
    }

    /**
     * The stat block as lines. {@code catalog} gives each payload's effect (an effect it doesn't know is shown by id);
     * {@code readout} is optional (null: no foot); {@code school} is the spell's school (null: none shown).
     */
    public static List<String> lines(Spell s, Map<String, Effect> catalog, Readout readout, String school,
            Map<String, String> lang) {
        T t = new T(lang);
        List<String> out = new ArrayList<>();
        out.add(s.name());
        if (school != null) out.add(t.f("spellcrafter.block.school", "school", school));
        out.add(t.f("spellcrafter.block.cost", "mana", Long.toString(readout != null && readout.mana() != null
                ? readout.mana() : s.costMana()), "cast", Milli.format(s.castTime()), "cooldown", Milli.format(s.cooldown())));
        Map<String, Long> dp = s.delivery().params();
        out.add(t.f("spellcrafter.block.delivery." + s.delivery().kind(), "range", num(dp.get("range")), "radius",
                num(dp.get("radius"))));
        if (s.delivery().burst() != null) {
            out.add(t.f("spellcrafter.block.burst", "radius", num(dp.get("burst.radius")), "affects",
                    t.f("spellcrafter.block.affects." + s.delivery().burst())));
        }
        for (Component c : s.components()) {
            out.add(t.f(c.consumed() ? "spellcrafter.block.component.consumed" : "spellcrafter.block.component",
                    "count", Long.toString(c.count()), "item", c.item() != null ? c.item() : c.tag()));
        }
        for (Gate g : s.gates()) out.add(t.f("spellcrafter.block.requires", "condition", when(g.when(), t)));

        String kind = s.delivery().kind();
        Long radius = dp.get("burst.radius");
        for (Payload p : s.payloads()) {
            if (p.isReroll() || Targeting.pick(p.fields()) != null) continue;
            effect(p, kind, radius, catalog, t, out, false);
        }
        for (Pick g : s.picks()) {
            long total = 0;
            for (Payload p : s.payloads()) if (g.id().equals(Targeting.pick(p.fields()))) total += Targeting.weight(p.fields());
            out.add(t.f("spellcrafter.pick", "group", g.id()));
            for (Payload p : s.payloads()) {
                if (!g.id().equals(Targeting.pick(p.fields()))) continue;
                String odds = t.f("spellcrafter.pick.odds", "weight", Long.toString(Targeting.weight(p.fields())), "total",
                        Long.toString(total));
                if (p.isReroll()) {
                    out.add("  " + t.f("spellcrafter.pick.option", "name", t.f("spellcrafter.pick.reroll", "count",
                            Long.toString(Targeting.reroll(p.fields()))), "odds", odds));
                } else {
                    out.add("  " + t.f("spellcrafter.pick.option", "name", effectName(p, catalog), "odds", odds));
                    effect(p, kind, radius, catalog, t, out, true);
                }
            }
        }
        for (Rider r : s.riders()) {
            out.add(t.f("spellcrafter.block.rider", "condition", capital(when(r.when(), t)), "change", change(r, catalog, t)));
        }
        if (readout != null) {
            if (!readout.rated()) {
                for (String u : readout.unrated()) out.add(t.f("spellcrafter.block.unrated", "why", u));
            } else {
                Json.Obj j = (Json.Obj) readout.toJson();
                out.add(t.f("spellcrafter.block.readout", "grade", text(j.get("grade")), "fight", text(j.get("fight_grade")),
                        "placed_by", text(j.get("placed_by")), "zone", text(j.get("zone")), "mana", text(j.get("mana"))));
                out.add(t.f("spellcrafter.block.values", "best", text(j.get("value_best")), "expected",
                        text(j.get("value_expected"))));
            }
            for (String w : readout.warnings()) out.add(t.f("spellcrafter.block.warning", "text", w));
        }
        return out;
    }

    /** One payload: its heading ("Flame Bolt, on the target, 50% chance"), then a sentence per part. */
    private static void effect(Payload p, String kind, Long radius, Map<String, Effect> catalog, T t, List<String> out,
            boolean option) {
        String indent = option ? "    " : "";
        String target = Targeting.target(p.fields(), kind);
        String who = t.f("spellcrafter.who." + target, "radius", num(radius));
        long chance = Targeting.chance(p.fields());
        if (chance < 100) who = who + ", " + t.f("spellcrafter.chance", "chance", Long.toString(chance));
        if (!option) out.add(t.f("spellcrafter.block.effect", "name", effectName(p, catalog), "who", who));
        Effect e = catalog.get(effectId(p));
        if (e == null || !e.described()) return;
        long s = p.params().getOrDefault("strength", 1000L);
        Long dur = p.params().containsKey("duration") ? p.params().get("duration") : e.durationMs();
        for (Json.Obj part : e.parts()) {
            String line = part(part, s, dur, t);
            if (line != null) out.add(indent + "  " + line);
        }
        String look = seeThrough(e, t);
        if (look != null) out.add(indent + "  " + look);
    }

    /** "See-through (80%)" from the effect's look (commands-settings.md 5, {@code look.see_through}); else null. */
    static String seeThrough(Effect e, T t) {
        if (e.look() == null || !(e.look().get("see_through") instanceof Json.Num n)) return null;
        return t.f("spellcrafter.part.look.see_through", "percent", Milli.format(Effect.milliOf(n) * 100));
    }

    /** One effect's part sentences at strength {@code s} (thousandths) and duration {@code durMs}: for the window's tab. */
    public static List<String> partLines(Effect e, long s, Long durMs, Map<String, String> lang) {
        List<String> out = new ArrayList<>();
        if (!e.described()) return out;
        T t = new T(lang);
        for (Json.Obj part : e.parts()) {
            String line = part(part, s, durMs, t);
            if (line != null) out.add(line);
        }
        String look = seeThrough(e, t);
        if (look != null) out.add(look);
        return out;
    }

    /** A part's sentence; null for parts the stat block leaves out (looks, messages). */
    static String part(Json.Obj part, long s, Long durMs, T t) {
        String type = str(part, "type");
        if (type == null) return null;
        String dur = durMs == null ? "∞" : Milli.format(durMs);
        switch (type) {
            case "hit":
                return t.f("spellcrafter.part.hit", "amount", Milli.format(scale(milli(part, "amount"), s)), "cause", str(part, "cause"));
            case "damage_over_time":
                return t.f("spellcrafter.part.damage_over_time", "amount", Milli.format(scale(milli(part, "amount"), s)),
                        "cause", str(part, "cause"), "interval", Milli.format(milli(part, "interval")), "duration", dur);
            case "instant": {
                String amount = Milli.format(scale(milli(part, "amount"), s)) + ("percent".equals(str(part, "unit")) ? "%" : "");
                String stat = str(part, "stat");
                if ("health".equals(stat)) return t.f("spellcrafter.part.instant.health", "amount", amount);
                return t.f("spellcrafter.part.instant", "amount", amount, "stat", stat);
            }
            case "resist":
                return t.f("spellcrafter.part.resist", "percent", Milli.format(scale(milli(part, "amount"), s) * 100),
                        "cause", str(part, "cause"), "duration", dur);
            case "outgoing": {
                long m = milli(part, "multiplier");
                long scaled = 1000 + Milli.divRound((m - 1000) * s, 1000);
                String pct = Milli.format(Math.abs(scaled - 1000) * 100);
                return t.f(scaled >= 1000 ? "spellcrafter.part.outgoing.more" : "spellcrafter.part.outgoing.less",
                        "percent", pct, "cause", str(part, "cause"), "duration", dur);
            }
            case "speed":
                return t.f("spellcrafter.part.speed", "signed_percent", signed((milli(part, "multiplier") - 1000) * 100),
                        "duration", dur);
            case "stat": {
                String stat = str(part, "stat");
                long amount = milli(part, "amount");
                boolean mult = "multiply".equals(str(part, "op"));
                if ("speed".equals(stat)) {
                    return t.f("spellcrafter.part.stat.speed", "signed_percent",
                            signed(mult ? (amount - 1000) * 100 : amount), "duration", dur);
                }
                if ("health".equals(stat)) {
                    String v = mult ? signed((scale(amount, s) - 1000) * 100) + "%" : signed(scale(amount, s));
                    return t.f("spellcrafter.part.stat.health", "signed_amount", v, "duration", dur);
                }
                return raw(part, t);
            }
            case "regen": {
                long per = milli(part, "interval");
                long amount = scale(milli(part, "amount"), s);
                long perSecond = per > 0 ? Milli.divRound(amount * 1000, per) : amount;
                return t.f("spellcrafter.part.regen", "amount", Milli.format(perSecond)
                        + ("percent".equals(str(part, "unit")) ? "%" : ""), "stat", str(part, "stat"), "duration", dur);
            }
            case "lock_movement":
                return t.f("spellcrafter.part.lock_movement", "duration", dur);
            case "disable_actions": {
                List<String> names = new ArrayList<>();
                if (part.get("actions") instanceof Json.Arr a) for (Json j : a.items()) if (j instanceof Json.Str js) names.add(js.value());
                return t.f("spellcrafter.part.disable_actions", "actions", String.join(", ", names), "duration", dur);
            }
            case "invulnerable":
                return t.f("spellcrafter.part.invulnerable", "duration", dur);
            case "intangible":
                return t.f("spellcrafter.part.intangible", "duration", dur);
            case "light":
                return t.f("spellcrafter.part.light", "radius", Milli.format(milli(part, "radius")), "duration", dur);
            case "message", "look", "model", "behavior":
                return null;
            default:
                return raw(part, t);
        }
    }

    private static String raw(Json.Obj part, T t) {
        List<String> fs = new ArrayList<>();
        for (Map.Entry<String, Json> e : part.members().entrySet()) {
            if (!e.getKey().equals("type")) fs.add(e.getKey() + " " + text(e.getValue()));
        }
        return t.f("spellcrafter.part.raw", "type", str(part, "type"), "fields", String.join(", ", fs));
    }

    /** A condition in words: "in a storm", "from 5:00 to 8:00", "at night". */
    static String when(Condition c, T t) {
        if (c instanceof Condition.Not n) return t.f("spellcrafter.when.not", "condition", when(n.of(), t));
        if (c instanceof Condition.All a) return join(a.of(), " and ", t);
        if (c instanceof Condition.Any a) return join(a.of(), " or ", t);
        Condition.Leaf l = (Condition.Leaf) c;
        Json.Obj j = l.json();
        switch (l.type()) {
            case "weather": {
                List<String> parts = new ArrayList<>();
                if (j.get("category") instanceof Json.Arr cats) {
                    for (Json k : cats.items()) {
                        String cat = text(k);
                        String key = "spellcrafter.when.weather." + cat;
                        parts.add(t.has(key) ? t.f(key) : t.f("spellcrafter.when.weather", "category", cat));
                    }
                }
                return String.join(" or ", parts);
            }
            case "time_of_day":
                return t.f("spellcrafter.when.time_of_day", "from", text(j.get("from")), "to", text(j.get("to")));
            case "is_night":
                return t.f("spellcrafter.when.is_night");
            case "wielding", "wearing":
                return t.f("spellcrafter.when." + l.type(), "item", j.has("item") ? text(j.get("item")) : text(j.get("tag")));
            default:
                return t.f("spellcrafter.when.other", "type", l.type());
        }
    }

    private static String join(List<Condition> cs, String sep, T t) {
        List<String> out = new ArrayList<>();
        for (Condition c : cs) out.add(when(c, t));
        return String.join(sep, out);
    }

    private static String change(Rider r, Map<String, Effect> catalog, T t) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Long> e : r.bonusPct().entrySet()) {
            out.add(t.f("spellcrafter.change.bonus_pct", "n", Long.toString(e.getValue()), "param", e.getKey()));
        }
        for (Map.Entry<String, Long> e : r.plus().entrySet()) {
            out.add(t.f("spellcrafter.change.plus", "n", Milli.format(e.getValue()), "param", e.getKey()));
        }
        for (Map.Entry<String, Long> e : r.morePct().entrySet()) {
            out.add(t.f("spellcrafter.change.more_pct", "n", Milli.format(1000 + e.getValue() * 10), "param", e.getKey()));
        }
        for (Payload p : r.add()) out.add(t.f("spellcrafter.change.add", "name", effectName(p, catalog)));
        return String.join(", ", out);
    }

    // ---------------------------------------------------------------------------------------------- helpers

    private static String effectId(Payload p) {
        return p.fields().get("effect") instanceof Json.Str s ? s.value() : null;
    }

    /** The effect's name; a vanilla one without a name shows its id, words split ("Ability Frost Chill"). */
    static String effectName(Payload p, Map<String, Effect> catalog) {
        String id = effectId(p);
        Effect e = id == null ? null : catalog.get(id);
        String name = e == null ? id : e.name();
        if (name == null) return p.id();
        if (name.startsWith("hytale:")) name = name.substring(7).replace('_', ' ');
        return name;
    }

    private static long scale(long milli, long strengthMilli) {
        return Milli.divRound(milli * strengthMilli, 1000);
    }

    private static String signed(long milli) {
        return milli >= 0 ? "+" + Milli.format(milli) : "-" + Milli.format(-milli);
    }

    private static long milli(Json.Obj o, String key) {
        return o.get(key) instanceof Json.Num n ? Effect.milli(n) : 0;
    }

    private static String num(Long milli) {
        return milli == null ? "?" : Milli.format(milli);
    }

    private static String str(Json.Obj o, String key) {
        return o.get(key) instanceof Json.Str s ? s.value() : null;
    }

    private static String text(Json v) {
        if (v instanceof Json.Str s) return s.value();
        if (v instanceof Json.Num n) return n.text();
        if (v == null || v instanceof Json.Null) return "–";
        return v.toString();
    }

    private static String capital(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Templates: {@code {name}} replaced by its value; a missing key shows the key itself. */
    static final class T {
        private final Map<String, String> lang;

        T(Map<String, String> lang) {
            this.lang = lang;
        }

        boolean has(String key) {
            return lang.containsKey(key);
        }

        String f(String key, String... kv) {
            String s = lang.getOrDefault(key, key);
            for (int i = 0; i + 1 < kv.length; i += 2) s = s.replace("{" + kv[i] + "}", kv[i + 1] == null ? "?" : kv[i + 1]);
            return s;
        }
    }
}
