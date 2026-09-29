package dev.steelaspect.cytrabackups.gametest;

import dev.steelaspect.cytrabackups.mc.Perms;
import me.lucko.fabric.api.permissions.v0.PermissionCheckEvent;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.fabric.api.util.TriState;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.permissions.PermissionSet;

/**
 * Checks that CytraBackups asks fabric-permissions-api (the API LuckPerms and other permission mods implement)
 * and only falls back to op levels when no provider decides.
 */
public class PermissionsGameTest {
	private static volatile CommandSourceStack granted;
	private static volatile CommandSourceStack denied;

	static {
		PermissionCheckEvent.EVENT.register((source, permission) -> {
			if (source == granted && permission.equals("cytrabackups.restore")) return TriState.TRUE;
			if (source == denied && permission.startsWith("cytrabackups.")) return TriState.FALSE;
			return TriState.DEFAULT;
		});
	}

	@GameTest
	public void permissionsApiIsHonoured(GameTestHelper helper) {
		CommandSourceStack op = helper.getLevel().getServer().createCommandSourceStack(); // console: all permissions
		CommandSourceStack nobody = op.withPermission(PermissionSet.NO_PERMISSIONS);
		helper.assertTrue(Perms.check(op, Perms.RESTORE), "op-level fallback grants restore to the console");
		helper.assertTrue(!Perms.check(nobody, Perms.LIST), "level 0 has no permissions by default");
		helper.assertTrue(!Perms.any(nobody), "/backup is hidden from level 0");

		granted = op.withPermission(PermissionSet.NO_PERMISSIONS);
		helper.assertTrue(Perms.check(granted, Perms.RESTORE), "a permissions mod can grant cytrabackups.restore to a non-op");
		helper.assertTrue(!Perms.check(granted, Perms.CREATE), "other nodes still use the op fallback");
		helper.assertTrue(Perms.any(granted), "/backup becomes visible once any node is granted");
		var backupNode = helper.getLevel().getServer().getCommands().getDispatcher().getRoot().getChild("backup");
		helper.assertTrue(backupNode.canUse(granted), "root command usable with a granted node");
		helper.assertTrue(backupNode.getChild("restore").canUse(granted), "restore subcommand usable");
		helper.assertTrue(!backupNode.getChild("delete").canUse(granted), "delete subcommand still denied");

		denied = op.withPermission(PermissionSet.ALL_PERMISSIONS);
		helper.assertTrue(!Perms.check(denied, Perms.LIST), "a permissions mod can deny even an op");
		helper.assertTrue(!backupNode.canUse(denied), "/backup hidden when every node is denied");
		helper.succeed();
	}
}
