package dev.steelaspect.cytrabackups.client;

import dev.steelaspect.alloyui.AlloyScreen;
import dev.steelaspect.alloyui.Toast;
import dev.steelaspect.alloyui.widget.Button;
import dev.steelaspect.alloyui.widget.Column;
import dev.steelaspect.alloyui.widget.Container;
import dev.steelaspect.alloyui.widget.Label;
import dev.steelaspect.alloyui.widget.Rows;
import dev.steelaspect.alloyui.widget.ScrollPanel;
import dev.steelaspect.alloyui.widget.SettingRow;
import dev.steelaspect.alloyui.widget.TextField;
import dev.steelaspect.alloyui.widget.Widget;
import dev.steelaspect.cytrabackups.core.config.ConfigSchema;
import dev.steelaspect.cytrabackups.net.BackupListPayload;
import dev.steelaspect.cytrabackups.net.ConfigPayload;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * Editor for every Alloy Backups setting: one scrolling list with a folding section per config section. The server
 * sends the settings (passwords and keys are never sent, only whether they are set); Save sends just the changed
 * values, which the server validates, writes to config/cytrabackups.json and applies with a reload. A row's ↺ puts it
 * back to the saved value.
 */
final class SettingsScreen extends AlloyScreen {
	private static String section = "schedule"; // remembered while the game runs: the list opens there

	private final Map<String, String> edits = new LinkedHashMap<>();
	private final Column column = new Column(0);
	private final ScrollPanel list = new ScrollPanel(column);
	private long seenConfig = -1;
	private boolean saving;
	private @Nullable Widget scrollTarget;
	private boolean scrolled;

	SettingsScreen(@Nullable Screen parent) {
		super(Component.translatable("cytrabackups.gui.settings.title"), parent);
		ClientState.requestConfig();
		BackupFrame frame = new BackupFrame(title, this::status).maxWidth(520);
		frame.body(list);
		frame.button(BackupScreen.button("cytrabackups.gui.revert", "cytrabackups.gui.revert.tooltip", () -> {
			edits.clear();
			rebuild();
		}, () -> !edits.isEmpty()));
		frame.button(new Button(CommonComponents.GUI_DONE, this::onClose));
		Button save = new Button(() -> edits.isEmpty() || saving ? Component.translatable("cytrabackups.gui.save")
			: Component.translatable("cytrabackups.gui.save.count", edits.size()), this::save).style(Button.Style.PRIMARY);
		frame.button(BackupScreen.button(save, "cytrabackups.gui.save.tooltip", () -> !edits.isEmpty() && !saving));
		setRoot(frame);
		rebuild();
	}

	private Component status() {
		ConfigPayload cfg = ClientState.config;
		return cfg == null || cfg.problems().isEmpty() ? Component.empty() : Component.translatable("cytrabackups.gui.settings.problems", cfg.problems().size());
	}

	private String current(ConfigSchema.Entry e) {
		return edits.getOrDefault(e.path(), e.value());
	}

	private void edit(ConfigSchema.Entry e, String value) {
		if (value.equals(e.value())) edits.remove(e.path());
		else edits.put(e.path(), value);
	}

	private void save() {
		if (edits.isEmpty()) return;
		saving = true;
		ClientState.saveConfig(new LinkedHashMap<>(edits));
	}

	private void rebuild() {
		seenConfig = ClientState.configVersion;
		column.clear();
		ConfigPayload cfg = ClientState.config;
		if (cfg == null) {
			boolean denied = ClientState.list != null && !ClientState.can(BackupListPayload.CAN_ADMIN);
			column.add(new Label(Component.translatable(denied ? "cytrabackups.gui.settings.denied" : "cytrabackups.gui.loading")).color("textMuted"));
			return;
		}
		for (String p : cfg.problems()) column.add(new Label(Component.literal(p)).color("bad"));
		String last = null;
		for (ConfigSchema.Entry e : cfg.entries()) {
			if (!e.section().equals(last)) {
				last = e.section();
				Widget s = column.add(Rows.section(Component.translatable("cytrabackups.config.section." + last)));
				if (last.equals(section) && !scrolled) scrollTarget = s;
			}
			column.add(row(e));
		}
	}

	private Widget row(ConfigSchema.Entry e) {
		Component name = name(e), help = help(e);
		return switch (e.type()) {
			case BOOL -> Rows.toggle(name, help, () -> Boolean.parseBoolean(current(e)), v -> edit(e, String.valueOf(v)), Boolean.parseBoolean(e.value()));
			case CHOICE -> Rows.dropdown(name, help, e::choices, Component::literal,
				() -> e.choices().contains(current(e)) ? current(e) : e.choices().get(0), v -> edit(e, v), e.value());
			case SECRET -> new SettingRow(name, help, new SecretControl(e));
			default -> {
				TextField box = new TextField(() -> current(e), v -> edit(e, v)).live().maxLength(e.type() == ConfigSchema.Type.LIST ? 8192 : 1024);
				Predicate<String> valid = switch (e.type()) {
					case INT, LONG -> t -> t.matches("-?\\d+");
					case DOUBLE -> t -> t.matches("-?\\d+(\\.\\d+)?");
					default -> t -> true;
				};
				box.validator(valid, Component.translatable("cytrabackups.gui.settings.number"));
				if (e.type() == ConfigSchema.Type.LIST) box.placeholder(Component.translatable("cytrabackups.gui.list_hint"));
				yield new SettingRow(name, help, box).resettable(() -> current(e).equals(e.value()), () -> box.setText(e.value()));
			}
		};
	}

	@Override
	public void tick() {
		if (seenConfig != ClientState.configVersion) {
			ConfigPayload cfg = ClientState.config;
			if (saving && cfg != null && cfg.problems().isEmpty()) {
				edits.clear();
				toast(Toast.Kind.SUCCESS, Component.translatable("cytrabackups.gui.settings.saved"));
			} else if (saving && cfg != null) {
				toast(Toast.Kind.ERROR, Component.translatable("cytrabackups.gui.settings.problems", cfg.problems().size()));
			}
			saving = false;
			rebuild();
		}
		if (scrollTarget != null && scrollTarget.h() > 0) {
			list.scrollTo(scrollTarget.y(), list.h() - 8);
			scrollTarget = null;
			scrolled = true;
		}
	}

	@Override
	public void onClose() {
		if (edits.isEmpty()) {
			minecraft.setScreen(parent);
			return;
		}
		Dialogs.confirm(Component.translatable("cytrabackups.gui.discard.title", edits.size()), Component.translatable("cytrabackups.gui.discard.message"),
			Component.translatable("cytrabackups.gui.discard"), true, () -> minecraft.setScreen(parent));
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

	/**
	 * A password or key: the field stays empty (the server never sends it) and says whether one is stored. Typing sets
	 * a new one; Clear removes the stored one.
	 */
	private final class SecretControl extends Container {
		private final ConfigSchema.Entry entry;
		private final TextField box;
		private final Button clear;

		SecretControl(ConfigSchema.Entry e) {
			this.entry = e;
			boolean clearing = "".equals(edits.get(e.path()));
			box = add(new TextField(() -> clearing ? "" : edits.getOrDefault(e.path(), ""), text -> {
				if (text.isEmpty()) edits.remove(e.path());
				else edits.put(e.path(), text);
			}).live().maxLength(1024).placeholder(Component.translatable(clearing ? "cytrabackups.gui.secret.clearing"
				: e.set() ? "cytrabackups.gui.secret.set" : "cytrabackups.gui.secret.unset")));
			clear = add(BackupScreen.button(new Button(Component.translatable("cytrabackups.gui.clear"), () -> {
				edits.put(entry.path(), "");
				rebuild();
			}), "cytrabackups.gui.clear.tooltip", () -> entry.set() || edits.containsKey(entry.path())));
		}

		@Override
		public float measure(float width) {
			return 16;
		}

		@Override
		protected void layout() {
			float bw = Math.min(44, w / 3);
			box.setBounds(x, y, w - bw - 4, 16);
			clear.setBounds(x + w - bw, y, bw, 16);
		}
	}
}
