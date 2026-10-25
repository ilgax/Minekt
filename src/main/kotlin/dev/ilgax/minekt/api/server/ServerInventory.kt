package dev.ilgax.minekt.api.server

import dev.ilgax.minekt.api.ItemStackInfo
import dev.ilgax.minekt.api.toInfo
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.world.Container
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.ItemLore
import net.minecraft.world.item.enchantment.EnchantmentHelper

// Players' inventories, their ender chests, and chests, barrels, furnaces… in the world:
//
//   val inv = inventory("Steve")
//   echo(inv.items.map { "${it.count} ${it.name}" })
//   inv.setSlot(0, "minecraft:diamond_sword", name = "Excalibur", enchantments = mapOf("sharpness" to 5))
//   if (inv.take("minecraft:emerald", 3) == 3) inv.give("minecraft:diamond")
//   container(BlockPos(10, 64, 10))?.clearSlot(0)
//
// A player's inventory slots: 0–8 hotbar, 9–35 the rest, 36–39 armor (feet, legs, chest, head), 40 offhand.

/** A player's inventory or ender chest, or a block's container, from [inventory], [enderChest] or [container]. */
class ServerInventory internal constructor(
    private val api: ServerMinekt,
    /** What this is, like `Steve's inventory`. */
    val name: String,
    private val open: (MinecraftServer) -> Container,
) {
    /** How many slots it has. */
    val size: Int get() = read { it.containerSize }

    /** Every slot that isn't empty, with its item's details. */
    val items: List<ItemStackInfo> get() = read { container ->
        (0 until container.containerSize).mapNotNull { slot -> container.getItem(slot).takeUnless { it.isEmpty }?.toInfo(slot) }
    }

    /** The item in [slot], or null if it's empty. */
    operator fun get(slot: Int): ItemStackInfo? = read { container ->
        checkSlot(container, slot)
        container.getItem(slot).takeUnless { it.isEmpty }?.toInfo(slot)
    }

    /** How many of [item] (an id like `minecraft:diamond`) are in it. */
    fun count(item: String): Int = read { container ->
        val type = itemType(item)
        (0 until container.containerSize).sumOf { container.getItem(it).takeIf { stack -> stack.`is`(type) }?.count ?: 0 }
    }

    /**
     * Put [count] of [item] in [slot], replacing what's there. [name] and [lore] are shown in its tooltip, [enchantments]
     * map ids like `minecraft:sharpness` (or just `sharpness`) to levels (an enchanted book stores them), and [damage]
     * is how worn it is.
     */
    fun setSlot(
        slot: Int,
        item: String,
        count: Int = 1,
        name: String? = null,
        lore: List<String> = emptyList(),
        enchantments: Map<String, Int> = emptyMap(),
        damage: Int = 0,
    ) = write { server, container ->
        checkSlot(container, slot)
        container.setItem(slot, stack(server, item, count, name, lore, enchantments, damage))
    }

    /** Empty [slot]. */
    fun clearSlot(slot: Int) = write { _, container ->
        checkSlot(container, slot)
        container.setItem(slot, ItemStack.EMPTY)
    }

    /** Add [count] of [item] where it fits, stacking with what's there. Returns false if it didn't all fit (what fit stays). */
    fun give(item: String, count: Int = 1): Boolean = write { server, container ->
        val stack = stack(server, item, count, null, emptyList(), emptyMap(), 0)
        if (container is Inventory) return@write container.add(stack)
        for (pass in 0..1) {
            for (slot in 0 until container.containerSize) {
                if (stack.isEmpty) break
                val there = container.getItem(slot)
                val fits = if (pass == 0) !there.isEmpty && ItemStack.isSameItemSameComponents(there, stack) else there.isEmpty
                if (!fits || !container.canPlaceItem(slot, stack)) continue
                val limit = minOf(container.getMaxStackSize(stack), stack.maxStackSize)
                val moved = minOf(stack.count, limit - there.count).takeIf { it > 0 } ?: continue
                container.setItem(slot, stack.copyWithCount(there.count + moved))
                stack.shrink(moved)
            }
        }
        stack.isEmpty
    }

    /** Take up to [count] of [item] out. Returns how many were taken. */
    fun take(item: String, count: Int = 1): Int = write { _, container ->
        require(count > 0) { "count must be at least 1" }
        val type = itemType(item)
        var taken = 0
        for (slot in 0 until container.containerSize) {
            if (taken == count) break
            val there = container.getItem(slot)
            if (!there.`is`(type)) continue
            taken += container.removeItem(slot, count - taken).count
        }
        taken
    }

    override fun toString() = name

    private fun <T> read(block: (Container) -> T): T = api.server { block(open(it)) }

    private fun <T> write(block: (MinecraftServer, Container) -> T): T = api.server { server ->
        val container = open(server)
        block(server, container).also { container.setChanged() }
    }

    private fun checkSlot(container: Container, slot: Int) =
        require(slot in 0 until container.containerSize) { "Slot $slot is out of range for $name (0..${container.containerSize - 1})" }

    private fun itemType(item: String) =
        BuiltInRegistries.ITEM.getOptional(api.identifier(item)).orElseThrow { IllegalArgumentException("No item '$item'") }

    private fun stack(server: MinecraftServer, item: String, count: Int, name: String?, lore: List<String>, enchantments: Map<String, Int>, damage: Int): ItemStack {
        require(count > 0) { "count must be at least 1" }
        val stack = ItemStack(itemType(item), count)
        name?.let { stack.set(DataComponents.CUSTOM_NAME, Component.literal(it)) }
        if (lore.isNotEmpty()) stack.set(DataComponents.LORE, ItemLore(lore.map(Component::literal)))
        if (enchantments.isNotEmpty()) {
            val registry = server.registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
            EnchantmentHelper.updateEnchantments(stack) { mutable ->
                for ((id, level) in enchantments) {
                    val key = ResourceKey.create(Registries.ENCHANTMENT, api.identifier(id))
                    mutable.set(registry.get(key).orElseThrow { IllegalArgumentException("No enchantment '$id'") }, level)
                }
            }
        }
        if (damage != 0) stack.damageValue = damage
        return stack
    }
}

/** [player]'s inventory: slots 0–8 hotbar, 9–35 the rest, 36–39 armor (feet, legs, chest, head), 40 offhand. */
fun ServerMinekt.inventory(player: String): ServerInventory =
    ServerInventory(this, "$player's inventory") { s -> online(s, player).inventory }

/** [player]'s ender chest (27 slots). */
fun ServerMinekt.enderChest(player: String): ServerInventory =
    ServerInventory(this, "$player's ender chest") { s -> online(s, player).enderChestInventory }

/**
 * The container at [pos] in [dimension] (a chest, barrel, furnace, hopper, shulker box…), or null if there's none.
 * A double chest is two containers, one per half.
 */
fun ServerMinekt.container(pos: BlockPos, dimension: String = "minecraft:overworld"): ServerInventory? {
    val at = pos.immutable()
    val exists = server { s -> level(s, dimension).getBlockEntity(at) is Container }
    if (!exists) return null
    return ServerInventory(this, "the container at ${at.x}, ${at.y}, ${at.z}") { s ->
        level(s, dimension).getBlockEntity(at) as? Container ?: throw IllegalStateException("There's no container at ${at.x}, ${at.y}, ${at.z} anymore")
    }
}
