import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.security.MessageDigest
import java.util.zip.ZipFile

plugins {
    id("net.fabricmc.fabric-loom")
    kotlin("jvm")
}

val kotlinVersion = property("kotlin_version") as String

version = property("mod_version") as String
group = property("maven_group") as String

base { archivesName = "minekt" }

repositories {
    mavenCentral()
    maven("https://maven.terraformersmc.com/releases") {
        content { includeGroup("com.terraformersmc") }
    }
}

// The Kotlin scripting host + embeddable compiler are not shipped by fabric-language-kotlin,
// so we bundle them (jar-in-jar). Everything FLK already provides is skipped to avoid duplicates.
val bundled: Configuration = configurations.create("bundled")
configurations.implementation { extendsFrom(bundled) }
val providedByFabricKotlin = setOf(
    "org.jetbrains.kotlin:kotlin-stdlib",
    "org.jetbrains.kotlin:kotlin-stdlib-jdk7",
    "org.jetbrains.kotlin:kotlin-stdlib-jdk8",
    "org.jetbrains.kotlin:kotlin-reflect",
    "org.jetbrains:annotations",
)

/**
 * Jars of the Maven resolver used by `@file:DependsOn`. Loaded in their own class loader the first
 * time a script needs them: bundled as plain files in the full jar, downloaded by the small one.
 * Anything the game, Fabric or Fabric Language Kotlin already provides is left out.
 */
val resolverRuntime: Configuration = configurations.create("resolverRuntime") {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-scripting-common")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-scripting-dependencies")
    exclude(group = "org.jetbrains.kotlinx")
    exclude(group = "org.jetbrains", module = "annotations")
    exclude(group = "com.google.code.gson")
    exclude(group = "com.google.guava")
    exclude(group = "commons-io")
    exclude(group = "commons-codec")
    exclude(group = "org.slf4j", module = "slf4j-api")
    exclude(group = "org.ow2.asm")
}

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    implementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    implementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")
    implementation("net.fabricmc:fabric-language-kotlin:${property("fabric_kotlin_version")}")
    // Optional: Minekt's settings behind Mod Menu's Configure button.
    compileOnly("com.terraformersmc:modmenu:${property("modmenu_version")}") { isTransitive = false }
    // ...and in the dev and production test runs, to try it.
    localRuntime("com.terraformersmc:modmenu:${property("modmenu_version")}") { isTransitive = false }
    testRuntimeOnly("com.terraformersmc:modmenu:${property("modmenu_version")}") { isTransitive = false } // self-tests use it

    bundled("org.jetbrains.kotlin:kotlin-scripting-jvm-host:$kotlinVersion")
    bundled("org.jetbrains.kotlin:kotlin-scripting-jvm:$kotlinVersion")
    bundled("org.jetbrains.kotlin:kotlin-scripting-common:$kotlinVersion")
    // @file:DependsOn / @file:Repository annotations and the resolver interfaces (small).
    bundled("org.jetbrains.kotlin:kotlin-scripting-dependencies:$kotlinVersion")
    // The Maven resolver behind them: not on the mod classpath, loaded on first use (see resolverRuntime).
    resolverRuntime("org.jetbrains.kotlin:kotlin-scripting-dependencies-maven:$kotlinVersion")
    // Attaches a Java agent at runtime so scripts can hook methods of already-loaded game classes.
    bundled("net.bytebuddy:byte-buddy-agent:${property("byte_buddy_version")}")

    testImplementation(kotlin("test"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Jar-in-jar every resolved artifact of `bundled` (Loom's `include` is not transitive).
afterEvaluate {
    bundled.resolvedConfiguration.resolvedArtifacts
        .map { it.moduleVersion.id }
        .filterNot { "${it.group}:${it.name}" in providedByFabricKotlin || it.group == "org.jetbrains.kotlinx" }
        .forEach { dependencies.add("include", "${it.group}:${it.name}:${it.version}") }
}

val resourceProps = mapOf(
    "version" to project.version.toString(),
    "minecraft_version" to property("minecraft_version") as String,
    "loader_version" to property("loader_version") as String,
    "fabric_kotlin_version" to property("fabric_kotlin_version") as String,
)

tasks.processResources {
    val props = resourceProps
    inputs.properties(props)
    filesMatching("fabric.mod.json") { expand(props) }
}

// ---- Two builds: "full" bundles the Kotlin compiler; the small one fetches it on first launch ----

val compilerModule = "kotlin-compiler-embeddable"

/** The compiler jar from Maven, as resolved for this build. */
val compilerJar = bundled.incoming.artifactView {
    componentFilter { it is org.gradle.api.artifacts.component.ModuleComponentIdentifier && it.module == compilerModule }
}.files

/** Pins exact files (coordinates, SHA-256, size) so Minekt accepts nothing else at runtime. */
fun pinLines(artifacts: Iterable<ResolvedArtifact>): String = artifacts.sortedBy { it.file.name }.joinToString("") { artifact ->
    val id = artifact.moduleVersion.id
    val digest = MessageDigest.getInstance("SHA-256").digest(artifact.file.readBytes()).joinToString("") { "%02x".format(it) }
    val classifier = artifact.classifier?.let { ":$it" } ?: ""
    "${id.group}:${id.name}:${id.version}$classifier $digest ${artifact.file.length()}\n"
}

val compilerPin = tasks.register("compilerPin") {
    val outDir = layout.buildDirectory.dir("generated/compilerPin")
    inputs.files(compilerJar, resolverRuntime)
    outputs.dir(outDir)
    doLast {
        val dir = outDir.get().asFile.apply { mkdirs() }
        val compiler = bundled.resolvedConfiguration.resolvedArtifacts.filter { it.moduleVersion.id.name == compilerModule }
        dir.resolve("minekt-compiler.txt").writeText(pinLines(compiler))
        dir.resolve("minekt-resolver.txt").writeText(pinLines(resolverRuntime.resolvedConfiguration.resolvedArtifacts))
    }
}
sourceSets.main { resources.srcDir(compilerPin) }

// The jar Loom builds bundles everything: that's the full one.
tasks.jar {
    archiveClassifier = "full"
    from(resolverRuntime) { into("minekt-resolver") }
}

/** fabric.mod.json of the full jar minus the bundled compiler. */
val smallModJson = tasks.register("smallModJson") {
    val full = tasks.jar.flatMap { it.archiveFile }
    val out = layout.buildDirectory.file("smallJar/fabric.mod.json")
    inputs.file(full)
    outputs.file(out)
    doLast {
        val text = ZipFile(full.get().asFile).use { zip ->
            zip.getInputStream(zip.getEntry("fabric.mod.json")).readBytes().decodeToString()
        }
        @Suppress("UNCHECKED_CAST")
        val json = groovy.json.JsonSlurper().parseText(text) as MutableMap<String, Any?>
        json["jars"] = (json["jars"] as List<Map<String, String>>).filterNot { compilerModule in it.getValue("file") }
        out.get().asFile.writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(json)))
    }
}

val smallJar = tasks.register<Zip>("smallJar") {
    description = "The mod without the Kotlin compiler; it downloads the pinned compiler on first launch."
    val full = tasks.jar.flatMap { it.archiveFile }
    dependsOn(smallModJson)
    from(zipTree(full)) {
        exclude("META-INF/jars/$compilerModule-*.jar", "minekt-resolver/**", "fabric.mod.json")
    }
    from(smallModJson)
    archiveBaseName = base.archivesName
    archiveVersion = project.version.toString()
    archiveExtension = "jar"
    destinationDirectory = layout.buildDirectory.dir("libs")
}
tasks.assemble { dependsOn(smallJar) }

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
    withSourcesJar()
}

kotlin {
    jvmToolchain(25)
    compilerOptions.jvmTarget = JvmTarget.JVM_25
}

tasks.test {
    useJUnitPlatform()
}

// `./gradlew runClient -PsmallRun` runs the dev client like the small build: without the compiler on
// its classpath, so Minekt has to find or download it. `-PsmallRunHome=<dir>` also hides the Gradle
// and Maven caches (they're looked up under the user's home), forcing a real download.
if (providers.gradleProperty("smallRun").isPresent) {
    configurations.runtimeClasspath { exclude(group = "org.jetbrains.kotlin", module = compilerModule) }
    providers.gradleProperty("smallRunHome").orNull?.let { home ->
        loom.runs.named("client") { vmArg("-Duser.home=$home") }
    }
}

// `./gradlew prodClient` (full jar) / `prodClientSmall` (small jar) start the real, production game with the
// built jar, Fabric API and Fabric Language Kotlin as plain mod jars, like a launcher does, in build/prodrun.
// Extra game arguments: -PprodArgs="--quickPlaySingleplayer test".
val prodMods: Configuration by configurations.creating { isTransitive = false }
dependencies {
    prodMods("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")
    prodMods("net.fabricmc:fabric-language-kotlin:${property("fabric_kotlin_version")}")
    prodMods("com.terraformersmc:modmenu:${property("modmenu_version")}")
}
for ((name, jar) in listOf("prodClient" to tasks.jar.flatMap { it.archiveFile }, "prodClientSmall" to smallJar.flatMap { it.archiveFile })) {
    tasks.register<net.fabricmc.loom.task.prod.ClientProductionRunTask>(name) {
        mods.from(prodMods, jar)
        runDir = layout.buildDirectory.dir("prodrun")
        useXVFB = false
        providers.gradleProperty("prodArgs").orNull?.let { programArgs.addAll(it.split(' ')) }
    }
}

// ---- The stable script API ------------------------------------------------------------------------
// api/minekt.api lists every stable declaration scripts can use: `dev.ilgax.minekt.api`, minus what's
// marked experimental. `check` (and so `build`) fails when it changes; after a deliberate change, run
// `./gradlew updateKotlinAbi` and commit the new file with it.
@OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
kotlin {
    abiValidation {
        filters {
            include { byNames.add("dev.ilgax.minekt.api.**") }
            exclude {
                annotatedWith.addAll(
                    "dev.ilgax.minekt.api.ExperimentalFabricEvents",
                    "dev.ilgax.minekt.api.ExperimentalPackets",
                    "dev.ilgax.minekt.api.ExperimentalHooks",
                    "dev.ilgax.minekt.api.ExperimentalVisuals",
                    "dev.ilgax.minekt.api.ExperimentalBaritone",
                )
            }
        }
    }
}
@OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
tasks.check { dependsOn(kotlin.abiValidation.checkTaskProvider) }
