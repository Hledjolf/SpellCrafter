# Changelog

## 1.0.0 (2026-11-08), Hytale 0.7.6

First release.

- 34 spells: bolts, heals, wards, battle fury, stuns, snares, curses, Vampiric Touch, Magelight, Ghost Form, Fireball, Thunderclap, Shadow Bolt, Smite and Prismatic Spray, with Lesser to Grand tiers for the staples.
- 60 vessels: 33 scrolls and 11 potions, used up when cast or drunk, and 16 wands that draw on your mana. Scrolls and wands are crafted at the Arcanebench, potions at the Alchemy bench, each tiered to its spell's zone.
- Casting: a cast time (moving or switching items interrupts it), a cooldown per spell, and mana for wands. A cast that can't take effect refunds its mana or keeps its scroll.
- Deliveries: bolts, touch spells, spells on yourself, and bursts where a bolt lands.
- Riders: spells that change with the world (Shock in a storm, healing at dawn), and requirements that gate a spell.
- Several effects in one spell, with chances and random picks (Prismatic Spray's rays).
- Damage types from BuffEffects 1.0, including shadow and holy, with their own damage colours.
- Power ratings on the game's own progression curve, with zones, and server caps set in Mod Settings Manager: burst targets and radius, bonuses, PvP behaviour, frequencies, and overrides for requirements and material components.
- The spell editor window (/spell editor): templates, a live stat block, grade, zone and mana, a tab per effect, riders from presets, Roll it for ten creatures, Aim for a zone, Undo and Redo, a scroll of the draft, and saving to the server's own spell pack. Saved spells get a scroll and a wand with Arcanebench recipes, without a restart.
- Commands: /spell list, info, give, cast, cooldowns, debug, reload and editor, described in CommandDeck's browser. Permissions: spellcrafter.editor, give, cast.any, debug and admin.
- For modders: spells, vessels and groups as JSON sidecars in your own mod; effects come from BuffEffects sidecars.
- Requires BuffEffects 1.0, CommandDeck 1.2 and Mod Settings Manager 1.0 or later.
