package dev.insight.review

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/**
 * One packed circle: an entry of the hierarchy, its centre and radius in layout units, and the circles nested in it.
 * The shown directory's circle sits at the origin; children lie inside their parent.
 */
internal class PackedCircle(
    val entry: ReviewLineChart.Entry,
    val x: Double,
    val y: Double,
    val r: Double,
    val depth: Int,
    val children: List<PackedCircle>,
) {
    fun contains(px: Double, py: Double) = hypot(px - x, py - y) <= r

    /** The deepest circle under the point, or null outside this one. */
    fun hit(px: Double, py: Double): PackedCircle? {
        if (!contains(px, py)) return null
        for (child in children) child.hit(px, py)?.let { return it }
        return this
    }

    fun flatten(): List<PackedCircle> = listOf(this) + children.flatMap { it.flatten() }
}

/**
 * Packs the shown [directory] of [chart] into nested circles, leaf areas proportional to the chart's metric. Directories
 * below [maxDepth] keep their packed size but show no nested circles. Entries without a value take no space; files stay
 * leaves. Returns null when nothing in the directory has a value.
 */
internal fun packCircles(chart: ReviewLineChart, directory: ReviewLineChart.Entry, maxDepth: Int = 3): PackedCircle? {
    val root = layout(chart, directory) ?: return null
    root.translate(-root.x, -root.y)
    return root.freeze(0, maxDepth)
}

private class Circle(val entry: ReviewLineChart.Entry, var x: Double = 0.0, var y: Double = 0.0, var r: Double,
                     val children: List<Circle> = emptyList()) {
    fun translate(dx: Double, dy: Double) {
        x += dx; y += dy
        children.forEach { it.translate(dx, dy) }
    }

    fun freeze(depth: Int, maxDepth: Int): PackedCircle = PackedCircle(entry, x, y, r, depth,
        if (depth >= maxDepth) emptyList() else children.map { it.freeze(depth + 1, maxDepth) })
}

private fun layout(chart: ReviewLineChart, entry: ReviewLineChart.Entry): Circle? {
    if (entry.value <= 0) return null
    if (!entry.directory) return Circle(entry, r = sqrt(entry.value.toDouble()))
    val children = chart.children(entry).filter { it.kind == ReviewLineChart.Kind.DIRECTORY || it.kind == ReviewLineChart.Kind.FILE }
        .mapNotNull { layout(chart, it) }
    if (children.isEmpty()) return Circle(entry, r = sqrt(entry.value.toDouble()))
    // Siblings pack with a small margin, so neighbours and nested rings stay apart at any scale.
    val margins = children.map { it.r * SIBLING_GAP }
    val packed = children.zip(margins).map { (circle, gap) -> Circle(circle.entry, r = circle.r + gap) }
    val radius = packSiblings(packed)
    children.forEachIndexed { index, child -> child.translate(packed[index].x - child.x, packed[index].y - child.y) }
    val outer = radius + max(radius * PARENT_PADDING, margins.max())
    return Circle(entry, 0.0, 0.0, outer, children)
}

private const val SIBLING_GAP = 0.04
private const val PARENT_PADDING = 0.03

private class ChainNode(val c: Circle) {
    lateinit var next: ChainNode
    lateinit var previous: ChainNode
}

/**
 * Places [circles] side by side with the front-chain algorithm (Wang et al., 2006), in their order, then centres them on
 * the origin. Returns the radius of a circle enclosing them all.
 */
private fun packSiblings(circles: List<Circle>): Double {
    val n = circles.size
    if (n == 0) return 0.0
    val first = circles[0]
    first.x = 0.0; first.y = 0.0
    if (n == 1) return first.r
    val second = circles[1]
    first.x = -second.r; second.x = first.r; second.y = 0.0
    if (n == 2) return centre(circles, listOf(first, second))
    place(second, first, circles[2])

    var a = ChainNode(first)
    var b = ChainNode(second)
    var c = ChainNode(circles[2])
    a.next = b; c.previous = b
    b.next = c; a.previous = c
    c.next = a; b.previous = a

    var i = 3
    pack@ while (i < n) {
        val circle = circles[i]
        place(a.c, b.c, circle)
        c = ChainNode(circle)
        // Find the closest circle on the front chain that the new one overlaps, if any, by distance along the chain.
        var j = b.next
        var k = a.previous
        var sj = b.c.r
        var sk = a.c.r
        do {
            if (sj <= sk) {
                if (intersects(j.c, c.c)) {
                    b = j; a.next = b; b.previous = a
                    continue@pack
                }
                sj += j.c.r; j = j.next
            } else {
                if (intersects(k.c, c.c)) {
                    a = k; a.next = b; b.previous = a
                    continue@pack
                }
                sk += k.c.r; k = k.previous
            }
        } while (j !== k.next)
        // Insert the new circle between a and b, then restart from the pair closest to the centroid.
        c.previous = a; c.next = b; a.next = c; b.previous = c; b = c
        var best = score(a)
        var node = c.next
        while (node !== b) {
            val s = score(node)
            if (s < best) { a = node; best = s }
            node = node.next
        }
        b = a.next
        i++
    }
    val chain = mutableListOf(b.c)
    var node = b.next
    while (node !== b) { chain += node.c; node = node.next }
    return centre(circles, chain)
}

/** Moves [circles] so the circle enclosing [front] sits on the origin, and returns its radius. */
private fun centre(circles: List<Circle>, front: List<Circle>): Double {
    // Badoiu–Clarkson: step toward the farthest circle edge with shrinking steps for a rough centre.
    var cx = front.sumOf { it.x } / front.size
    var cy = front.sumOf { it.y } / front.size
    repeat(ENCLOSE_STEPS) { step ->
        val far = front.maxBy { hypot(it.x - cx, it.y - cy) + it.r }
        val d = hypot(far.x - cx, far.y - cy)
        val (ex, ey) = if (d == 0.0) far.x + far.r to far.y else far.x + (far.x - cx) / d * far.r to far.y + (far.y - cy) / d * far.r
        cx += (ex - cx) / (step + 2)
        cy += (ey - cy) / (step + 2)
    }
    // The radius is convex in the centre, so a shrinking compass search tightens the rough centre to the minimum.
    fun reach(x: Double, y: Double) = front.maxOf { hypot(it.x - x, it.y - y) + it.r }
    var best = reach(cx, cy)
    var step = best / 4
    while (step > best * 1e-7) {
        val moved = (0 until COMPASS).map { kotlin.math.PI * 2 * it / COMPASS }
            .map { cx + step * kotlin.math.cos(it) to cy + step * kotlin.math.sin(it) }
            .minBy { (x, y) -> reach(x, y) }
        val value = reach(moved.first, moved.second)
        if (value < best) { cx = moved.first; cy = moved.second; best = value } else step /= 2
    }
    for (circle in circles) { circle.x -= cx; circle.y -= cy }
    return circles.maxOf { hypot(it.x, it.y) + it.r }
}

private const val ENCLOSE_STEPS = 100
private const val COMPASS = 16

private fun place(b: Circle, a: Circle, c: Circle) {
    val dx = b.x - a.x
    val dy = b.y - a.y
    val d2 = dx * dx + dy * dy
    if (d2 > 0) {
        val a2 = (a.r + c.r).let { it * it }
        val b2 = (b.r + c.r).let { it * it }
        if (a2 > b2) {
            val x = (d2 + b2 - a2) / (2 * d2)
            val y = sqrt(max(0.0, b2 / d2 - x * x))
            c.x = b.x - x * dx - y * dy
            c.y = b.y - x * dy + y * dx
        } else {
            val x = (d2 + a2 - b2) / (2 * d2)
            val y = sqrt(max(0.0, a2 / d2 - x * x))
            c.x = a.x + x * dx - y * dy
            c.y = a.y + x * dy + y * dx
        }
    } else {
        c.x = a.x + c.r
        c.y = a.y
    }
}

private fun intersects(a: Circle, b: Circle): Boolean {
    val dr = a.r + b.r - 1e-6 * max(1.0, a.r + b.r)
    val dx = b.x - a.x
    val dy = b.y - a.y
    return dr > 0 && dr * dr > dx * dx + dy * dy
}

private fun score(node: ChainNode): Double {
    val a = node.c
    val b = node.next.c
    val ab = a.r + b.r
    val dx = (a.x * b.r + b.x * a.r) / ab
    val dy = (a.y * b.r + b.y * a.r) / ab
    return dx * dx + dy * dy
}
