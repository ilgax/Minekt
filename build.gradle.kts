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

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    implementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    implementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")
    implementation("net.fabricmc:fabric-language-kotlin:${property("fabric_kotlin_version")}")

    bundled("org.jetbrains.kotlin:kotlin-scripting-jvm-host:$kotlinVersion")
    bundled("org.jetbrains.kotlin:kotlin-scripting-jvm:$kotlinVersion")
    bundled("org.jetbrains.kotlin:kotlin-scripting-common:$kotlinVersion")
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

/** Pins the exact compiler file (version + SHA-256) so the small build accepts nothing else. */
val compilerPin = tasks.register("compilerPin") {
    val outDir = layout.buildDirectory.dir("generated/compilerPin")
    val version = kotlinVersion
    inputs.files(compilerJar)
    outputs.dir(outDir)
    doLast {
        val jar = compilerJar.singleFile
        val digest = MessageDigest.getInstance("SHA-256").digest(jar.readBytes())
        val file = outDir.get().file("minekt-compiler.properties").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            group=org.jetbrains.kotlin
            name=$compilerModule
            version=$version
            sha256=${digest.joinToString("") { "%02x".format(it) }}
            size=${jar.length()}
            """.trimIndent() + "\n",
        )
    }
}
sourceSets.main { resources.srcDir(compilerPin) }

// The jar Loom builds bundles everything: that's the full one.
tasks.jar { archiveClassifier = "full" }

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
        exclude("META-INF/jars/$compilerModule-*.jar", "fabric.mod.json")
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
