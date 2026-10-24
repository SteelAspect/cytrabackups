package dev.steelaspect.cytrabackups.gametest;

import dev.steelaspect.cytrabackups.core.backup.BackupMeta;
import dev.steelaspect.cytrabackups.core.config.CytraConfig;
import dev.steelaspect.cytrabackups.core.manifest.Manifest;
import dev.steelaspect.cytrabackups.mc.BackupManager;
import dev.steelaspect.cytrabackups.mc.Feedback;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.gametest.framework.GameTestHelper;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import org.slf4j.LoggerFactory;

/**
 * /cbackup migrate export on a real server: refused without the off-site copy; with an off-site destination that can't
 * be reached it still makes the server backup (whole server folder, pinned) and then reports the failed upload; world
 * actions refuse a server backup. The import side is covered end to end by MigrateIntegrationTest (S3Proxy).
 */
public class MigrateGameTest {
	private static final class Messages implements Feedback {
		final List<String> lines = new CopyOnWriteArrayList<>();

		@Override
		public void send(net.minecraft.network.chat.Component message, boolean error) {
			lines.add((error ? "ERROR " : "") + message.getString());
		}

		String all() {
			return String.join(" | ", lines);
		}
	}

	private static BackupMeta byComment(BackupManager m, String comment) {
		List<BackupMeta> list = m.services().repo.list();
		for (int i = list.size() - 1; i >= 0; i--) if (list.get(i).comment.equals(comment)) return list.get(i);
		return null;
	}

	private static void offsite(BackupManager m, boolean on, Messages msgs, AtomicBoolean done) {
		CytraConfig cfg = m.config();
		cfg.offsite.enabled = on;
		cfg.offsite.type = "s3";
		cfg.offsite.s3.endpoint = "http://127.0.0.1:9"; // nothing listens here: every upload fails fast
		cfg.offsite.s3.bucket = "gametest";
		cfg.offsite.s3.accessKey = "gametest";
		cfg.offsite.s3.secretKey = "gametest";
		m.saveConfigAndReload(cfg, msgs, () -> done.set(true));
	}

	@GameTest(environment = "cytrabackups-gametest:migrate", maxTicks = 4_000_000) // unthrottled ticks while real-time uploads fail and retry
	public void exportMakesAPinnedServerBackup(GameTestHelper helper) {
		BackupManager m = BackupManager.get();
		Messages msgs = new Messages();
		AtomicBoolean start = new AtomicBoolean(), on = new AtomicBoolean(), off = new AtomicBoolean();
		String comment = "gametest-migrate-" + System.currentTimeMillis();
		BackupMeta[] made = new BackupMeta[1];
		helper.startSequence()
			.thenExecute(() -> offsite(m, false, msgs, start)) // a failed earlier run may have left it on
			.thenWaitUntil(() -> helper.assertTrue(start.get(), "waiting for the config reload: " + msgs.all()))
			.thenExecute(() -> {
				msgs.lines.clear();
				helper.assertFalse(m.migrateExport("", "gametest", msgs), "export needs the off-site copy");
				helper.assertTrue(msgs.all().contains("off-site"), "explains why: " + msgs.all());
				msgs.lines.clear();
				offsite(m, true, msgs, on);
			})
			.thenWaitUntil(() -> helper.assertTrue(on.get(), "waiting for the config reload: " + msgs.all()))
			.thenExecute(() -> helper.assertTrue(m.migrateExport(comment, "gametest", msgs), "export should start: " + msgs.all()))
			.thenWaitUntil(() -> helper.assertTrue(m.currentJob() == null && byComment(m, comment) != null, "waiting for the export: " + msgs.all()))
			.thenExecute(() -> {
				BackupMeta b = byComment(m, comment);
				made[0] = b;
				helper.assertTrue(b.serverScope() && b.pinned, "a pinned server backup: scope=" + b.scope + " pinned=" + b.pinned);
				helper.assertTrue(!b.worldFolder.isEmpty() && !b.loaderVersion.isEmpty(), "records world folder and loader: " + b.worldFolder + " " + b.loaderVersion);
				try {
					Manifest man = m.services().repo.loadManifest(b.id);
					helper.assertTrue(man.get(b.worldFolder + "/level.dat") != null, "holds the world inside the server folder");
					helper.assertTrue(man.entries().stream().noneMatch(e -> e.path().startsWith("backups/") || e.path().endsWith("session.lock")),
						"never holds the backup folder or lock files");
					LoggerFactory.getLogger("Alloy Backups-GameTest").info("Server backup #{}: {} files, e.g. {}", b.id, man.size(),
						man.entries().stream().limit(8).map(e -> e.path()).toList());
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
				helper.assertTrue(msgs.all().contains("could not be uploaded"), "reports the failed upload: " + msgs.all());
				helper.assertTrue(m.services().repo.latestFull().map(x -> !x.serverScope()).orElse(true), "never the world baseline");
				// importing from this server's own location would number new backups from #1 over the old ones: refused
				String own = dev.steelaspect.cytrabackups.mc.OffsiteTargets.location(m.config());
				helper.assertFalse(m.requestMigrateImport(own + "/", b.id, false, helper.getLevel().getServer().createCommandSourceStack()),
					"an import from this server's own off-site location is refused");
				Messages refuse = new Messages();
				helper.assertFalse(m.export(b.id, "gametest", refuse), "zip export refuses a server backup");
				helper.assertTrue(refuse.all().contains("server backup"), "explains why: " + refuse.all());
				offsite(m, false, msgs, off);
			})
			.thenWaitUntil(() -> helper.assertTrue(off.get(), "waiting for the config reload"))
			.thenExecute(() -> m.setPinned(made[0].id, false, msgs))
			.thenSucceed();
	}
}
