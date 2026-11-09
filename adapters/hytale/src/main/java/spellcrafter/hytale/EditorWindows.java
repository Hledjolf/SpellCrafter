package spellcrafter.hytale;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.joml.Vector3d;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;

import spellcrafter.core.editor.Editor;
import spellcrafter.core.editor.Effect;
import spellcrafter.core.editor.RecipeRules;
import spellcrafter.core.editor.StatBlock;
import spellcrafter.core.editor.Template;
import spellcrafter.core.format.Formats;
import spellcrafter.core.format.Parsed;
import spellcrafter.core.json.Json;
import spellcrafter.core.model.Model.Spell;
import spellcrafter.core.model.Model.Vessel;
import spellcrafter.core.power.Frequencies;
import spellcrafter.core.power.Gta;
import spellcrafter.core.window.WindowModel;
import spellcrafter.hytale.casting.Content;
import spellcrafter.hytale.casting.DefaultPack;
import spellcrafter.hytale.casting.PayloadPort;
import spellcrafter.hytale.casting.VesselAssets;
import spellcrafter.hytale.window.EditorPage;

/**
 * The spell editor on Hytale (design/window.md, editor.md): one {@link WindowModel} per person, kept while the server
 * runs so a closed window keeps its draft, drawn on {@link EditorPage}. The game's side: BuffEffects' catalog (its
 * {@code list} and {@code describe}, the game's own effects included), the spell packs as the editor's library, the
 * {@code default} pack's files in the data folder, run-time items for saved vessels (probe B6), and Try it.
 */
final class EditorWindows {
    /** The training dummy "Try it" casts on (probe B5). */
    static final String DUMMY = "Test_Dummy_Stationary";

    private final SpellCrafterPlugin plugin;
    private final DefaultPack pack;
    private final Consumer<String> log;
    private final Map<String, WindowModel> windows = new ConcurrentHashMap<>();

    EditorWindows(SpellCrafterPlugin plugin, Path dataDir, Consumer<String> log) {
        this.plugin = plugin;
        this.pack = new DefaultPack(dataDir);
        this.log = log;
    }

    DefaultPack pack() {
        return pack;
    }

    /** Opens the window for a player. False when they aren't in a world. */
    boolean open(PlayerRef pr) {
        String id = pr.getUuid().toString();
        WindowModel m = windows.computeIfAbsent(id, k -> create(pr.getUsername()));
        return EditorPage.open(pr, m, s -> log.accept("[window] " + s), () -> afterEvent(id, pr.getUsername(), m));
    }

    /** A model for {@code author}: for the window, and for headless scenarios. */
    WindowModel create(String author) {
        return new WindowModel(context(author), library(), statBlockLang());
    }

    // ------------------------------------------------------------------------------------------ the context

    Editor.Context context(String author) {
        Content c = plugin.content();
        Frequencies f = c.options() == null ? Frequencies.NONE : c.options().frequencies();
        return new Editor.Context(templates(), catalog(), c.profile(), c.settings(), f, recipeRules(), Gta.ZONE_1, author,
                LocalDate.now().toString());
    }

    List<Template> templates() {
        Parsed<Json> j = Formats.json(plugin.resource("spellcrafter-1.0.templates.json"));
        if (j instanceof Parsed.Ok<Json> ok && Template.parse(ok.value()) instanceof Parsed.Ok<List<Template>> t) return t.value();
        log.accept("the editor's templates are unreadable");
        return List.of();
    }

    RecipeRules recipeRules() {
        Parsed<Json> j = Formats.json(plugin.resource("hytale.recipe_rules.json"));
        if (j instanceof Parsed.Ok<Json> ok && RecipeRules.parse(ok.value()) instanceof Parsed.Ok<RecipeRules> r) return r.value();
        return null;
    }

    Map<String, String> statBlockLang() {
        return StatBlock.parseLang(plugin.resource("spellcrafter.statblock.en-US.lang"));
    }

    /**
     * BuffEffects' catalog: every effect it lists, then the game's own ({@code hytale:<id>}), each as {@code describe}
     * gives it. An effect describe doesn't answer is left out.
     */
    List<Effect> catalog() {
        PayloadPort p = plugin.fakePayloads();
        List<String> ids = new ArrayList<>(p.list());
        try {
            for (String k : EntityEffect.getAssetMap().getAssetMap().keySet()) {
                if (!k.startsWith("BFX_")) ids.add("hytale:" + k);
            }
        } catch (RuntimeException e) {
            log.accept("the game's effects aren't listed: " + e);
        }
        List<Effect> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String id : ids) {
            if (!seen.add(id)) continue;
            Effect e = p.describe(id);
            if (e != null) out.add(e);
        }
        return out;
    }

    /** The loaded packs, read only, and the default mod's files, open for saving. */
    Map<String, Editor.ModFiles> library() {
        Map<String, Editor.ModFiles> out = new LinkedHashMap<>();
        Json.Obj spells = obj(plugin.resource("spellcrafter-1.0.spells.json"));
        Json.Obj vessels = obj(plugin.resource("spellcrafter-1.0.vessels.json"));
        String mod = spells != null && spells.get("mod") instanceof Json.Obj m && m.get("id") instanceof Json.Str s
                ? s.value() : "Hledjolf:SpellCrafter";
        out.put(mod, new Editor.ModFiles(spells, vessels, false));
        out.put(Editor.DEFAULT_MOD, pack.read());
        return out;
    }

    // ------------------------------------------------------------------------------------------ after an event

    /**
     * After each event: the files the window saved, then what it asked for. Files are written at once; the items for
     * new vessels are registered off the world thread (B6), then the content is reloaded and any request carried out.
     */
    void afterEvent(String playerId, String name, WindowModel m) {
        List<Editor.Write> writes;
        List<WindowModel.Request> requests;
        synchronized (m) {
            writes = m.takeWrites();
            requests = m.takeRequests();
        }
        if (writes.isEmpty() && requests.isEmpty()) return;
        if (!writes.isEmpty()) write(writes, name);
        Thread t = new Thread(() -> {
            if (!writes.isEmpty()) {
                plugin.reload();
                registerDefaultAssets();
            }
            for (WindowModel.Request r : requests) carryOut(playerId, r);
        }, "spellcrafter-editor-save");
        t.setDaemon(true);
        t.start();
    }

    /** Writes the window's files; vessels of the default mod get their item ids first. */
    void write(List<Editor.Write> writes, String who) {
        Map<String, String> schools = schools(writes);
        for (Editor.Write w : writes) {
            Editor.Write out = w;
            if (w.file().equals("vessels") && w.doc() instanceof Json.Obj d) {
                String mod = w.where().equals(Editor.DEFAULT_MOD) ? Editor.DEFAULT_MOD : w.where().replace("overrides/", "");
                out = new Editor.Write(w.where(), w.file(), VesselAssets.bind(d, mod, schools));
            }
            try {
                Path p = pack.write(out);
                plugin.actionLog().write("editor", "by", who, "file", out.where() + "/" + out.file(), "path", p.toString(),
                        "spells", spellIds(out));
            } catch (IOException e) {
                log.accept("couldn't write " + out.where() + "/" + out.file() + ": " + e);
            }
        }
    }

    /** spell id -> school, from the spells written alongside (the recipe rules' rule). */
    private Map<String, String> schools(List<Editor.Write> writes) {
        Map<String, String> out = new LinkedHashMap<>();
        RecipeRules rules = recipeRules();
        for (Editor.Write w : writes) {
            if (!w.file().equals("spells") || !(w.doc() instanceof Json.Obj d)) continue;
            if (!(Formats.pack(d, Formats.Options.DEFAULT) instanceof Parsed.Ok<Formats.Pack> ok)) continue;
            for (Spell s : ok.value().spells()) out.put(s.id(), rules == null ? "utility" : rules.school(s.categories()));
        }
        return out;
    }

    private static String spellIds(Editor.Write w) {
        List<String> ids = new ArrayList<>();
        if (w.doc() instanceof Json.Obj d && d.get(w.file().startsWith("spells") ? "spells" : "vessels") instanceof Json.Arr a) {
            for (Json j : a.items()) if (j instanceof Json.Obj o && o.get("id") instanceof Json.Str s) ids.add(s.value());
        }
        return String.join(",", ids);
    }

    /**
     * Registers the item of every loaded vessel the game doesn't have yet (the default pack's), and a bolt for each
     * projectile or touch spell without one. Off the world thread only.
     */
    int registerDefaultAssets() {
        Content c = plugin.content();
        RecipeRules rules = recipeRules();
        int made = 0;
        Map<String, String> names = new LinkedHashMap<>();
        for (Vessel v : c.vessels().values()) {
            String item = Content.hytaleItem(v);
            Spell s = c.spell(v.spell());
            if (item == null || s == null || Item.getAssetMap().getAsset(item) != null) continue;
            String school = rules == null ? "utility" : rules.school(s.categories());
            if (RuntimeAssets.item(item, VesselAssets.item(v, s, school, path -> plugin.hasResource(path)), log)) {
                made++;
                names.putAll(VesselAssets.lang(v, s));
            }
            HytaleCasting hc = SpellCrafterPlugin.casting();
            if (hc != null && !hc.hasBolt(s.id())) {
                Json.Obj bolt = VesselAssets.bolt(s, school);
                String id = VesselAssets.boltId(s.id());
                if (bolt != null && RuntimeAssets.bolt(id, bolt, log)) hc.addBolt(s.id(), id);
            }
        }
        if (!names.isEmpty()) RuntimeAssets.lang(names, log);
        if (made > 0) log.accept("registered " + made + " vessel items for saved spells");
        return made;
    }

    // ------------------------------------------------------------------------------------------ requests

    void carryOut(String playerId, WindowModel.Request r) {
        PlayerRef pr = Universe.get().getPlayer(UUID.fromString(playerId));
        if (pr == null) return;
        Content c = plugin.content();
        if (r instanceof WindowModel.GiveScroll g) {
            Vessel v = vesselOf(c, g.spellId());
            String item = v == null ? null : Content.hytaleItem(v);
            if (item == null || Item.getAssetMap().getAsset(item) == null) {
                say(pr, "No vessel item for " + g.spellId() + " yet: save it with a vessel ticked.");
                return;
            }
            onWorld(pr, (store, ref) -> {
                Player.giveItem(new ItemStack(item, 1), ref, store);
                say(pr, "Gave you a " + v.kind() + " of " + g.spellId() + (v.kind().equals("scroll") ? "." : " (it has no scroll)."));
                plugin.actionLog().write("give", "by", pr.getUsername(), "player", pr.getUsername(), "vessel", v.id(), "count", "1");
            });
        } else if (r instanceof WindowModel.CastOnDummy d) {
            Spell s = c.spell(d.spellId());
            if (s == null) {
                say(pr, d.spellId() + " isn't loaded yet.");
                return;
            }
            onWorld(pr, (store, ref) -> {
                Vector3d at = inFront(store, ref, 4);
                if (at == null) return;
                var res = NPCPlugin.get().spawnNPC(store, DUMMY, null, at, new Rotation3f());
                Ref<EntityStore> dummy = res == null ? null : res.left();
                if (dummy == null) {
                    say(pr, "Couldn't place a training dummy.");
                    return;
                }
                String what = SpellCrafterPlugin.casting().castNow(store, ref, s, dummy);
                say(pr, "Cast " + s.name() + " on a training dummy: " + what + ".");
            });
        }
    }

    /** The spell's scroll, else its first vessel. */
    static Vessel vesselOf(Content c, String spellId) {
        Vessel first = null;
        for (Vessel v : c.vessels().values()) {
            if (!v.spell().equals(spellId)) continue;
            if (v.kind().equals("scroll")) return v;
            if (first == null) first = v;
        }
        return first;
    }

    private static Vector3d inFront(Store<EntityStore> store, Ref<EntityStore> ref, double blocks) {
        TransformComponent t = store.getComponent(ref, TransformComponent.getComponentType());
        if (t == null) return null;
        Vector3d p = new Vector3d(t.getPosition());
        HeadRotation h = store.getComponent(ref, HeadRotation.getComponentType());
        Vector3d dir = h == null ? new Vector3d(0, 0, 1) : new Vector3d(h.getDirection());
        dir.y = 0;
        if (dir.lengthSquared() < 1e-6) dir.set(0, 0, 1);
        dir.normalize().mul(blocks);
        return p.add(dir);
    }

    private interface WorldTask {
        void run(Store<EntityStore> store, Ref<EntityStore> ref);
    }

    private static void onWorld(PlayerRef pr, WorldTask task) {
        Ref<EntityStore> ref = pr.getReference();
        if (ref == null || !ref.isValid()) return;
        World w = ref.getStore().getExternalData().getWorld();
        w.execute(() -> {
            Ref<EntityStore> now = pr.getReference();
            if (now == null || !now.isValid()) return;
            task.run(now.getStore(), now);
        });
    }

    private static void say(PlayerRef pr, String text) {
        pr.sendMessage(com.hypixel.hytale.server.core.Message.raw("[SpellCrafter] " + text));
    }

    private static Json.Obj obj(String text) {
        Parsed<Json> p = Formats.json(text);
        return p instanceof Parsed.Ok<Json> ok && ok.value() instanceof Json.Obj o ? o : null;
    }
}
