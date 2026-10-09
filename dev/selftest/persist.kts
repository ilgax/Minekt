// Persistence across a real game restart: run in two separate launches of the same world.
// Launch 1 stores values; launch 2 checks they came back (with their types).
fun check(name: String, block: () -> Any?) {
    val result = runCatching(block)
    val ok = result.isSuccess && result.getOrNull() != false
    echo("PERSIST ${if (ok) "OK  " else "FAIL"} $name -> ${result.getOrElse { it.toString() }}")
}

data class Waypoint(val name: String, val pos: BlockPos, val note: String?)

var launches by persistent { 0 }
val homes by persistent(perWorld = true) { mutableMapOf<String, Waypoint>() }
val elsewhere by persistent(key = "homes", perWorld = false) { mutableMapOf<String, Waypoint>() }

launches++
check("launch") { launches }
if (launches == 1) {
    homes["spawn"] = Waypoint("spawn", position.blockPos, null)  // in-place change, saved on exit
    check("stored") { homes }
} else {
    check("perWorld value restored") { homes["spawn"]?.let { "${it.name} at ${it.pos}, note=${it.note}, type=${it::class.simpleName}" } ?: false }
    check("non-perWorld key is separate") { elsewhere.isEmpty() }
}
echo("PERSIST DONE")
sleep(500)
game { it.stop() }
