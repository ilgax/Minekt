// Run from autorun.txt's [startup] section: before any world, on the title screen.
val where = game { mc -> "world=${mc.level != null} screen=${mc.gui.screen()?.javaClass?.simpleName}" }
echo("STARTUP ran: $where")
echo("STARTUP eval: " + eval("x * 2", "x" to 21))
echo("STARTUP runScript: " + startScript("double", "4").await())
sleep(1000)
game { it.stop() }
