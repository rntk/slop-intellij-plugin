package dev.insight.diff

import com.intellij.util.ui.JBUI
import java.awt.Font
import kotlin.math.roundToInt

/** [n] scaled pixels at [zoom], never below one so borders and gaps stay visible. */
internal fun zoomScaled(n: Int, zoom: Double) = (JBUI.scale(n) * zoom).roundToInt().coerceAtLeast(1)

/** This font at [zoom] times its size. */
internal fun Font.zoomed(zoom: Double): Font = deriveFont((size2D * zoom).toFloat())
