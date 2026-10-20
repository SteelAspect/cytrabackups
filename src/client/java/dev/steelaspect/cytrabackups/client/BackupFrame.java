package dev.steelaspect.cytrabackups.client;

import dev.steelaspect.alloyui.AlloyScreen;
import dev.steelaspect.alloyui.UiContext;
import dev.steelaspect.alloyui.render.Draw;
import dev.steelaspect.alloyui.theme.Theme;
import dev.steelaspect.alloyui.widget.Button;
import dev.steelaspect.alloyui.widget.Container;
import dev.steelaspect.alloyui.widget.Widget;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The Nocturne frame of the Alloy Backups screens: a centred panel with a header (title left, a muted status right),
 * optional tabs under it, the body, an optional bottom panel (the output log) and a row of footer buttons. Used as the
 * screen's root; clicks outside the panel are not taken.
 */
final class BackupFrame extends Container {
	static final float PAD = 8, GAP = 4;
	private static final float HEADER_H = 22, TABS_H = 18, FOOTER_H = 26, BUTTON_GAP = 6;

	private final Component title;
	private final Supplier<Component> status;
	private @Nullable Widget tabs;
	private @Nullable Widget body;
	private @Nullable Widget bottom;
	private final List<Button> buttons = new ArrayList<>();
	private float maxWidth = 640;
	private float px, py, pw, ph;

	BackupFrame(Component title, Supplier<Component> status) {
		this.title = title;
		this.status = status;
	}

	BackupFrame maxWidth(float w) {
		this.maxWidth = w;
		return this;
	}

	BackupFrame tabs(Widget tabs) {
		this.tabs = add(tabs);
		return this;
	}

	/** Swaps the body (a tab's page). */
	void body(Widget w) {
		if (body != null) children.remove(body);
		body = w;
		children.add(tabs == null ? 0 : 1, w);
	}

	BackupFrame bottom(Widget w) {
		this.bottom = add(w);
		return this;
	}

	Button button(Button b) {
		buttons.add(add(b));
		return b;
	}

	@Override
	public float measure(float width) {
		return 0;
	}

	@Override
	public boolean contains(double mx, double my) {
		return mx >= px && mx < px + pw && my >= py && my < py + ph;
	}

	/** Height of the bottom panel: about a fifth of the frame, 30 to 80 units. */
	private float bottomHeight() {
		return bottom == null ? 0 : Math.max(30, Math.min(80, ph / 5));
	}

	@Override
	protected void layout() {
		float[] f = AlloyScreen.frameBounds(w, h);
		pw = Math.min(f[2], maxWidth);
		ph = f[3];
		px = x + (w - pw) / 2;
		py = y + f[1];
		float top = py + HEADER_H;
		if (tabs != null) {
			tabs.setBounds(px + PAD, top, pw - 2 * PAD, tabs.measure(pw - 2 * PAD));
			top += TABS_H;
		}
		float footerTop = py + ph - FOOTER_H;
		float bh = bottomHeight();
		if (bottom != null) bottom.setBounds(px + PAD, footerTop - GAP - bh, pw - 2 * PAD, bh);
		float bodyBottom = bottom == null ? footerTop - GAP : footerTop - 2 * GAP - bh;
		if (body != null) body.setBounds(px + PAD, top + GAP, pw - 2 * PAD, Math.max(0, bodyBottom - top - GAP));
		float bx = px + pw - PAD;
		for (int i = buttons.size() - 1; i >= 0; i--) {
			Button b = buttons.get(i);
			if (!b.visible()) continue;
			float bw = Math.max(70, b.preferredWidth());
			bx -= bw;
			b.setBounds(bx, footerTop + 5, bw, b.measure(bw));
			bx -= BUTTON_GAP;
		}
	}

	@Override
	protected void renderWidget(UiContext ctx) {
		Theme t = ctx.theme;
		Draw.elevation(ctx.g, px, py, pw, ph, t.metricF("radiusLarge"), 3);
		Draw.panel(ctx.g, px, py, pw, ph, t.metricF("radiusLarge"), t.color("panel"), t.color("border"), t.metricF("borderWidth"));
		float titleW = Draw.textWidth(title, Draw.Role.HEADING);
		Draw.heading(ctx.g, title, px + PAD + 2, py + 8, t.color("text"));
		float sx = px + PAD + 2 + titleW + 12, sw = px + pw - PAD - sx;
		Component s = status.get();
		if (sw > 20 && !s.getString().isEmpty()) {
			float w = Math.min(Draw.textWidth(s), sw);
			Draw.textClipped(ctx.g, s, px + pw - PAD - w, py + 8, sw, t.color("textMuted"));
		}
		if (tabs == null) Draw.fadeRule(ctx.g, px + PAD, py + HEADER_H - 3, pw - 2 * PAD, t.color("border"));
		Draw.fadeRule(ctx.g, px + PAD, py + ph - FOOTER_H, pw - 2 * PAD, t.color("border"));
		super.renderWidget(ctx);
	}
}
