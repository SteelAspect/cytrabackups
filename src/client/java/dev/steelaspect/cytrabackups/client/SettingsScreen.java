package dev.steelaspect.cytrabackups.client;

import dev.steelaspect.cytrabackups.core.config.ConfigSchema;
import dev.steelaspect.cytrabackups.net.BackupListPayload;
import dev.steelaspect.cytrabackups.net.ConfigPayload;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * Editor for every CytraBackups setting. The server sends the settings (passwords and keys are never sent, only
 * whether they are set); Save sends just the changed values, which the server validates, writes to
 * config/cytrabackups.json and applies with a reload.
 */
final class SettingsScreen extends Screen {
	private static final int ROW_H = 24;
	private static final int SIDE_W = 96;
	private static String section = "schedule"; // remembered while the game runs

	private final Screen parent;
	private final Map<String, String> edits = new LinkedHashMap<>();
	private final List<Row> rows = new ArrayList<>();
	private long seenConfig = -1;
	private int scroll;
	private boolean saving;
	private Button saveButton, revertButton;

	private record Row(ConfigSchema.Entry entry, String label, List<AbstractWidget> widgets) {
	}

	SettingsScreen(Screen parent) {
		super(Component.literal("CytraBackups settings"));
		this.parent = parent;
		ClientState.requestConfig();
	}

	private int rowsTop() {
		return 34;
	}

	private int rowsBottom() {
		return height - 58;
	}

	private int labelX() {
		return 10 + SIDE_W + 12;
	}

	private int widgetW() {
		return Math.max(90, Math.min(220, (width - labelX() - 10) / 2));
	}

	private List<String> sections(ConfigPayload cfg) {
		Set<String> out = new LinkedHashSet<>();
		for (ConfigSchema.Entry e : cfg.entries()) out.add(e.section());
		return new ArrayList<>(out);
	}

	@Override
	protected void init() {
		rows.clear();
		ConfigPayload cfg = ClientState.config;
		seenConfig = ClientState.configVersion;
		int by = height - 24;
		saveButton = addRenderableWidget(Button.builder(Component.literal("Save"), b -> save()).bounds(width / 2 - 154, by, 100, 20)
			.tooltip(Tooltip.create(Component.literal("Validate, write config/cytrabackups.json and apply the changes now"))).build());
		revertButton = addRenderableWidget(Button.builder(Component.literal("Revert"), b -> {
			edits.clear();
			rebuildWidgets();
		}).bounds(width / 2 - 50, by, 100, 20).tooltip(Tooltip.create(Component.literal("Forget unsaved changes"))).build());
		updateButtons();
		addRenderableWidget(Button.builder(Component.literal("Back"), b -> onClose()).bounds(width / 2 + 54, by, 100, 20).build());
		if (cfg == null) return;
		List<String> sections = sections(cfg);
		if (!sections.contains(section)) section = sections.getFirst();
		int sy = rowsTop();
		for (String s : sections) {
			Button b = Button.builder(Component.literal(sectionLabel(s)), btn -> {
				section = s;
				scroll = 0;
				rebuildWidgets();
			}).bounds(10, sy, SIDE_W, 20).build();
			b.active = !s.equals(section);
			addRenderableWidget(b);
			sy += 22;
		}
		int wx = width - 10 - widgetW(), ww = widgetW();
		for (ConfigSchema.Entry e : cfg.entries()) {
			if (!e.section().equals(section)) continue;
			List<AbstractWidget> ws = new ArrayList<>();
			Tooltip tip = e.comment().isEmpty() ? null : Tooltip.create(Component.literal(e.comment()));
			String current = edits.getOrDefault(e.path(), e.value());
			switch (e.type()) {
				case BOOL -> ws.add(CycleButton.onOffBuilder(Boolean.parseBoolean(current)).displayOnlyValue()
					.create(wx, 0, ww, 20, Component.literal(e.path()), (btn, v) -> edit(e, String.valueOf(v))));
				case CHOICE -> ws.add(CycleButton.<String>builder(Component::literal, e.choices().contains(current) ? current : e.choices().getFirst())
					.withValues(e.choices()).displayOnlyValue().create(wx, 0, ww, 20, Component.literal(e.path()), (btn, v) -> edit(e, v)));
				case SECRET -> {
					EditBox box = new EditBox(font, wx, 0, ww - 44, 20, Component.literal(e.path()));
					box.setMaxLength(1024);
					boolean pendingClear = "".equals(edits.get(e.path()));
					box.setValue(pendingClear ? "" : edits.getOrDefault(e.path(), ""));
					box.setHint(Component.literal(pendingClear ? "will be cleared" : e.set() ? "set - type to replace" : "not set"));
					box.setResponder(text -> {
						if (text.isEmpty()) edits.remove(e.path());
						else edits.put(e.path(), text);
						updateButtons();
					});
					ws.add(box);
					Button clear = Button.builder(Component.literal("Clear"), b -> {
						edits.put(e.path(), "");
						rebuildWidgets();
					}).bounds(wx + ww - 40, 0, 40, 20).tooltip(Tooltip.create(Component.literal("Remove the stored value when you save"))).build();
					clear.active = e.set() || edits.containsKey(e.path());
					ws.add(clear);
				}
				default -> {
					EditBox box = new EditBox(font, wx, 0, ww, 20, Component.literal(e.path()));
					box.setMaxLength(e.type() == ConfigSchema.Type.LIST ? 8192 : 1024);
					box.setValue(current);
					switch (e.type()) {
						case INT, LONG -> box.setFilter(t -> t.matches("-?\\d*"));
						case DOUBLE -> box.setFilter(t -> t.matches("-?\\d*\\.?\\d*"));
						default -> {
						}
					}
					if (e.type() == ConfigSchema.Type.LIST) box.setHint(Component.literal("comma-separated, empty = none"));
					box.setResponder(text -> edit(e, text));
					ws.add(box);
				}
			}
			for (AbstractWidget w : ws) {
				if (tip != null && !(w instanceof Button && e.type() == ConfigSchema.Type.SECRET)) w.setTooltip(tip);
				addRenderableWidget(w);
			}
			rows.add(new Row(e, label(e), ws));
		}
		layoutRows();
	}

	private void edit(ConfigSchema.Entry e, String value) {
		if (value.equals(e.value())) edits.remove(e.path());
		else edits.put(e.path(), value);
		updateButtons();
	}

	private void updateButtons() {
		if (saveButton == null) return;
		saveButton.active = !edits.isEmpty() && !saving;
		revertButton.active = !edits.isEmpty();
	}

	private int visibleRows() {
		return Math.max(1, (rowsBottom() - rowsTop()) / ROW_H);
	}

	private void layoutRows() {
		scroll = Math.max(0, Math.min(scroll, Math.max(0, rows.size() - visibleRows())));
		for (int i = 0; i < rows.size(); i++) {
			int idx = i - scroll;
			boolean vis = idx >= 0 && idx < visibleRows();
			int y = rowsTop() + idx * ROW_H;
			for (AbstractWidget w : rows.get(i).widgets()) {
				w.visible = vis;
				w.setY(y);
			}
		}
	}

	private void save() {
		if (edits.isEmpty()) return;
		saving = true;
		ClientState.saveConfig(new LinkedHashMap<>(edits));
		updateButtons();
	}

	@Override
	public void tick() {
		super.tick();
		if (seenConfig != ClientState.configVersion) {
			ConfigPayload cfg = ClientState.config;
			if (saving && cfg != null && cfg.problems().isEmpty()) edits.clear(); // saved and reloaded
			saving = false;
			rebuildWidgets();
		}
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		if (x > labelX() - 4) {
			scroll -= (int) Math.signum(scrollY) * 2;
			layoutRows();
			return true;
		}
		return super.mouseScrolled(x, y, scrollX, scrollY);
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		super.render(g, mouseX, mouseY, partialTick);
		g.drawString(font, title, 10, 8, 0xFFFFAA00);
		String hint = edits.isEmpty() ? "Changes are written to config/cytrabackups.json and applied at once." : edits.size() + " unsaved change(s)";
		g.drawString(font, hint, width - 10 - font.width(hint), 8, edits.isEmpty() ? 0xFFA0A0A0 : 0xFFFFFF55);
		ConfigPayload cfg = ClientState.config;
		if (cfg == null) {
			String msg = ClientState.can(BackupListPayload.CAN_ADMIN) || ClientState.list == null ? "Loading settings..."
				: "You need the cytrabackups.admin permission to change settings.";
			g.drawCenteredString(font, msg, width / 2, height / 2 - 10, 0xFFA0A0A0);
			return;
		}
		g.drawString(font, sectionLabel(section) + " - hover a setting for help, scroll for more", labelX(), 22, 0xFFA0A0A0);
		for (int i = scroll; i < rows.size() && i - scroll < visibleRows(); i++) {
			Row r = rows.get(i);
			int y = rowsTop() + (i - scroll) * ROW_H;
			boolean changed = edits.containsKey(r.entry().path());
			int maxW = width - 10 - widgetW() - labelX() - 8;
			String text = font.width(r.label()) > maxW ? font.plainSubstrByWidth(r.label(), maxW - 6) + "..." : r.label();
			g.drawString(font, (changed ? "* " : "") + text, labelX(), y + 6, changed ? 0xFFFFFF55 : 0xFFE0E0E0);
			if (!r.entry().comment().isEmpty() && mouseX >= labelX() && mouseX < labelX() + maxW && mouseY >= y && mouseY < y + 20) {
				List<FormattedCharSequence> tip = new ArrayList<>(font.split(Component.literal(r.entry().comment()), 260));
				tip.addFirst(Component.literal(r.entry().path()).withStyle(net.minecraft.ChatFormatting.GRAY).getVisualOrderText());
				g.setTooltipForNextFrame(tip, mouseX, mouseY);
			}
		}
		if (rows.size() > visibleRows()) {
			int h = rowsBottom() - rowsTop();
			int barH = Math.max(10, h * visibleRows() / rows.size());
			int barY = rowsTop() + (h - barH) * scroll / Math.max(1, rows.size() - visibleRows());
			g.fill(width - 5, barY, width - 3, barY + barH, 0xFF808080);
		}
		int py = rowsBottom() + 4;
		for (String p : cfg.problems()) {
			for (FormattedCharSequence line : font.split(Component.literal(p), width - 20)) {
				if (py > height - 34) break;
				g.drawString(font, line, 10, py, 0xFFFF5555);
				py += 10;
			}
		}
		ClientState.LogLine last = ClientState.lastLog();
		if (cfg.problems().isEmpty() && last != null && System.currentTimeMillis() - last.at() < 10_000) {
			List<FormattedCharSequence> lines = font.split(last.text(), width - 20);
			if (!lines.isEmpty()) g.drawString(font, lines.getFirst(), 10, py, 0xFFE0E0E0);
		}
	}

	static String sectionLabel(String s) {
		if (s.equals("offsite")) return "Off-site";
		return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
	}

	/** "schedule.intervalMinutes" -> "Interval minutes", "offsite.s3.accessKey" -> "S3 > Access key". */
	static String label(ConfigSchema.Entry e) {
		String p = e.path();
		if (p.startsWith("permissions.defaultLevels.")) return "Op level for " + p.substring("permissions.defaultLevels.".length());
		String rest = e.section().equals("general") ? p : p.substring(e.section().length() + 1);
		List<String> parts = new ArrayList<>();
		for (String part : rest.split("\\.")) parts.add(words(part));
		return String.join(" > ", parts);
	}

	static String words(String camel) {
		String[] parts = camel.replace("MiB", "Mib").replace("GiB", "Gib").split("(?<=[a-z0-9])(?=[A-Z])");
		StringBuilder sb = new StringBuilder();
		for (String part : parts) {
			String w = part.toLowerCase(Locale.ROOT);
			w = switch (w) {
				case "mib" -> "MiB";
				case "gib" -> "GiB";
				case "s3" -> "S3";
				case "sftp" -> "SFTP";
				case "webdav" -> "WebDAV";
				case "url" -> "URL";
				default -> w;
			};
			if (!sb.isEmpty()) sb.append(' ');
			sb.append(w);
		}
		return sb.isEmpty() ? "" : Character.toUpperCase(sb.charAt(0)) + sb.substring(1);
	}

	@Override
	public void onClose() {
		if (edits.isEmpty()) {
			minecraft.setScreen(parent);
			return;
		}
		minecraft.setScreen(new ConfirmScreen(yes -> minecraft.setScreen(yes ? parent : this),
			Component.literal("Discard " + edits.size() + " unsaved change(s)?"), Component.literal("They are not written to the config file.")));
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
