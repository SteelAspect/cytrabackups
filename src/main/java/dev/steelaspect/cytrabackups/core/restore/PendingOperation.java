package dev.steelaspect.cytrabackups.core.restore;

import dev.steelaspect.cytrabackups.core.Lang;
import dev.steelaspect.cytrabackups.core.backup.ChunkSelection;
import java.util.ArrayList;
import java.util.List;

/**
 * An operation queued for the next time the world is opened ({@code pending-restore.json}). Applied by a mixin
 * before Minecraft reads anything from the world folder.
 */
public final class PendingOperation {
	public enum Type {
		FULL_RESTORE, CHUNK_RESTORE, ROLLBACK,
		/** Replace this server's folder with a server backup stored off-site (/cbackup migrate import). */
		MIGRATE_IMPORT
	}

	public Type type;
	public int backupId;
	/** For ROLLBACK: the restore to undo. */
	public String restoreId;
	public String dimension = "minecraft:overworld";
	public String dimensionFolder = "";
	public List<ChunkSelection.Box> boxes = new ArrayList<>();
	/** Absolute path of the world folder the operation targets. */
	public String worldDir;
	public String requestedBy = "";
	public long requestedAt;
	/** Why a chunk restore was queued instead of applied live. */
	public String reason = "";
	/** MIGRATE_IMPORT: where the old server's backups are (S3 prefix, SFTP folder or WebDAV URL) and whether this server's own backup settings are replaced too. */
	public String location = "";
	public boolean replaceBackupSettings;
	/** Number of failed attempts; the operation is dropped after one failure to avoid restart loops. */
	public int attempts;

	public ChunkSelection selection() {
		return new ChunkSelection(boxes);
	}

	public String describe() {
		return switch (type) {
			case FULL_RESTORE -> Lang.get("cytrabackups.pending.full", backupId);
			case CHUNK_RESTORE -> Lang.get("cytrabackups.pending.chunks", backupId, selection().describe(), dimension);
			case MIGRATE_IMPORT -> Lang.get("cytrabackups.pending.migrate", backupId, location);
			case ROLLBACK -> restoreId == null ? Lang.get("cytrabackups.pending.rollback_last") : Lang.get("cytrabackups.pending.rollback", restoreId);
		};
	}
}
