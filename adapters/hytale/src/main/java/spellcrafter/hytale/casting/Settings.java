package spellcrafter.hytale.casting;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import spellcrafter.core.format.Formats;
import spellcrafter.core.format.Parsed;
import spellcrafter.core.format.Problem;
import spellcrafter.core.json.Json;
import spellcrafter.core.json.JsonWriter;
import spellcrafter.core.model.Model;
import spellcrafter.core.model.Model.ServerSettings;
import spellcrafter.core.power.Frequencies;

/**
 * SpellCrafter's server settings on Hytale (design/commands-settings.md section 3): the flat {@code config.json} in the
 * plugin's data folder, which Mod Settings Manager edits (sidecar {@code Settings/Hledjolf_SpellCrafter.settings.json}).
 * The caps and overrides become a {@code spellcrafter.settings} 1.0 document, read by the core's own parser; the
 * world frequencies lie over the adapter's defaults; the rest are the adapter's own options.
 *
 * <p>No game API, so it's unit-tested.
 */
public final class Settings {

    /** Whether bursts and {@code enemies} may hit players: follow the world's PvP setting, or always, or never. */
    public enum PlayerHits { FOLLOW_PVP, ALWAYS, NEVER }

    /** Every key, in the order the file and the window show them, with its default. */
    public static final Map<String, Object> DEFAULTS = defaults();

    private static Map<String, Object> defaults() {
        Map<String, Object> d = new LinkedHashMap<>();
        // Caps (spell-format 10, 7b).
        d.put("BonusPct", 200L);
        d.put("MorePctEach", 50L);
        d.put("MorePctPerParam", 2L);
        d.put("AddedPayloads", 3L);
        d.put("RidersPerCast", 16L);
        d.put("CircumstancesPerCast", 8L);
        d.put("BurstTargets", 16L);
        d.put("BurstRadius", 8.0);
        d.put("ConditionDepth", 4L);
        d.put("GroupDepth", 3L);
        d.put("RecheckMinMs", 1000L);
        // Overrides: comma-separated ids.
        d.put("IgnoreGates", "");
        d.put("IgnoreGateTypes", "");
        d.put("IgnoreUnknownGates", false);
        d.put("IgnoreComponents", "");
        d.put("IgnoreComponentItems", "");
        d.put("IgnoreFoci", false);
        d.put("IgnoreAllComponents", false);
        d.put("CircumstancesOff", "");
        d.put("CircumstanceModsOff", "");
        // World: how often world conditions hold (release-1.0.md section 3).
        d.put("WeatherClear", 0.6);
        d.put("WeatherPrecipitation", 0.3);
        d.put("WeatherRain", 0.25);
        d.put("WeatherSnow", 0.05);
        d.put("WeatherStorm", 0.1);
        d.put("WeatherFog", 0.1);
        d.put("NightShare", 0.4);
        // Combat.
        d.put("BurstsHitPlayers", "follow_pvp");
        d.put("InterruptDistance", 0.5);
        // Content.
        d.put("DisabledPacks", "");
        return java.util.Collections.unmodifiableMap(d);
    }

    private static final Map<String, String> WEATHER = Map.of("WeatherClear", "core:clear", "WeatherPrecipitation",
            "core:precipitation", "WeatherRain", "core:rain", "WeatherSnow", "core:snow", "WeatherStorm", "core:storm",
            "WeatherFog", "core:fog");

    private final Map<String, Json> values;
    private final ServerSettings server;
    private final Frequencies frequencies;
    private final List<String> problems;

    private Settings(Map<String, Json> values, ServerSettings server, Frequencies frequencies, List<String> problems) {
        this.values = values;
        this.server = server;
        this.frequencies = frequencies;
        this.problems = List.copyOf(problems);
    }

    /** The defaults, over {@code base} frequencies. */
    public static Settings defaults(Frequencies base) {
        return read(JsonWriter.obj(), base);
    }

    /**
     * Reads {@code config} (a parsed {@code config.json}; missing keys take their defaults) over the adapter's
     * {@code base} frequencies. A value the core refuses is reported in {@link #problems()} and the defaults are kept
     * for the caps and overrides.
     */
    public static Settings read(Json.Obj config, Frequencies base) {
        List<String> problems = new ArrayList<>();
        Map<String, Json> v = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : DEFAULTS.entrySet()) {
            Json given = config.get(e.getKey());
            v.put(e.getKey(), given != null ? given : json(e.getValue()));
        }
        for (String k : config.members().keySet()) if (!DEFAULTS.containsKey(k)) problems.add("config.json: unknown key " + k);

        Json.Obj caps = JsonWriter.obj("bonus_pct", v.get("BonusPct"),
                "more_pct", JsonWriter.obj("each", v.get("MorePctEach"), "per_param", v.get("MorePctPerParam")),
                "added_payloads", v.get("AddedPayloads"), "riders_per_cast", v.get("RidersPerCast"),
                "circumstances_per_cast", v.get("CircumstancesPerCast"), "burst_targets", v.get("BurstTargets"),
                "params", JsonWriter.obj("delivery.burst.radius", v.get("BurstRadius")),
                "condition_depth", v.get("ConditionDepth"), "group_depth", v.get("GroupDepth"),
                "recheck_min_ms", v.get("RecheckMinMs"));
        Json.Obj overrides = JsonWriter.obj("ignore_gates", list(v.get("IgnoreGates")),
                "ignore_gate_types", list(v.get("IgnoreGateTypes")), "ignore_unknown_gates", v.get("IgnoreUnknownGates"),
                "ignore_components", list(v.get("IgnoreComponents")),
                "ignore_component_items", list(v.get("IgnoreComponentItems")), "ignore_foci", v.get("IgnoreFoci"),
                "ignore_all_components", v.get("IgnoreAllComponents"), "circumstances_off", list(v.get("CircumstancesOff")),
                "circumstance_mods_off", list(v.get("CircumstanceModsOff")));
        Json.Obj doc = JsonWriter.obj("format", new Json.Str("spellcrafter.settings"), "format_version",
                new Json.Str("1.0"), "caps", caps, "overrides", overrides);
        ServerSettings server = Model.DEFAULT_SETTINGS;
        Parsed<ServerSettings> p = Formats.settings(doc);
        if (p instanceof Parsed.Ok<ServerSettings> ok) server = ok.value();
        else for (Problem x : ((Parsed.Invalid<ServerSettings>) p).problems()) {
            problems.add("config.json" + x.pointer() + ": " + x.code() + ": " + x.message());
        }

        Map<String, Json> weather = new LinkedHashMap<>();
        for (Map.Entry<String, String> w : WEATHER.entrySet()) weather.put(w.getValue(), v.get(w.getKey()));
        Json.Obj fdoc = JsonWriter.obj("format", new Json.Str("spellcrafter.frequencies"), "format_version",
                new Json.Str("1.0"), "weather", new Json.Obj(weather), "is_night", v.get("NightShare"));
        Frequencies freq = base;
        Parsed<Frequencies> fp = Frequencies.parse(fdoc);
        if (fp instanceof Parsed.Ok<Frequencies> ok) freq = base.over(ok.value());
        else for (Problem x : ((Parsed.Invalid<Frequencies>) fp).problems()) {
            problems.add("config.json (world)" + x.pointer() + ": " + x.code() + ": " + x.message());
        }
        Settings s = new Settings(v, server, freq, problems);
        if (s.playerHits() == null) problems.add("config.json: BurstsHitPlayers is follow_pvp, always or never");
        if (s.interruptDistance() < 0) problems.add("config.json: InterruptDistance can't be negative");
        return new Settings(v, server, freq, problems);
    }

    /** The caps and overrides, as the core reads them. */
    public ServerSettings server() {
        return server;
    }

    /** The adapter's frequencies with the server's World values over them. */
    public Frequencies frequencies() {
        return frequencies;
    }

    /** Blocks a caster may move before a cast is interrupted. */
    public double interruptDistance() {
        return num(values.get("InterruptDistance"), 0.5);
    }

    /** Whether bursts and {@code enemies} may hit players; null when the value is unknown (then: follow PvP). */
    public PlayerHits playerHits() {
        String s = values.get("BurstsHitPlayers") instanceof Json.Str x ? x.value() : "";
        return switch (s) {
            case "follow_pvp" -> PlayerHits.FOLLOW_PVP;
            case "always" -> PlayerHits.ALWAYS;
            case "never" -> PlayerHits.NEVER;
            default -> null;
        };
    }

    /** The mod ids whose spell packs are off. */
    public List<String> disabledPacks() {
        return strings(list(values.get("DisabledPacks")));
    }

    public List<String> problems() {
        return problems;
    }

    /** The file as it stands, every key, one per line (for writing {@code config.json}). */
    public String toFileText() {
        StringBuilder b = new StringBuilder("{\n");
        int i = 0;
        for (Map.Entry<String, Json> e : values.entrySet()) {
            b.append("  ").append(JsonWriter.write(new Json.Str(e.getKey()))).append(": ").append(JsonWriter.write(e.getValue()));
            b.append(++i < values.size() ? ",\n" : "\n");
        }
        return b.append("}\n").toString();
    }

    /** What changed from {@code before}, as {@code key: old -> new} lines (for {@code /spell reload}'s log). */
    public List<String> changesFrom(Settings before) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Json> e : values.entrySet()) {
            Json old = before == null ? null : before.values.get(e.getKey());
            String o = old == null ? "-" : JsonWriter.write(old);
            String n = JsonWriter.write(e.getValue());
            if (!o.equals(n)) out.add(e.getKey() + ": " + o + " -> " + n);
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private static Json json(Object o) {
        if (o instanceof Boolean b) return new Json.Bool(b);
        if (o instanceof Long l) return new Json.Num(Long.toString(l));
        if (o instanceof Double d) return new Json.Num(d.toString());
        return new Json.Str(String.valueOf(o));
    }

    /** A comma-separated string (MSM edits it as text) or a JSON array, as an array of strings. */
    private static Json list(Json v) {
        List<Json> out = new ArrayList<>();
        if (v instanceof Json.Arr a) return a;
        if (v instanceof Json.Str s) {
            for (String part : s.value().split(",")) if (!part.isBlank()) out.add(new Json.Str(part.trim()));
        }
        return new Json.Arr(out);
    }

    private static List<String> strings(Json v) {
        List<String> out = new ArrayList<>();
        if (v instanceof Json.Arr a) for (Json j : a.items()) if (j instanceof Json.Str s) out.add(s.value());
        return out;
    }

    private static double num(Json v, double d) {
        if (!(v instanceof Json.Num n)) return d;
        try {
            return Double.parseDouble(n.text());
        } catch (NumberFormatException e) {
            return d;
        }
    }
}
