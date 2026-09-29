package dev.steelaspect.cytrabackups.client;

import dev.steelaspect.cytrabackups.net.BackupListPayload;
import dev.steelaspect.cytrabackups.net.MessagePayload;
import dev.steelaspect.cytrabackups.net.RequestPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/** Latest data received from the server (client thread only). */
final class ClientState {
	static BackupListPayload list;
	static String message = "";
	static boolean messageError;
	static long messageAt;
	static long version;

	private ClientState() {
	}

	static void update(BackupListPayload payload) {
		list = payload;
		version++;
	}

	static void message(MessagePayload payload) {
		message = payload.text().replace("[CytraBackups] ", "");
		messageError = payload.error();
		messageAt = System.currentTimeMillis();
		// most messages mean something changed: refresh the list shortly
		requestList();
	}

	static void clear() {
		list = null;
		message = "";
	}

	static boolean serverSupported() {
		return ClientPlayNetworking.canSend(RequestPayload.TYPE);
	}

	static void send(RequestPayload payload) {
		if (serverSupported()) ClientPlayNetworking.send(payload);
	}

	static void requestList() {
		send(RequestPayload.simple(RequestPayload.LIST, 0));
	}
}
