// In-game test for item details (durability, enchantments, names, lore, food) and block details (state,
// light, biome, hardness, sign text). Singleplayer test world.

fun check(name: String, block: () -> Any?) {
    val result = runCatching(block)
    val ok = result.isSuccess && result.getOrNull() != false && !result.getOrNull().toString().startsWith("FALSE")
    echo("DT ${if (ok) "OK  " else "FAIL"} $name -> ${result.getOrElse { it.toString() }}")
}

infix fun Boolean.then(detail: Any?): Any? = if (this) detail else "FALSE $detail"

fun server(command: String) = game { mc ->
    val s = mc.singleplayerServer ?: error("needs singleplayer")
    s.execute { s.commands.performPrefixedCommand(s.createCommandSourceStack(), command) }
}

var waited = 0
while (blockAt(BlockPos.containing(player.position).below()).id == "minecraft:void_air" && waited < 1200) { waitTicks(20); waited += 20 }
waitTicks(40)
val me = player.name
server("gamemode survival $me")
server("time set 18000")
server("clear $me")
server("fill -6 199 -6 6 199 16 minecraft:stone")
server("fill -6 200 -6 6 204 16 minecraft:air")
server("tp $me 0.5 200 0.5 0 0")
server("give $me minecraft:diamond_sword[minecraft:enchantments={\"minecraft:sharpness\":3,\"minecraft:looting\":2},minecraft:damage=100,minecraft:custom_name=\"Blade\",minecraft:lore=[\"An old sword\"]]")
server("give $me minecraft:bread 12")
server("give $me minecraft:enchanted_book[minecraft:stored_enchantments={\"minecraft:mending\":1}]")
server("give $me minecraft:ender_pearl 3")
// Blocks: ripe wheat on farmland, a lit torch, a sign with text, obsidian and bedrock, all under the night sky.
server("setblock 2 199 3 minecraft:farmland")
server("setblock 2 200 3 minecraft:wheat[age=7]")
server("setblock -2 200 3 minecraft:torch")
server("setblock 0 200 5 minecraft:oak_sign[rotation=8]{front_text:{messages:[\"Shop\",\"Diamonds\",\"5 each\",\"\"]}}")
server("setblock 3 200 5 minecraft:obsidian")
server("setblock 4 200 5 minecraft:bedrock")
waitTicks(40)

val items = inventory
check("item details: durability, enchantments, custom name and lore") {
    val sword = items.firstOrNull { it.id == "minecraft:diamond_sword" } ?: return@check "FALSE no sword in $items"
    (sword.name == "Blade" && sword.customName == "Blade" && sword.damage == 100 && sword.maxDamage == 1561 && sword.durability == 1461 &&
        sword.enchantments == mapOf("minecraft:sharpness" to 3, "minecraft:looting" to 2) && sword.lore == listOf("An old sword") &&
        sword.maxStackSize == 1 && !sword.isFood) then sword
}
check("item details: food, stack size, and a book's stored enchantments") {
    val bread = items.first { it.id == "minecraft:bread" }
    val book = items.first { it.id == "minecraft:enchanted_book" }
    val pearl = items.first { it.id == "minecraft:ender_pearl" }
    (bread.isFood && bread.nutrition == 5 && bread.maxStackSize == 64 && bread.durability == null && bread.name == "Bread" &&
        book.enchantments == mapOf("minecraft:mending" to 1) && pearl.maxStackSize == 16 && !pearl.isFood) then
        "bread ${bread.nutrition} food, book ${book.enchantments}, pearls stack to ${pearl.maxStackSize}"
}
check("container slots carry the same details") {
    val slot = container.slots.firstOrNull { it.id == "minecraft:diamond_sword" }
    (slot?.item?.durability == 1461 && slot.item?.enchantments?.get("minecraft:sharpness") == 3) then slot?.item
}

check("block state: ripe wheat has age 7") {
    val wheat = blockAt(BlockPos(2, 200, 3))
    (wheat.id == "minecraft:wheat" && wheat.properties["age"] == "7" && !wheat.isSolid) then wheat.properties
}
check("light: a torch gives off light, the night sky doesn't") {
    val torch = blockAt(BlockPos(-2, 200, 3))
    val nextTo = blockAt(BlockPos(-1, 200, 3))
    val open = blockAt(BlockPos(5, 200, 12))
    (torch.lightEmission == 14 && nextTo.blockLight == 13 && open.skyLight == 15 && open.light < open.skyLight) then
        "torch emits ${torch.lightEmission}, next to it block light ${nextTo.blockLight}; open ground sky ${open.skyLight}, light now ${open.light}"
}
check("a sign's text") {
    val sign = blockAt(BlockPos(0, 200, 5))
    (sign.signFront == listOf("Shop", "Diamonds", "5 each", "") && sign.signBack == listOf("", "", "", "")) then sign.signFront
}
check("hardness, air, solid and biome") {
    val obsidian = blockAt(BlockPos(3, 200, 5))
    val bedrock = blockAt(BlockPos(4, 200, 5))
    val air = blockAt(BlockPos(0, 202, 0))
    (obsidian.hardness == 50f && obsidian.isSolid && bedrock.hardness == -1f && air.isAir && air.biome.startsWith("minecraft:")) then
        "obsidian ${obsidian.hardness}, bedrock ${bedrock.hardness}, biome ${air.biome}"
}

server("fill -6 200 -6 6 204 16 minecraft:air")
server("time set 6000")
server("clear $me")
echo("DT DONE")
sleep(1000)
game { it.stop() }
