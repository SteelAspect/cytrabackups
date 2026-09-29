package dev.steelaspect.cytrabackups.client;

import dev.steelaspect.cytrabackups.core.Formatting;
import dev.steelaspect.cytrabackups.net.BackupListPayload;
import dev.steelaspect.cytrabackups.net.RequestPayload;
import java.time.ZoneId;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** Lists backups and offers create / restore / partial restore / pin / delete. All actions are checked server-side. */
public final class BackupListScreen extends Screen {
	private static final int ROW = 12;
	private static final int WHITE = 0xFFFFFFFF;
	private static final int GRAY = 0xFFA0A0A0;
	private static final int GOLD = 0xFFFFAA00;

	private int selectedId = -1;
	private int scroll;
	private long seenVersion = -1;
	private int ticks;
	private EditBox commentBox;
	private Button createButton, restoreButton, partialButton, pinButton, deleteButton;

	public BackupListScreen() {
		super(Component.literal("CytraBackups"));
	}

	private int listTop() {
		return 34;
	}

	private int listBottom() {
		return height - 80;
	}

	@Override
	protected void init() {
		int y = height - 72;
		commentBox = new EditBox(font, 10, y, Math.min(260, width - 130), 20, Component.literal("Comment"));
		commentBox.setMaxLength(200);
		commentBox.setHint(Component.literal("Comment for a new backup (optional)"));
		addRenderableWidget(commentBox);
		createButton = addRenderableWidget(Button.builder(Component.literal("Create backup"), b -> {
			ClientState.send(new RequestPayload(RequestPayload.CREATE, 0, commentBox.getValue(), "", 0, 0, 0, 0));
			commentBox.setValue("");
		}).bounds(Math.min(280, width - 115), y, 105, 20).tooltip(Tooltip.create(Component.literal("Runs /backup create on the server"))).build());

		int by = height - 46;
		int n = 6;
		int w = Math.min(100, (width - 20 - (n - 1) * 4) / n);
		int x = (width - (n * w + (n - 1) * 4)) / 2;
		restoreButton = addRenderableWidget(Button.builder(Component.literal("Restore..."), b -> confirmRestore()).bounds(x, by, w, 20)
			.tooltip(Tooltip.create(Component.literal("Restore the whole world. Everyone is kicked and the server restarts."))).build());
		partialButton = addRenderableWidget(Button.builder(Component.literal("Chunks..."), b -> minecraft.setScreen(new ChunkSelectorScreen(this, selectedId)))
			.bounds(x + (w + 4), by, w, 20).tooltip(Tooltip.create(Component.literal("Restore selected chunks only (map selector)"))).build());
		pinButton = addRenderableWidget(Button.builder(Component.literal("Pin"), b -> togglePin()).bounds(x + 2 * (w + 4), by, w, 20).build());
		deleteButton = addRenderableWidget(Button.builder(Component.literal("Delete..."), b -> confirmDelete()).bounds(x + 3 * (w + 4), by, w, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Refresh"), b -> ClientState.requestList()).bounds(x + 4 * (w + 4), by, w, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose()).bounds(x + 5 * (w + 4), by, w, 20).build());
		updateButtons();
	}

	private BackupListPayload.Entry selected() {
		BackupListPayload list = ClientState.list;
		if (list == null) return null;
		for (BackupListPayload.Entry e : list.entries()) if (e.id() == selectedId) return e;
		return null;
	}

	private void updateButtons() {
		BackupListPayload list = ClientState.list;
		BackupListPayload.Entry sel = selected();
		boolean hasSel = sel != null;
		createButton.active = list != null && list.can(BackupListPayload.CAN_CREATE);
		restoreButton.active = hasSel && list.can(BackupListPayload.CAN_RESTORE) && !sel.partial();
		partialButton.active = hasSel && list.can(BackupListPayload.CAN_RESTORE);
		pinButton.active = hasSel && list.can(BackupListPayload.CAN_PIN);
		pinButton.setMessage(Component.literal(hasSel && sel.pinned() ? "Unpin" : "Pin"));
		deleteButton.active = hasSel && list.can(BackupListPayload.CAN_DELETE) && !sel.pinned();
	}

	private void confirmRestore() {
		BackupListPayload.Entry e = selected();
		if (e == null) return;
		minecraft.setScreen(new ConfirmScreen(yes -> {
			if (yes) ClientState.send(RequestPayload.simple(RequestPayload.RESTORE, e.id()));
			minecraft.setScreen(this);
		}, Component.literal("Restore backup #" + e.id() + "?"), Component.literal("The server takes a pre-restore backup, counts down, kicks everyone and "
			+ "restarts to restore the world to " + date(e.createdAt()) + ".")));
	}

	private void confirmDelete() {
		BackupListPayload.Entry e = selected();
		if (e == null) return;
		minecraft.setScreen(new ConfirmScreen(yes -> {
			if (yes) ClientState.send(RequestPayload.simple(RequestPayload.DELETE, e.id()));
			minecraft.setScreen(this);
		}, Component.literal("Delete backup #" + e.id() + "?"), Component.literal("This cannot be undone.")));
	}

	private void togglePin() {
		BackupListPayload.Entry e = selected();
		if (e == null) return;
		ClientState.send(RequestPayload.simple(e.pinned() ? RequestPayload.UNPIN : RequestPayload.PIN, e.id()));
	}

	private static String date(long millis) {
		return Formatting.dateTime(millis, ZoneId.systemDefault());
	}

	@Override
	public void tick() {
		super.tick();
		if (++ticks % 40 == 0) ClientState.requestList();
		if (seenVersion != ClientState.version) {
			seenVersion = ClientState.version;
			updateButtons();
		}
	}

	private int visibleRows() {
		return Math.max(1, (listBottom() - listTop()) / ROW);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) return true;
		double mx = event.x(), my = event.y();
		BackupListPayload list = ClientState.list;
		if (list != null && mx >= 10 && mx <= width - 10 && my >= listTop() && my < listBottom()) {
			int row = (int) ((my - listTop()) / ROW) + scroll;
			if (row >= 0 && row < list.entries().size()) {
				selectedId = list.entries().get(row).id();
				updateButtons();
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		BackupListPayload list = ClientState.list;
		int max = list == null ? 0 : Math.max(0, list.entries().size() - visibleRows());
		scroll = Math.max(0, Math.min(max, scroll - (int) Math.signum(scrollY) * 3));
		return true;
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		super.render(g, mouseX, mouseY, partialTick);
		g.drawString(font, title, 10, 8, GOLD);
		BackupListPayload list = ClientState.list;
		if (list == null) {
			g.drawCenteredString(font, "Loading backups...", width / 2, height / 2 - 20, GRAY);
			return;
		}
		String status = list.status() + (list.jobProgress() >= 0 ? " - " + list.jobPhase() + " " + Formatting.percent(list.jobProgress()) : "");
		g.drawString(font, status, width - 10 - font.width(status), 8, GRAY);
		if (list.jobProgress() >= 0) {
			g.fill(10, 20, width - 10, 23, 0xFF303030);
			g.fill(10, 20, 10 + (int) ((width - 20) * list.jobProgress()), 23, 0xFF40C060);
		}
		int top = listTop();
		g.drawString(font, "#", 14, top - 10, GRAY);
		g.drawString(font, "Date", 50, top - 10, GRAY);
		g.drawString(font, "Trigger", 170, top - 10, GRAY);
		g.drawString(font, "Size (+new)", 245, top - 10, GRAY);
		g.drawString(font, "Comment", 350, top - 10, GRAY);
		g.fill(10, top - 1, width - 10, listBottom(), 0x80000000);
		List<BackupListPayload.Entry> entries = list.entries();
		if (entries.isEmpty()) g.drawCenteredString(font, "No backups yet", width / 2, top + 10, GRAY);
		g.enableScissor(10, top, width - 10, listBottom());
		for (int i = 0; i < visibleRows() && i + scroll < entries.size(); i++) {
			BackupListPayload.Entry e = entries.get(i + scroll);
			int y = top + i * ROW;
			boolean hover = mouseX >= 10 && mouseX <= width - 10 && mouseY >= y && mouseY < y + ROW;
			if (e.id() == selectedId) g.fill(10, y, width - 10, y + ROW, 0xFF2E5A88);
			else if (hover) g.fill(10, y, width - 10, y + ROW, 0x40FFFFFF);
			int color = e.partial() ? GRAY : WHITE;
			g.drawString(font, "#" + e.id(), 14, y + 2, color);
			g.drawString(font, date(e.createdAt()), 50, y + 2, color);
			g.drawString(font, e.trigger() + (e.partial() ? " (area)" : ""), 170, y + 2, color);
			g.drawString(font, Formatting.bytes(e.totalSize()) + " (+" + Formatting.bytes(e.newBytes()) + ")", 245, y + 2, color);
			String comment = (e.pinned() ? "★ " : "") + e.comment();
			int maxW = width - 20 - 350;
			if (maxW > 20 && font.width(comment) > maxW) comment = font.plainSubstrByWidth(comment, maxW - 6) + "...";
			if (maxW > 20) g.drawString(font, comment, 350, y + 2, e.pinned() ? GOLD : GRAY);
		}
		g.disableScissor();
		if (!ClientState.message.isEmpty() && System.currentTimeMillis() - ClientState.messageAt < 15_000) {
			String msg = ClientState.message;
			if (font.width(msg) > width - 20) msg = font.plainSubstrByWidth(msg, width - 26) + "...";
			g.drawCenteredString(font, msg, width / 2, height - 20, ClientState.messageError ? 0xFFFF5555 : 0xFF55FF55);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
