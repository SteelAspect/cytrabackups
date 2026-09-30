package dev.steelaspect.cytrabackups.client;

import dev.steelaspect.cytrabackups.mc.BackupCommands;
import dev.steelaspect.cytrabackups.net.BackupListPayload;
import dev.steelaspect.cytrabackups.net.CommandPayload;
import dev.steelaspect.cytrabackups.net.ConfigPayload;
import dev.steelaspect.cytrabackups.net.ConfigSavePayload;
import dev.steelaspect.cytrabackups.net.MessagePayload;
import dev.steelaspect.cytrabackups.net.RequestPayload;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.network.chat.contents.TranslatableContents;

/** Latest data received from the server and the GUI output log (client thread only). */
final class ClientState {
	static final int MAX_LOG = 300;

	/** One message in the GUI output panel. */
	record LogLine(Component text, boolean error, long at) {
	}

	static BackupListPayload list;
	static ConfigPayload config;
	static long version;
	static long configVersion;
	static final List<LogLine> log = new ArrayList<>();
	static long logVersion;

	private ClientState() {
	}

	static void update(BackupListPayload payload) {
		list = payload;
		version++;
	}

	static void config(ConfigPayload payload) {
		config = payload;
		configVersion++;
	}

	static void message(MessagePayload payload) {
		log(stripPrefix(payload.message()), payload.error());
		requestList(); // most messages mean something changed
	}

	static void log(Component text, boolean error) {
		log.add(new LogLine(text, error, System.currentTimeMillis()));
		while (log.size() > MAX_LOG) log.removeFirst();
		logVersion++;
	}

	static LogLine lastLog() {
		return log.isEmpty() ? null : log.getLast();
	}

	/** Drops the "[CytraBackups]" chat prefix (also when wrapped by a command failure), keeping colours and links. */
	static Component stripPrefix(Component c) {
		if (c.getContents() instanceof TranslatableContents t && t.getKey().equals("cytrabackups.chat.prefix")) {
			MutableComponent out = Component.empty();
			List<Component> rest = c.getSiblings();
			rest.subList(Math.min(1, rest.size()), rest.size()).forEach(out::append); // skip the space after the prefix
			return out;
		}
		if (c.getContents() instanceof PlainTextContents p && p.text().isEmpty() && c.getSiblings().size() == 1) {
			return stripPrefix(c.getSiblings().getFirst()).copy().withStyle(c.getStyle());
		}
		return c;
	}

	static void clear() {
		list = null;
		config = null;
		log.clear();
		logVersion++;
	}

	static boolean serverSupported() {
		return ClientPlayNetworking.canSend(CommandPayload.TYPE);
	}

	static void requestList() {
		if (serverSupported()) ClientPlayNetworking.send(new RequestPayload(RequestPayload.LIST));
	}

	static void requestConfig() {
		if (serverSupported()) ClientPlayNetworking.send(new RequestPayload(RequestPayload.CONFIG));
	}

	static void saveConfig(Map<String, String> changes) {
		if (serverSupported()) ClientPlayNetworking.send(new ConfigSavePayload(changes));
	}

	/** Runs {@code /cbackup <sub>} on the server; its output comes back into the log. */
	static void run(String sub) {
		runRaw(BackupCommands.ROOT + " " + sub);
	}

	/** Runs a full /cbackup command line typed or edited by the player. */
	static void runRaw(String command) {
		String cmd = command.startsWith("/") ? command.substring(1) : command;
		if (!serverSupported()) return;
		log(Component.literal("> /" + cmd).withStyle(ChatFormatting.DARK_GRAY), false);
		ClientPlayNetworking.send(new CommandPayload(cmd.length() > CommandPayload.MAX_LENGTH ? cmd.substring(0, CommandPayload.MAX_LENGTH) : cmd));
	}

	static boolean isOwnCommand(String command) {
		String cmd = command.startsWith("/") ? command.substring(1) : command;
		return cmd.equals(BackupCommands.ROOT) || cmd.startsWith(BackupCommands.ROOT + " ");
	}

	static boolean can(int flag) {
		return list != null && list.can(flag);
	}
}
