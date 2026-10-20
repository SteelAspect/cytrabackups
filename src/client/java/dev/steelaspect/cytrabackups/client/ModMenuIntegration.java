package dev.steelaspect.cytrabackups.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** Config button in Mod Menu. Only loaded by Mod Menu itself, so Alloy Backups works fine without it. */
public final class ModMenuIntegration implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return CytraBackupsClient::screenFor;
	}
}
