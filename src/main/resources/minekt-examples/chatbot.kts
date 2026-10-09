// \chatbot — reacts to chat until \kill'ed. Handlers keep the script alive.
echo("Chatbot listening. Say 'ping' (or \\kill ${jobId}).")
onChat { message ->
    if (message.endsWith("ping")) echo("pong!")
    if (message.endsWith("bye bot")) { echo("bye!"); stop() }
}
