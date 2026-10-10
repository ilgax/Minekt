// Companion to selftest.kts: registers the Fabric hooks; selftest.kts (plus real key presses) drives them.
command("mkt", completions = listOf("alpha", "beta")) { args ->
    echo("HOOKTEST command args=$args")
    if (args.firstOrNull() == "stop") stop()
}
val hookKey = keybind("Hooktest key")
hookKey.onPress { echo("HOOKTEST keybind fired, bound to ${hookKey.key}") }
echo("HOOKTEST ready")
