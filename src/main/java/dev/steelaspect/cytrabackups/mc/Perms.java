package dev.steelaspect.cytrabackups.mc;

import dev.steelaspect.cytrabackups.core.config.CytraConfig;
import java.util.function.Predicate;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;

/** Permission checks via fabric-permissions-api (LuckPerms etc.) with op-level fallback from the config. */
public final class Perms {
	public static final String LIST = "list";
	public static final String CREATE = "create";
	public static final String COMMENT = "comment";
	public static final String VERIFY = "verify";
	public static final String PROGRESS = "progress";
	public static final String PIN = "pin";
	public static final String CANCEL = "cancel";
	public static final String PRUNE = "prune";
	public static final String EXPORT = "export";
	public static final String DELETE = "delete";
	public static final String RESTORE = "restore";
	public static final String ADMIN = "admin";

	private Perms() {
	}

	private static int level(String node) {
		BackupManager m = BackupManager.getOrNull();
		CytraConfig cfg = m != null ? m.config() : ModEnv.loadConfigOrDefaults();
		return cfg.permissions.level(node);
	}

	public static boolean check(CommandSourceStack source, String node) {
		return Permissions.check(source, "cytrabackups." + node, level(node));
	}

	public static boolean check(ServerPlayer player, String node) {
		return Permissions.check(player, "cytrabackups." + node, level(node));
	}

	public static Predicate<CommandSourceStack> require(String node) {
		return source -> check(source, node);
	}

	/** Root /cbackup node: visible to anyone holding at least one CytraBackups permission. */
	public static boolean any(CommandSourceStack source) {
		for (String n : new String[]{LIST, CREATE, RESTORE, ADMIN, PRUNE, DELETE, VERIFY, EXPORT, PIN, CANCEL, COMMENT}) {
			if (check(source, n)) return true;
		}
		return false;
	}
}
