package dev.steelaspect.cytrabackups.client;

import dev.steelaspect.cytrabackups.core.config.ConfigSchema;
import dev.steelaspect.cytrabackups.net.BackupListPayload;
import dev.steelaspect.cytrabackups.net.ConfigPayload;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Editor for every CytraBackups setting, laid out like vanilla's option screens. The server sends the settings
 * (passwords and keys are never sent, only whether they are set); Save sends just the changed values, which the
 * server validates, writes to config/cytrabackups.json and applies with a reload.
 */
final class SettingsScreen extends Screen {
	private static final int SECTIONS_W = 100;
	private static final int WIDGET_W = 150;
	private static String section = "schedule"; // remembered while the game runs

	private final Screen parent;
	private HeaderAndFooterLayout layout;
	private final Map<String, String> edits = new LinkedHashMap<>();
	private SectionList sections;
	private SettingList settings;
	private Button saveButton, revertButton;
	private long seenConfig = -1;
	private boolean saving;

	SettingsScreen(Screen parent) {
		super(Component.translatable("cytrabackups.gui.settings.title"));
		this.parent = parent;
		ClientState.requestConfig();
	}

	@Override
	protected void init() {
		layout = new HeaderAndFooterLayout(this);
		layout.addTitleHeader(title, font);
		LinearLayout footer = layout.addToFooter(LinearLayout.horizontal().spacing(8));
		saveButton = footer.addChild(Button.builder(Component.translatable("cytrabackups.gui.save"), b -> save()).width(98)
			.tooltip(Tooltip.create(Component.translatable("cytrabackups.gui.save.tooltip"))).build());
		revertButton = footer.addChild(Button.builder(Component.translatable("cytrabackups.gui.revert"), b -> {
			edits.clear();
			rebuildWidgets();
		}).width(98).tooltip(Tooltip.create(Component.translatable("cytrabackups.gui.revert.tooltip"))).build());
		footer.addChild(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).width(98).build());
		layout.visitWidgets(this::addRenderableWidget);
		seenConfig = ClientState.configVersion;
		ConfigPayload cfg = ClientState.config;
		if (cfg != null) {
			List<String> names = new ArrayList<>(new LinkedHashSet<>(cfg.entries().stream().map(ConfigSchema.Entry::section).toList()));
			if (!names.contains(section)) section = names.getFirst();
			sections = addRenderableWidget(new SectionList(names));
			settings = addRenderableWidget(new SettingList(cfg.entries().stream().filter(e -> e.section().equals(section)).toList()));
		}
		repositionElements();
		updateButtons();
	}

	@Override
	protected void repositionElements() {
		layout.arrangeElements();
		int top = layout.getHeaderHeight(), h = height - top - layout.getFooterHeight() - problemsHeight();
		if (sections != null) sections.updateSizeAndPosition(SECTIONS_W, h, 10, top);
		if (settings != null) settings.updateSizeAndPosition(width - SECTIONS_W - 30, h, SECTIONS_W + 20, top);
	}

	private int problemsHeight() {
		ConfigPayload cfg = ClientState.config;
		return cfg == null || cfg.problems().isEmpty() ? 0 : Math.min(2, cfg.problems().size()) * 10 + 4;
	}

	private void edit(ConfigSchema.Entry e, String value) {
		if (value.equals(e.value())) edits.remove(e.path());
		else edits.put(e.path(), value);
		updateButtons();
	}

	private void updateButtons() {
		saveButton.active = !edits.isEmpty() && !saving;
		saveButton.setMessage(edits.isEmpty() || saving ? Component.translatable("cytrabackups.gui.save")
			: Component.translatable("cytrabackups.gui.save.count", edits.size()));
		revertButton.active = !edits.isEmpty();
	}

	private void save() {
		if (edits.isEmpty()) return;
		saving = true;
		ClientState.saveConfig(new LinkedHashMap<>(edits));
		updateButtons();
	}

	@Override
	public void tick() {
		if (seenConfig != ClientState.configVersion) {
			ConfigPayload cfg = ClientState.config;
			if (saving && cfg != null && cfg.problems().isEmpty()) edits.clear();
			saving = false;
			rebuildWidgets();
		}
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		super.render(g, mouseX, mouseY, partialTick);
		ConfigPayload cfg = ClientState.config;
		if (cfg == null) {
			boolean denied = ClientState.list != null && !ClientState.can(BackupListPayload.CAN_ADMIN);
			g.drawCenteredString(font, Component.translatable(denied ? "cytrabackups.gui.settings.denied" : "cytrabackups.gui.loading"), width / 2, height / 2 - 4, 0xFFA0A0A0);
			return;
		}
		int y = height - layout.getFooterHeight() - problemsHeight() + 2;
		for (String p : cfg.problems().subList(0, Math.min(2, cfg.problems().size()))) {
			g.drawString(font, BackupScreen.clip(font, Component.literal(p), width - 20), 10, y, 0xFFFF5555);
			y += 10;
		}
	}

	@Override
	public void onClose() {
		if (edits.isEmpty()) {
			minecraft.setScreen(parent);
			return;
		}
		minecraft.setScreen(new ConfirmScreen(yes -> minecraft.setScreen(yes ? parent : this),
			Component.translatable("cytrabackups.gui.discard.title", edits.size()), Component.translatable("cytrabackups.gui.discard.message")));
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	static Component name(ConfigSchema.Entry e) {
		return Component.translatable(e.nameKey(), e.keyArgument());
	}

	static Component help(ConfigSchema.Entry e) {
		return Component.translatable(e.helpKey(), e.keyArgument());
	}

	/** The sections, like vanilla's language list. */
	private final class SectionList extends ObjectSelectionList<SectionList.Row> {
		SectionList(List<String> names) {
			super(SettingsScreen.this.minecraft, SECTIONS_W, 100, 0, 18);
			for (String n : names) {
				Row r = new Row(n);
				addEntry(r);
				if (n.equals(section)) setSelected(r);
			}
		}

		@Override
		public int getRowWidth() {
			return width - 8;
		}

		@Override
		protected int scrollBarX() {
			return getRight() - 6;
		}

		final class Row extends ObjectSelectionList.Entry<Row> {
			private final String name;
			private final Component label;

			Row(String name) {
				this.name = name;
				this.label = Component.translatable("cytrabackups.config.section." + name);
			}

			@Override
			public Component getNarration() {
				return label;
			}

			@Override
			public void renderContent(GuiGraphics g, int mouseX, int mouseY, boolean hovered, float partialTick) {
				g.drawCenteredString(font, label, getContentXMiddle(), getContentYMiddle() - 4, 0xFFFFFFFF);
			}

			@Override
			public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
				if (!name.equals(section)) {
					section = name;
					rebuildWidgets();
				}
				return true;
			}
		}
	}

	/** Setting rows: name on the left, its control on the right, like vanilla's controls list. */
	private final class SettingList extends ContainerObjectSelectionList<SettingList.Row> {
		SettingList(List<ConfigSchema.Entry> entries) {
			super(SettingsScreen.this.minecraft, 300, 100, 0, 24);
			for (ConfigSchema.Entry e : entries) addEntry(new Row(e));
		}

		@Override
		public int getRowWidth() {
			return Math.min(width - 14, 340);
		}

		@Override
		protected int scrollBarX() {
			return getRight() - 6;
		}

		final class Row extends ContainerObjectSelectionList.Entry<Row> {
			private final ConfigSchema.Entry entry;
			private final List<AbstractWidget> controls = new ArrayList<>();

			Row(ConfigSchema.Entry e) {
				this.entry = e;
				Tooltip tip = Tooltip.create(help(e));
				String current = edits.getOrDefault(e.path(), e.value());
				switch (e.type()) {
					case BOOL -> controls.add(CycleButton.onOffBuilder(Boolean.parseBoolean(current)).displayOnlyValue().withTooltip(v -> tip)
						.create(0, 0, WIDGET_W, 20, name(e), (b, v) -> edit(e, String.valueOf(v))));
					case CHOICE -> controls.add(CycleButton.<String>builder(Component::literal, e.choices().contains(current) ? current : e.choices().getFirst())
						.withValues(e.choices()).displayOnlyValue().withTooltip(v -> tip).create(0, 0, WIDGET_W, 20, name(e), (b, v) -> edit(e, v)));
					case SECRET -> {
						boolean clearing = "".equals(edits.get(e.path()));
						EditBox box = new EditBox(font, 0, 0, WIDGET_W - 44, 20, name(e));
						box.setMaxLength(1024);
						box.setValue(clearing ? "" : edits.getOrDefault(e.path(), ""));
						box.setHint(Component.translatable(clearing ? "cytrabackups.gui.secret.clearing" : e.set() ? "cytrabackups.gui.secret.set"
							: "cytrabackups.gui.secret.unset").withStyle(ChatFormatting.DARK_GRAY));
						box.setTooltip(tip);
						box.setResponder(text -> {
							if (text.isEmpty()) edits.remove(e.path());
							else edits.put(e.path(), text);
							updateButtons();
						});
						Button clear = Button.builder(Component.translatable("cytrabackups.gui.clear"), b -> {
							edits.put(e.path(), "");
							rebuildWidgets();
						}).width(40).tooltip(Tooltip.create(Component.translatable("cytrabackups.gui.clear.tooltip"))).build();
						clear.active = e.set() || edits.containsKey(e.path());
						controls.add(box);
						controls.add(clear);
					}
					default -> {
						EditBox box = new EditBox(font, 0, 0, WIDGET_W, 20, name(e));
						box.setMaxLength(e.type() == ConfigSchema.Type.LIST ? 8192 : 1024);
						box.setValue(current);
						switch (e.type()) {
							case INT, LONG -> box.setFilter(t -> t.matches("-?\\d*"));
							case DOUBLE -> box.setFilter(t -> t.matches("-?\\d*\\.?\\d*"));
							default -> {
							}
						}
						if (e.type() == ConfigSchema.Type.LIST) box.setHint(Component.translatable("cytrabackups.gui.list_hint").withStyle(ChatFormatting.DARK_GRAY));
						box.setTooltip(tip);
						box.setResponder(text -> edit(e, text));
						controls.add(box);
					}
				}
			}

			@Override
			public void renderContent(GuiGraphics g, int mouseX, int mouseY, boolean hovered, float partialTick) {
				int right = getContentRight();
				int controlW = Math.min(WIDGET_W, getContentWidth() / 2);
				for (int i = controls.size() - 1; i >= 0; i--) {
					AbstractWidget c = controls.get(i);
					if (controls.size() == 1) c.setWidth(controlW);
					else if (i == 0) c.setWidth(controlW - 44);
					c.setPosition(right - c.getWidth(), getContentY());
					c.render(g, mouseX, mouseY, partialTick);
					right -= c.getWidth() + 4;
				}
				boolean changed = edits.containsKey(entry.path());
				Component label = changed ? name(entry).copy().append("*").withStyle(ChatFormatting.ITALIC) : name(entry);
				g.drawString(font, BackupScreen.clip(font, label, right - getContentX() - 4), getContentX(), getContentYMiddle() - 4, 0xFFFFFFFF);
				if (hovered && mouseX < right) g.setTooltipForNextFrame(font.split(help(entry), 220), mouseX, mouseY);
			}

			@Override
			public List<? extends GuiEventListener> children() {
				return controls;
			}

			@Override
			public List<? extends NarratableEntry> narratables() {
				return controls;
			}
		}
	}
}
