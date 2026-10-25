// \autoeat — eats when you're hungry: picks food from your hotbar, holds "use" until you've eaten,
// then goes back to the slot you were on. Only while no menu is open.
// Shows: player.food, item details (isFood), press/release by binding name.

val eatBelow by setting(14, 1..19, label = "Eat when food is below")

/** Hotbar slots (0–8) holding something edible, the most filling first. */
fun foodSlots(): List<Int> = inventory.filter { it.slot in 0..8 && it.isFood }.sortedByDescending { it.nutrition }.map { it.slot }

launch {
    while (true) {
        awaitTicks(20)
        val p = player
        if (p.food >= eatBelow || game { it.gui.screen() } != null) continue
        val slot = foodSlots().firstOrNull() ?: continue
        val back = p.selectedSlot
        game { it.player!!.inventory.selectedSlot = slot }
        press("use")
        // Eating takes 32 ticks; wait until the food bar goes up (or give up after 3 seconds).
        for (tick in 0 until 60) {
            if (player.food > p.food) break
            awaitTicks(1)
        }
        release("use")
        game { it.player!!.inventory.selectedSlot = back }
    }
}
echo("Auto-eat on (below ${eatBelow} food). \\kill to stop.")
