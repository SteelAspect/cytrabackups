package dev.steelaspect.cytrabackups.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.steelaspect.cytrabackups.CytraBackups;
import dev.steelaspect.cytrabackups.net.BackupListPayload;
import dev.steelaspect.cytrabackups.net.MessagePayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/**
 * Optional client GUI. Only talks to servers that register the CytraBackups channel; without it nothing is sent
 * and the player is pointed to the /backup commands instead.
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
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ClientState.clear());
	}

	public static void open(Minecraft mc) {
		if (mc.player == null) return;
		if (!ClientState.serverSupported()) {
			mc.player.displayClientMessage(Component.literal("[CytraBackups] This server does not run CytraBackups' GUI channel. Use the /backup commands instead."), false);
			return;
		}
		ClientState.requestList();
		mc.setScreen(new BackupListScreen());
	}
}
