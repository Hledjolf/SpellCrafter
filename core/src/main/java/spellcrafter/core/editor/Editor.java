package spellcrafter.core.editor;

import static spellcrafter.core.power.Fixed.ONE;
import static spellcrafter.core.power.Fixed.mul;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import spellcrafter.core.eval.CastInput;
import spellcrafter.core.eval.CastResult;
import spellcrafter.core.eval.Evaluator;
import spellcrafter.core.eval.Facts;
import spellcrafter.core.eval.Tri;
import spellcrafter.core.format.Formats;
import spellcrafter.core.format.Parsed;
import spellcrafter.core.format.Problem;
import spellcrafter.core.format.Vocabulary;
import spellcrafter.core.json.Json;
import spellcrafter.core.json.JsonWriter;
import spellcrafter.core.model.Condition;
import spellcrafter.core.model.Model.Gate;
import spellcrafter.core.model.Model.Profile;
import spellcrafter.core.model.Model.Rider;
import spellcrafter.core.model.Model.ServerSettings;
import spellcrafter.core.model.Model.Spell;
import spellcrafter.core.model.Model.Vessel;
import spellcrafter.core.num.Milli;
import spellcrafter.core.power.Fixed;
import spellcrafter.core.power.Frequencies;
import spellcrafter.core.power.Gta;
import spellcrafter.core.power.Placement;

/**
 * The spell editor's model (design/editor.md): one draft spell, the commands that change it, the live readout, undo
 * and redo, save to the {@code default} mod and "move to mod". A view-model: state and commands, no rendering and no
 * I/O. The adapter draws the state and performs the {@link Write}s each command returns.
 *
 * <p>Every command returns {@link Ok} with the new state and readout, or {@link Refused} saying why; a refused command
 * changes nothing. The draft is validated with the core's own validator after every command, so the editor never
 * holds a spell the loader would refuse.
 */
public final class Editor {

    public static final String DEFAULT_MOD = "default";
    public static final String PROVIDER = "BuffEffects";

    /**
     * What the editor works with. {@code frequencies} says how often world-controlled conditions hold (the adapter's
     * defaults, with the server's settings over them); anything it can't price is at the best case, with no gate
     * discount (spell-format 13). {@code recipes} writes saved vessels' recipes; null for none (they can only be given). {@code author} and {@code today} go into new drafts'
     * provenance (the core has no clock).
     */
    public record Context(List<Template> templates, List<Effect> catalog, Profile profile, ServerSettings settings,
            Frequencies frequencies, RecipeRules recipes, Gta.Reference reference, String author, String today) {
        public Context {
            templates = List.copyOf(templates);
            catalog = List.copyOf(catalog);
        }
    }

    /**
     * The draft: the spell as authored JSON, the template it came from (null for a spell opened from a file without
     * one), the vessel kinds to make, and whether it has been saved (a saved spell keeps its id on rename).
     */
    public record Draft(Json.Obj spell, String template, List<String> vessels, boolean saved) {
        public Draft {
            vessels = List.copyOf(vessels);
        }

        public String id() {
            return spell.get("id") instanceof Json.Str s ? s.value() : null;
        }

        public Json toJson() {
            return JsonWriter.obj("spell", spell, "template", template == null ? new Json.Null() : new Json.Str(template),
                    "vessels", Readout.strs(vessels), "saved", new Json.Bool(saved));
        }
    }

    /**
     * One mod's spell and vessel files, as the adapter read them. {@code open}: the mod is open from its source
     * folder (UME-D090), so "move to mod" may write into it. Either document may be null (no file yet).
     */
    public record ModFiles(Json.Obj spells, Json.Obj vessels, boolean open) {
    }

    /**
     * A file the adapter writes. {@code where}: {@code default}, a mod id, or {@code overrides/<mod id>} (the
     * server's override folder, spell-format 10); {@code file}: {@code spells}, {@code vessels} or {@code spells.bak}.
     */
    public record Write(String where, String file, Json doc) {
    }

    /** A command's result. */
    public sealed interface Outcome permits Ok, Refused {
    }

    /** The new state. {@code writes} is what to save (only {@code save} and {@code move} write); {@code info} answers a query. */
    public record Ok(Draft draft, Readout readout, List<Write> writes, Json info) implements Outcome {
    }

    /** Nothing changed. {@code details}: the bounds, the validator's problems, or the choices; may be null. */
    public record Refused(String code, String message, Json details) implements Outcome {
    }

    private final Context ctx;
    private final Formats.Options opt;
    private final Map<String, Effect> catalog = new LinkedHashMap<>();
    private final Map<String, Template> templates = new LinkedHashMap<>();
    private final Map<String, ModFiles> library = new LinkedHashMap<>();
    private final Deque<Draft> undo = new ArrayDeque<>();
    private final Deque<Draft> redo = new ArrayDeque<>();
    private Draft draft;
    private Readout readout;

    public Editor(Context ctx, Map<String, ModFiles> library) {
        this.ctx = ctx;
        this.opt = new Formats.Options(Vocabulary.COMMANDDECK_1_0, ctx.settings().caps());
        for (Effect e : ctx.catalog()) catalog.put(e.id(), e);
        for (Template t : ctx.templates()) {
            templates.put(t.id(), t);
            templates.putIfAbsent(t.shortName(), t);
        }
        this.library.putAll(library);
    }

    public Draft draft() {
        return draft;
    }

    public Readout readout() {
        return readout;
    }

    /** The mod files as they stand after this session's saves and moves. */
    public Map<String, ModFiles> library() {
        return java.util.Collections.unmodifiableMap(library);
    }

    // ------------------------------------------------------------------------------------------------ commands

    /** Runs a command given as JSON: {@code {"cmd": "set", "param": "strength", "value": 1.2}} and so on. */
    public Outcome run(Json.Obj c) {
        String cmd = str(c, "cmd");
        if (cmd == null) return refuse("unknown_command", "a command has a \"cmd\"", null);
        if (!cmd.equals("new") && !cmd.equals("open") && !cmd.equals("move") && draft == null) {
            return refuse("no_draft", "start a draft first (new or open)", null);
        }
        switch (cmd) {
            case "new": return newDraft(str(c, "template"));
            case "open": return open(str(c, "spell"));
            case "rename": return rename(str(c, "name"));
            case "choices": return choices(str(c, "slot"));
            case "choose_effect": return chooseEffect(str(c, "slot"), str(c, "effect"));
            case "add_effect": return addEffect(str(c, "effect"));
            case "remove_effect": return removeEffect(str(c, "id"));
            case "duplicate_effect": return duplicateEffect(str(c, "id"));
            case "set_payload": return setPayload(str(c, "id"), str(c, "field"), c.get("value"));
            case "set_burst": {
                Json v = c.get("radius");
                Milli.Parsed m = v instanceof Json.Num n ? Milli.parseMilli(n.text()) : null;
                return setBurst(m == null || !m.ok() ? null : m.value(), str(c, "affects"));
            }
            case "set_categories": {
                List<String> cats = new ArrayList<>();
                if (c.get("categories") instanceof Json.Arr a) for (Json j : a.items()) if (j instanceof Json.Str s) cats.add(s.value());
                return setCategories(cats);
            }
            case "set": {
                Json v = c.get("value");
                Milli.Parsed m = v instanceof Json.Num n ? Milli.parseMilli(n.text()) : null;
                if (m == null || !m.ok()) return refuse("invalid_value", "a number, to at most 3 decimals", null);
                return set(str(c, "param"), m.value());
            }
            case "add_rider": return addTo("riders", obj(c, "rider"), null);
            case "edit_rider": return addTo("riders", obj(c, "rider"), str(c, "id"));
            case "remove_rider": return removeFrom("riders", str(c, "id"));
            case "add_requirement": return addTo("requires", obj(c, "gate"), null);
            case "remove_requirement": return removeFrom("requires", str(c, "id"));
            case "add_component": return addTo("components", obj(c, "component"), null);
            case "remove_component": return removeFrom("components", str(c, "id"));
            case "set_vessels": {
                List<String> kinds = new ArrayList<>();
                if (c.get("kinds") instanceof Json.Arr a) for (Json j : a.items()) if (j instanceof Json.Str s) kinds.add(s.value());
                return setVessels(kinds);
            }
            case "undo": return undo();
            case "redo": return redo();
            case "save": return save(c.get("confirm") instanceof Json.Bool b && b.value());
            case "move": return move(str(c, "spell"), str(c, "to"));
            default: return refuse("unknown_command", "no command \"" + cmd + "\"", null);
        }
    }

    /** {@code new(template)}: a draft from a template, with the slot's default effect. */
    public Outcome newDraft(String templateId) {
        Template t = templateId == null ? null : templates.get(templateId);
        if (t == null) {
            List<String> ids = new ArrayList<>();
            for (Template x : ctx.templates()) ids.add(x.shortName());
            return refuse("unknown_template", "no template \"" + templateId + "\"", Readout.strs(ids));
        }
        List<Json> payloads = new ArrayList<>();
        for (Template.Slot slot : t.slots()) {
            Effect e = slot.defaultEffect() != null ? catalog.get(slot.defaultEffect()) : null;
            if (e == null || slot.refuses(e) != null) {
                e = null;
                for (Effect x : ctx.catalog()) {
                    if (slot.refuses(x) == null) {
                        e = x;
                        break;
                    }
                }
            }
            if (e == null) return refuse("no_effect", "no effect in BuffEffects' catalog fits slot \"" + slot.id() + "\"", null);
            payloads.add(payload(slot.id(), e.id()));
        }
        String name = "New " + t.name();
        Json.Obj spell = JsonWriter.obj(
                "format", new Json.Str("spellcrafter.spell"), "format_version", new Json.Str("1.0"),
                "id", new Json.Str(DEFAULT_MOD + "/" + slug(name)), "name", new Json.Str(name),
                "origin", JsonWriter.obj("kind", new Json.Str("native")),
                "cast", JsonWriter.obj("time", milli(t.castMs()), "cooldown", milli(t.cooldownMs())),
                "delivery", t.delivery(),
                "payloads", new Json.Arr(payloads),
                "categories", Readout.strs(defaultCategories(catalog.get(str((Json.Obj) payloads.get(0), "effect")))),
                "provenance", JsonWriter.obj("author", new Json.Str(ctx.author()), "created", new Json.Str(ctx.today()),
                        "made_from", Readout.strs(List.of(t.shortName()))),
                "cost", JsonWriter.obj("mana", new Json.Num(Long.toString(UNRATED_MANA))));
        return commit(new Draft(spell, t.id(), t.vessels(), false));
    }

    /** Opens a saved spell (from {@code default}, an override, or any mod) as the draft. */
    public Outcome open(String spellId) {
        if (spellId == null) return refuse("not_found", "which spell?", null);
        String mod = modOf(spellId);
        for (String where : List.of(overrides(mod), mod)) {
            ModFiles f = library.get(where);
            Json.Obj s = f == null ? null : findSpell(f.spells(), spellId);
            if (s == null) continue;
            // A spell in a pack has no format lines of its own: the draft is a standalone spell document.
            if (!s.has("format")) {
                Json.Obj head = JsonWriter.obj("format", new Json.Str("spellcrafter.spell"), "format_version",
                        f.spells().get("format_version") instanceof Json.Str v ? v : new Json.Str("1.0"));
                java.util.Map<String, Json> m = new java.util.LinkedHashMap<>(head.members());
                m.putAll(s.members());
                s = new Json.Obj(m);
            }
            List<String> kinds = new ArrayList<>();
            for (Json.Obj v : vesselsOf(f.vessels(), spellId)) kinds.add(str(v, "kind"));
            String template = null;
            if (s.get("provenance") instanceof Json.Obj pv && pv.get("made_from") instanceof Json.Arr mf) {
                for (Json j : mf.items()) {
                    if (j instanceof Json.Str js && templates.containsKey(js.value())) template = templates.get(js.value()).id();
                }
            }
            return commit(new Draft(s, template, kinds, true));
        }
        return refuse("not_found", "no spell \"" + spellId + "\"", null);
    }

    /** {@code rename(name)}: the name, and the id's slug while the spell has never been saved. */
    public Outcome rename(String name) {
        if (name == null || name.isBlank()) return refuse("invalid_value", "a spell needs a name", null);
        Json.Obj s = put(draft.spell(), "name", new Json.Str(name));
        if (!draft.saved()) s = put(s, "id", new Json.Str(DEFAULT_MOD + "/" + slug(name)));
        return commit(new Draft(s, draft.template(), draft.vessels(), draft.saved()));
    }

    /** The effects a slot accepts, for the picker: {@code info} lists their ids and names. */
    public Outcome choices(String slotId) {
        Template.Slot slot = slot(slotId);
        if (slot == null && !payloadIds().contains(slotId)) return refuse("unknown_slot", "no slot \"" + slotId + "\"", null);
        List<Json> out = new ArrayList<>();
        for (Effect e : ctx.catalog()) {
            if (slot == null || slot.refuses(e) == null) {
                out.add(JsonWriter.obj("id", new Json.Str(e.id()), "name", new Json.Str(e.name()),
                        "described", new Json.Bool(e.described())));
            }
        }
        return new Ok(draft, readout, List.of(), new Json.Arr(out));
    }

    /** {@code choose_effect(slot, effect)}: from the catalog, filtered by the slot's {@code accepts}. */
    public Outcome chooseEffect(String slotId, String effectId) {
        Template.Slot slot = slot(slotId);
        if (slot == null && !payloadIds().contains(slotId)) return refuse("unknown_slot", "no slot \"" + slotId + "\"", null);
        Effect e = effectId == null ? null : catalog.get(effectId);
        if (e == null) return refuse("unknown_effect", "\"" + effectId + "\" isn't in BuffEffects' catalog", null);
        String why = slot == null ? null : slot.refuses(e);
        if (why != null) return refuse("not_accepted", e.name() + " doesn't fit: " + why, null);
        List<Json> payloads = new ArrayList<>(arr(draft.spell(), "payloads"));
        boolean found = false;
        for (int i = 0; i < payloads.size(); i++) {
            if (payloads.get(i) instanceof Json.Obj p && slotId.equals(str(p, "id"))) {
                payloads.set(i, payload(slotId, e.id()));
                found = true;
            }
        }
        if (!found) payloads.add(payload(slotId, e.id()));
        Json.Obj s = put(draft.spell(), "payloads", new Json.Arr(payloads));
        // The school follows the first effect while it's still that effect's default.
        Json.Obj first = (Json.Obj) arr(draft.spell(), "payloads").get(0);
        if (slotId.equals(str(first, "id"))) {
            List<String> was = new ArrayList<>();
            for (Json j : arr(draft.spell(), "categories")) if (j instanceof Json.Str js) was.add(js.value());
            if (was.equals(defaultCategories(catalog.get(str(first, "effect"))))) {
                s = put(s, "categories", Readout.strs(defaultCategories(e)));
            }
        }
        return change(s);
    }

    /**
     * {@code add_effect(effect)}: one more payload (a new tab in the window), from anywhere in the catalog; its id is
     * the next free {@code e2}, {@code e3}, ...
     */
    public Outcome addEffect(String effectId) {
        Effect e = effectId == null ? null : catalog.get(effectId);
        if (e == null) return refuse("unknown_effect", "\"" + effectId + "\" isn't in BuffEffects' catalog", null);
        List<Json> payloads = new ArrayList<>(arr(draft.spell(), "payloads"));
        payloads.add(payload(freeId("e"), e.id()));
        return change(put(draft.spell(), "payloads", new Json.Arr(payloads)));
    }

    /** {@code remove_effect(id)}: drops a payload; the last one can't go (a spell has at least one). */
    public Outcome removeEffect(String id) {
        List<Json> payloads = new ArrayList<>(arr(draft.spell(), "payloads"));
        int at = id == null ? -1 : indexOf(payloads, id);
        if (at < 0) return refuse("not_found", "no effect \"" + id + "\"", null);
        if (payloads.size() == 1) return refuse("last_effect", "a spell needs at least one effect", null);
        payloads.remove(at);
        return change(put(draft.spell(), "payloads", new Json.Arr(payloads)));
    }

    /** {@code duplicate_effect(id)}: a copy of a payload, at the end, under a new id. */
    public Outcome duplicateEffect(String id) {
        List<Json> payloads = new ArrayList<>(arr(draft.spell(), "payloads"));
        int at = id == null ? -1 : indexOf(payloads, id);
        if (at < 0) return refuse("not_found", "no effect \"" + id + "\"", null);
        payloads.add(put((Json.Obj) payloads.get(at), "id", new Json.Str(freeId("e"))));
        return change(put(draft.spell(), "payloads", new Json.Arr(payloads)));
    }

    /**
     * {@code set_payload(id, field, value)}, spell-format 7b's fields: {@code target} ({@code target}, {@code caster},
     * {@code area}), {@code chance} (1–100), {@code pick} (a group id, declared in {@code picks} if new; empty clears
     * it), {@code weight} (from 1). A value of null removes the field.
     */
    public Outcome setPayload(String id, String field, Json value) {
        if (field == null || !List.of("target", "chance", "pick", "weight").contains(field)) {
            return refuse("unknown_param", "a payload's target, chance, pick or weight", null);
        }
        List<Json> payloads = new ArrayList<>(arr(draft.spell(), "payloads"));
        int at = id == null ? -1 : indexOf(payloads, id);
        if (at < 0) return refuse("not_found", "no effect \"" + id + "\"", null);
        Json.Obj p = (Json.Obj) payloads.get(at);
        boolean clear = value == null || value instanceof Json.Null || (value instanceof Json.Str vs && vs.value().isEmpty());
        p = clear ? without(p, field) : put(p, field, value);
        if (field.equals("pick") && clear) p = without(p, "weight");
        payloads.set(at, p);
        Json.Obj spell = put(draft.spell(), "payloads", new Json.Arr(payloads));
        // Pick groups follow their options: declare a new one, drop one nothing names.
        java.util.Set<String> named = new java.util.LinkedHashSet<>();
        for (Json j : payloads) if (j instanceof Json.Obj o && str(o, "pick") != null) named.add(str(o, "pick"));
        List<Json> picks = new ArrayList<>();
        java.util.Set<String> have = new java.util.HashSet<>();
        for (Json j : arr(spell, "picks")) {
            if (j instanceof Json.Obj o && named.contains(str(o, "id"))) {
                picks.add(o);
                have.add(str(o, "id"));
            }
        }
        for (String g : named) if (!have.contains(g)) picks.add(JsonWriter.obj("id", new Json.Str(g), "rolls", new Json.Num("1")));
        spell = picks.isEmpty() ? without(spell, "picks") : put(spell, "picks", new Json.Arr(picks));
        return change(spell);
    }

    /** {@code set_burst(radius, affects)}: a burst on impact; a radius of null (or 0) removes it. */
    public Outcome setBurst(Long radiusMilli, String affects) {
        Json.Obj d = (Json.Obj) draft.spell().get("delivery");
        Json.Obj ps = d.get("params") instanceof Json.Obj o ? o : JsonWriter.obj();
        if (radiusMilli == null || radiusMilli <= 0) {
            d = without(put(d, "params", without(ps, "burst.radius")), "burst");
            // Area payloads go back to the target.
            List<Json> payloads = new ArrayList<>();
            for (Json j : arr(draft.spell(), "payloads")) {
                Json.Obj o = (Json.Obj) j;
                payloads.add("area".equals(str(o, "target")) ? without(o, "target") : o);
            }
            return change(put(put(draft.spell(), "delivery", d), "payloads", new Json.Arr(payloads)));
        }
        String a = affects == null ? "others" : affects;
        d = put(put(d, "params", put(ps, "burst.radius", milli(radiusMilli))), "burst", JsonWriter.obj("affects", new Json.Str(a)));
        return change(put(draft.spell(), "delivery", d));
    }

    private String freeId(String prefix) {
        List<String> ids = payloadIds();
        for (int i = 2; ; i++) if (!ids.contains(prefix + i)) return prefix + i;
    }

    /** {@code set_categories([...])}: the spell's categories; its school is the first {@code spellcrafter:} one. */
    public Outcome setCategories(List<String> categories) {
        return change(put(draft.spell(), "categories", Readout.strs(categories)));
    }

    /** A new spell's categories: its first effect's {@code category} as {@code spellcrafter:<category>}, or none. */
    private static List<String> defaultCategories(Effect e) {
        return e == null || e.category() == null ? List.of() : List.of(RecipeRules.CATEGORY_PREFIX + e.category());
    }

    /**
     * {@code set(param, value)}, value in thousandths: {@code strength} and {@code duration} (or {@code <slot>.<param>}),
     * bounded by the effect's {@code caller_may_set}; {@code delivery.<param>}; {@code cast.time}, {@code cast.cooldown};
     * {@code cost.mana} only while the spell is unrated (a rated spell is priced by the method).
     */
    public Outcome set(String param, long value) {
        if (param == null) return refuse("unknown_param", "which param?", null);
        Json.Obj s = draft.spell();
        if (param.equals("cast.time") || param.equals("cast.cooldown")) {
            if (value < 0) return bounds(param, 0L, null);
            Json.Obj cast = (Json.Obj) s.get("cast");
            return change(put(s, "cast", put(cast, param.substring(5), milli(value))));
        }
        if (param.startsWith("delivery.")) {
            String name = param.substring(9);
            Json.Obj d = (Json.Obj) s.get("delivery");
            Json.Obj ps = d.get("params") instanceof Json.Obj o ? o : JsonWriter.obj();
            if (!ps.has(name)) return refuse("unknown_param", "this delivery has no \"" + name + "\"", Readout.strs(List.copyOf(ps.members().keySet())));
            if (value < 0) return bounds(param, 0L, null);
            return change(put(s, "delivery", put(d, "params", put(ps, name, milli(value)))));
        }
        if (param.equals("cost.mana")) {
            if (readout != null && readout.rated()) {
                return refuse("priced_by_method", "a rated spell's mana comes from the readout (" + readout.mana() + ")", null);
            }
            if (value < 0 || value % 1000 != 0) return refuse("invalid_value", "mana is a whole number, at least 0", null);
            return change(put(s, "cost", JsonWriter.obj("mana", new Json.Num(Long.toString(value / 1000)))));
        }
        String slotId = null, name = param;
        int dot = param.indexOf('.');
        if (dot > 0) {
            slotId = param.substring(0, dot);
            name = param.substring(dot + 1);
        }
        List<Json> payloads = new ArrayList<>(arr(s, "payloads"));
        for (int i = 0; i < payloads.size(); i++) {
            Json.Obj p = (Json.Obj) payloads.get(i);
            if (slotId != null && !slotId.equals(str(p, "id"))) continue;
            Effect e = catalog.get(str(p, "effect"));
            if (e == null) return refuse("unknown_effect", "\"" + str(p, "effect") + "\" isn't in BuffEffects' catalog", null);
            Effect.Bound b = e.callerMaySet().get(name);
            if (b == null) {
                return refuse("not_settable", e.name() + " lets callers set " + e.callerMaySet().keySet() + ", not \"" + name + "\"",
                        Readout.strs(List.copyOf(e.callerMaySet().keySet())));
            }
            if (!b.holds(value)) return bounds(param, b.min(), b.max());
            Json.Obj ps = p.get("params") instanceof Json.Obj o ? o : JsonWriter.obj();
            payloads.set(i, put(p, "params", put(ps, name, milli(value))));
            return change(put(s, "payloads", new Json.Arr(payloads)));
        }
        return refuse("unknown_param", "no payload " + (slotId == null ? "" : "\"" + slotId + "\" ") + "to set \"" + name + "\" on", null);
    }

    /** {@code add_rider}, {@code edit_rider}, {@code add_requirement}, {@code add_component}: {@code replace} is the id to replace. */
    private Outcome addTo(String key, Json.Obj item, String replace) {
        if (item == null) return refuse("invalid_value", "nothing to add", null);
        List<Json> items = new ArrayList<>(arr(draft.spell(), key));
        if (replace != null) {
            int at = indexOf(items, replace);
            if (at < 0) return refuse("not_found", "no " + singular(key) + " \"" + replace + "\"", null);
            items.set(at, item);
        } else {
            items.add(item);
        }
        return change(put(draft.spell(), key, new Json.Arr(items)));
    }

    private Outcome removeFrom(String key, String id) {
        List<Json> items = new ArrayList<>(arr(draft.spell(), key));
        int at = id == null ? -1 : indexOf(items, id);
        if (at < 0) return refuse("not_found", "no " + singular(key) + " \"" + id + "\"", null);
        items.remove(at);
        return change(items.isEmpty() ? without(draft.spell(), key) : put(draft.spell(), key, new Json.Arr(items)));
    }

    /** {@code set_vessels([...])}: scroll, potion, wand; a potion only of a {@code self} spell. */
    public Outcome setVessels(List<String> kinds) {
        Set<String> seen = new LinkedHashSet<>();
        for (String k : kinds) {
            if (!Template.VESSEL_KINDS.contains(k)) return refuse("invalid_value", "a vessel kind is scroll, potion or wand", null);
            if (!seen.add(k)) return refuse("invalid_value", "\"" + k + "\" is listed twice", null);
        }
        if (seen.contains("potion") && !"self".equals(deliveryKind(draft.spell()))) {
            return refuse("vessel_delivery", "a potion holds only a self spell", null);
        }
        return commit(new Draft(draft.spell(), draft.template(), List.copyOf(seen), draft.saved()));
    }

    public Outcome undo() {
        if (undo.isEmpty()) return refuse("nothing_to_undo", "nothing to undo", null);
        redo.push(draft);
        Draft d = undo.pop();
        return show(d);
    }

    public Outcome redo() {
        if (redo.isEmpty()) return refuse("nothing_to_redo", "nothing to redo", null);
        undo.push(draft);
        Draft d = redo.pop();
        return show(d);
    }

    // ------------------------------------------------------------------------------------------------ save and move

    /**
     * Saves the draft (design/editor.md section 5): into the {@code default} mod, or, for a spell another mod owns,
     * into the server's override folder for that mod. Replacing a spell with the same id needs {@code confirm}. The
     * method's mana is written into {@code cost.mana}; the vessels go alongside.
     */
    public Outcome save(boolean confirm) {
        Json.Obj spell = draft.spell();
        String id = draft.id();
        String mod = modOf(id);
        String where = mod.equals(DEFAULT_MOD) ? DEFAULT_MOD : overrides(mod);
        ModFiles f = library.getOrDefault(where, new ModFiles(null, null, true));
        Json.Obj pack = f.spells() != null ? f.spells() : emptyPack("spellcrafter.spells", mod);
        List<Json> spells = new ArrayList<>(arr(pack, "spells"));
        int at = indexOf(spells, id);
        if (at >= 0 && !confirm) {
            return refuse("confirm_replace", "\"" + id + "\" is already saved in " + where + ": save again with confirm to replace it", null);
        }
        if (readout.rated()) spell = put(spell, "cost", JsonWriter.obj("mana", new Json.Num(Long.toString(readout.mana()))));
        Json.Obj inPack = without(without(spell, "format"), "format_version");
        if (at >= 0) spells.set(at, inPack);
        else spells.add(inPack);
        Json.Obj newPack = put(pack, "spells", new Json.Arr(spells));
        Json.Obj vdoc = f.vessels() != null ? f.vessels() : emptyPack("spellcrafter.vessels", mod);
        List<Json> vs = new ArrayList<>();
        for (Json v : arr(vdoc, "vessels")) if (!(v instanceof Json.Obj o && id.equals(str(o, "spell")))) vs.add(v);
        String local = id.substring(id.lastIndexOf('/') + 1);
        List<String> categories = new ArrayList<>();
        for (Json c : arr(spell, "categories")) if (c instanceof Json.Str cs) categories.add(cs.value());
        String zone = readout.rated() ? readout.placement().zone() : null;
        for (String kind : draft.vessels()) {
            Json.Obj recipe = ctx.recipes() == null ? null : ctx.recipes().recipe(kind, zone, categories);
            Json.Obj v = JsonWriter.obj("id", new Json.Str(mod + "/" + kind + "_" + local), "kind", new Json.Str(kind),
                    "spell", new Json.Str(id));
            vs.add(recipe == null ? v : put(v, "recipe", recipe));
        }
        Json.Obj newVessels = put(vdoc, "vessels", new Json.Arr(vs));
        Refused bad = checkFiles(newPack, newVessels);
        if (bad != null) return bad;
        library.put(where, new ModFiles(newPack, newVessels, f.open()));
        Draft saved = new Draft(spell, draft.template(), draft.vessels(), true);
        draft = saved;
        return new Ok(saved, readout, List.of(new Write(where, "spells", newPack), new Write(where, "vessels", newVessels)), null);
    }

    /**
     * {@code move(spell, mod)}: from {@code default} into a mod open from its source folder. The id becomes
     * {@code <mod id>/<name>} and its vessels' ids and {@code spell} references follow; the spell leaves
     * {@code default}, whose old file is kept as {@code spells.bak}.
     */
    public Outcome move(String spellId, String target) {
        ModFiles def = library.get(DEFAULT_MOD);
        Json.Obj s = def == null || spellId == null ? null : findSpell(def.spells(), spellId);
        if (s == null) return refuse("not_found", "no spell \"" + spellId + "\" in the default mod", null);
        if (target == null || target.equals(DEFAULT_MOD)) return refuse("invalid_value", "move it to which mod?", null);
        ModFiles to = library.get(target);
        if (to == null || !to.open()) {
            return refuse("target_closed", target + " isn't open from its source folder (UME-D090), so it can't be written to", null);
        }
        String local = spellId.substring(spellId.lastIndexOf('/') + 1);
        String newId = target + "/" + local;
        Json.Obj toPack = to.spells() != null ? to.spells() : emptyPack("spellcrafter.spells", target);
        if (findSpell(toPack, newId) != null) return refuse("name_taken", target + " already has \"" + newId + "\"", null);

        List<Json> keep = new ArrayList<>();
        for (Json j : arr(def.spells(), "spells")) if (!(j instanceof Json.Obj o && spellId.equals(str(o, "id")))) keep.add(j);
        List<Json> moved = new ArrayList<>(arr(toPack, "spells"));
        moved.add(put(s, "id", new Json.Str(newId)));
        List<Json> defVessels = new ArrayList<>();
        List<Json> toVessels = new ArrayList<>(arr(to.vessels(), "vessels"));
        for (Json j : arr(def.vessels(), "vessels")) {
            Json.Obj v = (Json.Obj) j;
            if (!spellId.equals(str(v, "spell"))) {
                defVessels.add(v);
                continue;
            }
            String vid = str(v, "id");
            String vlocal = vid == null ? str(v, "kind") + "_" + local : vid.substring(vid.lastIndexOf('/') + 1);
            toVessels.add(put(put(v, "id", new Json.Str(target + "/" + vlocal)), "spell", new Json.Str(newId)));
        }
        Json.Obj newDefault = put(def.spells(), "spells", new Json.Arr(keep));
        Json.Obj newDefVessels = def.vessels() == null ? null : put(def.vessels(), "vessels", new Json.Arr(defVessels));
        Json.Obj newTo = put(toPack, "spells", new Json.Arr(moved));
        Json.Obj newToVessels = put(to.vessels() != null ? to.vessels() : emptyPack("spellcrafter.vessels", target),
                "vessels", new Json.Arr(toVessels));
        Refused bad = checkFiles(newTo, newToVessels);
        if (bad != null) return bad;
        List<Write> writes = new ArrayList<>();
        writes.add(new Write(DEFAULT_MOD, "spells.bak", def.spells()));
        writes.add(new Write(DEFAULT_MOD, "spells", newDefault));
        if (newDefVessels != null) writes.add(new Write(DEFAULT_MOD, "vessels", newDefVessels));
        writes.add(new Write(target, "spells", newTo));
        writes.add(new Write(target, "vessels", newToVessels));
        library.put(DEFAULT_MOD, new ModFiles(newDefault, newDefVessels, def.open()));
        library.put(target, new ModFiles(newTo, newToVessels, true));
        if (draft != null && spellId.equals(draft.id())) {
            draft = new Draft(put(draft.spell(), "id", new Json.Str(newId)), draft.template(), draft.vessels(), true);
        }
        return new Ok(draft, readout, writes, JsonWriter.obj("id", new Json.Str(newId)));
    }

    /** Both files must load: the pack, the vessels, and the vessels against the pack's spells. */
    private Refused checkFiles(Json.Obj pack, Json.Obj vessels) {
        Parsed<Formats.Pack> p = Formats.pack(pack, opt);
        if (p instanceof Parsed.Invalid<Formats.Pack> inv) return refuse("invalid", "the spell file wouldn't load", problems(inv.problems()));
        Parsed<Formats.VesselPack> v = Formats.vessels(vessels);
        if (v instanceof Parsed.Invalid<Formats.VesselPack> inv) return refuse("invalid", "the vessels file wouldn't load", problems(inv.problems()));
        Map<String, Spell> byId = new LinkedHashMap<>();
        for (Spell s : ((Parsed.Ok<Formats.Pack>) p).value().spells()) byId.put(s.id(), s);
        List<Problem> vp = Formats.checkVessels(((Parsed.Ok<Formats.VesselPack>) v).value(), byId);
        return vp.isEmpty() ? null : refuse("invalid", "the vessels don't fit the spells", problems(vp));
    }

    // ------------------------------------------------------------------------------------------------ state

    private Outcome change(Json.Obj spell) {
        return commit(new Draft(spell, draft.template(), draft.vessels(), draft.saved()));
    }

    /** Validates, rates and keeps a new draft, recording the old one for undo. */
    private Outcome commit(Draft next) {
        Object r = evaluateDraft(next);
        if (r instanceof Refused ref) return ref;
        Readout ro = (Readout) r;
        if (ro.rated() && !manaOf(next.spell()).equals(ro.mana())) {
            next = new Draft(put(next.spell(), "cost", JsonWriter.obj("mana", new Json.Num(Long.toString(ro.mana())))),
                    next.template(), next.vessels(), next.saved());
        }
        if (draft != null) undo.push(draft);
        redo.clear();
        draft = next;
        readout = ro;
        return new Ok(draft, readout, List.of(), null);
    }

    private Outcome show(Draft d) {
        Object r = evaluateDraft(d);
        draft = d;
        readout = r instanceof Readout ro ? ro : readout;
        return new Ok(draft, readout, List.of(), null);
    }

    /** The draft's readout, or a refusal if the loader or this game would refuse it. */
    private Object evaluateDraft(Draft d) {
        Parsed<Spell> p = Formats.spell(d.spell(), opt);
        if (p instanceof Parsed.Invalid<Spell> inv) {
            return refuse("invalid", "the spell wouldn't load: " + inv.problems().get(0).message(), problems(inv.problems()));
        }
        Spell spell = ((Parsed.Ok<Spell>) p).value();
        List<String> warnings = new ArrayList<>();
        for (Problem w : ((Parsed.Ok<Spell>) p).warnings()) warnings.add(w.code() + ": " + w.message());
        return rate(spell, warnings);
    }

    // ------------------------------------------------------------------------------------------------ the readout

    /** The sentinel the readout's variants put in place of a rider's condition: answered TRUE. */
    private static final Condition.Leaf ACTIVE = new Condition.Leaf("editor.active",
            JsonWriter.obj("type", new Json.Str("editor.active")), "{\"type\":\"editor.active\"}");

    private Object rate(Spell spell, List<String> warnings) {
        boolean onSelf = "self".equals(spell.delivery().kind());
        List<String> all = new ArrayList<>();
        for (Rider r : spell.riders()) all.add(r.id());
        Object bestPlan = plan(spell, all);
        if (bestPlan instanceof Refused ref) return ref;
        Rater.Worth best = Rater.worth((CastResult.CastPlan) bestPlan, catalog, ctx.reference());
        warnings.addAll(best.flags());
        String fold = ctx.profile().unsupported().get("more_pct");
        if (fold != null && !fold.equals("refuse")) {
            for (Rider r : spell.riders()) {
                if (!r.morePct().isEmpty()) {
                    warnings.add("more_pct is folded into " + fold + " on " + ctx.profile().id());
                    break;
                }
            }
        }
        if (spell.delivery().kind().equals("area")) warnings.add("area: expected targets aren't counted yet");
        // A rider changes only params that are there: one naming a param no payload sets does nothing.
        for (Rider r : spell.riders()) {
            Set<String> names = new LinkedHashSet<>(r.plus().keySet());
            names.addAll(r.bonusPct().keySet());
            names.addAll(r.morePct().keySet());
            for (String n : names) {
                boolean found = n.startsWith("delivery.") ? spell.delivery().params().containsKey(n.substring(9)) : false;
                for (var p : spell.payloads()) found |= p.params().containsKey(n);
                if (!found) warnings.add("rider " + r.id() + " changes " + n + ", which nothing sets: set it first");
            }
        }
        if (!best.unrated().isEmpty()) return new Readout(false, best.unrated(), null, null, null, false, warnings);

        // Expected: each world-controlled rider at its frequency (spell-format 13).
        Long expected = best.expected();
        if (expected != null) {
            Rater.Worth base = Rater.worth((CastResult.CastPlan) plan(spell, List.of()), catalog, ctx.reference());
            for (Rider r : spell.riders()) {
                Long p = frequency(r.when());
                if (p == null || base.expected() == null) continue;
                Rater.Worth alone = Rater.worth((CastResult.CastPlan) plan(spell, List.of(r.id())), catalog, ctx.reference());
                if (alone.expected() == null) continue;
                expected -= mul(ONE - p, alone.expected() - base.expected());
            }
            expected = Math.max(0, expected);
        }
        List<Long> gatePs = new ArrayList<>();
        for (Gate g : spell.gates()) {
            Long p = frequency(g.when());
            if (p != null) gatePs.add(p);
        }
        long cd = Rater.fx(spell.cooldown());
        Placement.Placed placed = Placement.place(new Placement.Input(best.value(), gatePs, best.kind(), cd > 0 ? cd : null,
                Rater.fx(spell.castTime())), ctx.reference(), expected);
        boolean optIn = placed.zone().startsWith("above 4");
        if (optIn) warnings.add("zone above 4: " + Readout.OPT_IN);
        // Movement and resources have no damage value per cast: the placement prices them by its own rule (SC-D016).
        if (best.kind() instanceof Placement.Movement || best.kind() instanceof Placement.Resource) {
            return new Readout(true, List.of(), placed.value(), placed.value(), placed, optIn, warnings);
        }
        return new Readout(true, List.of(), best.value() == null ? null : Fixed.rounded(best.value(), 2),
                expected == null ? null : Fixed.rounded(expected, 2), placed, optIn, warnings);
    }

    /** The cast with only {@code active} riders, all of them in force; no gates or components (they don't change numbers). */
    private Object plan(Spell s, List<String> active) {
        List<Rider> riders = new ArrayList<>();
        for (Rider r : s.riders()) {
            if (active.contains(r.id())) {
                riders.add(new Rider(r.id(), ACTIVE, r.plus(), r.bonusPct(), r.morePct(), r.add(), r.onlyTags(), false, null));
            }
        }
        Spell v = new Spell(s.formatVersion(), s.id(), s.name(), s.originKind(), s.originFrom(), s.categories(), s.groups(),
                s.castTime(), s.cooldown(), s.delivery(), List.of(), s.payloads(), List.of(), riders, s.caps(),
                s.provenance(), s.costMana(), s.bindings(), s.uses(), s.picks());
        Map<String, Map<String, CastInput.Bound>> bounds = new LinkedHashMap<>();
        for (var p : s.payloads()) {
            Effect e = p.fields().get("effect") instanceof Json.Str es ? catalog.get(es.value()) : null;
            if (e == null) continue;
            Map<String, CastInput.Bound> per = new LinkedHashMap<>();
            for (var b : e.callerMaySet().entrySet()) per.put(b.getKey(), new CastInput.Bound(b.getValue().min(), b.getValue().max()));
            bounds.put(p.id(), per);
        }
        Facts facts = new Facts.Builder().put(ACTIVE, Tri.TRUE, null).build();
        CastResult r = Evaluator.evaluate(new CastInput(v, List.of(), List.of(), spellcrafter.core.registry.Registries.EMPTY,
                ctx.profile(), ctx.settings(), bounds, facts, null));
        if (r instanceof CastResult.Invalid inv) {
            return refuse("refused_here", ctx.profile().id() + " refuses this spell: " + inv.problems().get(0).message(),
                    problems(inv.problems()));
        }
        if (!(r instanceof CastResult.CastPlan)) return refuse("refused_here", "the spell doesn't cast", null);
        return r;
    }

    private Long frequency(Condition when) {
        return ctx.frequencies().p(when);
    }

    // ------------------------------------------------------------------------------------------------ helpers

    /** The draft's mana before it's rated: one reference cast (12.5, half away from zero). */
    static final long UNRATED_MANA = 13;

    private Template.Slot slot(String id) {
        Template t = draft == null || draft.template() == null ? null : templates.get(draft.template());
        if (t == null) return null;
        for (Template.Slot s : t.slots()) if (s.id().equals(id)) return s;
        return null;
    }

    private List<String> payloadIds() {
        List<String> out = new ArrayList<>();
        for (Json j : arr(draft.spell(), "payloads")) if (j instanceof Json.Obj o) out.add(str(o, "id"));
        return out;
    }

    private static Json.Obj payload(String slot, String effect) {
        return JsonWriter.obj("id", new Json.Str(slot), "provider", new Json.Str(PROVIDER), "op", new Json.Str("apply"),
                "effect", new Json.Str(effect));
    }

    private Refused bounds(String param, Long min, Long max) {
        return refuse("out_of_bounds", param + " must be " + (min != null ? "at least " + Milli.format(min) : "")
                + (min != null && max != null ? " and " : "") + (max != null ? "at most " + Milli.format(max) : ""),
                JsonWriter.obj("min", min == null ? new Json.Null() : milli(min), "max", max == null ? new Json.Null() : milli(max)));
    }

    private static Refused refuse(String code, String message, Json details) {
        return new Refused(code, message, details);
    }

    static Json problems(List<Problem> ps) {
        List<Json> out = new ArrayList<>();
        for (Problem p : ps) {
            out.add(JsonWriter.obj("code", new Json.Str(p.code()), "pointer", new Json.Str(p.pointer()),
                    "message", new Json.Str(p.message())));
        }
        return new Json.Arr(out);
    }

    /** The slug of a name: lower-case letters and digits, other runs become one {@code _}. */
    public static String slug(String name) {
        StringBuilder b = new StringBuilder();
        boolean gap = false;
        for (char ch : name.toLowerCase(java.util.Locale.ROOT).toCharArray()) {
            if ((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')) {
                if (gap && b.length() > 0) b.append('_');
                b.append(ch);
                gap = false;
            } else {
                gap = true;
            }
        }
        return b.length() == 0 ? "spell" : b.toString();
    }

    static String modOf(String id) {
        int i = id.lastIndexOf('/');
        return i < 0 ? id : id.substring(0, i);
    }

    static String overrides(String mod) {
        return "overrides/" + mod;
    }

    private Json.Obj emptyPack(String format, String mod) {
        return JsonWriter.obj("format", new Json.Str(format), "format_version", new Json.Str("1.0"),
                "mod", JsonWriter.obj("id", new Json.Str(mod)), format.endsWith("spells") ? "spells" : "vessels",
                new Json.Arr(List.of()));
    }

    private static Json.Obj findSpell(Json.Obj pack, String id) {
        for (Json j : arr(pack, "spells")) if (j instanceof Json.Obj o && id.equals(str(o, "id"))) return o;
        return null;
    }

    private static List<Json.Obj> vesselsOf(Json.Obj doc, String spellId) {
        List<Json.Obj> out = new ArrayList<>();
        for (Json j : arr(doc, "vessels")) if (j instanceof Json.Obj o && spellId.equals(str(o, "spell"))) out.add(o);
        return out;
    }

    private static Long manaOf(Json.Obj spell) {
        if (spell.get("cost") instanceof Json.Obj c && c.get("mana") instanceof Json.Num n) {
            Milli.Parsed m = Milli.parseWhole(n.text());
            if (m.ok()) return m.value();
        }
        return -1L;
    }

    private static String deliveryKind(Json.Obj spell) {
        return spell.get("delivery") instanceof Json.Obj d ? str(d, "kind") : null;
    }

    private static int indexOf(List<Json> items, String id) {
        for (int i = 0; i < items.size(); i++) if (items.get(i) instanceof Json.Obj o && id.equals(str(o, "id"))) return i;
        return -1;
    }

    private static String singular(String key) {
        return key.equals("riders") ? "rider" : key.equals("requires") ? "requirement" : "component";
    }

    static Json milli(long v) {
        return new Json.Num(Milli.format(v));
    }

    static String str(Json.Obj o, String key) {
        return o != null && o.get(key) instanceof Json.Str s ? s.value() : null;
    }

    private static Json.Obj obj(Json.Obj o, String key) {
        return o.get(key) instanceof Json.Obj x ? x : null;
    }

    static List<Json> arr(Json.Obj o, String key) {
        return o != null && o.get(key) instanceof Json.Arr a ? a.items() : List.of();
    }

    static Json.Obj put(Json.Obj o, String key, Json v) {
        Map<String, Json> m = new LinkedHashMap<>(o.members());
        m.put(key, v);
        return new Json.Obj(m);
    }

    static Json.Obj without(Json.Obj o, String key) {
        Map<String, Json> m = new LinkedHashMap<>(o.members());
        m.remove(key);
        return new Json.Obj(m);
    }
}
