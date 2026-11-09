package app.keplersync.tandem.fabric.ui

import java.awt.Color

/** Tandem's colours. */
object Tok {
	val panel = Color(0x101216)
	val rail = Color(0x0b0d10)
	val raise = Color(0x161a1f)
	val raise2 = Color(0x1c2128)
	val line = Color(0x2e343d)
	val track = Color(0x22272e)
	val outline = Color(0x646e7c)

	val ink = Color(0xe8ecf1)
	val muted = Color(0xa1a9b5)
	val sec = Color(0xb9c1cc)

	val violet = Color(0xa78bfa)
	val violetHi = Color(0xc4b5fd)
	/** Atlas's own accent and the two halves of its mark, wherever Tandem points to Atlas. */
	val atlas = Color(0x7dd3e8)
	val atlasLight = Color(0x7ae5f5)
	val atlasDark = Color(0x1aa4b9)
	val violetInk = Color(0x1a1033)
	val live = Color(0x1a1726)

	val ok = Color(0x6fd08c)
	val okText = Color(0x8fdca6)
	val warn = Color(0xe9b866)
	val warnText = Color(0xefc887)
	val warnInk = Color(0x2a1d00)
	val danger = Color(0xff8f94)
	val dangerText = Color(0xffb3b6)

	val dimmer = Color(6, 8, 10, 140)
	val clear = Color(0, 0, 0, 0)

	fun alpha(c: Color, a: Double) = Color(c.red, c.green, c.blue, (a * 255).toInt())

	/** [top] at opacity [a] over [under], as one opaque colour. */
	fun over(under: Color, top: Color, a: Double) = Color(
		(under.red + (top.red - under.red) * a).toInt(),
		(under.green + (top.green - under.green) * a).toInt(),
		(under.blue + (top.blue - under.blue) * a).toInt(),
	)
}
