package dev.ilgax.minekt

import com.terraformersmc.modmenu.api.ConfigScreenFactory
import com.terraformersmc.modmenu.api.ModMenuApi

/** Mod Menu's Configure button opens Minekt's settings. Only loaded when Mod Menu is installed. */
class ModMenuEntry : ModMenuApi {
    override fun getModConfigScreenFactory() = ConfigScreenFactory { parent -> MinektClient.settingsScreen(parent) }
}
