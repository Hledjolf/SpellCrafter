rootProject.name = "SpellCrafter"

include("core")

// One build unit per game. An adapter is included once it has a build file, so adapters/hytale/ and
// adapters/necesse/ stay out until their work starts (design/spell-format.md, section 1).
file("adapters").listFiles()?.filter { File(it, "build.gradle.kts").exists() }?.sortedBy { it.name }?.forEach {
    include(":adapters:${it.name}")
}
