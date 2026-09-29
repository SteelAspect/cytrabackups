package dev.steelaspect.cytrabackups.client;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

/**
 * Scrollable output panel showing the server's replies to GUI actions, with the same colours as chat. Links in the
 * text work: [Confirm], [Info], [Restore] etc. run their /backup command through the GUI, and "suggest" links open
 * an editable command box.
 */
final class LogPanel {
	private static final int LINE = 10;
	int x, y, w, h;
	private int scroll; // lines scrolled up from the bottom
	private long seenVersion = -1;
	private int seenWidth = -1;
	private final List<FormattedCharSequence> lines = new ArrayList<>();
	private final Consumer<String> suggest;

	/** {@code suggest} receives the text of "suggest command" links. */
	LogPanel(Consumer<String> suggest) {
		this.suggest = suggest;
	}

	void setBounds(int x, int y, int w, int h) {
		this.x = x;
		this.y = y;
		this.w = w;
		this.h = h;
	}

	private int visibleLines() {
		return Math.max(1, (h - 4) / LINE);
	}

	private void rewrap(Font font) {
		if (seenVersion == ClientState.logVersion && seenWidth == w) return;
		boolean atBottom = scroll == 0;
		int before = lines.size();
		lines.clear();
		for (ClientState.LogLine l : ClientState.log) lines.addAll(font.split(l.text(), w - 8));
		if (!atBottom) scroll = Math.max(0, scroll + lines.size() - before); // keep the view still while reading
		seenVersion = ClientState.logVersion;
		seenWidth = w;
	}

	void render(GuiGraphics g, Font font, int mouseX, int mouseY) {
		rewrap(font);
		g.fill(x, y, x + w, y + h, 0xA0000000);
		g.renderOutline(x, y, w, h, 0xFF404040);
		int visible = visibleLines();
		int maxScroll = Math.max(0, lines.size() - visible);
		scroll = Math.min(scroll, maxScroll);
		int first = Math.max(0, lines.size() - visible - scroll);
		if (lines.isEmpty()) {
			g.drawString(font, "Output of your actions appears here. Underlined/bold [links] can be clicked.", x + 4, y + 3, 0xFF707070);
		}
		g.enableScissor(x, y, x + w, y + h);
		for (int i = 0; i < visible && first + i < lines.size(); i++) {
			g.drawString(font, lines.get(first + i), x + 4, y + 3 + i * LINE, 0xFFE0E0E0);
		}
		g.disableScissor();
		if (maxScroll > 0) {
			int barH = Math.max(8, h * visible / lines.size());
			int barY = y + (h - barH) * (maxScroll - scroll) / maxScroll;
			g.fill(x + w - 3, barY, x + w - 1, barY + barH, 0xFF808080);
		}
		Style s = styleAt(font, mouseX, mouseY);
		if (s != null && s.getHoverEvent() instanceof HoverEvent.ShowText(var text)) g.setTooltipForNextFrame(text, mouseX, mouseY);
	}

	boolean contains(double mx, double my) {
		return mx >= x && mx < x + w && my >= y && my < y + h;
	}

	private Style styleAt(Font font, double mx, double my) {
		if (!contains(mx, my)) return null;
		int visible = visibleLines();
		int first = Math.max(0, lines.size() - visible - scroll);
		int row = (int) ((my - y - 3) / LINE);
		if (row < 0 || row >= visible || first + row >= lines.size()) return null;
		return styleAtWidth(font, lines.get(first + row), (int) (mx - x - 4));
	}

	static Style styleAtWidth(Font font, FormattedCharSequence seq, int px) {
		if (px < 0) return null;
		float[] width = {0};
		Style[] found = {null};
		seq.accept((index, style, codepoint) -> {
			float cw = font.width(FormattedCharSequence.codepoint(codepoint, style));
			if (px >= width[0] && px < width[0] + cw) {
				found[0] = style;
				return false;
			}
			width[0] += cw;
			return true;
		});
		return found[0];
	}

	boolean mouseClicked(Font font, double mx, double my) {
		Style s = styleAt(font, mx, my);
		if (s == null || s.getClickEvent() == null) return contains(mx, my);
		ClickEvent click = s.getClickEvent();
		if (click instanceof ClickEvent.RunCommand(String command)) {
			String cmd = command.startsWith("/") ? command.substring(1) : command;
			if (cmd.equals("backup") || cmd.startsWith("backup ")) ClientState.run(cmd);
		} else if (click instanceof ClickEvent.SuggestCommand(String command)) {
			suggest.accept(command);
		} else if (click instanceof ClickEvent.CopyToClipboard(String value)) {
			Minecraft.getInstance().keyboardHandler.setClipboard(value);
		}
		return true;
	}

	boolean mouseScrolled(double mx, double my, double dy) {
		if (!contains(mx, my)) return false;
		scroll = Math.max(0, scroll + (int) Math.signum(dy) * 2);
		return true;
	}
}
