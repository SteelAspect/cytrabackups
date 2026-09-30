package dev.steelaspect.cytrabackups.gametest;

import dev.steelaspect.cytrabackups.client.BackupScreen;
import dev.steelaspect.cytrabackups.client.ChunkSelectorScreen;
import dev.steelaspect.cytrabackups.client.GuiPreviewData;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Renders every CytraBackups screen with sample data on the title screen (no world needed) and saves screenshots to
 * build/run/clientGameTest/screenshots, for a typical 1080p setup and for the smallest scaled sizes (GUI scales 1-4).
 */
public class GuiPreviewClientGameTest implements FabricClientGameTest {
	private record Setup(String name, int width, int height, int scale) {
	}

	private static final Setup[] SETUPS = {
		new Setup("1080p-s2", 1920, 1009, 2),
		new Setup("480p-s2", 854, 480, 2),
		new Setup("720p-s3", 1280, 720, 3),
		new Setup("1080p-s4", 1920, 1080, 4),
		new Setup("480p-s1", 854, 480, 1),
	};

	@Override
	public void runTest(ClientGameTestContext ctx) {
		ctx.runOnClient(mc -> GuiPreviewData.load());
		for (Setup setup : SETUPS) {
			ctx.getInput().resizeWindow(setup.width(), setup.height());
			ctx.runOnClient(mc -> {
				mc.options.guiScale().set(setup.scale());
				mc.resizeDisplay();
			});
			String suffix = "-" + setup.name();
			shot(ctx, 0, 11, "gui-1-backups" + suffix);
			shot(ctx, 1, 11, "gui-2-restore" + suffix);
			shot(ctx, 2, 11, "gui-3-tools" + suffix);
			for (String section : new String[]{"schedule", "offsite"}) {
				ctx.runOnClient(mc -> GuiPreviewData.settingsSection(section));
				ctx.setScreen(() -> GuiPreviewData.settings(new BackupScreen()));
				ctx.waitTicks(5);
				ctx.takeScreenshot("gui-4-settings-" + section + suffix);
			}
			ctx.setScreen(() -> GuiPreviewData.form(new BackupScreen()));
			ctx.waitTicks(5);
			ctx.takeScreenshot("gui-5-form" + suffix);
			ctx.setScreen(() -> new ConfirmScreen(yes -> {
			}, Component.literal("Delete backup #11 (2026-09-29 01:42, pre-restore)?"), Component.literal("This can't be undone. Expires in 30s."),
				Component.translatable("cytrabackups.gui.confirm"), CommonComponents.GUI_CANCEL));
			ctx.waitTicks(5);
			ctx.takeScreenshot("gui-6-confirm" + suffix);
			ctx.setScreen(() -> new ChunkSelectorScreen(new BackupScreen(), 11));
			ctx.waitTicks(5);
			ctx.takeScreenshot("gui-7-area" + suffix);
		}
		ctx.setScreen(() -> null);
	}

	private static void shot(ClientGameTestContext ctx, int tab, int id, String name) {
		ctx.setScreen(() -> null); // an open BackupScreen stores its tab when it is replaced
		ctx.runOnClient(mc -> GuiPreviewData.select(tab, id));
		ctx.setScreen(BackupScreen::new);
		ctx.waitTicks(5);
		ctx.takeScreenshot(name);
	}
}
