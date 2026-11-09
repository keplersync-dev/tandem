package app.keplersync.tandem.fabric.ui

import java.awt.Font

/** Inter 4.1's static weights (assets/tandem/inter), drawn by [Raster]. */
enum class Weight(file: String) {
	REGULAR("Inter-Regular.ttf"), MEDIUM("Inter-Medium.ttf"), SEMIBOLD("Inter-SemiBold.ttf"), BOLD("Inter-Bold.ttf");

	val awt: Font by lazy { Raster.loadFont(file) }
}

/** Inter's line box (ascender − descender) and ascender, in ems. */
const val LINE_EM = 1.21f
const val ASCENT_EM = 0.969f
