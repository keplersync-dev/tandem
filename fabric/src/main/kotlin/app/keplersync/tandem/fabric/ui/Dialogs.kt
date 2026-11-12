package app.keplersync.tandem.fabric.ui

import gg.essential.elementa.UIComponent
import gg.essential.elementa.components.UIContainer
import gg.essential.elementa.constraints.CenterConstraint
import gg.essential.elementa.constraints.SiblingConstraint
import gg.essential.elementa.dsl.basicHeightConstraint
import gg.essential.elementa.dsl.basicWidthConstraint
import gg.essential.elementa.dsl.childOf
import gg.essential.elementa.dsl.constrain
import gg.essential.elementa.dsl.minus
import gg.essential.elementa.dsl.percent
import gg.essential.elementa.effects.ScissorEffect
import gg.essential.universal.UKeyboard
import gg.essential.universal.UMatrixStack
import net.minecraft.SharedConstants
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.ConfirmLinkScreen
import java.awt.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A dialog: [width] Tandem px of content inside 28 px padding and a 1 px border, parts 18 apart. */
fun dialog(width: Int, vararg parts: UIComponent): UIComponent {
	val box = box(18, Tok.panel, Tok.line).constrain { this.width = (width + 58).at }
	val col = Column(18).constrain { x = 29.at; y = 29.at; this.width = width.at } childOf box
	for (p in parts) col.add(p)
	box.constrain { height = basicHeightConstraint { col.getHeight() + 58.tp } }
	return box
}

/** A dialog's h2 and .sub: a 20 px bold title, then a muted paragraph at line-height 1.5. */
fun titled(title: String, sub: String, width: Int): UIComponent {
	val c = Column(0).constrain { this.width = width.at }
	c.add(wrapped(title, 20, Weight.BOLD, Tok.ink, width.tp, 1.3f))
	c.add(wrapped(sub, 14, Weight.REGULAR, Tok.muted, width.tp, 1.5f), 4)
	c.add(UIContainer(), 6)
	return c
}

/** A 48 px face or icon tile beside the title. */
fun who(lead: UIComponent, title: String, sub: String, width: Int) =
	row(14, lead, titled(title, sub, width - 48 - 14)).constrain { this.width = width.at }

/** The .mic tile: a 22 px icon on a 48 px tint. */
fun mic(icon: String, tint: Color): UIComponent {
	val b = box(10, Tok.over(Tok.panel, tint, 0.12)).constrain { width = 48.at; height = 48.at }
	Icon(icon, 22, tint).constrain { x = CenterConstraint(); y = CenterConstraint() } childOf b
	return b
}

/** The .safe line: an 18 px icon and a 13 px note at line-height 1.5. */
fun safe(icon: String, text: String, width: Int, tint: Color = Tok.ok): UIComponent {
	val c = UIContainer().constrain { this.width = width.at }
	Icon(icon, 18, tint).constrain { y = 1.at } childOf c
	val t = wrapped(text, 13, Weight.REGULAR, Tok.sec, (width - 28).tp, 1.5f).constrain { x = 28.at } childOf c
	c.constrain { height = basicHeightConstraint { t.getHeight() } }
	return c
}

/** A big progress bar with its two .stats lines underneath. */
fun progress(warn: Boolean, width: Int, value: () -> Float, left: Label, right: Label): UIComponent {
	val c = UIContainer().constrain { this.width = width.at; height = (8 + 10 + 13 * LINE_EM).at }
	Progress(warn, 8) { value() }.constrain { this.width = 100.percent } childOf c
	left.constrain { y = 18.at } childOf c
	right.constrain { x = 0.fromEnd; y = 18.at } childOf c
	return c
}

/** The .actions row: buttons at the right, 10 apart, in reading order. */
fun actions(width: Int, vararg buttons: UIComponent): UIComponent {
	val c = UIContainer().constrain { this.width = width.at; height = 40.at }
	for ((i, b) in buttons.reversed().withIndex()) {
		b.constrain { x = if (i == 0) 0.fromEnd else SiblingConstraint(10.tp, alignOpposite = true) }
	}
	for (b in buttons.reversed()) b childOf c
	return c
}

/** Something that moves from [from] to 1 over [secs], measured from when it was made, so a resize doesn't restart it. */
/** Something filling up from 0 to 1: sample progress on a timer, or a real transfer ([Moving]). */
interface Prog {
	val value: Float
	/** Seconds until done at the speed so far, or -1 before that's known. */
	val secondsLeft: Long get() = -1
}

class Timed(private val from: Float, private val secs: Float) : Prog {
	private val start = System.currentTimeMillis()
	var finished = false
	override val value get() = min(1f, from + (1 - from) * (System.currentTimeMillis() - start) / (secs * 1000))
	override val secondsLeft get() = ((1 - value) / (1 - from) * secs).toLong()
}

/** "20 s" or "3 min", or null before it's known and in its last second. */
fun leftText(secs: Long): String? = if (secs < 1) null else if (secs < 60) tr("tandem.n_s", secs) else tr("tandem.n_min", (secs + 59) / 60)

/** An upload after leaving, in a few words: "63% · about 20 s left". */
fun uploadedText(run: Prog) = "${(run.value * 100).roundToInt()}%" + (leftText(run.secondsLeft)?.let { tr("tandem.about_left", it) } ?: "")

/** The sample's dialogs. [changed] rebuilds the screen after the sample changes. */
class Dialogs(val layers: Layers, val changed: () -> Unit) {
	/** Getting the latest copy: downloads only what changed, never touches the copy on this PC until it's checked. */
	fun getLatest(w: World) {
		if (Sample.diskFull) return noSpace(w)
		val run = Timed(0f, if (Sample.slow) 14f else 5f)
		val helper = Sample.friends.firstOrNull { it.online && it.name in w.shared }
		layers.openDialog {
			val got = Label(tr("tandem.0_mb_of_1_08_gb"), 13, Weight.REGULAR, Tok.muted)
			val box = dialog(
				500,
				titled(tr("tandem.getting_the_latest", w.name), tr("tandem.last_saved_by_only_the_parts_that", w.who, w.ago), 500),
				progress(false, 500, { run.value }, got, Label(if (Sample.slow) tr("tandem.0_9_mb_s_about_18_min") else tr("tandem.3_2_mb_s_about_5_s"), 13, Weight.REGULAR, Tok.muted)),
				safe("shield", tr("tandem.your_copy_on_this_pc_isnt_touched"), 500),
				*(if (Sample.slow && helper != null) arrayOf(slowRow(helper, w)) else emptyArray()),
				actions(500, Button(tr("tandem.cancel"), Kind.OUTLINE, Size.NORMAL, null, Tok.panel) { layers.closeDialog() }),
			)
			Ticker {
				got.text = tr("tandem.mb_of_1_08_gb", (run.value * 1080).roundToInt())
				// shortcut: sample data stops here; the real mod opens the world once the copy is checked
				if (run.value >= 1f && !run.finished) { run.finished = true; layers.closeDialog() }
			} childOf box
			box
		}
	}

	/** Waiting for a friend's upload: the world opens by itself when it's done (always the latest copy). */
	fun waitFor(w: World) {
		if (Sample.alexQuit) return waitQuit(w, w.pct / 100f, tr("tandem.9_40_pm_tomorrow"), tr("tandem.25_min_before_they_quit"))
		val run = Timed(w.pct / 100f, 6f)
		val friend = Sample.person(w.who)
		layers.openDialog {
			val pct = Label(tr("tandem.uploaded", w.pct), 13, Weight.REGULAR, Tok.muted)
			val box = dialog(
				500,
				who(
					Face({ friend.skin }, 48, 10, null, Tok.panel), tr("tandem.waiting_for_to_finish_saving", w.who),
					tr("tandem.s_last_session_of_is_uploading_the", w.who, w.name), 500,
				),
				progress(true, 500, { run.value }, pct, Label(tr("tandem.about_1_min_left"), 13, Weight.REGULAR, Tok.muted)),
				actions(500, Button(tr("tandem.stop_waiting"), Kind.OUTLINE, Size.NORMAL, null, Tok.panel) { layers.closeDialog() }),
			)
			Ticker {
				pct.text = tr("tandem.uploaded", (run.value * 100).roundToInt())
				if (run.value >= 1f && !run.finished) {
					run.finished = true
					w.state = WorldState.READY; w.ago = tr("tandem.just_now")
					changed()
					getLatest(w)
				}
			} childOf box
			box
		}
	}

	/** On a slow connection, a friend who has the world can host instead. */
	private fun slowRow(friend: Person, w: World): UIComponent {
		val b = box(12, Tok.raise, Tok.line).constrain { width = 500.at; height = (34 + 28).at }
		Label(tr("tandem.slow_connection_can_host_instead_and_you", friend.name), 13, Weight.REGULAR, Tok.sec).constrain { x = 17.at; y = CenterConstraint() } childOf b
		lateinit var ask: Button
		ask = Button(tr("tandem.ask_to_host", friend.name), Kind.OUTLINE, Size.SMALL, null, Tok.raise) {
			ask.text = tr("tandem.asked"); ask.enabled = false
			layers.toast(tr("tandem.asked_to_host", friend.name, w.name), tr("tandem.youll_get_an_invite_when_is_in", friend.name))
		}.constrain { x = 17.fromEnd; y = CenterConstraint() } childOf b
		return b
	}

	/**
	 * Mods don't match: counts as tabs, one plain sentence each, search and a scrolling list once there are more
	 * than 8, Copy the list, and the one Atlas line. A world for another Minecraft version says so first.
	 */
	fun mods(w: World, tab: Int = 0) {
		val d = w.mods ?: return
		val host = d.host
		val cats = listOf(
			Triple(tr("tandem.missing"), d.missing, tr("tandem.in_the_world_not_in_your_game")),
			Triple(tr("tandem.different_version"), d.different, tr("tandem.the_same_mod_another_version")),
			Triple(tr("tandem.extra_in_your_game"), d.extra, tr("tandem.not_in_the_world_they_would_add", host)),
		).filter { it.second.isNotEmpty() }
		val cur = cats[tab.coerceIn(0, cats.lastIndex)]
		val game = SharedConstants.getCurrentVersion().name
		layers.openDialog {
			val (title, sub) = when {
				w.mc != null -> tr("tandem.is_made_for_minecraft", w.name, w.mc) to tr("tandem.this_game_is_minecraft_opening_a_world", game)
				w.state == WorldState.OTHER -> tr("tandem.uses_different_mods", w.name) to tr("tandem.it_was_last_played_with_s_mods", host)
				else -> tr("tandem.your_mods_dont_match_s", host) to tr("tandem.runs_with_s_mods_match_them_then", w.name, host)
			}
			val parts = mutableListOf<UIComponent>(who(mic("warn", Tok.warn), title, tr("tandem.your_worlds_arent_changed", sub), 760))
			if (w.mc != null) {
				parts += versions(game, w)
				parts += Label(tr("tandem.it_also_needs_mods_this_game_doesnt", d.missing.size), 13, Weight.REGULAR, Tok.sec)
			}
			if (cats.size > 1) parts += modTabs(w, cats.map { it.first to it.second.size }, cats.indexOf(cur))

			val list = ModList(cur.second, cur === cats.find { it.first == tr("tandem.different_version") })
			val head = UIContainer().constrain { width = 100.percent; height = (if (cur.second.size > 8) 34 else 16).at }
			val sentence = if (cats.size > 1) listOf(Run(cur.third, Weight.REGULAR, Tok.sec))
			else listOf(Run(tr("tandem.n_kind", cur.second.size, cur.first.lowercase()), Weight.BOLD, Tok.ink), Run(" ${cur.third}", Weight.REGULAR, Tok.sec))
			rich(sentence, 13, 500.tp).constrain { y = CenterConstraint() } childOf head
			if (cur.second.size > 8) Field(tr("tandem.search_mods", cur.second.size), 220) { list.query = it }.constrain { x = 0.fromEnd } childOf head
			val block = Column(8).constrain { width = 760.at }
			block.add(head)
			block.add(list)
			block.add(Label(tr("tandem.mods_that_only_change_your_screen_like"), 13, Weight.REGULAR, Tok.muted))
			parts += block
			parts += atlasTip(if (w.mc != null) tr("tandem.atlas_can_set_up_a_minecraft_game", w.mc, host) else tr("tandem.atlas_can_match_your_mods_to_s", host))
			parts += actions(
				760,
				Button(tr("tandem.copy_the_list"), Kind.OUTLINE, Size.NORMAL, null, Tok.panel) { copy(w, d) },
				Button(tr("tandem.close"), Kind.FILLED, Size.NORMAL, null, Tok.panel) { layers.closeDialog() },
			)
			dialog(760, *parts.toTypedArray())
		}
	}

	private fun versions(game: String, w: World): UIComponent {
		val b = box(12, Tok.raise, Tok.line).constrain { width = 760.at; height = 60.at }
		fun side(small: String, big: String) = Column(0).also {
			it.add(Label(small, 12.5, Weight.REGULAR, Tok.muted))
			it.add(Label(big, 16, Weight.BOLD))
			it.constrain { width = basicWidthConstraint { c -> c.children.maxOf { ch -> ch.getWidth() } } }
		}
		row(14, side(tr("tandem.this_game"), tr("tandem.minecraft", game)), Icon("chev", 18, Tok.muted), side(tr("tandem.s_needs", w.name), tr("tandem.minecraft", w.mc)))
			.constrain { x = 17.at; y = CenterConstraint() } childOf b
		return b
	}

	private fun modTabs(w: World, tabs: List<Pair<String, Int>>, on: Int): UIComponent {
		val c = UIContainer().constrain { width = 760.at; height = (24 + 13 * LINE_EM + 2 + 24 + 2).at }
		val each = (760 - 8 * (tabs.size - 1)) / tabs.size.toFloat()
		for ((i, t) in tabs.withIndex()) {
			val sel = i == on
			val b = Box(12.tp, if (sel) Tok.over(Tok.panel, Tok.violet, 0.08) else Tok.raise, if (sel) Tok.violet else Tok.outline)
				.constrain { x = (i * (each + 8)).at; width = each.at; height = 100.percent } childOf c
			val col = Column(2).constrain { x = 15.at; y = 13.at; width = 100.percent - 30.at } childOf b
			col.add(Label(t.second.toString(), 22, Weight.BOLD, Tok.ink, 1.1f))
			col.add(Label(t.first, 13, Weight.REGULAR, Tok.sec))
			val pick = { if (!sel) mods(w, i) }
			b.onMouseClick { it.stopPropagation(); pick() }
			Focus.add(b, b.corner, pick)
		}
		return c
	}

	private fun atlasTip(text: String): UIComponent {
		val c = Dashed(12).constrain { width = 760.at; height = (24 + 13 * LINE_EM + 2).at }
		val mark = UIContainer().constrain { x = 15.at; y = CenterConstraint(); width = 18.at; height = 18.at } childOf c
		Icon("atlas-light", 18, Tok.atlasLight) childOf mark
		Icon("atlas-dark", 18, Tok.atlasDark) childOf mark
		Label(text, 13, Weight.REGULAR, Tok.sec).constrain { x = (15 + 18 + 10).at; y = CenterConstraint() } childOf c
		link(tr("tandem.get_atlas"), Tok.atlas) {
			val mc = Minecraft.getInstance()
			ConfirmLinkScreen.confirmLinkNow(mc.screen, "https://keplersync.app")
		}.constrain { x = 15.fromEnd; y = CenterConstraint() } childOf c
		return c
	}

	private fun copy(w: World, d: Mods) {
		val lines = buildList {
			add(tr("tandem.mods_that_dont_match_s", w.name, d.host))
			// The name to read, and the id, which is what to search for when it differs.
			fun mod(id: String) = ModIcons.name(id).let { if (it == id) id else "$it ($id)" }
			d.missing.forEach { add(tr("tandem.missing_2", mod(it[0]), it[1])) }
			d.different.forEach { add(tr("tandem.different_version_yours_needs", mod(it[0]), it[1], it[2])) }
			d.extra.forEach { add(tr("tandem.extra_in_your_game_2", mod(it[0]), it[1])) }
		}
		Minecraft.getInstance().keyboardHandler.clipboard = lines.joinToString("\n")
		layers.toast(tr("tandem.copied_the_list_of_mods", modCount(d.total)), tr("tandem.paste_it_to_a_friend_or_into"))
	}

	/**
	 * Several versions: any number, all alike, none picked; the confirm button names the choice and stays off until
	 * there is one. The others go to History, and the choice can be undone.
	 */
	fun versions(w: World, vs: List<Version> = Sample.versions, choose: ((Version) -> Unit)? = null) {
		var pick: Version? = null
		layers.openDialog {
			val names = vs.map { if (it.who == "You") "you" else it.who }
			val from = if (choose == null) tr("tandem.from_the_same_save_tue_7_00") else tr("tandem.from_the_same_save")
			val sub = names.first().replaceFirstChar { it.uppercase() } + names.drop(1).dropLast(1).joinToString("") { ", $it" } +
				tr("tandem.and_each_kept_playing_without_being_connected", names.last(), from)
			lateinit var confirm: Button
			val choices = Column(8).constrain { width = 760.at }
			val rows = mutableListOf<Pair<Version, Choice>>()
			for (v in vs) {
				val ch = Choice(760) {
					pick = v
					rows.forEach { (rv, c) -> c.on = rv === v }
					confirm.enabled = true
					confirm.text = tr("tandem.continue_from_session", whose(v.who))
				}
				ch.lead(Face({ v.face ?: v.skin }, 36, 8, null, ch.surface))
				ch.text(v.title, v.line ?: tr("tandem.saved_played_changed", v.saved, v.played, v.changed), v.note, if (v.openNow) tr("tandem.open_until_you_pick") else null)
				rows += v to ch
				choices.add(ch)
				dialogTargets["version-${v.who}"] = ch
			}
			confirm = Button(tr("tandem.pick_a_version_first"), Kind.FILLED, Size.NORMAL, null, Tok.panel) {
				val v = pick ?: return@Button
				layers.closeDialog()
				if (choose != null) return@Button choose(v)
				w.state = WorldState.READY; w.who = if (v.who == "You") "you" else v.who; w.ago = v.saved
				changed()
				layers.toast(
					tr("tandem.everyone_now_plays_version", whose(v.who)), tr("tandem.the_other_are_in_history", vs.size - 1),
					undo = { w.state = WorldState.VERSIONS; changed() },
				)
			}
			confirm.enabled = false
			dialogTargets["versions-confirm"] = confirm
			dialog(
				760,
				titled(tr("tandem.split_into_versions", w.name, vs.size), sub, 760),
				choices,
				safe("shield", tr("tandem.the_others_move_to_history_where_anyone"), 760),
				actions(760, Button(tr("tandem.decide_later"), Kind.OUTLINE, Size.NORMAL, null, Tok.panel) { layers.closeDialog() }, confirm),
			)
		}
	}
}

/** A choice: a radio, then whatever leads (a face), then a title and up to two lines. */
class Choice(private val cardWidth: Int, private val select: () -> Unit) : Box(12.tp, Tok.raise, Tok.outline) {
	val surface get() = fill
	private val body = row(12, Radio()).constrain { x = 17.at; y = CenterConstraint() } childOf this

	var on = false
		set(v) {
			field = v
			fill = if (v) Tok.over(Tok.panel, Tok.violet, 0.08) else Tok.raise
			border = if (v) Tok.violet else Tok.outline
			body.children.forEach { (it as? Radio)?.on = v; (it as? Face)?.under = fill }
		}

	init {
		constrain { width = cardWidth.at }
		onMouseClick { it.stopPropagation(); select() }
		Focus.add(this, corner, select)
	}

	fun lead(c: UIComponent) {
		c.constrain { x = SiblingConstraint(12.tp); y = CenterConstraint() } childOf body
	}

	fun text(title: String, line1: String?, line2: String?, tag: String?, rec: String? = null) {
		val col = Column(0)
		// Lines wrap in what's left after the radio, any lead and the note on the right.
		var room = (cardWidth - 17 - 22 - 12 - 17).tp - body.children.drop(1).sumOf { (it.getWidth() + 12.tp).toDouble() }.toFloat()
		if (rec != null) room -= textWidth(rec, 13, Weight.REGULAR) + 12.tp
		col.add(Label(title, 14, Weight.SEMIBOLD))
		if (line1 != null) col.add(wrapped(line1, 13, Weight.REGULAR, Tok.muted, room, 1.45f), 3)
		if (line2 != null) col.add(wrapped(line2, 13, Weight.REGULAR, Tok.muted, room, 1.45f), 3)
		if (rec != null) Label(rec, 13, Weight.REGULAR, Tok.violetHi).constrain { x = 17.fromEnd; y = CenterConstraint() } childOf this
		col.constrain { x = SiblingConstraint(12.tp); y = CenterConstraint(); width = basicWidthConstraint { c -> c.children.maxOf { it.getWidth() } } } childOf body
		if (tag != null) {
			val t = box(12, fill, Tok.outline)
			val l = Label(tag, 12.5, Weight.REGULAR, Tok.sec) childOf t
			l.constrain { x = 10.at; y = CenterConstraint() }
			t.constrain { x = 17.fromEnd; y = CenterConstraint(); width = basicWidthConstraint { l.getWidth() + 20.tp }; height = (12.5 * LINE_EM + 8).at } childOf this
		}
		constrain { height = basicHeightConstraint { col.getHeight() + 30.tp } }
	}

	/** An 18 px circle with a 2 px border; a violet dot when picked. */
	private class Radio : UIComponent() {
		var on = false

		init {
			constrain { width = 22.at; height = 22.at }
		}

		override fun draw(matrixStack: UMatrixStack) {
			beforeDraw(matrixStack)
			val g = Raster.gui
			val n = (getWidth() * g / 2).roundToInt()
			val c = Raster.ring(n, (2.tp * g).roundToInt().coerceAtLeast(1)).loc
			val s = (n / g).toFloat()
			val l = Raster.snap(getLeft()); val t = Raster.snap(getTop())
			val col = if (on) Tok.violet else Tok.outline
			Tex.draw(matrixStack, c, l, t, s, s, col)
			Tex.draw(matrixStack, c, l + s, t, s, s, col, 1f, 0f, 0f, 1f)
			Tex.draw(matrixStack, c, l, t + s, s, s, col, 0f, 1f, 1f, 0f)
			Tex.draw(matrixStack, c, l + s, t + s, s, s, col, 1f, 1f, 0f, 0f)
			if (on) {
				val d = 4.tp
				rounded(matrixStack, l + s - d, t + s - d, l + s + d, t + s + d, d, Tok.violet)
			}
			super.draw(matrixStack)
		}
	}
}

/**
 * Rows of name and version in a rounded box, at most 184 px tall, scrolling past that.
 * Draws only the rows in view, so a world with 400 different mods stays quick.
 */
class ModList(private val rows: List<List<String>>, private val different: Boolean, private val names: Map<String, String> = emptyMap()) : UIComponent() {
	var query = ""
		set(v) { field = v; offset = 0f; target = 0f }
	private var offset = 0f
	private var target = 0f
	private var last = 0L
	/** Names cut to fit their row, kept so they're measured once rather than every frame. */
	private val fitted = HashMap<String, String>()

	/** A mod's name: the one the world's game gave it, else this game's or Modrinth's ([ModIcons]). */
	private fun nameOf(id: String) = names[id] ?: ModIcons.name(id)

	private val shown get() = if (query.isBlank()) rows else rows.filter { it[0].contains(query.trim(), ignoreCase = true) || nameOf(it[0]).contains(query.trim(), ignoreCase = true) }

	init {
		ModIcons.want(rows.map { it[0] })
		constrain {
			width = 100.percent
			height = basicHeightConstraint { (min(max(1, shown.size) * ROW - 1, 184) + 2).tp }
		}
		enableEffect(ScissorEffect())
		onMouseScroll { e ->
			e.stopPropagation()
			// Eased like Scroller: a notch at a time, notches add up.
			target = (target - e.delta.toFloat() * scrollStep).coerceIn(0f, max(0f, shown.size * ROW - 1 - 184f))
		}
	}

	override fun draw(matrixStack: UMatrixStack) {
		beforeDraw(matrixStack)
		val now = System.nanoTime()
		val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(0.1f)
		last = now
		offset += (target - offset) * (1 - kotlin.math.exp(-dt / 0.045f))
		if (kotlin.math.abs(target - offset) < 0.01f) offset = target
		val l = getLeft(); val t = getTop(); val r = getRight(); val b = getBottom()
		rounded(matrixStack, l, t, r, b, 12.tp, Tok.line)
		rounded(matrixStack, l + 1.tp, t + 1.tp, r - 1.tp, b - 1.tp, 11.tp, Tok.rail)
		val list = shown
		val scrolls = list.size * ROW - 1 > 184
		val right = r - 1.tp - (if (scrolls) 10 else 0).tp
		if (list.isEmpty()) {
			drawText(matrixStack, tr("tandem.no_mods_match", query.trim()), 13, Weight.REGULAR, Tok.ink, l + 15.tp, t + 9.tp)
		}
		val first = (offset / ROW).toInt()
		for (i in first until min(list.size, first + 184 / ROW + 2)) {
			val y = t + 1.tp + (i * ROW - offset).tp
			if (y > b) break
			val m = list[i]
			val name = nameOf(m[0])
			val icon = ModIcons.get(m[0])
			val ix = l + 13.tp; val iy = y + 7.tp
			if (icon != null) {
				Tex.draw(matrixStack, icon, ix, iy, 18.tp, 18.tp, Color.WHITE)
				Tex.corners(matrixStack, ix, iy, ix + 18.tp, iy + 18.tp, 4.tp, Tok.rail)
			} else {
				rounded(matrixStack, ix, iy, ix + 18.tp, iy + 18.tp, 4.tp, Tok.track)
				val letter = name.take(1).uppercase()
				drawText(matrixStack, letter, 11, Weight.SEMIBOLD, Tok.muted, ix + (18.tp - textWidth(letter, 11, Weight.SEMIBOLD)) / 2, iy + 2.tp)
			}
			val v = if (different) tr("tandem.yours_needs", m[1], m[2]) else m[1]
			val vx = right - 14.tp - textWidth(v, 12.5, Weight.REGULAR)
			val room = vx - 12.tp - (l + (13 + 18 + 10).tp)
			val shownName = fitted.getOrPut("$name|$room") { fitText(name, 13, Weight.REGULAR, room) }
			drawText(matrixStack, shownName, 13, Weight.REGULAR, Tok.ink, l + (13 + 18 + 10).tp, y + 8.tp)
			drawText(matrixStack, v, 12.5, Weight.REGULAR, Tok.muted, vx, y + 8.tp)
			if (i < list.size - 1) rounded(matrixStack, l + 1.tp, y + (ROW - 1).tp, right, y + ROW.tp, 0f, Tok.track)
		}
		if (scrolls) {
			val track = 184f
			val h = max(24f, track * track / (list.size * ROW - 1))
			val y = t + 1.tp + ((track - h) * offset / (list.size * ROW - 1 - track)).tp
			rounded(matrixStack, r - 1.tp - 7.tp, y + 3.tp, r - 1.tp - 3.tp, y + (h - 3).tp, 2.tp, Color(0x3a414b))
		}
		super.draw(matrixStack)
	}

	private companion object {
		/** 8 px padding, a 16 px line, 8 px padding and a 1 px rule. */
		const val ROW = 33
	}
}
