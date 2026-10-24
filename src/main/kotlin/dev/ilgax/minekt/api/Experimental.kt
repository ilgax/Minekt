package dev.ilgax.minekt.api

// The parts of the API that aren't frozen yet. Using one needs a line at the top of the script, like
// `@file:OptIn(ExperimentalPackets::class)`; without it, the script doesn't compile and says which line
// to add. Several: `@file:OptIn(ExperimentalPackets::class, ExperimentalHooks::class)`.
// Everything else in Minekt is stable: scripts using only that keep working across Minekt versions.

/**
 * Reading, changing and sending network packets (`onPacket`, `interceptPacket`, `sendPacket`…). Packets are
 * Minecraft's own classes, which change with every game version.
 */
@RequiresOptIn(
    "Packets are experimental: they change with every Minecraft version. Add @file:OptIn(ExperimentalPackets::class) at the top of the script to use them",
    RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
annotation class ExperimentalPackets

/**
 * Changing the game's code while it runs (`hook`, `hookAfter`, `rewriteClass`, `transformClass`,
 * `classBytes`). They name Minecraft's methods and classes, which change with every game version.
 */
@RequiresOptIn(
    "Hooks and class rewriting are experimental: they change with every Minecraft version. Add @file:OptIn(ExperimentalHooks::class) at the top of the script to use them",
    RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
annotation class ExperimentalHooks

/** Glow outlines on entities, fake blocks and pixels: new, and may still change. */
@RequiresOptIn(
    "glow, fake blocks and pixels are experimental and may still change. Add @file:OptIn(ExperimentalVisuals::class) at the top of the script to use them",
    RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
annotation class ExperimentalVisuals

/** The `baritone` helpers: they follow Baritone's own API, which Minekt doesn't control. */
@RequiresOptIn(
    "The baritone helpers are experimental: they follow Baritone's API. Add @file:OptIn(ExperimentalBaritone::class) at the top of the script to use them",
    RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
annotation class ExperimentalBaritone
