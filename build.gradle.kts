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
// so we bundle them (jar-in-jar). Everything FLK already provides is excluded to avoid duplicates.
val bundled: Configuration by configurations.creating {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk7")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-reflect")
    exclude(group = "org.jetbrains.kotlinx")
    exclude(group = "org.jetbrains", module = "annotations")
}
configurations.implementation { extendsFrom(bundled) }

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    implementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    implementation("net.fabricmc.fabric-api:fabric-api:${property("fabric_api_version")}")
    implementation("net.fabricmc:fabric-language-kotlin:${property("fabric_kotlin_version")}")

    bundled("org.jetbrains.kotlin:kotlin-scripting-jvm-host:$kotlinVersion")
    bundled("org.jetbrains.kotlin:kotlin-scripting-jvm:$kotlinVersion")
    bundled("org.jetbrains.kotlin:kotlin-scripting-common:$kotlinVersion")

    testImplementation(kotlin("test"))
}

// Jar-in-jar every resolved artifact of `bundled` (Loom's `include` is not transitive).
afterEvaluate {
    bundled.resolvedConfiguration.resolvedArtifacts.forEach { artifact ->
        val id = artifact.moduleVersion.id
        dependencies.add("include", "${id.group}:${id.name}:${id.version}")
    }
}

tasks.processResources {
    val props = mapOf(
        "version" to project.version,
        "minecraft_version" to property("minecraft_version"),
        "loader_version" to property("loader_version"),
        "fabric_kotlin_version" to property("fabric_kotlin_version"),
    )
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
