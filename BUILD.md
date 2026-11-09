# Building SpellCrafter

You need a JDK 25, Python 3 (the build generates the item assets with it), and four files from your Hytale install
(none is bundled):

- **Hytale's server jar** and **`Assets.zip`.** By default the build looks in the launcher's install folder:
  `%APPDATA%\Hytale\install\release\package\game\latest\Server\HytaleServer.jar` and `...\latest\Assets.zip`.
  The item textures are recoloured from the game's own art, read from `Assets.zip` at build time, so they're in the
  jar but never in this repository.
- **BuffEffects 1.0.0's jar** and **CommandDeck 1.2.0's jar**, for their APIs. By default:
  `%APPDATA%\Hytale\UserData\Mods\BuffEffects-1.0.0.jar` and `...\Mods\CommandDeck-1.2.0.jar`.

Elsewhere? Pass their paths:

```
gradlew jar -PreleaseBuild -PhytaleServerJar=<HytaleServer.jar> -PhytaleAssetsZip=<Assets.zip> -PbuffEffectsJar=<BuffEffects jar> -PcommandDeckJar=<CommandDeck jar>
```

The plugin is written to `build\mods\SpellCrafter-1.0.0.jar`. Put it in your mods folder, with BuffEffects 1.0,
CommandDeck 1.2 and Mod Settings Manager 1.0 or later.

`adapters\hytale\pack\` holds the 1.0 spell pack (spells, vessels and their BuffEffects effects) that the plugin
is generated from. `core\` is the game-agnostic engine; `adapters\hytale\` is the Hytale plugin.
