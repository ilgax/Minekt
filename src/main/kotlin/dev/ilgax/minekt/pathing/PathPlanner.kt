package dev.ilgax.minekt.pathing

import java.util.PriorityQueue
import kotlin.math.sqrt

/** What a block is, as far as walking is concerned. */
enum class Cell {
    /** Nothing to bump into: air, grass, flowers, open doors. */
    OPEN,
    /** A block you can stand on: full blocks, slabs, stairs, … */
    SOLID,
    /** Taller than a block (fences, walls): can't be walked through or stood on. */
    TALL,
    WATER,
    /** Lava, fire, cactus, magma, … never walked into or onto. */
    HAZARD,
    /** Not loaded: the planner can't see there. */
    UNKNOWN,
}

/** Read-only view of the world for planning. */
fun interface BlockView {
    fun cell(x: Int, y: Int, z: Int): Cell
}

/** A spot the player's feet can be in. */
data class Node(val x: Int, val y: Int, val z: Int) {
    fun distanceTo(o: Node): Double {
        val dx = (x - o.x).toDouble()
        val dy = (y - o.y).toDouble()
        val dz = (z - o.z).toDouble()
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
}

/**
 * A* over standable spots: walking (also diagonally, without cutting corners), jumping up one
 * block, dropping down at most [maxFall] blocks (any height into water), and swimming.
 * [step] can be called repeatedly with a budget, so a long search can be spread over several ticks.
 */
class PathPlanner(
    private val view: BlockView,
    private val start: Node,
    /** Where to go: true for any acceptable end spot. */
    private val isGoal: (Node) -> Boolean,
    /** Guides the search; must not overestimate the remaining cost. 0 searches outward evenly. */
    private val heuristic: (Node) -> Double,
    private val maxFall: Int = 3,
    private val maxNodes: Int = 40_000,
) {
    sealed interface Result {
        /** A full path from start to a goal spot. */
        data class Found(val path: List<Node>) : Result
        /** No full path within what's visible: the path to the spot closest to the goal. */
        data class Partial(val path: List<Node>, val reason: String) : Result
        data class NotFound(val reason: String) : Result
    }

    private class Entry(val node: Node, val cost: Double, val estimate: Double)

    private val open = PriorityQueue<Entry>(compareBy { it.estimate })
    private val best = HashMap<Node, Double>()
    private val cameFrom = HashMap<Node, Node>()
    private var expanded = 0
    private var closest = start
    private var closestH = heuristic(start)
    /** The search touched unloaded chunks, so a failure may just mean "can't see far enough". */
    private var sawUnknown = false

    var result: Result? = null
        private set

    init {
        best[start] = 0.0
        open += Entry(start, 0.0, heuristic(start))
    }

    /** Expand up to [budget] nodes. Returns the result once the search is over, else null. */
    fun step(budget: Int = Int.MAX_VALUE): Result? {
        result?.let { return it }
        var left = budget
        while (left-- > 0) {
            val entry = open.poll() ?: return finish(null)
            val node = entry.node
            if (entry.cost > (best[node] ?: Double.MAX_VALUE)) continue // stale entry
            if (isGoal(node)) return finish(node)
            if (++expanded > maxNodes) return finish(null, "the search got too big ($maxNodes spots)")
            val h = heuristic(node)
            if (h < closestH) {
                closestH = h
                closest = node
            }
            neighbours(node) { next, moveCost ->
                val cost = entry.cost + moveCost
                if (cost < (best[next] ?: Double.MAX_VALUE)) {
                    best[next] = cost
                    cameFrom[next] = node
                    open += Entry(next, cost, cost + heuristic(next))
                }
            }
        }
        return null
    }

    /** Run the whole search now. */
    fun solve(): Result = step()!!

    private fun finish(goal: Node?, why: String? = null): Result {
        val r = when {
            goal != null -> Result.Found(pathTo(goal))
            closest != start -> Result.Partial(pathTo(closest), why ?: if (sawUnknown) "the goal is beyond loaded chunks" else "no way through")
            else -> Result.NotFound(why ?: if (sawUnknown) "the goal is beyond loaded chunks" else "no way there")
        }
        result = r
        return r
    }

    private fun pathTo(end: Node): List<Node> = generateSequence(end) { cameFrom[it] }.toList().asReversed()

    // ---- the world ----------------------------------------------------------------------------

    private val cache = HashMap<Long, Cell>()

    private fun cell(x: Int, y: Int, z: Int): Cell {
        val key = (x.toLong() and 0x3FFFFFF shl 38) or (z.toLong() and 0x3FFFFFF shl 12) or (y.toLong() and 0xFFF)
        return cache.getOrPut(key) { view.cell(x, y, z) }.also { if (it == Cell.UNKNOWN) sawUnknown = true }
    }

    private fun clear(c: Cell) = c == Cell.OPEN || c == Cell.WATER

    /** Feet and head fit here, and there's something to stand on (or water to swim in). */
    fun standable(x: Int, y: Int, z: Int): Boolean {
        val feet = cell(x, y, z)
        if (!clear(feet) || !clear(cell(x, y + 1, z))) return false
        return feet == Cell.WATER || cell(x, y - 1, z) == Cell.SOLID
    }

    private fun passable(x: Int, y: Int, z: Int) = clear(cell(x, y, z)) && clear(cell(x, y + 1, z))

    private inline fun neighbours(n: Node, add: (Node, Double) -> Unit) {
        val inWater = cell(n.x, n.y, n.z) == Cell.WATER
        val swim = if (inWater) 2.0 else 1.0
        for ((dx, dz) in STRAIGHT) {
            val x = n.x + dx
            val z = n.z + dz
            when {
                standable(x, n.y, z) -> add(Node(x, n.y, z), swim * waterCost(x, n.y, z))
                // Jump up one block (needs headroom above us).
                standable(x, n.y + 1, z) && clear(cell(n.x, n.y + 2, n.z)) -> add(Node(x, n.y + 1, z), 2.0 * waterCost(x, n.y + 1, z))
                // Step off an edge and fall.
                passable(x, n.y, z) -> fall(x, n.y, z)?.let { landing -> add(landing, 1.0 + (n.y - landing.y) * 0.5) }
            }
        }
        for ((dx, dz) in DIAGONAL) {
            val x = n.x + dx
            val z = n.z + dz
            // Only on the level, and never cutting a corner.
            if (standable(x, n.y, z) && passable(n.x + dx, n.y, n.z) && passable(n.x, n.y, n.z + dz)) add(Node(x, n.y, z), DIAG * swim * waterCost(x, n.y, z))
        }
        if (inWater) {
            if (standable(n.x, n.y + 1, n.z)) add(Node(n.x, n.y + 1, n.z), 2.0)
            if (cell(n.x, n.y - 1, n.z) == Cell.WATER && standable(n.x, n.y - 1, n.z)) add(Node(n.x, n.y - 1, n.z), 2.0)
        }
    }

    private fun waterCost(x: Int, y: Int, z: Int) = if (cell(x, y, z) == Cell.WATER) 2.0 else 1.0

    /** Where stepping into the open column at (x, y, z) lands: at most [maxFall] down, or any height into water. */
    private fun fall(x: Int, y: Int, z: Int): Node? {
        var feet = y
        repeat(WATER_FALL) {
            when (cell(x, feet - 1, z)) {
                Cell.SOLID -> return if (y - feet <= maxFall) Node(x, feet, z) else null
                Cell.WATER -> return Node(x, feet - 1, z)
                Cell.OPEN -> feet--
                else -> return null // lava, fire, fences, unloaded: not safe
            }
        }
        return null
    }

    companion object {
        private val STRAIGHT = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
        private val DIAGONAL = listOf(1 to 1, 1 to -1, -1 to 1, -1 to -1)
        private const val DIAG = 1.4142135
        private const val WATER_FALL = 24

        /** Straight-line distance to [target]: never more than the real cost (every move costs ≥ 1 per block). */
        fun towards(target: Node): (Node) -> Double = { it.distanceTo(target) }

        /** A* to an exact spot, or to within [within] blocks of it. */
        fun to(view: BlockView, start: Node, target: Node, within: Double = 0.0, maxFall: Int = 3, maxNodes: Int = 40_000) =
            PathPlanner(view, start, { it == target || (within > 0 && it.distanceTo(target) <= within) }, towards(target), maxFall, maxNodes)

        /** Nearest spot (by walking cost) that [goal] accepts, within [maxNodes] spots. */
        fun nearest(view: BlockView, start: Node, maxFall: Int = 3, maxNodes: Int = 40_000, goal: (Node) -> Boolean) =
            PathPlanner(view, start, goal, { 0.0 }, maxFall, maxNodes)
    }
}
