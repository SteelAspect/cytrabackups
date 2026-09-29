package dev.steelaspect.cytrabackups.client;

import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;

/** Flat, coloured button used by the CytraBackups screens (also as a tab or a value cycler). */
class FlatButton extends AbstractButton {
	private final Consumer<FlatButton> action;
	private int color;
	private boolean selected;
	private boolean tab;

	FlatButton(int x, int y, int w, int h, String label, int color, Consumer<FlatButton> action) {
		super(x, y, w, h, Component.literal(label));
		this.color = color;
		this.action = action;
	}

	FlatButton tooltip(String text) {
		if (text != null && !text.isEmpty()) setTooltip(Tooltip.create(Component.literal(text)));
		return this;
	}

	/** Draws as a tab: flat when idle, accent with an underline when selected. */
	FlatButton asTab(boolean selected) {
		this.tab = true;
		this.selected = selected;
		return this;
	}

	void setColor(int color) {
		this.color = color;
	}

	void setLabel(String label) {
		setMessage(Component.literal(label));
	}

	@Override
	public void onPress(InputWithModifiers input) {
		action.accept(this);
	}

	@Override
	protected void renderContents(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		Font font = Minecraft.getInstance().font;
		int x = getX(), y = getY(), w = getWidth(), h = getHeight();
		boolean hot = active && isHoveredOrFocused();
		int text;
		if (tab) {
			int bg = selected ? Theme.shade(Theme.ACCENT, 0.55f) : hot ? Theme.shade(Theme.NEUTRAL, 1.2f) : Theme.shade(Theme.NEUTRAL, 0.8f);
			g.fill(x, y, x + w, y + h, bg);
			if (selected) g.fill(x, y + h - 2, x + w, y + h, Theme.ACCENT);
			text = selected ? 0xFFFFFFFF : active ? Theme.TEXT : Theme.FAINT;
		} else {
			int base = active ? color : Theme.DISABLED;
			g.fill(x, y, x + w, y + h, hot ? Theme.shade(base, 1.25f) : base);
			g.fill(x, y + h - 2, x + w, y + h, Theme.shade(base, 0.6f));
			g.fill(x, y, x + w, y + 1, Theme.shade(base, 1.3f));
			if (hot) g.renderOutline(x, y, w, h, 0xC0FFFFFF);
			text = active ? 0xFFFFFFFF : Theme.FAINT;
		}
		String label = Theme.ellipsize(font, getMessage().getString(), w - 6);
		g.drawCenteredString(font, label, x + w / 2, y + (h - 8) / 2, text);
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput out) {
		defaultButtonNarrationText(out);
	}

	/** Colour for a value of a {@link Cycle} button. */
	@FunctionalInterface
	interface ColorFor {
		int color(String value);
	}

	/** Button that cycles through values; the colour can depend on the value (e.g. ON green, OFF red). */
	static final class Cycle extends FlatButton {
		private final List<String> values;
		private final ColorFor colors;
		private final java.util.function.UnaryOperator<String> labels;
		private final Consumer<String> onChange;
		private int index;

		Cycle(int x, int y, int w, int h, List<String> values, String current, ColorFor colors, Consumer<String> onChange) {
			this(x, y, w, h, values, current, colors, v -> v, onChange);
		}

		/** {@code labels} turns a value into the text shown (e.g. "true" -> "ON"). */
		Cycle(int x, int y, int w, int h, List<String> values, String current, ColorFor colors, java.util.function.UnaryOperator<String> labels,
			  Consumer<String> onChange) {
			super(x, y, w, h, current, colors.color(current), null);
			this.values = values;
			this.colors = colors;
			this.labels = labels;
			this.onChange = onChange;
			this.index = Math.max(0, values.indexOf(current));
			refresh();
		}

		String value() {
			return values.get(index);
		}

		private void refresh() {
			setLabel(labels.apply(value()));
			setColor(colors.color(value()));
		}

		@Override
		public void onPress(InputWithModifiers input) {
			index = (index + 1) % values.size();
			refresh();
			onChange.accept(value());
		}
	}
}
