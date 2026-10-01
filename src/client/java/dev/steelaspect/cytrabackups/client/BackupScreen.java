package dev.steelaspect.cytrabackups.client;

import dev.steelaspect.cytrabackups.core.Formatting;
import dev.steelaspect.cytrabackups.net.BackupListPayload;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.tabs.Tab;
import net.minecraft.client.gui.components.tabs.TabManager;
import net.minecraft.client.gui.components.tabs.TabNavigationBar;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * Main CytraBackups screen. Every button runs the matching /cbackup command on the server (same permissions and
 * behaviour as typing it); the server's replies appear in the output panel and confirmations open a dialog.
 */
public final class BackupScreen extends Screen {
	private static final int BUTTON_W = 98;
	private static final int ROW_H = 24;
	private static final int SMALL_W = 74;

	// remembered while the game runs, so dialogs and reopening keep the place
	private static int lastTab;
	private static int selectedId = -1;

	private final HeaderAndFooterLayout layout = new HeaderAndFooterLayout(this);
	private final TabManager tabManager = new TabManager(this::addRenderableWidget, this::removeWidget);
	private final LogPanel log = new LogPanel(this::suggest);
	private final List<Runnable> refreshers = new ArrayList<>();
	private TabNavigationBar tabBar;
	private BackupsTab backupsTab;
	private long seenVersion = -1;
	private int ticks;
	private int logTop;

	public BackupScreen() {
		super(Component.translatable("cytrabackups.gui.title"));
	}

	/** The text, cut with "..." when wider than {@code maxWidth}. */
	static FormattedCharSequence clip(Font font, Component text, int maxWidth) {
		return font.width(text) <= maxWidth ? text.getVisualOrderText() : StringWidget.clipText(text, font, Math.max(maxWidth, font.width(CommonComponents.ELLIPSIS)));
	}

	private static boolean can(int flag) {
		return ClientState.can(flag);
	}

	private Button button(String key, String tooltipKey, int width, Runnable action, BooleanSupplier active) {
		Button b = Button.builder(Component.translatable(key), btn -> action.run()).width(width)
			.tooltip(Tooltip.create(Component.translatable(tooltipKey))).build();
		refreshers.add(() -> b.active = active.getAsBoolean());
		return b;
	}

	private static StringWidget label(String key) {
		return new StringWidget(Component.translatable(key), Minecraft.getInstance().font);
	}

	@Override
	protected void init() {
		refreshers.clear();
		backupsTab = new BackupsTab();
		tabBar = TabNavigationBar.builder(tabManager, width).addTabs(backupsTab, new RestoreTab(), new ToolsTab()).build();
		addRenderableWidget(tabBar);
		LinearLayout footer = layout.addToFooter(LinearLayout.horizontal().spacing(8));
		footer.addChild(button("cytrabackups.gui.settings", "cytrabackups.gui.settings.tooltip", BUTTON_W,
			() -> minecraft.setScreen(new SettingsScreen(this)), () -> can(BackupListPayload.CAN_ADMIN)));
		footer.addChild(button("cytrabackups.gui.refresh", "cytrabackups.gui.refresh.tooltip", BUTTON_W, ClientState::requestList, () -> true));
		footer.addChild(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).width(BUTTON_W).build());
		layout.visitWidgets(this::addRenderableWidget);
		tabBar.selectTab(Math.min(lastTab, 2), false);
		repositionElements();
		refresh();
	}

	@Override
	protected void repositionElements() {
		if (tabBar == null) return;
		tabBar.setWidth(width);
		tabBar.arrangeElements();
		int top = tabBar.getRectangle().bottom();
		layout.setHeaderHeight(top);
		layout.arrangeElements();
		int logH = Math.max(36, Math.min(100, height / 5));
		logTop = height - layout.getFooterHeight() - logH - 6;
		tabManager.setTabArea(new ScreenRectangle(0, top, width, logTop - 14 - top));
		log.setBounds(10, logTop, width - 20, logH);
	}

	@Override
	public void removed() {
		Tab current = tabManager.getCurrentTab();
		if (current != null && tabBar != null) lastTab = Math.max(0, tabBar.getTabs().indexOf(current));
	}

	private void refresh() {
		if (backupsTab != null) backupsTab.list.refresh();
		refreshSelection();
	}

	private void refreshSelection() {
		for (Runnable r : refreshers) r.run();
		if (backupsTab != null) backupsTab.update();
	}

	@Override
	public void tick() {
		if (++ticks % 40 == 0) ClientState.requestList();
		if (seenVersion != ClientState.version) {
			seenVersion = ClientState.version;
			refresh();
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (log.contains(event.x(), event.y())) return log.mouseClicked(font, event.x(), event.y());
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		return log.mouseScrolled(x, y, scrollY) || super.mouseScrolled(x, y, scrollX, scrollY);
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		super.render(g, mouseX, mouseY, partialTick);
		g.blit(RenderPipelines.GUI_TEXTURED, minecraft.level == null ? FOOTER_SEPARATOR : INWORLD_FOOTER_SEPARATOR, 0,
			height - layout.getFooterHeight() - 2, 0, 0, width, 2, 32, 2);
		BackupListPayload list = ClientState.list;
		if (list != null) {
			String status = list.jobProgress() >= 0 ? list.status() + ": " + list.jobPhase() + " " + Formatting.percent(list.jobProgress()) : list.status();
			g.drawString(font, clip(font, Component.literal(status), width - 20), 10, logTop - 11, 0xFFA0A0A0);
		}
		log.render(g, font, mouseX, mouseY);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	private static BackupListPayload.Entry selected() {
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
		minecraft.setScreen(new FormScreen(this, Component.translatable("cytrabackups.gui.compare.title", e.id()),
			List.of(new FormScreen.Field("cytrabackups.gui.compare.field", prev > 0 ? String.valueOf(prev) : "", 10)), values -> {
				try {
					int other = Integer.parseInt(values.getFirst().trim());
					ClientState.run("diff " + Math.min(other, e.id()) + " " + Math.max(other, e.id()));
				} catch (NumberFormatException ex) {
					ClientState.log(Component.translatable("cytrabackups.gui.compare.invalid"), true);
				}
			}));
	}

	private void editComment() {
		BackupListPayload.Entry e = selected();
		if (e == null) return;
		minecraft.setScreen(new FormScreen(this, Component.translatable("cytrabackups.gui.comment.title", e.id()),
			List.of(new FormScreen.Field("cytrabackups.gui.comment.field", e.comment(), 200)), values -> {
				String c = values.getFirst().trim();
				ClientState.run(c.isEmpty() ? "comment " + e.id() : "comment " + e.id() + " " + c);
			}));
	}

	private void confirmPrune() {
		minecraft.setScreen(new ConfirmScreen(yes -> {
			minecraft.setScreen(this);
			if (yes) ClientState.run("prune");
		}, Component.translatable("cytrabackups.gui.prune.title"), Component.translatable("cytrabackups.gui.prune.message")));
	}

	/** "Suggest command" links (e.g. [Comment] in the info output) open an editable command box. */
	private void suggest(String command) {
		String cmd = command.startsWith("/") ? command.substring(1) : command;
		minecraft.setScreen(new FormScreen(this, Component.translatable("cytrabackups.gui.command.title"),
			List.of(new FormScreen.Field("cytrabackups.gui.command.field", cmd, 512)), values -> ClientState.runRaw(values.getFirst())));
	}

	/** A tab made of absolutely positioned widgets. */
	private abstract static class WidgetTab implements Tab {
		private final Component title;
		protected final List<AbstractWidget> widgets = new ArrayList<>();

		WidgetTab(String key) {
			title = Component.translatable(key);
		}

		<T extends AbstractWidget> T add(T widget) {
			widgets.add(widget);
			return widget;
		}

		@Override
		public Component getTabTitle() {
			return title;
		}

		@Override
		public Component getTabExtraNarration() {
			return Component.empty();
		}

		@Override
		public void visitChildren(Consumer<AbstractWidget> consumer) {
			widgets.forEach(consumer);
		}
	}

	/** Rows of a label and buttons, centred in the tab area. */
	private abstract class RowsTab extends WidgetTab {
		private final List<List<AbstractWidget>> rows = new ArrayList<>();

		RowsTab(String key) {
			super(key);
		}

		void row(String labelKey, String tooltipKey, AbstractWidget... controls) {
			StringWidget label = add(label(labelKey));
			label.setTooltip(Tooltip.create(Component.translatable(tooltipKey)));
			List<AbstractWidget> row = new ArrayList<>();
			row.add(label);
			for (AbstractWidget c : controls) row.add(add(c));
			rows.add(row);
		}

		@Override
		public void doLayout(ScreenRectangle area) {
			int controlsW = 0, labelW = 0;
			for (List<AbstractWidget> row : rows) {
				labelW = Math.max(labelW, font.width(row.getFirst().getMessage()));
				int w = 0;
				for (AbstractWidget c : row.subList(1, row.size())) w += c.getWidth() + 4;
				controlsW = Math.max(controlsW, w - 4);
			}
			int total = Math.min(area.width() - 20, labelW + 12 + controlsW);
			int x = area.left() + (area.width() - total) / 2;
			int rowH = Math.max(22, Math.min(ROW_H, (area.height() - 4) / rows.size()));
			int y = area.top() + Math.max(2, Math.min(8, (area.height() - rows.size() * rowH) / 3));
			for (List<AbstractWidget> row : rows) {
				StringWidget label = (StringWidget) row.getFirst();
				label.setMaxWidth(Math.max(40, total - controlsW - 12));
				label.setPosition(x, y + 6);
				int cx = x + total - controlsW;
				for (AbstractWidget c : row.subList(1, row.size())) {
					c.setPosition(cx, y);
					cx += c.getWidth() + 4;
				}
				y += rowH;
			}
		}
	}

	private final class RestoreTab extends RowsTab {
		RestoreTab() {
			super("cytrabackups.gui.tab.restore");
			row("cytrabackups.gui.restore.queued", "cytrabackups.gui.restore.queued.tooltip",
				button("cytrabackups.gui.show", "cytrabackups.gui.restore.show.tooltip", SMALL_W, () -> ClientState.run("pending"), () -> can(BackupListPayload.CAN_RESTORE)),
				button("cytrabackups.gui.cancel", "cytrabackups.gui.restore.cancel.tooltip", SMALL_W, () -> ClientState.run("pending cancel"), () -> can(BackupListPayload.CAN_RESTORE)),
				button("cytrabackups.gui.apply", "cytrabackups.gui.restore.apply.tooltip", SMALL_W, () -> ClientState.run("pending apply"), () -> can(BackupListPayload.CAN_RESTORE)));
			row("cytrabackups.gui.restore.undo", "cytrabackups.gui.restore.undo.tooltip",
				button("cytrabackups.gui.undo", "cytrabackups.gui.restore.undo.button", SMALL_W, () -> ClientState.run("rollback"), () -> can(BackupListPayload.CAN_RESTORE)));
			row("cytrabackups.gui.restore.job", "cytrabackups.gui.restore.job.tooltip",
				button("cytrabackups.gui.stop", "cytrabackups.gui.restore.stop.tooltip", SMALL_W, () -> ClientState.run("cancel"), () -> can(BackupListPayload.CAN_CANCEL)));
		}
	}

	private final class ToolsTab extends RowsTab {
		private final EditBox importPath = new EditBox(font, 0, 0, 120, 20, Component.translatable("cytrabackups.gui.import.path"));
		private final EditBox importComment = new EditBox(font, 0, 0, 80, 20, Component.translatable("cytrabackups.gui.import.comment"));

		ToolsTab() {
			super("cytrabackups.gui.tab.tools");
			row("cytrabackups.gui.tools.status", "cytrabackups.gui.tools.status.tooltip",
				button("cytrabackups.gui.show", "cytrabackups.gui.tools.show.tooltip", SMALL_W, () -> ClientState.run("status"), () -> can(BackupListPayload.CAN_LIST)),
				button("cytrabackups.gui.stop", "cytrabackups.gui.restore.stop.tooltip", SMALL_W, () -> ClientState.run("cancel"), () -> can(BackupListPayload.CAN_CANCEL)),
				button("cytrabackups.gui.help", "cytrabackups.gui.tools.help.tooltip", SMALL_W, () -> ClientState.run("help"), () -> true));
			row("cytrabackups.gui.tools.prune", "cytrabackups.gui.tools.prune.tooltip",
				button("cytrabackups.gui.preview", "cytrabackups.gui.tools.preview.tooltip", SMALL_W, () -> ClientState.run("prune dryrun"), () -> can(BackupListPayload.CAN_PRUNE)),
				button("cytrabackups.gui.prune", "cytrabackups.gui.tools.prune_now.tooltip", SMALL_W, BackupScreen.this::confirmPrune, () -> can(BackupListPayload.CAN_PRUNE)),
				button("cytrabackups.gui.free", "cytrabackups.gui.tools.free.tooltip", SMALL_W, () -> ClientState.run("gc"), () -> can(BackupListPayload.CAN_PRUNE)));
			importPath.setMaxLength(512);
			importPath.setHint(Component.translatable("cytrabackups.gui.import.path").withStyle(ChatFormatting.DARK_GRAY));
			importComment.setMaxLength(200);
			importComment.setHint(Component.translatable("cytrabackups.gui.import.comment").withStyle(ChatFormatting.DARK_GRAY));
			row("cytrabackups.gui.tools.import", "cytrabackups.gui.tools.import.tooltip", importPath, importComment,
				button("cytrabackups.gui.import", "cytrabackups.gui.tools.import.button", SMALL_W, this::runImport, () -> can(BackupListPayload.CAN_ADMIN)));
			row("cytrabackups.gui.tools.offsite", "cytrabackups.gui.tools.offsite.tooltip",
				button("cytrabackups.gui.show", "cytrabackups.gui.tools.offsite_status.tooltip", SMALL_W, () -> ClientState.run("offsite status"), () -> can(BackupListPayload.CAN_ADMIN)),
				button("cytrabackups.gui.upload", "cytrabackups.gui.tools.upload.tooltip", SMALL_W, () -> ClientState.run("offsite sync"), () -> can(BackupListPayload.CAN_ADMIN)),
				button("cytrabackups.gui.list", "cytrabackups.gui.tools.offsite_list.tooltip", SMALL_W, () -> ClientState.run("offsite list"), () -> can(BackupListPayload.CAN_ADMIN)));
			row("cytrabackups.gui.tools.config", "cytrabackups.gui.tools.config.tooltip",
				button("cytrabackups.gui.reload", "cytrabackups.gui.tools.reload.tooltip", SMALL_W, () -> ClientState.run("reload"), () -> can(BackupListPayload.CAN_ADMIN)));
		}

		private void runImport() {
			String path = importPath.getValue().trim();
			if (path.isEmpty()) return;
			String quoted = path.contains(" ") ? "\"" + path + "\"" : path;
			String comment = importComment.getValue().trim();
			ClientState.run("import " + quoted + (comment.isEmpty() ? "" : " " + comment));
		}

		@Override
		public void doLayout(ScreenRectangle area) {
			int boxes = Math.max(100, Math.min(220, area.width() - 20 - 110 - SMALL_W - 4));
			importPath.setWidth(boxes * 3 / 5);
			importComment.setWidth(boxes - boxes * 3 / 5 - 4);
			super.doLayout(area);
		}
	}

	private final class BackupsTab extends WidgetTab {
		private final BackupList list = add(new BackupList());
		private final EditBox comment = add(new EditBox(font, 0, 0, 100, 20, Component.translatable("cytrabackups.gui.create.comment")));
		private final Button create = add(button("cytrabackups.gui.create", "cytrabackups.gui.create.tooltip", 100, this::create, () -> can(BackupListPayload.CAN_CREATE)));
		private final StringWidget heading = add(new StringWidget(Component.empty(), font));
		private final StringWidget details = add(new StringWidget(Component.empty(), font));
		private final StringWidget note = add(new StringWidget(Component.empty(), font));
		private final List<Button> actions = new ArrayList<>();
		private Button restore, pin;

		BackupsTab() {
			super("cytrabackups.gui.tab.backups");
			comment.setMaxLength(200);
			comment.setHint(Component.translatable("cytrabackups.gui.create.comment").withStyle(ChatFormatting.DARK_GRAY));
			BooleanSupplier sel = () -> selected() != null;
			action("cytrabackups.gui.info", "cytrabackups.gui.info.tooltip", () -> runOnSelected("info"), () -> sel.getAsBoolean() && can(BackupListPayload.CAN_LIST));
			action("cytrabackups.gui.compare", "cytrabackups.gui.compare.tooltip", BackupScreen.this::compare, () -> sel.getAsBoolean() && can(BackupListPayload.CAN_LIST));
			restore = action("cytrabackups.gui.restore", "cytrabackups.gui.restore.tooltip", () -> runOnSelected("restore"),
				() -> sel.getAsBoolean() && can(BackupListPayload.CAN_RESTORE));
			action("cytrabackups.gui.area", "cytrabackups.gui.area.tooltip", () -> minecraft.setScreen(new ChunkSelectorScreen(BackupScreen.this, selectedId)),
				() -> sel.getAsBoolean() && can(BackupListPayload.CAN_RESTORE));
			action("cytrabackups.gui.verify", "cytrabackups.gui.verify.tooltip", () -> runOnSelected("verify"), () -> sel.getAsBoolean() && can(BackupListPayload.CAN_VERIFY));
			action("cytrabackups.gui.export", "cytrabackups.gui.export.tooltip", () -> runOnSelected("export"),
				() -> sel.getAsBoolean() && !selected().partial() && can(BackupListPayload.CAN_EXPORT));
			pin = action("cytrabackups.gui.pin", "cytrabackups.gui.pin.tooltip", () -> runOnSelected(selected().pinned() ? "unpin" : "pin"),
				() -> sel.getAsBoolean() && can(BackupListPayload.CAN_PIN));
			action("cytrabackups.gui.comment", "cytrabackups.gui.comment.tooltip", BackupScreen.this::editComment, () -> sel.getAsBoolean() && can(BackupListPayload.CAN_COMMENT));
			action("cytrabackups.gui.delete", "cytrabackups.gui.delete.tooltip", () -> runOnSelected("delete"),
				() -> sel.getAsBoolean() && !selected().pinned() && can(BackupListPayload.CAN_DELETE));
		}

		private Button action(String key, String tooltipKey, Runnable run, BooleanSupplier active) {
			Button b = add(button(key, tooltipKey, 73, run, active));
			actions.add(b);
			return b;
		}

		private void create() {
			String c = comment.getValue().trim();
			ClientState.run(c.isEmpty() ? "create" : "create " + c);
			comment.setValue("");
		}

		/** Height of the selected-backup column: text lines, then the action buttons in rows. */
		private int columnHeight(int buttonColumns, int textLines) {
			return 6 + textLines * 12 + 4 + (actions.size() + buttonColumns - 1) / buttonColumns * ROW_H - 4;
		}

		@Override
		public void doLayout(ScreenRectangle area) {
			// On short screens the buttons go three to a row, then only the heading is shown.
			int buttonColumns = 2, textLines = 3;
			if (columnHeight(buttonColumns, textLines) > area.height()) buttonColumns = 3;
			if (columnHeight(buttonColumns, textLines) > area.height()) textLines = 1;
			int buttonW = buttonColumns == 2 ? 73 : 60;
			int columnW = buttonColumns * buttonW + (buttonColumns - 1) * 4;
			int columnX = area.right() - 10 - columnW;
			int listW = columnX - 10 - area.left() - 10;
			list.updateSizeAndPosition(listW, area.height() - 34, area.left() + 10, area.top() + 4);
			int rowY = area.bottom() - 24;
			comment.setPosition(area.left() + 10, rowY);
			comment.setWidth(Math.max(60, listW - create.getWidth() - 4));
			create.setPosition(area.left() + 10 + comment.getWidth() + 4, rowY);
			int y = area.top() + 6;
			List<StringWidget> texts = List.of(heading, details, note);
			for (int i = 0; i < texts.size(); i++) {
				StringWidget w = texts.get(i);
				w.visible = i < textLines;
				w.setMaxWidth(columnW);
				w.setPosition(columnX, y);
				if (w.visible) y += 12;
			}
			y += 4;
			for (int i = 0; i < actions.size(); i++) {
				Button b = actions.get(i);
				b.setWidth(buttonW);
				b.setPosition(columnX + (i % buttonColumns) * (buttonW + 4), y + (i / buttonColumns) * ROW_H);
			}
		}

		void update() {
			BackupListPayload.Entry e = selected();
			if (e == null) {
				heading.setMessage(Component.translatable("cytrabackups.gui.none_selected"));
				details.setMessage(Component.empty());
				note.setMessage(Component.empty());
			} else {
				heading.setMessage(Component.translatable("cytrabackups.gui.selected", e.id()));
				details.setMessage(Component.literal(Formatting.dateTimeShort(e.createdAt(), ZoneId.systemDefault()) + ", "
					+ Formatting.bytes(e.totalSize())).withStyle(ChatFormatting.GRAY));
				note.setMessage(e.comment().isBlank() ? Component.literal(e.trigger()).withStyle(ChatFormatting.GRAY)
					: Component.literal(e.comment()).withStyle(ChatFormatting.GRAY));
			}
			pin.setMessage(Component.translatable(e != null && e.pinned() ? "cytrabackups.gui.unpin" : "cytrabackups.gui.pin"));
			restore.setTooltip(Tooltip.create(Component.translatable(e != null && e.partial() ? "cytrabackups.gui.restore.area_tooltip" : "cytrabackups.gui.restore.tooltip")));
		}
	}

	/** Backups, newest first, in the style of the world selection list. */
	private final class BackupList extends ObjectSelectionList<BackupList.Row> {
		BackupList() {
			super(BackupScreen.this.minecraft, 200, 100, 0, 24);
		}

		void refresh() {
			BackupListPayload payload = ClientState.list;
			double scroll = scrollAmount();
			List<Row> rows = new ArrayList<>();
			Row sel = null;
			if (payload != null && payload.can(BackupListPayload.CAN_LIST)) {
				for (BackupListPayload.Entry e : payload.entries()) {
					Row r = new Row(e);
					if (e.id() == selectedId) sel = r;
					rows.add(r);
				}
			}
			replaceEntries(rows);
			setSelected(sel);
			setScrollAmount(scroll);
		}

		@Override
		public int getRowWidth() {
			return width - 12;
		}

		@Override
		protected int scrollBarX() {
			return getRight() - 6;
		}

		@Override
		public void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
			super.renderWidget(g, mouseX, mouseY, partialTick);
			BackupListPayload payload = ClientState.list;
			String key = payload == null ? "cytrabackups.gui.loading" : !payload.can(BackupListPayload.CAN_LIST) ? "cytrabackups.gui.no_list"
				: payload.entries().isEmpty() ? "cytrabackups.gui.empty" : null;
			if (key != null) g.drawCenteredString(font, Component.translatable(key), getX() + width / 2, getY() + 12, 0xFFA0A0A0);
		}

		final class Row extends ObjectSelectionList.Entry<Row> {
			private final BackupListPayload.Entry backup;

			Row(BackupListPayload.Entry backup) {
				this.backup = backup;
			}

			@Override
			public Component getNarration() {
				return Component.translatable("cytrabackups.gui.selected", backup.id());
			}

			@Override
			public void renderContent(GuiGraphics g, int mouseX, int mouseY, boolean hovered, float partialTick) {
				int x = getContentX(), y = getContentY(), w = getContentWidth();
				String date = Formatting.dateTimeShort(backup.createdAt(), ZoneId.systemDefault());
				String first = "#" + backup.id() + (backup.pinned() ? "*" : "") + "  " + date;
				g.drawString(font, first, x, y, 0xFFFFFFFF);
				int cx = x + font.width(first) + 8;
				if (!backup.comment().isBlank() && cx < x + w) g.drawString(font, clip(font, Component.literal(backup.comment()), x + w - cx), cx, y, 0xFFA0A0A0);
				String second = Formatting.bytes(backup.totalSize()) + ", " + backup.trigger() + (backup.partial() ? " " + Component.translatable("cytrabackups.list.area").getString() : "");
				g.drawString(font, clip(font, Component.literal(second), w), x, y + 11, 0xFFA0A0A0);
			}

			@Override
			public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
				BackupList.this.setSelected(this);
				selectedId = backup.id();
				refreshSelection();
				if (doubleClick && can(BackupListPayload.CAN_LIST)) ClientState.run("info " + backup.id());
				return true;
			}
		}
	}
}
