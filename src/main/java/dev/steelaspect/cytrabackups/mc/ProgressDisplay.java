package dev.steelaspect.cytrabackups.mc;

import dev.steelaspect.cytrabackups.core.Formatting;
import dev.steelaspect.cytrabackups.core.Lang;
import dev.steelaspect.cytrabackups.core.Progress;
import dev.steelaspect.cytrabackups.core.config.CytraConfig;
import java.util.ArrayList;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;

/** Boss bar / action bar progress for the running job. Server thread only. */
final class ProgressDisplay {
	private ServerBossEvent bar;

	static String line(BackupManager.Job job) {
		Progress p = job.progress();
		String progress = "";
		if (p.bytesTotal() > 0) progress = Lang.get("cytrabackups.progress.bytes", Formatting.percent(p.fraction()), Formatting.bytes(p.bytesDone()), Formatting.bytes(p.bytesTotal()));
		else if (p.total() > 0) progress = Lang.get("cytrabackups.progress.count", Formatting.percent(p.fraction()), p.done(), p.total());
		return Lang.get(progress.isEmpty() ? "cytrabackups.progress.line" : "cytrabackups.progress.line_with", job.displayName(), p.phase(), progress);
	}

	private static boolean allowed(ServerPlayer player, CytraConfig cfg) {
		return switch (cfg.progress.showTo) {
			case "everyone" -> true;
			case "nobody" -> false;
			default -> Perms.check(player, Perms.PROGRESS);
		};
	}

	void update(MinecraftServer server, CytraConfig cfg, BackupManager.Job job) {
		if (job == null || job.quiet()) {
			clear();
			return;
		}
		String text = line(job);
		if (cfg.progress.bossBar) {
			if (bar == null) bar = new ServerBossEvent(Component.literal(text), BossEvent.BossBarColor.GREEN, BossEvent.BossBarOverlay.PROGRESS);
			bar.setName(Component.literal(text));
			bar.setProgress(Math.max(0f, Math.min(1f, job.progress().fraction())));
			for (ServerPlayer p : server.getPlayerList().getPlayers()) {
				boolean ok = allowed(p, cfg);
				boolean has = bar.getPlayers().contains(p);
				if (ok && !has) bar.addPlayer(p);
				else if (!ok && has) bar.removePlayer(p);
			}
		} else if (bar != null) {
			clear();
		}
		if (cfg.progress.actionBar) {
			for (ServerPlayer p : server.getPlayerList().getPlayers()) {
				if (allowed(p, cfg)) p.sendSystemMessage(Component.literal(text), true);
			}
		}
	}

	void clear() {
		if (bar != null) {
			for (ServerPlayer p : new ArrayList<>(bar.getPlayers())) bar.removePlayer(p);
			bar.setVisible(false);
			bar = null;
		}
	}
}
