package dev.steelaspect.cytrabackups.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.steelaspect.cytrabackups.CytraBackups;
import dev.steelaspect.cytrabackups.net.BackupListPayload;
import dev.steelaspect.cytrabackups.net.ConfigPayload;
import dev.steelaspect.cytrabackups.net.MessagePayload;
import dev.steelaspect.cytrabackups.net.PromptPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Optional client GUI. Only talks to servers that register the CytraBackups channel; without it nothing is sent
 * and the player is pointed to the /cbackup commands instead. Open it with /cbackupgui, a key binding (unbound by
 * default) or the "Backups" button in the pause menu.
 */
public final class CytraBackupsClient implements ClientModInitializer {
	private static KeyMapping openKey;
	private static boolean openRequested;

	@Override
	public void onInitializeClient() {
		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(CytraBackups.MOD_ID, "main"));
		openKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.cytrabackups.open", InputConstants.UNKNOWN.getValue(), category));
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			while (openKey.consumeClick()) openRequested = true;
			if (openRequested) {
				openRequested = false;
				open(mc);
			}
		});
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, ctx) -> dispatcher.register(ClientCommands.literal("cbackupgui").executes(c -> {
			openRequested = true; // opened next tick, after the chat screen has closed
			return 1;
		})));
		ClientPlayNetworking.registerGlobalReceiver(BackupListPayload.TYPE, (payload, context) -> ClientState.update(payload));
		ClientPlayNetworking.registerGlobalReceiver(MessagePayload.TYPE, (payload, context) -> ClientState.message(payload));
		ClientPlayNetworking.registerGlobalReceiver(ConfigPayload.TYPE, (payload, context) -> ClientState.config(payload));
		ClientPlayNetworking.registerGlobalReceiver(PromptPayload.TYPE, (payload, context) -> Dialogs.prompt(payload));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ClientState.clear());
		ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
			if (screen instanceof PauseScreen && ClientState.serverSupported()) {
				Screens.getWidgets(screen).add(Button.builder(Component.translatable("cytrabackups.gui.pause_button"), b -> open(client)).bounds(4, h - 24, 64, 20)
					.tooltip(Tooltip.create(Component.translatable("cytrabackups.gui.pause_button.tooltip"))).build());
			}
		});
	}

	public static void open(Minecraft mc) {
		if (mc.player == null) return;
		if (!ClientState.serverSupported()) {
			mc.player.sendSystemMessage(Component.translatable("cytrabackups.gui.unsupported"));
			return;
		}
		ClientState.requestList();
		mc.gui.setScreen(new BackupScreen(null));
	}

	/**
	 * The screen Mod Menu opens: the menu when connected to a server with Alloy Backups, otherwise a notice saying
	 * where it works. Both go back to {@code parent}.
	 */
	public static Screen screenFor(Screen parent) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null && ClientState.serverSupported()) {
			ClientState.requestList();
			return new BackupScreen(parent);
		}
		Dialogs.HostScreen host = new Dialogs.HostScreen(parent);
		host.openOverlay(Dialogs.notice(Component.translatable("cytrabackups.gui.title"), Component.translatable("cytrabackups.gui.modmenu.no_world")), true);
		return host;
	}
}
