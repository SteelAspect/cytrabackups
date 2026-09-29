package dev.steelaspect.cytrabackups.mc;

import dev.steelaspect.cytrabackups.CytraBackups;
import dev.steelaspect.cytrabackups.core.Formatting;
import dev.steelaspect.cytrabackups.core.backup.BackupMeta;
import dev.steelaspect.cytrabackups.core.config.CytraConfig;
import dev.steelaspect.cytrabackups.core.notify.DiscordWebhook;

/** Maps backup events to Discord webhook posts according to the config toggles. */
final class Notifier implements AutoCloseable {
	private final DiscordWebhook hook = new DiscordWebhook("", "CytraBackups", msg -> CytraBackups.LOGGER.warn("CytraBackups: {}", msg));
	private volatile CytraConfig cfg;
	private final String serverName;

	Notifier(CytraConfig cfg, String serverName) {
		this.serverName = serverName;
		configure(cfg);
	}

	void configure(CytraConfig cfg) {
		this.cfg = cfg;
		hook.configure(cfg.discord.enabled ? cfg.discord.webhookUrl : "", cfg.discord.username);
	}

	private boolean on(boolean toggle) {
		return cfg.discord.enabled && toggle && !cfg.discord.webhookUrl.isBlank();
	}

	void backupSuccess(BackupMeta m) {
		if (!on(cfg.discord.onBackupSuccess)) return;
		hook.send(DiscordWebhook.Level.SUCCESS, "Backup #" + m.id + " created",
			"**World:** " + serverName + "\n**Trigger:** " + m.trigger.displayName() + (m.comment.isBlank() ? "" : "\n**Comment:** " + m.comment)
				+ "\n**Size:** " + Formatting.bytes(m.totalSize) + " (" + m.fileCount + " files, " + m.chunkCount + " chunks)"
				+ "\n**New data stored:** " + Formatting.bytes(m.newStoredBytes) + " — dedup saved " + Formatting.bytes(m.dedupSavedBytes())
				+ "\n**Took:** " + Formatting.duration(m.durationMillis));
	}

	void backupFailure(String what, String reason) {
		if (!on(cfg.discord.onBackupFailure)) return;
		if (!cfg.discord.failureMention.isBlank()) hook.sendText(cfg.discord.failureMention);
		hook.send(DiscordWebhook.Level.FAILURE, what + " failed", "**World:** " + serverName + "\n" + reason);
	}

	void restore(String title, String description, boolean failure) {
		if (!on(cfg.discord.onRestore)) return;
		if (failure && !cfg.discord.failureMention.isBlank()) hook.sendText(cfg.discord.failureMention);
		hook.send(failure ? DiscordWebhook.Level.FAILURE : DiscordWebhook.Level.WARNING, title, "**World:** " + serverName + "\n" + description);
	}

	void lowDisk(long free, long threshold) {
		if (!on(cfg.discord.onLowDisk)) return;
		hook.send(DiscordWebhook.Level.WARNING, "Low disk space", "Only " + Formatting.bytes(free) + " free on the backup disk (warning below "
			+ Formatting.bytes(threshold) + ").");
	}

	void prune(int deleted, long freed) {
		if (!on(cfg.discord.onPrune) || deleted == 0) return;
		hook.send(DiscordWebhook.Level.INFO, "Pruned " + deleted + " backup(s)", "Freed " + Formatting.bytes(freed));
	}

	@Override
	public void close() {
		hook.close();
	}
}
