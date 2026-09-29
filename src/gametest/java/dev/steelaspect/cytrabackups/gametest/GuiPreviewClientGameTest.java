package dev.steelaspect.cytrabackups.gametest;

import dev.steelaspect.cytrabackups.client.BackupScreen;
import dev.steelaspect.cytrabackups.client.ChunkSelectorScreen;
import dev.steelaspect.cytrabackups.client.ConfirmDialog;
import dev.steelaspect.cytrabackups.client.GuiPreviewData;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/**
 * Renders every CytraBackups screen with sample data on the title screen (no world needed) and saves screenshots to
 * build/run/clientGameTest/screenshots, at the window size and GUI scale of a typical 1080p setup.
 */
public class GuiPreviewClientGameTest implements FabricClientGameTest {
	@Override
	public void runTest(ClientGameTestContext ctx) {
		ctx.getInput().resizeWindow(1920, 1009);
		ctx.runOnClient(mc -> {
			mc.options.guiScale().set(2);
			mc.resizeDisplay();
			GuiPreviewData.load();
		});
		shot(ctx, "BACKUPS", 11, "gui-1-backups");
		shot(ctx, "RESTORE", 11, "gui-2-restore");
		shot(ctx, "TOOLS", 11, "gui-3-tools");
		for (String section : new String[]{"schedule", "restore", "offsite"}) {
			ctx.runOnClient(mc -> {
				try {
					GuiPreviewData.settingsSection(section);
				} catch (ReflectiveOperationException e) {
					throw new RuntimeException(e);
				}
			});
			ctx.setScreen(() -> GuiPreviewData.settings(new BackupScreen()));
			ctx.waitTicks(5);
			ctx.takeScreenshot("gui-4-settings-" + section);
		}
		ctx.setScreen(() -> GuiPreviewData.form(new BackupScreen()));
		ctx.waitTicks(5);
		ctx.takeScreenshot("gui-5-form");
		ctx.setScreen(() -> new ConfirmDialog("Delete backup #11 (2026-09-29 01:42, pre-restore)?", "This cannot be undone. (Expires after 30 seconds.)",
			"Confirm", 0xFFB33A3A, yes -> {
			}));
		ctx.waitTicks(5);
		ctx.takeScreenshot("gui-6-confirm");
		ctx.setScreen(() -> new ChunkSelectorScreen(new BackupScreen(), 11));
		ctx.waitTicks(5);
		ctx.takeScreenshot("gui-7-area");
		ctx.setScreen(() -> null);
	}

	private static void shot(ClientGameTestContext ctx, String tab, int id, String name) {
		ctx.runOnClient(mc -> {
			try {
				GuiPreviewData.select(tab, id);
			} catch (ReflectiveOperationException e) {
				throw new RuntimeException(e);
			}
		});
		ctx.setScreen(BackupScreen::new);
		ctx.waitTicks(5);
		ctx.takeScreenshot(name);
	}
}
