package spellcrafter.hytale.casting;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import spellcrafter.core.json.Json;
import spellcrafter.core.model.Model.Payload;
import spellcrafter.core.model.Model.Spell;
import spellcrafter.core.model.Model.Targeting;
import spellcrafter.core.model.Model.Vessel;
import spellcrafter.hytale.casting.Casting.Line;

/**
 * What {@code /spell} answers (design/commands-settings.md section 1), with no game API: the lines for {@code list},
 * {@code info} and {@code cooldowns}, and finding spells and vessels by what a player types. Every line is a lang key
 * ({@code spellcrafter.cmd.*}) with its parameters. The Hytale command classes only parse, check permissions, and act.
 */
public final class SpellCommands {
    private SpellCommands() {
    }

    /** A spell by full id, local id ({@code flame_bolt}) or name ({@code "Flame Bolt"}), case-insensitive; else null. */
    public static Spell findSpell(Content content, String text) {
        if (text == null || text.isBlank()) return null;
        String t = text.trim();
        Spell exact = content.spell(t);
        if (exact != null) return exact;
        for (Spell s : content.spells().values()) {
            if (local(s.id()).equalsIgnoreCase(t) || s.name().equalsIgnoreCase(t)
                    || local(s.id()).equalsIgnoreCase(t.replace(' ', '_'))) return s;
        }
        return null;
    }

    /** A vessel by full id, local id ({@code scroll_flame_bolt}) or game item id; else null. */
    public static Vessel findVessel(Content content, String text) {
        if (text == null || text.isBlank()) return null;
        String t = text.trim();
        Vessel v = content.vessels().get(t);
        if (v != null) return v;
        v = content.vesselForItem(t);
        if (v != null) return v;
        for (Vessel x : content.vessels().values()) {
            if (local(x.id()).equalsIgnoreCase(t)) return x;
            String item = Content.hytaleItem(x);
            if (item != null && item.equalsIgnoreCase(t)) return x;
        }
        return null;
    }

    /** {@code /spell list [filter]}: name, school, mana, vessels; the filter matches name, school or category. */
    public static List<Line> list(Content content, String filter) {
        String f = filter == null ? "" : filter.trim().toLowerCase(Locale.ROOT);
        List<Line> rows = new ArrayList<>();
        for (Spell s : content.spells().values()) {
            String school = school(s);
            boolean match = f.isEmpty() || s.name().toLowerCase(Locale.ROOT).contains(f) || school.contains(f)
                    || s.categories().stream().anyMatch(c -> c.toLowerCase(Locale.ROOT).contains(f));
            if (!match) continue;
            rows.add(Line.of("spellcrafter.cmd.list.row", "name", s.name(), "id", local(s.id()), "school", school,
                    "mana", Long.toString(s.costMana()), "vessels", String.join(", ", vesselKinds(content, s))));
        }
        List<Line> out = new ArrayList<>();
        if (rows.isEmpty()) {
            out.add(Line.of("spellcrafter.cmd.list.none", "filter", f));
            return out;
        }
        out.add(Line.of("spellcrafter.cmd.list.header", "count", Integer.toString(rows.size()), "filter", f.isEmpty() ? "all" : f));
        out.addAll(rows);
        return out;
    }

    /**
     * {@code /spell info <spell>}: the spell's summary. A stand-in for the stat block (window.md section 2), which the
     * core will write; this method is the one place to swap it in.
     */
    private static volatile Map<String, String> statBlockLang;

    /** The stat block's English templates, from the core's resource (loaded once). */
    static Map<String, String> statBlockLang() {
        Map<String, String> l = statBlockLang;
        if (l != null) return l;
        try (var in = SpellCommands.class.getClassLoader().getResourceAsStream("spellcrafter/spellcrafter.statblock.en-US.lang")) {
            l = in == null ? Map.of() : spellcrafter.core.editor.StatBlock.parseLang(
                    new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException e) {
            l = Map.of();
        }
        statBlockLang = l;
        return l;
    }

    /**
     * {@code /spell info <spell>}: the core's stat block (window.md section 2), with each effect as {@code describe}
     * gives it. Without the templates, the summary below.
     */
    public static List<Line> infoLines(Spell s, java.util.function.Function<String, spellcrafter.core.editor.Effect> describe) {
        Map<String, String> lang = statBlockLang();
        if (lang.isEmpty()) return infoLines(s);
        Map<String, spellcrafter.core.editor.Effect> catalog = new java.util.LinkedHashMap<>();
        for (Payload p : s.payloads()) {
            String id = p.fields().get("effect") instanceof Json.Str e ? e.value() : null;
            if (id == null || catalog.containsKey(id)) continue;
            spellcrafter.core.editor.Effect e = describe == null ? null : describe.apply(id);
            if (e != null) catalog.put(id, e);
        }
        List<Line> out = new ArrayList<>();
        for (String text : spellcrafter.core.editor.StatBlock.lines(s, catalog, null, school(s), lang)) {
            out.add(Line.of("spellcrafter.cmd.info.text", "text", text));
        }
        return out;
    }

    public static List<Line> infoLines(Spell s) {
        List<Line> out = new ArrayList<>();
        out.add(Line.of("spellcrafter.cmd.info.name", "name", s.name(), "id", s.id()));
        out.add(Line.of("spellcrafter.cmd.info.school", "school", school(s), "categories", String.join(", ", s.categories())));
        out.add(Line.of("spellcrafter.cmd.info.cost", "mana", Long.toString(s.costMana()), "cast", seconds(s.castTime()),
                "cooldown", seconds(s.cooldown())));
        Map<String, Long> dp = s.delivery().params();
        String range = dp.containsKey("range") ? seconds(dp.get("range")) : "-";
        String burst = s.delivery().burst() == null ? "none"
                : seconds(dp.getOrDefault("burst.radius", 0L)) + " (" + s.delivery().burst() + ")";
        out.add(Line.of("spellcrafter.cmd.info.delivery", "kind", s.delivery().kind(), "range", range, "burst", burst));
        for (Payload p : s.payloads()) {
            if (p.isReroll()) {
                out.add(Line.of("spellcrafter.cmd.info.reroll", "id", p.id(), "pick", Targeting.pick(p.fields()),
                        "rolls", Long.toString(Targeting.reroll(p.fields())), "weight", Long.toString(Targeting.weight(p.fields()))));
                continue;
            }
            String effect = p.fields().get("effect") instanceof Json.Str e ? e.value() : p.provider() + " " + p.op();
            String pick = Targeting.pick(p.fields());
            out.add(Line.of("spellcrafter.cmd.info.payload", "id", p.id(), "effect", effect,
                    "target", Targeting.target(p.fields(), s.delivery().kind()),
                    "chance", Long.toString(Targeting.chance(p.fields())),
                    "pick", pick == null ? "-" : pick + " (weight " + Targeting.weight(p.fields()) + ")"));
        }
        return out;
    }

    /** {@code /spell cooldowns}: each spell the caster waits on, soonest first. */
    public static List<Line> cooldowns(Content content, Map<String, Long> left, String who) {
        List<Line> out = new ArrayList<>();
        if (left.isEmpty()) {
            out.add(Line.of("spellcrafter.cmd.cooldowns.none", "player", who));
            return out;
        }
        out.add(Line.of("spellcrafter.cmd.cooldowns.header", "player", who));
        for (Map.Entry<String, Long> e : left.entrySet()) {
            Spell s = content.spell(e.getKey());
            out.add(Line.of("spellcrafter.cmd.cooldowns.row", "spell", s == null ? e.getKey() : s.name(),
                    "seconds", Casting.secondsText(e.getValue())));
        }
        return out;
    }

    /** The spell's school: its first {@code spellcrafter:} category, or {@code utility}. */
    public static String school(Spell s) {
        for (String c : s.categories()) if (c.startsWith("spellcrafter:")) return c.substring("spellcrafter:".length());
        return "utility";
    }

    private static List<String> vesselKinds(Content content, Spell s) {
        List<String> out = new ArrayList<>();
        for (Vessel v : content.vessels().values()) if (v.spell().equals(s.id())) out.add(v.kind());
        return out;
    }

    static String local(String id) {
        return id.substring(id.lastIndexOf('/') + 1);
    }

    /** Thousandths as a short decimal: 1500 is "1.5", 2000 is "2". */
    static String seconds(long milli) {
        String s = Long.toString(milli / 1000);
        long frac = Math.abs(milli % 1000);
        if (frac == 0) return s;
        String f = String.format(Locale.ROOT, "%03d", frac);
        while (f.endsWith("0")) f = f.substring(0, f.length() - 1);
        return s + "." + f;
    }
}
