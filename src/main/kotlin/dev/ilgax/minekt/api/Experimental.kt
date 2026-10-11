package dev.ilgax.minekt.api

/**
 * Fabric API events from scripts, written like in a mod (`ClientTickEvents.END_CLIENT_TICK.register { … }`),
 * with listeners taken out again when the script ends. Experimental: opt in with
 * `@file:OptIn(ExperimentalFabricEvents::class)` at the top of the script.
 */
@RequiresOptIn("Fabric events from scripts are experimental", RequiresOptIn.Level.WARNING)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
annotation class ExperimentalFabricEvents
