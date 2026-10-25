package dev.ilgax.minekt.server

import dev.ilgax.minekt.script.ServerScriptCompiler
import dev.ilgax.minekt.share.ScriptInstaller
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.MutableComponent
import java.util.concurrent.Executors
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * `/mks install`, `/mks share` and `/mks settings`: managing a server's scripts from chat or the console. Installing
 * works like the client's `\install`: the script is downloaded and checked first, and only written into the script
 * folder after `/mks install confirm`.
 */
internal class ServerManagement(private val scripts: ServerScripts) {
    private val installer = ScriptInstaller(scripts.scriptDir, compiler = ServerScriptCompiler, server = true)

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "minekt-server-install").apply {
            isDaemon = true
            contextClassLoader = ServerManagement::class.java.classLoader // the script compiler needs Knot's loader
        }
    }

    // ---- install ----------------------------------------------------------------------------------

    /** `/mks install <url> [name]`: download, check and show what the script can do. */
    fun install(source: CommandSourceStack, url: String, name: String?) {
        reply(source, "Downloading $url …", ChatFormatting.GRAY)
        worker.execute {
            try {
                preview(source, installer.fromUrl(url, name))
            } catch (e: Exception) {
                reply(source, "Install failed: ${e.message ?: e.javaClass.simpleName}", ChatFormatting.RED)
            }
        }
    }

    /** `/mks install confirm|cancel [token]`; without a token, the latest install waiting. */
    fun answer(source: CommandSourceStack, confirm: Boolean, token: String?) {
        try {
            if (confirm) {
                val done = installer.confirm(token)
                send(
                    source,
                    Component.literal("Installed ${done.name}.kts. Run it with ").withStyle(ChatFormatting.GREEN)
                        .append(button("/mks run ${done.name}", ChatFormatting.YELLOW, "Put it in the chat box", ClickEvent.SuggestCommand("/mks run ${done.name}"))),
                )
            } else {
                reply(source, "Cancelled installing ${installer.cancel(token).name}", ChatFormatting.GRAY)
            }
        } catch (e: IllegalStateException) {
            reply(source, e.message ?: "Install failed", ChatFormatting.RED)
        }
    }

    private fun preview(source: CommandSourceStack, p: ScriptInstaller.Pending) {
        val lines = mutableListOf<Component>()
        lines += Component.literal("Install server script ").withStyle(ChatFormatting.GOLD)
            .append(Component.literal(p.name).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
            .append(Component.literal(" (${p.lines} lines" + (p.replacesLines?.let { ", replaces the current ${p.name}.kts with $it lines" } ?: "") + ")").withStyle(ChatFormatting.GOLD))
        lines += Component.literal("  from ${p.source}").withStyle(ChatFormatting.GRAY)
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
            .append(button("[Install]", ChatFormatting.GREEN, "Save it as ${p.name}.kts", ClickEvent.RunCommand("mks install confirm ${p.token}")))
            .append(" ")
            .append(button("[Cancel]", ChatFormatting.RED, "Don't install", ClickEvent.RunCommand("mks install cancel ${p.token}")))
        lines += Component.literal("  Or type /mks install confirm (or cancel). Its source is in ${p.previewFile} until then. Scripts can do anything on the server's computer, not just in the game. Only run scripts you trust.")
            .withStyle(ChatFormatting.DARK_GRAY)
        lines.forEach { send(source, it) }
    }

    // ---- share ------------------------------------------------------------------------------------

    /** `/mks share <script>`: a button that copies the script, and its link if it was installed from one. */
    fun share(source: CommandSourceStack, name: String) {
        val file = scripts.scriptDir.resolve("$name.kts")
        if (!file.exists()) return reply(source, "No script named '$name'", ChatFormatting.RED)
        val text = file.readText()
        val message = Component.literal("$name.kts (${ScriptInstaller.lineCount(text)} lines): ").withStyle(ChatFormatting.GRAY)
        if (source.isPlayer && text.length <= MAX_COPY) {
            message.append(button("[Copy]", ChatFormatting.YELLOW, "Copy the script; others install it with \\install clipboard", ClickEvent.CopyToClipboard(text)))
        } else {
            message.append(Component.literal(file.toAbsolutePath().toString()).withStyle(ChatFormatting.WHITE))
        }
        installer.sourceOf(name)?.let { url ->
            message.append(Component.literal(" from ").withStyle(ChatFormatting.GRAY))
                .append(button(url, ChatFormatting.AQUA, "Copy the link; servers install it with /mks install <link>", ClickEvent.CopyToClipboard(url)))
        }
        send(source, message)
    }

    // ---- settings ---------------------------------------------------------------------------------

    /** `/mks settings <script>`: every setting the script declared, with its value. */
    fun showSettings(source: CommandSourceStack, name: String) {
        val settings = scripts.jobs.settings.list(name)
        if (settings.isEmpty()) return reply(source, "$name has no settings (they appear once it has run)", ChatFormatting.GRAY)
        reply(source, "Settings of $name:", ChatFormatting.GOLD)
        for (setting in settings) {
            val line = Component.literal("  ${setting.key} = ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(setting.value.toString()).withStyle(ChatFormatting.WHITE))
                .append(Component.literal("  (${setting.label}: ${setting.kind.describe}; default ${setting.default})").withStyle(ChatFormatting.DARK_GRAY))
            send(source, line.withStyle { it.withClickEvent(ClickEvent.SuggestCommand("/mks settings $name ${setting.key} ")) })
        }
    }

    /** `/mks settings <script> <key> <value>`; a running script gets `onSettingChange`. */
    fun changeSetting(source: CommandSourceStack, name: String, key: String, text: String) {
        val setting = scripts.jobs.settings.list(name).firstOrNull { it.key == key }
            ?: return reply(source, "$name has no setting '$key'", ChatFormatting.RED)
        val value = setting.kind.parse(text.trim())
            ?: return reply(source, "'$text' doesn't fit $key: it takes ${setting.kind.describe}", ChatFormatting.RED)
        val stored = scripts.jobs.settings.set(name, key, value)
        reply(source, "$name: $key = $stored", ChatFormatting.GREEN)
    }

    /** The keys of [name]'s settings, for Tab completion. */
    fun settingKeys(name: String): List<String> = runCatching { scripts.jobs.settings.list(name).map { it.key } }.getOrDefault(emptyList())

    fun close() {
        worker.shutdownNow()
    }

    private fun button(label: String, color: ChatFormatting, hover: String, click: ClickEvent): MutableComponent =
        Component.literal(label).withStyle { it.withColor(color).withHoverEvent(HoverEvent.ShowText(Component.literal(hover))).withClickEvent(click) }

    private fun reply(source: CommandSourceStack, text: String, color: ChatFormatting) = send(source, Component.literal(text).withStyle(color))

    /** From any thread: replies go out on the server thread. */
    private fun send(source: CommandSourceStack, message: Component) = scripts.server.execute { source.sendSystemMessage(message) }

    private companion object {
        /** Longer scripts aren't put in a chat button: shared by their file instead. */
        const val MAX_COPY = 32_000
    }
}
