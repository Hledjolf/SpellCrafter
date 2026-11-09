package spellcrafter.hytale;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.bson.BsonDocument;

import com.hypixel.hytale.server.core.asset.AssetModule;
import com.hypixel.hytale.protocol.UpdateType;
import com.hypixel.hytale.protocol.packets.assets.UpdateRecipes;
import com.hypixel.hytale.protocol.packets.assets.UpdateTranslations;
import com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.modules.projectile.config.ProjectileConfig;

import spellcrafter.core.json.Json;
import spellcrafter.core.json.JsonWriter;

/**
 * Registers assets while the server runs (probe B6): an editor-saved vessel's item, with the recipe inside it, and its
 * spell's bolt. Decoded with the game's own codecs into SpellCrafter's asset pack. <b>Never call it on a world
 * thread:</b> loading assets waits on the worlds, so inside a world task it hangs that world (B6).
 */
public final class RuntimeAssets {
    private RuntimeAssets() {
    }

    static final String PACK_HINT = "SpellCrafter";

    /** The asset pack key SpellCrafter's pack registered under (else the base pack's). */
    static String packKey() {
        AssetModule mod = AssetModule.get();
        for (var p : mod.getAssetPacks()) if (p.getName().contains(PACK_HINT)) return p.getName();
        return mod.getBaseAssetPack().getName();
    }

    /** Registers (or replaces) an item. True when it's in the item map afterwards. */
    static boolean item(String id, Json.Obj doc, Consumer<String> log) {
        try {
            var st = Item.getAssetStore();
            Item it = st.decode(packKey(), id, BsonDocument.parse(JsonWriter.write(doc)));
            var res = st.loadAssets(packKey(), List.of(it));
            if (!res.getFailedToLoadKeys().isEmpty()) log.accept("item " + id + " failed to load: " + res.getFailedToLoadKeys());
            recipes(it, log);
        } catch (RuntimeException e) {
            log.accept("couldn't register item " + id + ": " + e);
        }
        return Item.getAssetMap().getAsset(id) != null;
    }

    /**
     * The item's own recipe, as a bench recipe. A pack's items get theirs generated while the pack loads; an item
     * loaded at run time doesn't, so an editor-saved scroll never reached the Arcanebench, even after a restart
     * (the author's playtest, 2026-11-08).
     */
    static void recipes(Item it, Consumer<String> log) {
        if (!it.hasRecipesToGenerate()) return;
        List<CraftingRecipe> rs = new ArrayList<>();
        it.collectRecipesToGenerate(rs);
        if (rs.isEmpty()) return;
        var res = CraftingRecipe.getAssetStore().loadAssets(packKey(), rs);
        if (!res.getFailedToLoadKeys().isEmpty()) log.accept("recipes of " + it.getId() + " failed to load: " + res.getFailedToLoadKeys());
        // The server's bench had it, but the author's client didn't list it after a save (fourth pass, 2026-11-08): send
        // the recipes to the clients as well, now and on joining, as the names are.
        Map<String, com.hypixel.hytale.protocol.CraftingRecipe> add = new java.util.LinkedHashMap<>();
        for (CraftingRecipe r : rs) {
            CraftingRecipe loaded = CraftingRecipe.getAssetMap().getAsset(r.getId());
            if (loaded == null) continue;
            try {
                add.put(loaded.getId(), loaded.toPacket(loaded.getId()));
            } catch (RuntimeException e) {
                log.accept("couldn't make the packet for recipe " + loaded.getId() + ": " + e);
            }
        }
        if (add.isEmpty()) return;
        RECIPES.putAll(add);
        int sent = 0;
        for (PlayerRef p : Universe.get().getPlayers()) if (sendRecipes(p, add, log)) sent++;
        log.accept("recipes of " + it.getId() + " (" + add.size() + ") sent to " + sent + " players");
    }

    /** Every run-time recipe, as sent to clients: sent to each client as it joins. */
    private static final Map<String, com.hypixel.hytale.protocol.CraftingRecipe> RECIPES = new java.util.concurrent.ConcurrentHashMap<>();

    private static boolean sendRecipes(PlayerRef p, Map<String, com.hypixel.hytale.protocol.CraftingRecipe> rs, Consumer<String> log) {
        try {
            p.getPacketHandler().write(new UpdateRecipes(UpdateType.AddOrUpdate, new java.util.HashMap<>(rs), new String[0]));
            return true;
        } catch (RuntimeException e) {
            log.accept("couldn't send recipes to " + p.getUsername() + ": " + e);
            return false;
        }
    }

    /** The recipes kept for clients (for scenarios). */
    public static java.util.Set<String> sentRecipes() {
        return java.util.Set.copyOf(RECIPES.keySet());
    }

    /** Whether a bench recipe makes the item. */
    public static boolean hasRecipe(String itemId) {
        for (CraftingRecipe r : CraftingRecipe.getAssetMap().getAssetMap().values()) {
            var out = r.getPrimaryOutput();
            if (out != null && itemId.equals(out.getItemId())) return true;
        }
        return false;
    }

    /** Registers (or replaces) a projectile config. True when it's in the map afterwards. */
    static boolean bolt(String id, Json.Obj doc, Consumer<String> log) {
        try {
            var st = ProjectileConfig.getAssetStore();
            ProjectileConfig c = st.decode(packKey(), id, BsonDocument.parse(JsonWriter.write(doc)));
            var res = st.loadAssets(packKey(), List.of(c));
            if (!res.getFailedToLoadKeys().isEmpty()) log.accept("bolt " + id + " failed to load: " + res.getFailedToLoadKeys());
        } catch (RuntimeException e) {
            log.accept("couldn't register bolt " + id + ": " + e);
        }
        return ProjectileConfig.getAssetMap().getAsset(id) != null;
    }

    /** Every run-time item's English name, by lang key: sent to each client as it joins. */
    private static final Map<String, String> NAMES = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * English names for run-time items. The server's message map is read-only (UnsupportedOperationException), so
     * the names go straight to the clients: to everyone online now, and to each player who joins later
     * ({@link #sendNames}). Without them an editor-saved scroll showed its lang key (the author's playtest, 2026-11-08).
     */
    static void lang(Map<String, String> entries, Consumer<String> log) {
        Map<String, String> add = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, String> e : entries.entrySet()) {
            String key = e.getKey().startsWith("spellcrafter.") ? e.getKey() : "spellcrafter." + e.getKey();
            add.put(key, e.getValue());
        }
        NAMES.putAll(add);
        int sent = 0;
        for (PlayerRef p : Universe.get().getPlayers()) if (send(p, add, log)) sent++;
        log.accept("names of " + add.size() + " run-time items sent to " + sent + " players");
    }

    /** Sends every run-time name to one player (on joining). */
    static void sendNames(PlayerRef p, Consumer<String> log) {
        if (!NAMES.isEmpty()) send(p, Map.copyOf(NAMES), log);
        if (!RECIPES.isEmpty()) sendRecipes(p, Map.copyOf(RECIPES), log);
    }

    private static boolean send(PlayerRef p, Map<String, String> names, Consumer<String> log) {
        try {
            p.getPacketHandler().write(new UpdateTranslations(UpdateType.AddOrUpdate, new java.util.HashMap<>(names)));
            return true;
        } catch (RuntimeException e) {
            log.accept("couldn't send item names to " + p.getUsername() + ": " + e);
            return false;
        }
    }

    /** The names kept for clients (for scenarios). */
    public static Map<String, String> names() {
        return Map.copyOf(NAMES);
    }
}
