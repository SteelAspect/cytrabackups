package dev.steelaspect.cytrabackups.core.restore;

import dev.steelaspect.cytrabackups.core.backup.ChunkSelection;
import java.util.ArrayList;
import java.util.List;

/**
 * An operation queued for the next time the world is opened ({@code pending-restore.json}). Applied by a mixin
 * before Minecraft reads anything from the world folder.
 */
public final class PendingOperation {
	public enum Type {
		FULL_RESTORE, CHUNK_RESTORE, ROLLBACK
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
	/** Number of failed attempts; the operation is dropped after one failure to avoid restart loops. */
	public int attempts;

	public ChunkSelection selection() {
		return new ChunkSelection(boxes);
	}

	public String describe() {
		return switch (type) {
			case FULL_RESTORE -> "full restore of backup #" + backupId;
			case CHUNK_RESTORE -> "chunk restore from backup #" + backupId + " (" + selection().describe() + " in " + dimension + ")";
			case ROLLBACK -> restoreId == null ? "rollback of the last restore" : "rollback of restore " + restoreId;
		};
	}
}
