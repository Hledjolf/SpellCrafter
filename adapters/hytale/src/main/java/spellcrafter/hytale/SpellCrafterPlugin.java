package spellcrafter.hytale;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;

import spellcrafter.core.format.Formats;
import spellcrafter.core.format.Parsed;
import spellcrafter.core.json.Json;
import spellcrafter.core.power.Frequencies;
import spellcrafter.hytale.casting.ActionLog;
import spellcrafter.hytale.casting.Settings;
import spellcrafter.hytale.casting.Casting;
import spellcrafter.hytale.casting.ConditionPort;
import spellcrafter.hytale.casting.Content;
import spellcrafter.hytale.casting.FakeConditions;
import spellcrafter.hytale.casting.FakePayloads;
import spellcrafter.hytale.casting.PayloadPort;

/**
 * SpellCrafter on Hytale: loads the 1.0 pack from the jar through the core, registers the casting interactions the
 * vessel items name, and watches casts in progress.
 *
 * <p>Conditions and payloads go through {@link ConditionPort} and {@link PayloadPort}. Conditions are answered by
 * CommandDeck's condition library when it's installed (else UNKNOWN); payloads by BuffEffects' API when it's
 * installed (else logged and not applied). Headless tests can set answers on both ports.
 */
public final class SpellCrafterPlugin extends JavaPlugin {
    static final String PREFIX = "[SpellCrafter] ";
    static final long TICK_MS = 100;
    static final String DATA = "spellcrafter/";

    private static volatile SpellCrafterPlugin instance;

    private Content content;
    private Casting rules;
    private Settings options;
    private Frequencies baseFrequencies = Frequencies.NONE;
    private ActionLog actionLog = new ActionLog(null, null);
    private FakeConditions conditions;
    private FakePayloads payloads;
    private HytaleCasting casting;
    private ScheduledExecutorService scheduler;
    private EditorWindows editors;

    public SpellCrafterPlugin(JavaPluginInit init) {
        super(init);
    }

    /** The running plugin's casting glue, or null before setup. */
    static HytaleCasting casting() {
        SpellCrafterPlugin p = instance;
        return p == null ? null : p.casting;
    }

    /** For headless tests: the plugin, once set up. */
    public static SpellCrafterPlugin get() {
        return instance;
    }

    public Content content() {
        return content;
    }

    /** The condition port; headless tests set answers on it. */
    public FakeConditions fakeConditions() {
        return conditions;
    }

    /** The payload port; headless tests read what it was asked and answered. */
    public FakePayloads fakePayloads() {
        return payloads;
    }

    private final List<java.util.function.Consumer<String>> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** For headless tests: every log line, as it's written. */
    public void listen(java.util.function.Consumer<String> l) {
        listeners.add(l);
    }

    void log(String s) {
        getLogger().atInfo().log("%s%s", PREFIX, s);
        for (var l : listeners) l.accept(s);
    }

    @Override
    protected void setup() {
        log("SpellCrafter " + getManifest().getVersion() + " on Hytale");
        // Before the asset pack decodes, so the vessel items and bolts can name these types.
        var reg = getCodecRegistry(Interaction.CODEC);
        reg.register(SpellInteractions.BEGIN, SpellInteractions.Begin.class, SpellInteractions.Begin.CODEC);
        reg.register(SpellInteractions.COMPLETE, SpellInteractions.Complete.class, SpellInteractions.Complete.CODEC);
        reg.register(SpellInteractions.DRINK, SpellInteractions.Drink.class, SpellInteractions.Drink.CODEC);
        reg.register(SpellInteractions.HIT, SpellInteractions.Hit.class, SpellInteractions.Hit.CODEC);
        reg.register(SpellInteractions.MISS, SpellInteractions.Miss.class, SpellInteractions.Miss.CODEC);

        Parsed<Frequencies> fp = Frequencies.parse(Formats.json(source("hytale.frequencies.json").text()) instanceof Parsed.Ok<Json> fj
                ? fj.value() : null);
        if (fp instanceof Parsed.Ok<Frequencies> ok) baseFrequencies = ok.value();
        else log("hytale.frequencies.json is unreadable: every world condition at its best case");
        actionLog = new ActionLog(getDataDirectory().resolve("spellcrafter.log"), this::log);
        editors = new EditorWindows(this, getDataDirectory(), this::log);
        options = readSettings();
        content = loadContent(options);
        content.options(options);

        conditions = new FakeConditions();
        payloads = new FakePayloads(this::log);
        rules = new Casting(content, conditions, this::log);
        casting = new HytaleCasting(rules, content, payloads, bolts(), this::log);
        casting.actionLog(actionLog);
        getCommandRegistry().registerCommand(new SpellCommand(this));
        // Editor-saved items' names live only on the clients (RuntimeAssets.lang): give them to each player who joins.
        getEventRegistry().registerGlobal(com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent.class, e -> {
            try {
                var ref = e.getPlayerRef();
                var p = ref == null || !ref.isValid() ? null
                        : ref.getStore().getComponent(ref, com.hypixel.hytale.server.core.universe.PlayerRef.getComponentType());
                if (p != null) RuntimeAssets.sendNames(p, this::log);
            } catch (RuntimeException x) {
                log("couldn't send item names to a joining player: " + x);
            }
        });
        instance = this;
    }

    @Override
    protected void start() {
        // CommandDeck's condition library starts in its setup, so it's there (or not) by now.
        conditions.useLibrary(LibraryConditions.find(this::log));
        log("conditions: " + conditions.name());
        // BuffEffects publishes its API in its setup, likewise.
        payloads.useProvider(BuffEffectsPayloads.find(this::log));
        log("payloads: " + payloads.name());
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "spellcrafter-tick");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(() -> {
            try {
                // Never into a world that's stopping, and only on its own thread: a stopping world runs its queue
                // on the shutdown thread, where every store read throws. BuffEffects' identical ticker did that
                // ~1,700 times on 2026-11-07 and the server never finished shutting down (the author's Integrated Test).
                for (World w : Universe.get().getWorlds().values()) {
                    if (!w.isAlive()) continue;
                    w.execute(() -> {
                        if (w.isAlive() && w.isInThread()) casting.tick(w);
                    });
                }
                casting.expireBolts();
            } catch (RuntimeException e) {
                log("tick failed: " + e);
            }
        }, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
        // Items for the default pack's vessels (spells saved in the editor), off the world threads (probe B6).
        Thread t = new Thread(() -> {
            try {
                editors.registerDefaultAssets();
            } catch (RuntimeException e) {
                log("couldn't register the default pack's items: " + e);
            }
        }, "spellcrafter-default-assets");
        t.setDaemon(true);
        t.start();
    }

    /** The spell editor's windows ({@code /spell editor}). */
    EditorWindows editors() {
        return editors;
    }

    /** For headless scenarios: a window model as {@code /spell editor} would make one, with no page. */
    public spellcrafter.core.window.WindowModel editorWindow(String author) {
        return editors.create(author);
    }

    /**
     * For headless scenarios: what a page does after an event, minus the requests (they need a player): writes the
     * window's files, reloads, and registers the new vessels' items. Off the world thread. Returns the items made.
     */
    public int applyEditorWrites(spellcrafter.core.window.WindowModel m, String who) {
        List<spellcrafter.core.editor.Editor.Write> writes;
        synchronized (m) {
            writes = m.takeWrites();
        }
        if (writes.isEmpty()) return 0;
        editors.write(writes, who);
        reload();
        return editors.registerDefaultAssets();
    }

    /** The default pack's spells file (the editor saves there). */
    public Path defaultSpellsFile() {
        return editors.pack().spellsFile();
    }

    /** A text resource of the jar's {@code spellcrafter/} folder, or "" if there's none. */
    String resource(String name) {
        return source(name).text();
    }

    /** Whether the jar holds a resource at this path (for run-time items' art). */
    boolean hasResource(String path) {
        return getClass().getClassLoader().getResource(path) != null;
    }

    @Override
    protected void shutdown() {
        if (scheduler != null) scheduler.shutdownNow();
        instance = null;
    }

    private Content.Source source(String name) {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(DATA + name)) {
            if (in == null) return new Content.Source(name, "");
            return new Content.Source(name, new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return new Content.Source(name, "");
        }
    }

    // ------------------------------------------------------------------------------------------------ settings

    /** The casting rules (cooldowns, debug), for the commands. */
    Casting rules() {
        return rules;
    }

    /** SpellCrafter's log ({@code spellcrafter.log} in the data folder). */
    ActionLog actionLog() {
        return actionLog;
    }

    private Content loadContent(Settings o) {
        // The 1.0 pack from the jar, then the default pack the editor saves into (the data folder).
        List<Content.Source> packs = new ArrayList<>(List.of(source("spellcrafter-1.0.spells.json")));
        List<Content.Source> vessels = new ArrayList<>(List.of(source("spellcrafter-1.0.vessels.json")));
        if (editors != null) {
            packs.addAll(editors.pack().spellSources());
            vessels.addAll(editors.pack().vesselSources());
        }
        Content c = Content.load(source("hytale.profile.json"), packs, vessels, o.disabledPacks());
        for (String p : c.problems()) log("refused: " + p);
        for (String m : c.disabledPacks()) log("spell pack of " + m + " is off (DisabledPacks)");
        log("loaded " + c.spells().size() + " spells and " + c.vessels().size() + " vessels"
                + (c.problems().isEmpty() ? "" : " (" + c.problems().size() + " problems above)"));
        return c;
    }

    private Path configFile() {
        return getDataDirectory().resolve("config.json");
    }

    /** {@code config.json}, written with every default on first start, so Mod Settings Manager shows every key. */
    Settings readSettings() {
        Settings s;
        try {
            if (Files.isRegularFile(configFile())) {
                Parsed<Json> p = Formats.json(Files.readString(configFile(), StandardCharsets.UTF_8));
                s = p instanceof Parsed.Ok<Json> ok && ok.value() instanceof Json.Obj o ? Settings.read(o, baseFrequencies)
                        : Settings.defaults(baseFrequencies);
                if (!(p instanceof Parsed.Ok<Json>)) log("config.json isn't valid JSON: using the defaults");
            } else {
                s = Settings.defaults(baseFrequencies);
                Files.createDirectories(getDataDirectory());
                Files.writeString(configFile(), s.toFileText(), StandardCharsets.UTF_8);
            }
        } catch (IOException | RuntimeException e) {
            log("couldn't read config.json, using the defaults: " + e);
            s = Settings.defaults(baseFrequencies);
        }
        for (String p : s.problems()) log("settings: " + p);
        return s;
    }

    /**
     * {@code /spell reload}: re-reads the settings, then the spell packs and vessels. Returns what changed, which
     * the command reports and logs.
     */
    synchronized List<String> reload() {
        Settings before = options;
        Settings now = readSettings();
        Content fresh = loadContent(now);
        List<String> changes = new ArrayList<>(now.changesFrom(before));
        if (fresh.spells().size() != content.spells().size()) {
            changes.add("spells: " + content.spells().size() + " -> " + fresh.spells().size());
        }
        if (fresh.vessels().size() != content.vessels().size()) {
            changes.add("vessels: " + content.vessels().size() + " -> " + fresh.vessels().size());
        }
        content.replaceWith(fresh);
        content.options(now);
        options = now;
        return changes;
    }

    /**
     * Mod Settings Manager's live-apply hook: {@code config.json} is already written. Disabling a spell pack needs the
     * packs read again; everything else applies as is.
     */
    public boolean onSettingsChanged(Map<String, Object> changed) {
        if (changed != null && changed.containsKey("DisabledPacks")) {
            reload();
        } else {
            Settings now = readSettings();
            content.options(now);
            options = now;
        }
        return true;
    }

    /** spell id -> its bolt's projectile config, from the generated hytale-assets.json. */
    private Map<String, String> bolts() {
        Map<String, String> out = new LinkedHashMap<>();
        Parsed<Json> p = Formats.json(source("hytale-assets.json").text());
        if (p instanceof Parsed.Ok<Json> ok && ok.value() instanceof Json.Obj o && o.get("bolts") instanceof Json.Obj b) {
            for (Map.Entry<String, Json> e : b.members().entrySet()) {
                if (e.getValue() instanceof Json.Str s) out.put(e.getKey(), s.value());
            }
        } else {
            log("hytale-assets.json is missing: no bolts");
        }
        return out;
    }
}
