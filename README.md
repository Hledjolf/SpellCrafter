# SpellCrafter

**Spells you can read, craft and build, for Hytale.**

SpellCrafter adds magic as data: every spell is a short, readable definition, rated on the same power curve as Hytale's own gear. Craft scrolls, potions and wands at the Arcanebench and the Alchemy bench, cast them with a cast time and a cooldown, and watch the weather and the time of day change what they do.

## In 1.0

- **34 spells**, from a Lesser Flame Bolt for your first night to a Prismatic Spray for zone 4: bolts, heals, wards, battle fury, stuns, snares, curses, a Vampiric Touch, a Magelight for dark caves, and a Ghost Form that turns you see-through and lets you slip past mobs.
- **New damage types:** shadow and holy, in Shadow Bolt and Smite, each with its own damage colour.
- **Scrolls, potions and wands:** 60 in all. Scrolls and potions are used up; wands draw on your mana. Every recipe is tiered to the zone its spell belongs in.
- **Spells that react to the world.** Shock is stronger in a storm; healing is stronger at dawn.
- **Several effects in one spell.** A Fireball strikes its target and sets everyone around it burning. Prismatic Spray hits each creature in its burst with a ray at random.
- **A spell editor window** for server operators and modders (`/spell editor`). Start from a template, and watch a live stat block, the grade, the zone and the mana as you build. Roll a spell for ten creatures to see its chances, aim it at a zone, and save it to your own spell pack. A saved spell gets its own scroll and wand, with recipes at the Arcanebench.
- **Balanced against Hytale's own progression,** with caps the server owner sets, and a power check for every spell from any author.
- **Settings through Mod Settings Manager:** caps, frequencies, PvP behaviour, and overrides for requirements and material components.
- **Commands:** `/spell list`, `info`, `give`, `cast`, `cooldowns`, `debug`, `reload` and `editor`, each in CommandDeck's browser with help.

## Requirements

- **BuffEffects 1.0** or later, which carries out the spells' effects.
- **CommandDeck 1.2** or later.
- **Mod Settings Manager 1.0** or later.

Server-side: players need no client mod beyond what Hytale downloads from the server.

## Install

Put `SpellCrafter-1.0.0.jar` in your mods folder (singleplayer: `%APPDATA%\Hytale\UserData\Mods`), beside BuffEffects, CommandDeck and Mod Settings Manager, and turn it on in the world's mod list. Play in Adventure mode to use mana: Creative refills it.

## For modders

Spells, vessels and groups are JSON sidecars in your own mod. A spell pack loads without the editor, and your effects come from BuffEffects sidecars.

## Known limits in 1.0

- A creature hit from long range, by a spell or an arrow alike, may lose track of you: that's the game's own behaviour.
- A bolt whose caster dies or logs out mid-flight does nothing.

## Notes

- Art is recoloured from Hytale's own.
- Part of the Hledjolf utility family: CommandDeck, BuffEffects, Mod Settings Manager, Generous Operator, Calendar & Seasons, WorldBackup.
- MIT licensed. Copyright (c) 2026 Hledjolf.

## Building from source

See [BUILD.md](BUILD.md).
