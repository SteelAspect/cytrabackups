package dev.steelaspect.cytrabackups.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.steelaspect.cytrabackups.CytraBackups;
import dev.steelaspect.cytrabackups.net.BackupListPayload;
import dev.steelaspect.cytrabackups.net.ConfigPayload;
import dev.steelaspect.cytrabackups.net.MessagePayload;
import dev.steelaspect.cytrabackups.net.PromptPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Optional client GUI. Only talks to servers that register the CytraBackups channel; without it nothing is sent
 * and the player is pointed to the /backup commands instead. Open it with /backupgui, a key binding (unbound by
 * default) or the "Backups" button in the pause menu.
 */
public final class CytraBackupsClient implements ClientModInitializer {
	private static KeyMapping openKey;
	private static boolean openRequested;

	@Override
	public void onInitializeClient() {
		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(CytraBackups.MOD_ID, "main"));
		openKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.cytrabackups.open", InputConstants.UNKNOWN.getValue(), category));
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			while (openKey.consumeClick()) openRequested = true;
			if (openRequested) {
				openRequested = false;
				open(mc);
			}
		});
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, ctx) -> dispatcher.register(ClientCommandManager.literal("backupgui").executes(c -> {
			openRequested = true; // opened next tick, after the chat screen has closed
			return 1;
		})));
		ClientPlayNetworking.registerGlobalReceiver(BackupListPayload.TYPE, (payload, context) -> ClientState.update(payload));
		ClientPlayNetworking.registerGlobalReceiver(MessagePayload.TYPE, (payload, context) -> ClientState.message(payload));
		ClientPlayNetworking.registerGlobalReceiver(ConfigPayload.TYPE, (payload, context) -> ClientState.config(payload));
		ClientPlayNetworking.registerGlobalReceiver(PromptPayload.TYPE, (payload, context) -> prompt(context.client(), payload));
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ClientState.clear());
		ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
			if (screen instanceof PauseScreen && ClientState.serverSupported()) {
				Screens.getButtons(screen).add(Button.builder(Component.literal("Backups"), b -> open(client)).bounds(4, h - 24, 64, 20)
					.tooltip(Tooltip.create(Component.literal("CytraBackups: create, restore and manage backups"))).build());
			}
		});
	}

	/** A GUI action needs confirmation: show a dialog and answer with /backup confirm|deny <token>. */
	private static void prompt(Minecraft mc, PromptPayload p) {
		Screen back = mc.screen;
		mc.setScreen(new ConfirmScreen(yes -> {
			mc.setScreen(back);
			ClientState.run("backup " + (yes ? "confirm " : "deny ") + p.token());
		}, Component.literal(p.title()), Component.literal(p.details() + "\n\nThis dialog expires after " + p.timeoutSeconds() + " seconds."),
			Component.literal("Confirm"), Component.literal("Cancel")));
	}

	public static void open(Minecraft mc) {
		if (mc.player == null) return;
		if (!ClientState.serverSupported()) {
			mc.player.displayClientMessage(Component.literal("[CytraBackups] This server does not run CytraBackups' GUI channel (or runs another version). "
				+ "Use the /backup commands instead."), false);
			return;
		}
		ClientState.requestList();
		mc.setScreen(new BackupScreen());
	}
}
