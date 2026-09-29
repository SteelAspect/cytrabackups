package dev.steelaspect.cytrabackups.client;

import dev.steelaspect.cytrabackups.core.config.ConfigSchema;
import dev.steelaspect.cytrabackups.core.config.CytraConfig;
import dev.steelaspect.cytrabackups.net.BackupListPayload;
import dev.steelaspect.cytrabackups.net.ConfigPayload;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;

/** Sample data so the GUI can be rendered and screenshotted without a server (test only). */
public final class GuiPreviewData {
	private GuiPreviewData() {
	}

	public static void load() {
		long now = System.currentTimeMillis(), h = 3_600_000L;
		List<BackupListPayload.Entry> entries = new ArrayList<>();
		String[] triggers = {"manual", "scheduled", "scheduled", "pre-restore", "scheduled", "manual", "import", "scheduled", "scheduled", "manual",
			"scheduled", "scheduled", "scheduled", "manual"};
		String[] comments = {"before the dragon fight", "", "", "Automatic backup before restoring #9", "", "new base finished", "old world.zip", "", "",
			"test", "", "", "", "first backup"};
		for (int i = 0; i < triggers.length; i++) {
			int id = 14 - i;
			entries.add(new BackupListPayload.Entry(id, now - i * 5 * h - 600_000L, triggers[i], comments[i], "steelaspect",
				177L * 1024 * 1024 + i * 3_000_000L, i == 13 ? 97L * 1024 * 1024 : 1_500_000L + i * 400_000L, i == 5 || i == 13, i == 3));
		}
		int all = 0;
		for (int f = 1; f <= 1024; f <<= 1) all |= f;
		ClientState.update(new BackupListPayload(entries, "14 backups", "", -1f, all, 12,
			List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end")));
		CytraConfig cfg = new CytraConfig();
		cfg.offsite.s3.secretKey = "hidden";
		ClientState.config(new ConfigPayload(ConfigSchema.describe(cfg), List.of()));
		ClientState.log(Component.literal("> /backup create before the dragon fight").withStyle(ChatFormatting.DARK_GRAY), false);
		ClientState.log(Component.literal("Backup #14 created in 2.1s: 177 MiB, 97.4 MiB new (12/40 files unchanged). ").withStyle(ChatFormatting.GREEN)
			.append(Component.literal("[Info]").withStyle(s -> s.withColor(ChatFormatting.AQUA).withBold(true)
				.withClickEvent(new ClickEvent.RunCommand("/backup info 14")).withHoverEvent(new HoverEvent.ShowText(Component.literal("Show details"))))), false);
		ClientState.log(Component.literal("Backup #14 — 2026-09-29 17:20 (just now)").withStyle(ChatFormatting.GRAY), false);
		ClientState.log(Component.literal(" Size: 177 MiB in 40 files, 1,204 chunks · Dedup saved: 79.6 MiB").withStyle(ChatFormatting.GRAY), false);
	}

	/** Sets the main screen's tab and selected backup (they are private statics). */
	public static void select(String tab, int id) throws ReflectiveOperationException {
		var t = BackupScreen.class.getDeclaredField("tab");
		t.setAccessible(true);
		t.set(null, Enum.valueOf(BackupScreen.Tab.class, tab));
		var s = BackupScreen.class.getDeclaredField("selectedId");
		s.setAccessible(true);
		s.setInt(null, id);
	}

	public static void settingsSection(String section) throws ReflectiveOperationException {
		var f = SettingsScreen.class.getDeclaredField("section");
		f.setAccessible(true);
		f.set(null, section);
	}

	public static net.minecraft.client.gui.screens.Screen settings(net.minecraft.client.gui.screens.Screen parent) {
		return new SettingsScreen(parent);
	}

	public static net.minecraft.client.gui.screens.Screen form(net.minecraft.client.gui.screens.Screen parent) {
		return new FormScreen(parent, "Comment for backup #14", "Leave empty to clear the comment.",
			List.of(new FormScreen.Field("Comment", "before the dragon fight", "", 200)), "Save", v -> {
			});
	}
}
