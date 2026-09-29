package dev.steelaspect.cytrabackups.mc;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * "save-all flush" + "save-off" for the duration of a copy, and the reverse afterwards. Only levels that had
 * saving enabled before are re-enabled, so an admin's own /save-off is respected.
 */
public final class SaveControl {
	private SaveControl() {
	}

	/** Runs on the server thread: saves everything (optionally flushing chunk IO), then disables autosave. */
	public static CompletableFuture<Map<ServerLevel, Boolean>> saveAndDisable(MinecraftServer server, boolean flush) {
		return server.submit(() -> {
			server.saveEverything(true, flush, true);
			Map<ServerLevel, Boolean> previous = new IdentityHashMap<>();
			for (ServerLevel level : server.getAllLevels()) {
				previous.put(level, level.noSave);
				level.noSave = true;
			}
			return previous;
		});
	}

	/** Re-enables saving without blocking (safe even while the server is shutting down). */
	public static void restore(MinecraftServer server, Map<ServerLevel, Boolean> previous) {
		if (previous == null) return;
		Runnable r = () -> previous.forEach((level, wasNoSave) -> level.noSave = wasNoSave);
		if (server.isSameThread()) r.run();
		else server.execute(r);
	}
}
