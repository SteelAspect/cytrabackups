package dev.steelaspect.cytrabackups.client;

import dev.steelaspect.cytrabackups.core.Formatting;
import dev.steelaspect.cytrabackups.net.BackupListPayload;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
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

	private static final int ROW = 13;
	private static final int BTN_H = 18;
	private static final int COL_W = 164; // "Selected backup" card on the Backups tab
	private static final int CARD_H = 34;
	private static final int DETAILS_H = 50; // text lines above the buttons in the "Selected backup" card

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
	private final List<Card> cards = new ArrayList<>();
	private EditBox commentBox, importPath, importComment;

	/** A titled row on the Restore and Tools tabs; its buttons sit on the right. */
	private record Card(String title, String description, int y, int textWidth) {
	}

	public BackupScreen() {
		super(Component.literal("CytraBackups"));
	}

	// ------------------------------------------------------------------ layout

	private int contentTop() {
		return 50;
	}

	private int logHeight() {
		return Math.max(40, Math.min(120, height / 5));
	}

	private int logTop() {
		return height - 26 - logHeight();
	}

	private int contentBottom() {
		return logTop() - 18;
	}

	private int columnX() {
		return width - 14 - COL_W;
	}

	private int listLeft() {
		return 14;
	}

	private int listRight() {
		return columnX() - 8;
	}

	private int listTop() {
		return contentTop() + 18;
	}

	private int listBottom() {
		return contentBottom() - 40;
	}

	// ------------------------------------------------------------------ widgets

	private FlatButton button(String label, String tooltip, int color, int x, int y, int w, Runnable action, BooleanSupplier active) {
		FlatButton b = new FlatButton(x, y, w, BTN_H, label, color, btn -> action.run()).tooltip(tooltip);
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
		cards.clear();
		int tx = 10;
		for (Tab t : Tab.values()) {
			addRenderableWidget(new FlatButton(tx, 27, 76, 18, t.label, Theme.NEUTRAL, b -> switchTab(t)).asTab(t == tab));
			tx += 78;
		}
		FlatButton settings = new FlatButton(tx, 27, 76, 18, "Settings", Theme.NEUTRAL, b -> minecraft.setScreen(new SettingsScreen(this)))
			.asTab(false).tooltip("Edit every CytraBackups setting (needs cytrabackups.admin)");
		addRenderableWidget(settings);
		refreshers.add(() -> settings.active = can(BackupListPayload.CAN_ADMIN));
		switch (tab) {
			case BACKUPS -> initBackups();
			case RESTORE -> initRestore();
			case TOOLS -> initTools();
		}
		button("Refresh", "Reload the backup list", Theme.NEUTRAL, width - 10 - 64 - 4 - 64, height - 22, 64, ClientState::requestList, () -> true);
		button("Close", "", Theme.NEUTRAL, width - 10 - 64, height - 22, 64, this::onClose, () -> true);
		log.setBounds(9, logTop(), width - 18, logHeight());
		refresh();
	}

	private void switchTab(Tab t) {
		tab = t;
		rebuildWidgets();
	}

	private void initBackups() {
		int y = contentBottom() - 24;
		int boxW = Math.max(80, Math.min(260, listRight() - listLeft() - 112));
		commentBox = new EditBox(font, listLeft(), y, boxW, BTN_H, Component.literal("Comment"));
		commentBox.setMaxLength(200);
		commentBox.setHint(Component.literal("Comment for a new backup (optional)"));
		addRenderableWidget(commentBox);
		button("+ Create backup", "Back up the world now (/backup create)", Theme.SUCCESS, listLeft() + boxW + 4, y, 108, () -> {
			String c = commentBox.getValue().trim();
			ClientState.run(c.isEmpty() ? "backup create" : "backup create " + c);
			commentBox.setValue("");
		}, () -> can(BackupListPayload.CAN_CREATE));

		int cx = columnX() + 6, cw = (COL_W - 12 - 4) / 2, cy = contentTop() + 6 + 14 + DETAILS_H;
		BooleanSupplier sel = () -> selected() != null;
		button("Info", "Details and size of the selected backup", Theme.PRIMARY, cx, cy, cw, () -> runOnSelected("backup info %d"),
			() -> sel.getAsBoolean() && can(BackupListPayload.CAN_LIST));
		button("Compare...", "Changed files and chunks between two backups", Theme.PRIMARY, cx + cw + 4, cy, cw, this::compare,
			() -> sel.getAsBoolean() && can(BackupListPayload.CAN_LIST));
		cy += BTN_H + 3;
		button("Restore...", "Restore the whole world to this backup. You confirm first; everyone is kicked and the server restarts.",
			Theme.DANGER, cx, cy, cw, () -> runOnSelected("backup restore %d"),
			() -> sel.getAsBoolean() && !selected().partial() && can(BackupListPayload.CAN_RESTORE));
		button("Area...", "Restore chosen chunks, a region or a radius from this backup (map selector)", Theme.WARNING, cx + cw + 4, cy, cw,
			() -> minecraft.setScreen(new ChunkSelectorScreen(this, selectedId)), () -> sel.getAsBoolean() && can(BackupListPayload.CAN_RESTORE));
		cy += BTN_H + 3;
		button("Verify", "Check every stored piece of this backup against its hash", Theme.PRIMARY, cx, cy, cw, () -> runOnSelected("backup verify %d"),
			() -> sel.getAsBoolean() && can(BackupListPayload.CAN_VERIFY));
		button("Export", "Build a standalone world .zip from this backup (path shown when done)", Theme.PRIMARY, cx + cw + 4, cy, cw,
			() -> runOnSelected("backup export %d"), () -> sel.getAsBoolean() && !selected().partial() && can(BackupListPayload.CAN_EXPORT));
		cy += BTN_H + 3;
		FlatButton pin = button("Pin", "Pinned backups are never pruned", Theme.WARNING, cx, cy, cw,
			() -> runOnSelected(selected().pinned() ? "backup unpin %d" : "backup pin %d"), () -> sel.getAsBoolean() && can(BackupListPayload.CAN_PIN));
		refreshers.add(() -> pin.setLabel(selected() != null && selected().pinned() ? "Unpin" : "Pin"));
		button("Comment...", "Edit or clear the comment", Theme.NEUTRAL, cx + cw + 4, cy, cw, this::editComment,
			() -> sel.getAsBoolean() && can(BackupListPayload.CAN_COMMENT));
		cy += BTN_H + 3;
		button("Diff prev", "Compare with the backup before it", Theme.PRIMARY, cx, cy, cw, this::diffPrevious,
			() -> sel.getAsBoolean() && previousId() > 0 && can(BackupListPayload.CAN_LIST));
		button("Delete...", "Delete this backup (you confirm first). Pinned backups must be unpinned first.", Theme.DANGER, cx + cw + 4, cy, cw,
			() -> runOnSelected("backup delete %d"), () -> sel.getAsBoolean() && !selected().pinned() && can(BackupListPayload.CAN_DELETE));
	}

	/** Adds a card row and returns the x where its right-aligned buttons start, given their total width. */
	private int card(String title, String description, int y, int buttonsWidth) {
		int buttonsX = width - 18 - buttonsWidth;
		cards.add(new Card(title, description, y, buttonsX - 22 - 10));
		return buttonsX;
	}

	private void initRestore() {
		int y = contentTop() + 6;
		int bx = card("Queued restore", "A restore waiting for the next restart (singleplayer: the next time the world opens).", y, 70 + 4 + 76 + 4 + 90);
		int by = y + (CARD_H - BTN_H) / 2;
		button("Show", "Show the queued restore (/backup pending)", Theme.PRIMARY, bx, by, 70, () -> ClientState.run("backup pending"),
			() -> can(BackupListPayload.CAN_RESTORE));
		button("Cancel it", "Drop the queued restore (/backup pending cancel)", Theme.WARNING, bx + 74, by, 76, () -> ClientState.run("backup pending cancel"),
			() -> can(BackupListPayload.CAN_RESTORE));
		button("Apply now...", "Count down, kick everyone and stop the server so the queued restore is applied (you confirm first)", Theme.DANGER,
			bx + 154, by, 90, () -> ClientState.run("backup pending apply"), () -> can(BackupListPayload.CAN_RESTORE));
		y += CARD_H + 4;
		bx = card("Undo the last restore", "Put back the files the last restore replaced, from its recycle bin.", y, 100);
		button("Roll back...", "Roll back the last restore (you confirm first)", Theme.DANGER, bx, y + (CARD_H - BTN_H) / 2, 100,
			() -> ClientState.run("backup rollback"), () -> can(BackupListPayload.CAN_RESTORE));
		y += CARD_H + 4;
		bx = card("Countdown or running job", "Stop a restore countdown, or the running backup, prune or verify.", y, 100);
		button("Cancel", "/backup cancel", Theme.WARNING, bx, y + (CARD_H - BTN_H) / 2, 100, () -> ClientState.run("backup cancel"),
			() -> can(BackupListPayload.CAN_CANCEL));
		y += CARD_H + 4;
		cards.add(new Card("Restoring a backup", "Pick it on the Backups tab: Restore... for the whole world, Area... for chunks. "
			+ "Area restores run live when nobody is near, otherwise at the next restart.", y, width - 22 - 22));
	}

	private void initTools() {
		int y = contentTop() + 6;
		int bx = card("Status", "Running job, next automatic backup, storage and disk space.", y, 3 * 96 + 8);
		int by = y + (CARD_H - BTN_H) / 2;
		button("Show status", "/backup status", Theme.PRIMARY, bx, by, 96, () -> ClientState.run("backup status"), () -> can(BackupListPayload.CAN_LIST));
		button("Cancel job", "Stop the running job or restore countdown (/backup cancel)", Theme.WARNING, bx + 100, by, 96,
			() -> ClientState.run("backup cancel"), () -> can(BackupListPayload.CAN_CANCEL));
		button("All commands", "List every /backup command (/backup help)", Theme.NEUTRAL, bx + 200, by, 96, () -> ClientState.run("backup help"), () -> true);
		y += CARD_H + 4;
		bx = card("Pruning", "Delete backups your retention rules no longer keep. Preview first.", y, 3 * 96 + 8);
		by = y + (CARD_H - BTN_H) / 2;
		button("Preview", "Show what would be deleted, without deleting (/backup prune dryrun)", Theme.PRIMARY, bx, by, 96,
			() -> ClientState.run("backup prune dryrun"), () -> can(BackupListPayload.CAN_PRUNE));
		button("Prune now...", "Delete backups the retention rules no longer keep (you confirm first)", Theme.DANGER, bx + 100, by, 96, () -> confirm(
			"Prune backups now?", "Backups not kept by the retention rules (Settings > Prune) are deleted. Pinned backups are kept. Use Preview first to see the list.",
			"backup prune"), () -> can(BackupListPayload.CAN_PRUNE));
		button("Free space", "Delete stored data no backup uses any more (/backup gc)", Theme.PRIMARY, bx + 200, by, 96, () -> ClientState.run("backup gc"),
			() -> can(BackupListPayload.CAN_PRUNE));
		y += CARD_H + 4;
		int boxes = Math.max(160, Math.min(320, width - 18 - 96 - 4 - 200));
		bx = card("Import a world", "Folder or .zip, relative to the server folder.", y, boxes + 4 + 96);
		by = y + (CARD_H - BTN_H) / 2;
		int pw = boxes * 3 / 5;
		importPath = new EditBox(font, bx, by, pw, BTN_H, Component.literal("Path"));
		importPath.setMaxLength(512);
		importPath.setHint(Component.literal("folder or .zip"));
		addRenderableWidget(importPath);
		importComment = new EditBox(font, bx + pw + 4, by, boxes - pw - 4, BTN_H, Component.literal("Comment"));
		importComment.setMaxLength(200);
		importComment.setHint(Component.literal("comment"));
		addRenderableWidget(importComment);
		button("Import", "Store a world folder or .zip as a backup. Paths are relative to the server (game) folder.", Theme.SUCCESS, bx + boxes + 4, by, 96, () -> {
			String path = importPath.getValue().trim();
			if (path.isEmpty()) return;
			String quoted = path.contains(" ") ? "\"" + path + "\"" : path;
			String comment = importComment.getValue().trim();
			ClientState.run("backup import " + quoted + (comment.isEmpty() ? "" : " " + comment));
		}, () -> can(BackupListPayload.CAN_ADMIN));
		y += CARD_H + 4;
		bx = card("Off-site copies", "S3, SFTP or WebDAV (set up in Settings > Off-site).", y, 2 * 96 + 4);
		by = y + (CARD_H - BTN_H) / 2;
		button("Off-site status", "Upload queue and last sync (/backup offsite status)", Theme.PRIMARY, bx, by, 96,
			() -> ClientState.run("backup offsite status"), () -> can(BackupListPayload.CAN_ADMIN));
		button("Sync now", "Upload missing backups now (/backup offsite sync)", Theme.SUCCESS, bx + 100, by, 96, () -> ClientState.run("backup offsite sync"),
			() -> can(BackupListPayload.CAN_ADMIN));
		y += CARD_H + 4;
		bx = card("Configuration", "Edit settings here, or reload the file after editing it by hand.", y, 2 * 96 + 4);
		by = y + (CARD_H - BTN_H) / 2;
		button("Settings...", "Edit every setting in the GUI", Theme.PRIMARY, bx, by, 96, () -> minecraft.setScreen(new SettingsScreen(this)),
			() -> can(BackupListPayload.CAN_ADMIN));
		button("Reload file", "Re-read config/cytrabackups.json (/backup reload)", Theme.NEUTRAL, bx + 100, by, 96,
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
		minecraft.setScreen(new ConfirmDialog(title, details, "Confirm", Theme.DANGER, yes -> {
			minecraft.setScreen(this);
			if (yes) ClientState.run(command);
		}));
	}

	/** "Suggest command" links (e.g. [Comment] in /backup info) open an editable command box. */
	private void suggest(String command) {
		minecraft.setScreen(new FormScreen(this, "Run command", "Edit the command, then run it.",
			List.of(new FormScreen.Field("Command", command, "", 512)), "Run", values -> ClientState.run(values.getFirst())));
	}

	private static String date(long millis) {
		return Formatting.dateTime(millis, ZoneId.systemDefault());
	}

	static int triggerColor(String trigger) {
		return switch (trigger) {
			case "manual" -> Theme.GREEN_TEXT;
			case "scheduled" -> Theme.ACCENT;
			case "pre-restore" -> Theme.GOLD_TEXT;
			case "import" -> 0xFFB794F4;
			default -> Theme.MUTED;
		};
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
		if (tab == Tab.BACKUPS && list != null && mx >= listLeft() && mx <= listRight() && my >= listTop() && my < listBottom()) {
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
	public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		super.renderBackground(g, mouseX, mouseY, partialTick);
		Theme.header(g, font, width, "CytraBackups", "backup manager");
		Theme.panel(g, 8, contentTop(), width - 16, contentBottom() - contentTop());
		Theme.titledPanel(g, font, 8, logTop() - 14, width - 16, logHeight() + 14, "Output", Theme.TEXT);
		if (tab == Tab.BACKUPS) Theme.titledPanel(g, font, columnX(), contentTop() + 6, COL_W, 14 + DETAILS_H + 5 * (BTN_H + 3) + 4, "Selected backup", Theme.TEXT);
		for (Card c : cards) {
			g.fill(14, c.y(), width - 14, c.y() + CARD_H, Theme.CARD);
			g.fill(14, c.y(), 16, c.y() + CARD_H, Theme.ACCENT);
			g.drawString(font, c.title(), 22, c.y() + (c.description().isEmpty() ? 13 : 7), Theme.TEXT);
			if (!c.description().isEmpty()) g.drawString(font, Theme.ellipsize(font, c.description(), c.textWidth()), 22, c.y() + 19, Theme.MUTED);
		}
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		super.render(g, mouseX, mouseY, partialTick);
		BackupListPayload list = ClientState.list;
		if (list != null) {
			String status = list.status() + (list.jobProgress() >= 0 ? " - " + list.jobPhase() + " " + Formatting.percent(list.jobProgress()) : "");
			g.drawString(font, status, width - 10 - font.width(status), 7, list.jobProgress() >= 0 ? Theme.GREEN_TEXT : Theme.MUTED);
			if (list.jobProgress() >= 0) {
				g.fill(0, 23, width, 25, 0xFF203020);
				g.fill(0, 23, (int) (width * list.jobProgress()), 25, Theme.GREEN_TEXT);
			}
		}
		if (tab == Tab.BACKUPS) {
			renderList(g, list, mouseX, mouseY);
			renderSelected(g);
		}
		log.render(g, font, mouseX, mouseY);
	}

	private void renderSelected(GuiGraphics g) {
		int x = columnX() + 6, y = contentTop() + 6 + 18, w = COL_W - 12;
		BackupListPayload.Entry e = selected();
		if (e == null) {
			g.drawString(font, "Click a backup in the list", x, y + 4, Theme.MUTED);
			g.drawString(font, "to see its actions.", x, y + 16, Theme.MUTED);
			return;
		}
		g.drawString(font, "#" + e.id(), x, y, Theme.TITLE);
		g.drawString(font, Theme.ellipsize(font, date(e.createdAt()), w - font.width("#" + e.id()) - 6), x + font.width("#" + e.id()) + 6, y, Theme.TEXT);
		g.drawString(font, e.trigger() + (e.partial() ? " (area)" : ""), x, y + 11, triggerColor(e.trigger()));
		g.drawString(font, Theme.ellipsize(font, Formatting.bytes(e.totalSize()) + " · +" + Formatting.bytes(e.newBytes()) + " new", w), x, y + 22, Theme.MUTED);
		String comment = e.comment().isBlank() ? (e.pinned() ? "★ pinned" : "no comment") : (e.pinned() ? "★ " : "") + e.comment();
		g.drawString(font, Theme.ellipsize(font, comment, w), x, y + 33, e.comment().isBlank() && !e.pinned() ? Theme.FAINT : e.pinned() ? Theme.GOLD_TEXT : Theme.TEXT);
	}

	private void renderList(GuiGraphics g, BackupListPayload list, int mouseX, int mouseY) {
		int left = listLeft(), top = listTop(), right = listRight(), bottom = listBottom();
		if (list == null) {
			g.drawCenteredString(font, "Loading backups...", (left + right) / 2, top + 20, Theme.MUTED);
			return;
		}
		if (!list.can(BackupListPayload.CAN_LIST)) {
			g.drawCenteredString(font, "You don't have permission to list backups (cytrabackups.list).", (left + right) / 2, top + 20, Theme.MUTED);
			return;
		}
		int cDate = left + 34, cTrig = cDate + font.width("0000-00-00 00:00:00") + 10, cSize = cTrig + font.width("pre-restore*") + 10,
			cComment = cSize + font.width("000.0 MiB") + 14;
		g.fill(left, top - 14, right, top - 1, Theme.HEADER);
		g.drawString(font, "#", left + 4, top - 11, Theme.MUTED);
		g.drawString(font, "Date", cDate, top - 11, Theme.MUTED);
		g.drawString(font, "Trigger", cTrig, top - 11, Theme.MUTED);
		g.drawString(font, "Size", cSize, top - 11, Theme.MUTED);
		if (right - cComment > 40) g.drawString(font, "Comment", cComment, top - 11, Theme.MUTED);
		List<BackupListPayload.Entry> entries = list.entries();
		if (entries.isEmpty()) g.drawCenteredString(font, "No backups yet - press + Create backup", (left + right) / 2, top + 12, Theme.MUTED);
		g.enableScissor(left, top, right, bottom);
		for (int i = 0; i < visibleRows() && i + scroll < entries.size(); i++) {
			BackupListPayload.Entry e = entries.get(i + scroll);
			int y = top + i * ROW;
			boolean hover = mouseX >= left && mouseX <= right && mouseY >= y && mouseY < y + ROW;
			int bg = e.id() == selectedId ? Theme.SELECTED_ROW : hover ? 0x40FFFFFF : ((i + scroll) % 2 == 0 ? 0x30000000 : 0x18FFFFFF);
			g.fill(left, y, right, y + ROW, bg);
			if (e.id() == selectedId) g.fill(left, y, left + 2, y + ROW, Theme.ACCENT);
			int text = e.partial() ? Theme.MUTED : Theme.TEXT;
			g.drawString(font, "#" + e.id(), left + 4, y + 3, Theme.TITLE);
			g.drawString(font, date(e.createdAt()), cDate, y + 3, text);
			g.drawString(font, e.trigger() + (e.partial() ? "*" : ""), cTrig, y + 3, triggerColor(e.trigger()));
			g.drawString(font, Formatting.bytes(e.totalSize()), cSize, y + 3, text);
			int maxW = right - cComment - 4;
			if (maxW > 40) {
				String comment = (e.pinned() ? "★ " : "") + e.comment();
				g.drawString(font, Theme.ellipsize(font, comment, maxW), cComment, y + 3, e.pinned() ? Theme.GOLD_TEXT : Theme.MUTED);
			}
		}
		g.disableScissor();
		int total = entries.size();
		if (total > visibleRows()) {
			int h = bottom - top, barH = Math.max(8, h * visibleRows() / total);
			int barY = top + (h - barH) * scroll / Math.max(1, total - visibleRows());
			g.fill(right - 2, barY, right, barY + barH, Theme.ACCENT);
		}
		g.drawString(font, total + " backups · click to select, double-click for details" + (entries.stream().anyMatch(BackupListPayload.Entry::partial)
			? " · * = area backup" : ""), left, bottom + 3, Theme.FAINT);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
