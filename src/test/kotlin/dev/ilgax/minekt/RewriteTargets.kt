package dev.ilgax.minekt

/** Classes for the class-rewrite tests. */
class RewriteTarget {
    fun value(): Int = 1
    fun label(): String = "original"
}

/** Never touched before its test rewrites it, so it's rewritten as it loads. */
class LazyRewriteTarget {
    fun value(): Int = 1
}
