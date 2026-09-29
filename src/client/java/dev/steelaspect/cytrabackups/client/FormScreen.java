package dev.steelaspect.cytrabackups.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
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

	private int top() {
		return Math.max(40, height / 2 - 20 - fields.size() * 18);
	}

	@Override
	protected void init() {
		boxes.clear();
		int w = Math.min(320, width - 40);
		int x = (width - w) / 2;
		int y = top() + 24;
		for (Field f : fields) {
			EditBox b = new EditBox(font, x, y + 10, w, 20, Component.literal(f.label()));
			b.setMaxLength(f.maxLength());
			b.setValue(f.initial());
			if (!f.hint().isEmpty()) b.setHint(Component.literal(f.hint()));
			boxes.add(addRenderableWidget(b));
			y += 36;
		}
		addRenderableWidget(Button.builder(Component.literal(okLabel), b -> submit()).bounds(width / 2 - 104, y + 6, 100, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> onClose()).bounds(width / 2 + 4, y + 6, 100, 20).build());
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
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		super.render(g, mouseX, mouseY, partialTick);
		int w = Math.min(320, width - 40);
		int x = (width - w) / 2;
		g.drawCenteredString(font, title, width / 2, top() - 8, 0xFFFFAA00);
		if (!description.isEmpty()) {
			List<net.minecraft.util.FormattedCharSequence> lines = font.split(Component.literal(description), w);
			for (int i = 0; i < lines.size() && i < 2; i++) g.drawCenteredString(font, lines.get(i), width / 2, top() + 4 + i * 10 - (lines.size() > 1 ? 5 : 0), 0xFFA0A0A0);
		}
		int y = top() + 24;
		for (Field f : fields) {
			g.drawString(font, f.label(), x, y, 0xFFE0E0E0);
			y += 36;
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
