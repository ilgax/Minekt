// \chatbot — reacts to chat until \kill'ed. Handlers keep the script alive.
echo("Chatbot listening. Say 'ping' (or \\kill ${jobId}).")
onChat { (text, sender) ->
    if (sender == player.name) return@onChat // not our own messages
    if (text.endsWith("ping")) echo("pong!")
    if (text.endsWith("bye bot")) { echo("bye!"); stop() }
}
