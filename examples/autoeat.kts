// \autoeat — eats when you're hungry: picks food from your hotbar, holds "use" until you've eaten,
// then goes back to the slot you were on. Only while no menu is open.
// Shows: player.food, the item's data components through game { }, press/release by binding name.
import net.minecraft.core.component.DataComponents

val eatBelow by setting(14, 1..19, label = "Eat when food is below")

/** Hotbar slots (0–8) holding something edible. */
fun foodSlots(): List<Int> = game { mc ->
    val inv = mc.player!!.inventory
    (0..8).filter { inv.getItem(it).has(DataComponents.FOOD) }
}

launch {
    while (true) {
        awaitTicks(20)
        val p = player
        if (p.food >= eatBelow || game { it.gui.screen() } != null) continue
        val slot = foodSlots().firstOrNull() ?: continue
        val back = p.selectedSlot
        selectSlot(slot)
        press("use")
        // Eating takes 32 ticks; wait until the food bar goes up (or give up after 3 seconds).
        for (tick in 0 until 60) {
            if (player.food > p.food) break
            awaitTicks(1)
        }
        release("use")
        selectSlot(back)
    }
}
echo("Auto-eat on (below ${eatBelow} food). \\kill to stop.")
