package dev.ilgax.minekt

/** Whitespace-separated, with "double quotes" grouping. */
internal fun splitArgs(line: String): List<String> =
    Regex("\"([^\"]*)\"|(\\S+)").findAll(line).map { it.groupValues[1].ifEmpty { it.groupValues[2] } }.toList()
