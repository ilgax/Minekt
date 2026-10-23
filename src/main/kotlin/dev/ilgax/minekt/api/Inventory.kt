package dev.ilgax.minekt.api

import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.ContainerInput

// Inventory, containers and screens. Slot indices are the open menu's indices: read them from
// container().slots. With nothing open, the menu is the player's own inventory (2x2 crafting at
// 0–4, armor 5–8, main inventory 9–35, hotbar 36–44, offhand 45).

/** The open screen (chest, inventory, chat, pause menu, ...), or null when playing. */
val Minekt.screen: ScreenInfo?
    get() = game { mc -> mc.gui.screen()?.toInfo() }

internal fun Screen.toInfo() = ScreenInfo(title.string, javaClass.simpleName, this is AbstractContainerScreen<*>)

/** Slots of the open container menu (chest, furnace, ...), or of the player's inventory if none is open. */
val Minekt.container: ContainerInfo get() = game { mc ->
    val player = mc.player ?: error("Not in a world")
    val menu = player.containerMenu
    val title = mc.gui.screen()?.title?.string ?: "Inventory"
    ContainerInfo(
        title,
        menu.slots.map { slot ->
            val stack = slot.item
            val item = if (stack.isEmpty) null else stack.toInfo(slot.index)
            SlotInfo(
                index = slot.index,
                id = item?.id,
                count = stack.count,
                isPlayerInventory = slot.container is Inventory,
                item = item,
            )
        },
    )
}

/** The item in the main hand, or null if empty. */
val Minekt.heldItem: ItemStackInfo? get() = game { mc ->
    val player = mc.player ?: error("Not in a world")
    player.mainHandItem.takeUnless { it.isEmpty }?.toInfo(player.inventory.selectedSlot)
}

/** The item in the offhand, or null if empty. */
val Minekt.offhandItem: ItemStackInfo? get() = game { mc ->
    mc.player?.offhandItem?.takeUnless { it.isEmpty }?.toInfo(OFFHAND_INVENTORY_SLOT)
}

/** Left-click (button 0) or right-click (button 1) a slot of the open menu: pick up / put down. */
fun Minekt.clickSlot(slot: Int, button: Int = 0) = containerInput(slot, button, ContainerInput.PICKUP)

/** Shift-click: move the stack between the container and the player's inventory. */
fun Minekt.shiftClick(slot: Int) = containerInput(slot, 0, ContainerInput.QUICK_MOVE)

/** Swap a slot with hotbar slot [hotbar] (0–8), like pressing a number key over it. */
fun Minekt.swapWithHotbar(slot: Int, hotbar: Int) {
    require(hotbar in 0..8) { "Hotbar slot must be 0..8" }
    containerInput(slot, hotbar, ContainerInput.SWAP)
}

/** Drop one item from a slot, or the whole stack with [all]. */
fun Minekt.dropSlot(slot: Int, all: Boolean = false) = containerInput(slot, if (all) 1 else 0, ContainerInput.THROW)

/** Close the open screen (sends the close packet for containers). */
fun Minekt.closeScreen() = game { mc ->
    val player = mc.player
    if (player != null && player.containerMenu !== player.inventoryMenu) player.closeContainer()
    else mc.gui.setScreen(null)
}

/** Open the player's inventory screen. */
fun Minekt.openInventory() = game { mc ->
    val player = mc.player ?: error("Not in a world")
    mc.gui.setScreen(InventoryScreen(player))
}

private fun Minekt.containerInput(slot: Int, button: Int, input: ContainerInput) = game { mc ->
    val player = mc.player ?: error("Not in a world")
    val menu = player.containerMenu
    require(slot in menu.slots.indices) { "Slot $slot is out of range (0..${menu.slots.size - 1})" }
    val gameMode = mc.gameMode ?: error("Not in a world")
    gameMode.handleContainerInput(menu.containerId, slot, button, input, player)
}

private const val OFFHAND_INVENTORY_SLOT = 40
