// \realplayer — a real player on a real server: run `./gradlew prodServer` with dev/servertest/realplayer.kts in the
// server's autorun.txt and online-mode=false, then join it with this in the client's autorun.txt (as [multiplayer]).
// Checks the server's packet hooks over a real connection, the /mks install and share chat buttons, and that /mks
// leaves the command tree when the player loses op. Closes the game at the end.
@file:OptIn(ExperimentalPackets::class)

import net.minecraft.client.gui.components.ChatComponent
import net.minecraft.client.multiplayer.chat.GuiMessage
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.protocol.game.ClientboundSetActionBarTextPacket
import it.unimi.dsi.fastutil.booleans.BooleanConsumer
import java.util.concurrent.CopyOnWriteArrayList

fun check(name: String, block: () -> Any?) {
    val result = runCatching(block)
    val ok = result.isSuccess && result.getOrNull() != false
    echo("REAL ${if (ok) "OK  " else "FAIL"} $name -> ${result.getOrElse { it.toString() }}")
}

fun waitFor(seconds: Int, condition: () -> Boolean): Boolean {
    repeat(seconds * 10) { if (condition()) return true; sleep(100) }
    return condition()
}

infix fun Boolean.then(detail: Any?): Any? = if (this) detail else false

val chatLines = CopyOnWriteArrayList<String>()
onChat { chatLines += it.text }
var actionBar: String? = null
onPacket<ClientboundSetActionBarTextPacket> { actionBar = it.text().string }

/** The click events of every chat line, newest first. */
fun chatClicks(): List<ClickEvent> = game { mc ->
    val field = ChatComponent::class.java.getDeclaredField("allMessages").apply { isAccessible = true }
    @Suppress("UNCHECKED_CAST")
    (field.get(mc.gui.hud.chat) as List<GuiMessage>).flatMap { it.content().toFlatList() }.mapNotNull { it.style.clickEvent }
}

/** Run a chat button's command exactly like clicking it does. */
fun click(command: String) = game { it.player!!.connection.sendUnattendedCommand(command, null) }

fun mksSubcommands(): List<String>? = game { mc -> mc.player!!.connection.commands.root.getChild("mks")?.children?.map { it.name } }

val serverDir = java.io.File("../build/prodserver/minekt")

sleep(3000)
check("the server opped us, so /mks is offered with all its subcommands") {
    waitFor(10) { mksSubcommands()?.contains("install") == true }
    val subs = mksSubcommands().orEmpty()
    listOf("run", "eval", "kill", "jobs", "log", "install", "share", "settings").all { it in subs } then subs
}
check("onPacket sees our chat packet, with our name") {
    chat("pkt ping")
    waitFor(10) { chatLines.any { it.startsWith("REALSRV packet from ${player.name}: pkt ping") } } then chatLines.last { "REALSRV packet" in it }
}
check("sendPacket reaches us") {
    chat("/sendpkt")
    waitFor(10) { actionBar == "REALSRV action bar" } then actionBar
}
check("interceptPacketSent drops a packet on its way to us") {
    chat("/secrettest")
    val visible = waitFor(10) { chatLines.any { it == "REALSRV visible line" } }
    sleep(1000)
    (visible && chatLines.none { "secret" in it }) then chatLines.filter { "REALSRV" in it && "packet" !in it }
}

val web = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
web.createContext("/realinst.kts") { exchange ->
    val body = "echo(\"realinst ran\")\n".toByteArray()
    exchange.sendResponseHeaders(200, body.size.toLong())
    exchange.responseBody.use { it.write(body) }
}
web.start()
try {
    java.io.File(serverDir, "realinst.kts").delete()
    check("/mks install shows a preview with an [Install] button that installs it") {
        chat("/mks install http://127.0.0.1:${web.address.port}/realinst.kts")
        var confirm: String? = null
        waitFor(60) {
            confirm = chatClicks().mapNotNull { (it as? ClickEvent.RunCommand)?.command() }.firstOrNull { it.startsWith("mks install confirm ") }
            confirm != null
        }
        val command = confirm ?: return@check "no [Install] button"
        click(command)
        // The game asks before a chat button runs a command that needs permissions: answer like the player would.
        waitFor(5) { game { it.gui.screen() is net.minecraft.client.gui.screens.ConfirmScreen } }
        game { mc ->
            val screen = mc.gui.screen() as? net.minecraft.client.gui.screens.ConfirmScreen ?: return@game
            val field = net.minecraft.client.gui.screens.ConfirmScreen::class.java.declaredFields.first { it.type == BooleanConsumer::class.java }
            (field.apply { isAccessible = true }.get(screen) as BooleanConsumer).accept(true)
        }
        val installed = waitFor(10) { java.io.File(serverDir, "realinst.kts").isFile }
        if (!installed) error("not installed; screen ${game { it.gui.screen()?.javaClass?.name }}, chat ${chatLines.takeLast(3)}")
        command
    }
    check("/mks share gives a [Copy] button with the script") {
        chat("/mks share realinst")
        waitFor(10) {
            chatClicks().any { it is ClickEvent.CopyToClipboard && it.value().contains("echo(\"realinst ran\")") }
        } then "copies ${java.io.File(serverDir, "realinst.kts").readText().length} characters"
    }
} finally {
    web.stop(0)
}

check("without op, /mks leaves the command tree") {
    chat("/deopme")
    waitFor(10) { mksSubcommands() == null } then "gone"
}

echo("REAL DONE")
sleep(1000)
game { it.stop() }
