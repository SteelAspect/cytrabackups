package dev.steelaspect.cytrabackups.gametest;

import dev.steelaspect.cytrabackups.core.backup.BackupMeta;
import dev.steelaspect.cytrabackups.mc.BackupManager;
import dev.steelaspect.cytrabackups.net.CytraNetworking;
import dev.steelaspect.cytrabackups.net.GuiCommandSource;
import dev.steelaspect.cytrabackups.net.PromptPayload;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.players.NameAndId;

/**
 * The GUI runs every action as a /cbackup command through {@link CytraNetworking#runCommand}. Checks with real
 * (mock-connection) players that output reaches the GUI, that confirmations become dialog requests, that a player
 * without permission gets exactly what typing the command would give, and that other commands are refused.
 */
@SuppressWarnings("removal") // makeMockServerPlayerInLevel is the only way to get a ServerPlayer in a headless test
public class GuiChannelGameTest {
	/** Captures what the GUI would show. */
	private static final class Capture extends GuiCommandSource {
		final List<String> lines = new CopyOnWriteArrayList<>();
		volatile PromptPayload prompt;

		Capture(ServerPlayer player) {
			super(player);
		}

		@Override
		protected void mirror(Component component) {
			lines.add(component.getString());
		}

		@Override
		protected boolean prompt(PromptPayload p) {
			prompt = p;
			return true;
		}

		String text() {
			return String.join("\n", lines);
		}

		String last() {
			return lines.isEmpty() ? "" : lines.getLast();
		}
	}

	private static BackupMeta unpinned(BackupManager m) {
		return m.services().repo.list().stream().filter(b -> !b.pinned).findFirst().orElse(null);
	}

	@GameTest(maxTicks = 400_000)
	public void guiRunsBackupCommandsAsThePlayer(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		BackupManager m = BackupManager.get();
		ServerPlayer admin = helper.makeMockServerPlayerInLevel();
		// explicit level: the test server's default op level is "all players" (0)
		server.getPlayerList().op(new NameAndId(admin.getGameProfile()), Optional.of(LevelBasedPermissionSet.OWNER), Optional.empty());
		Capture adminOut = new Capture(admin);
		int[] id = {-1};

		helper.startSequence()
			.thenExecute(() -> {
				CytraNetworking.runCommand(adminOut, "cbackup help");
				helper.assertTrue(adminOut.text().contains("/cbackup create"), "command output should reach the GUI: " + adminOut.text());
				CytraNetworking.runCommand(adminOut, "op someone");
				helper.assertTrue(adminOut.last().contains("only run /cbackup"), "other commands must be refused: " + adminOut.last());
			})
			.thenWaitUntil(() -> helper.assertTrue(unpinned(m) != null, "waiting for another test to create a backup"))
			.thenExecute(() -> {
				BackupMeta b = unpinned(m);
				id[0] = b.id;
				CytraNetworking.runCommand(adminOut, "cbackup delete " + b.id);
				PromptPayload p = adminOut.prompt;
				helper.assertTrue(p != null && p.title().contains("Delete backup #" + b.id), "delete should ask through a GUI dialog, got " + p
					+ "; output: " + adminOut.text());
				CytraNetworking.runCommand(adminOut, "cbackup deny " + p.token());
				helper.assertTrue(adminOut.last().contains("Cancelled"), "denying the dialog cancels: " + adminOut.last());
				helper.assertTrue(m.services().repo.get(b.id).isPresent(), "a denied delete keeps the backup");
			})
			.thenExecute(() -> {
				// the same player without op: the GUI gets exactly what typing the command would give
				server.getPlayerList().deop(new NameAndId(admin.getGameProfile()));
				Capture guestOut = new Capture(admin);
				CytraNetworking.runCommand(guestOut, "cbackup delete " + id[0]);
				helper.assertTrue(guestOut.prompt == null, "no confirmation for a player without permission");
				helper.assertTrue(!guestOut.lines.isEmpty() && !guestOut.text().contains("Delete backup"), "refused like the command: " + guestOut.text());
				helper.assertTrue(m.services().repo.get(id[0]).isPresent(), "the backup is still there");
				org.slf4j.LoggerFactory.getLogger("CytraBackups-GameTest").info("GUI channel: admin output {} lines, prompt ok; non-op got: {}",
					adminOut.lines.size(), guestOut.last());
				server.getPlayerList().remove(admin);
			})
			.thenSucceed();
	}
}
