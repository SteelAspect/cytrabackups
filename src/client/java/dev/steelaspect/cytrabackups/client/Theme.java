package dev.steelaspect.cytrabackups.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/** Colours and panel drawing shared by the CytraBackups screens. */
final class Theme {
	private Theme() {
	}

	// surfaces
	static final int HEADER = 0xF0182330;
	static final int PANEL = 0xD8121920;
	static final int PANEL_ALT = 0xD81A232D;
	static final int CARD = 0xE01B2530;
	static final int BORDER = 0xFF2E4255;
	static final int SHADOW = 0x60000000;
	// text
	static final int TEXT = 0xFFE6EDF3;
	static final int MUTED = 0xFF8FA1B2;
	static final int FAINT = 0xFF5E6E7C;
	static final int TITLE = 0xFFFFC857;
	// accents
	static final int ACCENT = 0xFF3AA6E8;
	static final int GREEN_TEXT = 0xFF5FD99A;
	static final int RED_TEXT = 0xFFFF6B6B;
	static final int GOLD_TEXT = 0xFFFFC857;
	static final int SELECTED_ROW = 0xFF1F4E79;
	// buttons
	static final int PRIMARY = 0xFF2A6FB0;
	static final int SUCCESS = 0xFF2E8B57;
	static final int DANGER = 0xFFB33A3A;
	static final int WARNING = 0xFFB7791F;
	static final int NEUTRAL = 0xFF3B4856;
	static final int DISABLED = 0xFF2A323B;

	static int shade(int argb, float factor) {
		int a = argb >>> 24, r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
		if (factor >= 1f) {
			float t = factor - 1f;
			r = (int) (r + (255 - r) * t);
			g = (int) (g + (255 - g) * t);
			b = (int) (b + (255 - b) * t);
		} else {
			r = (int) (r * factor);
			g = (int) (g * factor);
			b = (int) (b * factor);
		}
		return (a << 24) | (Math.min(255, r) << 16) | (Math.min(255, g) << 8) | Math.min(255, b);
	}

	/** Dark translucent panel with a thin border. */
	static void panel(GuiGraphics g, int x, int y, int w, int h) {
		g.fill(x + 2, y + 2, x + w + 2, y + h + 2, SHADOW);
		g.fill(x, y, x + w, y + h, PANEL);
		g.renderOutline(x, y, w, h, BORDER);
	}

	/** Panel with a titled header strip and an accent line under it. */
	static void titledPanel(GuiGraphics g, Font font, int x, int y, int w, int h, String title, int titleColor) {
		panel(g, x, y, w, h);
		g.fill(x + 1, y + 1, x + w - 1, y + 13, HEADER);
		g.fill(x + 1, y + 13, x + w - 1, y + 14, ACCENT);
		g.drawString(font, title, x + 6, y + 3, titleColor);
	}

	/** Full-width header bar with the screen title on the left. */
	static void header(GuiGraphics g, Font font, int width, String title, String subtitle) {
		g.fillGradient(0, 0, width, 22, 0xF01E2E40, 0xF0121A24);
		g.fill(0, 22, width, 23, ACCENT);
		g.drawString(font, title, 10, 7, TITLE);
		if (subtitle != null && !subtitle.isEmpty()) g.drawString(font, subtitle, 14 + font.width(title), 7, MUTED);
	}

	static String ellipsize(Font font, String text, int maxWidth) {
		if (font.width(text) <= maxWidth) return text;
		return font.plainSubstrByWidth(text, Math.max(0, maxWidth - font.width("..."))) + "...";
	}
}
