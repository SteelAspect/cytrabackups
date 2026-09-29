package dev.steelaspect.cytrabackups.client;

import dev.steelaspect.cytrabackups.core.Formatting;
import dev.steelaspect.cytrabackups.net.BackupListPayload;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Main CytraBackups screen. Every button runs the matching /backup command on the server (same permissions and
 * behaviour as typing it); the server's replies appear in the output panel and confirmations open a dialog.
 */
public final class BackupScreen extends Screen {
	enum Tab {
		BACKUPS("Backups"), RESTORE("Restore"), TOOLS("Tools");

		final String label;

		Tab(String label) {
			this.label = label;
		}
	}

	private static final int ROW = 12;
	private static final int WHITE = 0xFFFFFFFF;
	private static final int GRAY = 0xFFA0A0A0;
	private static final int GOLD = 0xFFFFAA00;
	private static final int COL_W = 150; // right-hand action column on the Backups tab

	// remembered while the game runs, so dialogs and reopening keep the place
	private static Tab tab = Tab.BACKUPS;
	private static int selectedId = -1;

	private int scroll;
	private long seenVersion = -1;
	private int ticks;
	private long lastClickAt;
	private int lastClickId = -1;
	private final LogPanel log = new LogPanel(this::suggest);
	private final List<Runnable> refreshers = new ArrayList<>();
	private final List<Label> labels = new ArrayList<>();
	private EditBox commentBox, importPath, importComment;

	private record Label(String text, int x, int y, int color) {
	}

	public BackupScreen() {
		super(Component.literal("CytraBackups"));
	}

	// ------------------------------------------------------------------ layout

	private int contentTop() {
		return 48;
	}

	private int logHeight() {
		return Math.max(44, Math.min(120, height / 4));
	}

	private int logTop() {
		return height - 28 - logHeight();
	}

	private int contentBottom() {
		return logTop() - 6;
	}

	private int listRight() {
		return width - 10 - COL_W - 8;
	}

	private int listTop() {
		return contentTop() + 12;
	}

	private int listBottom() {
		return contentBottom() - 36;
	}

	// ------------------------------------------------------------------ widgets

	private Button button(String label, String tooltip, int x, int y, int w, Runnable action, BooleanSupplier active) {
		Button.Builder builder = Button.builder(Component.literal(label), btn -> action.run()).bounds(x, y, w, 20);
		if (!tooltip.isEmpty()) builder.tooltip(Tooltip.create(Component.literal(tooltip)));
		Button b = builder.build();
		addRenderableWidget(b);
		refreshers.add(() -> b.active = active.getAsBoolean());
		return b;
	}

	private static boolean can(int flag) {
		return ClientState.can(flag);
	}

	@Override
	protected void init() {
		refreshers.clear();
		labels.clear();
		int tx = 10;
		for (Tab t : Tab.values()) {
			Button b = Button.builder(Component.literal(t.label), btn -> switchTab(t)).bounds(tx, 22, 80, 20).build();
			b.active = t != tab;
			addRenderableWidget(b);
			tx += 84;
		}
		button("Settings...", "Edit every CytraBackups setting (needs cytrabackups.admin)", tx, 22, 80,
			() -> minecraft.setScreen(new SettingsScreen(this)), () -> can(BackupListPayload.CAN_ADMIN));
		switch (tab) {
			case BACKUPS -> initBackups();
			case RESTORE -> initRestore();
			case TOOLS -> initTools();
		}
		button("Refresh", "Reload the backup list", width - 10 - 64 - 4 - 64, height - 24, 64, ClientState::requestList, () -> true);
		button("Close", "", width - 10 - 64, height - 24, 64, this::onClose, () -> true);
		log.setBounds(10, logTop(), width - 20, logHeight());
		refresh();
	}

	private void switchTab(Tab t) {
		tab = t;
		rebuildWidgets();
	}

	private void initBackups() {
		int y = contentBottom() - 20;
		int boxW = Math.max(80, Math.min(260, listRight() - 10 - 110));
		commentBox = new EditBox(font, 10, y, boxW, 20, Component.literal("Comment"));
		commentBox.setMaxLength(200);
		commentBox.setHint(Component.literal("Comment for a new backup (optional)"));
		addRenderableWidget(commentBox);
		button("Create backup", "Back up the world now (/backup create)", 10 + boxW + 4, y, 106, () -> {
			String c = commentBox.getValue().trim();
			ClientState.run(c.isEmpty() ? "backup create" : "backup create " + c);
			commentBox.setValue("");
		}, () -> can(BackupListPayload.CAN_CREATE));

		int cx = width - 10 - COL_W, cw = (COL_W - 4) / 2, cy = contentTop();
		BooleanSupplier sel = () -> selected() != null;
		button("Info", "Details and size of the selected backup", cx, cy, cw, () -> runOnSelected("backup info %d"),
			() -> sel.getAsBoolean() && can(BackupListPayload.CAN_LIST));
		button("Compare...", "Changed files and chunks between two backups", cx + cw + 4, cy, cw, this::compare,
			() -> sel.getAsBoolean() && can(BackupListPayload.CAN_LIST));
		cy += 24;
		button("Restore...", "Restore the whole world to this backup. You confirm first; everyone is kicked and the server restarts.",
			cx, cy, cw, () -> runOnSelected("backup restore %d"), () -> sel.getAsBoolean() && !selected().partial() && can(BackupListPayload.CAN_RESTORE));
		button("Area...", "Restore chosen chunks, a region or a radius from this backup (map selector)", cx + cw + 4, cy, cw,
			() -> minecraft.setScreen(new ChunkSelectorScreen(this, selectedId)), () -> sel.getAsBoolean() && can(BackupListPayload.CAN_RESTORE));
		cy += 24;
		button("Verify", "Check every stored piece of this backup against its hash", cx, cy, cw, () -> runOnSelected("backup verify %d"),
			() -> sel.getAsBoolean() && can(BackupListPayload.CAN_VERIFY));
		button("Export", "Build a standalone world .zip from this backup (path shown when done)", cx + cw + 4, cy, cw,
			() -> runOnSelected("backup export %d"), () -> sel.getAsBoolean() && !selected().partial() && can(BackupListPayload.CAN_EXPORT));
		cy += 24;
		Button pin = button("Pin", "Pinned backups are never pruned", cx, cy, cw,
			() -> runOnSelected(selected().pinned() ? "backup unpin %d" : "backup pin %d"), () -> sel.getAsBoolean() && can(BackupListPayload.CAN_PIN));
		refreshers.add(() -> pin.setMessage(Component.literal(selected() != null && selected().pinned() ? "Unpin" : "Pin")));
		button("Comment...", "Edit or clear the comment", cx + cw + 4, cy, cw, this::editComment,
			() -> sel.getAsBoolean() && can(BackupListPayload.CAN_COMMENT));
		cy += 24;
		button("Delete...", "Delete this backup (you confirm first). Pinned backups must be unpinned first.", cx, cy, cw,
			() -> runOnSelected("backup delete %d"), () -> sel.getAsBoolean() && !selected().pinned() && can(BackupListPayload.CAN_DELETE));
		button("Diff prev", "Compare with the backup before it", cx + cw + 4, cy, cw, this::diffPrevious,
			() -> sel.getAsBoolean() && previousId() > 0 && can(BackupListPayload.CAN_LIST));
	}

	private void initRestore() {
		int x = 10, bx = 170, y = contentTop();
		labels.add(new Label("Queued restore", x, y + 6, WHITE));
		button("Show", "Show the restore waiting for the next restart (/backup pending)", bx, y, 70, () -> ClientState.run("backup pending"),
			() -> can(BackupListPayload.CAN_RESTORE));
		button("Cancel it", "Drop the queued restore (/backup pending cancel)", bx + 74, y, 80, () -> ClientState.run("backup pending cancel"),
			() -> can(BackupListPayload.CAN_RESTORE));
		button("Apply now...", "Count down, kick everyone and stop the server so the queued restore is applied (you confirm first)", bx + 158, y, 90,
			() -> ClientState.run("backup pending apply"), () -> can(BackupListPayload.CAN_RESTORE));
		y += 28;
		labels.add(new Label("Undo the last restore", x, y + 6, WHITE));
		button("Roll back...", "Put back the files the last restore replaced (from its recycle bin). You confirm first.", bx, y, 100,
			() -> ClientState.run("backup rollback"), () -> can(BackupListPayload.CAN_RESTORE));
		y += 28;
		labels.add(new Label("Countdown or running job", x, y + 6, WHITE));
		button("Cancel", "Stop a restore countdown or the running backup/prune/verify job (/backup cancel)", bx, y, 100,
			() -> ClientState.run("backup cancel"), () -> can(BackupListPayload.CAN_CANCEL));
		y += 32;
		labels.add(new Label("Restore the whole world or an area: pick a backup on the Backups tab, then Restore... or Area...", x, y, GRAY));
		labels.add(new Label("Full restores take a pre-restore backup, count down, kick everyone and restart (singleplayer: reopen the world).", x, y + 12, GRAY));
		labels.add(new Label("Area restores run live when nobody is near the area, otherwise they are queued for the next restart.", x, y + 24, GRAY));
	}

	private void initTools() {
		int x = 10, bx = 130, y = contentTop();
		labels.add(new Label("Status", x, y + 6, WHITE));
		button("Show status", "Jobs, schedule, storage and disk space (/backup status)", bx, y, 100, () -> ClientState.run("backup status"),
			() -> can(BackupListPayload.CAN_LIST));
		button("Cancel job", "Stop the running job or restore countdown (/backup cancel)", bx + 104, y, 100, () -> ClientState.run("backup cancel"),
			() -> can(BackupListPayload.CAN_CANCEL));
		button("All commands", "List every /backup command (/backup help)", bx + 208, y, 100, () -> ClientState.run("backup help"), () -> true);
		y += 24;
		labels.add(new Label("Pruning", x, y + 6, WHITE));
		button("Preview", "Show which backups the retention rules would delete, without deleting (/backup prune dryrun)", bx, y, 100,
			() -> ClientState.run("backup prune dryrun"), () -> can(BackupListPayload.CAN_PRUNE));
		button("Prune now...", "Delete backups the retention rules no longer keep (you confirm first)", bx + 104, y, 100, () -> confirm(
			"Prune backups now?", "Backups not kept by the retention rules (Settings > prune) are deleted. Pinned backups are kept. Use Preview first to see the list.",
			"backup prune"), () -> can(BackupListPayload.CAN_PRUNE));
		button("Free space (GC)", "Delete stored data no backup uses any more (/backup gc)", bx + 208, y, 100, () -> ClientState.run("backup gc"),
			() -> can(BackupListPayload.CAN_PRUNE));
		y += 24;
		labels.add(new Label("Import a world", x, y + 6, WHITE));
		int pw = Math.max(80, Math.min(200, width - bx - 10 - 104 - 104 - 8));
		importPath = new EditBox(font, bx, y, pw, 20, Component.literal("Path"));
		importPath.setMaxLength(512);
		importPath.setHint(Component.literal("world folder or .zip"));
		addRenderableWidget(importPath);
		importComment = new EditBox(font, bx + pw + 4, y, 100, 20, Component.literal("Comment"));
		importComment.setMaxLength(200);
		importComment.setHint(Component.literal("comment"));
		addRenderableWidget(importComment);
		button("Import", "Store a world folder or .zip as a backup. Paths are relative to the server (game) folder.", bx + pw + 108, y, 100, () -> {
			String path = importPath.getValue().trim();
			if (path.isEmpty()) return;
			String quoted = path.contains(" ") ? "\"" + path + "\"" : path;
			String comment = importComment.getValue().trim();
			ClientState.run("backup import " + quoted + (comment.isEmpty() ? "" : " " + comment));
		}, () -> can(BackupListPayload.CAN_ADMIN));
		y += 24;
		labels.add(new Label("Off-site copies", x, y + 6, WHITE));
		button("Off-site status", "Upload queue and last sync (/backup offsite status)", bx, y, 100, () -> ClientState.run("backup offsite status"),
			() -> can(BackupListPayload.CAN_ADMIN));
		button("Sync now", "Upload missing backups now (/backup offsite sync)", bx + 104, y, 100, () -> ClientState.run("backup offsite sync"),
			() -> can(BackupListPayload.CAN_ADMIN));
		y += 24;
		labels.add(new Label("Configuration", x, y + 6, WHITE));
		button("Settings...", "Edit every setting in the GUI", bx, y, 100, () -> minecraft.setScreen(new SettingsScreen(this)),
			() -> can(BackupListPayload.CAN_ADMIN));
		button("Reload file", "Re-read config/cytrabackups.json after editing it by hand (/backup reload)", bx + 104, y, 100,
			() -> ClientState.run("backup reload"), () -> can(BackupListPayload.CAN_ADMIN));
	}

	// ------------------------------------------------------------------ actions

	private BackupListPayload.Entry selected() {
		BackupListPayload list = ClientState.list;
		if (list == null) return null;
		for (BackupListPayload.Entry e : list.entries()) if (e.id() == selectedId) return e;
		return null;
	}

	/** Id of the next older backup than the selected one, or -1. The list is newest first. */
	private int previousId() {
		BackupListPayload list = ClientState.list;
		if (list == null) return -1;
		List<BackupListPayload.Entry> es = list.entries();
		for (int i = 0; i < es.size() - 1; i++) if (es.get(i).id() == selectedId) return es.get(i + 1).id();
		return -1;
	}

	private void runOnSelected(String format) {
		if (selected() != null) ClientState.run(String.format(format, selectedId));
	}

	private void diffPrevious() {
		int prev = previousId();
		if (prev > 0 && selected() != null) ClientState.run("backup diff " + prev + " " + selectedId);
	}

	private void compare() {
		BackupListPayload.Entry e = selected();
		if (e == null) return;
		int prev = previousId();
		minecraft.setScreen(new FormScreen(this, "Compare backup #" + e.id(), "Shows files and chunks that differ between the two backups.",
			List.of(new FormScreen.Field("Compare with backup #", prev > 0 ? String.valueOf(prev) : "", "backup number", 10)), "Compare", values -> {
				try {
					int other = Integer.parseInt(values.getFirst().trim());
					ClientState.run("backup diff " + Math.min(other, e.id()) + " " + Math.max(other, e.id()));
				} catch (NumberFormatException ex) {
					ClientState.log(Component.literal("Enter a backup number to compare with."), true);
				}
			}));
	}

	private void editComment() {
		BackupListPayload.Entry e = selected();
		if (e == null) return;
		minecraft.setScreen(new FormScreen(this, "Comment for backup #" + e.id(), "Leave empty to clear the comment.",
			List.of(new FormScreen.Field("Comment", e.comment(), "", 200)), "Save", values -> {
				String c = values.getFirst().trim();
				ClientState.run(c.isEmpty() ? "backup comment " + e.id() : "backup comment " + e.id() + " " + c);
			}));
	}

	/** Client-side confirmation for actions the server does not ask about itself. */
	private void confirm(String title, String details, String command) {
		minecraft.setScreen(new ConfirmScreen(yes -> {
			minecraft.setScreen(this);
			if (yes) ClientState.run(command);
		}, Component.literal(title), Component.literal(details)));
	}

	/** "Suggest command" links (e.g. [Comment] in /backup info) open an editable command box. */
	private void suggest(String command) {
		minecraft.setScreen(new FormScreen(this, "Run command", "Edit the command, then run it.",
			List.of(new FormScreen.Field("Command", command, "", 512)), "Run", values -> ClientState.run(values.getFirst())));
	}

	private static String date(long millis) {
		return Formatting.dateTime(millis, ZoneId.systemDefault());
	}

	private void refresh() {
		for (Runnable r : refreshers) r.run();
	}

	@Override
	public void tick() {
		super.tick();
		if (++ticks % 40 == 0) ClientState.requestList();
		if (seenVersion != ClientState.version) {
			seenVersion = ClientState.version;
			refresh();
		}
	}

	private int visibleRows() {
		return Math.max(1, (listBottom() - listTop()) / ROW);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) return true;
		double mx = event.x(), my = event.y();
		if (log.contains(mx, my)) return log.mouseClicked(font, mx, my);
		BackupListPayload list = ClientState.list;
		if (tab == Tab.BACKUPS && list != null && mx >= 10 && mx <= listRight() && my >= listTop() && my < listBottom()) {
			int row = (int) ((my - listTop()) / ROW) + scroll;
			if (row >= 0 && row < list.entries().size()) {
				int id = list.entries().get(row).id();
				long now = System.currentTimeMillis();
				if (id == lastClickId && now - lastClickAt < 400 && can(BackupListPayload.CAN_LIST)) ClientState.run("backup info " + id); // double-click
				lastClickId = id;
				lastClickAt = now;
				selectedId = id;
				refresh();
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		if (log.mouseScrolled(x, y, scrollY)) return true;
		BackupListPayload list = ClientState.list;
		int max = list == null ? 0 : Math.max(0, list.entries().size() - visibleRows());
		scroll = Math.max(0, Math.min(max, scroll - (int) Math.signum(scrollY) * 3));
		return true;
	}

	// ------------------------------------------------------------------ rendering

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		super.render(g, mouseX, mouseY, partialTick);
		g.drawString(font, title, 10, 8, GOLD);
		BackupListPayload list = ClientState.list;
		if (list != null) {
			String status = list.status() + (list.jobProgress() >= 0 ? " - " + list.jobPhase() + " " + Formatting.percent(list.jobProgress()) : "");
			g.drawString(font, status, width - 10 - font.width(status), 8, GRAY);
			if (list.jobProgress() >= 0) {
				g.fill(10, 18, width - 10, 20, 0xFF303030);
				g.fill(10, 18, 10 + (int) ((width - 20) * list.jobProgress()), 20, 0xFF40C060);
			}
		}
		for (Label l : labels) g.drawString(font, l.text(), l.x(), l.y(), l.color());
		if (tab == Tab.BACKUPS) renderList(g, list, mouseX, mouseY);
		log.render(g, font, mouseX, mouseY);
		g.drawString(font, "Output", 12, logTop() - 10, GRAY);
	}

	private void renderList(GuiGraphics g, BackupListPayload list, int mouseX, int mouseY) {
		int top = listTop(), right = listRight(), bottom = listBottom();
		if (list == null) {
			g.drawCenteredString(font, "Loading backups...", (10 + right) / 2, top + 20, GRAY);
			return;
		}
		if (!list.can(BackupListPayload.CAN_LIST)) {
			g.drawCenteredString(font, "You don't have permission to list backups (cytrabackups.list).", (10 + right) / 2, top + 20, GRAY);
			return;
		}
		int wDate = 50, wTrig = wDate + 118, wSize = wTrig + 78, wComment = wSize + 100;
		g.drawString(font, "#", 14, top - 10, GRAY);
		g.drawString(font, "Date", wDate, top - 10, GRAY);
		g.drawString(font, "Trigger", wTrig, top - 10, GRAY);
		g.drawString(font, "Size (+new)", wSize, top - 10, GRAY);
		if (right - wComment > 30) g.drawString(font, "Comment", wComment, top - 10, GRAY);
		g.fill(10, top - 1, right, bottom, 0x80000000);
		List<BackupListPayload.Entry> entries = list.entries();
		if (entries.isEmpty()) g.drawCenteredString(font, "No backups yet - type a comment below and press Create backup", (10 + right) / 2, top + 10, GRAY);
		g.enableScissor(10, top, right, bottom);
		for (int i = 0; i < visibleRows() && i + scroll < entries.size(); i++) {
			BackupListPayload.Entry e = entries.get(i + scroll);
			int y = top + i * ROW;
			boolean hover = mouseX >= 10 && mouseX <= right && mouseY >= y && mouseY < y + ROW;
			if (e.id() == selectedId) g.fill(10, y, right, y + ROW, 0xFF2E5A88);
			else if (hover) g.fill(10, y, right, y + ROW, 0x40FFFFFF);
			int color = e.partial() ? GRAY : WHITE;
			g.drawString(font, "#" + e.id(), 14, y + 2, color);
			g.drawString(font, date(e.createdAt()), wDate, y + 2, color);
			g.drawString(font, e.trigger() + (e.partial() ? " (area)" : ""), wTrig, y + 2, color);
			g.drawString(font, Formatting.bytes(e.totalSize()) + " (+" + Formatting.bytes(e.newBytes()) + ")", wSize, y + 2, color);
			String comment = (e.pinned() ? "★ " : "") + e.comment();
			int maxW = right - wComment - 4;
			if (maxW > 20) {
				if (font.width(comment) > maxW) comment = font.plainSubstrByWidth(comment, maxW - 6) + "...";
				g.drawString(font, comment, wComment, y + 2, e.pinned() ? GOLD : GRAY);
			}
		}
		g.disableScissor();
		g.drawString(font, "Click to select, double-click for details. Scroll for more.", 10, bottom + 2, 0xFF707070);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
