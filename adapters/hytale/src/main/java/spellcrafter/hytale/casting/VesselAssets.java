package spellcrafter.hytale.casting;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import spellcrafter.core.json.Json;
import spellcrafter.core.json.JsonWriter;
import spellcrafter.core.model.Model.Recipe;
import spellcrafter.core.model.Model.RecipeInput;
import spellcrafter.core.model.Model.Spell;
import spellcrafter.core.model.Model.Vessel;
import spellcrafter.core.num.Milli;

/**
 * The item and bolt assets of a vessel the editor saved (window.md section 4, "Try it"; probe B6): the same JSON that
 * {@code make_assets.py} writes for the 1.0 vessels at build time, built here at run time so a saved spell can be
 * crafted, given and cast without a restart. Game-free; the plugin decodes and registers what this returns.
 *
 * <p>Art is reused, never generated at run time: the recoloured icon and texture of the vessel's kind in its school's
 * colour when the jar has them, else the kind's art in another colour the jar has.
 */
public final class VesselAssets {
    private VesselAssets() {
    }

    /** School to colour (release-1.0.md section 2), as make_assets.py's SCHOOL_COLOUR. */
    public static final Map<String, String> SCHOOL_COLOUR = Map.of("fire", "Red", "frost", "Blue", "lightning", "Yellow",
            "poison", "Purple", "control", "Purple", "healing", "White", "abjuration", "Cyan", "alteration", "Cyan",
            "utility", "Blue");
    static final List<String> COLOURS = List.of("Red", "Blue", "Yellow", "Purple", "White", "Cyan");
    /** Bolt models by school; a touch is invisible (release-1.0.md 4a). */
    static final Map<String, String> BOLT_MODEL = Map.of("fire", "Fireball", "frost", "Ice_Bolt", "lightning",
            "Ability_ChargedShot_Bolt", "poison", "Scarak_Seeker_Spitball", "control", "Ice_Ball");
    static final Map<String, String> BOLT_SOUND = Map.of("frost", "SFX_Staff_Ice_Shoot", "lightning",
            "SFX_Ability_Charged_Shot_Launch");
    static final long TOUCH_SPEED = 15_000;

    /** The item id an editor vessel gets: {@code SpellCrafter_Default_Scroll_FireRain}. */
    public static String itemId(String mod, String kind, String spellId) {
        String local = spellId.substring(spellId.lastIndexOf('/') + 1);
        String m = mod.equals("default") ? "Default" : pascal(mod.replace(':', '_').replace('/', '_'));
        return "SpellCrafter_" + m + "_" + label(kind) + "_" + pascal(local);
    }

    /** The bolt config id a spell gets: {@code SpellCrafter_Bolt_Default_FireRain}. */
    public static String boltId(String spellId) {
        String mod = spellId.substring(0, spellId.lastIndexOf('/'));
        String local = spellId.substring(spellId.lastIndexOf('/') + 1);
        return "SpellCrafter_Bolt_" + (mod.equals("default") ? "Default" : pascal(mod.replace(':', '_'))) + "_" + pascal(local);
    }

    /**
     * A vessel's item JSON. {@code hasArt} answers whether the jar holds a resource path (Common/...), so a colour
     * without art falls back to one with it.
     */
    public static Json.Obj item(Vessel v, Spell s, String school, Predicate<String> hasArt) {
        String kind = v.kind();
        String colour = colour(kind, SCHOOL_COLOUR.getOrDefault(school, "Blue"), hasArt);
        String item = itemIdOf(v);
        Map<String, Json> doc = new LinkedHashMap<>();
        doc.put("TranslationProperties", JsonWriter.obj("Name", new Json.Str("spellcrafter.items." + item + ".name"),
                "Description", new Json.Str("spellcrafter.items." + item + ".description")));
        doc.put("Categories", strs(switch (kind) {
            case "wand" -> List.of("Items.Weapons");
            case "potion" -> List.of("Items.Potions");
            default -> List.of("Items.Tools");
        }));
        doc.put("Icon", new Json.Str("Icons/ItemsGenerated/SpellCrafter_" + label(kind) + "_" + colour + ".png"));
        doc.put("IconProperties", switch (kind) {
            case "wand" -> JsonWriter.obj("Scale", num("0.3"), "Translation", nums("-61", "-61"), "Rotation", nums("50", "90", "0"));
            case "potion" -> JsonWriter.obj("Scale", num("0.9"), "Rotation", nums("22.5", "45", "22.5"), "Translation", nums("0", "-9"));
            default -> JsonWriter.obj("Scale", num("0.735"), "Translation", nums("0", "-7.495"), "Rotation", nums("22.5", "45", "22.5"));
        });
        doc.put("Quality", new Json.Str("Uncommon"));
        doc.put("Model", new Json.Str(switch (kind) {
            case "wand" -> "Items/Weapons/Staff/Wood.blockymodel";
            case "potion" -> "Items/Consumables/Potions/Potion_Normal.blockymodel";
            default -> "Blocks/Miscellaneous/Scrolls.blockymodel";
        }));
        doc.put("Texture", new Json.Str(kind.equals("potion") ? "Items/Consumables/Potions/Potion_Textures/" + colour + ".png"
                : "Items/SpellCrafter/" + label(kind) + "_" + colour + ".png"));
        doc.put("PlayerAnimationsId", switch (kind) {
            case "wand" -> new Json.Str("Staff");
            case "potion" -> JsonWriter.obj("Parent", new Json.Str("Item"), "Animations", JsonWriter.obj("Consume", JsonWriter.obj(
                    "FirstPerson", new Json.Str("Characters/Animations/Items/Main_Handed/Item/Attacks/Drink/Drink_FPS.blockyanim"),
                    "ThirdPerson", new Json.Str("Characters/Animations/Items/Main_Handed/Item/Attacks/Drink/Drink.blockyanim"),
                    "Speed", num("1"), "Looping", new Json.Bool(true))));
            default -> new Json.Str("Item");
        });
        doc.put("MaxStack", num(switch (kind) {
            case "wand" -> "1";
            case "potion" -> "10";
            default -> "16";
        }));
        doc.put("Interactions", JsonWriter.obj("Secondary", secondary(kind, s)));
        if (!kind.equals("potion")) doc.put("ItemSoundSetId", new Json.Str(kind.equals("wand") ? "ISS_Weapons_Wood" : "ISS_Blocks_Wood"));
        Recipe r = v.recipe();
        if (r != null) {
            List<Json> in = new ArrayList<>();
            for (RecipeInput i : r.inputs()) {
                String id = i.item().contains(":") ? i.item().substring(i.item().indexOf(':') + 1) : i.item();
                in.add(JsonWriter.obj("ItemId", new Json.Str(id), "Quantity", num(Long.toString(i.count()))));
            }
            Map<String, Json> req = new LinkedHashMap<>();
            req.put("Id", new Json.Str(r.bench()));
            req.put("Type", new Json.Str("Crafting"));
            if (r.category() != null) req.put("Categories", strs(List.of(r.category())));
            doc.put("Recipe", JsonWriter.obj("Input", new Json.Arr(in), "OutputQuantity", num(Long.toString(r.output())),
                    "BenchRequirement", new Json.Arr(List.of(new Json.Obj(req))), "TimeSeconds", num("2")));
        }
        return new Json.Obj(doc);
    }

    /** The use (right click), as make_assets.py's secondary(): begin, wait out the cast time, complete; or drink. */
    static Json.Obj secondary(String kind, Spell s) {
        if (kind.equals("potion")) {
            return JsonWriter.obj("RequireNewClick", new Json.Bool(true), "Interactions", new Json.Arr(List.of(JsonWriter.obj(
                    "Type", new Json.Str("Simple"), "RunTime", num("1"),
                    "Effects", JsonWriter.obj("ItemAnimationId", new Json.Str("Consume"), "ClearAnimationOnFinish", new Json.Bool(true)),
                    "Next", JsonWriter.obj("Type", new Json.Str("SpellCrafter_Drink"))))));
        }
        Map<String, Json> complete = new LinkedHashMap<>();
        complete.put("Type", new Json.Str("SpellCrafter_Cast_Complete"));
        Map<String, Json> wait = new LinkedHashMap<>();
        wait.put("Type", new Json.Str("Simple"));
        wait.put("RunTime", num(Milli.format(s.castTime())));
        if (kind.equals("wand")) {
            wait.put("Effects", JsonWriter.obj("ItemAnimationId", new Json.Str("CastSummonCharging")));
            complete.put("Next", JsonWriter.obj("Type", new Json.Str("Simple"), "RunTime", num("0.25"),
                    "Effects", JsonWriter.obj("ItemAnimationId", new Json.Str("CastSummonCharged"))));
        }
        wait.put("Next", new Json.Obj(complete));
        return JsonWriter.obj("RequireNewClick", new Json.Bool(true), "Interactions", new Json.Arr(List.of(JsonWriter.obj(
                "Type", new Json.Str("SpellCrafter_Cast_Begin"), "Next", new Json.Obj(wait),
                "Failed", JsonWriter.obj("Type", new Json.Str("Simple"))))));
    }

    /** A projectile or touch spell's bolt (make_assets.py's bolt configs); null for other deliveries. */
    public static Json.Obj bolt(Spell s, String school) {
        String kind = s.delivery().kind();
        if (!kind.equals("projectile") && !kind.equals("touch")) return null;
        String model = kind.equals("touch") ? "Invisible_Projectile" : BOLT_MODEL.getOrDefault(school, "Ice_Ball");
        Long sp = s.delivery().params().get("speed");
        long speed = kind.equals("projectile") && sp != null ? sp : TOUCH_SPEED;
        Map<String, Json> cfg = new LinkedHashMap<>();
        cfg.put("Model", new Json.Str(model));
        cfg.put("LaunchForce", num(Milli.format(speed)));
        cfg.put("Physics", JsonWriter.obj("Type", new Json.Str("Standard"), "Gravity", num("0"), "TerminalVelocityAir", num("50"),
                "TerminalVelocityWater", num("15"), "RotationMode", new Json.Str("VelocityDamped"), "Bounciness", num("0.0"),
                "SticksVertically", new Json.Bool(true)));
        cfg.put("Interactions", JsonWriter.obj(
                "ProjectileHit", JsonWriter.obj("Cooldown", JsonWriter.obj("Cooldown", num("0")), "Interactions", new Json.Arr(List.of(
                        // No RemoveEntity on "User": the client ran it on the player (BFX-D037). HytaleCasting removes
                        // the bolt on the server instead.
                        JsonWriter.obj("Type", new Json.Str("SpellCrafter_Bolt_Hit"))))),
                "ProjectileMiss", JsonWriter.obj("Cooldown", JsonWriter.obj("Cooldown", num("0")), "Interactions", new Json.Arr(List.of(
                        JsonWriter.obj("Type", new Json.Str("SpellCrafter_Bolt_Miss")))))));
        String sound = BOLT_SOUND.get(school);
        if (sound != null && kind.equals("projectile")) cfg.put("LaunchWorldSoundEventId", new Json.Str(sound));
        return new Json.Obj(cfg);
    }

    /**
     * Gives every vessel in a {@code spellcrafter.vessels} document that has no Hytale item one: {@code bindings.hytale}
     * with the item id and the school (as the 1.0 pack's vessels have). Returns the document, changed or not.
     */
    public static Json.Obj bind(Json.Obj vesselsDoc, String mod, Map<String, String> schoolBySpell) {
        if (!(vesselsDoc.get("vessels") instanceof Json.Arr arr)) return vesselsDoc;
        List<Json> out = new ArrayList<>();
        boolean changed = false;
        for (Json j : arr.items()) {
            if (!(j instanceof Json.Obj v) || hasItem(v)) {
                out.add(j);
                continue;
            }
            String spell = v.get("spell") instanceof Json.Str ss ? ss.value() : "";
            String kind = v.get("kind") instanceof Json.Str ks ? ks.value() : "scroll";
            Map<String, Json> m = new LinkedHashMap<>(v.members());
            m.put("bindings", JsonWriter.obj("hytale", JsonWriter.obj("item", new Json.Str(itemId(mod, kind, spell)),
                    "hue_from_school", new Json.Str(schoolBySpell.getOrDefault(spell, "utility")))));
            out.add(new Json.Obj(m));
            changed = true;
        }
        if (!changed) return vesselsDoc;
        Map<String, Json> d = new LinkedHashMap<>(vesselsDoc.members());
        d.put("vessels", new Json.Arr(out));
        return new Json.Obj(d);
    }

    /** The item's English name and description, for the lang file (make_assets.py's describe). */
    public static Map<String, String> lang(Vessel v, Spell s) {
        String item = itemIdOf(v);
        String how = switch (s.delivery().kind()) {
            case "self" -> "on yourself";
            case "touch" -> "by touch";
            case "projectile" -> "as a bolt";
            default -> s.delivery().kind();
        };
        StringBuilder d = new StringBuilder("Casts " + s.name() + " " + how + ".");
        if (v.kind().equals("wand")) d.append(" ").append(s.costMana()).append(" mana a cast, ").append(Milli.format(s.cooldown())).append(" s cooldown.");
        else if (v.kind().equals("scroll")) d.append(" Used up when the spell is cast.");
        else d.append(" Drink it.");
        if (s.castTime() > 0 && !v.kind().equals("potion")) d.append(" Takes ").append(Milli.format(s.castTime())).append(" s to cast; moving interrupts it.");
        Map<String, String> out = new LinkedHashMap<>();
        out.put("spellcrafter.items." + item + ".name", label(v.kind()) + " of " + s.name());
        out.put("spellcrafter.items." + item + ".description", d.toString());
        return out;
    }

    static String itemIdOf(Vessel v) {
        String item = Content.hytaleItem(v);
        return item != null ? item : itemId(v.id().substring(0, v.id().lastIndexOf('/')), v.kind(), v.spell());
    }

    private static boolean hasItem(Json.Obj v) {
        return v.get("bindings") instanceof Json.Obj b && b.get("hytale") instanceof Json.Obj h && h.get("item") instanceof Json.Str;
    }

    /** The school's colour when the jar has this kind's art in it, else the first colour it has. */
    static String colour(String kind, String wanted, Predicate<String> hasArt) {
        if (hasArt.test(iconPath(kind, wanted))) return wanted;
        for (String c : COLOURS) if (hasArt.test(iconPath(kind, c))) return c;
        return wanted;
    }

    static String iconPath(String kind, String colour) {
        return "Common/Icons/ItemsGenerated/SpellCrafter_" + label(kind) + "_" + colour + ".png";
    }

    static String label(String kind) {
        return switch (kind) {
            case "wand" -> "Wand";
            case "potion" -> "Potion";
            default -> "Scroll";
        };
    }

    static String pascal(String local) {
        StringBuilder b = new StringBuilder();
        for (String p : local.split("[_\\-]")) if (!p.isEmpty()) b.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        return b.toString();
    }

    private static Json num(String text) {
        return new Json.Num(text);
    }

    private static Json nums(String... texts) {
        List<Json> out = new ArrayList<>();
        for (String t : texts) out.add(new Json.Num(t));
        return new Json.Arr(out);
    }

    private static Json strs(List<String> v) {
        List<Json> out = new ArrayList<>();
        for (String s : v) out.add(new Json.Str(s));
        return new Json.Arr(out);
    }
}
