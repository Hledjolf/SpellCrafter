package spellcrafter.hytale.casting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import spellcrafter.core.format.Formats;
import spellcrafter.core.format.Parsed;
import spellcrafter.core.format.Problem;
import spellcrafter.core.json.Json;
import spellcrafter.core.model.Model;
import spellcrafter.core.model.Model.Profile;
import spellcrafter.core.model.Model.ServerSettings;
import spellcrafter.core.model.Model.Spell;
import spellcrafter.core.model.Model.Vessel;

/**
 * The loaded spells and vessels, read through the core. A vessel is found by its game item id (its
 * {@code bindings.hytale.item}). Anything the core refuses is reported in {@link #problems()} and left out.
 */
public final class Content {
    private final Map<String, Spell> spells = new LinkedHashMap<>();
    private final Map<String, Vessel> vessels = new LinkedHashMap<>();
    private final Map<String, Vessel> byItem = new LinkedHashMap<>();
    private final List<String> problems = new ArrayList<>();
    private Profile profile;
    private ServerSettings settings = Model.DEFAULT_SETTINGS;
    private Settings options;
    private final List<String> disabled = new ArrayList<>();

    /** A document to read: where it came from (for messages) and its text. */
    public record Source(String name, String text) {
    }

    public static Content load(Source profileDoc, List<Source> spellPacks, List<Source> vesselFiles) {
        return load(profileDoc, spellPacks, vesselFiles, List.of());
    }

    /** As {@link #load(Source, List, List)}, leaving out the spell packs of the mods in {@code disabledMods}. */
    public static Content load(Source profileDoc, List<Source> spellPacks, List<Source> vesselFiles,
            List<String> disabledMods) {
        Content c = new Content();
        Json pj = c.json(profileDoc);
        if (pj != null) c.profile = c.ok(profileDoc.name(), Formats.profile(pj));
        for (Source s : spellPacks) {
            Json j = c.json(s);
            Formats.Pack pack = j == null ? null : c.ok(s.name(), Formats.pack(j, Formats.Options.DEFAULT));
            if (pack == null) continue;
            if (disabledMods.contains(pack.modId())) {
                c.disabled.add(pack.modId());
                continue;
            }
            for (Spell sp : pack.spells()) {
                if (c.spells.putIfAbsent(sp.id(), sp) != null) c.problems.add(s.name() + ": spell " + sp.id() + " is loaded twice");
            }
        }
        for (Source s : vesselFiles) {
            Json j = c.json(s);
            Formats.VesselPack pack = j == null ? null : c.ok(s.name(), Formats.vessels(j));
            if (pack == null) continue;
            List<Problem> ps = Formats.checkVessels(pack, c.spells);
            for (Problem p : ps) c.problems.add(s.name() + p.pointer() + ": " + p.code() + ": " + p.message());
            java.util.Set<String> bad = new java.util.HashSet<>();
            for (Problem p : ps) bad.add(p.pointer().substring(0, p.pointer().indexOf('/', "/vessels/".length())));
            for (int i = 0; i < pack.vessels().size(); i++) {
                Vessel v = pack.vessels().get(i);
                if (bad.contains("/vessels/" + i)) continue;
                String item = hytaleItem(v);
                if (item == null) {
                    c.problems.add(s.name() + "/vessels/" + i + ": no bindings.hytale.item, so it can't be used here");
                    continue;
                }
                c.vessels.put(v.id(), v);
                c.byItem.put(item, v);
            }
        }
        return c;
    }

    /**
     * Takes everything {@code other} loaded (spells, vessels, profile, problems), keeping this object, so the casting
     * rules that hold it see a {@code /spell reload}. Settings are set separately.
     */
    public synchronized void replaceWith(Content other) {
        spells.clear();
        spells.putAll(other.spells);
        vessels.clear();
        vessels.putAll(other.vessels);
        byItem.clear();
        byItem.putAll(other.byItem);
        problems.clear();
        problems.addAll(other.problems);
        disabled.clear();
        disabled.addAll(other.disabled);
        profile = other.profile;
    }

    /** The vessel's Hytale item id, or null. */
    public static String hytaleItem(Vessel v) {
        if (v.bindings() == null || !(v.bindings().get("hytale") instanceof Json.Obj h)) return null;
        return h.get("item") instanceof Json.Str s ? s.value() : null;
    }

    private Json json(Source s) {
        Parsed<Json> p = Formats.json(s.text());
        return ok(s.name(), p);
    }

    private <T> T ok(String name, Parsed<T> p) {
        if (p instanceof Parsed.Ok<T> ok) {
            for (Problem w : ok.warnings()) problems.add(name + w.pointer() + ": warning " + w.code() + ": " + w.message());
            return ok.value();
        }
        for (Problem x : ((Parsed.Invalid<T>) p).problems()) {
            problems.add(name + x.pointer() + ": " + x.code() + ": " + x.message());
        }
        return null;
    }

    public Profile profile() {
        return profile;
    }

    /** The server's caps and overrides ({@code spellcrafter.settings}); the defaults until set. */
    public ServerSettings settings() {
        return settings;
    }

    /** Sets the server's settings (from Mod Settings Manager, or the settings file). */
    public void settings(ServerSettings s) {
        settings = s == null ? Model.DEFAULT_SETTINGS : s;
    }

    /** Sets everything from {@code config.json}: the core's settings, the frequencies and the adapter's options. */
    public void options(Settings o) {
        options = o;
        settings(o == null ? null : o.server());
    }

    /** The adapter's options from {@code config.json}; null until set (then the defaults apply). */
    public Settings options() {
        return options;
    }

    /** Blocks a caster may move before a cast is interrupted (0.5 until set). */
    public double interruptDistance() {
        return options == null ? 0.5 : options.interruptDistance();
    }

    /** The mod ids whose spell packs were left out, being disabled. */
    public List<String> disabledPacks() {
        return Collections.unmodifiableList(disabled);
    }

    public Spell spell(String id) {
        return spells.get(id);
    }

    public Map<String, Spell> spells() {
        return Collections.unmodifiableMap(spells);
    }

    public Map<String, Vessel> vessels() {
        return Collections.unmodifiableMap(vessels);
    }

    /** The vessel for a game item id, or null. */
    public Vessel vesselForItem(String itemId) {
        return byItem.get(itemId);
    }

    public List<String> problems() {
        return Collections.unmodifiableList(problems);
    }
}
