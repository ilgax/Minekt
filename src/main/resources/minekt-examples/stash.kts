// \stash [item] — look at a chest and run this: it opens the chest and moves every stack of
// [item] (default: cobblestone) from your inventory into it.
// Shows: targeting, interaction, containers, coroutines.
val item = args.firstOrNull()?.let { if (":" in it) it else "minecraft:$it" } ?: "minecraft:cobblestone"
val target = targetedBlock ?: run { echo("Look at a chest first"); stop() }

val chest = runBlocking {
    val opened = async { withTimeoutOrNull(2000) { awaitScreen { it.isContainer } } }
    delay(50)
    useOn(target.pos, target.face)
    opened.await()
} ?: run { echo("That didn't open a container"); stop() }

var moved = 0
for (slot in container.playerSlots.filter { it.id == item }) {
    shiftClick(slot.index)
    moved += slot.count
    waitTicks(1)
}
closeScreen()
echo("Moved $moved $item into the ${chest.title}")
