import org.jetbrains.kotlin.gradle.dsl.JvmTarget

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
