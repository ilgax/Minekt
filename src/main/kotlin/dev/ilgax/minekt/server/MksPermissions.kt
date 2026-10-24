package dev.ilgax.minekt.server

import net.fabricmc.fabric.api.permission.v1.PermissionContextOwner
import net.fabricmc.fabric.api.util.TriState
import net.minecraft.commands.CommandSourceStack
import net.minecraft.resources.Identifier
import net.minecraft.server.permissions.PermissionLevel

/**
 * Permission nodes for `/mks` and scripts' commands, checked through Fabric's permission API so permission-manager
 * mods can grant or deny them per player. Without such a mod (or when it says nothing about a node) each node falls
 * back to a vanilla permission level. The console has every level, so it's always allowed.
 *
 * Nodes are ids in the `minekt` namespace (`minekt:command.run`); permission managers usually show them as
 * `minekt.command.run`.
 */
internal object MksPermissions {
    enum class Node(path: String, val level: PermissionLevel) {
        JOBS("command.jobs", PermissionLevel.GAMEMASTERS),
        LOG("command.log", PermissionLevel.GAMEMASTERS),
        RUN("command.run", PermissionLevel.OWNERS),
        KILL("command.kill", PermissionLevel.OWNERS),
        SETTINGS("command.settings", PermissionLevel.OWNERS),
        EVAL("command.eval", PermissionLevel.OWNERS),
        INSTALL("command.install", PermissionLevel.OWNERS),
        SHARE("command.share", PermissionLevel.OWNERS),
        /** Seeing what scripts print and their errors (and clicking their chat buttons). */
        NOTIFY("notify", PermissionLevel.GAMEMASTERS),
        ;

        val id: Identifier = Identifier.fromNamespaceAndPath(NAMESPACE, path)
    }

    private const val NAMESPACE = "minekt"

    fun allows(source: CommandSourceStack, node: Node): Boolean =
        (source as PermissionContextOwner).checkPermission(node.id, node.level)

    /** Running the script [name]: its own node `minekt:script.run.<name>` decides if set, else [Node.RUN]. */
    fun mayRun(source: CommandSourceStack, name: String): Boolean =
        explicit(source, scriptRunNode(name)) ?: allows(source, Node.RUN)

    /** Whether `/mks run` is any use to [source]: [Node.RUN], or one of [scripts]' own nodes. */
    fun mayRunAny(source: CommandSourceStack, scripts: List<String>): Boolean =
        allows(source, Node.RUN) || scripts.any { explicit(source, scriptRunNode(it)) == true }

    /** Using a script's command `/name`: `minekt:script.command.<name>` decides if set, else [fallback] (EVERYONE / OPS). */
    fun mayUseCommand(source: CommandSourceStack, name: String, fallback: () -> Boolean): Boolean =
        explicit(source, commandNode(name)) ?: fallback()

    fun scriptRunNode(script: String): Identifier = Identifier.fromNamespaceAndPath(NAMESPACE, "script.run." + nodePart(script))

    fun commandNode(command: String): Identifier = Identifier.fromNamespaceAndPath(NAMESPACE, "script.command." + nodePart(command))

    /** Node paths are lowercase `a-z0-9_.-`: script names are lowercased, anything else becomes `_`. */
    fun nodePart(name: String): String = name.lowercase().replace(Regex("[^a-z0-9_.-]"), "_")

    /** What a permission manager says about [node], or null when it says nothing. */
    private fun explicit(source: CommandSourceStack, node: Identifier): Boolean? =
        when ((source as PermissionContextOwner).checkPermission(node)) {
            TriState.TRUE -> true
            TriState.FALSE -> false
            else -> null
        }
}
