package app.keplersync.tandem.fabric.ui

import gg.essential.elementa.UIComponent
import gg.essential.elementa.components.UIContainer
import gg.essential.elementa.components.Window
import gg.essential.elementa.constraints.CenterConstraint
import gg.essential.elementa.constraints.SiblingConstraint
import gg.essential.elementa.dsl.basicHeightConstraint
import gg.essential.elementa.dsl.basicWidthConstraint
import gg.essential.elementa.dsl.childOf
import gg.essential.elementa.dsl.constrain
import gg.essential.elementa.dsl.minus
import gg.essential.elementa.dsl.percent
import gg.essential.universal.UMatrixStack
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.ConfirmLinkScreen
import net.minecraft.client.resources.DefaultPlayerSkin
import net.minecraft.resources.ResourceLocation
import java.awt.Color
import java.util.UUID

/** Minecraft's own pictures: the grass block for a game with no mods, and the picture of a pack it doesn't know. */
private val GRASS = ResourceLocation.withDefaultNamespace("textures/block/grass_block_side.png")
private val UNKNOWN_PACK = ResourceLocation.withDefaultNamespace("textures/misc/unknown_pack.png")

/** A pack's picture: its modpack's, a profile's own icon.png, Minecraft's own, or its first letter. */
private class PackPic(private val pic: () -> ResourceLocation?, private val letter: String, private val size: Int, private val round: Int, private val under: Color) : UIComponent() {
	init {
		constrain { width = size.at; height = size.at }
	}

	override fun draw(matrixStack: UMatrixStack) {
		beforeDraw(matrixStack)
		val l = getLeft(); val t = getTop(); val r = getRight(); val b = getBottom()
		val tex = pic()
		if (tex != null) {
			Tex.draw(matrixStack, tex, l, t, r - l, b - t, Color.WHITE)
			Tex.corners(matrixStack, l, t, r, b, round.tp, under)
		} else {
			rounded(matrixStack, l, t, r, b, round.tp, Color(0x2a3038))
			val text = size * 0.4f
			drawText(matrixStack, letter, text, Weight.BOLD, Tok.ink, l + (r - l - textWidth(letter, text, Weight.BOLD)) / 2, t + (b - t - text.tp * LINE_EM) / 2)
		}
		super.draw(matrixStack)
	}
}

/** A Modrinth App profile's own icon.png beside it, for a game on this PC. */
private fun ownPic(g: GameInfo): ResourceLocation? = g.dir?.let(ModIcons::file)

private fun letterOf(s: String?) = (s?.firstOrNull { it.isLetterOrDigit() } ?: '?').uppercaseChar().toString()

/** A world's last session, as a line: "Jules played 3 days ago". */
private fun played(w: World): String = when {
	w.savedAt <= 0 -> ""
	w.savedBy == "you" || (w.savedBy.isBlank() && w.mine) -> tr("tandem.you_played_s", RealWorlds.ago(w.savedAt))
	else -> tr("tandem.s_played_s", w.savedBy.ifBlank { w.owner ?: "" }, RealWorlds.ago(w.savedAt))
}

/** The skin face of [name], one of the people in [w]: a friend's own, else Minecraft's default for them. */
private fun skinOf(w: World, name: String): ResourceLocation {
	val id = w.userIds[name]
	val friend = Sample.friends.firstOrNull { (id != null && it.userId == id) || (id == null && it.name == name) }
	return friend?.skin ?: DefaultPlayerSkin.get(UUID.nameUUIDFromBytes((id ?: name).toByteArray())).texture()
}

/** "Forge 1.12.2", or "Minecraft 1.21.11" for a world that needs no mods. */
private fun gameLine(w: World): String {
	val v = w.version ?: w.mc ?: ""
	return if (w.needs.isEmpty()) tr("tandem.minecraft", v) else Games.loaderName(w.game?.loader ?: "fabric") + " " + v
}

/** Whose copy a world's last save was, for "as Jules plays it"; null when it was this account's own. */
private fun saver(w: World): String? = w.savedBy.takeIf { it.isNotBlank() && it != "you" } ?: w.owner?.takeIf { it != "You" }

/**
 * A one-line switch between two or three groups, each name with its count beside it (Everyone's 12 · Yours 5). With
 * [width], the options share it evenly.
 */
fun segmented(options: List<Pair<String, Int?>>, on: Int, width: Int? = null, pick: (Int) -> Unit): UIComponent {
	val box = box(10, Tok.rail, Tok.line)
	val n = options.size
	// The one picked now: a pick repaints the switch in place, so it keeps its keyboard focus.
	var cur = on
	val paint = mutableListOf<() -> Unit>()
	for ((i, o) in options.withIndex()) {
		val item = box(7, Tok.rail)
		val name = Label(o.first, 13, Weight.SEMIBOLD, Tok.muted)
		paint += { item.fill = if (i == cur) Tok.raise2 else Tok.rail; name.tint = if (i == cur) Tok.ink else Tok.muted }
		val parts = listOfNotNull(name, o.second?.let { Label(it.toString(), 13, Weight.MEDIUM, Tok.muted) })
		val inside = row(6, *parts.toTypedArray()).constrain { x = CenterConstraint(); y = CenterConstraint() } childOf item
		item.constrain {
			x = if (i == 0) 3.at else SiblingConstraint(4.tp)
			y = 3.at
			height = 28.at
			this.width = if (width != null) ((width - 6 - 4 * (n - 1)).toFloat() / n).at else basicWidthConstraint { inside.getWidth() + 22.tp }
		} childOf box
		val choose = { if (i != cur) { cur = i; paint.forEach { it() }; pick(i) } }
		item.onMouseEnter { name.tint = Tok.ink }
		item.onMouseLeave { name.tint = if (i == cur) Tok.ink else Tok.muted }
		item.onMouseClick { it.stopPropagation(); choose() }
		Focus.add(item, item.corner, choose)
	}
	paint.forEach { it() }
	box.constrain {
		height = 34.at
		this.width = width?.at ?: basicWidthConstraint { c -> c.children.sumOf { it.getWidth().toDouble() }.toFloat() + (6 + 4 * (n - 1)).tp }
	}
	return box
}

/**
 * The Synced worlds tab: every synced world you own or that's shared with you, from every game. Sections by Minecraft
 * version and loader fold; inside, a card for each pack says where its worlds play, and How to play explains the rest.
 */
class SyncedPage(
	private val layers: Layers,
	private val dialogs: Dialogs,
	private val rebuild: () -> Unit,
	private val details: (World) -> Unit,
	private val act: (World) -> Unit,
	private val menu: (World, UIComponent) -> Unit,
	/** Dev screenshots: things the runner clicks, by name. */
	private val devTargets: MutableMap<String, UIComponent>,
) {
	companion object {
		const val W = 982

		/** Folded sections by key, the search and whose worlds: kept while the game runs, like the tab itself. */
		val folded = mutableSetOf<String>()
		var query = ""
		var whose = 0
		private var searching = false
	}

	private val howTo = HowToPlay(layers)
	private var holder: UIContainer? = null

	private class Card(val key: String, val where: Where, val worlds: MutableList<World>) {
		val game get() = worlds.firstNotNullOfOrNull { it.game }
	}

	private class Section(val key: String, val title: String, val loader: String, val here: Boolean, val cards: List<Card>) {
		val count get() = cards.sumOf { it.worlds.size }
	}

	private fun synced() = Sample.worlds.filter { it.owner != null && it.owner != "?" }

	fun build(into: UIComponent) {
		val col = Column(16).constrain { x = 24.at; y = 18.at; width = W.at } childOf into
		col.add(toolbar())
		val h = UIContainer().constrain { width = W.at; height = basicHeightConstraint { c -> c.children.firstOrNull()?.getHeight() ?: 0f } }
		holder = h
		col.add(h)
		fill()
		UIContainer().constrain { y = SiblingConstraint(); height = 26.at } childOf into
	}

	private fun toolbar(): UIComponent {
		val bar = UIContainer().constrain { width = W.at; height = 36.at }
		val all = synced()
		val title = Label(tr("tandem.synced_worlds"), 17, Weight.BOLD)
		if (all.isEmpty()) {
			title.constrain { y = CenterConstraint() } childOf bar
			toggle = null
			return bar
		}
		row(10, title, Label(all.size.toString(), 14, Weight.REGULAR, Tok.muted)).constrain { y = CenterConstraint() } childOf bar
		val search = Field(tr("tandem.search_worlds_packs_or_friends"), 250, 34, query) { query = it; fill() }
		search.onFocus { searching = true }
		search.onFocusLost { searching = false }
		devTargets["synced-search"] = search
		val counts = listOf(all.size, all.count { it.mine }, all.count { !it.mine })
		val who = segmented(listOf(tr("tandem.everyones"), tr("tandem.yours"), tr("tandem.friends_possessive")).zip(counts), whose) { whose = it; fill() }
		val toggle = Button(if (allOpen(sections())) tr("tandem.collapse_all") else tr("tandem.expand_all"), Kind.OUTLINE, Size.SMALL, null, Tok.panel) {
			val now = sections()
			if (allOpen(now)) folded += now.map { it.key } else folded.clear()
			fill()
		}
		// As wide as the longer of its two words, so the toolbar stays put when it flips.
		toggle.constrain { width = basicWidthConstraint { maxOf(textWidth(tr("tandem.collapse_all"), 13f, Weight.SEMIBOLD), textWidth(tr("tandem.expand_all"), 13f, Weight.SEMIBOLD)) + 28.tp } }
		devTargets["synced-toggle"] = toggle
		row(10, search, who, toggle).constrain { x = 0.fromEnd; y = CenterConstraint() } childOf bar
		// The list is filled again as each letter is typed, not the whole screen: the field keeps its place. A
		// screen rebuilt meanwhile (a friend coming online) puts the caret back.
		if (searching) Window.enqueueRenderOperation { runCatching { search.grabWindowFocus() } }
		this.toggle = toggle
		return bar
	}

	private var toggle: Button? = null

	private fun allOpen(sections: List<Section>) = sections.all { it.key !in folded }

	/** The list under the toolbar, built again from the current search, filter and folds. */
	private fun fill() {
		val h = holder ?: return
		h.clearChildren()
		val list = Column(16).constrain { width = W.at } childOf h
		val sections = sections()
		toggle?.text = if (allOpen(sections)) tr("tandem.collapse_all") else tr("tandem.expand_all")
		when {
			synced().isEmpty() -> list.add(empty(tr("tandem.no_synced_worlds_yet"), tr("tandem.sync_a_world_from_play_and_worlds")))
			sections.isEmpty() -> list.add(empty(tr("tandem.no_synced_worlds_match"), null))
			else -> for (s in sections) list.add(section(s))
		}
		list.add(Label(tr("tandem.worlds_you_havent_synced_stay_in"), 13, Weight.REGULAR, Tok.muted), 4)
	}

	private fun sections(): List<Section> {
		val here = Games.here
		val q = query.trim().lowercase()
		val cards = linkedMapOf<String, Pair<String, Card>>()
		for (w in synced()) {
			if (whose == 1 && !w.mine || whose == 2 && w.mine) continue
			val where = Games.where(w)
			val title = cardTitle(where, w)
			if (q.isNotEmpty() && listOfNotNull(w.name, title, w.owner, w.savedBy).none { it.lowercase().contains(q) }) continue
			val vanilla = where is Where.Vanilla
			val version = if (where is Where.Here) here.mc ?: "" else w.version ?: w.mc ?: ""
			val loader = if (vanilla) "vanilla" else if (where is Where.Here) here.loader else w.game?.loader ?: "fabric"
			val sectionKey = "$version|$loader"
			val cardKey = sectionKey + "|" + when (where) {
				is Where.Here -> "here"
				is Where.OnPc -> "pc|" + where.game.dir + "|" + title
				is Where.Vanilla -> "vanilla"
				is Where.Update -> "update|" + where.need.key + "|" + where.need.version + "|" + where.game.dir
				is Where.Differs -> "differs|" + w.game?.pack?.modpack?.key + "|" + where.game.dir + "|" + w.needs.sorted().joinToString(",")
				is Where.GetIt -> "get|" + where.modpack.key + "|" + where.modpack.version
				is Where.Own -> "own|" + where.pack?.name + "|" + w.owner
				is Where.Unknown -> "unknown"
			}
			cards.getOrPut(cardKey) { sectionKey to Card(cardKey, where, mutableListOf()) }.second.worlds += w
		}
		val rank = { c: Card ->
			when (c.where) {
				is Where.Here -> 0; is Where.OnPc -> 1; is Where.Vanilla -> 2; is Where.Update -> 3; is Where.Differs -> 4
				is Where.GetIt -> 5; is Where.Own -> 6; is Where.Unknown -> 7
			}
		}
		val hereKey = "${here.mc}|${here.loader}"
		return cards.values.groupBy({ it.first }, { it.second }).map { (key, cs) ->
			for (c in cs) c.worlds.sortWith(compareByDescending<World> { it.state == WorldState.HOSTED || it.hostedBy != null }.thenByDescending { it.savedAt })
			val (version, loader) = key.split('|', limit = 2).let { it[0] to it[1] }
			Section(key, version, loader, key == hereKey, cs.sortedWith(compareBy<Card>(rank).thenByDescending { c -> c.worlds.maxOf { it.savedAt } }))
		}.sortedWith(compareByDescending<Section> { it.here }.thenComparator { a, b ->
			Games.newestFirst.compare(a.title, b.title).takeIf { it != 0 } ?: (if (a.loader == "vanilla") 1 else 0).compareTo(if (b.loader == "vanilla") 1 else 0).takeIf { it != 0 } ?: a.loader.compareTo(b.loader)
		})
	}

	private fun empty(title: String, note: String?): UIComponent {
		val row = Dashed(12).constrain { width = W.at; height = (if (note != null) 78 else 60).at }
		val col = Column(4).constrain { x = 20.at; y = CenterConstraint(); width = (W - 40).at } childOf row
		col.add(Label(title, 15, Weight.SEMIBOLD))
		if (note != null) col.add(Label(note, 13, Weight.REGULAR, Tok.muted))
		if (query.isNotEmpty()) link(tr("tandem.clear_the_search")) { query = ""; searching = false; rebuild() }
			.constrain { x = 20.fromEnd; y = CenterConstraint() } childOf row
		return row
	}

	private fun section(s: Section): UIComponent {
		val open = s.key !in folded
		val col = Column(10).constrain { width = W.at }
		val head = box(8, Tok.panel).constrain { width = W.at; height = 34.at }
		val chev = Icon(if (open) "chevdown" else "chev", 15, Tok.muted).constrain { x = 8.at; y = CenterConstraint() } childOf head
		val loader = if (s.loader == "vanilla") tr("tandem.vanilla") else Games.loaderName(s.loader)
		val parts = mutableListOf<UIComponent>(Label(s.title, 16, Weight.BOLD), Label("· $loader", 16, Weight.SEMIBOLD, Tok.sec))
		if (s.here) parts += pill12(tr("tandem.this_game"), Tok.panel)
		if (!open) parts += stack(s.cards)
		row(9, *parts.toTypedArray()).constrain { x = SiblingConstraint(9.tp); y = CenterConstraint() } childOf head
		val right = mutableListOf<UIComponent>()
		if (!open) s.cards.flatMap { it.worlds }.firstNotNullOfOrNull { hostOf(it) }?.let { host ->
			right += row(5, Icon("host", 14, Tok.violetHi), Label(tr("tandem.is_hosting_2", host), 13, Weight.MEDIUM, Tok.violetHi))
		}
		right += Label(if (s.count == 1) tr("tandem.one_world") else tr("tandem.n_worlds", s.count), 13, Weight.REGULAR, Tok.muted)
		row(14, *right.toTypedArray()).constrain { x = 8.fromEnd; y = CenterConstraint() } childOf head
		val toggle = { if (open) folded += s.key else folded -= s.key; fill() }
		head.onMouseEnter { head.fill = Tok.raise; chev.tint = Tok.ink }
		head.onMouseLeave { head.fill = Tok.panel; chev.tint = Tok.muted }
		head.onMouseClick { it.stopPropagation(); toggle() }
		Focus.add(head, head.corner, toggle)
		col.add(head)
		if (open) for (c in s.cards) col.add(card(c))
		col.constrain { width = W.at }
		return col
	}

	/** A folded section's packs, as a row of small pictures: one per pack, though two friends' copies are two cards. */
	private fun stack(cards: List<Card>): UIComponent {
		val pics = cards.distinctBy { cardTitle(it.where, it.worlds.first()) }.take(6)
			.map { c -> PackPic(picOf(c), letterOf(cardTitle(c.where, c.worlds.first())), 22, 5, Tok.panel) }
		return row(4, *pics.toTypedArray())
	}

	private fun hostOf(w: World) = if (w.state == WorldState.HOSTED) w.who else w.hostedBy

	private fun picOf(c: Card): () -> ResourceLocation? {
		val w = c.worlds.first()
		return when (val where = c.where) {
			is Where.Here -> { { Games.here.pack?.modpack?.let(ModIcons::pack) ?: ownPic(Games.here) } }
			is Where.Vanilla -> { { GRASS } }
			is Where.Unknown -> { { UNKNOWN_PACK } }
			is Where.OnPc -> { { w.game?.pack?.modpack?.let(ModIcons::pack) ?: where.game.pack?.modpack?.let(ModIcons::pack) ?: ownPic(where.game) } }
			else -> { val mp = w.game?.pack?.modpack; { mp?.let(ModIcons::pack) } }
		}
	}

	private fun cardTitle(where: Where, w: World): String = when (where) {
		is Where.Here -> Games.here.title ?: tr("tandem.this_game")
		is Where.Vanilla -> tr("tandem.vanilla")
		is Where.Unknown -> tr("tandem.pack_not_known_yet")
		is Where.OnPc -> w.game?.title ?: where.game.title ?: tr("tandem.s_game", whose(w.owner))
		is Where.Own -> where.pack?.name?.ifBlank { null } ?: tr("tandem.s_game", whose(w.owner))
		else -> w.game?.title ?: ""
	}

	private fun cardVersion(where: Where, w: World): String? = when (where) {
		is Where.Here -> Games.here.pack?.modpack?.label
		is Where.Vanilla -> w.version
		is Where.Unknown, is Where.Own -> null
		else -> w.game?.pack?.modpack?.label
	}

	private fun card(c: Card): UIComponent {
		val here = c.where is Where.Here
		val box = box(12, Tok.raise, if (here) Tok.alpha(Tok.violet, 0.42) else Tok.line).constrain { width = W.at }
		val col = Column(0).constrain { width = 100.percent } childOf box
		col.add(header(c))
		for (w in c.worlds) {
			col.add(box(0, Tok.track).constrain { width = 100.percent; height = 1.at })
			col.add(worldRow(w, c.where))
		}
		box.constrain { height = basicHeightConstraint { col.getHeight() } }
		return box
	}

	private fun header(c: Card): UIComponent {
		val w = c.worlds.first()
		val h = UIContainer().constrain { width = 100.percent; height = 64.at }
		val title = cardTitle(c.where, w)
		PackPic(picOf(c), letterOf(title), 40, 9, Tok.raise).constrain { x = 12.at; y = CenterConstraint() } childOf h
		val button = when (c.where) {
			is Where.Update, is Where.Differs, is Where.GetIt, is Where.Own -> Button(tr("tandem.how_to_play"), Kind.OUTLINE, Size.SMALL, null, Tok.raise) { howTo.open(w, c.where) }
			is Where.Unknown -> Button(tr("tandem.see_whats_different"), Kind.OUTLINE, Size.SMALL, null, Tok.raise) { if (w.mods != null) dialogs.mods(w) }
			else -> null
		}
		button?.constrain { x = 12.fromEnd; y = CenterConstraint() }?.let { it childOf h }
		val text = Column(4).constrain { x = 65.at; y = CenterConstraint(); width = 100.percent - (65 + 12 + 160).at } childOf h
		val name = Label(title, 15, Weight.SEMIBOLD)
		val top = listOfNotNull(name, cardVersion(c.where, w)?.let { Label(it, 13, Weight.MEDIUM, Tok.muted) })
		text.add(row(8, *top.toTypedArray()))
		name.fitWithin(text, 60)
		val (icon, color, words) = status(c)
		text.add(row(6, Icon(icon, 14, color), Label(words, 13, Weight.MEDIUM, color).fitWithin(text)))
		return h
	}

	/** The one line under a pack's name: where its worlds play, or what's in the way. */
	private fun status(c: Card): Triple<String, Color, String> {
		val w = c.worlds.first()
		fun copy(g: GameInfo): String {
			val launcher = g.pack?.launcherName.orEmpty()
			val name = g.pack?.name.orEmpty()
			return when {
				// This game is a copy of the same pack under the same name: "your copy" could be either.
				name.isNotBlank() && name == Games.here.pack?.name -> if (launcher.isNotEmpty()) tr("tandem.plays_in_your_other_copy_s", launcher) else tr("tandem.plays_in_your_other_copy")
				name.isNotBlank() && name != cardTitle(c.where, w) && launcher.isNotEmpty() -> tr("tandem.plays_in_your_s_s", name, launcher)
				launcher.isNotEmpty() -> tr("tandem.plays_in_your_copy_s", launcher)
				name.isNotBlank() && name != cardTitle(c.where, w) -> tr("tandem.plays_in_your_s", name)
				else -> tr("tandem.plays_in_your_copy")
			}
		}
		return when (val where = c.where) {
			is Where.Here -> Triple("check", Tok.okText, tr("tandem.this_game"))
			is Where.OnPc -> Triple("pc", Tok.sec, copy(where.game))
			is Where.Vanilla -> where.game?.let { Triple("pc", Tok.sec, copy(it)) } ?: Triple("pc", Tok.muted, tr("tandem.opens_in_any_s_game_with_tandem", w.version ?: ""))
			is Where.Update -> Triple("warn", Tok.warnText, tr("tandem.yours_is_s", where.game.pack?.modpack?.label ?: tr("tandem.another_version")))
			is Where.Differs -> Triple("warn", Tok.warnText, diffLine(where.diff, saver(w)))
			is Where.GetIt -> Triple("pc", Tok.muted, tr("tandem.not_on_this_pc"))
			is Where.Own -> Triple("person", Tok.sec, if (w.mine) tr("tandem.your_own_pack_not_on_this_pc") else tr("tandem.s_own_pack_not_on_this_pc", w.owner ?: ""))
			is Where.Unknown -> Triple("clock", Tok.muted, tr("tandem.its_mods_differ_from_this_games"))
		}
	}

	private fun worldRow(w: World, where: Where): UIComponent {
		val here = where is Where.Here
		val row = UIContainer().constrain { width = 100.percent; height = 58.at }
		Thumb(w.bands, 56, 42, 6, !here, Tok.raise, w.icon).constrain { x = 12.at; y = CenterConstraint() } childOf row
		val more = IconButton("more", 34, Tok.raise) { menu(w, row) }.constrain { x = 10.fromEnd; y = CenterConstraint() } childOf row
		devTargets["synced-more-${w.name}"] = more
		val right = mutableListOf<UIComponent>()
		if (!w.mine && w.owner != null) right += Face({ skinOf(w, w.owner!!) }, 24, 6, null, Tok.raise)
		if (here) {
			val (label, kind) = actionFor(w)
			right += Button(label, kind, Size.SMALL, 124, Tok.raise) { act(w) }.also { it.enabled = Sample.reachable }
		}
		val side = row(12, *right.toTypedArray()).constrain { x = 54.fromEnd; y = CenterConstraint() } childOf row
		val info = UIContainer().constrain {
			x = 82.at; y = CenterConstraint(); width = 100.percent - (82 + 54 + 12).at - basicWidthConstraint { side.getWidth() }
			height = basicHeightConstraint { c -> c.children.sumOf { it.getHeight().toDouble() }.toFloat() + 3.tp }
		} childOf row
		val name = Label(w.name, 14.5, Weight.SEMIBOLD).fitWithin(info) childOf info
		val open = { details(w) }
		name.onMouseEnter { name.underline = true }
		name.onMouseLeave { name.underline = false }
		name.onMouseClick { it.stopPropagation(); open() }
		Focus.add(name, 4.tp, open)
		val line = when {
			here -> statusLine(w, info)
			hostOf(w) != null -> row(5, Icon("host", 14, Tok.violetHi), Label(tr("tandem.is_hosting_2", hostOf(w)), 13, Weight.MEDIUM, Tok.violetHi).fitWithin(info))
			else -> row(5, Icon("clock", 14, Tok.muted), Label(played(w), 13, Weight.REGULAR, Tok.muted).fitWithin(info))
		}
		line.constrain { y = SiblingConstraint(3.tp); height = 18.at } childOf info
		return row
	}

	fun howToPlay(w: World) = howTo.open(w, Games.where(w))

	/** Dev screenshots: How to play on its list of mods. */
	internal fun howToPlayList(w: World) = howTo.openList(w, Games.where(w))
}

/** "1 mod", "2 mods". */
fun modCount(n: Int) = if (n == 1) tr("tandem.one_mod") else tr("tandem.n_mods", n)

/** The key's "_one" wording for a single mod. */
private fun one(key: String, n: Int) = if (n == 1) key + "_one" else key

/** The status line for mods that differ: what's most likely the problem first. */
private fun diffLine(d: ModDiff, who: String?): String = when {
	d.onlyYours.isNotEmpty() && d.onlyTheirs.isEmpty() && d.otherVersion.isEmpty() ->
		if (who != null) tr("tandem.yours_has_d_that_s_doesnt", modCount(d.onlyYours.size), who) else tr("tandem.yours_has_d_this_world_doesnt", modCount(d.onlyYours.size))
	d.onlyTheirs.isNotEmpty() && d.onlyYours.isEmpty() && d.otherVersion.isEmpty() -> tr("tandem.yours_lacks_d", modCount(d.onlyTheirs.size))
	d.otherVersion.isNotEmpty() && d.onlyYours.isEmpty() && d.onlyTheirs.isEmpty() -> tr("tandem.d_at_another_version", modCount(d.otherVersion.size))
	else -> tr("tandem.d_differ_from_yours", modCount(d.total))
}

/**
 * How to play a synced world that doesn't play here: what it needs, a check or three, the one thing to do with its
 * button, and its mods on a second page, built for packs of hundreds.
 */
class HowToPlay(private val layers: Layers) {
	private companion object {
		const val WIDTH = 560
	}

	private class Check(val state: Int, val label: String, val value: String, val see: Boolean = false)

	private class Group(val label: String, val sentence: String, val rows: List<List<String>>, val different: Boolean)

	fun open(w: World, where: Where) {
		if (where is Where.Here || where is Where.OnPc || where is Where.Vanilla || where is Where.Unknown) return
		layers.openDialog { summary(w, where) }
	}

	/** Straight to the list of mods (dev screenshots). */
	internal fun openList(w: World, where: Where) {
		if (where is Where.Differs || where is Where.Own) layers.openDialog { list(w, where, 0) }
	}

	private fun summary(w: World, where: Where): UIComponent {
		val g = w.game
		val mp = g?.pack?.modpack
		val who = saver(w)
		val checks = mutableListOf<Check>()
		var fix: String
		var atlas: String? = null
		val buttons = mutableListOf<UIComponent>()
		val close = { kind: Kind -> Button(tr("tandem.close"), kind, Size.NORMAL, null, Tok.panel) { layers.closeDialog() } }
		fun get(m: Modpack, label: String) = m.page?.let { url -> Button(label, Kind.FILLED, Size.NORMAL, null, Tok.panel) { openLink(url) } }
		val title: String
		val version: String?
		val sub: String
		val see = { layers.openDialog { list(w, where, 0) } }
		when (where) {
			is Where.Update -> {
				title = where.need.title; version = where.need.label
				sub = tr("tandem.s_modpack_s", where.need.siteName, gameLine(w))
				val diff = Games.diff(where.game, w)
				checks += Check(1, tr("tandem.modpack"), tr("tandem.yours_is_s", where.game.pack?.modpack?.label ?: tr("tandem.another_version")))
				checks += Check(2, tr("tandem.mods"), if (diff.total > 0) if (diff.total == 1) tr("tandem.one_differs_checked_again_once_you_update") else tr("tandem.d_differ_checked_again_once_you_update", diff.total) else tr("tandem.updating_keeps_them_in_step"))
				fix = tr("tandem.update_your_copy_to_s_in_s_then_open", where.need.label ?: tr("tandem.that_version"), where.game.pack?.launcherName?.ifBlank { null } ?: tr("tandem.your_launcher"), w.name)
				w.hostedBy?.let { fix += " " + tr("tandem.s_is_hosting_it_now", it) }
				buttons += close(Kind.FILLED)
			}
			is Where.Differs -> {
				title = mp?.title ?: ""; version = mp?.label
				sub = (if (who != null) tr("tandem.as_s_plays_it", who) else tr("tandem.as_it_was_last_saved")) + " · " + gameLine(w)
				val d = where.diff
				checks += Check(0, tr("tandem.modpack"), where.game.pack?.launcherName?.ifBlank { null }?.let { tr("tandem.you_have_it_in_s", it) } ?: tr("tandem.you_have_it_on_this_pc"))
				checks += Check(1, tr("tandem.mods"), diffLine(d, who), see = true)
				val names = { mods: List<GameMod> -> if (mods.size <= 2) mods.joinToString(tr("tandem.and_joiner")) { it.name } else tr("tandem.these_d_mods", mods.size) }
				val newer = d.otherVersion.isNotEmpty() && d.otherVersion.all { (mine, needed) -> Games.newestFirst.compare(needed, mine.version) < 0 }
				val older = d.otherVersion.isNotEmpty() && d.otherVersion.all { (mine, needed) -> Games.newestFirst.compare(needed, mine.version) > 0 }
				fix = when {
					d.onlyYours.isNotEmpty() && d.onlyTheirs.isEmpty() && d.otherVersion.isEmpty() ->
						tr("tandem.install_s_again_as_a_separate_profile", listOfNotNull(mp?.title, mp?.label).joinToString(" "), w.name)
					d.onlyTheirs.isNotEmpty() && d.onlyYours.isEmpty() && d.otherVersion.isEmpty() -> tr("tandem.add_s_to_your_copy_then_open", names(d.onlyTheirs), w.name)
					d.onlyYours.isEmpty() && d.onlyTheirs.isEmpty() && newer ->
						tr("tandem.update_s_in_your_copy_to_s_versions", names(d.otherVersion.map { it.first }), who ?: tr("tandem.the_worlds"), w.name)
					d.onlyYours.isEmpty() && d.onlyTheirs.isEmpty() && older ->
						if (who != null) tr("tandem.ask_s_to_update_s", who, names(d.otherVersion.map { it.first }))
						else tr("tandem.update_s_in_the_copy_s_was_saved_in", names(d.otherVersion.map { it.first }), w.name)
					else -> tr("tandem.make_your_copy_match_s", who ?: tr("tandem.the_worlds_game"), w.name)
				}
				if (who != null) atlas = tr("tandem.or_s_can_share_their_copy_with_atlas", who)
				buttons += close(if (d.onlyYours.isNotEmpty() && d.onlyTheirs.isEmpty() && d.otherVersion.isEmpty() && mp?.page != null) Kind.OUTLINE else Kind.FILLED)
				if (d.onlyYours.isNotEmpty() && d.onlyTheirs.isEmpty() && d.otherVersion.isEmpty() && mp != null) get(mp, tr("tandem.get_s_from_s", mp.label ?: mp.title, mp.siteName))?.let { buttons += it }
			}
			is Where.GetIt -> {
				title = where.modpack.title; version = where.modpack.label
				sub = tr("tandem.s_modpack_s", where.modpack.siteName, gameLine(w))
				checks += Check(1, tr("tandem.modpack"), tr("tandem.not_on_this_pc"))
				checks += Check(2, tr("tandem.mods"), tr("tandem.checked_once_you_have_the_modpack"))
				fix = tr("tandem.get_s_from_s_add_tandem_to_it", listOfNotNull(where.modpack.title, where.modpack.label).joinToString(" "), where.modpack.siteName, w.name)
				if (who != null) fix += " " + tr("tandem.tandem_then_says_if_s_copy_differs", who)
				val get = get(where.modpack, tr("tandem.get_s", listOfNotNull(where.modpack.title, where.modpack.label).joinToString(" ")))
				buttons += close(if (get != null) Kind.OUTLINE else Kind.FILLED)
				get?.let { buttons += it }
			}
			is Where.Own -> {
				title = where.pack?.name?.ifBlank { null } ?: tr("tandem.s_game", whose(w.owner)); version = null
				val owner = w.owner ?: ""
				sub = (if (w.mine) tr("tandem.your_own_pack") else tr("tandem.s_own_pack", owner)) + " · " + gameLine(w)
				val all = g?.mods.orEmpty()
				checks += Check(1, tr("tandem.pack"), tr("tandem.not_a_modpack_so_it_cant_be_downloaded"))
				if (all.isNotEmpty()) checks += Check(2, tr("tandem.mods"), tr(one("tandem.d_and_d_of_them_add_to_the_world", all.count { it.adds }), all.size, all.count { it.adds }), see = true)
				checks += Check(2, tr("tandem.settings"), if (w.mine) tr("tandem.only_on_the_pc_that_has_it") else tr("tandem.only_on_s_pc", owner))
				fix = if (w.mine) tr("tandem.your_own_pack_fix") else tr("tandem.ask_s_to_share_s_with_atlas", owner, title, all.size)
				if (all.isNotEmpty()) buttons += Button(tr("tandem.copy_the_mod_list"), Kind.OUTLINE, Size.NORMAL, null, Tok.panel) { copy(w, listOf(groupAll(w)), all.size) }
				buttons += close(Kind.FILLED)
			}
			else -> return dialog(WIDTH)
		}
		val pic = PackPic({ mp?.let(ModIcons::pack) }, letterOf(title), 56, 12, Tok.panel)
		return dialog(WIDTH, head(pic, tr("tandem.to_play_s_you_need", w.name), title, version, sub), checks(checks, see), fixBox(fix, atlas), actions(WIDTH, *buttons.toTypedArray()))
	}

	private fun groupAll(w: World) = Group("", "", w.game?.mods.orEmpty().map { listOf(it.id, it.version) }, false)

	/** The second page: the mods, in groups with their counts, searchable once there are more than 8. */
	private fun list(w: World, where: Where, tab: Int): UIComponent {
		val g = w.game
		val names = HashMap<String, String>()
		g?.mods?.forEach { names[it.id] = it.name }
		val who = saver(w)
		val groups = when (where) {
			is Where.Differs -> {
				where.game.mods.forEach { names.putIfAbsent(it.id, it.name) }
				val d = where.diff
				listOfNotNull(
					d.onlyYours.takeIf { it.isNotEmpty() }?.let { m -> Group(tr("tandem.only_in_yours"), tr(one("tandem.only_in_yours_sentence", m.size), modCount(m.size), w.name, who ?: tr("tandem.your_other_copy")), m.map { listOf(it.id, it.version) }, false) },
					d.onlyTheirs.takeIf { it.isNotEmpty() }?.let { m -> Group(if (who != null) tr("tandem.only_in_s", who) else tr("tandem.only_in_the_worlds"), tr(one("tandem.only_in_theirs_sentence", m.size), modCount(m.size), w.name), m.map { listOf(it.id, it.version) }, false) },
					d.otherVersion.takeIf { it.isNotEmpty() }?.let { m -> Group(tr("tandem.other_version"), tr(one("tandem.other_version_sentence", m.size), modCount(m.size), w.name), m.map { (mine, v) -> listOf(mine.id, mine.version, v) }, true) },
				)
			}
			is Where.Own -> {
				val all = g?.mods.orEmpty()
				listOf(
					Group(tr("tandem.add_to_the_world"), tr(one("tandem.s_cant_open_without_these", all.count { it.adds }), w.name), all.filter { it.adds }.map { listOf(it.id, it.version) }, false),
					Group(tr("tandem.everything_else"), tr("tandem.libraries_speed_and_screen_mods"), all.filter { !it.adds }.map { listOf(it.id, it.version) }, false),
				).filter { it.rows.isNotEmpty() }
			}
			else -> emptyList()
		}
		if (groups.isEmpty()) return summary(w, where)
		val on = tab.coerceIn(0, groups.lastIndex)
		val cur = groups[on]
		val parts = mutableListOf<UIComponent>()
		val head = UIContainer().constrain { width = WIDTH.at; height = 44.at }
		val back = IconButton("back", 36, Tok.panel) { layers.openDialog { summary(w, where) } }.constrain { y = CenterConstraint() } childOf head
		val t = Column(2).constrain { x = SiblingConstraint(10.tp); y = CenterConstraint(); width = (WIDTH - 46 - 46).at } childOf head
		t.add(Label(if (where is Where.Own) tr("tandem.s_mods", g?.pack?.name ?: w.name) else tr("tandem.mods_that_differ"), 18, Weight.BOLD).fitWithin(t))
		t.add(Label(if (where is Where.Differs) (if (who != null) tr("tandem.your_s_and_s", where.game.title ?: tr("tandem.copy_lower"), whose(who)) else tr("tandem.this_copy_and_the_one_s_was_saved_in", w.name)) else tr("tandem.d_mods_on_s_pc", g?.mods?.size ?: 0, w.owner ?: ""), 13, Weight.REGULAR, Tok.muted).fitWithin(t))
		IconButton("close", 36, Tok.panel) { layers.closeDialog() }.constrain { x = 0.fromEnd; y = CenterConstraint() } childOf head
		Focus.add(back, back.corner) { layers.openDialog { summary(w, where) } }
		parts += head
		if (groups.size > 1) parts += segmented(groups.map { it.label to it.rows.size }, on, WIDTH) { i -> layers.openDialog { list(w, where, i) } }
		parts += wrapped(cur.sentence, 13.5, Weight.REGULAR, Tok.sec, WIDTH.tp, 1.5f)
		val mods = ModList(cur.rows, cur.different, names)
		if (cur.rows.size > 8) parts += Field(tr("tandem.search_mods", cur.rows.size), null) { mods.query = it }
		parts += mods
		parts += actions(
			WIDTH,
			Button(tr("tandem.copy_the_list"), Kind.OUTLINE, Size.NORMAL, null, Tok.panel) { copy(w, listOf(cur), cur.rows.size, names) },
			Button(tr("tandem.back"), Kind.FILLED, Size.NORMAL, null, Tok.panel) { layers.openDialog { summary(w, where) } },
		)
		return dialog(WIDTH, *parts.toTypedArray())
	}

	private fun head(pic: UIComponent, need: String, title: String, version: String?, sub: String): UIComponent {
		val c = UIContainer().constrain { width = WIDTH.at; height = 62.at }
		pic.constrain { y = CenterConstraint() } childOf c
		val col = Column(2).constrain { x = 70.at; y = CenterConstraint(); width = (WIDTH - 70 - 44).at } childOf c
		col.add(Label(need, 13, Weight.REGULAR, Tok.muted).fitWithin(col))
		val name = Label(title, 20, Weight.BOLD)
		col.add(row(8, *listOfNotNull(name, version?.let { Label(it, 15, Weight.SEMIBOLD, Tok.sec) }).toTypedArray()))
		name.fitWithin(col, 70)
		col.add(Label(sub, 13, Weight.REGULAR, Tok.muted).fitWithin(col))
		IconButton("close", 36, Tok.panel) { layers.closeDialog() }.constrain { x = 0.fromEnd; y = 0.at } childOf c
		return c
	}

	/** The checks, one line each: ticked, needs you, or for information; Mods can open the list. */
	private fun checks(rows: List<Check>, see: () -> Unit): UIComponent {
		val box = box(12, Tok.rail, Tok.line).constrain { width = WIDTH.at }
		val col = Column(0).constrain { width = 100.percent } childOf box
		for ((i, r) in rows.withIndex()) {
			if (i > 0) col.add(box(0, Tok.track).constrain { x = 1.at; width = 100.percent - 2.at; height = 1.at })
			val line = UIContainer().constrain { width = 100.percent; height = 50.at }
			val tint = when (r.state) { 0 -> Tok.okText; 1 -> Tok.warnText; else -> Tok.muted }
			val badge = box(13, Tok.over(Tok.rail, tint, 0.15)).constrain { x = 14.at; y = CenterConstraint(); width = 26.at; height = 26.at } childOf line
			if (r.state == 2) box(1, tint).constrain { x = CenterConstraint(); y = CenterConstraint(); width = 10.at; height = 2.at } childOf badge
			else Icon(if (r.state == 0) "check" else "warn", 14, tint).constrain { x = CenterConstraint(); y = CenterConstraint() } childOf badge
			Label(r.label, 13, Weight.SEMIBOLD, Tok.muted).constrain { x = 52.at; y = CenterConstraint() } childOf line
			val value = Label(r.value, 14, Weight.REGULAR, Tok.ink).constrain { x = 134.at; y = CenterConstraint() } childOf line
			if (r.see) {
				val l = link(tr("tandem.see_them")) { see() }.constrain { x = 14.fromEnd; y = CenterConstraint() } childOf line
				l.underline = false
				value.fitBefore(l, 12)
			} else value.fitWithin(line, 14)
			col.add(line)
		}
		box.constrain { height = basicHeightConstraint { col.getHeight() } }
		return box
	}

	/** The one thing to do, in violet, with the Atlas line under it when a friend's own copy is the exact answer. */
	private fun fixBox(text: String, atlas: String?): UIComponent {
		val b = box(12, Tok.over(Tok.panel, Tok.violet, 0.07), Tok.alpha(Tok.violet, 0.45)).constrain { width = WIDTH.at }
		val col = Column(6).constrain { x = 16.at; y = 14.at; width = (WIDTH - 32).at } childOf b
		col.add(Label(tr("tandem.do_this"), 12.5, Weight.BOLD, Tok.violetHi))
		col.add(wrapped(text, 14, Weight.REGULAR, Tok.ink, (WIDTH - 32).tp, 1.5f))
		if (atlas != null) {
			val mark = UIContainer().constrain { width = 16.at; height = 16.at }
			Icon("atlas-light", 16, Tok.atlasLight) childOf mark
			Icon("atlas-dark", 16, Tok.atlasDark) childOf mark
			col.add(row(8, mark, Label(atlas, 13, Weight.REGULAR, Tok.sec), link(tr("tandem.get_atlas"), Tok.atlas) { openLink("https://keplersync.app") }), 4)
		}
		b.constrain { height = basicHeightConstraint { col.getHeight() + 28.tp } }
		return b
	}

	private fun copy(w: World, groups: List<Group>, count: Int, names: Map<String, String> = w.game?.mods?.associate { it.id to it.name }.orEmpty()) {
		val lines = buildList {
			add(tr("tandem.mods_for_s", w.name))
			for (g in groups) for (r in g.rows) {
				val n = names[r[0]] ?: ModIcons.name(r[0])
				add(if (g.different) tr("tandem.copy_line_versions", n, r[0], r[1], r[2]) else tr("tandem.copy_line", n, r[0], r[1]))
			}
		}
		Minecraft.getInstance().keyboardHandler.clipboard = lines.joinToString("\n")
		layers.toast(tr("tandem.copied_the_list_of_mods", modCount(count)), tr("tandem.paste_it_to_a_friend_or_into"))
	}

	private fun openLink(url: String) {
		val mc = Minecraft.getInstance()
		ConfirmLinkScreen.confirmLinkNow(mc.screen, url)
	}
}
