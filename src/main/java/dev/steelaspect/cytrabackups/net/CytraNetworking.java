package dev.steelaspect.cytrabackups.net;

import dev.steelaspect.cytrabackups.core.backup.BackupMeta;
import dev.steelaspect.cytrabackups.core.backup.ChunkSelection;
import dev.steelaspect.cytrabackups.core.backup.Trigger;
import dev.steelaspect.cytrabackups.mc.BackupManager;
import dev.steelaspect.cytrabackups.mc.Feedback;
import dev.steelaspect.cytrabackups.mc.Perms;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Optional GUI channel. The server only ever sends these payloads to clients that announced support, so vanilla
 * clients are never affected; every request is permission-checked like the matching command.
 */
public final class CytraNetworking {
	private CytraNetworking() {
	}

	public static void registerPayloadTypes() {
		PayloadTypeRegistry.playC2S().register(RequestPayload.TYPE, RequestPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(BackupListPayload.TYPE, BackupListPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(MessagePayload.TYPE, MessagePayload.CODEC);
	}

	public static void registerServerHandlers() {
		ServerPlayNetworking.registerGlobalReceiver(RequestPayload.TYPE, (payload, ctx) -> handle(payload, ctx.player()));
	}

	public static void sendMessage(ServerPlayer player, String text, boolean error) {
		if (ServerPlayNetworking.canSend(player, MessagePayload.TYPE)) ServerPlayNetworking.send(player, new MessagePayload(text, error));
	}

	static void handle(RequestPayload p, ServerPlayer player) {
		BackupManager m = BackupManager.getOrNull();
		if (m == null) {
			sendMessage(player, "CytraBackups is not running on this server.", true);
			return;
		}
		Feedback fb = Feedback.of(player.createCommandSourceStack());
		String name = player.getGameProfile().name();
		switch (p.action()) {
			case RequestPayload.LIST -> {
				if (deny(player, Perms.LIST, fb)) return;
				sendList(player, m);
			}
			case RequestPayload.CREATE -> {
				if (deny(player, Perms.CREATE, fb)) return;
				if (m.createBackup(Trigger.MANUAL, p.text(), name, fb)) fb.info("Backup started...");
			}
			case RequestPayload.RESTORE -> {
				if (deny(player, Perms.RESTORE, fb)) return;
				if (m.services().repo.get(p.id()).isEmpty()) {
					fb.error("No backup #" + p.id() + ".");
					return;
				}
				m.startFullRestore(p.id(), name, fb.and(Feedback.console()));
			}
			case RequestPayload.RESTORE_CHUNKS -> {
				if (deny(player, Perms.RESTORE, fb)) return;
				ServerLevel level = m.levelOf(p.dimension());
				if (level == null || m.services().repo.get(p.id()).isEmpty()) {
					fb.error("Unknown dimension or backup.");
					return;
				}
				ChunkSelection sel = ChunkSelection.box(p.x1(), p.z1(), p.x2(), p.z2());
				if (sel.chunkCount() > m.config().restore.maxChunks) {
					fb.error("Selection too large (" + sel.chunkCount() + " chunks, limit " + m.config().restore.maxChunks + ").");
					return;
				}
				m.startChunkRestore(p.id(), level, sel, name, fb.and(Feedback.console()));
			}
			case RequestPayload.DELETE -> {
				if (deny(player, Perms.DELETE, fb)) return;
				m.delete(p.id(), name, fb);
			}
			case RequestPayload.PIN, RequestPayload.UNPIN -> {
				if (deny(player, Perms.PIN, fb)) return;
				m.setPinned(p.id(), p.action().equals(RequestPayload.PIN), fb);
			}
			case RequestPayload.COMMENT -> {
				if (deny(player, Perms.COMMENT, fb)) return;
				m.setComment(p.id(), p.text(), fb);
			}
			case RequestPayload.VERIFY -> {
				if (deny(player, Perms.VERIFY, fb)) return;
				m.verify(p.id(), name, fb);
			}
			default -> fb.error("Unknown request " + p.action());
		}
	}

	private static boolean deny(ServerPlayer player, String node, Feedback fb) {
		if (Perms.check(player, node)) return false;
		fb.error("You don't have permission cytrabackups." + node + ".");
		return true;
	}

	public static void sendList(ServerPlayer player, BackupManager m) {
		if (!ServerPlayNetworking.canSend(player, BackupListPayload.TYPE)) return;
		List<BackupListPayload.Entry> entries = new ArrayList<>();
		List<BackupMeta> list = m.services().repo.list();
		for (int i = list.size() - 1; i >= 0 && entries.size() < 500; i--) {
			BackupMeta b = list.get(i);
			entries.add(new BackupListPayload.Entry(b.id, b.createdAt, b.trigger.displayName(), b.comment, b.creator, b.totalSize, b.newStoredBytes, b.pinned, b.partial));
		}
		int perms = 0;
		if (Perms.check(player, Perms.CREATE)) perms |= BackupListPayload.CAN_CREATE;
		if (Perms.check(player, Perms.RESTORE)) perms |= BackupListPayload.CAN_RESTORE;
		if (Perms.check(player, Perms.DELETE)) perms |= BackupListPayload.CAN_DELETE;
		if (Perms.check(player, Perms.PIN)) perms |= BackupListPayload.CAN_PIN;
		if (Perms.check(player, Perms.COMMENT)) perms |= BackupListPayload.CAN_COMMENT;
		BackupManager.Job job = m.currentJob();
		String status = job == null ? list.size() + " backups" : job.name() + " running";
		ServerPlayNetworking.send(player, new BackupListPayload(entries, status, job == null ? "" : job.progress().phase(),
			job == null ? -1f : job.progress().fraction(), perms, m.server().getPlayerList().getViewDistance()));
	}
}
