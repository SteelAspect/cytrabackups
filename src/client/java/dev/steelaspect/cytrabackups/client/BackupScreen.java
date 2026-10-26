package dev.steelaspect.cytrabackups.client;

import dev.steelaspect.alloyui.AlloyScreen;
import dev.steelaspect.alloyui.UiContext;
import dev.steelaspect.alloyui.render.Draw;
import dev.steelaspect.alloyui.widget.Button;
import dev.steelaspect.alloyui.widget.Column;
import dev.steelaspect.alloyui.widget.Container;
import dev.steelaspect.alloyui.widget.FlowRow;
import dev.steelaspect.alloyui.widget.Label;
import dev.steelaspect.alloyui.widget.ScrollPanel;
import dev.steelaspect.alloyui.widget.Tabs;
import dev.steelaspect.alloyui.widget.TextField;
import dev.steelaspect.alloyui.widget.Widget;
import dev.steelaspect.cytrabackups.core.Formatting;
import dev.steelaspect.cytrabackups.net.BackupListPayload;
import java.time.ZoneId;
import java.util.List;
import java.util.function.BooleanSupplier;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Main Alloy Backups screen, in the Nocturne look of Alloy UI. Every button runs the matching /cbackup command on the
 * server (same permissions and behaviour as typing it); the server's replies appear in the output panel and
 * confirmations open a dialog.
 */
public final class BackupScreen extends AlloyScreen {
	// remembered while the game runs, so dialogs and reopening keep the place
	private static int lastTab;
	private static int selectedId = -1;

	private final BackupFrame frame;
	private final Widget[] pages;
	private final BackupsPage backups;
	private long seenVersion = -1;
	private int ticks;

	/** {@code parent}: where Done goes back to, Mod Menu's list or null (back to the game). */
	public BackupScreen(@Nullable Screen parent) {
		super(Component.translatable("cytrabackups.gui.title"), parent);
		backups = new BackupsPage();
		pages = new Widget[] {backups, restorePage(), toolsPage()};
		frame = new BackupFrame(title, BackupScreen::status);
		frame.tabs(new Tabs(() -> List.of(Component.translatable("cytrabackups.gui.tab.backups"), Component.translatable("cytrabackups.gui.tab.restore"),
			Component.translatable("cytrabackups.gui.tab.tools")), () -> lastTab, this::selectTab));
		frame.bottom(new LogPanel(this::suggest));
		frame.button(button("cytrabackups.gui.settings", "cytrabackups.gui.settings.tooltip", () -> minecraft.gui.setScreen(new SettingsScreen(this)),
			() -> can(BackupListPayload.CAN_ADMIN)));
		frame.button(button("cytrabackups.gui.refresh", "cytrabackups.gui.refresh.tooltip", ClientState::requestList, () -> true));
		frame.button(new Button(CommonComponents.GUI_DONE, this::onClose));
		lastTab = Math.max(0, Math.min(2, lastTab));
		frame.body(pages[lastTab]);
		setRoot(frame);
	}

	private void selectTab(int i) {
		lastTab = i;
		frame.body(pages[i]);
	}

	private static Component status() {
		BackupListPayload list = ClientState.list;
		if (list == null) return Component.empty();
		return Component.literal(list.jobProgress() >= 0 ? list.status() + ": " + list.jobPhase() + " " + Formatting.percent(list.jobProgress()) : list.status());
	}

	private static boolean can(int flag) {
		return ClientState.can(flag);
	}

	static Button button(String key, String tooltipKey, Runnable action, BooleanSupplier active) {
		return button(new Button(Component.translatable(key), action), tooltipKey, active);
	}

	static Button button(Button b, String tooltipKey, BooleanSupplier active) {
		b.tooltip(Component.translatable(tooltipKey));
		b.enabledWhen(active);
		return b;
	}

	@Override
	public void tick() {
		if (++ticks % 40 == 0) ClientState.requestList();
		if (seenVersion != ClientState.version) {
			seenVersion = ClientState.version;
			backups.refresh();
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	/** For tests: the centre of the widget labelled {@code label} (a button, or "#id" for a backup row) in GUI units, or null. */
	public double @Nullable [] pointForTest(String label) {
		Widget w = Dialogs.find(root(), label);
		if (w == null) return null;
		return new double[] {(w.x() + w.w() / 2) * uiScale(), (w.y() + w.h() / 2) * uiScale()};
	}

	private static BackupListPayload.@Nullable Entry selected() {
		BackupListPayload list = ClientState.list;
		if (list == null) return null;
		for (BackupListPayload.Entry e : list.entries()) if (e.id() == selectedId) return e;
		return null;
	}

	/** Id of the backup before the selected one, or -1. The list is newest first. */
	private static int previousId() {
		BackupListPayload list = ClientState.list;
		if (list == null) return -1;
		List<BackupListPayload.Entry> es = list.entries();
		for (int i = 0; i < es.size() - 1; i++) if (es.get(i).id() == selectedId) return es.get(i + 1).id();
		return -1;
	}

	private static void runOnSelected(String sub) {
		if (selected() != null) ClientState.run(sub + " " + selectedId);
	}

	private void compare() {
		BackupListPayload.Entry e = selected();
		if (e == null) return;
		int prev = previousId();
		Dialogs.form(Component.translatable("cytrabackups.gui.compare.title", e.id()), Component.empty(),
			List.of(new Dialogs.Field("cytrabackups.gui.compare.field", prev > 0 ? String.valueOf(prev) : "", 10)), values -> {
				try {
					int other = Integer.parseInt(values.getFirst().trim());
					ClientState.run("diff " + Math.min(other, e.id()) + " " + Math.max(other, e.id()));
				} catch (NumberFormatException ex) {
					ClientState.log(Component.translatable("cytrabackups.gui.compare.invalid"), true);
				}
			});
	}

	private void editComment() {
		BackupListPayload.Entry e = selected();
		if (e == null) return;
		Dialogs.form(Component.translatable("cytrabackups.gui.comment.title", e.id()), Component.empty(),
			List.of(new Dialogs.Field("cytrabackups.gui.comment.field", e.comment(), 200)), values -> {
				String c = values.getFirst().trim();
				ClientState.run(c.isEmpty() ? "comment " + e.id() : "comment " + e.id() + " " + c);
			});
	}

	private void confirmPrune() {
		Dialogs.confirm(Component.translatable("cytrabackups.gui.prune.title"), Component.translatable("cytrabackups.gui.prune.message"),
			Component.translatable("cytrabackups.gui.prune"), true, () -> ClientState.run("prune"));
	}

	/** "Suggest command" links (e.g. [Comment] in the info output) open an editable command box. */
	private void suggest(String command) {
		String cmd = command.startsWith("/") ? command.substring(1) : command;
		Dialogs.form(Component.translatable("cytrabackups.gui.command.title"), Component.empty(),
			List.of(new Dialogs.Field("cytrabackups.gui.command.field", cmd, 512)), values -> ClientState.runRaw(values.getFirst()));
	}

	private static ScrollPanel rows(Widget... rows) {
		Column c = new Column(2);
		for (Widget r : rows) c.add(r);
		return new ScrollPanel(c);
	}

	private static Component tr(String key) {
		return Component.translatable(key);
	}

	private Widget restorePage() {
		return rows(
			new ActionRow(tr("cytrabackups.gui.restore.queued"), tr("cytrabackups.gui.restore.queued.tooltip"),
				button("cytrabackups.gui.show", "cytrabackups.gui.restore.show.tooltip", () -> ClientState.run("pending"), () -> can(BackupListPayload.CAN_RESTORE)),
				button("cytrabackups.gui.cancel", "cytrabackups.gui.restore.cancel.tooltip", () -> ClientState.run("pending cancel"), () -> can(BackupListPayload.CAN_RESTORE)),
				button("cytrabackups.gui.apply", "cytrabackups.gui.restore.apply.tooltip", () -> ClientState.run("pending apply"), () -> can(BackupListPayload.CAN_RESTORE))),
			new ActionRow(tr("cytrabackups.gui.restore.undo"), tr("cytrabackups.gui.restore.undo.tooltip"),
				button("cytrabackups.gui.undo", "cytrabackups.gui.restore.undo.button", () -> ClientState.run("rollback"), () -> can(BackupListPayload.CAN_RESTORE))),
			new ActionRow(tr("cytrabackups.gui.restore.job"), tr("cytrabackups.gui.restore.job.tooltip"),
				button("cytrabackups.gui.stop", "cytrabackups.gui.restore.stop.tooltip", () -> ClientState.run("cancel"), () -> can(BackupListPayload.CAN_CANCEL))));
	}

	private Widget toolsPage() {
		TextField importPath = new TextField(() -> "", v -> {}).live().maxLength(512).placeholder(tr("cytrabackups.gui.import.path"));
		TextField importComment = new TextField(() -> "", v -> {}).live().maxLength(200).placeholder(tr("cytrabackups.gui.import.comment"));
		Runnable runImport = () -> {
			String path = importPath.text().trim();
			if (path.isEmpty()) return;
			String quoted = path.contains(" ") ? "\"" + path + "\"" : path;
			String comment = importComment.text().trim();
			ClientState.run("import " + quoted + (comment.isEmpty() ? "" : " " + comment));
		};
		return rows(
			new ActionRow(tr("cytrabackups.gui.tools.status"), tr("cytrabackups.gui.tools.status.tooltip"),
				button("cytrabackups.gui.show", "cytrabackups.gui.tools.show.tooltip", () -> ClientState.run("status"), () -> can(BackupListPayload.CAN_LIST)),
				button("cytrabackups.gui.stop", "cytrabackups.gui.restore.stop.tooltip", () -> ClientState.run("cancel"), () -> can(BackupListPayload.CAN_CANCEL)),
				button("cytrabackups.gui.help", "cytrabackups.gui.tools.help.tooltip", () -> ClientState.run("help"), () -> true)),
			new ActionRow(tr("cytrabackups.gui.tools.prune"), tr("cytrabackups.gui.tools.prune.tooltip"),
				button("cytrabackups.gui.preview", "cytrabackups.gui.tools.preview.tooltip", () -> ClientState.run("prune dryrun"), () -> can(BackupListPayload.CAN_PRUNE)),
				button("cytrabackups.gui.prune", "cytrabackups.gui.tools.prune_now.tooltip", this::confirmPrune, () -> can(BackupListPayload.CAN_PRUNE)),
				button("cytrabackups.gui.free", "cytrabackups.gui.tools.free.tooltip", () -> ClientState.run("gc"), () -> can(BackupListPayload.CAN_PRUNE))),
			new ActionRow(tr("cytrabackups.gui.tools.import"), tr("cytrabackups.gui.tools.import.tooltip"), importPath, importComment,
				button("cytrabackups.gui.import", "cytrabackups.gui.tools.import.button", runImport, () -> can(BackupListPayload.CAN_ADMIN))),
			new ActionRow(tr("cytrabackups.gui.tools.offsite"), tr("cytrabackups.gui.tools.offsite.tooltip"),
				button("cytrabackups.gui.show", "cytrabackups.gui.tools.offsite_status.tooltip", () -> ClientState.run("offsite status"), () -> can(BackupListPayload.CAN_ADMIN)),
				button("cytrabackups.gui.upload", "cytrabackups.gui.tools.upload.tooltip", () -> ClientState.run("offsite sync"), () -> can(BackupListPayload.CAN_ADMIN)),
				button("cytrabackups.gui.list", "cytrabackups.gui.tools.offsite_list.tooltip", () -> ClientState.run("offsite list"), () -> can(BackupListPayload.CAN_ADMIN))),
			new ActionRow(tr("cytrabackups.gui.tools.config"), tr("cytrabackups.gui.tools.config.tooltip"),
				button("cytrabackups.gui.reload", "cytrabackups.gui.tools.reload.tooltip", () -> ClientState.run("reload"), () -> can(BackupListPayload.CAN_ADMIN))));
	}

	/**
	 * The backup list with a "create" line under it, and the selected backup's details and actions beside it (under it
	 * on narrow windows).
	 */
	private final class BackupsPage extends Container {
		private static final float NARROW = 340;

		private final Column listColumn = new Column(2);
		private final ScrollPanel list = add(new ScrollPanel(listColumn));
		private final TextField comment = add(new TextField(() -> "", v -> {}).live().maxLength(200).placeholder(tr("cytrabackups.gui.create.comment")));
		private final Button create = add(button(new Button(tr("cytrabackups.gui.create"), this::create).style(Button.Style.PRIMARY),
			"cytrabackups.gui.create.tooltip", () -> can(BackupListPayload.CAN_CREATE)));
		private final Label heading = add(new Label(() -> {
			BackupListPayload.Entry e = selected();
			return e == null ? tr("cytrabackups.gui.none_selected") : Component.translatable("cytrabackups.gui.selected", e.id());
		}).heading());
		private final Label details = add(new Label(() -> {
			BackupListPayload.Entry e = selected();
			if (e == null) return Component.empty();
			String note = e.comment().isBlank() ? e.trigger() : e.comment();
			return Component.literal(Formatting.dateTimeShort(e.createdAt(), ZoneId.systemDefault()) + ", " + Formatting.bytes(e.totalSize()) + "\n" + note);
		}).color("textMuted"));
		private final FlowRow actions = new FlowRow(BackupFrame.GAP);
		private final ScrollPanel actionsScroll = add(new ScrollPanel(actions)); // scrolls on short windows instead of overlapping the log
		private boolean narrow;

		BackupsPage() {
			comment.onEnter(this::create);
			BooleanSupplier sel = () -> selected() != null;
			action("cytrabackups.gui.info", "cytrabackups.gui.info.tooltip", () -> runOnSelected("info"), () -> sel.getAsBoolean() && can(BackupListPayload.CAN_LIST));
			action("cytrabackups.gui.compare", "cytrabackups.gui.compare.tooltip", BackupScreen.this::compare, () -> sel.getAsBoolean() && can(BackupListPayload.CAN_LIST));
			Button restore = action("cytrabackups.gui.restore", "cytrabackups.gui.restore.tooltip", () -> runOnSelected("restore"),
				() -> sel.getAsBoolean() && can(BackupListPayload.CAN_RESTORE));
			restore.tooltip(() -> {
				BackupListPayload.Entry e = selected();
				return List.of(tr(e != null && e.partial() ? "cytrabackups.gui.restore.area_tooltip" : "cytrabackups.gui.restore.tooltip"));
			});
			action("cytrabackups.gui.area", "cytrabackups.gui.area.tooltip", () -> minecraft.gui.setScreen(new ChunkSelectorScreen(BackupScreen.this, selectedId)),
				() -> sel.getAsBoolean() && can(BackupListPayload.CAN_RESTORE));
			action("cytrabackups.gui.verify", "cytrabackups.gui.verify.tooltip", () -> runOnSelected("verify"), () -> sel.getAsBoolean() && can(BackupListPayload.CAN_VERIFY));
			action("cytrabackups.gui.export", "cytrabackups.gui.export.tooltip", () -> runOnSelected("export"),
				() -> sel.getAsBoolean() && !selected().partial() && can(BackupListPayload.CAN_EXPORT));
			Button pin = new Button(() -> {
				BackupListPayload.Entry e = selected();
				return tr(e != null && e.pinned() ? "cytrabackups.gui.unpin" : "cytrabackups.gui.pin");
			}, () -> runOnSelected(selected().pinned() ? "unpin" : "pin"));
			actions.add(button(pin, "cytrabackups.gui.pin.tooltip", () -> sel.getAsBoolean() && can(BackupListPayload.CAN_PIN)));
			action("cytrabackups.gui.comment", "cytrabackups.gui.comment.tooltip", BackupScreen.this::editComment, () -> sel.getAsBoolean() && can(BackupListPayload.CAN_COMMENT));
			action("cytrabackups.gui.delete", "cytrabackups.gui.delete.tooltip", () -> runOnSelected("delete"),
				() -> sel.getAsBoolean() && !selected().pinned() && can(BackupListPayload.CAN_DELETE));
			refresh();
		}

		private Button action(String key, String tooltipKey, Runnable run, BooleanSupplier active) {
			return actions.add(button(key, tooltipKey, run, active));
		}

		private void create() {
			String c = comment.text().trim();
			ClientState.run(c.isEmpty() ? "create" : "create " + c);
			comment.setText("");
		}

		void refresh() {
			listColumn.clear();
			BackupListPayload payload = ClientState.list;
			if (payload != null && payload.can(BackupListPayload.CAN_LIST)) {
				for (BackupListPayload.Entry e : payload.entries()) listColumn.add(new BackupRow(e));
			}
		}

		@Override
		public float measure(float width) {
			return 0;
		}

		@Override
		protected void layout() {
			narrow = w < NARROW;
			float createH = 16;
			if (narrow) {
				// list, create line, then the heading and as many action rows as fit (the rest scroll)
				float ah = actions.measure(w);
				float listH = Math.max(24, Math.min(h - createH - 3 * BackupFrame.GAP - 12 - 16, h - createH - 3 * BackupFrame.GAP - 12 - ah));
				list.setBounds(x, y, w, listH);
				float cy = y + listH + BackupFrame.GAP;
				layoutCreate(x, cy, w);
				float top = cy + createH + BackupFrame.GAP * 2;
				heading.setBounds(x, top, w, 10);
				details.setBounds(-10000, -10000, 0, 0);
				actionsScroll.setBounds(x, top + 12, w, Math.max(16, y + h - top - 12));
			} else {
				float dw = Math.max(150, Math.min(220, w * 0.36f));
				float dx = x + w - dw;
				float listW = w - dw - BackupFrame.PAD;
				list.setBounds(x, y, listW, Math.max(0, h - createH - BackupFrame.GAP));
				layoutCreate(x, y + h - createH, listW);
				float top = y + 2;
				float hh = heading.measure(dw);
				heading.setBounds(dx, top, dw, hh);
				float dy = top + hh + 4;
				float dh = details.measure(dw);
				// short window: drop the date/comment lines before the buttons have to scroll
				if (dy + dh + 8 + actions.measure(dw) > y + h) {
					details.setBounds(-10000, -10000, 0, 0);
				} else {
					details.setBounds(dx, dy, dw, dh);
					dy += dh + 8;
				}
				actionsScroll.setBounds(dx, dy, dw, Math.max(16, y + h - dy));
			}
		}

		private void layoutCreate(float cx, float cy, float cw) {
			float bw = Math.max(80, create.preferredWidth());
			comment.setBounds(cx, cy, Math.max(40, cw - bw - BackupFrame.GAP), 16);
			create.setBounds(cx + cw - bw, cy, bw, 16);
		}

		@Override
		protected void renderWidget(UiContext ctx) {
			var t = ctx.theme;
			super.renderWidget(ctx);
			BackupListPayload payload = ClientState.list;
			String key = payload == null ? "cytrabackups.gui.loading" : !payload.can(BackupListPayload.CAN_LIST) ? "cytrabackups.gui.no_list"
				: payload.entries().isEmpty() ? "cytrabackups.gui.empty" : null;
			if (key != null) Draw.textCentered(ctx.g, tr(key), list.x() + list.w() / 2, list.y() + 12, t.color("textMuted"));
		}
	}

	/** One backup: "#id date" and the comment, then size and trigger in muted text. Click selects, double click shows info. */
	private final class BackupRow extends Widget {
		private static long lastClick;
		private static int lastClickId = -1;
		private final BackupListPayload.Entry backup;

		BackupRow(BackupListPayload.Entry backup) {
			this.backup = backup;
		}

		@Override
		public float measure(float width) {
			return 24;
		}

		@Override
		public Component searchLabel() {
			return Component.literal("#" + backup.id());
		}

		@Override
		protected void renderWidget(UiContext ctx) {
			var t = ctx.theme;
			boolean sel = backup.id() == selectedId;
			if (sel) Draw.roundRect(ctx.g, x, y, w, h, t.metricF("radius"), t.color("selection"));
			else if (hover.get() > 0) Draw.roundRect(ctx.g, x, y, w, h, t.metricF("radius"), Draw.withAlpha(t.color("text"), 0.05f * hover.get()));
			if (sel) Draw.roundRect(ctx.g, x, y + 4, 2, h - 8, 1, t.color("accent"));
			String date = Formatting.dateTimeShort(backup.createdAt(), ZoneId.systemDefault());
			Component first = Component.literal("#" + backup.id() + "  " + date);
			float fw = Draw.textWidth(first);
			Draw.text(ctx.g, first, x + 6, y + 3, t.color("text"));
			float cx = x + 6 + fw + 6;
			if (backup.pinned()) {
				cx += Draw.tag(ctx.g, tr("cytrabackups.gui.pinned"), cx, y + 2, t.color("tag"), t.color("text")) + 4;
			}
			if (!backup.comment().isBlank() && cx < x + w - 10) {
				Draw.textClipped(ctx.g, Component.literal(backup.comment()), cx, y + 3, x + w - 6 - cx, t.color("textMuted"));
			}
			String second = Formatting.bytes(backup.totalSize()) + ", " + backup.trigger() + (backup.partial() ? " " + tr("cytrabackups.list.area").getString() : "");
			Draw.textClipped(ctx.g, Component.literal(second), x + 6, y + 13, w - 12, t.color("textMuted"));
		}

		@Override
		public boolean mouseClicked(UiContext ctx, double mx, double my, int button) {
			if (button != com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT || !contains(mx, my) || !ctx.inClip(mx, my)) return false;
			long now = System.currentTimeMillis();
			boolean doubleClick = lastClickId == backup.id() && now - lastClick < 400;
			lastClick = now;
			lastClickId = backup.id();
			selectedId = backup.id();
			if (doubleClick && can(BackupListPayload.CAN_LIST)) ClientState.run("info " + backup.id());
			return true;
		}
	}
}
