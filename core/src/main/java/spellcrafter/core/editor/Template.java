package spellcrafter.core.editor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import spellcrafter.core.format.Parsed;
import spellcrafter.core.format.Problem;
import spellcrafter.core.json.Json;
import spellcrafter.core.num.Milli;

/**
 * A spell template (design/editor.md section 2): every spell the editor makes starts from one. Templates are data,
 * {@code spellcrafter.templates} 1.0, so more can be added without code.
 *
 * @param id       qualified, {@code Hledjolf:SpellCrafter/template.bolt}; {@link #shortName()} is what
 *                 {@code provenance.made_from} records ({@code template:bolt})
 * @param delivery the spell's {@code delivery} object, as written
 * @param castMs   cast time, ms; {@code cooldownMs} likewise
 * @param vessels  the vessel kinds made by default
 */
public record Template(String id, String name, Json.Obj delivery, List<Slot> slots, long castMs, long cooldownMs,
        List<String> vessels) {

    public Template {
        slots = List.copyOf(slots);
        vessels = List.copyOf(vessels);
    }

    /**
     * A payload slot and which effects it accepts. {@code partsAny}: the effect has at least one of these part types
     * (empty: no such rule); {@code harmful}: the effect's {@code harmful} must equal it (null: either).
     * {@code defaultEffect} is the effect a new draft starts with (null: the first the catalog offers).
     */
    public record Slot(String id, List<String> partsAny, Boolean harmful, String defaultEffect) {
        public Slot {
            partsAny = List.copyOf(partsAny);
        }

        /** Null if the slot accepts the effect, otherwise why not. */
        public String refuses(Effect e) {
            if (harmful != null && e.harmful() != harmful) {
                return harmful ? "the slot takes a harmful effect" : "the slot takes a beneficial effect";
            }
            if (!partsAny.isEmpty()) {
                if (!e.described()) return "its parts aren't known yet (not described by BuffEffects), and the slot needs one of " + partsAny;
                for (String t : e.partTypes()) if (partsAny.contains(t)) return null;
                return "the slot needs an effect with one of " + partsAny;
            }
            return null;
        }
    }

    /** {@code template:bolt} for {@code .../template.bolt}. */
    public String shortName() {
        String local = id.substring(id.lastIndexOf('/') + 1);
        return "template:" + (local.startsWith("template.") ? local.substring(9) : local);
    }

    public static final Set<String> VESSEL_KINDS = Set.of("scroll", "potion", "wand");

    /** Reads a {@code spellcrafter.templates} 1.0 file. */
    public static Parsed<List<Template>> parse(Json doc) {
        List<Problem> problems = new ArrayList<>();
        List<Template> out = new ArrayList<>();
        if (!(doc instanceof Json.Obj o)) return invalid("", "a templates file is an object");
        if (!(o.get("format") instanceof Json.Str f) || !f.value().equals("spellcrafter.templates")) {
            problems.add(new Problem("unsupported_format", "/format", "expected \"spellcrafter.templates\""));
        }
        if (!(o.get("format_version") instanceof Json.Str v) || !v.value().startsWith("1.")) {
            problems.add(new Problem("unsupported_version", "/format_version", "this core reads major version 1"));
        }
        Set<String> ids = new HashSet<>();
        List<Json> items = o.get("templates") instanceof Json.Arr a ? a.items() : List.of();
        if (items.isEmpty()) problems.add(new Problem("invalid", "/templates", "no templates"));
        for (int i = 0; i < items.size(); i++) {
            String p = "/templates/" + i;
            if (!(items.get(i) instanceof Json.Obj t)) {
                problems.add(new Problem("invalid", p, "a template is an object"));
                continue;
            }
            String id = str(t, "id");
            String name = str(t, "name");
            if (id == null || !id.contains("/")) problems.add(new Problem("bad_id", p + "/id", "a template id is <mod id>/<name>"));
            else if (!ids.add(id)) problems.add(new Problem("duplicate_id", p + "/id", "\"" + id + "\" appears twice"));
            if (name == null) problems.add(new Problem("invalid", p + "/name", "a template has a name"));
            Json.Obj delivery = t.get("delivery") instanceof Json.Obj d ? d : null;
            if (delivery == null || !(delivery.get("kind") instanceof Json.Str)) {
                problems.add(new Problem("invalid", p + "/delivery", "a template has a delivery with a kind"));
            }
            List<Slot> slots = new ArrayList<>();
            List<Json> ss = t.get("slots") instanceof Json.Arr a ? a.items() : List.of();
            if (ss.isEmpty()) problems.add(new Problem("invalid", p + "/slots", "a template has at least one slot"));
            for (int k = 0; k < ss.size(); k++) {
                String sp = p + "/slots/" + k;
                if (!(ss.get(k) instanceof Json.Obj so) || str(so, "id") == null) {
                    problems.add(new Problem("invalid", sp, "a slot has an id"));
                    continue;
                }
                Json.Obj acc = so.get("accepts") instanceof Json.Obj ao ? ao : new Json.Obj(java.util.Map.of());
                List<String> any = new ArrayList<>();
                if (acc.get("parts_any") instanceof Json.Arr pa) {
                    for (Json j : pa.items()) if (j instanceof Json.Str s) any.add(s.value());
                }
                Boolean harmful = acc.get("harmful") instanceof Json.Bool b ? b.value() : null;
                slots.add(new Slot(str(so, "id"), any, harmful, str(so, "default_effect")));
            }
            long castMs = 0, cooldownMs = 0;
            if (t.get("cast") instanceof Json.Obj c) {
                castMs = milli(c.get("time"), p + "/cast/time", problems);
                cooldownMs = milli(c.get("cooldown"), p + "/cast/cooldown", problems);
            } else {
                problems.add(new Problem("invalid", p + "/cast", "a template has a cast time and cooldown"));
            }
            List<String> vessels = new ArrayList<>();
            if (t.get("vessels") instanceof Json.Arr va) {
                for (int k = 0; k < va.items().size(); k++) {
                    String kind = va.items().get(k) instanceof Json.Str s ? s.value() : null;
                    if (kind == null || !VESSEL_KINDS.contains(kind)) {
                        problems.add(new Problem("invalid", p + "/vessels/" + k, "a vessel kind is scroll, potion or wand"));
                    } else {
                        vessels.add(kind);
                    }
                }
            }
            if (delivery != null && vessels.contains("potion") && !"self".equals(str(delivery, "kind"))) {
                problems.add(new Problem("vessel_delivery", p + "/vessels", "a potion holds only a self spell"));
            }
            out.add(new Template(id, name, delivery, slots, castMs, cooldownMs, vessels));
        }
        return problems.isEmpty() ? new Parsed.Ok<>(out, List.of()) : new Parsed.Invalid<>(problems);
    }

    private static Parsed<List<Template>> invalid(String p, String m) {
        return new Parsed.Invalid<>(List.of(new Problem("invalid", p, m)));
    }

    private static String str(Json.Obj o, String key) {
        return o.get(key) instanceof Json.Str s && !s.value().isEmpty() ? s.value() : null;
    }

    private static long milli(Json v, String p, List<Problem> problems) {
        if (v instanceof Json.Num n) {
            Milli.Parsed m = Milli.parseMilli(n.text());
            if (m.problem() == null && m.value() >= 0) return m.value();
        }
        problems.add(new Problem("invalid", p, "a number of seconds, at least 0"));
        return 0;
    }
}
