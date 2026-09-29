package dev.steelaspect.cytrabackups.mc;

import dev.steelaspect.cytrabackups.CytraBackups;
import dev.steelaspect.cytrabackups.core.config.ConfigIO;
import dev.steelaspect.cytrabackups.core.config.CytraConfig;
import java.io.IOException;
import java.nio.file.Path;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;

/** Environment helpers: config/storage locations and version strings. */
public final class ModEnv {
	private ModEnv() {
	}

	public static Path configFile() {
		return FabricLoader.getInstance().getConfigDir().resolve("cytrabackups.json");
	}

	public static Path gameDir() {
		return FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
	}

	public static boolean isDedicated() {
		return FabricLoader.getInstance().getEnvironmentType() == EnvType.SERVER;
	}

	/** Loads the config, falling back to defaults (with a log line) if it is invalid. */
	public static CytraConfig loadConfigOrDefaults() {
		try {
			return ConfigIO.load(configFile());
		} catch (IOException e) {
			CytraBackups.LOGGER.error("Could not load {}: {} - using defaults", configFile(), e.getMessage());
			return new CytraConfig();
		}
	}

	/**
	 * Backup storage for a world. Dedicated servers use the configured folder directly; singleplayer adds a
	 * subfolder per world so different saves never share a repository.
	 */
	public static Path storageFor(CytraConfig cfg, String levelId) {
		Path base = Path.of(cfg.storagePath);
		if (!base.isAbsolute()) base = gameDir().resolve(base);
		base = base.normalize();
		return isDedicated() ? base : base.resolve(levelId.replaceAll("[^A-Za-z0-9._ -]", "_"));
	}

	public static String minecraftVersion() {
		try {
			return SharedConstants.getCurrentVersion().name();
		} catch (Throwable t) {
			return "1.21.11";
		}
	}

	public static String modVersion() {
		return FabricLoader.getInstance().getModContainer(CytraBackups.MOD_ID)
			.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("dev");
	}
}
