package dev.ilgax.minekt.api

import dev.ilgax.minekt.job.ChatClicks
import dev.ilgax.minekt.job.Job
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.TextColor
import java.net.URI

// Chat messages with colors, hover text and click actions:
//
//   echo("Diamond at 12 -58 40", chatText("[copy]", Color.GREEN, hover = "Copy the coordinates", click = ChatClick.copy("12 -58 40")))
//   echo(chatText("Warning: ", Color.RED, bold = true) + "low durability")
//   echo(chatText("[again]", Color.CYAN, click = ChatClick.run { scan() }))    // runs this script's code
//
// echo joins its parts with spaces; `+` joins pieces without one.

/** A piece of chat text, made by [chatText]. Join pieces with `+`. Its plain text is [toString]. */
class ChatText internal constructor(internal val parts: List<Part>) {
    internal class Part(
        val text: String,
        val color: Int?,
        val bold: Boolean,
        val italic: Boolean,
        val underlined: Boolean,
        val strikethrough: Boolean,
        val hover: String?,
        val click: ChatClick?,
    )

    operator fun plus(other: ChatText) = ChatText(parts + other.parts)

    operator fun plus(text: String) = this + chatText(text)

    override fun toString() = parts.joinToString("") { it.text }

    /** The game's text for this, with any [ChatClick.run] actions registered for [job]. */
    internal fun toComponent(job: Job): MutableComponent {
        val root = Component.empty()
        for (part in parts) {
            root.append(
                Component.literal(part.text).withStyle { style ->
                    var s = style
                    part.color?.let { s = s.withColor(TextColor.fromRgb(it and 0xFFFFFF)) }
                    if (part.bold) s = s.withBold(true)
                    if (part.italic) s = s.withItalic(true)
                    if (part.underlined) s = s.withUnderlined(true)
                    if (part.strikethrough) s = s.withStrikethrough(true)
                    part.hover?.let { s = s.withHoverEvent(HoverEvent.ShowText(Component.literal(it))) }
                    part.click?.let { s = s.withClickEvent(it.toEvent(job)) }
                    s
                },
            )
        }
        return root
    }
}

/**
 * Chat text with a [color] (`Color.RED`, `0xRRGGBB`), styles, [hover] text shown under the mouse and a
 * [click] action. `§` color codes in [text] work too.
 */
fun chatText(
    text: String,
    color: Int? = null,
    bold: Boolean = false,
    italic: Boolean = false,
    underlined: Boolean = false,
    strikethrough: Boolean = false,
    hover: String? = null,
    click: ChatClick? = null,
) = ChatText(listOf(ChatText.Part(text, color, bold, italic, underlined, strikethrough, hover, click)))

/** What clicking a [chatText] does. */
sealed class ChatClick {
    internal abstract fun toEvent(job: Job): ClickEvent

    private class Command(val command: String) : ChatClick() {
        override fun toEvent(job: Job) = ClickEvent.RunCommand(command.removePrefix("/"))
    }

    private class Suggest(val text: String) : ChatClick() {
        override fun toEvent(job: Job) = ClickEvent.SuggestCommand(text)
    }

    private class Url(val url: String) : ChatClick() {
        override fun toEvent(job: Job) = ClickEvent.OpenUrl(URI.create(url))
    }

    private class Copy(val text: String) : ChatClick() {
        override fun toEvent(job: Job) = ClickEvent.CopyToClipboard(text)
    }

    private class Run(val action: () -> Unit) : ChatClick() {
        override fun toEvent(job: Job) = ClickEvent.RunCommand("minekt click ${ChatClicks.add(job, action)}")
    }

    companion object {
        /** Run a server or client command, as if typed: `"/home"`. (For a script, use [run] with `startScript`.) */
        fun command(command: String): ChatClick = Command(command)

        /** Put [text] in the chat box for the player to edit and send. */
        fun suggest(text: String): ChatClick = Suggest(text)

        /** Open [url] in the browser (the game asks first). */
        fun url(url: String): ChatClick {
            require(url.startsWith("https://") || url.startsWith("http://")) { "Links must start with http:// or https://" }
            return Url(url)
        }

        /** Copy [text] to the clipboard. */
        fun copy(text: String): ChatClick = Copy(text)

        /**
         * Run [action] in this script, on its thread, like a handler. Only while the script is still
         * running: clicking after it ended says so.
         */
        fun run(action: () -> Unit): ChatClick = Run(action)
    }
}
