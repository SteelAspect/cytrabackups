package dev.steelaspect.cytrabackups.client;

import dev.steelaspect.cytrabackups.net.BackupListPayload;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 1.20.4 stand-in for the client game tests (fabric-client-gametest-api-v1 starts at 1.21.4).
 * Run with {@code ./headless.sh ./smoketest.sh}: a dedicated 1.20.4 server runs the built jar, this client checks the
 * Mod Menu notice on the title screen, joins (as op), opens the menu through {@code /cbackupgui}, creates a backup with
 * the GUI button, changes a far-away chunk, restores it live from the backup through the GUI's confirmation dialog and
 * checks the block came back, starts and cancels a full-restore countdown, opens the area selector (drag + Preview) and
 * the settings screen. Clicks go through the vanilla 1.20.4 input methods. Logs {@code [BackupsSmoke] PASS} or
 * {@code FAIL: ...}, saves screenshots to build/run/smoke/screenshots and quits.
 */
public class BackupsSmokeTest implements ClientModInitializer {
	private static final Logger LOG = LoggerFactory.getLogger("BackupsSmoke");
	private static final int TIMEOUT_TICKS = 20 * 120;
	/** Far from spawn (more than the live-restore holder range), in chunk 1000,1000. */
	private static final BlockPos P = new BlockPos(16005, 100, 16007);
	private static final String AT = P.getX() + " " + P.getY() + " " + P.getZ();
	private static final String FORCE = (P.getX() >> 4 << 4) + " " + (P.getZ() >> 4 << 4);

	private final List<String> failures = new ArrayList<>();
	private int step;
	private int wait = 40;
	private BooleanSupplier until;
	private int untilTicks;
	private String untilWhat;
	private boolean done;
	private int backupA = -1;
	private int logMark;
	private final java.util.ArrayDeque<String> commands = new java.util.ArrayDeque<>();
	private int commandWait;

	@Override
	public void onInitializeClient() {
		if (!Boolean.getBoolean("cytrabackups.smoke")) return;
		ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
	}

	private void onTick(Minecraft mc) {
		if (done) return;
		if (!commands.isEmpty()) {
			if (--commandWait <= 0 && mc.player != null) {
				mc.player.connection.sendCommand(commands.poll());
				commandWait = 10; // lets /forceload load the chunk before the next command
			}
			return;
		}
		if (until != null) {
			if (until.getAsBoolean()) {
				until = null;
			} else if (++untilTicks > TIMEOUT_TICKS) {
				fail("timed out waiting for " + untilWhat + "; last GUI line: " + lastLog());
				finish(mc);
				return;
			} else {
				return;
			}
		}
		if (wait > 0) {
			wait--;
			return;
		}
		try {
			run(mc);
		} catch (Throwable t) {
			LOG.error("[BackupsSmoke] step {} threw", step - 1, t);
			fail("step " + (step - 1) + " threw " + t);
			finish(mc);
		}
	}

	private void run(Minecraft mc) {
		switch (step++) {
			case 0 -> waitFor("title screen", () -> mc.screen instanceof TitleScreen);
			case 1 -> {
				mc.setScreen(CytraBackupsClient.screenFor(new TitleScreen())); // what Mod Menu opens
				wait = 10;
			}
			case 2 -> {
				expect(mc, Dialogs.HostScreen.class);
				shot(mc, "modmenu-no-world");
				mc.screen.keyPressed(256, 0, 0); // Esc through the 1.20.4 key method closes the notice
				wait = 10;
			}
			case 3 -> {
				expect(mc, TitleScreen.class);
				String address = System.getProperty("cytrabackups.smoke.server", "localhost:25574");
				LOG.info("[BackupsSmoke] joining {}", address);
				ConnectScreen.startConnecting(new TitleScreen(), mc, ServerAddress.parseString(address),
					new ServerData("smoke", address, ServerData.Type.OTHER), false);
				waitFor("joining the server", () -> mc.player != null && mc.level != null && mc.screen == null);
			}
			case 4 -> waitFor("the Alloy Backups handshake and op", () -> ClientState.serverSupported() && mc.player.hasPermissions(4));
			case 5 -> {
				// a far-away chunk with a stone block, saved before the backup
				command(mc, "forceload add " + FORCE);
				command(mc, "setblock " + AT + " stone");
				command(mc, "forceload remove " + FORCE);
				command(mc, "cbackupgui"); // client command, as a player types it
				waitFor("the backup menu with its list", () -> mc.screen instanceof BackupScreen && ClientState.list != null);
			}
			case 6 -> {
				shot(mc, "backup-list");
				int before = entries().size();
				click(mc, "Create backup");
				waitFor("the GUI backup", () -> entries().size() > before && idle());
			}
			case 7 -> {
				backupA = entries().get(0).id();
				LOG.info("[BackupsSmoke] GUI created backup #{}", backupA);
				shot(mc, "after-create");
				command(mc, "forceload add " + FORCE);
				command(mc, "setblock " + AT + " gold_block");
				command(mc, "forceload remove " + FORCE);
				wait = 40;
			}
			case 8 -> {
				logMark = ClientState.log.size();
				int cx = P.getX() >> 4, cz = P.getZ() >> 4;
				ClientState.run("restore " + backupA + " chunks minecraft:overworld " + cx + " " + cz + " " + cx + " " + cz);
				waitFor("the restore confirmation dialog", () -> ((BackupScreen) mc.screen).pointForTest("Confirm") != null);
			}
			case 9 -> {
				shot(mc, "confirm-chunk-restore");
				click(mc, "Confirm");
				waitFor("the live chunk restore", () -> logSince(logMark).contains("Restored 1 chunk"));
			}
			case 10 -> {
				LOG.info("[BackupsSmoke] live restore: {}", lastLog());
				command(mc, "tp @s " + P.getX() + " " + (P.getY() + 1) + " " + P.getZ());
				waitFor("the restored chunk on the client", () -> mc.level.hasChunk(P.getX() >> 4, P.getZ() >> 4)
					&& !mc.level.getBlockState(P).isAir());
			}
			case 11 -> {
				check(mc.level.getBlockState(P).is(Blocks.STONE), "the restored chunk should have stone back, got " + mc.level.getBlockState(P));
				command(mc, "tp @s 0 -59 0");
				// full restore from the GUI: row, Restore..., Confirm starts the countdown, then cancel it
				click(mc, "#" + backupA);
				click(mc, "Restore...");
				waitFor("the full-restore confirmation", () -> ((BackupScreen) mc.screen).pointForTest("Confirm") != null);
			}
			case 12 -> {
				click(mc, "Confirm");
				waitFor("the restore countdown", () -> ClientState.list != null && ClientState.list.status().contains("countdown"));
			}
			case 13 -> {
				shot(mc, "restore-countdown");
				ClientState.run("cancel");
				waitFor("the countdown to stop", () -> ClientState.list != null && !ClientState.list.status().contains("countdown"));
			}
			case 14 -> {
				mc.setScreen(new ChunkSelectorScreen(mc.screen, backupA));
				wait = 20;
			}
			case 15 -> {
				ChunkSelectorScreen s = expect(mc, ChunkSelectorScreen.class);
				double[] c = s.mapCenterForTest();
				int cell = s.cellForTest();
				s.mouseClicked(c[0] - cell, c[1] - cell, 0);
				s.mouseDragged(c[0] + cell, c[1] + cell, 0, 2 * cell, 2 * cell);
				s.mouseReleased(c[0] + cell, c[1] + cell, 0);
				wait = 2;
			}
			case 16 -> {
				shot(mc, "chunk-selector");
				logMark = ClientState.log.size();
				click(mc, "Preview");
				waitFor("the preview reply", () -> logSince(logMark).contains("preview ("));
			}
			case 17 -> {
				LOG.info("[BackupsSmoke] preview: {}", lastLog());
				click(mc, "Back");
				wait = 10;
			}
			case 18 -> {
				expect(mc, BackupScreen.class);
				click(mc, "Settings...");
				waitFor("the settings", () -> mc.screen instanceof SettingsScreen && ClientState.config != null);
			}
			case 19 -> {
				wait = 10;
			}
			case 20 -> {
				shot(mc, "settings");
				mc.setScreen(null);
				wait = 5;
			}
			default -> finish(mc);
		}
	}

	private static List<BackupListPayload.Entry> entries() {
		return ClientState.list == null ? List.of() : ClientState.list.entries();
	}

	private static boolean idle() {
		return ClientState.list != null && ClientState.list.jobPhase().isEmpty();
	}

	private static String lastLog() {
		ClientState.LogLine l = ClientState.lastLog();
		return l == null ? "" : l.text().getString();
	}

	private static String logSince(int mark) {
		StringBuilder b = new StringBuilder();
		for (int i = Math.min(mark, ClientState.log.size()); i < ClientState.log.size(); i++) b.append(ClientState.log.get(i).text().getString()).append('\n');
		return b.toString();
	}

	/** Queued: sent one every 10 ticks, before the next step runs. */
	private void command(Minecraft mc, String command) {
		commands.add(command);
	}

	/** Clicks the widget labelled {@code label} through the 1.20.4 vanilla mouse methods. */
	private void click(Minecraft mc, String label) {
		Screen s = mc.screen;
		double[] p = s instanceof BackupScreen b ? b.pointForTest(label) : s instanceof ChunkSelectorScreen c ? c.pointForTest(label) : null;
		if (p == null) {
			fail("no \"" + label + "\" on " + s);
			return;
		}
		s.mouseClicked(p[0], p[1], 0);
		s.mouseReleased(p[0], p[1], 0);
	}

	private void waitFor(String what, BooleanSupplier condition) {
		until = condition;
		untilTicks = 0;
		untilWhat = what;
	}

	private <T extends Screen> T expect(Minecraft mc, Class<T> type) {
		check(type.isInstance(mc.screen), type.getSimpleName() + " did not open: " + mc.screen);
		return type.isInstance(mc.screen) ? type.cast(mc.screen) : null;
	}

	private void check(boolean ok, String message) {
		if (!ok) fail(message);
	}

	private void fail(String message) {
		LOG.error("[BackupsSmoke] check failed: {}", message);
		failures.add(message);
	}

	private static void shot(Minecraft mc, String name) {
		Screenshot.grab(mc.gameDirectory, name + ".png", mc.getMainRenderTarget(), t -> LOG.info("[BackupsSmoke] {}", t.getString()));
	}

	private void finish(Minecraft mc) {
		done = true;
		if (failures.isEmpty()) {
			LOG.info("[BackupsSmoke] PASS");
		} else {
			LOG.error("[BackupsSmoke] FAIL: {}", failures);
		}
		if (mc.level != null) mc.level.disconnect();
		mc.disconnect();
		mc.stop();
	}
}
