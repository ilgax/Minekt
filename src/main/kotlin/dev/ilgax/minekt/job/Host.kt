package dev.ilgax.minekt.job

import net.minecraft.client.Minecraft
import java.nio.file.Path

/** The environment jobs run in. Abstracted so the engine can be exercised without a running game. */
interface Host {
    /** Directory scripts are loaded from (`.minecraft/minekt`). */
    val scriptDir: Path

    /** Show a message to the player. */
    fun echo(message: String, kind: MessageKind = MessageKind.SCRIPT)

    /** Run [block] on the client thread and wait for its result. */
    fun <T> onGameThread(block: (Minecraft) -> T): T
}

enum class MessageKind { SCRIPT, INFO, ERROR }
