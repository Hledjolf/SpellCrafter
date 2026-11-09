"""Generates the Hytale plugin's asset pack from the 1.0 pack (release-1.0.md sections 2 and 4).

    python -I make_assets.py --root <SpellCrafter folder> --assets <Assets.zip> --out <dir>

Writes, under --out (a build folder, never committed):
  spellcrafter/          the spells and vessels files the plugin loads, and hytale-assets.json (spell -> bolt config)
  Effects/               SpellCrafter's BuffEffects sidecar (BuffEffects finds Effects/<Group>_<Name>.effects.json)
  Server/Item/Items/SpellCrafter/         one item per vessel, with its recipe and use interactions
  Server/ProjectileConfigs/SpellCrafter/  one bolt per projectile or touch spell
  Server/Languages/en-US/spellcrafter.lang
  Common/Items/SpellCrafter/, Common/Icons/ItemsGenerated/   recoloured vanilla textures and icons

The textures are hue-shifted copies of vanilla art read from the installed game. They ship in the jar and never go in
a repository (release-1.0.md section 7).
"""
import argparse
import colorsys
import io
import json
import os
import shutil
import sys
import zipfile

from PIL import Image

# The BuffEffects sidecar's format_version in the jar: None keeps the sample's (see the copy below).
SIDECAR_VERSION = "1.0"
# Light radius per effect id, overriding the sample (see the sidecar copy below).
LIGHT_RADIUS = {"magelight": 4}

# School -> colour (release-1.0.md section 2: the essence and crystal colour).
SCHOOL_COLOUR = {
    "fire": "Red", "frost": "Blue", "lightning": "Yellow", "poison": "Purple", "control": "Purple",
    "healing": "White", "abjuration": "Cyan", "alteration": "Cyan", "utility": "Blue",
    # SC-D027: evocation has lightning's colour, shadow poison's and holy healing's.
    "evocation": "Yellow", "shadow": "Purple", "holy": "White",
}
HUE = {"Red": 2, "Blue": 212, "Yellow": 50, "Purple": 282, "Cyan": 186}

# Bolt models by school (vanilla projectile models); touch spells use the invisible one.
BOLT_MODEL = {"fire": "Fireball", "frost": "Ice_Bolt", "lightning": "Ability_ChargedShot_Bolt",
              "poison": "Scarak_Seeker_Spitball", "control": "Ice_Ball"}
BOLT_SOUND = {"frost": "SFX_Staff_Ice_Shoot", "lightning": "SFX_Ability_Charged_Shot_Launch"}
TOUCH_SPEED = 15

KIND = {
    "wand": {
        "label": "Wand", "model": "Items/Weapons/Staff/Wood.blockymodel",
        "texture_from": "Common/Items/Weapons/Staff/Wood_default.png",
        "icon_from": "Common/Icons/ItemsGenerated/Weapon_Staff_Wood.png",
        "icon_props": {"Scale": 0.3, "Translation": [-61, -61], "Rotation": [50, 90, 0]},
        "animations": "Staff", "categories": ["Items.Weapons"], "max_stack": 1, "sound": "ISS_Weapons_Wood",
    },
    "scroll": {
        "label": "Scroll", "model": "Blocks/Miscellaneous/Scrolls.blockymodel",
        "texture_from": "Common/Blocks/Miscellaneous/Scrolls_Texture.png",
        "icon_from": "Common/Icons/ItemsGenerated/Deco_Scroll.png",
        "icon_props": {"Scale": 0.735, "Translation": [0, -7.495], "Rotation": [22.5, 45, 22.5]},
        "animations": "Item", "categories": ["Items.Tools"], "max_stack": 16, "sound": "ISS_Blocks_Wood",
    },
    "potion": {
        "label": "Potion", "model": "Items/Consumables/Potions/Potion_Normal.blockymodel",
        "texture_from": None,  # vanilla has every colour already
        "icon_from": "Common/Icons/ItemsGenerated/Potion_Health.png",
        "icon_props": {"Scale": 0.9, "Rotation": [22.5, 45, 22.5], "Translation": [0, -9]},
        "animations": {"Parent": "Item", "Animations": {"Consume": {
            "FirstPerson": "Characters/Animations/Items/Main_Handed/Item/Attacks/Drink/Drink_FPS.blockyanim",
            "ThirdPerson": "Characters/Animations/Items/Main_Handed/Item/Attacks/Drink/Drink.blockyanim",
            "Speed": 1, "Looping": True}}},
        "categories": ["Items.Potions"], "max_stack": 10, "sound": None,
    },
}

# The plugin registers these interaction types (spellcrafter.hytale.SpellCrafterPlugin).
BEGIN, COMPLETE, DRINK, HIT, MISS = ("SpellCrafter_Cast_Begin", "SpellCrafter_Cast_Complete", "SpellCrafter_Drink",
                                     "SpellCrafter_Bolt_Hit", "SpellCrafter_Bolt_Miss")
DRINK_SECONDS = 1.0

LANG = {
    "cast.begin": "You begin casting {spell}.",
    "cast.interrupted": "Your {spell} is interrupted.",
    "cast.fizzle": "Your {spell} fizzles: {reason}",
    "cast.no_mana": "Not enough mana for {spell} ({cost} needed).",
    "cast.unavailable": "{spell} is unavailable: {reason}",
    "cast.cooldown": "{spell} isn't ready yet ({seconds} s).",
    "cast.refunded": "{spell} had no effect, so nothing was spent.",
    "cast.points": "{caster} points at you and mutters.",
    "cast.debug": "[debug] {spell}: seed {seed}, {entities} in reach, {applied} applied; riders: {report}",
    # /spell (commands-settings.md sections 1-2).
    "cmd.starting": "SpellCrafter is still starting up.",
    "cmd.usage": "Usage: {usage}",
    "cmd.no_permission": "You need {node} for that.",
    "cmd.players_only": "Only a player in a world can do that.",
    "cmd.no_spell": "No spell called \"{spell}\". Try /spell list.",
    "cmd.no_vessel": "No vessel called \"{vessel}\".",
    "cmd.no_player": "No player called \"{player}\" is online.",
    "cmd.list.header": "{count} spells ({filter}):",
    "cmd.list.row": "{name} ({id}): {school}, {mana} mana; {vessels}",
    "cmd.list.none": "No spell matches \"{filter}\".",
    "cmd.info.text": "{text}",
    "cmd.info.name": "{name} ({id})",
    "cmd.info.school": "School: {school}. Categories: {categories}",
    "cmd.info.cost": "Mana {mana}, cast {cast} s, cooldown {cooldown} s",
    "cmd.info.delivery": "Delivery: {kind}, range {range}, burst {burst}",
    "cmd.info.payload": "- {id}: {effect} on {target}, {chance}% chance, pick {pick}",
    "cmd.info.reroll": "- {id}: in {pick}, weight {weight}: roll {rolls} more",
    "cmd.editor.later": "The spell editor's window comes in a later version of SpellCrafter.",
    "cmd.give.done": "Gave {count} {vessel} to {player}.",
    "cmd.cast.done": "Cast {spell} at {target}: {result}.",
    "cmd.cast.no_target": "Look at something to cast {spell} at, or name a player.",
    "cmd.cooldowns.header": "Cooldowns for {player}:",
    "cmd.cooldowns.row": "{spell}: {seconds} s",
    "cmd.cooldowns.none": "{player} has no spell cooling down.",
    "cmd.debug.on": "Debug on: your casts print their rider report, seed and rolls.",
    "cmd.debug.off": "Debug off.",
    "cmd.reload.done": "Reloaded: {spells} spells, {vessels} vessels, {problems} problems. Changed: {changes}",
}


def pascal(local):
    return "".join(p[:1].upper() + p[1:] for p in local.split("_"))


def num(x):
    return int(x) if float(x) == int(x) else x


def shift(img, colour):
    """Recolours an RGBA image towards a school colour, keeping its shading."""
    img = img.convert("RGBA")
    px = img.load()
    for y in range(img.height):
        for x in range(img.width):
            r, g, b, a = px[x, y]
            if a == 0:
                continue
            h, s, v = colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)
            if colour == "White":
                s, v = s * 0.15, min(1.0, v * 0.6 + 0.4)
            else:
                h, s = HUE[colour] / 360, max(s, 0.45) if s > 0.05 else s
            r2, g2, b2 = colorsys.hsv_to_rgb(h, s, v)
            px[x, y] = (round(r2 * 255), round(g2 * 255), round(b2 * 255), a)
    return img


def school_of(vessel):
    return vessel["bindings"]["hytale"]["hue_from_school"]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", required=True)
    ap.add_argument("--assets", required=True)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    rel = os.path.join(a.root, "adapters", "hytale", "pack")
    spells_file = os.path.join(rel, "spellcrafter-1.0.spells.json")
    vessels_file = os.path.join(rel, "spellcrafter-1.0.vessels.json")
    effects_file = os.path.join(rel, "spellcrafter.effects.json")
    with open(spells_file, encoding="utf-8") as f:
        spells = {s["id"]: s for s in json.load(f)["spells"]}
    with open(vessels_file, encoding="utf-8") as f:
        vessels = json.load(f)["vessels"]
    if not os.path.isfile(a.assets):
        sys.exit(f"Assets.zip not found: {a.assets} (pass -PhytaleAssetsZip=...)")

    if os.path.isdir(a.out):
        shutil.rmtree(a.out)
    out = lambda *p: os.path.join(a.out, *p)

    def write_json(path, doc):
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8", newline="\n") as f:
            json.dump(doc, f, indent=2, ensure_ascii=False)
            f.write("\n")

    # The data the plugin loads.
    os.makedirs(out("spellcrafter"), exist_ok=True)
    shutil.copyfile(spells_file, out("spellcrafter", "spellcrafter-1.0.spells.json"))
    shutil.copyfile(vessels_file, out("spellcrafter", "spellcrafter-1.0.vessels.json"))
    os.makedirs(out("Effects"), exist_ok=True)
    # BuffEffects' effects contract is 1.0 (BFX-D034, BFX-023), and its 1.0 reads "0.5" as the same format. Kept at the
    # sample's 0.5 for now: the BuffEffects dev jar of 2026-11-08 18:47 refuses "1.0" (it reads only 0.5), which
    # unloaded every SpellCrafter effect. SIDECAR_VERSION switches it once BuffEffects' 1.0 jar is in.
    with open(effects_file, encoding="utf-8") as f:
        sidecar = json.load(f)
    if SIDECAR_VERSION:
        sidecar["format_version"] = SIDECAR_VERSION
    # Light radii for 1.0 (2026-11-08): Hytale's light carries far past its radius (BuffEffects' finding), and
    # Magelight at 12 was far too bright in the playtest. The author chose 4. Desktop's sample follows (SC-024).
    for e in sidecar.get("effects", []):
        if e.get("id") in LIGHT_RADIUS:
            for part in e.get("parts", []):
                if part.get("type") == "light":
                    part["radius"] = LIGHT_RADIUS[e["id"]]
    write_json(out("Effects", "Hledjolf_SpellCrafter.effects.json"), sidecar)

    lang = dict(LANG)
    bolts = {}
    textures = set()
    zf = zipfile.ZipFile(a.assets)

    # Bolts: one per projectile or touch spell that a vessel holds.
    for sid in sorted({v["spell"] for v in vessels}):
        s = spells[sid]
        kind = s["delivery"]["kind"]
        if kind not in ("projectile", "touch"):
            continue
        school = next(school_of(v) for v in vessels if v["spell"] == sid)
        cfg_id = "SpellCrafter_Bolt_" + pascal(sid.split("/")[-1])
        hb = (s.get("bindings") or {}).get("hytale") or {}
        model = "Invisible_Projectile" if kind == "touch" else hb.get("projectile", BOLT_MODEL.get(school, "Ice_Ball"))
        speed = s["delivery"].get("params", {}).get("speed", TOUCH_SPEED) if kind == "projectile" else TOUCH_SPEED
        cfg = {
            "Model": model,
            "LaunchForce": num(speed),
            "Physics": {"Type": "Standard", "Gravity": 0, "TerminalVelocityAir": 50, "TerminalVelocityWater": 15,
                        "RotationMode": "VelocityDamped", "Bounciness": 0.0, "SticksVertically": True},
            "Interactions": {
                "ProjectileHit": {"Cooldown": {"Cooldown": 0},
                                  "Interactions": [{"Type": HIT}]},
                "ProjectileMiss": {"Cooldown": {"Cooldown": 0},
                                   "Interactions": [{"Type": MISS}]},
            },
        }
        sound = hb.get("cast_sound", BOLT_SOUND.get(school))
        if sound and kind == "projectile":
            cfg["LaunchWorldSoundEventId"] = sound
        write_json(out("Server", "ProjectileConfigs", "SpellCrafter", cfg_id + ".json"), cfg)
        bolts[sid] = cfg_id

    # Vessels.
    for v in vessels:
        s = spells[v["spell"]]
        k = KIND[v["kind"]]
        item = v["bindings"]["hytale"]["item"]
        colour = SCHOOL_COLOUR[school_of(v)]
        icon = f"Icons/ItemsGenerated/SpellCrafter_{k['label']}_{colour}.png"
        textures.add((v["kind"], colour))
        texture = (f"Items/Consumables/Potions/Potion_Textures/{colour}.png" if v["kind"] == "potion"
                   else f"Items/SpellCrafter/{k['label']}_{colour}.png")
        doc = {
            "TranslationProperties": {"Name": f"spellcrafter.items.{item}.name",
                                      "Description": f"spellcrafter.items.{item}.description"},
            "Categories": k["categories"],
            "Icon": icon,
            "IconProperties": k["icon_props"],
            "Quality": "Uncommon",
            "Model": k["model"],
            "Texture": texture,
            "PlayerAnimationsId": k["animations"],
            "MaxStack": k["max_stack"],
            "Interactions": {"Secondary": secondary(v["kind"], s)},
        }
        if k["sound"]:
            doc["ItemSoundSetId"] = k["sound"]
        r = v.get("recipe")
        if r:
            req = {"Id": r["bench"], "Type": "Crafting"}
            if r.get("category"):
                req["Categories"] = [r["category"]]
            doc["Recipe"] = {"Input": [{"ItemId": i["item"].split(":", 1)[1], "Quantity": i["count"]}
                                       for i in r["inputs"]],
                             "OutputQuantity": r["output"], "BenchRequirement": [req], "TimeSeconds": 2}
        write_json(out("Server", "Item", "Items", "SpellCrafter", item + ".json"), doc)
        lang[f"items.{item}.name"] = f"{k['label']} of {s['name']}"
        lang[f"items.{item}.description"] = describe(v, s)

    # Textures and icons, recoloured from vanilla.
    for kind, colour in sorted(textures):
        k = KIND[kind]
        if k["texture_from"]:
            img = shift(Image.open(io.BytesIO(zf.read(k["texture_from"]))), colour)
            path = out("Common", "Items", "SpellCrafter", f"{k['label']}_{colour}.png")
            os.makedirs(os.path.dirname(path), exist_ok=True)
            img.save(path)
        img = shift(Image.open(io.BytesIO(zf.read(k["icon_from"]))), colour)
        path = out("Common", "Icons", "ItemsGenerated", f"SpellCrafter_{k['label']}_{colour}.png")
        os.makedirs(os.path.dirname(path), exist_ok=True)
        img.save(path)

    write_json(out("spellcrafter", "hytale-assets.json"), {"bolts": bolts})
    os.makedirs(out("Server", "Languages", "en-US"), exist_ok=True)
    with open(out("Server", "Languages", "en-US", "spellcrafter.lang"), "w", encoding="utf-8", newline="\n") as f:
        for key in sorted(lang):
            f.write(f"{key} = {lang[key]}\n")
    print(f"{len(vessels)} vessels, {len(bolts)} bolts, {len(textures)} textures -> {a.out}")


def secondary(kind, spell):
    """The use (right click). Scrolls and wands: begin, wait out the cast time, complete. Potions: drink."""
    if kind == "potion":
        return {"RequireNewClick": True, "Interactions": [{
            "Type": "Simple", "RunTime": DRINK_SECONDS,
            "Effects": {"ItemAnimationId": "Consume", "ClearAnimationOnFinish": True},
            "Next": {"Type": DRINK}}]}
    complete = {"Type": COMPLETE}
    wait = {"Type": "Simple", "RunTime": num(spell["cast"]["time"]), "Next": complete}
    if kind == "wand":
        wait["Effects"] = {"ItemAnimationId": "CastSummonCharging"}
        complete["Next"] = {"Type": "Simple", "RunTime": 0.25, "Effects": {"ItemAnimationId": "CastSummonCharged"}}
    return {"RequireNewClick": True, "Interactions": [{"Type": BEGIN, "Next": wait, "Failed": {"Type": "Simple"}}]}


def describe(v, s):
    d = s["delivery"]["kind"]
    how = {"self": "on yourself", "touch": "by touch", "projectile": "as a bolt"}.get(d, d)
    parts = [f"Casts {s['name']} {how}."]
    if v["kind"] == "wand":
        parts.append(f"{s['cost']['mana']} mana a cast, {num(s['cast']['cooldown'])} s cooldown.")
    elif v["kind"] == "scroll":
        parts.append("Used up when the spell is cast.")
    else:
        parts.append("Drink it.")
    if s["cast"]["time"] and v["kind"] != "potion":
        parts.append(f"Takes {num(s['cast']['time'])} s to cast; moving interrupts it.")
    return " ".join(parts)


if __name__ == "__main__":
    sys.exit(main())
