package spellcrafter.core.editor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import spellcrafter.core.json.Json;
import spellcrafter.core.num.Milli;

/**
 * One effect in BuffEffects' catalog, as the editor needs it (design/editor.md sections 3, 4 and 4a): its parts,
 * duration and what a caller may set. The adapter fills the catalog from BuffEffects' {@code list} and
 * {@code describe} ({@code EffectInfo.definitionJson}); the core only reads the definition.
 *
 * @param id           the effect reference a payload names: {@code <mod id>/<id>}, or {@code hytale:<id>} for vanilla
 * @param durationMs   the effect's own run time in ms; null for {@code infinite} or a clock duration
 * @param parts        BuffEffects' parts as written (vanilla ones translated by BuffEffects' describe, BFX-016);
 *                     <b>null</b> when the effect is listed without them, which the readout shows as unrated
 * @param callerMaySet param → bounds in thousandths (seconds for {@code duration}). BuffEffects' default is
 *                     {@code duration} only, bounded by the effect's own duration
 * @param category     the definition's {@code category} ("fire"), or null: a new spell's default school
 * @param look         the Hytale binding's {@code look.while_active}, or null (the stat block reads see-through from it)
 */
public record Effect(String id, String name, boolean harmful, Long durationMs, List<Json.Obj> parts,
        Map<String, Bound> callerMaySet, String category, Json.Obj look) {

    public Effect {
        parts = parts == null ? null : List.copyOf(parts);
        callerMaySet = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(callerMaySet));
    }

    /** Bounds in thousandths; either may be null. */
    public record Bound(Long min, Long max) {
        public boolean holds(long v) {
            return (min == null || v >= min) && (max == null || v <= max);
        }
    }

    /** Whether BuffEffects described the effect's parts. */
    public boolean described() {
        return parts != null;
    }

    /** The part types the effect has, in order. Empty when undescribed. */
    public List<String> partTypes() {
        List<String> out = new ArrayList<>();
        if (parts != null) {
            for (Json.Obj p : parts) {
                if (p.get("type") instanceof Json.Str s) out.add(s.value());
            }
        }
        return out;
    }

    /** An effect BuffEffects lists without its parts. */
    public static Effect undescribed(String id, String name, boolean harmful, Long durationMs) {
        Map<String, Bound> cms = new LinkedHashMap<>();
        if (durationMs != null) cms.put("duration", new Bound(durationMs, durationMs));
        return new Effect(id, name, harmful, durationMs, null, cms, null, null);
    }

    /**
     * Reads one definition from a {@code buffeffects.effects} file (or {@code definitionJson}) of mod {@code modId}.
     * Lenient: BuffEffects' loader has already validated it; a field the editor can't read is left at its default.
     */
    public static Effect fromDefinition(Json.Obj def, String modId) {
        String local = def.get("id") instanceof Json.Str s ? s.value() : "";
        String id = local.contains(":") || local.contains("/") ? local : modId + "/" + local;
        String name = def.get("name") instanceof Json.Str s ? s.value() : local;
        boolean harmful = def.get("harmful") instanceof Json.Bool b && b.value();
        Long durationMs = null;
        if (def.get("duration") instanceof Json.Obj d && d.get("seconds") instanceof Json.Num n) durationMs = milli(n);
        List<Json.Obj> parts = new ArrayList<>();
        if (def.get("parts") instanceof Json.Arr a) {
            for (Json p : a.items()) if (p instanceof Json.Obj o) parts.add(o);
        }
        Map<String, Bound> cms = new LinkedHashMap<>();
        if (def.get("caller_may_set") instanceof Json.Obj c) {
            for (Map.Entry<String, Json> e : c.members().entrySet()) {
                if (!(e.getValue() instanceof Json.Obj b)) continue;
                cms.put(e.getKey(), new Bound(b.get("min") instanceof Json.Num n ? milli(n) : null,
                        b.get("max") instanceof Json.Num n ? milli(n) : null));
            }
        } else if (durationMs != null) {
            cms.put("duration", new Bound(durationMs, durationMs));
        }
        String category = def.get("category") instanceof Json.Str s && !s.value().isEmpty() ? s.value() : null;
        Json.Obj look = def.get("bindings") instanceof Json.Obj b && b.get("hytale") instanceof Json.Obj h
                && h.get("look") instanceof Json.Obj l && l.get("while_active") instanceof Json.Obj w ? w : null;
        return new Effect(id, name, harmful, durationMs, parts, cms, category, look);
    }

    /** Every effect in a {@code buffeffects.effects} file. */
    public static List<Effect> fromFile(Json doc) {
        List<Effect> out = new ArrayList<>();
        if (!(doc instanceof Json.Obj o)) return out;
        String modId = o.get("mod") instanceof Json.Obj m && m.get("id") instanceof Json.Str s ? s.value() : "";
        if (o.get("effects") instanceof Json.Arr a) {
            for (Json e : a.items()) if (e instanceof Json.Obj eo) out.add(fromDefinition(eo, modId));
        }
        return out;
    }

    /** A JSON number as thousandths (0 when it has more than 3 decimals). */
    public static long milliOf(Json.Num n) {
        return milli(n);
    }

    static long milli(Json.Num n) {
        Milli.Parsed p = Milli.parseMilli(n.text());
        return p.problem() == null ? p.value() : 0;
    }
}
