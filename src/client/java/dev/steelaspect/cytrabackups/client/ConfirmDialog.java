package dev.steelaspect.cytrabackups.client;

import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** Themed yes/no dialog used for every CytraBackups confirmation (restore, delete, rollback, prune, ...). */
public final class ConfirmDialog extends Screen {
	private final String message;
	private final String yesLabel;
	private final int yesColor;
	private final BooleanConsumer callback;
	private List<FormattedCharSequence> lines = List.of();

	public ConfirmDialog(String title, String message, String yesLabel, int yesColor, BooleanConsumer callback) {
		super(Component.literal(title));
		this.message = message;
		this.yesLabel = yesLabel;
		this.yesColor = yesColor;
		this.callback = callback;
	}

	private int panelW() {
		return Math.min(380, width - 40);
	}

	private int panelH() {
		return 14 + 10 + lines.size() * 11 + 10 + 20 + 10;
	}

	private int panelY() {
		return Math.max(28, (height - panelH()) / 2);
	}

	@Override
	protected void init() {
		lines = font.split(Component.literal(message), panelW() - 24);
		if (lines.size() > 12) lines = lines.subList(0, 12);
		int by = panelY() + panelH() - 30;
		addRenderableWidget(new FlatButton(width / 2 - 104, by, 100, 20, yesLabel, yesColor, b -> callback.accept(true)));
		addRenderableWidget(new FlatButton(width / 2 + 4, by, 100, 20, "Cancel", Theme.NEUTRAL, b -> callback.accept(false)));
	}

	@Override
	public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		super.renderBackground(g, mouseX, mouseY, partialTick);
		Theme.header(g, font, width, "CytraBackups", "confirm");
		int x = (width - panelW()) / 2;
		Theme.titledPanel(g, font, x, panelY(), panelW(), panelH(), Theme.ellipsize(font, title.getString(), panelW() - 12), Theme.TITLE);
		g.fill(x + 1, panelY() + 14, x + panelW() - 1, panelY() + 15, yesColor);
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		super.render(g, mouseX, mouseY, partialTick);
		int x = (width - panelW()) / 2 + 12, y = panelY() + 14 + 10;
		for (FormattedCharSequence line : lines) {
			g.drawString(font, line, x, y, Theme.TEXT);
			y += 11;
		}
	}

	@Override
	public void onClose() {
		callback.accept(false);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
