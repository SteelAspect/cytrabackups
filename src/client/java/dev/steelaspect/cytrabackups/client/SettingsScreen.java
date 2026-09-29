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
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * Editor for every CytraBackups setting. The server sends the settings (passwords and keys are never sent, only
 * whether they are set); Save sends just the changed values, which the server validates, writes to
 * config/cytrabackups.json and applies with a reload.
 */
final class SettingsScreen extends Screen {
	private static final int ROW_H = 22;
	private static final int SIDE_W = 104;
	private static final int TOP = 30;
	private static final Map<String, String> SECTION_HELP = Map.of(
		"general", "Storage location, speed limits, what gets backed up",
		"compression", "How stored data is compressed",
		"schedule", "When automatic backups run",
		"prune", "Which old backups are kept and which are deleted",
		"restore", "Confirmations, countdown and how restores are applied",
		"progress", "Boss bar, action bar and announcements",
		"discord", "Notifications to a Discord webhook",
		"offsite", "Copies to S3, SFTP or WebDAV",
		"permissions", "Op levels used when no permissions mod decides");
	private static String section = "schedule"; // remembered while the game runs

	private final Screen parent;
	private final Map<String, String> edits = new LinkedHashMap<>();
	private final List<Row> rows = new ArrayList<>();
	private long seenConfig = -1;
	private int scroll;
	private boolean saving;
	private FlatButton saveButton, revertButton;
	// layout of the settings panel, computed in init() from the labels of the current section
	private int panelX, panelW, labelW, widgetW;

	private record Row(ConfigSchema.Entry entry, String label, List<AbstractWidget> widgets) {
	}

	SettingsScreen(Screen parent) {
		super(Component.literal("CytraBackups settings"));
		this.parent = parent;
		ClientState.requestConfig();
	}

	private int rowsTop() {
		return TOP + 22;
	}

	private int rowsBottom() {
		return height - 52;
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
		saveButton = addRenderableWidget(new FlatButton(width / 2 - 154, by, 100, 20, "Save", Theme.SUCCESS, b -> save())
			.tooltip("Validate, write config/cytrabackups.json and apply the changes now"));
		revertButton = addRenderableWidget(new FlatButton(width / 2 - 50, by, 100, 20, "Revert", Theme.WARNING, b -> {
			edits.clear();
			rebuildWidgets();
		}).tooltip("Forget unsaved changes"));
		addRenderableWidget(new FlatButton(width / 2 + 54, by, 100, 20, "Back", Theme.NEUTRAL, b -> onClose()));
		updateButtons();
		if (cfg == null) return;
		List<String> sections = sections(cfg);
		if (!sections.contains(section)) section = sections.getFirst();
		int sy = TOP + 6;
		for (String s : sections) {
			addRenderableWidget(new FlatButton(14, sy, SIDE_W - 4, 18, sectionLabel(s), Theme.NEUTRAL, btn -> {
				section = s;
				scroll = 0;
				rebuildWidgets();
			}).asTab(s.equals(section)).tooltip(SECTION_HELP.getOrDefault(s, "")));
			sy += 20;
		}

		List<ConfigSchema.Entry> entries = cfg.entries().stream().filter(e -> e.section().equals(section)).toList();
		labelW = 120;
		for (ConfigSchema.Entry e : entries) labelW = Math.max(labelW, font.width(label(e)) + 16);
		int areaX = 8 + SIDE_W + 18, areaW = width - areaX - 10;
		labelW = Math.min(labelW, Math.max(120, areaW / 2 - 20));
		widgetW = Math.max(140, Math.min(230, areaW - labelW - 28));
		panelW = Math.min(areaW, labelW + widgetW + 28);
		panelX = areaX + Math.max(0, (areaW - panelW) / 2);
		int wx = panelX + 12 + labelW, ww = widgetW;

		for (ConfigSchema.Entry e : entries) {
			List<AbstractWidget> ws = new ArrayList<>();
			Tooltip tip = e.comment().isEmpty() ? null : Tooltip.create(Component.literal(e.comment()));
			String current = edits.getOrDefault(e.path(), e.value());
			switch (e.type()) {
				case BOOL -> ws.add(new FlatButton.Cycle(wx, 0, ww, 18, List.of("true", "false"), Boolean.parseBoolean(current) ? "true" : "false",
					v -> v.equals("true") ? Theme.SUCCESS : Theme.DANGER, v -> v.equals("true") ? "ON" : "OFF", v -> edit(e, v)));
				case CHOICE -> ws.add(new FlatButton.Cycle(wx, 0, ww, 18, e.choices(), e.choices().contains(current) ? current : e.choices().getFirst(),
					v -> Theme.PRIMARY, v -> v, v -> edit(e, v)));
				case SECRET -> {
					EditBox box = new EditBox(font, wx, 0, ww - 48, 18, Component.literal(e.path()));
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
					FlatButton clear = new FlatButton(wx + ww - 44, 0, 44, 18, "Clear", Theme.DANGER, b -> {
						edits.put(e.path(), "");
						rebuildWidgets();
					}).tooltip("Remove the stored value when you save");
					clear.active = e.set() || edits.containsKey(e.path());
					ws.add(clear);
				}
				default -> {
					EditBox box = new EditBox(font, wx, 0, ww, 18, Component.literal(e.path()));
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
				boolean plainButton = w instanceof FlatButton && !(w instanceof FlatButton.Cycle); // the Clear button keeps its own tooltip
				if (tip != null && !plainButton) w.setTooltip(tip);
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
		saveButton.setLabel(saving ? "Saving..." : edits.isEmpty() ? "Save" : "Save (" + edits.size() + ")");
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
			int y = rowsTop() + idx * ROW_H + 2;
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
		if (x >= panelX) {
			scroll -= (int) Math.signum(scrollY) * 2;
			layoutRows();
			return true;
		}
		return super.mouseScrolled(x, y, scrollX, scrollY);
	}

	@Override
	public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		super.renderBackground(g, mouseX, mouseY, partialTick);
		Theme.header(g, font, width, "CytraBackups", "settings");
		ConfigPayload cfg = ClientState.config;
		if (cfg == null) return;
		Theme.panel(g, 8, TOP, SIDE_W + 8, sections(cfg).size() * 20 + 10);
		int h = Math.min(visibleRows(), Math.max(1, rows.size())) * ROW_H + 22 + 4;
		Theme.titledPanel(g, font, panelX, TOP, panelW, h, sectionLabel(section), Theme.TITLE);
		g.drawString(font, Theme.ellipsize(font, SECTION_HELP.getOrDefault(section, ""), panelW - 16 - font.width(sectionLabel(section))),
			panelX + 12 + font.width(sectionLabel(section)), TOP + 3, Theme.MUTED);
		for (int i = scroll; i < rows.size() && i - scroll < visibleRows(); i++) {
			int y = rowsTop() + (i - scroll) * ROW_H;
			g.fill(panelX + 1, y, panelX + panelW - 1, y + ROW_H, (i % 2 == 0) ? 0x28FFFFFF : 0x10FFFFFF);
			if (edits.containsKey(rows.get(i).entry().path())) g.fill(panelX + 1, y, panelX + 4, y + ROW_H, Theme.GOLD_TEXT);
		}
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		super.render(g, mouseX, mouseY, partialTick);
		String hint = saving ? "Saving and reloading..." : edits.isEmpty() ? "Saved to config/cytrabackups.json and applied at once"
			: edits.size() + " unsaved change(s)";
		g.drawString(font, hint, width - 10 - font.width(hint), 7, edits.isEmpty() ? Theme.MUTED : Theme.GOLD_TEXT);
		ConfigPayload cfg = ClientState.config;
		if (cfg == null) {
			String msg = ClientState.can(BackupListPayload.CAN_ADMIN) || ClientState.list == null ? "Loading settings..."
				: "You need the cytrabackups.admin permission to change settings.";
			g.drawCenteredString(font, msg, width / 2, height / 2 - 10, Theme.MUTED);
			return;
		}
		for (int i = scroll; i < rows.size() && i - scroll < visibleRows(); i++) {
			Row r = rows.get(i);
			int y = rowsTop() + (i - scroll) * ROW_H;
			boolean changed = edits.containsKey(r.entry().path());
			String text = Theme.ellipsize(font, r.label(), labelW - 8);
			g.drawString(font, text, panelX + 12, y + 7, changed ? Theme.GOLD_TEXT : Theme.TEXT);
			if (!r.entry().comment().isEmpty() && mouseX >= panelX + 8 && mouseX < panelX + 8 + labelW && mouseY >= y && mouseY < y + ROW_H) {
				List<FormattedCharSequence> tip = new ArrayList<>(font.split(Component.literal(r.entry().comment()), 260));
				tip.addFirst(Component.literal(r.entry().path()).withStyle(ChatFormatting.GRAY).getVisualOrderText());
				g.setTooltipForNextFrame(tip, mouseX, mouseY);
			}
		}
		if (rows.size() > visibleRows()) {
			int h = visibleRows() * ROW_H;
			int barH = Math.max(10, h * visibleRows() / rows.size());
			int barY = rowsTop() + (h - barH) * scroll / Math.max(1, rows.size() - visibleRows());
			g.fill(panelX + panelW - 3, barY, panelX + panelW - 1, barY + barH, Theme.ACCENT);
			g.drawString(font, "scroll for more", panelX + panelW - 4 - font.width("scroll for more"), rowsBottom() + 2, Theme.FAINT);
		}
		int py = rowsBottom() + 12;
		for (String p : cfg.problems()) {
			for (FormattedCharSequence line : font.split(Component.literal(p), width - 20)) {
				if (py > height - 34) break;
				g.drawString(font, line, 10, py, Theme.RED_TEXT);
				py += 10;
			}
		}
		ClientState.LogLine last = ClientState.lastLog();
		if (cfg.problems().isEmpty() && last != null && System.currentTimeMillis() - last.at() < 10_000) {
			List<FormattedCharSequence> lines = font.split(last.text(), width - 20);
			if (!lines.isEmpty()) g.drawCenteredString(font, lines.getFirst(), width / 2, py, Theme.TEXT);
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
		minecraft.setScreen(new ConfirmDialog("Discard " + edits.size() + " unsaved change(s)?", "They are not written to the config file.", "Discard",
			Theme.DANGER, yes -> minecraft.setScreen(yes ? parent : this)));
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
