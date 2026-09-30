package dev.steelaspect.cytrabackups.mc;

import dev.steelaspect.cytrabackups.CytraBackups;
import dev.steelaspect.cytrabackups.core.CancelToken;
import dev.steelaspect.cytrabackups.core.Formatting;
import dev.steelaspect.cytrabackups.core.Lang;
import dev.steelaspect.cytrabackups.core.Progress;
import dev.steelaspect.cytrabackups.core.config.CytraConfig;
import dev.steelaspect.cytrabackups.core.notify.DiscordWebhook;
import dev.steelaspect.cytrabackups.core.restore.PendingOperation;
import dev.steelaspect.cytrabackups.core.restore.PendingOperationRunner;
import dev.steelaspect.cytrabackups.core.restore.RestoreResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Applies a queued restore while the world is closed: either when a world folder is opened (mixin, before
 * the world loads) or right after the server stopped ("shutdown" apply mode).
 */
public final class StartupRestore {
	private StartupRestore() {
	}

	/** Called from the LevelStorageAccess constructor (the world is locked but nothing has been read yet). */
	public static void onLevelOpened(String levelId, Path levelDir) {
		try {
			CytraConfig cfg = ModEnv.loadConfigOrDefaults();
			Path storage = ModEnv.storageFor(cfg, levelId);
			apply(cfg, storage, levelId, levelDir, "world load");
		} catch (Throwable t) {
			CytraBackups.LOGGER.error("CytraBackups: applying the queued restore failed; the world is loaded unchanged", t);
		}
	}

	/** Returns the result if an operation was applied, or null if nothing was pending. */
	public static RestoreResult apply(CytraConfig cfg, Path storage, String levelId, Path levelDir, String when) throws IOException {
		if (!Files.isDirectory(storage)) return null;
		Path journal = storage.resolve("restore-journal.log");
		PendingOperation op = PendingOperationRunner.readPending(storage);
		if (op == null && !Files.exists(journal)) return null;
		try (Services s = Services.open(cfg, storage)) {
			if (s.restore.recoverInterrupted(msg -> CytraBackups.LOGGER.warn("CytraBackups: {}", msg))) {
				CytraBackups.LOGGER.warn("CytraBackups: an interrupted restore was rolled back; the world is in its pre-restore state");
			}
			if (op == null) return null;
			Path target = levelDir.toAbsolutePath().normalize();
			if (op.worldDir != null && !Path.of(op.worldDir).toAbsolutePath().normalize().equals(target)) {
				CytraBackups.LOGGER.warn("CytraBackups: queued restore targets {} but {} was opened; leaving it queued", op.worldDir, target);
				return null;
			}
			RestoreResult result;
			if (op.attempts > 0) {
				result = new RestoreResult();
				result.operation = op.describe();
				result.success = false;
				result.message = Lang.get("cytrabackups.restore.interrupted");
				result.finishedAt = System.currentTimeMillis();
			} else {
				op.attempts++;
				PendingOperationRunner.writePending(storage, op);
				CytraBackups.LOGGER.info("CytraBackups: applying {} before {} (world {})", op.describe(), when, levelId);
				Progress progress = new Progress();
				Thread logger = progressLogger(progress);
				try {
					result = s.runner(levelId, msg -> CytraBackups.LOGGER.info("CytraBackups: {}", msg), target)
						.apply(op, target, progress, CancelToken.NONE);
				} finally {
					logger.interrupt();
				}
			}
			PendingOperationRunner.writeResult(storage, result);
			PendingOperationRunner.clearPending(storage);
			if (result.success) {
				CytraBackups.LOGGER.info("CytraBackups: {} (pre-restore backup #{})", result.message, result.preRestoreBackupId);
			} else {
				CytraBackups.LOGGER.error("CytraBackups: {} FAILED: {}. The world was left unchanged.", result.operation, result.message);
			}
			notifyDiscord(cfg, result);
			return result;
		}
	}

	private static Thread progressLogger(Progress p) {
		Thread t = new Thread(() -> {
			try {
				while (true) {
					Thread.sleep(5000);
					CytraBackups.LOGGER.info("CytraBackups: {} {} ({} / {}, {})", p.phase(), Formatting.percent(p.fraction()),
						p.done(), p.total(), Formatting.duration(System.currentTimeMillis() - p.startedAt()));
				}
			} catch (InterruptedException ignored) {
			}
		}, "CytraBackups-StartupProgress");
		t.setDaemon(true);
		t.start();
		return t;
	}

	private static void notifyDiscord(CytraConfig cfg, RestoreResult r) {
		if (!cfg.discord.enabled || !cfg.discord.onRestore || cfg.discord.webhookUrl.isBlank()) return;
		try (DiscordWebhook hook = new DiscordWebhook(cfg.discord.webhookUrl, cfg.discord.username, CytraBackups.LOGGER::warn)) {
			if (r.success) {
				hook.send(DiscordWebhook.Level.SUCCESS, Lang.get("cytrabackups.discord.restore_applied"),
					r.message + (r.preRestoreBackupId != null ? "\n" + Lang.get("cytrabackups.discord.pre_restore", r.preRestoreBackupId) : ""));
			} else {
				if (!cfg.discord.failureMention.isBlank()) hook.sendText(cfg.discord.failureMention);
				hook.send(DiscordWebhook.Level.FAILURE, Lang.get("cytrabackups.discord.restore_failed"),
					r.operation + "\n" + r.message + "\n" + Lang.get("cytrabackups.discord.unchanged"));
			}
		}
	}
}
