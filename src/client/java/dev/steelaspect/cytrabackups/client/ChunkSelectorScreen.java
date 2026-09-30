package dev.steelaspect.cytrabackups.client;

import com.mojang.blaze3d.platform.NativeImage;
import dev.steelaspect.cytrabackups.CytraBackups;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import org.lwjgl.glfw.GLFW;

/**
 * Map-style chunk selector. Chunks the client has loaded are painted once into a small texture from their top-block
 * map colours (a few hundred chunks per tick) and the map is drawn with a single blit, so the frame cost does not grow
 * with the zoom level or screen size. Drag with the left mouse button to select a rectangle, or type chunk coordinates.
 * The selection is restored from the chosen backup in the selected dimension.
 */
public final class ChunkSelectorScreen extends Screen {
	private static final int SAMPLES = 4; // map texels per chunk side (one texel = 4x4 blocks)
	private static final int MAX_RADIUS = 35; // chunks; the client never keeps more than render distance 32 + 3
	private static final int PAINT_BUDGET = 768; // chunks painted per tick
	private static final int REPAINT_INTERVAL_TICKS = 100;
	private static final int MAX_SELECT_RADIUS = 32;
	private static final Identifier MAP_TEXTURE = Identifier.fromNamespaceAndPath(CytraBackups.MOD_ID, "chunk_selector_map");
	private static final int MARGIN = 8;
	private static final int GRAY = 0xFFA0A0A0;

	private final Screen parent;
	private final int backupId;
	private int centerX, centerZ;
	private int cell = 8;
	private Integer selX1, selZ1, selX2, selZ2;
	private boolean dragging;
	private EditBox x1Box, z1Box, x2Box, z2Box;
	private CycleButton<String> dimensionButton;
	private String dimension; // dimension to restore in; the map is only drawn for the one the player is in
	private int radius = 2;
	private DynamicTexture map;
	private int originX, originZ, span; // map texture covers chunks originX.. originX+span-1 (same for Z)
	private final LongSet painted = new LongOpenHashSet();
	private int repaintTicks;

	public ChunkSelectorScreen(Screen parent, int backupId) {
		super(Component.translatable("cytrabackups.gui.area.title", backupId));
		this.parent = parent;
		this.backupId = backupId;
	}

	@Override
	protected void init() {
		if (minecraft.player != null && selX1 == null) {
			ChunkPos cp = minecraft.player.chunkPosition();
			centerX = cp.x;
			centerZ = cp.z;
		}
		String here = currentDimension();
		if (dimension == null) dimension = here;
		List<String> dims = new ArrayList<>(ClientState.list != null ? ClientState.list.dimensions() : List.of());
		if (!dims.contains(here) && !here.isEmpty()) dims.addFirst(here);
		if (!dims.contains(dimension)) dimension = dims.isEmpty() ? here : dims.getFirst();

		List<AbstractWidget> options = new ArrayList<>();
		dimensionButton = null;
		if (!dims.isEmpty()) {
			dimensionButton = CycleButton.<String>builder(ChunkSelectorScreen::dimensionName, dimension).withValues(dims).displayOnlyValue()
				.withTooltip(v -> Tooltip.create(Component.translatable("cytrabackups.gui.area.dimension.tooltip")))
				.create(0, 0, 100, 20, Component.translatable("cytrabackups.gui.area.dimension"), (b, v) -> dimension = v);
			options.add(dimensionButton);
		}
		options.add(new RadiusSlider());
		options.add(button("cytrabackups.gui.area.around_me", "cytrabackups.gui.area.around_me.tooltip", 74, this::selectRadius));
		options.add(button("cytrabackups.gui.area.region", "cytrabackups.gui.area.region.tooltip", 74, this::selectRegion));
		row(height - 76, 4, options);

		x1Box = box("x1");
		z1Box = box("z1");
		x2Box = box("x2");
		z2Box = box("z2");
		row(height - 52, 4, List.of(x1Box, z1Box, x2Box, z2Box, button("cytrabackups.gui.area.select", "cytrabackups.gui.area.select.tooltip", 60, this::fromBoxes)));

		Button restore = Button.builder(Component.translatable("cytrabackups.gui.area.restore"), b -> restore()).width(150)
			.tooltip(Tooltip.create(Component.translatable("cytrabackups.gui.area.restore.tooltip", backupId))).build();
		row(height - 28, 8, List.of(restore, Button.builder(CommonComponents.GUI_BACK, b -> onClose()).width(150).build()));
		syncBoxes();
	}

	private static Button button(String key, String tooltipKey, int width, Runnable action) {
		return Button.builder(Component.translatable(key), b -> action.run()).width(width).tooltip(Tooltip.create(Component.translatable(tooltipKey))).build();
	}

	private EditBox box(String hint) {
		EditBox b = new EditBox(font, 0, 0, 44, 20, Component.literal(hint));
		b.setHint(Component.literal(hint).withStyle(ChatFormatting.DARK_GRAY));
		b.setMaxLength(8);
		b.setFilter(t -> t.matches("-?\\d*"));
		return b;
	}

	/** Adds the widgets centred in one row at {@code y}, narrowed evenly when the screen is too small for them. */
	private void row(int y, int gap, List<? extends AbstractWidget> widgets) {
		int gaps = gap * (widgets.size() - 1), wanted = 0;
		for (AbstractWidget w : widgets) wanted += w.getWidth();
		double scale = Math.min(1, (width - 2 * MARGIN - gaps) / (double) wanted);
		int total = gaps;
		for (AbstractWidget w : widgets) {
			w.setWidth((int) (w.getWidth() * scale));
			total += w.getWidth();
		}
		int x = (width - total) / 2;
		for (AbstractWidget w : widgets) {
			w.setPosition(x, y);
			addRenderableWidget(w);
			x += w.getWidth() + gap;
		}
	}

	private static Component dimensionName(String id) {
		return Component.literal(id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id);
	}

	private String currentDimension() {
		return minecraft.level == null ? "" : minecraft.level.dimension().identifier().toString();
	}

	private boolean showingMap() {
		return dimension != null && dimension.equals(currentDimension());
	}

	private void selectRadius() {
		if (minecraft.player == null) return;
		ChunkPos cp = minecraft.player.chunkPosition();
		dimension = currentDimension();
		if (dimensionButton != null) dimensionButton.setValue(dimension);
		selX1 = cp.x - radius;
		selZ1 = cp.z - radius;
		selX2 = cp.x + radius;
		selZ2 = cp.z + radius;
		centerX = cp.x;
		centerZ = cp.z;
		syncBoxes();
	}

	private void selectRegion() {
		int cx, cz;
		if (selX1 != null) {
			cx = Math.min(selX1, selX2);
			cz = Math.min(selZ1, selZ2);
		} else if (minecraft.player != null) {
			cx = minecraft.player.chunkPosition().x;
			cz = minecraft.player.chunkPosition().z;
		} else {
			return;
		}
		int rx = Math.floorDiv(cx, 32), rz = Math.floorDiv(cz, 32);
		selX1 = rx * 32;
		selZ1 = rz * 32;
		selX2 = rx * 32 + 31;
		selZ2 = rz * 32 + 31;
		centerX = rx * 32 + 16;
		centerZ = rz * 32 + 16;
		syncBoxes();
	}

	private int gridLeft() {
		return MARGIN;
	}

	private int gridTop() {
		return 22;
	}

	private int gridBottom() {
		return height - 106;
	}

	private int cols() {
		return (width - 2 * MARGIN) / cell;
	}

	private int rows() {
		return Math.max(0, gridBottom() - gridTop()) / cell;
	}

	private int chunkAtX(double mx) {
		return centerX - cols() / 2 + (int) Math.floor((mx - gridLeft()) / cell);
	}

	private int chunkAtZ(double my) {
		return centerZ - rows() / 2 + (int) Math.floor((my - gridTop()) / cell);
	}

	private boolean inGrid(double mx, double my) {
		return mx >= gridLeft() && mx < gridLeft() + cols() * cell && my >= gridTop() && my < gridTop() + rows() * cell;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) return true;
		if (event.button() == 0 && inGrid(event.x(), event.y())) {
			selX1 = selX2 = chunkAtX(event.x());
			selZ1 = selZ2 = chunkAtZ(event.y());
			dragging = true;
			syncBoxes();
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (dragging) {
			selX2 = chunkAtX(Math.max(gridLeft(), Math.min(event.x(), gridLeft() + cols() * cell - 1)));
			selZ2 = chunkAtZ(Math.max(gridTop(), Math.min(event.y(), gridTop() + rows() * cell - 1)));
			syncBoxes();
			return true;
		}
		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		dragging = false;
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		cell = Math.max(3, Math.min(24, cell + (int) Math.signum(scrollY)));
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (getFocused() instanceof EditBox && getFocused().isFocused()) return super.keyPressed(event);
		int step = 4;
		switch (event.key()) {
			case GLFW.GLFW_KEY_LEFT -> centerX -= step;
			case GLFW.GLFW_KEY_RIGHT -> centerX += step;
			case GLFW.GLFW_KEY_UP -> centerZ -= step;
			case GLFW.GLFW_KEY_DOWN -> centerZ += step;
			default -> {
				return super.keyPressed(event);
			}
		}
		return true;
	}

	private void syncBoxes() {
		if (x1Box == null) return;
		x1Box.setValue(selX1 == null ? "" : String.valueOf(Math.min(selX1, selX2)));
		z1Box.setValue(selZ1 == null ? "" : String.valueOf(Math.min(selZ1, selZ2)));
		x2Box.setValue(selX2 == null ? "" : String.valueOf(Math.max(selX1, selX2)));
		z2Box.setValue(selZ2 == null ? "" : String.valueOf(Math.max(selZ1, selZ2)));
	}

	private void fromBoxes() {
		try {
			selX1 = Integer.parseInt(x1Box.getValue().trim());
			selZ1 = Integer.parseInt(z1Box.getValue().trim());
			selX2 = Integer.parseInt(x2Box.getValue().trim());
			selZ2 = Integer.parseInt(z2Box.getValue().trim());
			centerX = (selX1 + selX2) / 2;
			centerZ = (selZ1 + selZ2) / 2;
		} catch (NumberFormatException e) {
			ClientState.log(Component.translatable("cytrabackups.gui.area.bad_coords"), true);
		}
	}

	/** The server asks for confirmation, then restores the chunks live or queues them. */
	private void restore() {
		if (selX1 == null || dimension == null || dimension.isEmpty()) {
			ClientState.log(Component.translatable("cytrabackups.gui.area.nothing_selected"), true);
			return;
		}
		int x1 = Math.min(selX1, selX2), x2 = Math.max(selX1, selX2), z1 = Math.min(selZ1, selZ2), z2 = Math.max(selZ1, selZ2);
		ClientState.run("restore " + backupId + " chunks " + dimension + " " + x1 + " " + z1 + " " + x2 + " " + z2);
	}

	/** Creates the map texture for the client's loaded area around the player, once per screen visit. */
	private void ensureMap() {
		if (map != null || minecraft.player == null || minecraft.level == null) return;
		// Same window the client keeps chunks for (ClientChunkCache storage range), capped for the texture size.
		int radius = Math.min(MAX_RADIUS, Math.max(2, minecraft.options.getEffectiveRenderDistance()) + 3);
		ChunkPos cp = minecraft.player.chunkPosition();
		originX = cp.x - radius;
		originZ = cp.z - radius;
		span = 2 * radius + 1;
		map = new DynamicTexture(() -> "cytrabackups chunk selector map", span * SAMPLES, span * SAMPLES, true);
		minecraft.getTextureManager().register(MAP_TEXTURE, map);
		painted.clear();
		paint(PAINT_BUDGET);
	}

	/** Paints up to {@code budget} loaded, not yet painted chunks into the map texture and uploads it if anything changed. */
	private void paint(int budget) {
		ClientLevel level = minecraft.level;
		NativeImage img = map == null ? null : map.getPixels();
		if (level == null || img == null) return;
		boolean changed = false;
		for (int dz = 0; dz < span && budget > 0; dz++) {
			for (int dx = 0; dx < span && budget > 0; dx++) {
				int cx = originX + dx, cz = originZ + dz;
				long key = ChunkPos.asLong(cx, cz);
				if (painted.contains(key)) continue;
				LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
				if (chunk == null) continue;
				paintChunk(level, chunk, img, dx * SAMPLES, dz * SAMPLES);
				painted.add(key);
				changed = true;
				budget--;
			}
		}
		if (changed) map.upload();
	}

	/** 4x4 map-style samples (one per 4x4 blocks) with vanilla-map height shading against the sample to the north. */
	private static void paintChunk(ClientLevel level, LevelChunk chunk, NativeImage img, int tx, int tz) {
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		int baseX = chunk.getPos().getMinBlockX(), baseZ = chunk.getPos().getMinBlockZ();
		for (int sz = 0; sz < SAMPLES; sz++) {
			for (int sx = 0; sx < SAMPLES; sx++) {
				int bx = baseX + sx * 4 + 2, bz = baseZ + sz * 4 + 2;
				int top = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, bx, bz);
				MapColor mc = MapColor.NONE;
				int y = top;
				// Like vanilla maps, look through blocks without a map colour (glass, barriers) for up to 16 blocks.
				for (int i = 0; i < 16 && y >= level.getMinY(); i++, y--) {
					pos.set(bx, y, bz);
					BlockState state = chunk.getBlockState(pos);
					mc = state.getMapColor(level, pos);
					if (mc != MapColor.NONE) break;
				}
				int argb;
				if (mc == MapColor.NONE) {
					argb = 0xFF202020;
				} else {
					int north = level.getHeight(Heightmap.Types.WORLD_SURFACE, bx, bz - 4) - 1;
					MapColor.Brightness b = north < level.getMinY() || mc == MapColor.WATER ? MapColor.Brightness.NORMAL
						: top > north ? MapColor.Brightness.HIGH : top < north ? MapColor.Brightness.LOW : MapColor.Brightness.NORMAL;
					argb = mc.calculateARGBColor(b) | 0xFF000000;
				}
				img.setPixel(tx + sx, tz + sz, argb);
			}
		}
	}

	@Override
	public void tick() {
		ensureMap();
		if (++repaintTicks >= REPAINT_INTERVAL_TICKS) {
			// Pick up blocks changed while the screen is open; repainting is spread over the next ticks.
			repaintTicks = 0;
			painted.clear();
		}
		paint(PAINT_BUDGET);
	}

	@Override
	public void removed() {
		if (map != null) {
			minecraft.getTextureManager().release(MAP_TEXTURE);
			map = null;
		}
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		super.render(g, mouseX, mouseY, partialTick);
		g.drawCenteredString(font, title, width / 2, 8, 0xFFFFFFFF);
		ensureMap();
		int cols = cols(), rows = rows();
		int left = gridLeft(), top = gridTop(), right = left + cols * cell, bottom = top + rows * cell;
		int startX = centerX - cols / 2, startZ = centerZ - rows / 2;
		// A handful of draw calls per frame: background, the map texture, region lines, selection and markers.
		g.fill(left, top, right, bottom, 0xFF000000);
		g.renderOutline(left - 1, top - 1, right - left + 2, bottom - top + 2, GRAY);
		if (map != null && showingMap()) {
			int mx = left + (originX - startX) * cell, my = top + (originZ - startZ) * cell, size = span * cell, tex = span * SAMPLES;
			g.enableScissor(left, top, right, bottom);
			g.blit(RenderPipelines.GUI_TEXTURED, MAP_TEXTURE, mx, my, 0, 0, size, size, tex, tex, tex, tex);
			g.disableScissor();
		} else if (rows > 0) {
			Component noMap = Component.translatable("cytrabackups.gui.area.no_map", dimension == null ? "" : dimensionName(dimension));
			g.drawCenteredString(font, BackupScreen.clip(font, noMap, right - left - 8), left + (right - left) / 2, top + (bottom - top) / 2 - 4, GRAY);
		}
		for (int c = Math.floorMod(-startX, 32); c < cols; c += 32) g.fill(left + c * cell, top, left + c * cell + 1, bottom, 0x60FFFFFF);
		for (int r = Math.floorMod(-startZ, 32); r < rows; r += 32) g.fill(left, top + r * cell, right, top + r * cell + 1, 0x60FFFFFF);
		if (selX1 != null) {
			int x1 = Math.min(selX1, selX2), x2 = Math.max(selX1, selX2), z1 = Math.min(selZ1, selZ2), z2 = Math.max(selZ1, selZ2);
			int sx1 = Math.max(left, left + (x1 - startX) * cell), sz1 = Math.max(top, top + (z1 - startZ) * cell);
			int sx2 = Math.min(right, left + (x2 - startX + 1) * cell), sz2 = Math.min(bottom, top + (z2 - startZ + 1) * cell);
			if (sx2 > sx1 && sz2 > sz1) {
				g.fill(sx1, sz1, sx2, sz2, 0x40FFFFFF);
				g.renderOutline(sx1, sz1, sx2 - sx1, sz2 - sz1, 0xFFFFFFFF);
			}
		}
		if (minecraft.player != null && showingMap()) {
			ChunkPos pc = minecraft.player.chunkPosition();
			int px = left + (pc.x - startX) * cell + cell / 2, py = top + (pc.z - startZ) * cell + cell / 2;
			if (px >= left && py >= top && px < right && py < bottom) {
				g.fill(px - 3, py - 3, px + 3, py + 3, 0xFF000000);
				g.fill(px - 2, py - 2, px + 2, py + 2, 0xFFFFFFFF);
			}
		}
		int textW = width - 2 * MARGIN;
		g.drawString(font, BackupScreen.clip(font, selectionText(), textW), MARGIN, height - 100, 0xFFFFFFFF);
		Component second = null;
		int color = GRAY;
		if (inGrid(mouseX, mouseY)) {
			int hx = chunkAtX(mouseX), hz = chunkAtZ(mouseY);
			second = Component.translatable("cytrabackups.gui.area.hover", hx, hz, hx * 16, hz * 16, Math.floorDiv(hx, 32), Math.floorDiv(hz, 32));
		} else {
			ClientState.LogLine last = ClientState.lastLog();
			if (last != null && System.currentTimeMillis() - last.at() < 30_000) {
				second = last.text();
				color = last.error() ? 0xFFFF5555 : 0xFFFFFFFF;
			}
		}
		if (second != null) g.drawString(font, BackupScreen.clip(font, second, textW), MARGIN, height - 89, color);
	}

	private Component selectionText() {
		if (selX1 == null) return Component.translatable("cytrabackups.gui.area.hint").withStyle(ChatFormatting.GRAY);
		int x1 = Math.min(selX1, selX2), x2 = Math.max(selX1, selX2), z1 = Math.min(selZ1, selZ2), z2 = Math.max(selZ1, selZ2);
		long count = (long) (x2 - x1 + 1) * (z2 - z1 + 1);
		return Component.translatable(count == 1 ? "cytrabackups.gui.area.selection.one" : "cytrabackups.gui.area.selection", x1, z1, x2, z2, count, x1 * 16, z1 * 16, x2 * 16 + 15, z2 * 16 + 15);
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	/** Radius for "Around me", 0 to 32 chunks. */
	private final class RadiusSlider extends AbstractSliderButton {
		RadiusSlider() {
			super(0, 0, 80, 20, Component.empty(), radius / (double) MAX_SELECT_RADIUS);
			setTooltip(Tooltip.create(Component.translatable("cytrabackups.gui.area.radius.tooltip")));
			updateMessage();
		}

		@Override
		protected void updateMessage() {
			setMessage(Component.translatable("cytrabackups.gui.area.radius", radius));
		}

		@Override
		protected void applyValue() {
			radius = (int) Math.round(value * MAX_SELECT_RADIUS);
		}
	}
}
