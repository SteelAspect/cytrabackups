package dev.steelaspect.cytrabackups.mc;

import dev.steelaspect.cytrabackups.CytraBackups;
import dev.steelaspect.cytrabackups.core.Formatting;
import dev.steelaspect.cytrabackups.core.Lang;
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
		String text = Lang.get("cytrabackups.discord.backup.text", serverName, m.trigger.displayName(), Formatting.bytes(m.totalSize),
			Formatting.bytes(m.newStoredBytes), Formatting.duration(m.durationMillis));
		if (!m.comment.isBlank()) text += "\n" + Lang.get("cytrabackups.discord.comment", m.comment);
		hook.send(DiscordWebhook.Level.SUCCESS, Lang.get("cytrabackups.discord.backup", m.id), text);
	}

	void backupFailure(String what, String reason) {
		if (!on(cfg.discord.onBackupFailure)) return;
		if (!cfg.discord.failureMention.isBlank()) hook.sendText(cfg.discord.failureMention);
		hook.send(DiscordWebhook.Level.FAILURE, Lang.get("cytrabackups.discord.failed", what), Lang.get("cytrabackups.discord.world", serverName) + "\n" + reason);
	}

	void restore(String title, String description, boolean failure) {
		if (!on(cfg.discord.onRestore)) return;
		if (failure && !cfg.discord.failureMention.isBlank()) hook.sendText(cfg.discord.failureMention);
		hook.send(failure ? DiscordWebhook.Level.FAILURE : DiscordWebhook.Level.WARNING, title, Lang.get("cytrabackups.discord.world", serverName) + "\n" + description);
	}

	void lowDisk(long free, long threshold) {
		if (!on(cfg.discord.onLowDisk)) return;
		hook.send(DiscordWebhook.Level.WARNING, Lang.get("cytrabackups.discord.low_disk"), Lang.get("cytrabackups.discord.low_disk.text", Formatting.bytes(free), Formatting.bytes(threshold)));
	}

	void prune(int deleted, long freed) {
		if (!on(cfg.discord.onPrune) || deleted == 0) return;
		hook.send(DiscordWebhook.Level.INFO, Lang.get("cytrabackups.discord.pruned", deleted), Lang.get("cytrabackups.discord.freed", Formatting.bytes(freed)));
	}

	@Override
	public void close() {
		hook.close();
	}
}
