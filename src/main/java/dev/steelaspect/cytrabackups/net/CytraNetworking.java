package dev.steelaspect.cytrabackups.net;

import dev.steelaspect.cytrabackups.CytraBackups;
import dev.steelaspect.cytrabackups.core.backup.BackupMeta;
import dev.steelaspect.cytrabackups.core.config.ConfigSchema;
import dev.steelaspect.cytrabackups.mc.BackupManager;
import dev.steelaspect.cytrabackups.mc.Feedback;
import dev.steelaspect.cytrabackups.mc.Msg;
import dev.steelaspect.cytrabackups.mc.Perms;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Optional GUI channel. The server only ever sends these payloads to clients that announced support, so vanilla
 * clients are never affected. GUI actions run through the normal {@code /backup} command tree as the player, so every
 * permission check and message is the same as typing the command; only the settings editor has its own handlers,
 * which require cytrabackups.admin like /backup reload.
 */
public final class CytraNetworking {
	private CytraNetworking() {
	}

	public static void registerPayloadTypes() {
		PayloadTypeRegistry.playC2S().register(RequestPayload.TYPE, RequestPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(CommandPayload.TYPE, CommandPayload.CODEC);
		PayloadTypeRegistry.playC2S().register(ConfigSavePayload.TYPE, ConfigSavePayload.CODEC);
		PayloadTypeRegistry.playS2C().register(BackupListPayload.TYPE, BackupListPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(MessagePayload.TYPE, MessagePayload.CODEC);
		PayloadTypeRegistry.playS2C().register(PromptPayload.TYPE, PromptPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(ConfigPayload.TYPE, ConfigPayload.CODEC);
	}

	public static void registerServerHandlers() {
		ServerPlayNetworking.registerGlobalReceiver(RequestPayload.TYPE, (payload, ctx) -> handleRequest(payload, ctx.player()));
		ServerPlayNetworking.registerGlobalReceiver(CommandPayload.TYPE, (payload, ctx) -> runCommand(ctx.player(), payload.command()));
		ServerPlayNetworking.registerGlobalReceiver(ConfigSavePayload.TYPE, (payload, ctx) -> saveConfig(ctx.player(), payload));
	}

	public static void sendMessage(ServerPlayer player, Component message, boolean error) {
		if (ServerPlayNetworking.canSend(player, MessagePayload.TYPE)) ServerPlayNetworking.send(player, new MessagePayload(message, error));
	}

	/** Opens a confirmation dialog when the prompt came from a command the GUI started. */
	public static boolean sendPrompt(CommandSourceStack src, String token, String title, String details, int timeoutSeconds) {
		return src.source instanceof GuiCommandSource gui && gui.prompt(new PromptPayload(token, title, details, timeoutSeconds));
	}

	static void runCommand(ServerPlayer player, String command) {
		runCommand(new GuiCommandSource(player), command);
	}

	/**
	 * Runs a /backup command for the GUI as the player, through the normal command tree (so the same permission checks
	 * apply); any other command is refused. Server thread.
	 */
	public static void runCommand(GuiCommandSource out, String command) {
		ServerPlayer player = out.player();
		String cmd = command.startsWith("/") ? command.substring(1) : command;
		if (!(cmd.equals("backup") || cmd.startsWith("backup "))) {
			out.sendSystemMessage(Msg.error("The GUI can only run /backup commands."));
			return;
		}
		if (BackupManager.getOrNull() == null) {
			out.sendSystemMessage(Msg.error("CytraBackups is not running on this server."));
			return;
		}
		CytraBackups.LOGGER.info("{} ran /{} from the CytraBackups GUI", player.getGameProfile().name(), cmd);
		CommandSourceStack src = player.createCommandSourceStack().withSource(out);
		player.level().getServer().getCommands().performPrefixedCommand(src, cmd);
	}

	static void handleRequest(RequestPayload p, ServerPlayer player) {
		BackupManager m = BackupManager.getOrNull();
		if (m == null) {
			sendMessage(player, Msg.error("CytraBackups is not running on this server."), true);
			return;
		}
		switch (p.action()) {
			case RequestPayload.LIST -> sendList(player, m);
			case RequestPayload.CONFIG -> {
				if (!Perms.check(player, Perms.ADMIN)) {
					sendMessage(player, Msg.error("Changing settings needs the cytrabackups.admin permission."), true);
					return;
				}
				sendConfig(player, m, List.of());
			}
			default -> sendMessage(player, Msg.error("Unknown request " + p.action()), true);
		}
	}

	static void sendConfig(ServerPlayer player, BackupManager m, List<String> problems) {
		if (ServerPlayNetworking.canSend(player, ConfigPayload.TYPE)) {
			ServerPlayNetworking.send(player, new ConfigPayload(ConfigSchema.describe(m.config()), problems));
		}
	}

	static void saveConfig(ServerPlayer player, ConfigSavePayload p) {
		BackupManager m = BackupManager.getOrNull();
		if (m == null) return;
		Feedback fb = Feedback.of(player.createCommandSourceStack()).and(Feedback.console());
		if (!Perms.check(player, Perms.ADMIN)) {
			fb.error("Changing settings needs the cytrabackups.admin permission.");
			return;
		}
		if (p.changes().isEmpty()) {
			fb.info("No settings were changed.");
			return;
		}
		ConfigSchema.Result r = ConfigSchema.apply(m.config(), p.changes());
		if (!r.ok()) {
			fb.error("Settings not saved: " + String.join("; ", r.problems()));
			sendConfig(player, m, r.problems());
			return;
		}
		fb.info(player.getGameProfile().name() + " changed " + String.join(", ", p.changes().keySet()) + "; saving and reloading...");
		m.saveConfigAndReload(r.config(), fb, () -> sendConfig(player, m, List.of()));
	}

	public static void sendList(ServerPlayer player, BackupManager m) {
		if (!ServerPlayNetworking.canSend(player, BackupListPayload.TYPE)) return;
		boolean canList = Perms.check(player, Perms.LIST);
		List<BackupListPayload.Entry> entries = new ArrayList<>();
		List<BackupMeta> list = m.services().repo.list();
		for (int i = list.size() - 1; canList && i >= 0 && entries.size() < 500; i--) {
			BackupMeta b = list.get(i);
			entries.add(new BackupListPayload.Entry(b.id, b.createdAt, b.trigger.displayName(), b.comment, b.creator, b.totalSize, b.newStoredBytes, b.pinned, b.partial));
		}
		int perms = 0;
		String[] nodes = {Perms.CREATE, Perms.RESTORE, Perms.DELETE, Perms.PIN, Perms.COMMENT, Perms.VERIFY, Perms.EXPORT, Perms.PRUNE, Perms.CANCEL, Perms.ADMIN, Perms.LIST};
		int[] flags = {BackupListPayload.CAN_CREATE, BackupListPayload.CAN_RESTORE, BackupListPayload.CAN_DELETE, BackupListPayload.CAN_PIN,
			BackupListPayload.CAN_COMMENT, BackupListPayload.CAN_VERIFY, BackupListPayload.CAN_EXPORT, BackupListPayload.CAN_PRUNE,
			BackupListPayload.CAN_CANCEL, BackupListPayload.CAN_ADMIN, BackupListPayload.CAN_LIST};
		for (int i = 0; i < nodes.length; i++) if (Perms.check(player, nodes[i])) perms |= flags[i];
		BackupManager.Job job = m.currentJob();
		String countdown = m.countdownLabel();
		String status = countdown != null ? countdown + " (countdown running)" : job == null ? (canList ? list.size() + " backups" : "") : job.name() + " running";
		List<String> dims = new ArrayList<>();
		for (ServerLevel level : m.server().getAllLevels()) dims.add(level.dimension().identifier().toString());
		ServerPlayNetworking.send(player, new BackupListPayload(entries, status, job == null ? "" : job.progress().phase(),
			job == null ? -1f : job.progress().fraction(), perms, m.server().getPlayerList().getViewDistance(), dims));
	}
}
