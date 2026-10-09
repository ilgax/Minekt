package dev.ilgax.minekt.job

import kotlin.script.experimental.api.ScriptDiagnostic
import kotlin.script.experimental.api.SourceCode

/**
 * A script error, ready to show: a plain-language [summary], where it happened (with the offending
 * source line), the script-level call stack, and the full [detail] for the log.
 */
class ErrorReport(
    val script: String,
    val summary: String,
    /** 1-based line in [file], if known. */
    val line: Int?,
    val column: Int?,
    /** The script file the error is in, e.g. `miner.kts` or `util.kts` for an imported one. */
    val file: String?,
    /** The source line at [line], when the file is the one that was run. */
    val sourceLine: String?,
    /** Characters of [sourceLine] to point at (compile errors). */
    val highlight: IntRange?,
    /** Where in the script it happened, innermost first: `line 12 in mine`, … */
    val scriptStack: List<String>,
    /** Everything there is to know, for the log: the compiler message or the full stack trace. */
    val detail: String,
    val isCompileError: Boolean,
) {
    /** One line for chat: `[miner:3:5] message` for compile errors, `[miner] message (line 12)` otherwise. */
    val headline: String
        get() = if (isCompileError) {
            val at = line?.let { ":$it" + (column?.let { c -> ":$c" } ?: "") }.orEmpty()
            "[$script$at] $summary"
        } else {
            val at = line?.let { " (line $it" + (file?.takeIf { f -> f != "$script.kts" }?.let { f -> " of $f" } ?: "") + ")" }.orEmpty()
            "[$script] $summary$at"
        }

    companion object {
        private const val MAX_STACK = 8
        private val LAMBDA_METHODS = setOf("invoke", "invokeSuspend")
        private const val MAX_OTHER_FRAMES = 25
        /** Endless recursion has thousands of script frames. */
        private const val MAX_FRAMES = 60
        private const val MAX_DETAIL_CHARS = 20_000

        /** Report a compile error or warning from the Kotlin compiler. */
        fun fromDiagnostic(script: String, diagnostic: ScriptDiagnostic, source: SourceCode?): ErrorReport {
            val start = diagnostic.location?.start
            val end = diagnostic.location?.end
            val file = diagnostic.sourcePath?.substringAfterLast('/')?.substringAfterLast('\\') ?: source?.name
            val sameFile = source != null && (diagnostic.sourcePath == null || file == source.name)
            val sourceLine = start?.takeIf { sameFile }?.let { source!!.text.lines().getOrNull(it.line - 1) }
            val highlight = if (sourceLine != null && start != null) {
                val from = (start.col - 1).coerceIn(0, sourceLine.length)
                val to = if (end != null && end.line == start.line && end.col > start.col) end.col - 1 else tokenEnd(sourceLine, from)
                from until to.coerceIn(from, sourceLine.length)
            } else null
            val message = diagnostic.message.trim()
            return ErrorReport(
                script, message.lineSequence().first(), start?.line, start?.col, file, sourceLine?.trimEnd(), highlight,
                emptyList(), message + (diagnostic.exception?.let { "\n" + trace(it) } ?: ""), isCompileError = true,
            )
        }

        /** Report an exception thrown while the script ran. [source] is the script that was run. */
        fun fromThrowable(script: String, t: Throwable, source: SourceCode?): ErrorReport {
            val chain = generateSequence(t) { it.cause }.take(20).toList()
            val root = chain.last()
            // The deepest exception that passed through script code knows where in the script it was.
            val frames = chain.asReversed().map { e -> e.stackTrace.filter(::isScriptFrame) }.firstOrNull { it.isNotEmpty() }.orEmpty()
            val top = frames.firstOrNull()
            val file = top?.fileName
            val line = top?.lineNumber?.takeIf { it > 0 }
            val sourceLine = if (line != null && source != null && file == source.name) source.text.lines().getOrNull(line - 1)?.trimEnd() else null
            return ErrorReport(
                script, describe(root), line, null, file, sourceLine, null,
                stackOf(frames, mainFile = source?.name), trace(t), isCompileError = false,
            )
        }

        /** What went wrong, in words, for the common exceptions; otherwise its type and message. */
        fun describe(t: Throwable): String {
            val message = t.message?.trim()?.takeIf { it.isNotEmpty() }
            return when (t) {
                is NumberFormatException -> message?.let { m ->
                    Regex("For input string: \"(.*)\"").find(m)?.let { "\"${it.groupValues[1]}\" isn't a number" } ?: "Not a number: $m"
                } ?: "Not a number"
                // error(), require(), check(): the script's (or the API's) own words.
                is IllegalStateException, is IllegalArgumentException -> message ?: t.javaClass.simpleName
                is NullPointerException -> message?.let { "A value was null: $it" } ?: "A value was null (a !! on null, or a Java method returned null)"
                is IndexOutOfBoundsException -> message?.let { "Out of range: $it" } ?: "Index out of range"
                is NoSuchElementException -> message ?: "Nothing found"
                is ClassCastException -> "Wrong type: ${message ?: "a value isn't the type it was used as"}"
                is ArithmeticException -> "Math error: ${message ?: t.javaClass.simpleName}"
                is ConcurrentModificationException -> "A collection was changed while it was being looped over"
                is UnsupportedOperationException -> message ?: "Not supported here (e.g. changing a read-only list)"
                is StackOverflowError -> "Endless recursion: a function kept calling itself (stack overflow)"
                is OutOfMemoryError -> "Out of memory" + (message?.let { ": $it" } ?: "")
                is NotImplementedError -> message ?: "Not implemented yet (TODO())"
                else -> t.javaClass.simpleName + (message?.let { ": $it" } ?: "")
            }
        }

        private fun isScriptFrame(frame: StackTraceElement) = frame.fileName?.endsWith(".kts") == true

        /** `line 12 in mine`, collapsing recursion, at most [MAX_STACK] entries. */
        private fun stackOf(frames: List<StackTraceElement>, mainFile: String?): List<String> {
            // A lambda's bridge `invoke` shows up as a second identical frame: not recursion.
            val unbridged = frames.filterIndexed { i, frame ->
                i == 0 || frame.methodName !in LAMBDA_METHODS || frame != frames[i - 1]
            }
            val described = unbridged.map { frame ->
                val where = if (frame.fileName == mainFile) "line ${frame.lineNumber}" else "${frame.fileName}:${frame.lineNumber}"
                functionName(frame)?.let { "$where in $it" } ?: where
            }
            val collapsed = mutableListOf<String>()
            var i = 0
            while (i < described.size && collapsed.size < MAX_STACK) {
                var n = 1
                while (i + n < described.size && described[i + n] == described[i]) n++
                collapsed += if (n > 1) "${described[i]} (×$n)" else described[i]
                i += n
            }
            if (i < described.size) collapsed += "… ${described.size - i} more"
            return collapsed
        }

        /** The script function a frame is in, or null for the script body. Lambdas name their enclosing function. */
        private fun functionName(frame: StackTraceElement): String? {
            val method = frame.methodName
            if (method == "<init>" || method == "<clinit>") return null
            val lambdaOwner = method.substringBefore("\$lambda").takeIf { "\$lambda" in method }
            return when {
                lambdaOwner != null -> if (lambdaOwner.isEmpty() || lambdaOwner.startsWith("_")) "a lambda" else "a lambda in $lambdaOwner"
                method in LAMBDA_METHODS -> {
                    // `Script$go$task$1`: the first named segment after the script class is the enclosing function.
                    val owner = frame.className.split('$').drop(1).firstOrNull { it.isNotEmpty() && !it[0].isDigit() && it != "lambda" }
                    if (owner == null) "a lambda" else "a lambda in $owner"
                }
                '$' in method -> method.substringBefore('$').ifEmpty { null }
                else -> method
            }
        }

        private fun tokenEnd(line: String, from: Int): Int {
            var i = from
            while (i < line.length && (line[i].isLetterOrDigit() || line[i] == '_')) i++
            return if (i == from) (from + 1).coerceAtMost(line.length) else i
        }

        /**
         * The stack trace, cut after the last script line of each exception: what comes after is
         * Minekt and Java running the script, which only buries the useful part.
         */
        private fun trace(t: Throwable): String = buildString {
            val seen = HashSet<Throwable>()
            var e: Throwable? = t
            while (e != null && seen.add(e) && length < MAX_DETAIL_CHARS) {
                if (e !== t) append("Caused by: ")
                append(e.toString()).append('\n')
                val frames = e.stackTrace
                val lastScript = frames.indexOfLast(::isScriptFrame)
                val shown = (if (lastScript >= 0) lastScript + 1 else MAX_OTHER_FRAMES).coerceAtMost(minOf(frames.size, MAX_FRAMES))
                frames.take(shown).forEach { append("\tat ").append(it).append('\n') }
                if (frames.size > shown) append("\t… ${frames.size - shown} more\n")
                e = e.cause
            }
        }.trimEnd()
    }
}
