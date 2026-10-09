// Companion to selftest.kts: registers the Fabric hooks; selftest.kts (plus real key presses) drives them.
command("mkt", completions = listOf("alpha", "beta")) { args ->
    echo("HOOKTEST command args=$args")
    if (args.firstOrNull() == "stop") stop()
}
onBinding(1) { echo("HOOKTEST onBinding(1) fired, bound to ${bindingKey(1)}") }
onKey("k") { echo("HOOKTEST onKey(k) fired") }
echo("HOOKTEST ready")
