package dev.steelaspect.cytrabackups.gametest;

import com.terraformersmc.modmenu.ModMenu;
import dev.steelaspect.cytrabackups.client.BackupScreen;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.options.OptionsScreen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Mod Menu config button, looked up through Mod Menu itself: a notice on the title screen (no server to talk
 * to), the backup menu inside a world, and both return to the screen they were opened from.
 */
public class ModMenuClientGameTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("Alloy Backups-ModMenuTest");

	@Override
	public void runTest(ClientGameTestContext ctx) {
		if (!ctx.computeOnClient(mc -> ModMenu.hasConfigScreen("cytrabackups"))) throw new AssertionError("Mod Menu has no config screen for cytrabackups");

		ctx.waitForScreen(TitleScreen.class);
		ctx.setScreen(() -> ModMenu.getConfigScreen("cytrabackups", new TitleScreen()));
		ctx.waitForScreen(AlertScreen.class);
		ctx.takeScreenshot("cytrabackups-modmenu-1-no-world");
		ctx.clickScreenButton("Back");
		ctx.waitForScreen(TitleScreen.class);
		LOG.info("Mod Menu on the title screen: notice shown, Back returns: OK");

		try (TestSingleplayerContext sp = ctx.worldBuilder().adjustSettings(s -> s.setAllowCommands(true)).create()) {
			sp.getClientWorld().waitForChunksRender();
			ctx.setScreen(() -> ModMenu.getConfigScreen("cytrabackups", new OptionsScreen(null, Minecraft.getInstance().options)));
			ctx.waitForScreen(BackupScreen.class);
			ctx.waitTicks(20);
			ctx.takeScreenshot("cytrabackups-modmenu-2-in-world");
			ctx.clickScreenButton("Done");
			ctx.waitForScreen(OptionsScreen.class);
			LOG.info("Mod Menu in a world: backup menu opens, Done returns: OK");
			ctx.setScreen(() -> null);
		}
	}
}
