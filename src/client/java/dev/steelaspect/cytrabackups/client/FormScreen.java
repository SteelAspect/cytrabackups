package dev.steelaspect.cytrabackups.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

/** Small dialog with labelled text fields; OK passes the values to a callback and returns to the parent screen. */
final class FormScreen extends Screen {
	/** One text field. */
	record Field(String label, String initial, String hint, int maxLength) {
	}

	private final Screen parent;
	private final String description;
	private final List<Field> fields;
	private final String okLabel;
	private final Consumer<List<String>> onOk;
	private final List<EditBox> boxes = new ArrayList<>();

	FormScreen(Screen parent, String title, String description, List<Field> fields, String okLabel, Consumer<List<String>> onOk) {
		super(Component.literal(title));
		this.parent = parent;
		this.description = description;
		this.fields = fields;
		this.okLabel = okLabel;
		this.onOk = onOk;
	}

	private int panelW() {
		return Math.min(340, width - 40);
	}

	private int panelH() {
		return 14 + 28 + fields.size() * 34 + 30;
	}

	private int panelX() {
		return (width - panelW()) / 2;
	}

	private int panelY() {
		return Math.max(28, (height - panelH()) / 2);
	}

	@Override
	protected void init() {
		boxes.clear();
		int x = panelX() + 12, w = panelW() - 24;
		int y = panelY() + 14 + 28;
		for (Field f : fields) {
			EditBox b = new EditBox(font, x, y + 11, w, 18, Component.literal(f.label()));
			b.setMaxLength(f.maxLength());
			b.setValue(f.initial());
			if (!f.hint().isEmpty()) b.setHint(Component.literal(f.hint()));
			boxes.add(addRenderableWidget(b));
			y += 34;
		}
		int by = panelY() + panelH() - 26;
		addRenderableWidget(new FlatButton(panelX() + panelW() / 2 - 104, by, 100, 20, okLabel, Theme.SUCCESS, b -> submit()));
		addRenderableWidget(new FlatButton(panelX() + panelW() / 2 + 4, by, 100, 20, "Cancel", Theme.NEUTRAL, b -> onClose()));
		if (!boxes.isEmpty()) setInitialFocus(boxes.getFirst());
	}

	private void submit() {
		List<String> values = new ArrayList<>();
		for (EditBox b : boxes) values.add(b.getValue());
		minecraft.setScreen(parent);
		onOk.accept(values);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
			submit();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		super.renderBackground(g, mouseX, mouseY, partialTick);
		Theme.header(g, font, width, "CytraBackups", "");
		Theme.titledPanel(g, font, panelX(), panelY(), panelW(), panelH(), title.getString(), Theme.TITLE);
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		super.render(g, mouseX, mouseY, partialTick);
		int x = panelX() + 12;
		List<FormattedCharSequence> lines = font.split(Component.literal(description), panelW() - 24);
		for (int i = 0; i < lines.size() && i < 2; i++) g.drawString(font, lines.get(i), x, panelY() + 20 + i * 10, Theme.MUTED);
		int y = panelY() + 14 + 28;
		for (Field f : fields) {
			g.drawString(font, f.label(), x, y, Theme.TEXT);
			y += 34;
		}
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
