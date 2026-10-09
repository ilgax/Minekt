package dev.ilgax.minekt.hooks

import com.mojang.brigadier.arguments.StringArgumentType
import dev.ilgax.minekt.job.MessageKind
import dev.ilgax.minekt.share.ScriptInstaller
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.MutableComponent
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * `\install`, `\update` and `\share`, plus the clickable preview. Downloads and compile checks run
 * on a background thread; the preview's buttons run `/minekt install yes|no <token>`.
 */
internal class InstallUi(
    private val installer: ScriptInstaller,
    private val scriptDir: Path,
    private val echo: (String, MessageKind) -> Unit,
) {
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "minekt-install").apply {
            isDaemon = true
            contextClassLoader = InstallUi::class.java.classLoader // the script compiler needs Knot's loader
        }
    }

    fun register() {
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(
                ClientCommands.literal("minekt").then(
                    ClientCommands.literal("install").then(
                        ClientCommands.argument("answer", StringArgumentType.word()).then(
                            ClientCommands.argument("token", StringArgumentType.word()).executes { ctx ->
                                answer(StringArgumentType.getString(ctx, "answer"), StringArgumentType.getString(ctx, "token"))
                                1
                            },
                        ),
                    ),
                ),
            )
        }
    }

    /** `\install <url|clipboard> [name]`, `\install yes|no`. */
    fun install(args: List<String>) {
        when (val what = args.firstOrNull()) {
            null -> usage()
            "yes", "no" -> answer(what, args.getOrNull(1))
            "clipboard" -> {
                val text = Minecraft.getInstance().keyboardHandler.clipboard
                background { installer.fromText(text, args.getOrNull(1)) }
            }
            else -> {
                echo("Downloading $what …", MessageKind.INFO)
                background { installer.fromUrl(what, args.getOrNull(1)) }
            }
        }
    }

    /** `\update <name>`: fetch a newer version from where the script was installed from. */
    fun update(name: String?) {
        if (name == null) return echo("Usage: \\update <script>", MessageKind.ERROR)
        val url = installer.sourceOf(name)
            ?: return echo("$name.kts wasn't installed from a link, so there's nothing to update from", MessageKind.ERROR)
        echo("Checking $url …", MessageKind.INFO)
        background {
            val install = installer.fromUrl(url, name)
            val current = scriptDir.resolve("$name.kts").readText().lines().filterNot { it.startsWith(ScriptInstaller.SOURCE_MARKER) }
            if (install.text.lines() == current) {
                installer.cancel(install.token)
                echo("$name.kts is already up to date", MessageKind.INFO)
                null
            } else {
                install
            }
        }
    }

    /** `\share <name>`: copy the script to the clipboard (and show its link if it has one). */
    fun share(name: String?) {
        if (name == null) return echo("Usage: \\share <script>", MessageKind.ERROR)
        val file = scriptDir.resolve("$name.kts")
        if (!file.exists()) return echo("No script named '$name'", MessageKind.ERROR)
        val text = file.readText()
        Minecraft.getInstance().keyboardHandler.clipboard = text
        val message = Component.literal("Copied $name.kts (${text.lines().size} lines) to the clipboard. ").withStyle(ChatFormatting.GRAY)
            .append(Component.literal("Others can install it with ").withStyle(ChatFormatting.GRAY))
            .append(Component.literal("\\install clipboard").withStyle(ChatFormatting.YELLOW))
        installer.sourceOf(name)?.let { url ->
            message.append(Component.literal(" or from its ").withStyle(ChatFormatting.GRAY))
                .append(button("link", ChatFormatting.AQUA, "Copy $url", ClickEvent.CopyToClipboard(url)))
        }
        send(message)
    }

    private fun answer(answer: String, token: String?) {
        try {
            when (answer) {
                "yes" -> {
                    val done = installer.confirm(token)
                    send(
                        Component.literal("Installed ${done.name}.kts. Run it with ").withStyle(ChatFormatting.GREEN)
                            .append(button("\\${done.name}", ChatFormatting.YELLOW, "Put it in the chat box", ClickEvent.SuggestCommand("\\${done.name}"))),
                    )
                }
                "no" -> echo("Cancelled installing ${installer.cancel(token).name}", MessageKind.INFO)
                else -> echo("Usage: \\install yes|no", MessageKind.ERROR)
            }
        } catch (e: IllegalStateException) {
            echo(e.message ?: "Install failed", MessageKind.ERROR)
        }
    }

    private fun background(stage: () -> ScriptInstaller.Pending?) {
        worker.execute {
            try {
                stage()?.let(::preview)
            } catch (e: Exception) {
                echo("Install failed: ${e.message ?: e.javaClass.simpleName}", MessageKind.ERROR)
            }
        }
    }

    private fun preview(p: ScriptInstaller.Pending) {
        val lines = mutableListOf<Component>()
        lines += Component.literal("Install script ").withStyle(ChatFormatting.GOLD)
            .append(Component.literal(p.name).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
            .append(
                Component.literal(
                    " (${p.lines} lines" + (p.replacesLines?.let { ", replaces your current ${p.name}.kts with $it lines" } ?: "") + ")",
                ).withStyle(ChatFormatting.GOLD),
            )
        lines += Component.literal("  from ${p.source ?: "the clipboard"}").withStyle(ChatFormatting.GRAY)
        if (p.capabilities.isEmpty()) {
            lines += Component.literal("  Nothing sensitive found (a quick scan, not a guarantee).").withStyle(ChatFormatting.GREEN)
        } else {
            lines += Component.literal("  This script can:").withStyle(ChatFormatting.YELLOW)
            p.capabilities.forEach { lines += Component.literal("   • $it").withStyle(ChatFormatting.YELLOW) }
        }
        if (p.problems.isNotEmpty()) {
            lines += Component.literal("  ✘ It doesn't compile here:").withStyle(ChatFormatting.RED)
            p.problems.take(3).forEach { lines += Component.literal("     $it").withStyle(ChatFormatting.RED) }
            if (p.problems.size > 3) lines += Component.literal("     … and ${p.problems.size - 3} more").withStyle(ChatFormatting.RED)
        } else {
            lines += Component.literal("  ✔ Compiles").withStyle(ChatFormatting.GREEN)
        }
        lines += Component.literal("  ")
            .append(button("[Install]", ChatFormatting.GREEN, "Save it as ${p.name}.kts", ClickEvent.RunCommand("minekt install yes ${p.token}")))
            .append(" ")
            .append(button("[View source]", ChatFormatting.AQUA, "Open it in your editor first", ClickEvent.OpenFile(p.previewFile)))
            .append(" ")
            .append(button("[Cancel]", ChatFormatting.RED, "Don't install", ClickEvent.RunCommand("minekt install no ${p.token}")))
        lines += Component.literal("  Or type \\install yes / \\install no. Scripts have full access to your game: only install ones you trust.")
            .withStyle(ChatFormatting.DARK_GRAY)
        lines.forEach(::send)
    }

    private fun button(label: String, color: ChatFormatting, hover: String, click: ClickEvent): MutableComponent =
        chatButton(label, color, hover, click)

    private fun usage() {
        listOf(
            "\\install <url> [name]   install a script from a link (GitHub, gist, pastebin or raw)",
            "\\install clipboard [name]   install a script you copied",
            "\\update <script>   get a newer version from where it was installed from",
            "\\share <script>   copy a script so others can install it",
        ).forEach { echo(it, MessageKind.INFO) }
    }

    private fun send(component: Component) {
        val mc = Minecraft.getInstance()
        mc.execute { mc.player?.sendSystemMessage(component) }
    }
}
