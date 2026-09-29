package dev.steelaspect.cytrabackups.core.restore;

import dev.steelaspect.cytrabackups.core.CancelToken;
import dev.steelaspect.cytrabackups.core.Formatting;
import dev.steelaspect.cytrabackups.core.Json;
import dev.steelaspect.cytrabackups.core.Progress;
import dev.steelaspect.cytrabackups.core.backup.BackupMeta;
import dev.steelaspect.cytrabackups.core.backup.BackupService;
import dev.steelaspect.cytrabackups.core.backup.ChunkSelection;
import dev.steelaspect.cytrabackups.core.backup.DimensionPaths;
import dev.steelaspect.cytrabackups.core.backup.PathFilter;
import dev.steelaspect.cytrabackups.core.backup.Trigger;
import dev.steelaspect.cytrabackups.core.manifest.Manifest;
import dev.steelaspect.cytrabackups.core.region.RegionFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Applies a {@link PendingOperation} to a world that is not in use: pre-restore backup, then restore. */
public final class PendingOperationRunner {
	public static final String PENDING_FILE = "pending-restore.json";
	public static final String RESULT_FILE = "last-restore-result.json";

	private final BackupService backups;
	private final RestoreEngine restore;
	private final PathFilter filter;
	private final List<Path> excludedDirs;
	private final String levelName;
	private final int recycleKeep;
	private final Consumer<String> log;

	public PendingOperationRunner(BackupService backups, RestoreEngine restore, PathFilter filter, List<Path> excludedDirs,
								  String levelName, int recycleKeep, Consumer<String> log) {
		this.backups = backups;
		this.restore = restore;
		this.filter = filter;
		this.excludedDirs = excludedDirs;
		this.levelName = levelName;
		this.recycleKeep = recycleKeep;
		this.log = log;
	}

	public static Path pendingFile(Path storage) {
		return storage.resolve(PENDING_FILE);
	}

	public static Path resultFile(Path storage) {
		return storage.resolve(RESULT_FILE);
	}

	/**
	 * Applies the operation and returns a result; never throws for restore failures (they are reported in the
	 * result, and the world is left exactly as it was).
	 */
	public RestoreResult apply(PendingOperation op, Path world, Progress progress, CancelToken cancel) {
		RestoreResult result = new RestoreResult();
		result.operation = op.describe();
		try {
			if (restore.recoverInterrupted(log)) log.accept("Recovered from an interrupted restore before continuing");
			RestoreRecord record;
			switch (op.type) {
				case FULL_RESTORE -> {
					BackupMeta meta = backups.repository().get(op.backupId).orElseThrow(() -> new IOException("Backup #" + op.backupId + " does not exist"));
					Manifest manifest = backups.repository().loadManifest(op.backupId);
					result.preRestoreBackupId = preRestoreBackup(op, world, null, "before restoring #" + op.backupId, progress, cancel);
					RestorePlan plan = restore.planFull(manifest, op.backupId, meta.partial, world, filter, excludedDirs, progress, cancel);
					record = restore.execute(plan, world, progress, cancel);
					result.message = summary(plan);
				}
				case CHUNK_RESTORE -> {
					backups.repository().get(op.backupId).orElseThrow(() -> new IOException("Backup #" + op.backupId + " does not exist"));
					Manifest manifest = backups.repository().loadManifest(op.backupId);
					ChunkSelection sel = op.selection();
					Set<String> affected = affectedPaths(op.dimensionFolder, sel);
					result.preRestoreBackupId = preRestoreBackup(op, world, affected::contains, "area before chunk restore from #" + op.backupId, progress, cancel);
					RestorePlan plan = restore.planChunks(manifest, op.backupId, world, op.dimensionFolder, sel, progress, cancel);
					record = restore.execute(plan, world, progress, cancel);
					result.message = summary(plan);
				}
				case ROLLBACK -> {
					RestoreRecord target = (op.restoreId == null ? restore.latestUndoable() : restore.record(op.restoreId))
						.orElseThrow(() -> new IOException("Nothing to roll back"));
					result.preRestoreBackupId = preRestoreBackup(op, world, null, "before rolling back " + target.restoreId, progress, cancel);
					RestorePlan plan = restore.planRollback(target, world, progress, cancel);
					record = restore.execute(plan, world, progress, cancel);
					restore.markRolledBack(target, record.restoreId);
					result.message = summary(plan);
				}
				default -> throw new IOException("Unknown operation " + op.type);
			}
			result.restoreId = record.restoreId;
			result.success = true;
			int pruned = restore.pruneRecycleBin(recycleKeep);
			if (pruned > 0) log.accept("Pruned " + pruned + " old recycle bin(s)");
		} catch (Exception e) {
			result.success = false;
			result.message = e.getMessage() == null ? e.toString() : e.getMessage();
			log.accept("Restore failed: " + result.message);
		}
		result.finishedAt = System.currentTimeMillis();
		return result;
	}

	private Integer preRestoreBackup(PendingOperation op, Path world, Predicate<String> only, String comment, Progress progress, CancelToken cancel) throws IOException {
		if (!Files.isDirectory(world)) return null;
		BackupService.Request req = new BackupService.Request();
		req.worldDir = world;
		req.levelName = levelName;
		req.trigger = Trigger.PRE_RESTORE;
		req.comment = "Automatic backup " + comment;
		req.creator = op.requestedBy;
		req.filter = filter;
		req.excludedDirs = excludedDirs;
		req.only = only;
		req.scope = only == null ? "" : op.selection().describe() + " in " + op.dimension;
		req.restoreTarget = op.type == PendingOperation.Type.ROLLBACK ? null : op.backupId;
		req.progress = progress;
		req.cancel = cancel;
		req.warnings = w -> log.accept("[pre-restore backup] " + w);
		BackupService.Outcome out = backups.create(req);
		log.accept("Pre-restore backup #" + out.meta().id + " created (" + Formatting.bytes(out.meta().totalSize) + ")");
		return out.meta().id;
	}

	/** Region/entities/poi files and external chunk files touched by a chunk restore. */
	public static Set<String> affectedPaths(String dimensionFolder, ChunkSelection sel) {
		Set<String> out = new HashSet<>();
		for (ChunkSelection.RegionPos rp : sel.regions()) {
			for (String kind : DimensionPaths.REGION_KINDS) {
				String path = DimensionPaths.regionPath(dimensionFolder, kind, rp.x(), rp.z());
				out.add(path);
				String dir = path.substring(0, path.lastIndexOf('/'));
				for (int idx : sel.indicesIn(rp.x(), rp.z())) {
					out.add(dir + "/" + RegionFiles.externalChunkFileName(RegionFiles.chunkX(rp.x(), idx), RegionFiles.chunkZ(rp.z(), idx)));
				}
			}
		}
		return out;
	}

	private static String summary(RestorePlan plan) {
		String s = "Restored " + plan.description() + ": " + plan.placeCount() + " file(s) replaced, " + plan.removeCount() + " removed, " + plan.unchanged() + " already identical";
		if (!plan.notes().isEmpty()) s += ". " + String.join(". ", plan.notes());
		return s;
	}

	public static PendingOperation readPending(Path storage) throws IOException {
		return Json.readOrNull(pendingFile(storage), PendingOperation.class);
	}

	public static void writePending(Path storage, PendingOperation op) throws IOException {
		Json.write(pendingFile(storage), op);
	}

	public static void clearPending(Path storage) throws IOException {
		Files.deleteIfExists(pendingFile(storage));
	}

	public static void writeResult(Path storage, RestoreResult result) throws IOException {
		Json.write(resultFile(storage), result);
	}

	public static RestoreResult readResult(Path storage) throws IOException {
		return Json.readOrNull(resultFile(storage), RestoreResult.class);
	}
}
