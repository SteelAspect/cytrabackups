package dev.steelaspect.cytrabackups.client;

import com.mojang.blaze3d.platform.NativeImage;
import dev.steelaspect.cytrabackups.CytraBackups;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
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
 * The selection is restored from the chosen backup in the player's current dimension.
 */
public final class ChunkSelectorScreen extends Screen {
	private static final int SAMPLES = 4; // map texels per chunk side (one texel = 4x4 blocks)
	private static final int MAX_RADIUS = 35; // chunks; the client never keeps more than render distance 32 + 3
	private static final int PAINT_BUDGET = 768; // chunks painted per tick
	private static final int REPAINT_INTERVAL_TICKS = 100;
	private static final Identifier MAP_TEXTURE = Identifier.fromNamespaceAndPath(CytraBackups.MOD_ID, "chunk_selector_map");

	private final Screen parent;
	private final int backupId;
	private int centerX, centerZ;
	private int cell = 8;
	private Integer selX1, selZ1, selX2, selZ2;
	private boolean dragging;
	private EditBox x1Box, z1Box, x2Box, z2Box, radiusBox;
	private String dimension; // dimension to restore in; the map is only drawn for the one the player is in
	private String radiusText = "2";
	private DynamicTexture map;
	private int originX, originZ, span; // map texture covers chunks originX.. originX+span-1 (same for Z)
	private final LongSet painted = new LongOpenHashSet();
	private int repaintTicks;

	public ChunkSelectorScreen(Screen parent, int backupId) {
		super(Component.literal("Select chunks to restore from #" + backupId));
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
		int y = height - 28;
		int bw = 44;
		x1Box = box(10, y, "x1");
		z1Box = box(10 + bw + 4, y, "z1");
		x2Box = box(10 + 2 * (bw + 4), y, "x2");
		z2Box = box(10 + 3 * (bw + 4), y, "z2");
		addRenderableWidget(Button.builder(Component.literal("Use coords"), b -> fromBoxes()).bounds(10 + 4 * (bw + 4), y, 70, 20)
			.tooltip(Tooltip.create(Component.literal("Select the chunk rectangle typed in the boxes (chunk coordinates = block coordinates / 16)"))).build());
		addRenderableWidget(Button.builder(Component.literal("Restore selection..."), b -> restore()).bounds(width - 230, y, 130, 20)
			.tooltip(Tooltip.create(Component.literal("Restore the selected chunks from backup #" + backupId + ". The server asks you to confirm."))).build());
		addRenderableWidget(Button.builder(Component.literal("Back"), b -> onClose()).bounds(width - 95, y, 85, 20).build());

		int y2 = height - 52;
		List<String> dims = new ArrayList<>(ClientState.list != null ? ClientState.list.dimensions() : List.of());
		if (!dims.contains(here) && !here.isEmpty()) dims.addFirst(here);
		if (!dims.contains(dimension)) dimension = here;
		if (!dims.isEmpty()) {
			addRenderableWidget(CycleButton.<String>builder(Component::literal, dimension).withValues(dims)
				.withTooltip(v -> Tooltip.create(Component.literal("Dimension to restore in. The map is only shown for the dimension you are in; "
					+ "elsewhere, type chunk coordinates.")))
				.create(10, y2, 200, 20, Component.literal("Dimension"), (btn, v) -> dimension = v));
		}
		radiusBox = new EditBox(font, 216, y2, 30, 20, Component.literal("radius"));
		radiusBox.setMaxLength(2);
		radiusBox.setFilter(t -> t.matches("\\d*"));
		radiusBox.setValue(radiusText);
		radiusBox.setResponder(t -> radiusText = t);
		radiusBox.setTooltip(Tooltip.create(Component.literal("Radius in chunks (0-32) for Around me")));
		addRenderableWidget(radiusBox);
		addRenderableWidget(Button.builder(Component.literal("Around me"), b -> selectRadius()).bounds(250, y2, 80, 20)
			.tooltip(Tooltip.create(Component.literal("Select the square of chunks within this radius of your position (like /backup restore <id> radius <r>)"))).build());
		addRenderableWidget(Button.builder(Component.literal("Whole region"), b -> selectRegion()).bounds(334, y2, 90, 20)
			.tooltip(Tooltip.create(Component.literal("Grow the selection to the whole region file (32x32 chunks) it starts in, or yours if nothing is "
				+ "selected (like /backup restore <id> region ...)"))).build());
		syncBoxes();
	}

	private String currentDimension() {
		return minecraft.level == null ? "" : minecraft.level.dimension().identifier().toString();
	}

	private boolean showingMap() {
		return dimension != null && dimension.equals(currentDimension());
	}

	private void selectRadius() {
		if (minecraft.player == null) return;
		int r;
		try {
			r = Math.min(32, Integer.parseInt(radiusBox.getValue().trim()));
		} catch (NumberFormatException e) {
			r = 0;
		}
		ChunkPos cp = minecraft.player.chunkPosition();
		dimension = currentDimension();
		selX1 = cp.x - r;
		selZ1 = cp.z - r;
		selX2 = cp.x + r;
		selZ2 = cp.z + r;
		centerX = cp.x;
		centerZ = cp.z;
		rebuildWidgets();
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

	private EditBox box(int x, int y, String hint) {
		EditBox b = new EditBox(font, x, y, 44, 20, Component.literal(hint));
		b.setHint(Component.literal(hint));
		b.setMaxLength(8);
		return addRenderableWidget(b);
	}

	private int gridLeft() {
		return 10;
	}

	private int gridTop() {
		return 24;
	}

	private int gridW() {
		return width - 20;
	}

	private int gridH() {
		return height - 24 - 94;
	}

	private int cols() {
		return gridW() / cell;
	}

	private int rows() {
		return gridH() / cell;
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
			ClientState.log(Component.literal("Enter four chunk coordinates (x1 z1 x2 z2)"), true);
		}
	}

	/** Runs /backup restore <id> chunks ...; the server shows the confirmation dialog and restores live or queues. */
	private void restore() {
		if (selX1 == null || dimension == null || dimension.isEmpty()) {
			ClientState.log(Component.literal("Select chunks first: drag on the map, type coordinates, or use Around me / Whole region."), true);
			return;
		}
		int x1 = Math.min(selX1, selX2), x2 = Math.max(selX1, selX2), z1 = Math.min(selZ1, selZ2), z2 = Math.max(selZ1, selZ2);
		ClientState.run("backup restore " + backupId + " chunks " + dimension + " " + x1 + " " + z1 + " " + x2 + " " + z2);
	}

	static String blockRange(int x1, int z1, int x2, int z2) {
		return "blocks x " + x1 * 16 + ".." + (x2 * 16 + 15) + ", z " + z1 * 16 + ".." + (z2 * 16 + 15);
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
		g.drawString(font, title, 10, 8, 0xFFFFAA00);
		ClientLevel level = minecraft.level;
		if (level == null) return;
		ensureMap();
		int cols = cols(), rows = rows();
		int left = gridLeft(), top = gridTop(), right = left + cols * cell, bottom = top + rows * cell;
		int startX = centerX - cols / 2, startZ = centerZ - rows / 2;
		// A handful of draw calls per frame: background, the map texture, region lines, selection and markers.
		g.fill(left, top, right, bottom, 0xFF161616);
		if (map != null && showingMap()) {
			int mx = left + (originX - startX) * cell, my = top + (originZ - startZ) * cell, size = span * cell, tex = span * SAMPLES;
			g.enableScissor(left, top, right, bottom);
			g.blit(RenderPipelines.GUI_TEXTURED, MAP_TEXTURE, mx, my, 0, 0, size, size, tex, tex, tex, tex);
			g.disableScissor();
		}
		for (int c = Math.floorMod(-startX, 32); c < cols; c += 32) g.fill(left + c * cell, top, left + c * cell + 1, bottom, 0x60FFFFFF);
		for (int r = Math.floorMod(-startZ, 32); r < rows; r += 32) g.fill(left, top + r * cell, right, top + r * cell + 1, 0x60FFFFFF);
		if (selX1 != null) {
			int x1 = Math.min(selX1, selX2), x2 = Math.max(selX1, selX2), z1 = Math.min(selZ1, selZ2), z2 = Math.max(selZ1, selZ2);
			int sx1 = Math.max(left, left + (x1 - startX) * cell), sz1 = Math.max(top, top + (z1 - startZ) * cell);
			int sx2 = Math.min(left + cols * cell, left + (x2 - startX + 1) * cell), sz2 = Math.min(top + rows * cell, top + (z2 - startZ + 1) * cell);
			if (sx2 > sx1 && sz2 > sz1) {
				g.fill(sx1, sz1, sx2, sz2, 0x60FF3030);
				g.renderOutline(sx1, sz1, sx2 - sx1, sz2 - sz1, 0xFFFF5050);
			}
			String info = "Selected: chunks " + x1 + "," + z1 + " to " + x2 + "," + z2 + " (" + ((long) (x2 - x1 + 1) * (z2 - z1 + 1)) + " chunks; "
				+ blockRange(x1, z1, x2, z2) + ")";
			g.drawString(font, info, 10, height - 88, 0xFFFFFFFF);
		} else {
			g.drawString(font, "Drag to select chunks. Scroll = zoom, arrow keys = pan. Region borders are highlighted.", 10, height - 88, 0xFFA0A0A0);
		}
		if (!showingMap()) {
			g.drawCenteredString(font, "No map for " + dimension + " (you are in " + currentDimension() + "). Type chunk coordinates below.",
				left + (right - left) / 2, top + (bottom - top) / 2, 0xFFA0A0A0);
		}
		ClientState.LogLine last = ClientState.lastLog();
		if (last != null && System.currentTimeMillis() - last.at() < 30_000) {
			List<net.minecraft.util.FormattedCharSequence> lines = font.split(last.text(), width - 20);
			if (!lines.isEmpty()) g.drawString(font, lines.getFirst(), 10, height - 76, last.error() ? 0xFFFF5555 : 0xFFE0E0E0);
		}
		if (minecraft.player != null && showingMap()) {
			ChunkPos pc = minecraft.player.chunkPosition();
			int px = left + (pc.x - startX) * cell + cell / 2, py = top + (pc.z - startZ) * cell + cell / 2;
			if (px >= left && py >= top && px < left + cols * cell && py < top + rows * cell) g.fill(px - 2, py - 2, px + 2, py + 2, 0xFFFFFFFF);
		}
		if (inGrid(mouseX, mouseY)) {
			int hx = chunkAtX(mouseX), hz = chunkAtZ(mouseY);
			String hover = "Chunk " + hx + ", " + hz + "  (blocks " + hx * 16 + ", " + hz * 16 + "; region " + Math.floorDiv(hx, 32) + ", " + Math.floorDiv(hz, 32) + ")";
			g.drawString(font, hover, width - 10 - font.width(hover), 8, 0xFFFFFFFF);
		}
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
