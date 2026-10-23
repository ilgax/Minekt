// In-game test for class rewriting on real game classes. Singleplayer test world.
@file:OptIn(ExperimentalHooks::class)

import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.*

fun check(name: String, block: () -> Any?) {
    val result = runCatching(block)
    val ok = result.isSuccess && result.getOrNull() != false && !result.getOrNull().toString().startsWith("FALSE")
    echo("RW ${if (ok) "OK  " else "FAIL"} $name -> ${result.getOrElse { it.toString() }}")
}

infix fun Boolean.then(detail: Any?): Any? = if (this) detail else "FALSE $detail"

var waited = 0
while (blockId(player.position.blockPos.below()) == "minecraft:void_air" && waited < 1200) { waitTicks(20); waited += 20 }
waitTicks(20)

fun version() = game { it.launchedVersion }
val original = version()

check("rewrite a game method and call it") {
    val rewrite = rewriteClass<Minecraft> { node ->
        val method = node.methods.first { it.name == "getLaunchedVersion" && it.desc == "()Ljava/lang/String;" }
        method.instructions.clear()
        method.tryCatchBlocks.clear()
        method.localVariables?.clear()
        method.instructions.add(LdcInsnNode("rewritten by a script"))
        method.instructions.add(InsnNode(Opcodes.ARETURN))
    }
    val changed = version()
    rewrite.remove()
    (changed == "rewritten by a script" && version() == original) then "'$changed', then back to '$original'"
}

check("classBytes shows mixins already applied (Minekt's own ClientLevel mixin)") {
    val node = ClassNode().also { ClassReader(classBytes<ClientLevel>()).accept(it, 0) }
    val mixinMethods = node.methods.map { it.name }.filter { "minekt" in it }
    mixinMethods.isNotEmpty() then mixinMethods.take(3)
}

check("a hook on a rewritten game method wraps the new code") {
    val rewrite = transformClass<Minecraft> { bytes -> bytes } // identity: still a rewrite, keeps working with hooks
    val hook = hookAfter<Minecraft>("getLaunchedVersion") { it.result = "${it.result}+hook" }
    val v = version()
    hook.remove(); rewrite.remove()
    (v == "$original+hook" && version() == original) then v
}

check("adding a method to a loaded game class is refused with an explanation") {
    val message = runCatching {
        rewriteClass<Minecraft> { it.methods.add(MethodNode(Opcodes.ACC_PUBLIC, "minektExtra", "()V", null, null).apply { instructions.add(InsnNode(Opcodes.RETURN)) }) }
    }.exceptionOrNull()?.message.orEmpty()
    ("refused" in message && version() == original) then message
}

echo("RW DONE")
sleep(1000)
game { it.stop() }
