package dev.steelaspect.cytrabackups.client;

import dev.steelaspect.cytrabackups.core.config.ConfigSchema;
import dev.steelaspect.cytrabackups.core.config.CytraConfig;
import dev.steelaspect.cytrabackups.net.BackupListPayload;
import dev.steelaspect.cytrabackups.net.ConfigPayload;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
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
		ClientState.log(Component.literal("> /cbackup create before the dragon fight").withStyle(ChatFormatting.DARK_GRAY), false);
		ClientState.log(Component.literal("Backup #14 created in 2.1s: 177 MB, 97 MB new. ").withStyle(ChatFormatting.GREEN)
			.append(Component.literal("[Info]").withStyle(s -> s.withColor(ChatFormatting.GOLD)
				.withClickEvent(new ClickEvent.RunCommand("/cbackup info 14")).withHoverEvent(new HoverEvent.ShowText(Component.literal("Show details"))))), false);
		ClientState.log(Component.literal("Backup #14, 2026-09-29 17:20 (just now)"), false);
		ClientState.log(Component.literal("177 MB in 40 files, 1204 chunks. Deduplication saved 80 MB.").withStyle(ChatFormatting.GRAY), false);
	}

	/** Sets the main screen's tab and selected backup (they are private statics). */
	public static void select(int tab, int id) {
		setStatic(BackupScreen.class, "lastTab", tab);
		setStatic(BackupScreen.class, "selectedId", id);
	}

	public static void settingsSection(String section) {
		setStatic(SettingsScreen.class, "section", section);
	}

	private static void setStatic(Class<?> owner, String name, Object value) {
		try {
			Field f = owner.getDeclaredField(name);
			f.setAccessible(true);
			f.set(null, value);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	public static Screen settings(Screen parent) {
		return new SettingsScreen(parent);
	}

	public static Screen form(Screen parent) {
		return new FormScreen(parent, Component.translatable("cytrabackups.gui.comment.title", 14),
			List.of(new FormScreen.Field("cytrabackups.gui.comment.field", "before the dragon fight", 200)), v -> {
			});
	}
}
