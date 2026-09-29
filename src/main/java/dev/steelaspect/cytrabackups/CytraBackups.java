package dev.steelaspect.cytrabackups;

import dev.steelaspect.cytrabackups.mc.BackupCommands;
import dev.steelaspect.cytrabackups.mc.BackupManager;
import dev.steelaspect.cytrabackups.net.CytraNetworking;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Server-side entrypoint (also runs in singleplayer's integrated server). */
public final class CytraBackups implements ModInitializer {
	public static final String MOD_ID = "cytrabackups";
	public static final Logger LOGGER = LoggerFactory.getLogger("CytraBackups");

	@Override
	public void onInitialize() {
		CytraNetworking.registerPayloadTypes();
		CytraNetworking.registerServerHandlers();
		CommandRegistrationCallback.EVENT.register(BackupCommands::register);

		ServerLifecycleEvents.SERVER_STARTING.register(BackupManager::start);
		ServerLifecycleEvents.SERVER_STARTED.register(server -> ifRunning(BackupManager::onServerStarted));
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> ifRunning(BackupManager::onServerStopping));
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> ifRunning(BackupManager::onServerStopped));
		ServerTickEvents.END_SERVER_TICK.register(server -> ifRunning(BackupManager::tick));
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> ifRunning(m -> m.onPlayerJoin(handler.player)));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> ifRunning(m -> m.onPlayerLeave(handler.player)));
	}

	private static void ifRunning(java.util.function.Consumer<BackupManager> action) {
		BackupManager m = BackupManager.getOrNull();
		if (m != null) action.accept(m);
	}
}
