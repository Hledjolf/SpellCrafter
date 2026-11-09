package spellcrafter.core.window;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import spellcrafter.core.editor.Editor;
import spellcrafter.core.editor.Effect;
import spellcrafter.core.editor.Readout;
import spellcrafter.core.editor.RecipeRules;
import spellcrafter.core.editor.StatBlock;
import spellcrafter.core.eval.CastResult;
import spellcrafter.core.eval.CastInput;
import spellcrafter.core.eval.Evaluator;
import spellcrafter.core.eval.Facts;
import spellcrafter.core.eval.Resolve;
import spellcrafter.core.format.Formats;
import spellcrafter.core.format.Parsed;
import spellcrafter.core.json.Json;
import spellcrafter.core.json.JsonWriter;
import spellcrafter.core.model.Model.Spell;
import spellcrafter.core.model.Model.Targeting;
import spellcrafter.core.num.Milli;
import spellcrafter.core.registry.Registries;
import spellcrafter.core.window.ViewOps.Child;
import spellcrafter.core.window.ViewOps.Entry;
import spellcrafter.core.window.ViewOps.Part;
import spellcrafter.core.window.ViewOps.Prop;
import spellcrafter.core.window.ViewOps.Ref;
import spellcrafter.core.window.ViewOps.RowKind;
import spellcrafter.core.window.ViewOps.Trigger;

/**
 * The spell editor window (design/window.md), game-neutral: state, layout and events, drawn by each game's renderer
 * from {@link ViewOps} (the same contract as BuffEffects' window). Built on {@link Editor}, which holds the draft, the
 * undo history and the readout; this class lays them out and turns the page's events into editor commands.
 *
 * <p>Tabs: <b>Spells</b> (every loaded spell by mod, its stat block, open or start one), <b>Editor</b> (the draft:
 * the stat block and readout, the spell-wide strip, then the controls generated from the selected effect), and
 * <b>Try</b> (roll it, a scroll of the draft, a cast on a training dummy). The effects of the draft are the window's
 * "tabs" of window.md section 1: rows in the list on the left, with "+ Add effect" at the end.
 *
 * <p>The core does no I/O: saving leaves {@link #takeWrites()} for the adapter, and Try leaves {@link #takeRequests()}.
 */
public final class WindowModel {
    public enum Tab { SPELLS, EDITOR, TRY }

    /** What the window asks the game for, after an event. */
    public sealed interface Request permits GiveScroll, CastOnDummy {
    }

    /** Give the viewer a scroll of this (saved) spell. */
    public record GiveScroll(String spellId) implements Request {
    }

    /** Cast this spell on a training dummy in front of the viewer (window.md 4, "Try it"; probe B5). */
    public record CastOnDummy(String spellId) implements Request {
    }

    /** Shown, disabled, with this tooltip text (window.md section 3, "Greyed until a later release"). */
    public static final String UNAVAILABLE = "Unavailable in 1.0";
    static final List<String> GREYED_EFFECT = List.of("Contingent on: a condition or a circumstance",
            "Triggers: on hit, on expiry, after a delay",
            "Chain / bounce: count and falloff", "Several projectiles, split across targets",
            "Linked amount: heal for a share of the damage dealt", "Summon a creature", "Modes chosen at cast time");
    static final List<String> GREYED_SPELL = List.of("Delivery shapes: cone, line, wall, a persistent place",
            "Channelling: cost per second while held");
    /** Riders and requirements offered as ready-made sentences (the condition row's 1.0 set). */
    static final Map<String, String> RIDER_PRESETS = presets();
    static final List<String> TEMPLATES = List.of("bolt", "hex", "touch", "blessing");

    private final Editor.Context ctx;
    private final Editor editor;
    private final Map<String, Effect> catalog = new LinkedHashMap<>();
    private final Map<String, String> lang;
    private final RecipeRules rules;

    private Tab tab = Tab.SPELLS;
    private String search = "";
    private String selectedSpell;
    private String selectedEffect;
    /** The zone "Aim for zone" aims at: start, 1, 2-3 or 4. */
    private String aimZone = "1";
    /** Null, or what the effect picker is choosing for: "add", or a payload id. */
    private String picking;
    private String status = "";
    private Readout lastReadout;
    private String delta = "";
    private List<String> rolls = List.of();
    private long rollSeed = 12345;
    private final List<Editor.Write> writes = new ArrayList<>();
    private final List<Request> requests = new ArrayList<>();

    public WindowModel(Editor.Context ctx, Map<String, Editor.ModFiles> library, Map<String, String> statBlockLang) {
        this.ctx = ctx;
        this.editor = new Editor(ctx, library);
        for (Effect e : ctx.catalog()) catalog.put(e.id(), e);
        this.lang = statBlockLang;
        this.rules = ctx.recipes();
    }

    public Tab tab() {
        return tab;
    }

    public String status() {
        return status;
    }

    public Editor editor() {
        return editor;
    }

    /** Files to write after the last event (saves and moves), in order; cleared by this call. */
    public List<Editor.Write> takeWrites() {
        List<Editor.Write> out = List.copyOf(writes);
        writes.clear();
        return out;
    }

    /** What the game should do after the last event (Try it); cleared by this call. */
    public List<Request> takeRequests() {
        List<Request> out = List.copyOf(requests);
        requests.clear();
        return out;
    }

    /** Actions whose redraw waits until typing pauses. */
    public static boolean deferred(String action) {
        // A text field sends an event per keystroke. Redrawing its row at once rebuilt the box being typed in, so it
        // lost focus after a letter or two, and "1." (on the way to "1.2") was put back to "1" (the author's playtest,
        // 2026-11-08). As in BuffEffects: typing redraws only once it pauses, and only what changed.
        return "search".equals(action) || "field".equals(action);
    }

    /** The right pane's rows as last drawn: their kinds, labels and choices. A typed change that alters it redraws them. */
    private String shownShape = "";

    private static String shape(ViewOps v) {
        StringBuilder sb = new StringBuilder();
        List<RowKind> kinds = v.appended(Part.ROWS);
        for (int i = 0; i < kinds.size(); i++) {
            sb.append(kinds.get(i)).append('|').append(v.valueOf(Ref.row(i, Child.LABEL), Prop.TEXT)).append(';');
        }
        for (ViewOps.Op op : v.ops()) {
            if (op instanceof ViewOps.Entries e && e.ref().part() == Part.ROWS) sb.append(e.ref().index()).append(e.entries()).append(';');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------------------------------ drawing

    /** The whole window. */
    public ViewOps open() {
        return redraw();
    }

    private ViewOps redraw() {
        ViewOps v = new ViewOps();
        for (Tab t : Tab.values()) v.disabled(new Ref(Part.TAB, t.ordinal(), Child.NONE), t == tab);
        for (Tab t : Tab.values()) {
            v.bind(new Ref(Part.TAB, t.ordinal(), Child.NONE), Trigger.ACTIVATE, "tab", Map.of("tab", t.name()), Map.of());
        }
        v.bind(Ref.of(Part.SEARCH), Trigger.CHANGE, "search", Map.of(), Map.of("value", Ref.of(Part.SEARCH)));
        v.visible(Ref.of(Part.FILTER), false);
        v.clear(Part.LIST).clear(Part.ROWS).clear(Part.FOOTER);
        v.text(Ref.of(Part.PREVIEW), "").text(Ref.of(Part.PREVIEW_NOTE), "").text(Ref.of(Part.META), "");
        switch (tab) {
            case SPELLS -> drawSpells(v);
            case EDITOR -> drawEditor(v);
            case TRY -> drawTry(v);
        }
        v.text(Ref.of(Part.STATUS), status);
        shownShape = shape(v);
        return v;
    }

    /** The list's redraw after typing in the search box. */
    public ViewOps deferredOps(String action) {
        if ("field".equals(action)) {
            // The rows stay as typed unless the change altered them (a pick group adds Weight; a burst radius adds
            // "everyone in the burst"). Otherwise only the stat block, readout, heading and status are redrawn.
            String shown = shownShape;
            ViewOps full = redraw(); // records itself as shown: right if it's sent, put back below if not
            if (!shownShape.equals(shown)) return full;
            shownShape = shown;
            ViewOps v = new ViewOps();
            for (ViewOps.Op op : full.ops()) {
                // Window texts, and the notes' text (a part's "15 fire damage" follows the strength). Never a field's
                // value: that would reset the box being typed in.
                if (op instanceof ViewOps.Set s && s.ref().child() == Child.NONE && s.prop() == Prop.TEXT
                        && (s.ref().part() == Part.ROWS || s.ref().part() == Part.PREVIEW || s.ref().part() == Part.PREVIEW_NOTE
                            || s.ref().part() == Part.HEADING || s.ref().part() == Part.STATUS)) {
                    v.set(s.ref(), s.prop(), s.value());
                }
            }
            return v;
        }
        ViewOps v = new ViewOps();
        v.clear(Part.LIST);
        if (tab == Tab.SPELLS) drawSpellList(v);
        else if (tab == Tab.EDITOR && picking != null) drawPicker(v);
        else if (tab == Tab.EDITOR) drawEffectTabs(v);
        return v;
    }

    // ---- Spells

    private void drawSpells(ViewOps v) {
        drawSpellList(v);
        Spell s = selectedSpell == null ? null : librarySpell(selectedSpell);
        if (s == null) {
            v.text(Ref.of(Part.HEADING), "Spells");
            v.text(Ref.of(Part.META), "Pick a spell on the left, or start a new one from a template.");
        } else {
            v.text(Ref.of(Part.HEADING), s.name());
            v.text(Ref.of(Part.META), s.id());
            v.text(Ref.of(Part.PREVIEW), String.join("\n", StatBlock.lines(s, catalog, null, school(s), lang)));
        }
        int f = 0;
        v.append(Part.FOOTER, RowKind.FOOTER_ROW);
        Child[] bs = {Child.B1, Child.B2, Child.B3, Child.B4, Child.B5};
        if (s != null) {
            v.text(Ref.footer(bs[f]), "Open in editor");
            v.bind(Ref.footer(bs[f++]), Trigger.ACTIVATE, "open", Map.of("id", s.id()), Map.of());
        }
        for (String t : TEMPLATES) {
            v.text(Ref.footer(bs[f]), "New " + capital(t));
            v.bind(Ref.footer(bs[f++]), Trigger.ACTIVATE, "new", Map.of("template", "template:" + t), Map.of());
        }
        for (; f < 6; f++) if (f < bs.length) v.visible(Ref.footer(bs[f]), false);
        v.visible(Ref.footer(Child.B6), false);
    }

    private void drawSpellList(ViewOps v) {
        int row = 0;
        String q = search.trim().toLowerCase(java.util.Locale.ROOT);
        for (Map.Entry<String, Editor.ModFiles> m : editor.library().entrySet()) {
            List<Spell> spells = spellsOf(m.getValue());
            boolean heading = false;
            for (Spell s : spells) {
                if (!q.isEmpty() && !s.name().toLowerCase(java.util.Locale.ROOT).contains(q)) continue;
                if (!heading) {
                    v.append(Part.LIST, RowKind.LIST_HEADING);
                    v.text(Ref.list(row++, Child.NONE), m.getKey());
                    heading = true;
                }
                v.append(Part.LIST, RowKind.LIST_ROW);
                v.text(Ref.list(row, Child.BUTTON), (s.id().equals(selectedSpell) ? "> " : "") + s.name());
                v.bind(Ref.list(row++, Child.BUTTON), Trigger.ACTIVATE, "select", Map.of("id", s.id()), Map.of());
            }
        }
        v.text(Ref.of(Part.COUNT), row == 0 ? "No spells" : "");
    }

    // ---- Editor

    private void drawEditor(ViewOps v) {
        Editor.Draft d = editor.draft();
        if (d == null) {
            v.text(Ref.of(Part.HEADING), "No spell open");
            v.text(Ref.of(Part.META), "Open a spell, or start one from a template, on the Spells tab.");
            return;
        }
        Spell s = parse(d.spell());
        if (picking != null) drawPicker(v);
        else drawEffectTabs(v);
        v.text(Ref.of(Part.HEADING), str(d.spell(), "name") + (d.saved() ? "" : " *"));
        v.text(Ref.of(Part.META), d.id() + (d.template() == null ? "" : " · from " + d.template()));
        if (s != null) {
            List<String> block = StatBlock.lines(s, catalog, null, school(s), lang);
            v.text(Ref.of(Part.PREVIEW), String.join("\n", block));
        }
        v.text(Ref.of(Part.PREVIEW_NOTE), readoutText(editor.readout()));

        int r = 0;
        r = section(v, r, "Spell");
        r = text(v, r, "Name", str(d.spell(), "name"), "", "name");
        Json.Obj cast = (Json.Obj) d.spell().get("cast");
        r = text(v, r, "Cast time (s)", num(cast.get("time")), "", "cast.time");
        r = text(v, r, "Cooldown (s)", num(cast.get("cooldown")), "", "cast.cooldown");
        Json.Obj del = (Json.Obj) d.spell().get("delivery");
        String kind = str(del, "kind");
        Json.Obj dps = del.get("params") instanceof Json.Obj o ? o : JsonWriter.obj();
        if (dps.has("range")) r = text(v, r, "Range (blocks)", num(dps.get("range")), "", "delivery.range");
        if (!"area".equals(kind)) {
            r = text(v, r, "Burst radius (blocks)", dps.has("burst.radius") ? num(dps.get("burst.radius")) : "",
                    "empty: no burst", "burst.radius");
            if (del.get("burst") instanceof Json.Obj b) {
                r = choice(v, r, "Burst affects", str(b, "affects"), List.of(new Entry("everyone but you", "others"),
                        new Entry("everyone, you too", "all"), new Entry("enemies only", "enemies")), "burst.affects");
            }
        }
        for (String k : List.of("wand", "scroll", "potion")) {
            r = check(v, r, capital(k), d.vessels().contains(k), "vessel." + k, k.equals("potion") && !"self".equals(kind));
        }
        r = riders(v, r, d.spell());
        for (String g : GREYED_SPELL) r = greyed(v, r, g);

        // The selected effect's controls, generated from its parts and bounds (window.md section 3).
        Json.Obj p = payload(d.spell(), selectedEffect);
        if (p != null) r = effectControls(v, r, p, kind, del.get("burst") != null);

        v.append(Part.FOOTER, RowKind.FOOTER_ROW);
        footer(v, Child.B1, "Save", "save");
        footer(v, Child.B2, "Undo", "undo");
        footer(v, Child.B3, "Redo", "redo");
        footer(v, Child.B4, "Duplicate effect", "duplicate");
        footer(v, Child.B5, "Remove effect", "remove");
        footer(v, Child.B6, "Aim for zone " + aimZone, "aim");
    }

    private void drawEffectTabs(ViewOps v) {
        Editor.Draft d = editor.draft();
        if (d == null) return;
        int row = 0;
        v.append(Part.LIST, RowKind.LIST_HEADING);
        v.text(Ref.list(row++, Child.NONE), "Effects");
        for (Json j : arr(d.spell(), "payloads")) {
            Json.Obj p = (Json.Obj) j;
            String id = str(p, "id");
            String label = p.has("reroll") ? "Roll " + num(p.get("reroll")) + " more" : effectName(str(p, "effect"));
            if (str(p, "pick") != null) label += " (" + str(p, "pick") + ")";
            v.append(Part.LIST, RowKind.LIST_ROW);
            v.text(Ref.list(row, Child.BUTTON), (id.equals(selectedEffect) ? "> " : "") + label);
            v.bind(Ref.list(row++, Child.BUTTON), Trigger.ACTIVATE, "effect", Map.of("id", id), Map.of());
        }
        v.append(Part.LIST, RowKind.LIST_ROW);
        v.text(Ref.list(row, Child.BUTTON), "+ Add effect");
        v.bind(Ref.list(row++, Child.BUTTON), Trigger.ACTIVATE, "pickEffect", Map.of("for", "add"), Map.of());
        v.text(Ref.of(Part.COUNT), "");
    }

    /** The effect picker: BuffEffects' catalog, grouped by mod, narrowed by the search box. */
    private void drawPicker(ViewOps v) {
        String q = search.trim().toLowerCase(java.util.Locale.ROOT);
        Map<String, List<Effect>> byMod = new LinkedHashMap<>();
        for (Effect e : catalog.values()) {
            if (!q.isEmpty() && !e.name().toLowerCase(java.util.Locale.ROOT).contains(q)
                    && !e.id().toLowerCase(java.util.Locale.ROOT).contains(q)) continue;
            byMod.computeIfAbsent(modOf(e.id()), k -> new ArrayList<>()).add(e);
        }
        int row = 0;
        v.append(Part.LIST, RowKind.LIST_ROW);
        v.text(Ref.list(row, Child.BUTTON), "Cancel");
        v.bind(Ref.list(row++, Child.BUTTON), Trigger.ACTIVATE, "pickCancel", Map.of(), Map.of());
        int shown = 0;
        for (Map.Entry<String, List<Effect>> m : byMod.entrySet()) {
            v.append(Part.LIST, RowKind.LIST_HEADING);
            v.text(Ref.list(row++, Child.NONE), m.getKey());
            for (Effect e : m.getValue()) {
                if (shown++ >= 120) break;
                v.append(Part.LIST, RowKind.LIST_ROW);
                v.text(Ref.list(row, Child.BUTTON), effectName(e.id()) + (e.described() ? "" : " (no parts)"));
                v.bind(Ref.list(row++, Child.BUTTON), Trigger.ACTIVATE, "picked", Map.of("value", e.id()), Map.of());
            }
        }
        v.text(Ref.of(Part.COUNT), shown + " effects");
    }

    private int effectControls(ViewOps v, int r, Json.Obj p, String kind, boolean burst) {
        String id = str(p, "id");
        if (p.has("reroll")) {
            r = section(v, r, "Reroll option in " + str(p, "pick"));
            return note(v, r, "Rolls " + num(p.get("reroll")) + " more times in its group, leaving itself out.");
        }
        Effect e = catalog.get(str(p, "effect"));
        r = section(v, r, "Effect: " + effectName(str(p, "effect")));
        r = pickRow(v, r, "Effect", effectName(str(p, "effect")), id);
        Json.Obj params = p.get("params") instanceof Json.Obj o ? o : JsonWriter.obj();
        if (e != null) {
            for (Map.Entry<String, Effect.Bound> b : e.callerMaySet().entrySet()) {
                String hint = "(" + (b.getValue().min() == null ? "" : Milli.format(b.getValue().min())) + " – "
                        + (b.getValue().max() == null ? "" : Milli.format(b.getValue().max())) + ")";
                String cur = params.has(b.getKey()) ? num(params.get(b.getKey())) : "";
                r = text(v, r, capital(b.getKey()), cur, hint, id + "." + b.getKey());
            }
            if (e.callerMaySet().containsKey("strength")) {
                r = choice(v, r, "Aim for zone", aimZone, List.of(new Entry("start", "start"), new Entry("1", "1"),
                        new Entry("2-3", "2-3"), new Entry("4", "4")), "aim.zone");
            }
            if (e.described()) {
                long s = params.get("strength") instanceof Json.Num n ? Effect.milliOf(n) : 1000;
                for (String line : StatBlock.partLines(e, s, params.get("duration") instanceof Json.Num dn
                        ? Effect.milliOf(dn) : e.durationMs(), lang)) {
                    r = note(v, r, line);
                }
            } else {
                r = note(v, r, "BuffEffects lists this effect without its parts, so it can't be rated.");
            }
        }
        List<Entry> hits = new ArrayList<>(List.of(new Entry("the target", "target"), new Entry("you", "caster")));
        if (burst) hits.add(new Entry("everyone in the burst", "area"));
        r = choice(v, r, "Hits", Targeting.target(p, kind), hits, id + ".target");
        r = text(v, r, "Chance (%)", p.has("chance") ? num(p.get("chance")) : "100", "1 – 100", id + ".chance");
        r = text(v, r, "Pick group", str(p, "pick") == null ? "" : str(p, "pick"), "empty: always applies", id + ".pick");
        if (str(p, "pick") != null) r = text(v, r, "Weight", p.has("weight") ? num(p.get("weight")) : "1", "from 1", id + ".weight");
        for (String g : GREYED_EFFECT) r = greyed(v, r, g);
        return r;
    }

    private int riders(ViewOps v, int r, Json.Obj spell) {
        r = section(v, r, "Riders and requirements");
        for (String key : List.of("riders", "requires")) {
            for (Json j : arr(spell, key)) {
                Json.Obj o = (Json.Obj) j;
                v.append(Part.ROWS, RowKind.PART_ROW);
                v.text(Ref.row(r, Child.LABEL), (key.equals("requires") ? "Only: " : "Rider: ") + describeRule(o));
                v.visible(Ref.row(r, Child.UP), false).visible(Ref.row(r, Child.DOWN), false);
                v.bind(Ref.row(r++, Child.DEL), Trigger.ACTIVATE, "ruleDel",
                        Map.of("kind", key, "id", str(o, "id")), Map.of());
            }
        }
        v.append(Part.ROWS, RowKind.ADD_ROW);
        v.text(Ref.row(r, Child.LABEL), "Add");
        List<Entry> es = new ArrayList<>();
        for (String k : RIDER_PRESETS.keySet()) es.add(new Entry(k, k));
        v.entries(Ref.row(r, Child.INPUT), es);
        v.value(Ref.row(r, Child.INPUT), es.get(0).value());
        v.bind(Ref.row(r, Child.BUTTON), Trigger.ACTIVATE, "ruleAdd", Map.of(), Map.of("value", Ref.row(r, Child.INPUT)));
        return r + 1;
    }

    // ---- Try

    private void drawTry(ViewOps v) {
        Editor.Draft d = editor.draft();
        v.text(Ref.of(Part.HEADING), d == null ? "Nothing to try" : "Try " + str(d.spell(), "name"));
        v.text(Ref.of(Part.META), d == null ? "Open a spell first." : "Roll it, or try it in the world. A scroll or a "
                + "cast needs the spell saved.");
        int r = 0;
        if (d != null) {
            r = section(v, r, "Roll it: ten creatures in the burst, seed " + rollSeed);
            for (String line : rolls) r = note(v, r, line);
            Spell s = parse(d.spell());
            if (s != null) v.text(Ref.of(Part.PREVIEW), String.join("\n", StatBlock.lines(s, catalog, null, school(s), lang)));
            v.text(Ref.of(Part.PREVIEW_NOTE), readoutText(editor.readout()));
        }
        v.append(Part.FOOTER, RowKind.FOOTER_ROW);
        footer(v, Child.B1, "Roll for 10", "roll");
        footer(v, Child.B2, "New seed", "reseed");
        footer(v, Child.B3, "Give me a scroll", "give");
        footer(v, Child.B4, "Cast on a dummy", "dummy");
        v.visible(Ref.footer(Child.B5), false).visible(Ref.footer(Child.B6), false);
        boolean saved = d != null && d.saved();
        v.disabled(Ref.footer(Child.B3), !saved).disabled(Ref.footer(Child.B4), !saved);
    }

    // ------------------------------------------------------------------------------------------ events

    /** Handles one event: its action and data (params and captured values by name). Returns what to redraw. */
    public ViewOps onEvent(String action, Map<String, Object> data) {
        if (action == null) return new ViewOps();
        status = "";
        switch (action) {
            case "tab" -> {
                tab = Tab.valueOf(s(data.get("tab")));
                picking = null;
                search = "";
            }
            case "search" -> {
                search = s(data.get("value"));
                return new ViewOps();
            }
            case "select" -> selectedSpell = s(data.get("id"));
            case "open" -> {
                if (ok(cmd("open", "spell", new Json.Str(s(data.get("id")))))) {
                    tab = Tab.EDITOR;
                    selectFirstEffect();
                }
            }
            case "new" -> {
                if (ok(cmd("new", "template", new Json.Str(s(data.get("template")))))) {
                    tab = Tab.EDITOR;
                    selectFirstEffect();
                }
            }
            case "effect" -> {
                selectedEffect = s(data.get("id"));
                picking = null;
            }
            case "pickEffect" -> {
                picking = s(data.get("for"));
                search = "";
                // Say whether the pick adds or replaces: a Pick pressed for "+ Add effect" replaced the main effect.
                Json.Obj slot = "add".equals(picking) || editor.draft() == null ? null : payload(editor.draft().spell(), picking);
                status = slot == null || str(slot, "effect") == null
                        ? "Pick an effect on the left to add it (search narrows the list)."
                        : "Pick an effect on the left to replace " + effectName(str(slot, "effect"))
                                + " (to add one instead, Cancel and press \"+ Add effect\").";
            }
            case "pickCancel" -> picking = null;
            case "picked" -> picked(s(data.get("value")));
            // "choose" is a dropdown or a checkbox: it redraws at once. "field" is typing: see deferred().
            case "field", "choose" -> field(s(data.get("name")), s(data.get("value")));
            case "ruleAdd" -> {
                String json = RIDER_PRESETS.get(s(data.get("value")));
                if (json != null) {
                    Json.Obj rule = (Json.Obj) ((Parsed.Ok<Json>) Formats.json(json)).value();
                    boolean gate = !rule.has("bonus_pct");
                    String id = freeRuleId(str(rule, "id"));
                    rule = put(rule, "id", new Json.Str(id));
                    ok(gate ? cmd("add_requirement", "gate", rule) : cmd("add_rider", "rider", rule));
                }
            }
            case "ruleDel" -> ok(cmd(s(data.get("kind")).equals("requires") ? "remove_requirement" : "remove_rider", "id",
                    new Json.Str(s(data.get("id")))));
            case "button" -> button(s(data.get("name")));
            default -> {
                return new ViewOps();
            }
        }
        // A deferred event's full redraw is returned for callers that want it, but the page doesn't draw it, so it
        // mustn't count as shown (deferredOps compares against what the player really sees).
        String shown = shownShape;
        ViewOps v = redraw();
        if (deferred(action)) shownShape = shown;
        return v;
    }

    private void picked(String effect) {
        String target = picking;
        picking = null;
        if ("add".equals(target)) {
            if (ok(cmd("add_effect", "effect", new Json.Str(effect)))) {
                List<Json> ps = arr(editor.draft().spell(), "payloads");
                selectedEffect = str((Json.Obj) ps.get(ps.size() - 1), "id");
            }
        } else if (target != null) {
            ok(JsonWriter.obj("cmd", new Json.Str("choose_effect"), "slot", new Json.Str(target), "effect", new Json.Str(effect)));
        }
    }

    private void field(String name, String value) {
        if (name == null || editor.draft() == null) return;
        String v = value == null ? "" : value.trim();
        if (name.equals("aim.zone")) {
            if (List.of("start", "1", "2-3", "4").contains(v)) aimZone = v;
        } else if (name.equals("name")) {
            ok(cmd("rename", "name", new Json.Str(v)));
        } else if (name.equals("burst.radius")) {
            Json n = number(v);
            if (v.isEmpty()) ok(JsonWriter.obj("cmd", new Json.Str("set_burst")));
            else if (n != null) ok(JsonWriter.obj("cmd", new Json.Str("set_burst"), "radius", n, "affects", new Json.Str(affects())));
            else status = "Burst radius: a number of blocks.";
        } else if (name.equals("burst.affects")) {
            Json.Obj del = (Json.Obj) editor.draft().spell().get("delivery");
            Json.Obj ps = (Json.Obj) del.get("params");
            ok(JsonWriter.obj("cmd", new Json.Str("set_burst"), "radius", ps.get("burst.radius"), "affects", new Json.Str(v)));
        } else if (name.startsWith("vessel.")) {
            List<String> kinds = new ArrayList<>(editor.draft().vessels());
            String k = name.substring(7);
            if ("true".equals(v)) {
                if (!kinds.contains(k)) kinds.add(k);
            } else {
                kinds.remove(k);
            }
            List<Json> js = new ArrayList<>();
            for (String x : kinds) js.add(new Json.Str(x));
            ok(JsonWriter.obj("cmd", new Json.Str("set_vessels"), "kinds", new Json.Arr(js)));
        } else if (name.endsWith(".target") || name.endsWith(".chance") || name.endsWith(".pick") || name.endsWith(".weight")) {
            int dot = name.lastIndexOf('.');
            String id = name.substring(0, dot), f = name.substring(dot + 1);
            Json val;
            if (f.equals("target") || f.equals("pick")) val = new Json.Str(v);
            else if (v.isEmpty() || (f.equals("chance") && v.equals("100"))) val = new Json.Null();
            else {
                val = whole(v);
                if (val == null) {
                    status = capital(f) + ": a whole number.";
                    return;
                }
            }
            ok(JsonWriter.obj("cmd", new Json.Str("set_payload"), "id", new Json.Str(id), "field", new Json.Str(f), "value", val));
        } else {
            Json n = number(v);
            if (n == null) {
                status = name + ": a number, to at most 3 decimals.";
                return;
            }
            ok(JsonWriter.obj("cmd", new Json.Str("set"), "param", new Json.Str(name), "value", n));
        }
    }

    private void button(String name) {
        switch (name) {
            case "save" -> {
                Editor.Outcome o = editor.run(JsonWriter.obj("cmd", new Json.Str("save")));
                if (o instanceof Editor.Refused r && r.code().equals("confirm_replace")) {
                    o = editor.run(JsonWriter.obj("cmd", new Json.Str("save"), "confirm", new Json.Bool(true)));
                }
                if (o instanceof Editor.Ok ok) {
                    writes.addAll(ok.writes());
                    status = "Saved " + ok.draft().id() + " to the default pack.";
                } else {
                    status = ((Editor.Refused) o).message();
                }
            }
            case "undo" -> ok(cmd("undo"));
            case "redo" -> ok(cmd("redo"));
            case "duplicate" -> {
                if (selectedEffect != null && ok(cmd("duplicate_effect", "id", new Json.Str(selectedEffect)))) {
                    List<Json> ps = arr(editor.draft().spell(), "payloads");
                    selectedEffect = str((Json.Obj) ps.get(ps.size() - 1), "id");
                }
            }
            case "remove" -> {
                if (selectedEffect != null && ok(cmd("remove_effect", "id", new Json.Str(selectedEffect)))) selectFirstEffect();
            }
            case "aim" -> aim();
            case "roll" -> roll();
            case "reseed" -> {
                rollSeed = (rollSeed * 1103515245L + 12345L) & 0x7FFFFFFFL;
                if (rollSeed == 0) rollSeed = 1;
                roll();
            }
            case "give" -> {
                if (editor.draft() != null && editor.draft().saved()) {
                    requests.add(new GiveScroll(editor.draft().id()));
                    status = "A scroll of " + editor.draft().id() + " is on its way.";
                }
            }
            case "dummy" -> {
                if (editor.draft() != null && editor.draft().saved()) {
                    requests.add(new CastOnDummy(editor.draft().id()));
                    status = "Casting on a training dummy in front of you.";
                }
            }
            default -> {
            }
        }
    }

    /**
     * "Aim for a zone" (window.md section 4; every zone in 1.0, SC-D027), for the selected effect's strength: a sweep of
     * its bounds on the readout's zone, to the middle of the strengths that land in the chosen zone.
     */
    private void aim() {
        Json.Obj p = payload(editor.draft() == null ? null : editor.draft().spell(), selectedEffect);
        Effect e = p == null ? null : catalog.get(str(p, "effect"));
        Effect.Bound b = e == null ? null : e.callerMaySet().get("strength");
        if (b == null || b.min() == null || b.max() == null) {
            status = "This effect's strength can't be set, so there's nothing to aim.";
            return;
        }
        // The zone isn't monotonic in strength (the placement method changes with the casts in a fight), so take the
        // longest unbroken run of sampled strengths that land in the zone, and its middle sample.
        long step = Math.max(10, (b.max() - b.min()) / 40);
        long lo = -1, hi = -1, runLo = -1;
        for (long s = b.min(); s <= b.max(); s += step) {
            Editor.Outcome o = editor.run(set(selectedEffect + ".strength", s));
            boolean in = o instanceof Editor.Ok ok && ok.readout().rated() && aimZone.equals(ok.readout().placement().zone());
            if (o instanceof Editor.Ok) editor.run(cmd("undo"));
            if (in) {
                if (runLo < 0) runLo = s;
                if (lo < 0 || s - runLo > hi - lo) {
                    lo = runLo;
                    hi = s;
                }
            } else {
                runLo = -1;
            }
        }
        if (lo < 0) {
            status = "No strength in its bounds lands this spell in zone " + aimZone + ".";
            return;
        }
        long mid = lo + (hi - lo) / step / 2 * step;
        ok(set(selectedEffect + ".strength", mid));
        status = "Zone " + aimZone + " holds strengths " + Milli.format(lo) + " to " + Milli.format(hi) + ": set to " + Milli.format(mid) + ".";
    }

    /** "Roll it": the draft's chances and picks for ten creatures in its burst, from the seed. */
    private void roll() {
        Editor.Draft d = editor.draft();
        Spell s = d == null ? null : parse(d.spell());
        if (s == null) return;
        CastResult r = Evaluator.evaluate(new CastInput(s, List.of(), List.of(), Registries.EMPTY, ctx.profile(),
                ctx.settings(), Map.of(), Facts.NONE, null));
        if (!(r instanceof CastResult.CastPlan plan)) {
            rolls = List.of("It doesn't cast with no facts known (a requirement is unknown).");
            return;
        }
        List<Resolve.Caught> caught = new ArrayList<>();
        for (int i = 0; i < 10; i++) caught.add(new Resolve.Caught("creature " + (i + 1), 500L * (i + 1)));
        Resolve.Resolution res = Resolve.resolve(plan, "you", "creature 1", caught, rollSeed, ctx.settings().caps());
        Map<String, List<String>> got = new LinkedHashMap<>();
        for (String e : res.entities()) got.put(e, new ArrayList<>());
        got.put("you", new ArrayList<>());
        for (Resolve.Application a : res.applications()) {
            got.computeIfAbsent(a.entity(), k -> new ArrayList<>()).add(effectName(str(a.payload().fields(), "effect")));
        }
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, List<String>> g : got.entrySet()) {
            if (g.getKey().equals("you") && g.getValue().isEmpty()) continue;
            out.add(capital(g.getKey()) + ": " + (g.getValue().isEmpty() ? "nothing" : String.join(", ", g.getValue())));
        }
        rolls = out;
    }

    // ------------------------------------------------------------------------------------------ helpers

    private boolean ok(Json.Obj command) {
        Editor.Outcome o = editor.run(command);
        if (o instanceof Editor.Refused r) {
            status = r.message();
            return false;
        }
        Editor.Ok ok = (Editor.Ok) o;
        writes.addAll(ok.writes());
        trackDelta(ok.readout());
        if (selectedEffect != null && payload(ok.draft() == null ? null : ok.draft().spell(), selectedEffect) == null) {
            selectFirstEffect();
        }
        return true;
    }

    private void trackDelta(Readout now) {
        Readout was = lastReadout;
        lastReadout = now;
        if (was == null || now == null || !was.rated() || !now.rated()) {
            delta = "";
            return;
        }
        long g = now.placement().grade() != null && was.placement().grade() != null
                ? now.placement().grade() - was.placement().grade() : 0;
        long m = (now.mana() == null ? 0 : now.mana()) - (was.mana() == null ? 0 : was.mana());
        // Worded as a change, so "-10 mana" isn't read as the mana (the author's fourth pass, 2026-11-08).
        delta = "Your last edit changed the grade by " + signed(spellcrafter.core.power.Fixed.round(g, 2) * 10)
                + " and the mana by " + (m >= 0 ? "+" : "-") + Math.abs(m);
    }

    private String readoutText(Readout r) {
        if (r == null) return "";
        List<String> out = new ArrayList<>();
        if (!r.rated()) {
            out.addAll(r.unrated());
        } else {
            Json.Obj j = (Json.Obj) r.toJson();
            out.add("Grade " + text(j.get("grade")) + " · fight grade " + text(j.get("fight_grade")) + " · zone "
                    + text(j.get("zone")) + " · " + text(j.get("mana")) + " mana");
            out.add("Best case " + text(j.get("value_best")) + " · expected " + text(j.get("value_expected")));
            if (!delta.isEmpty()) out.add(delta);
        }
        for (String w : r.warnings()) out.add("Warning: " + w);
        return String.join("\n", out);
    }

    private void selectFirstEffect() {
        Editor.Draft d = editor.draft();
        List<Json> ps = d == null ? List.of() : arr(d.spell(), "payloads");
        selectedEffect = ps.isEmpty() ? null : str((Json.Obj) ps.get(0), "id");
        lastReadout = editor.readout();
        delta = "";
    }

    private String freeRuleId(String base) {
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (String k : List.of("riders", "requires")) for (Json j : arr(editor.draft().spell(), k)) ids.add(str((Json.Obj) j, "id"));
        if (!ids.contains(base)) return base;
        for (int i = 2; ; i++) if (!ids.contains(base + i)) return base + i;
    }

    private String affects() {
        Json.Obj del = (Json.Obj) editor.draft().spell().get("delivery");
        return del.get("burst") instanceof Json.Obj b && str(b, "affects") != null ? str(b, "affects") : "others";
    }

    private String describeRule(Json.Obj rule) {
        for (Map.Entry<String, String> e : RIDER_PRESETS.entrySet()) {
            Json.Obj p = (Json.Obj) ((Parsed.Ok<Json>) Formats.json(e.getValue())).value();
            if (p.get("when").equals(rule.get("when")) && java.util.Objects.equals(p.get("bonus_pct"), rule.get("bonus_pct"))) {
                return e.getKey();
            }
        }
        return str(rule, "id");
    }

    private static Map<String, String> presets() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("In a storm: +50% strength", "{\"id\":\"storm\",\"when\":{\"type\":\"weather\",\"category\":[\"core:storm\"]},\"bonus_pct\":{\"strength\":50}}");
        m.put("In rain or snow: +25% strength", "{\"id\":\"rain\",\"when\":{\"type\":\"weather\",\"category\":[\"core:precipitation\"]},\"bonus_pct\":{\"strength\":25}}");
        m.put("At dawn (5:00 to 8:00): +25% strength", "{\"id\":\"dawn\",\"when\":{\"type\":\"time_of_day\",\"from\":5,\"to\":8},\"bonus_pct\":{\"strength\":25}}");
        m.put("At night: +25% strength", "{\"id\":\"night\",\"when\":{\"type\":\"is_night\"},\"bonus_pct\":{\"strength\":25}}");
        m.put("Only at night", "{\"id\":\"only_night\",\"when\":{\"type\":\"is_night\"}}");
        m.put("Only in a storm", "{\"id\":\"only_storm\",\"when\":{\"type\":\"weather\",\"category\":[\"core:storm\"]}}");
        return m;
    }

    private Spell librarySpell(String id) {
        for (Editor.ModFiles f : editor.library().values()) for (Spell s : spellsOf(f)) if (s.id().equals(id)) return s;
        return null;
    }

    private static List<Spell> spellsOf(Editor.ModFiles f) {
        if (f.spells() == null) return List.of();
        Parsed<Formats.Pack> p = Formats.pack(f.spells(), Formats.Options.DEFAULT);
        return p instanceof Parsed.Ok<Formats.Pack> ok ? ok.value().spells() : List.of();
    }

    private static Spell parse(Json.Obj spell) {
        Parsed<Spell> p = Formats.spell(spell, Formats.Options.DEFAULT);
        return p instanceof Parsed.Ok<Spell> ok ? ok.value() : null;
    }

    private String school(Spell s) {
        return rules == null ? null : rules.school(s.categories());
    }

    private String effectName(String id) {
        if (id == null) return "?";
        Effect e = catalog.get(id);
        String n = e == null ? id : e.name();
        return n.startsWith("hytale:") ? n.substring(7).replace('_', ' ') : n;
    }

    private static String modOf(String id) {
        if (id.startsWith("hytale:")) return "Hytale";
        int slash = id.lastIndexOf('/');
        return slash > 0 ? id.substring(0, slash) : id;
    }

    private static Json.Obj payload(Json.Obj spell, String id) {
        if (spell == null || id == null) return null;
        for (Json j : arr(spell, "payloads")) if (j instanceof Json.Obj o && id.equals(str(o, "id"))) return o;
        return null;
    }

    private int section(ViewOps v, int r, String title) {
        v.append(Part.ROWS, RowKind.SECTION_ROW);
        v.text(Ref.row(r, Child.NONE), title); // the row is a bare Label: its text is its own
        return r + 1;
    }

    private int note(ViewOps v, int r, String text) {
        v.append(Part.ROWS, RowKind.NOTE_ROW);
        v.text(Ref.row(r, Child.NONE), text); // the row is a bare Label: its text is its own
        return r + 1;
    }

    private int greyed(ViewOps v, int r, String what) {
        v.append(Part.ROWS, RowKind.TEXT_ROW);
        v.text(Ref.row(r, Child.LABEL), what);
        // Read-only by having no binding: Hytale's TextField and CheckBox have no Disabled property, and setting one
        // disconnects the client (the author's playtest, 2026-11-08: "#Rows[11] #Input.Disabled"). Only buttons take Disabled.
        v.value(Ref.row(r, Child.INPUT), UNAVAILABLE);
        v.visible(Ref.row(r, Child.PICK), false);
        v.text(Ref.row(r, Child.HINT), UNAVAILABLE);
        return r + 1;
    }

    private int text(ViewOps v, int r, String label, String value, String hint, String field) {
        v.append(Part.ROWS, RowKind.TEXT_ROW);
        v.text(Ref.row(r, Child.LABEL), label);
        v.value(Ref.row(r, Child.INPUT), value == null ? "" : value);
        v.text(Ref.row(r, Child.HINT), hint);
        v.visible(Ref.row(r, Child.PICK), false);
        v.bind(Ref.row(r, Child.INPUT), Trigger.CHANGE, "field", Map.of("name", field), Map.of("value", Ref.row(r, Child.INPUT)));
        return r + 1;
    }

    private int pickRow(ViewOps v, int r, String label, String value, String payloadId) {
        v.append(Part.ROWS, RowKind.TEXT_ROW);
        v.text(Ref.row(r, Child.LABEL), label);
        v.value(Ref.row(r, Child.INPUT), value); // read-only: no binding (see greyed)
        v.bind(Ref.row(r, Child.PICK), Trigger.ACTIVATE, "pickEffect", Map.of("for", payloadId), Map.of());
        return r + 1;
    }

    private int check(ViewOps v, int r, String label, boolean on, String field, boolean disabled) {
        v.append(Part.ROWS, RowKind.CHECK_ROW);
        v.text(Ref.row(r, Child.LABEL), label);
        v.value(Ref.row(r, Child.INPUT), on);
        // A disabled check is read-only by having no binding (see greyed).
        if (!disabled) v.bind(Ref.row(r, Child.INPUT), Trigger.CHANGE, "choose", Map.of("name", field), Map.of("value", Ref.row(r, Child.INPUT)));
        return r + 1;
    }

    private int choice(ViewOps v, int r, String label, String value, List<Entry> entries, String field) {
        v.append(Part.ROWS, RowKind.CHOICE_ROW);
        v.text(Ref.row(r, Child.LABEL), label);
        v.entries(Ref.row(r, Child.INPUT), entries);
        v.value(Ref.row(r, Child.INPUT), value == null ? entries.get(0).value() : value);
        v.bind(Ref.row(r, Child.INPUT), Trigger.CHANGE, "choose", Map.of("name", field), Map.of("value", Ref.row(r, Child.INPUT)));
        return r + 1;
    }

    private void footer(ViewOps v, Child c, String label, String name) {
        v.text(Ref.footer(c), label);
        v.bind(Ref.footer(c), Trigger.ACTIVATE, "button", Map.of("name", name), Map.of());
    }

    private static Json.Obj cmd(String name, Object... kv) {
        Map<String, Json> m = new LinkedHashMap<>();
        m.put("cmd", new Json.Str(name));
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], (Json) kv[i + 1]);
        return new Json.Obj(m);
    }

    private static Json.Obj set(String param, long milli) {
        return JsonWriter.obj("cmd", new Json.Str("set"), "param", new Json.Str(param), "value", new Json.Num(Milli.format(milli)));
    }

    private static Json number(String v) {
        Milli.Parsed m = Milli.parseMilli(v);
        return v.isEmpty() || !m.ok() ? null : new Json.Num(Milli.format(m.value()));
    }

    private static Json whole(String v) {
        Milli.Parsed m = Milli.parseWhole(v);
        return v.isEmpty() || !m.ok() ? null : new Json.Num(Long.toString(m.value()));
    }

    private static Json.Obj put(Json.Obj o, String k, Json v) {
        Map<String, Json> m = new LinkedHashMap<>(o.members());
        m.put(k, v);
        return new Json.Obj(m);
    }

    private static List<Json> arr(Json.Obj o, String key) {
        return o.get(key) instanceof Json.Arr a ? a.items() : List.of();
    }

    private static String str(Json.Obj o, String key) {
        return o != null && o.get(key) instanceof Json.Str s ? s.value() : null;
    }

    private static String num(Json v) {
        return v instanceof Json.Num n ? n.text() : "";
    }

    private static String text(Json v) {
        if (v instanceof Json.Str s) return s.value();
        if (v instanceof Json.Num n) return n.text();
        return "–";
    }

    private static String s(Object o) {
        return o == null ? "" : o.toString();
    }

    private static String signed(long hundredthsTimesTen) {
        long h = hundredthsTimesTen / 10;
        String t = Milli.format(Math.abs(h) * 10);
        return (h >= 0 ? "+" : "-") + t;
    }

    private static String capital(String s) {
        return s == null || s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
