// SpellCrafter: a game-agnostic core plus one adapter per game.
// core/ is plain Java compiled for Java 17 (shared by every Java game, and the reference for the C#, C++ and
// Lua cores); adapters/<game>/ is that game's plugin, on the game's own Java.
plugins { base }

val releaseVersion = "1.0.0"
version = if (hasProperty("releaseBuild")) releaseVersion else "$releaseVersion-dev"

subprojects {
    version = rootProject.version
    apply(plugin = "java")
    repositories { mavenCentral() }

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(25))
    }

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Xlint:all,-serial", "-Werror"))
    }

    dependencies {
        "testImplementation"("org.junit.jupiter:junit-jupiter:5.11.4")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher:1.11.4")
    }
    tasks.withType<Test> {
        useJUnitPlatform()
        testLogging { events("failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
    }
}

// ---------------------------------------------------------------------------------------------------------
// checkAdapterBoundary: core/ is game-agnostic and portable (design/spell-format.md, section 12). No file under
// core/ may name a game API or use reflection. Its main code keeps to the import allow-list: spellcrafter.core.*,
// java.lang and java.util, but not java.util's concurrent, regex, Random or Scanner; and no regular expressions
// through String (matches, split, replaceAll, replaceFirst). No floating point in main code either.
// ---------------------------------------------------------------------------------------------------------
val checkAdapterBoundary = tasks.register("checkAdapterBoundary") {
    group = "verification"
    description = "Fails if core/ uses a game API, reflection, floating point or a class off the allow-list."
    val sources = fileTree("core") { include("**/*.java"); exclude("build/**") }
    inputs.files(sources)
    doLast {
        val banned = listOf("com.hypixel", "necesse.", "net.minecraft", "java.lang.reflect", "getDeclaredField",
            "getDeclaredMethod", "Class.forName")
        val offenders = sources.files.flatMap { f ->
            f.readLines().withIndex().filter { (_, l) -> banned.any { l.contains(it) } }
                .map { (i, l) -> " - ${f.relativeTo(projectDir)}:${i + 1}: ${l.trim()}" }
        }
        val main = fileTree("core/src/main") { include("**/*.java") }.files
        val outside = Regex("""\bjava\.(?!util\.|lang\.)[a-z]+|\bjava\.util\.(concurrent|regex)\b|\bjava\.util\.(Random|Scanner)\b""")
        val importLine = Regex("""^import\s+(static\s+)?([\w.]+)""")
        val regexCalls = Regex("""\.(matches|split|replaceAll|replaceFirst)\(|\bPattern\.""")
        val floats = Regex("""\b(double|float|Double|Float|BigDecimal|Math\.(sqrt|pow|log|exp|round))\b""")
        val allowed = main.flatMap { f ->
            f.readLines().withIndex().filter { (_, raw) ->
                val l = raw.substringBefore("//")
                val imp = importLine.find(l.trim())?.groupValues?.get(2)
                (imp != null && !imp.startsWith("spellcrafter.core.") && !imp.startsWith("java.util.") && !imp.startsWith("java.lang."))
                    || outside.containsMatchIn(l) || regexCalls.containsMatchIn(l) || floats.containsMatchIn(l)
            }.map { (i, l) -> " - ${f.relativeTo(projectDir)}:${i + 1}: ${l.trim()}" }
        }
        val all = offenders + allowed
        if (all.isNotEmpty()) throw GradleException("Game API, reflection, floating point or a class off the allow-list in core:\n" + all.joinToString("\n"))
    }
}

// ---------------------------------------------------------------------------------------------------------
// checkAdapterIsolation: each adapter depends on core, never on another adapter (MOD-FOLDER-STANDARD.md,
// "Several games in one mod"). Checks the declared project dependencies and the source imports.
// ---------------------------------------------------------------------------------------------------------
val adapterProjects = subprojects.filter { it.path.startsWith(":adapters:") }
val checkAdapterIsolation = tasks.register("checkAdapterIsolation") {
    group = "verification"
    description = "Fails if an adapter depends on, or imports from, another adapter."
    val adapterNames = adapterProjects.map { it.name }
    val deps = adapterProjects.associate { p ->
        p.name to p.configurations.flatMap { c ->
            c.dependencies.withType(ProjectDependency::class.java).map { it.path }
        }.distinct()
    }
    val sources = adapterProjects.associate { p -> p.name to p.fileTree("src") { include("**/*.java") }.files }
    doLast {
        val problems = mutableListOf<String>()
        deps.forEach { (name, paths) ->
            paths.filter { it.startsWith(":adapters:") && it != ":adapters:$name" }
                .forEach { problems += " - adapters/$name depends on $it" }
        }
        sources.forEach { (name, files) ->
            val others = adapterNames.filter { it != name }
            files.forEach { f ->
                f.readLines().withIndex().filter { (_, l) ->
                    l.trimStart().startsWith("import ") && others.any { l.contains("spellcrafter.$it.") }
                }.forEach { (i, l) -> problems += " - ${f.relativeTo(projectDir)}:${i + 1}: ${l.trim()}" }
            }
        }
        if (problems.isNotEmpty()) throw GradleException("Adapters must not use each other:\n" + problems.joinToString("\n"))
    }
}

tasks.named("check") {
    dependsOn(subprojects.filter { it.path != ":probe" && it.path != ":harness" }.map { it.tasks.named("check") })
    dependsOn(checkAdapterBoundary)
    dependsOn(checkAdapterIsolation)
}
