package dev.insight.ui

import com.intellij.util.ui.JBUI
import java.awt.Font
import javax.swing.border.EmptyBorder
import kotlin.math.roundToInt

/** [n] scaled pixels at [zoom], never below one so borders and gaps stay visible. */
internal fun zoomScaled(n: Int, zoom: Double) = (JBUI.scale(n) * zoom).roundToInt().coerceAtLeast(1)

/** This font at [zoom] times its size. */
internal fun Font.zoomed(zoom: Double): Font = deriveFont((size2D * zoom).toFloat())

/** An empty border of [top], [left], [bottom] and [right] screen pixels, already scaled (e.g. by [zoomScaled]); JBUI borders would scale them again. */
@Suppress("UseDPIAwareBorders")
internal fun pixelBorder(top: Int, left: Int, bottom: Int, right: Int) = EmptyBorder(top, left, bottom, right)
