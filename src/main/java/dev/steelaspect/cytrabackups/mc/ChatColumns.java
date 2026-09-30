package dev.steelaspect.cytrabackups.mc;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * Pads chat text to a pixel width so list columns line up in Minecraft's proportional default font. Widths use the
 * default font's glyph advances; normal spaces are 4 px and bold spaces 5 px, which together can fill any gap from 8 px.
 */
final class ChatColumns {
	private ChatColumns() {
	}

	static int width(String text) {
		int w = 0;
		for (int i = 0; i < text.length(); i++) w += advance(text.charAt(i));
		return w;
	}

	private static int advance(char c) {
		return switch (c) {
			case ' ', 'I', 't', '[', ']', '"', '*' -> 4;
			case '!', ',', '.', ':', ';', 'i', '|', '\'' -> 2;
			case 'l', '`' -> 3;
			case 'f', 'k', '<', '>', '{', '}', '(', ')' -> 5;
			case '@', '~' -> 7;
			default -> 6;
		};
	}

	/** Spaces filling {@code px} pixels (at least one normal space). */
	static MutableComponent gap(int px) {
		px = Math.max(4, px);
		int bold = 0;
		while ((px - bold * 5) % 4 != 0 && bold < 3) bold++;
		int normal = Math.max(0, (px - bold * 5) / 4);
		MutableComponent out = Component.literal(" ".repeat(normal));
		if (bold > 0) out.append(Component.literal(" ".repeat(bold)).withStyle(s -> s.withBold(true)));
		return out;
	}

	/** {@code cell} followed by spaces up to {@code columnWidth} plus a gap of {@code spacing}. */
	static MutableComponent cell(MutableComponent cell, String text, int columnWidth, int spacing) {
		return cell.append(gap(columnWidth - width(text) + spacing));
	}
}
