package dev.steelaspect.cytrabackups.client;

import dev.steelaspect.alloyui.AlloyScreen;
import dev.steelaspect.alloyui.UiContext;
import dev.steelaspect.alloyui.render.Draw;
import dev.steelaspect.alloyui.widget.Button;
import dev.steelaspect.alloyui.widget.Container;
import dev.steelaspect.alloyui.widget.Overlay;
import dev.steelaspect.alloyui.widget.Column;
import dev.steelaspect.alloyui.widget.Dialog;
import dev.steelaspect.alloyui.widget.Label;
import dev.steelaspect.alloyui.widget.TextField;
import dev.steelaspect.alloyui.widget.Widget;
import dev.steelaspect.cytrabackups.net.PromptPayload;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import dev.steelaspect.alloyui.input.KeyEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/** The Nocturne dialogs of Alloy Backups: server confirmations, small text forms and plain notices. */
public final class Dialogs {
	/** One text field; {@code labelKey} is a lang key. */
	record Field(String labelKey, String initial, int maxLength) {
	}

	/** The dialog opened last, while it is open (tests find its buttons through {@link #find}). */
	static @Nullable Widget open;

	private Dialogs() {
	}

	/** Opens {@code dialog} on the current screen when it is an Alloy screen, otherwise on a dimmed host screen. */
	static void show(Widget dialog) {
		Minecraft mc = Minecraft.getInstance();
		AlloyScreen host;
		if (mc.screen instanceof AlloyScreen a) {
			host = a;
		} else {
			host = new HostScreen(mc.screen);
			mc.setScreen(host);
		}
		open = dialog;
		host.openOverlay(dialog, true);
	}

	/**
	 * A GUI action needs confirmation: answers with /cbackup confirm|deny <token>. Every server confirmation is for
	 * something that changes or removes backups or the world, so the confirm button is red.
	 */
	static void prompt(PromptPayload p) {
		show(new PromptDialog(p));
	}

	/** A dialog with labelled text fields; Enter in a field or the confirm button submits. */
	static void form(Component title, Component message, List<Field> fields, Consumer<List<String>> onDone) {
		List<TextField> boxes = new ArrayList<>();
		Column body = new Column(BackupFrame.GAP);
		Dialog[] self = new Dialog[1];
		Runnable submit = () -> {
			List<String> values = new ArrayList<>();
			for (TextField b : boxes) values.add(b.text());
			close(self[0]);
			onDone.accept(values);
		};
		for (Field f : fields) {
			body.add(new Label(Component.translatable(f.labelKey())).color("textMuted"));
			TextField box = new TextField(f::initial, v -> {}).live().maxLength(f.maxLength()).onEnter(submit);
			boxes.add(body.add(box));
		}
		self[0] = new Tracked(title, message, CommonComponents.GUI_DONE, false, d -> submit.run());
		self[0].body(body).width(280);
		show(self[0]);
	}

	/** A yes/no question asked by the GUI itself; the confirm button is red when {@code danger}. */
	static void confirm(Component title, Component message, Component confirmLabel, boolean danger, Runnable onConfirm) {
		show(new Tracked(title, message, confirmLabel, danger, d -> onConfirm.run()));
	}

	/** A message with only a Back button, e.g. where the menu works. */
	static Widget notice(Component title, Component message) {
		Notice n = new Notice(title, message);
		open = n;
		return n;
	}

	private static void close(@Nullable Widget d) {
		if (d != null && Minecraft.getInstance().screen instanceof AlloyScreen a) a.closeOverlay(d);
	}

	/** A dialog that stays findable by tests while it is open. */
	private static class Tracked extends Dialog {
		Tracked(Component title, Component message, Component confirmLabel, boolean danger, Consumer<Dialog> onConfirm) {
			super(title, message, confirmLabel, danger, onConfirm);
		}

		@Override
		public void onClosed() {
			super.onClosed();
			if (open == this) open = null;
		}
	}

	/** Closing it without confirming answers "deny", like the old No button. */
	private static final class PromptDialog extends Tracked {
		private final String token;
		private boolean answered;

		PromptDialog(PromptPayload p) {
			super(Component.literal(p.title()), Component.literal(p.details()), Component.translatable("cytrabackups.gui.confirm"), true, d -> {
				((PromptDialog) d).answered = true;
				ClientState.run("confirm " + p.token());
			});
			this.token = p.token();
		}

		@Override
		public void onClosed() {
			super.onClosed();
			if (!answered) {
				answered = true;
				ClientState.run("deny " + token);
			}
		}
	}

	/** Title, message and one Back button. Enter or Esc closes it. */
	private static final class Notice extends Container implements Overlay {
		private final Label title;
		private final Label message;
		private final Button back;

		Notice(Component title, Component message) {
			this.title = add(new Label(title).heading());
			this.message = add(new Label(message).color("textMuted"));
			this.back = add(new Button(CommonComponents.GUI_BACK, () -> close(this)).style(Button.Style.PRIMARY));
		}

		@Override
		public boolean centered() {
			return true;
		}

		@Override
		public void onClosed() {
			if (open == this) open = null;
		}

		@Override
		public float measure(float width) {
			return 12 + title.measure(width - 24) + 6 + message.measure(width - 24) + 10 + 18 + 12;
		}

		@Override
		protected void layout() {
			float inner = w - 24, cy = y + 12;
			float th = title.measure(inner);
			title.setBounds(x + 12, cy, inner, th);
			cy += th + 6;
			message.setBounds(x + 12, cy, inner, message.measure(inner));
			back.setBounds(x + w - 12 - 90, y + h - 12 - 18, 90, 18);
		}

		@Override
		protected void renderWidget(UiContext ctx) {
			var t = ctx.theme;
			Draw.roundRect(ctx.g, x + 2, y + 3, w, h, t.metricF("radius"), t.color("shadow"));
			Draw.panel(ctx.g, x, y, w, h, t.metricF("radius"), t.color("panel"), t.color("border"), t.metricF("borderWidth"));
			super.renderWidget(ctx);
		}

		@Override
		public boolean mouseClicked(UiContext ctx, double mx, double my, int button) {
			if (!contains(mx, my)) return true; // modal: swallow
			return super.mouseClicked(ctx, mx, my, button);
		}

		@Override
		public boolean keyPressed(UiContext ctx, KeyEvent e) {
			int k = e.key();
			if (k == GLFW.GLFW_KEY_ENTER || k == GLFW.GLFW_KEY_KP_ENTER || k == GLFW.GLFW_KEY_ESCAPE) {
				close(this);
				return true;
			}
			return false;
		}
	}

	/**
	 * Holds a dialog when no Alloy screen is open (a confirmation arriving after the menu closed, the Mod Menu notice).
	 * It goes back to the screen before it once the dialog closes.
	 */
	public static final class HostScreen extends AlloyScreen {
		HostScreen(@Nullable Screen parent) {
			super(Component.translatable("cytrabackups.gui.title"), parent);
		}

		/** For tests: the centre of the open dialog's widget labelled {@code label}, in GUI units, or null. */
		public double @Nullable [] pointForTest(String label) {
			Widget w = find(root(), label);
			return w == null ? null : new double[] {(w.x() + w.w() / 2) * uiScale(), (w.y() + w.h() / 2) * uiScale()};
		}

		@Override
		protected Backdrop backdrop() {
			return Backdrop.DIM;
		}

		@Override
		public void tick() {
			if (!hasOverlay()) onClose();
		}

		@Override
		public boolean isPauseScreen() {
			return false;
		}
	}

	/** Finds the widget whose label is {@code text} in {@code root} or the open dialog (tests click it there). */
	static @Nullable Widget find(@Nullable Widget root, String text) {
		Widget w = open != null ? findIn(open, text) : null;
		return w != null ? w : findIn(root, text);
	}

	private static @Nullable Widget findIn(@Nullable Widget w, String text) {
		if (w == null || !w.visible()) return null;
		Component label = w.searchLabel();
		if (label != null && label.getString().equals(text)) return w;
		for (Widget c : w.children()) {
			Widget f = findIn(c, text);
			if (f != null) return f;
		}
		return null;
	}
}
