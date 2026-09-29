package dev.steelaspect.cytrabackups.gametest;

import dev.steelaspect.cytrabackups.client.BackupScreen;
import dev.steelaspect.cytrabackups.client.ChunkSelectorScreen;
import dev.steelaspect.cytrabackups.core.backup.BackupMeta;
import dev.steelaspect.cytrabackups.core.restore.PendingOperation;
import dev.steelaspect.cytrabackups.core.restore.PendingOperationRunner;
import dev.steelaspect.cytrabackups.core.restore.RestoreResult;
import dev.steelaspect.cytrabackups.mc.BackupManager;
import java.util.List;
import java.util.function.Function;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Real client (run headless under Xvfb): singleplayer world + integrated server, the optional GUI driven with mouse
 * and keyboard, and a singleplayer restore applied by the world-open mixin when the save is reopened.
 */
public class CytraBackupsClientGameTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("CytraBackups-ClientTest");

	private static <T> T onServer(TestServerContext server, Function<MinecraftServer, T> f) {
		return server.computeOnServer(f::apply);
	}

	private static List<BackupMeta> backups(TestServerContext server) {
		return onServer(server, s -> BackupManager.get().services().repo.list());
	}

	private static void waitUntil(ClientGameTestContext ctx, String what, java.util.function.BooleanSupplier condition) {
		for (int i = 0; i < 1200; i++) {
			if (condition.getAsBoolean()) return;
			ctx.waitTicks(5);
		}
		throw new AssertionError("Timed out waiting for: " + what);
	}

	private static void waitForIdle(ClientGameTestContext ctx, TestServerContext server, int backupCount) {
		waitUntil(ctx, backupCount + " backups", () -> backups(server).size() >= backupCount
			&& onServer(server, s -> BackupManager.get().currentJob() == null));
	}

	/** GUI coordinates -> window pixels. */
	private static void cursor(ClientGameTestContext ctx, double guiX, double guiY) {
		double scale = ctx.computeOnClient(mc -> (double) mc.getWindow().getGuiScale());
		ctx.getInput().setCursorPos(guiX * scale, guiY * scale);
	}

	private static void clickRow(ClientGameTestContext ctx, int row) {
		cursor(ctx, 60, 34 + row * 12 + 6);
		ctx.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
		ctx.waitTicks(2);
	}

	@Override
	public void runTest(ClientGameTestContext ctx) {
		BlockPos marker;
		int beforeId;
		TestWorldSave save;
		// Headless CI renders with software OpenGL; keep the render load low so the integrated server gets CPU time.
		ctx.runOnClient(mc -> {
			mc.options.framerateLimit().set(10);
			mc.options.enableVsync().set(false);
			mc.options.renderDistance().set(2);
		});
		// "Allow Commands" makes the singleplayer host an operator, which /backup needs (as for any op command)
		try (TestSingleplayerContext sp = ctx.worldBuilder().adjustSettings(s -> s.setAllowCommands(true)).create()) {
			sp.getClientWorld().waitForChunksRender();
			TestServerContext server = sp.getServer();

			server.runCommand("backup create singleplayer-before");
			waitForIdle(ctx, server, 1);
			beforeId = backups(server).get(0).id;
			marker = onServer(server, s -> {
				ServerPlayer p = s.getPlayerList().getPlayers().get(0);
				BlockPos pos = p.blockPosition().offset(3, 0, 3);
				s.overworld().setBlockAndUpdate(pos, Blocks.GOLD_BLOCK.defaultBlockState());
				return pos;
			});

			// open the GUI through the client command, exactly as a player would type it
			ctx.setScreen(() -> new ChatScreen("/backupgui", false));
			ctx.getInput().pressKey(GLFW.GLFW_KEY_ENTER);
			ctx.waitForScreen(BackupScreen.class);
			ctx.waitTicks(30);
			ctx.takeScreenshot("cytrabackups-1-backup-list");

			ctx.clickScreenButton("Create backup");
			waitForIdle(ctx, server, 2);
			ctx.waitTicks(60); // list refresh
			ctx.takeScreenshot("cytrabackups-2-after-create");
			LOG.info("GUI create backup: OK ({} backups)", backups(server).size());

			clickRow(ctx, 1); // newest first: row 1 is "singleplayer-before"
			ctx.clickScreenButton("Pin");
			waitUntil(ctx, "backup pinned", () -> onServer(server, s -> BackupManager.get().services().repo.get(beforeId).orElseThrow().pinned));
			LOG.info("GUI pin: OK");

			clickRow(ctx, 1);
			ctx.clickScreenButton("Area...");
			ctx.waitForScreen(ChunkSelectorScreen.class);
			ctx.waitTicks(10);
			int w = ctx.computeOnClient(mc -> mc.getWindow().getGuiScaledWidth());
			int h = ctx.computeOnClient(mc -> mc.getWindow().getGuiScaledHeight());
			int cx = 10 + ((w - 20) / 8 / 2) * 8 + 4, cy = 24 + ((h - 118) / 8 / 2) * 8 + 4;
			cursor(ctx, cx - 8, cy - 8);
			ctx.getInput().holdMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
			ctx.waitTick();
			cursor(ctx, cx + 8, cy + 8);
			ctx.waitTick();
			ctx.getInput().releaseMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
			ctx.waitTicks(2);
			ctx.takeScreenshot("cytrabackups-3-chunk-selector");
			ctx.clickScreenButton("Restore selection...");
			ctx.waitForScreen(ConfirmScreen.class); // the server's confirmation, shown as a dialog
			ctx.takeScreenshot("cytrabackups-4-confirm-chunks");
			ctx.clickScreenButton("Confirm");
			waitUntil(ctx, "chunk restore queued", () -> onServer(server, s -> {
				try {
					PendingOperation op = PendingOperationRunner.readPending(BackupManager.get().services().storage);
					return op != null && op.type == PendingOperation.Type.CHUNK_RESTORE && op.boxes.get(0).chunkCount() == 9;
				} catch (java.io.IOException e) {
					return false;
				}
			}));
			ctx.waitTicks(20);
			ctx.takeScreenshot("cytrabackups-5-chunk-restore-queued");
			LOG.info("GUI chunk selector restore: queued (player is standing in the area)");
			server.runCommand("backup pending cancel");
			ctx.clickScreenButton("Back"); // the dialog returned to the chunk selector
			ctx.waitForScreen(BackupScreen.class);

			clickRow(ctx, 1);
			ctx.clickScreenButton("Restore...");
			ctx.waitForScreen(ConfirmScreen.class);
			ctx.clickScreenButton("Confirm");
			waitUntil(ctx, "restore countdown", () -> onServer(server, s -> BackupManager.get().countdownLabel() != null));
			ctx.waitTicks(20);
			ctx.takeScreenshot("cytrabackups-6-restore-countdown");
			server.runCommand("backup cancel");
			waitUntil(ctx, "countdown cancelled", () -> onServer(server, s -> BackupManager.get().countdownLabel() == null));
			LOG.info("GUI full restore: countdown started and cancelled");

			// queue a full restore of the earlier backup and leave the world; it must be applied when the save is reopened
			int target = beforeId;
			server.runOnServer(s -> {
				PendingOperation op = new PendingOperation();
				op.type = PendingOperation.Type.FULL_RESTORE;
				op.backupId = target;
				op.worldDir = BackupManager.get().worldDir().toString();
				op.requestedBy = "client-gametest";
				op.requestedAt = System.currentTimeMillis();
				try {
					PendingOperationRunner.writePending(BackupManager.get().services().storage, op);
				} catch (java.io.IOException e) {
					throw new java.io.UncheckedIOException(e);
				}
			});
			ctx.setScreen(() -> null);
			save = sp.getWorldSave();
		}

		try (TestSingleplayerContext sp = save.open()) {
			sp.getClientWorld().waitForChunksRender();
			TestServerContext server = sp.getServer();
			Block b = onServer(server, s -> s.overworld().getBlockState(marker).getBlock());
			if (b == Blocks.GOLD_BLOCK) throw new AssertionError("singleplayer restore was not applied: gold block still at " + marker);
			RestoreResult r = onServer(server, s -> {
				try {
					return PendingOperationRunner.readResult(BackupManager.get().services().storage);
				} catch (java.io.IOException e) {
					throw new RuntimeException(e);
				}
			});
			if (r == null || !r.success) throw new AssertionError("restore result missing or failed: " + (r == null ? null : r.message));
			ctx.takeScreenshot("cytrabackups-7-singleplayer-restored");
			LOG.info("Singleplayer restore applied on world open: {} (pre-restore backup #{}); block at {} is now {}", r.message, r.preRestoreBackupId, marker, b);
		}
	}
}
