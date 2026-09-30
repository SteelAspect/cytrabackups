package dev.steelaspect.cytrabackups.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Small dialog with labelled text fields, laid out like vanilla's "Edit Server Info" screen. */
final class FormScreen extends Screen {
	/** One text field; {@code labelKey} is a lang key. */
	record Field(String labelKey, String initial, int maxLength) {
	}

	private static final int FIELD_W = 200;
	private final Screen parent;
	private final List<Field> fields;
	private final Consumer<List<String>> onDone;
	private final List<EditBox> boxes = new ArrayList<>();

	FormScreen(Screen parent, Component title, List<Field> fields, Consumer<List<String>> onDone) {
		super(title);
		this.parent = parent;
		this.fields = fields;
		this.onDone = onDone;
	}

	private int top() {
		return Math.max(30, height / 4);
	}

	@Override
	protected void init() {
		boxes.clear();
		int x = (width - FIELD_W) / 2, y = top();
		for (Field f : fields) {
			EditBox b = new EditBox(font, x, y + 12, FIELD_W, 20, Component.translatable(f.labelKey()));
			b.setMaxLength(f.maxLength());
			b.setValue(f.initial());
			boxes.add(addRenderableWidget(b));
			y += 44;
		}
		int by = Math.min(height - 28, y + 8);
		addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> submit()).bounds(width / 2 - 100, by, 98, 20).build());
		addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose()).bounds(width / 2 + 2, by, 98, 20).build());
		if (!boxes.isEmpty()) setInitialFocus(boxes.getFirst());
	}

	private void submit() {
		List<String> values = new ArrayList<>();
		for (EditBox b : boxes) values.add(b.getValue());
		minecraft.setScreen(parent);
		onDone.accept(values);
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
		g.drawCenteredString(font, title, width / 2, top() - 20, 0xFFFFFFFF);
		int x = (width - FIELD_W) / 2, y = top();
		for (Field f : fields) {
			g.drawString(font, Component.translatable(f.labelKey()), x, y, 0xFFA0A0A0);
			y += 44;
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
