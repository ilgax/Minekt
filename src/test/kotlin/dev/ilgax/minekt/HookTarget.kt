package dev.ilgax.minekt

/** Plain methods for the method-hook tests to rewrite. */
class HookTarget {
    fun add(a: Int, b: Int): Int = a + b
    fun greet(name: String): String = "hi $name"
    fun record(log: MutableList<String>) {
        log += "original"
    }
    fun scale(x: Double): Double = x * 2
    fun overloaded(x: Int) = x
    fun overloaded(x: String) = x

    companion object {
        @JvmStatic
        fun twice(x: Long): Long = x * 2
    }
}
