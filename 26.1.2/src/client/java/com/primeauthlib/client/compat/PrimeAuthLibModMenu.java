package com.primeauthlib.client.compat;

import com.primeauthlib.client.gui.AuthLibSkinScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * ModMenu integration for PrimeAuthLib.
 * Provides a direct 'Config' button in the ModMenu mods list.
 * Only loaded when ModMenu is present.
 */
public class PrimeAuthLibModMenu implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return AuthLibSkinScreen::new;
	}
}

