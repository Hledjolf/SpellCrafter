// The Hytale plugin. The server jar is the API: compile-only, never bundled. The game-agnostic core is merged into
// the plugin jar, so server owners install a single file.
val hytaleServerJar = (findProperty("hytaleServerJar") as String?)
    ?: "${System.getenv("APPDATA")}/Hytale/install/release/package/game/latest/Server/HytaleServer.jar"
val assetsZip = (findProperty("hytaleAssetsZip") as String?)
    ?: "${System.getenv("APPDATA")}/Hytale/install/release/package/game/latest/Assets.zip"

// CommandDeck's condition library (hledjolf.conditions.api 1.0, inside CommandDeck >= 1.2.0): compile-only. At run time
// the classes come from CommandDeck's jar, or they're absent and every condition is UNKNOWN.
val commandDeckJar = (findProperty("commandDeckJar") as String?) ?: "${System.getenv("APPDATA")}/Hytale/UserData/Mods/CommandDeck-1.2.0.jar"

// BuffEffects' API (buffeffects.api 1.0, BFX-D034; effects contract buffeffects.effects 1.0), likewise compile-only: at run time it comes from BuffEffects' jar.
val buffEffectsJar = (findProperty("buffEffectsJar") as String?) ?: "${System.getenv("APPDATA")}/Hytale/UserData/Mods/BuffEffects-1.0.0.jar"

dependencies {
    "implementation"(project(":core"))
    "compileOnly"(files(commandDeckJar))
    "compileOnly"(files(buffEffectsJar))
    "compileOnly"(files(hytaleServerJar))
    "testImplementation"(files(hytaleServerJar))
}

// The vessels' item assets, textures, projectiles and lang file are generated from the 1.0 pack
// (pack/) and the installed game's Assets.zip. The recoloured textures are derived vanilla art: they go
// in the jar but never in a repository (release-1.0.md section 7), so they live only under build/.
val generatedAssets = layout.buildDirectory.dir("generated-assets")
val makeAssets = tasks.register<Exec>("makeAssets") {
    val script = file("make_assets.py")
    inputs.file(script)
    inputs.dir(file("pack"))
    outputs.dir(generatedAssets)
    commandLine("python", "-I", script.absolutePath, "--root", rootProject.projectDir.absolutePath,
        "--assets", assetsZip, "--out", generatedAssets.get().asFile.absolutePath)
}
extensions.configure<SourceSetContainer> {
    named("main") { resources.srcDir(generatedAssets) }
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(makeAssets)
    inputs.property("version", rootProject.version.toString())
    filesMatching("manifest.json") { expand("version" to rootProject.version.toString()) }
}

// The casting rules are tested without the game, on the 1.0 pack read in place.
tasks.withType<Test> { systemProperty("spellcrafter.root", rootProject.projectDir.absolutePath) }

tasks.named<Jar>("jar") {
    archiveBaseName.set("SpellCrafter")
    destinationDirectory.set(rootProject.layout.buildDirectory.dir("mods"))
    from(project(":core").extensions.getByType<SourceSetContainer>()["main"].output)
    if (rootProject.file("LICENSE").exists()) from(rootProject.file("LICENSE"))
    // Playtest plans (playtest/*.md) ride in dev builds only, where PlaytestGamified finds them.
    if (rootProject.version.toString().endsWith("-dev") && rootProject.file("playtest").exists()) {
        from(rootProject.file("playtest")) {
            include("*.md")
            exclude("README.md")
            into("playtest")
        }
    }
}
