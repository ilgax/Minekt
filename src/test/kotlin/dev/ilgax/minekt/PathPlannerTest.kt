package dev.ilgax.minekt

import dev.ilgax.minekt.pathing.BlockView
import dev.ilgax.minekt.pathing.Cell
import dev.ilgax.minekt.pathing.Node
import dev.ilgax.minekt.pathing.PathPlanner
import dev.ilgax.minekt.pathing.PathPlanner.Result
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Route planning on small made-up worlds: flat ground at y = 0 (feet at y = 0), blocks placed by hand. */
class PathPlannerTest {
    private class World(private val loaded: Int = 64) : BlockView {
        val blocks = HashMap<Triple<Int, Int, Int>, Cell>()
        operator fun set(x: Int, y: Int, z: Int, c: Cell) { blocks[Triple(x, y, z)] = c }
        fun wall(x: Int, z1: Int, z2: Int, height: Int = 2, c: Cell = Cell.SOLID) {
            for (z in z1..z2) for (y in 0 until height) this[x, y, z] = c
        }
        override fun cell(x: Int, y: Int, z: Int): Cell =
            if (abs(x) > loaded || abs(z) > loaded) Cell.UNKNOWN
            else blocks[Triple(x, y, z)] ?: if (y < 0) Cell.SOLID else Cell.OPEN
    }

    /** No full route: the planner may still offer a partial one towards the target. */
    private fun assertNotFound(r: Result) = assertTrue(r !is Result.Found, "unexpectedly found: $r")

    private fun plan(world: World, from: Node, to: Node, maxFall: Int = 3) = PathPlanner.to(world, from, to, maxFall = maxFall).solve()

    @Test
    fun `straight line on flat ground, using diagonals`() {
        val r = assertIs<Result.Found>(plan(World(), Node(0, 0, 0), Node(5, 0, 5)))
        assertEquals(Node(5, 0, 5), r.path.last())
        assertEquals(6, r.path.size) // 5 diagonal steps
    }

    @Test
    fun `goes around a wall`() {
        val w = World().apply { wall(3, -4, 4) }
        val r = assertIs<Result.Found>(plan(w, Node(0, 0, 0), Node(6, 0, 0)))
        assertTrue(r.path.none { it.x == 3 && it.z in -4..4 }, "walked through the wall: ${r.path}")
    }

    @Test
    fun `jumps up one block but not two`() {
        val step = World().apply { for (z in -10..10) for (x in 3..10) this[x, 0, z] = Cell.SOLID }
        val up = assertIs<Result.Found>(plan(step, Node(0, 0, 0), Node(6, 1, 0)))
        assertTrue(up.path.any { it.y == 1 })
        val cliff = World().apply { for (z in -70..70) for (x in 3..10) { this[x, 0, z] = Cell.SOLID; this[x, 1, z] = Cell.SOLID } }
        assertNotFound(plan(cliff, Node(0, 0, 0), Node(6, 2, 0)))
    }

    @Test
    fun `drops at most maxFall blocks, any height into water`() {
        // A tower at x = 0..1 whose top is 5 above the ground; ground everywhere else.
        val tower = World().apply { for (x in -1..1) for (z in -1..1) for (y in 0..4) this[x, y, z] = Cell.SOLID }
        assertNotFound(plan(tower, Node(0, 5, 0), Node(4, 0, 0)))
        assertIs<Result.Found>(plan(tower, Node(0, 5, 0), Node(4, 0, 0), maxFall = 5))
        // A pool next to the tower makes the jump safe.
        tower[2, 0, 0] = Cell.WATER
        tower[2, -1, 0] = Cell.WATER
        val r = assertIs<Result.Found>(plan(tower, Node(0, 5, 0), Node(4, 0, 0)))
        assertTrue(r.path.any { it.x == 2 }, "should land in the pool: ${r.path}")
    }

    @Test
    fun `never walks into or onto hazards`() {
        val w = World().apply {
            for (z in -2..2) this[3, -1, z] = Cell.HAZARD  // magma floor strip
            this[3, 0, 3] = Cell.HAZARD; this[3, 0, -3] = Cell.HAZARD // fire at the ends
        }
        val r = assertIs<Result.Found>(plan(w, Node(0, 0, 0), Node(6, 0, 0)))
        assertTrue(r.path.none { it.x == 3 && it.z in -3..3 }, "touched a hazard: ${r.path}")
    }

    @Test
    fun `doesn't cut corners diagonally`() {
        val w = World().apply { this[1, 0, 0] = Cell.SOLID; this[1, 1, 0] = Cell.SOLID }
        val r = assertIs<Result.Found>(plan(w, Node(0, 0, 0), Node(1, 0, 1)))
        assertEquals(listOf(Node(0, 0, 0), Node(0, 0, 1), Node(1, 0, 1)), r.path)
    }

    @Test
    fun `fences can't be climbed`() {
        val w = World().apply { wall(3, -70, 70, height = 1, c = Cell.TALL) }
        assertNotFound(plan(w, Node(0, 0, 0), Node(6, 0, 0)))
    }

    @Test
    fun `targets past loaded chunks give a partial path towards them`() {
        val r = assertIs<Result.Partial>(plan(World(loaded = 20), Node(0, 0, 0), Node(200, 0, 0)))
        assertTrue(r.path.last().x >= 19 && "loaded" in r.reason, "$r")
    }

    @Test
    fun `nearest spot matching a goal`() {
        val w = World().apply { this[7, -1, 2] = Cell.WATER; this[-3, -1, 0] = Cell.WATER }
        val r = assertIs<Result.Found>(PathPlanner.nearest(w, Node(0, 0, 0)) { w.cell(it.x, it.y, it.z) == Cell.WATER }.solve())
        assertEquals(Node(-3, -1, 0), r.path.last())
    }

    @Test
    fun `can be spread over several steps`() {
        val planner = PathPlanner.to(World(), Node(0, 0, 0), Node(40, 0, 0))
        var calls = 0
        var result: Result? = null
        while (result == null) { result = planner.step(budget = 10); calls++ }
        assertIs<Result.Found>(result)
        assertTrue(calls > 1)
    }
}
