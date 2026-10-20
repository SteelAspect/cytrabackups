package dev.steelaspect.cytrabackups.client;

import dev.steelaspect.alloyui.UiContext;
import dev.steelaspect.alloyui.render.Draw;
import dev.steelaspect.alloyui.widget.Widget;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

/**
 * Scrollable output panel showing the server's replies to GUI actions, with the same colours as chat. Links in the
 * text work: [Confirm], [Info], [Restore] etc. run their /cbackup command through the GUI, and "suggest" links open
 * an editable command box.
 */
final class LogPanel extends Widget {
	private static final int LINE = 10;
	private float scroll; // lines scrolled up from the bottom
	private long seenVersion = -1;
	private float seenWidth = -1;
	private final List<FormattedCharSequence> lines = new ArrayList<>();
	private final Consumer<String> suggest;

	/** {@code suggest} receives the text of "suggest command" links. */
	LogPanel(Consumer<String> suggest) {
		this.suggest = suggest;
	}

	@Override
	public float measure(float width) {
		return 60;
	}

	private int visibleLines() {
		return Math.max(1, (int) ((h - 6) / LINE));
	}

	private void rewrap() {
		if (seenVersion == ClientState.logVersion && seenWidth == w) return;
		boolean atBottom = scroll == 0;
		int before = lines.size();
		lines.clear();
		Font font = Draw.font();
		for (ClientState.LogLine l : ClientState.log) lines.addAll(font.split(Draw.styled(l.text(), false), (int) Math.max(20, w - 12)));
		if (!atBottom) scroll = Math.max(0, scroll + lines.size() - before); // keep the view still while reading
		seenVersion = ClientState.logVersion;
		seenWidth = w;
	}

	private int first() {
		return Math.max(0, lines.size() - visibleLines() - (int) scroll);
	}

	@Override
	protected void renderWidget(UiContext ctx) {
		var t = ctx.theme;
		rewrap();
		Draw.panel(ctx.g, x, y, w, h, t.metricF("radius"), t.color("bg"), t.color("border"), t.metricF("borderWidth"));
		int visible = visibleLines();
		int maxScroll = Math.max(0, lines.size() - visible);
		scroll = Math.min(scroll, maxScroll);
		if (lines.isEmpty()) Draw.text(ctx.g, Component.translatable("cytrabackups.gui.output.empty"), x + 6, y + 4, t.color("textMuted"));
		ctx.pushClip(x, y, w, h);
		int first = first();
		for (int i = 0; i < visible && first + i < lines.size(); i++) Draw.text(ctx.g, lines.get(first + i), x + 6, y + 4 + i * LINE, t.color("text"));
		ctx.popClip();
		if (maxScroll > 0) {
			float barH = Math.max(8, h * visible / (float) lines.size());
			float barY = y + 2 + (h - 4 - barH) * (maxScroll - scroll) / maxScroll;
			Draw.roundRect(ctx.g, x + w - 4, barY, 2, barH, 1, t.color("scrollbar"));
		}
		Style s = styleAt(ctx.mouseX, ctx.mouseY);
		if (s != null && hovered && s.getHoverEvent() instanceof HoverEvent.ShowText(var text)) {
			ctx.host.showTooltip(List.of(text), ctx.mouseX, ctx.mouseY);
		}
	}

	private Style styleAt(double mx, double my) {
		if (!contains(mx, my)) return null;
		int row = (int) ((my - y - 4) / LINE);
		int first = first();
		if (row < 0 || row >= visibleLines() || first + row >= lines.size()) return null;
		return styleAtWidth(Draw.font(), lines.get(first + row), (int) (mx - x - 6));
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

	@Override
	public boolean mouseClicked(UiContext ctx, double mx, double my, int button) {
		if (!contains(mx, my)) return false;
		Style s = styleAt(mx, my);
		if (s == null || s.getClickEvent() == null) return true;
		ClickEvent click = s.getClickEvent();
		if (click instanceof ClickEvent.RunCommand(String command)) {
			if (ClientState.isOwnCommand(command)) ClientState.runRaw(command);
		} else if (click instanceof ClickEvent.SuggestCommand(String command)) {
			suggest.accept(command);
		} else if (click instanceof ClickEvent.CopyToClipboard(String value)) {
			Minecraft.getInstance().keyboardHandler.setClipboard(value);
		}
		return true;
	}

	@Override
	public boolean mouseScrolled(UiContext ctx, double mx, double my, double amount) {
		if (!contains(mx, my)) return false;
		scroll = Math.max(0, scroll + (int) Math.signum(amount) * 2);
		return true;
	}
}
