package app.keplersync.tandem.fabric.ui

import gg.essential.elementa.constraints.PixelConstraint
import net.minecraft.client.Minecraft
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Tandem draws at GUI scale ÷ 2, kept to about 75% of the screen, never below ×1 and never bigger than fits.
 * Sizes are converted to GUI units per component instead of scaling the whole window,
 * because Elementa's scissor maps GUI units to the screen with the game's GUI scale alone.
 */
object Scale {
	/** Real screen pixels per Tandem pixel. */
	var s = 1.0
		private set
	private var gui = 2.0

	fun update() {
		val w = Minecraft.getInstance().window
		val sw = w.width.toDouble()
		val sh = w.height.toDouble()
		gui = w.guiScale
		s = min(min(max(1.0, min(min(gui / 2, sw / 1400), sh / 860)), sw / 1100), sh / 660)
	}

	/** [n] Tandem pixels in GUI units, snapped to whole screen pixels. */
	fun px(n: Number): Float = ((n.toDouble() * s).roundToInt() / gui).toFloat()
}

val Number.tp: Float get() = Scale.px(this)
val Number.at: PixelConstraint get() = PixelConstraint(Scale.px(this))
val Number.fromEnd: PixelConstraint get() = PixelConstraint(Scale.px(this), alignOpposite = true)
