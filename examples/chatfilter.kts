// \chatfilter — hides chat and server messages containing words you choose (edit them under the script's
// Settings in \menu), and counts what it hid. Experimental: uses Fabric API events like a mod would.
// Shows: Fabric events from a script, settings, a small HUD counter.
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents

val words by setting("join our discord, free vip, buy now", label = "Hide messages with (comma separated)")
val hidden = java.util.concurrent.atomic.AtomicInteger()

fun blocked(text: String): Boolean {
    val lower = text.lowercase()
    return words.split(',').map { it.trim().lowercase() }.any { it.isNotEmpty() && it in lower }
}

// Both run on the game thread for every message: returning false hides it.
ClientReceiveMessageEvents.ALLOW_CHAT.register { message, _, _, _, _ ->
    !blocked(message.string).also { if (it) hidden.incrementAndGet() }
}
ClientReceiveMessageEvents.ALLOW_GAME.register { message, overlay ->
    overlay || !blocked(message.string).also { if (it) hidden.incrementAndGet() }
}

onHudRender { g ->
    val n = hidden.get()
    if (n > 0) g.text("$n hidden", 4, g.height - 12, 0x808080)
}
echo("Filtering chat. \\kill chatfilter to stop.")
