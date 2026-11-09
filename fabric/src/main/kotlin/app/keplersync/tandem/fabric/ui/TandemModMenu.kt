package app.keplersync.tandem.fabric.ui

import com.terraformersmc.modmenu.api.ConfigScreenFactory
import com.terraformersmc.modmenu.api.ModMenuApi

/** Mod Menu's Settings button for Tandem opens Tandem on its Settings; closing it goes back to Mod Menu. */
class TandemModMenu : ModMenuApi {
	override fun getModConfigScreenFactory() = ConfigScreenFactory { _ -> PlayScreen().also { it.afterOpen { s -> s.showSettings() } } }
}
