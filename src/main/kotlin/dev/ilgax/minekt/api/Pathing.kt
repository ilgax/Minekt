package dev.ilgax.minekt.api

import dev.ilgax.minekt.pathing.Cell
import dev.ilgax.minekt.pathing.LevelView
import dev.ilgax.minekt.pathing.Node
import dev.ilgax.minekt.pathing.PathPlanner
import dev.ilgax.minekt.job.Job
import net.minecraft.client.Minecraft
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.sqrt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

// Walking somewhere by itself:
//
//   walkTo(100, 64, -20)                                   // blocks until there (or it gives up)
//   val r = walkTo(chest.pos, sprint = true)                // a block you can't stand in: stops next to it
//   if (!r.arrived) echo("couldn't get there: ${r.reason}")
//   walkToNearest { pos -> getBlock(pos.offset(dy = -1)) == "minecraft:grass_block" }
//   val route = pathTo(0, 64, 0)                            // just plan, e.g. to draw it first
//
// Minekt plans the route itself through loaded chunks (walking, one-block jumps, safe drops,
// swimming; never into lava, fire, cactus, …) and walks it with the movement keys, re-planning when
// it gets stuck or pushed off course. Targets beyond loaded chunks, or with no route Minekt can find,
// are handed to Baritone if it's installed; without Baritone, far targets are approached in stages.

enum class WalkStatus { ARRIVED, NO_PATH, STUCK, TIMED_OUT }

/** How a walk ended. */
data class WalkResult(val status: WalkStatus, val reason: String, val usedBaritone: Boolean = false) {
    val arrived: Boolean get() = status == WalkStatus.ARRIVED
}

/**
 * Walk to (x, y, z): the spot for your feet. If that spot can't hold you (it's a chest, a wall, …),
 * stops within reach of it instead; pass [within] to choose how close is close enough.
 *
 * @param sprint sprint while walking.
 * @param avoid extra block ids never to walk into or onto.
 * @param useBaritone hand over to Baritone, if installed, for targets Minekt can't reach itself.
 * @param showPath draw the planned route in the world while walking.
 */
fun Minekt.walkTo(
    x: Int, y: Int, z: Int,
    within: Double? = null,
    sprint: Boolean = false,
    timeout: Duration = 2.minutes,
    avoid: Collection<String> = emptyList(),
    useBaritone: Boolean = true,
    showPath: Boolean = false,
): WalkResult {
    val target = Node(x, y, z)
    val reach = within ?: game { mc ->
        val planner = PathPlanner.to(LevelView(mc.level ?: error("Not in a world"), avoid.toSet()), target, target)
        if (planner.standable(x, y, z)) 0.0 else 1.8
    }
    return Walker(this, sprint, avoid.toSet(), showPath, timeout).walk(
        plan = { view, start -> PathPlanner.to(view, start, target, reach) },
        arrived = { feet -> feet.distanceTo(target) <= reach + 0.01 },
        baritone = if (useBaritone && baritone.isAvailable) ({ baritoneWalk(x, y, z, reach) }) else null,
    )
}

fun Minekt.walkTo(pos: BlockPos, within: Double? = null, sprint: Boolean = false, timeout: Duration = 2.minutes, avoid: Collection<String> = emptyList(), useBaritone: Boolean = true, showPath: Boolean = false) =
    walkTo(pos.x, pos.y, pos.z, within, sprint, timeout, avoid, useBaritone, showPath)

fun Minekt.walkTo(pos: Vec3, within: Double? = null, sprint: Boolean = false, timeout: Duration = 2.minutes, avoid: Collection<String> = emptyList(), useBaritone: Boolean = true, showPath: Boolean = false) =
    walkTo(pos.blockPos, within, sprint, timeout, avoid, useBaritone, showPath)

/**
 * Walk to the nearest spot (by walking distance, within loaded chunks) where [goal] is true for the
 * feet position, e.g. `walkToNearest { getBlock(it) == "minecraft:water" }`.
 */
fun Minekt.walkToNearest(
    sprint: Boolean = false,
    timeout: Duration = 2.minutes,
    avoid: Collection<String> = emptyList(),
    maxSpots: Int = 40_000,
    showPath: Boolean = false,
    goal: (BlockPos) -> Boolean,
): WalkResult {
    val test = { n: Node -> goal(BlockPos(n.x, n.y, n.z)) }
    return Walker(this, sprint, avoid.toSet(), showPath, timeout).walk(
        plan = { view, start -> PathPlanner.nearest(view, start, maxNodes = maxSpots, goal = test) },
        arrived = test,
        baritone = null,
        stagesWhenPartial = false,
    )
}

/** Plan a route to (x, y, z) without walking it: the feet positions in order, or null if there's none in loaded chunks. */
fun Minekt.pathTo(x: Int, y: Int, z: Int, within: Double = 0.0, avoid: Collection<String> = emptyList()): List<BlockPos>? {
    val target = Node(x, y, z)
    val walker = Walker(this, false, avoid.toSet(), false, 1.minutes)
    val result = walker.plan { view, start -> PathPlanner.to(view, start, target, within) }
    return (result as? PathPlanner.Result.Found)?.path?.map { BlockPos(it.x, it.y, it.z) }
}

private fun Minekt.baritoneWalk(x: Int, y: Int, z: Int, reach: Double): WalkResult {
    baritone.goto(x, y, z)
    baritone.await()
    val feet = player.position
    val distance = feet.distanceTo(Vec3(x + 0.5, y.toDouble(), z + 0.5))
    return if (distance <= reach + 1.0) WalkResult(WalkStatus.ARRIVED, "arrived (via Baritone)", usedBaritone = true)
    else WalkResult(WalkStatus.NO_PATH, "Baritone stopped %.1f blocks away".format(distance), usedBaritone = true)
}

/** Plans with [PathPlanner] on the game thread and steers the player along the route with the movement keys. */
private class Walker(
    private val api: Minekt,
    private val sprint: Boolean,
    private val avoid: Set<String>,
    private val showPath: Boolean,
    private val timeout: Duration,
) {
    private val shapes = mutableListOf<ShapeHandle>()
    private val deadline = System.currentTimeMillis() + timeout.inWholeMilliseconds

    private class State(val x: Double, val y: Double, val z: Double, val onGround: Boolean, val inWater: Boolean) {
        val feet get() = Node(floor(x).toInt(), floor(y + 0.01).toInt(), floor(z).toInt())
    }

    private fun state(): State = api.game { mc ->
        val p = mc.player ?: error("Not in a world")
        State(p.x, p.y, p.z, p.onGround(), p.isInWater)
    }

    /** Run the search a few thousand spots per tick, so a long one doesn't freeze the game. */
    fun plan(make: (LevelView, Node) -> PathPlanner): PathPlanner.Result {
        val start = state().feet
        lateinit var planner: PathPlanner
        api.game { mc ->
            val view = LevelView(mc.level ?: error("Not in a world"), avoid)
            // Standing on a slab or carpet puts the feet inside that block: plan from the spot above.
            val from = if (view.cell(start.x, start.y, start.z).let { it == Cell.SOLID || it == Cell.TALL }) start.copy(y = start.y + 1) else start
            planner = make(view, from)
        }
        while (true) {
            api.game { planner.step(SEARCH_PER_TICK) }?.let { return it }
            api.waitTicks(1)
        }
    }

    fun walk(
        plan: (LevelView, Node) -> PathPlanner,
        arrived: (Node) -> Boolean,
        baritone: (() -> WalkResult)?,
        stagesWhenPartial: Boolean = true,
    ): WalkResult {
        try {
            var replans = 0
            var stages = 0
            while (true) {
                if (arrived(state().feet)) return WalkResult(WalkStatus.ARRIVED, "arrived")
                var partial = false
                val path = when (val result = plan(plan)) {
                    is PathPlanner.Result.Found -> result.path
                    is PathPlanner.Result.Partial -> {
                        val beyondLoaded = "loaded" in result.reason
                        if (baritone != null) return baritone()
                        if (!beyondLoaded || !stagesWhenPartial || ++stages > MAX_STAGES) return WalkResult(WalkStatus.NO_PATH, result.reason)
                        partial = true
                        result.path // walk as far as we can see, then look again
                    }
                    is PathPlanner.Result.NotFound -> return baritone?.invoke() ?: WalkResult(WalkStatus.NO_PATH, result.reason)
                }
                draw(path)
                when (val outcome = follow(path)) {
                    // A full route walked without arriving (slid off, pushed): try again, but not forever.
                    Outcome.DONE -> if (!partial && !arrived(state().feet) && ++replans > MAX_REPLANS) return WalkResult(WalkStatus.STUCK, "can't get any closer")
                    Outcome.STUCK, Outcome.OFF_COURSE -> if (++replans > MAX_REPLANS) return WalkResult(WalkStatus.STUCK, "stuck after $MAX_REPLANS tries")
                    Outcome.TIMED_OUT -> return WalkResult(WalkStatus.TIMED_OUT, "took longer than $timeout")
                }
            }
        } finally {
            api.release(Key.FORWARD)
            api.release(Key.JUMP)
            api.release(Key.SPRINT)
            shapes.forEach { it.remove() }
        }
    }

    /** Walk [path] (feet positions) to its end, steered tick by tick on the game thread by a [Steering]. */
    private fun follow(path: List<Node>): Outcome {
        val steering = Steering(path, sprint, api.job)
        api.game { Steering.active += steering }
        try {
            while (true) {
                steering.outcome?.let { return it }
                if (System.currentTimeMillis() > deadline) return Outcome.TIMED_OUT
                api.waitTicks(1)
            }
        } finally {
            runCatching { api.job.host.onGameThread { mc -> steering.stop(mc) } }
        }
    }

    private fun draw(path: List<Node>) {
        shapes.forEach { it.remove() }
        shapes.clear()
        if (!showPath) return
        path.zipWithNext().forEach { (a, b) ->
            shapes += api.line(Vec3(a.x + 0.5, a.y + 0.1, a.z + 0.5), Vec3(b.x + 0.5, b.y + 0.1, b.z + 0.5), Color.CYAN)
        }
    }

    companion object {
        const val SEARCH_PER_TICK = 4_000
        const val MAX_REPLANS = 5
        const val MAX_STAGES = 50
    }
}

internal enum class Outcome { DONE, STUCK, OFF_COURSE, TIMED_OUT }

/**
 * Steers the player along a path, once per client tick on the game thread (see [Steering.tick]):
 * reading the position and setting the movement keys in the same moment keeps it precise at any
 * frame rate. The script thread only starts it and waits for [outcome].
 */
internal class Steering(private val path: List<Node>, private val sprint: Boolean, private val job: Job) {
    @Volatile var outcome: Outcome? = null
        private set

    private var next = 1
    private var bestDistance = Double.MAX_VALUE
    private var stillTicks = 0
    private var settle = 0

    fun tick(mc: Minecraft) {
        if (outcome != null) return
        val p = mc.player ?: return finish(mc, Outcome.OFF_COURSE)
        // Passed a node (close to its centre, at about its height): aim for the next one, and
        // measure progress afresh.
        while (next < path.size && close(p.x, p.y, p.z, path[next])) {
            next++
            bestDistance = Double.MAX_VALUE
            stillTicks = 0
        }
        if (next >= path.size) {
            // Let momentum die down before reporting done.
            keys(mc, forward = false, jump = false, sprint = false)
            if (++settle >= 3) finish(mc, Outcome.DONE)
            return
        }
        val target = path[next]
        val dx = target.x + 0.5 - p.x
        val dz = target.z + 0.5 - p.z
        val horizontal = sqrt(dx * dx + dz * dz)
        if (horizontal > 3.5 || p.y < target.y - 3.5) return finish(mc, Outcome.OFF_COURSE)

        if (horizontal < bestDistance - 0.03) {
            bestDistance = horizontal
            stillTicks = 0
        } else if (++stillTicks > STUCK_TICKS) {
            return finish(mc, Outcome.STUCK)
        }

        val yaw = Math.toDegrees(atan2(-dx, dz)).toFloat()
        val delta = ((yaw - p.yRot) % 360f + 540f) % 360f - 180f
        p.yRot += delta.coerceIn(-45f, 45f)
        p.setYHeadRot(p.yRot)

        val feetY = floor(p.y + 0.01).toInt()
        val climbing = target.y > feetY
        val jump = (climbing && horizontal < 1.6) || (p.isInWater && target.y >= feetY) || stillTicks > STUCK_TICKS / 2
        // Hold off walking while still turning hard, or corners get overshot.
        keys(mc, forward = abs(delta) < 60f, jump = jump, sprint = sprint && !p.isInWater)
    }

    private fun keys(mc: Minecraft, forward: Boolean, jump: Boolean, sprint: Boolean) {
        hold(mc, Key.FORWARD, forward)
        hold(mc, Key.JUMP, jump)
        hold(mc, Key.SPRINT, sprint)
    }

    private fun hold(mc: Minecraft, key: Key, down: Boolean) {
        val mapping = mc.options.mapping(key)
        mapping.setDown(down)
        if (down) job.heldKeys += mapping else job.heldKeys -= mapping
    }

    private fun finish(mc: Minecraft, result: Outcome) {
        keys(mc, forward = false, jump = false, sprint = false)
        outcome = result
        active -= this
    }

    /** Game thread: stop steering (if it hasn't already) and let go of the keys. */
    fun stop(mc: Minecraft) {
        active -= this
        keys(mc, forward = false, jump = false, sprint = false)
    }

    private fun close(x: Double, y: Double, z: Double, n: Node): Boolean {
        val dx = n.x + 0.5 - x
        val dz = n.z + 0.5 - z
        return dx * dx + dz * dz < 0.3 * 0.3 && y > n.y - 1.0 && y < n.y + 1.2
    }

    companion object {
        const val STUCK_TICKS = 40

        /** Steerings to run each client tick; only touched on the game thread. */
        val active = CopyOnWriteArrayList<Steering>()

        /** Called from the client tick (game thread). */
        fun tickAll(mc: Minecraft) = active.forEach { it.tick(mc) }
    }
}
