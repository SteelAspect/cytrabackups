package dev.steelaspect.cytrabackups.client;

import dev.steelaspect.alloyui.UiContext;
import dev.steelaspect.alloyui.render.Draw;
import dev.steelaspect.alloyui.widget.Button;
import dev.steelaspect.alloyui.widget.Container;
import dev.steelaspect.alloyui.widget.Widget;
import java.util.List;
import net.minecraft.network.chat.Component;

/**
 * One line of the Restore and Tools tabs: a label on the left (its hint as a tooltip) and controls on the right. When
 * the controls don't fit next to the label they go on their own line under it.
 */
final class ActionRow extends Container {
	private static final float ROW = 22, GAP = 4, LABEL_MIN = 90;

	private final Component label;
	private final Component hint;
	private boolean below;

	ActionRow(Component label, Component hint, Widget... controls) {
		this.label = label;
		this.hint = hint;
		for (Widget c : controls) add(c);
	}

	private static float controlWidth(Widget c) {
		return c instanceof Button b ? Math.max(56, b.preferredWidth()) : 110;
	}

	private float controlsWidth() {
		float total = 0;
		for (Widget c : children) total += controlWidth(c) + GAP;
		return Math.max(0, total - GAP);
	}

	@Override
	public float measure(float width) {
		below = width - controlsWidth() < LABEL_MIN;
		return below ? 12 + ROW : ROW;
	}

	@Override
	protected void layout() {
		float cw = controlsWidth();
		float scale = Math.min(1, w / Math.max(1, cw));
		float cx = below ? x : x + w - cw * scale, cy = below ? y + 12 : y + 3;
		for (Widget c : children) {
			float ww = controlWidth(c) * scale;
			c.setBounds(cx, cy, ww, 16);
			cx += ww + GAP * scale;
		}
	}

	@Override
	protected void renderWidget(UiContext ctx) {
		var t = ctx.theme;
		float labelW = below ? w : w - controlsWidth() - 8;
		float ly = below ? y + 1 : y + (ROW - 8) / 2;
		Draw.textClipped(ctx.g, label, x, ly, labelW, t.color("text"));
		if (ctx.hovering(x, y, Math.min(labelW, Draw.textWidth(label)), below ? 10 : ROW)) {
			ctx.host.showTooltip(List.of(hint), ctx.mouseX, ctx.mouseY);
		}
		super.renderWidget(ctx);
	}
}
