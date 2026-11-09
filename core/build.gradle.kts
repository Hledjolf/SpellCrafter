// Game-agnostic plain Java: spell parsing and validation, conditions as data, registries, combining
// profiles, caps, operator overrides and evaluation. No game API is ever on this project's classpath.
// Compiled for Java 17, the reference for the C#, C++ and Lua cores.
tasks.withType<JavaCompile> { options.release.set(17) }

// The conformance cases and the design samples are read in place by the tests.
tasks.withType<Test> {
    systemProperty("spellcrafter.root", rootProject.projectDir.absolutePath)
}
