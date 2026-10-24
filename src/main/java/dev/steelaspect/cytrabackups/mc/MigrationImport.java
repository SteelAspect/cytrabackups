package dev.steelaspect.cytrabackups.mc;

import dev.steelaspect.cytrabackups.CytraBackups;
import dev.steelaspect.cytrabackups.core.CancelToken;
import dev.steelaspect.cytrabackups.core.FileUtil;
import dev.steelaspect.cytrabackups.core.Formatting;
import dev.steelaspect.cytrabackups.core.Lang;
import dev.steelaspect.cytrabackups.core.Progress;
import dev.steelaspect.cytrabackups.core.config.CytraConfig;
import dev.steelaspect.cytrabackups.core.migrate.ServerMigration;
import dev.steelaspect.cytrabackups.core.offsite.OffsiteTarget;
import dev.steelaspect.cytrabackups.core.restore.PendingOperation;
import dev.steelaspect.cytrabackups.core.restore.RestoreResult;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Applies a queued /cbackup migrate import while the world is closed (see {@link StartupRestore}): the server folder is
 * replaced with the server backup, read straight from the old server's off-site location. Mods that were already
 * loaded keep running until the server starts again, so a successful import ends with {@link #stopForRestart()}.
 */
final class MigrationImport {
	private MigrationImport() {
	}

	/** Never throws: failures are in the result, with the server folder rolled back by the restore engine. */
	static RestoreResult apply(CytraConfig cfg, Path storage, Services s, PendingOperation op, Progress progress) {
		RestoreResult result = new RestoreResult();
		result.operation = op.describe();
		ExecutorService downloads = Executors.newCachedThreadPool(Services.threadFactory("Alloy Backups-Migrate"));
		try {
			Path dir = ServerMigration.workDir(storage, op.backupId);
			ServerMigration.Prepared p = ServerMigration.load(dir);
			Path game = ModEnv.gameDir();
			long free = FileUtil.usableSpace(game);
			long need = ServerMigration.spaceNeeded(p, cfg.minFreeSpaceMiB * 1024L * 1024L);
			if (free < need) throw new IOException(Lang.get("cytrabackups.migrate.no_space", Formatting.bytes(free), Formatting.bytes(need)));
			try (OffsiteTarget source = OffsiteTargets.create(cfg, storage, op.location)) {
				ServerMigration.Result r = ServerMigration.apply(p, source, dir, storage, game,
					ServerMigration.filter(cfg.migrate.include, cfg.migrate.exclude, op.replaceBackupSettings), s.excludedDirs(game),
					Services.compression(cfg), s.workers, downloads, OffsiteTargets.threads(cfg), cfg.trustModificationTime, progress, CancelToken.NONE);
				result.restoreId = r.record().restoreId;
				result.message = Lang.get("cytrabackups.migrate.applied", op.backupId, r.placed(), r.removed(), r.unchanged());
			}
			result.success = true;
			result.restartNeeded = true;
			FileUtil.deleteRecursively(dir);
		} catch (Exception e) {
			result.success = false;
			result.message = e.getMessage() == null ? e.toString() : e.getMessage();
		} finally {
			downloads.shutdownNow();
		}
		result.finishedAt = System.currentTimeMillis();
		return result;
	}

	/**
	 * Stops the server right after the import (or after undoing one): the mods running now are the old set. With the
	 * panel's auto-restart it comes straight back with the new ones; otherwise start it once more.
	 */
	static void stopForRestart() {
		CytraBackups.LOGGER.warn("Alloy Backups: the server folder was replaced; stopping now so the next start runs with its mods and settings. Start the server again if it doesn't restart by itself.");
		try {
			org.apache.logging.log4j.LogManager.shutdown();
		} catch (Throwable ignored) {
		}
		Runtime.getRuntime().halt(0);
	}
}
