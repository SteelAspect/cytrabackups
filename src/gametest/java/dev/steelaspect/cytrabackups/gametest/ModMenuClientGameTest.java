package dev.steelaspect.cytrabackups.gametest;

import com.terraformersmc.modmenu.ModMenu;
import dev.steelaspect.cytrabackups.client.BackupScreen;
import dev.steelaspect.cytrabackups.client.Dialogs;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
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

	/** Clicks the Alloy UI widget labelled {@code label} on the open Backups screen or notice, with the real mouse. */
	static void click(ClientGameTestContext ctx, String label) {
		double[] p = ctx.computeOnClient(mc -> switch (mc.screen) {
			case BackupScreen s -> s.pointForTest(label);
			case Dialogs.HostScreen s -> s.pointForTest(label);
			case dev.steelaspect.cytrabackups.client.ChunkSelectorScreen s -> s.pointForTest(label);
			case null, default -> null;
		});
		if (p == null) throw new AssertionError("No \"" + label + "\" on " + ctx.computeOnClient(mc -> String.valueOf(mc.screen)));
		double scale = ctx.computeOnClient(mc -> (double) mc.getWindow().getGuiScale());
		ctx.getInput().setCursorPos(p[0] * scale, p[1] * scale);
		ctx.getInput().pressMouse(org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT);
		ctx.waitTicks(2);
	}

	@Override
	public void runTest(ClientGameTestContext ctx) {
		ctx.runOnClient(mc -> mc.options.getSoundSourceOptionInstance(net.minecraft.sounds.SoundSource.MASTER).set(0.0)); // muted test client
		if (!ctx.computeOnClient(mc -> ModMenu.hasConfigScreen("cytrabackups"))) throw new AssertionError("Mod Menu has no config screen for cytrabackups");

		ctx.waitForScreen(TitleScreen.class);
		ctx.setScreen(() -> ModMenu.getConfigScreen("cytrabackups", new TitleScreen()));
		ctx.waitForScreen(Dialogs.HostScreen.class);
		ctx.waitTicks(5);
		ctx.takeScreenshot("cytrabackups-modmenu-1-no-world");
		click(ctx, "Back");
		ctx.waitForScreen(TitleScreen.class);
		LOG.info("Mod Menu on the title screen: notice shown, Back returns: OK");

		try (TestSingleplayerContext sp = ctx.worldBuilder().adjustSettings(s -> s.setAllowCommands(true)).create()) {
			sp.getClientWorld().waitForChunksRender();
			ctx.setScreen(() -> ModMenu.getConfigScreen("cytrabackups", new OptionsScreen(null, Minecraft.getInstance().options)));
			ctx.waitForScreen(BackupScreen.class);
			ctx.waitTicks(20);
			ctx.takeScreenshot("cytrabackups-modmenu-2-in-world");
			click(ctx, "Done");
			ctx.waitForScreen(OptionsScreen.class);
			LOG.info("Mod Menu in a world: backup menu opens, Done returns: OK");
			ctx.setScreen(() -> null);
		}
	}
}
