package dev.ilgax.minekt.job

/**
 * `minekt/autorun.txt`: `\commands` to run when joining a world, optionally only in some places.
 *
 * ```
 * \hello                      # before any section: every world
 * [server *.example.net]      # servers whose address matches (* and ? wildcards)
 * \autowalk
 * [world Creative test]       # singleplayer worlds by name
 * [dimension the_nether]      # also runs when you enter the Nether
 * [multiplayer]               # any server; [singleplayer] any local world
 * [server play.x.net, dimension the_end]   # all conditions must match
 * [always]                    # back to every world
 * ```
 *
 * Sections with a `dimension` condition run on joining (if you join in that dimension) and every
 * time you enter it; the others run on joining only. Matching ignores case.
 */
class Autorun(text: String) {
    /** Where the player is: server address (null in singleplayer), world name (null on a server), dimension id. */
    data class Place(val server: String?, val world: String?, val dimension: String)

    private sealed interface Condition {
        fun matches(place: Place): Boolean

        data class Server(val pattern: Regex) : Condition {
            override fun matches(place: Place) = place.server != null &&
                (pattern.matches(place.server) || pattern.matches(place.server.substringBeforeLast(':')))
        }

        data class World(val pattern: Regex) : Condition {
            override fun matches(place: Place) = place.world != null && pattern.matches(place.world)
        }

        data class Dimension(val pattern: Regex) : Condition {
            override fun matches(place: Place) = pattern.matches(place.dimension) || pattern.matches(place.dimension.substringAfter(':'))
        }

        data object Singleplayer : Condition {
            override fun matches(place: Place) = place.server == null
        }

        data object Multiplayer : Condition {
            override fun matches(place: Place) = place.server != null
        }

        /** Under a section that couldn't be read: better not to run than to run everywhere. */
        data object Never : Condition {
            override fun matches(place: Place) = false
        }
    }

    private class Line(val conditions: List<Condition>, val command: String) {
        val perDimension = conditions.any { it is Condition.Dimension }
        fun matches(place: Place) = conditions.all { it.matches(place) }
    }

    private val lines = mutableListOf<Line>()

    /** Problems found while reading the file, e.g. unknown section names, with their line numbers. */
    val problems = mutableListOf<String>()

    init {
        var conditions = emptyList<Condition>()
        text.lines().forEachIndexed { index, raw ->
            val line = raw.substringBefore('#').trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("[") && line.endsWith("]") -> conditions = parseSection(line.removeSurrounding("[", "]"), index + 1) ?: listOf(Condition.Never)
                else -> lines += Line(conditions, line)
            }
        }
    }

    /** Commands to run on joining a world at [place]. */
    fun onJoin(place: Place): List<String> = lines.filter { it.matches(place) }.map { it.command }

    /** Commands to run on entering a new dimension at [place]: only `dimension` sections. */
    fun onDimensionChange(place: Place): List<String> = lines.filter { it.perDimension && it.matches(place) }.map { it.command }

    private fun parseSection(header: String, lineNumber: Int): List<Condition>? {
        if (header.trim().equals("always", ignoreCase = true)) return emptyList()
        val conditions = header.split(',').map { part ->
            val word = part.trim().substringBefore(' ').lowercase()
            val value = part.trim().substringAfter(' ', "").trim()
            when {
                word == "singleplayer" && value.isEmpty() -> Condition.Singleplayer
                word == "multiplayer" && value.isEmpty() -> Condition.Multiplayer
                value.isEmpty() -> null
                word == "server" -> Condition.Server(glob(value))
                word == "world" -> Condition.World(glob(value))
                word == "dimension" -> Condition.Dimension(glob(value))
                else -> null
            } ?: run {
                problems += "autorun.txt line $lineNumber: unknown section '[$header]' (use server, world, dimension, singleplayer, multiplayer or always)"
                return null
            }
        }
        return conditions
    }

    private fun glob(pattern: String) =
        Regex(pattern.split('*').joinToString(".*") { part -> part.split('?').joinToString(".") { Regex.escape(it) } }, RegexOption.IGNORE_CASE)
}
