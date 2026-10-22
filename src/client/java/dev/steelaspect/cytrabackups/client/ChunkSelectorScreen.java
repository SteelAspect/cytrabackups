package dev.steelaspect.cytrabackups.client;

import com.mojang.blaze3d.platform.NativeImage;
import dev.steelaspect.alloyui.AlloyScreen;
import dev.steelaspect.alloyui.Toast;
import dev.steelaspect.alloyui.UiContext;
import dev.steelaspect.alloyui.render.Draw;
import dev.steelaspect.alloyui.theme.Theme;
import dev.steelaspect.alloyui.theme.ThemeManager;
import dev.steelaspect.alloyui.widget.Button;
import dev.steelaspect.alloyui.widget.Container;
import dev.steelaspect.alloyui.widget.Dropdown;
import dev.steelaspect.alloyui.widget.Stepper;
import dev.steelaspect.alloyui.widget.TextField;
import dev.steelaspect.alloyui.widget.Widget;
import dev.steelaspect.cytrabackups.CytraBackups;
import dev.steelaspect.cytrabackups.mc.BackupCommands;
import dev.steelaspect.cytrabackups.net.BackupListPayload;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
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
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/**
 * Map-style chunk selector. Chunks the client has loaded are painted once into a small texture from their top-block
 * map colours (a few hundred chunks per tick) and the map is drawn with a single blit, so the frame cost does not grow
 * with the zoom level or screen size. Drag with the left mouse button to select a rectangle, or type chunk coordinates.
 * The selection is restored from the chosen backup in the selected dimension, now or later with a copied command.
 */
public final class ChunkSelectorScreen extends AlloyScreen {
	private static final int SAMPLES = 4; // map texels per chunk side (one texel = 4x4 blocks)
	private static final int MAX_RADIUS = 35; // chunks; the client never keeps more than render distance 32 + 3
	private static final int PAINT_BUDGET = 768; // chunks painted per tick
	private static final int REPAINT_INTERVAL_TICKS = 100;
	private static final int MAX_SELECT_RADIUS = 32;
	private static final Identifier MAP_TEXTURE = Identifier.fromNamespaceAndPath(CytraBackups.MOD_ID, "chunk_selector_map");
	private static final int MARGIN = 8;
	private static final int PREVIEW_MAX_CHUNKS = 256; // same as the server's limit (BackupManager.PREVIEW_MAX_CHUNKS)

	private final int backupId;
	private int centerX, centerZ;
	private int cell = 8;
	private Integer selX1, selZ1, selX2, selZ2;
	private boolean dragging;
	private final TextField x1Box = box("x1"), z1Box = box("z1"), x2Box = box("x2"), z2Box = box("z2");
	private final List<String> dimensions = new ArrayList<>();
	private String dimension; // dimension to restore in; the map is only drawn for the one the player is in
	private int radius = 2;
	private DynamicTexture map;
	private int originX, originZ, span; // map texture covers chunks originX.. originX+span-1 (same for Z)
	private final LongSet painted = new LongOpenHashSet();
	private int repaintTicks;
	private final Controls controls;

	public ChunkSelectorScreen(@Nullable Screen parent, int backupId) {
		super(Component.translatable("cytrabackups.gui.area.title", backupId), parent);
		this.backupId = backupId;
		Minecraft mc = Minecraft.getInstance();
		if (mc.player != null) {
			ChunkPos cp = mc.player.chunkPosition();
			centerX = cp.x;
			centerZ = cp.z;
		}
		String here = currentDimension(mc);
		dimension = here;
		if (ClientState.list != null) dimensions.addAll(ClientState.list.dimensions());
		if (!dimensions.contains(here) && !here.isEmpty()) dimensions.addFirst(here);
		if (!dimensions.contains(dimension)) dimension = dimensions.isEmpty() ? here : dimensions.getFirst();

		List<Widget> options = new ArrayList<>();
		if (!dimensions.isEmpty()) {
			Dropdown<String> dims = new Dropdown<>(() -> dimensions, ChunkSelectorScreen::dimensionName, () -> dimension, v -> dimension = v);
			dims.tooltip(Component.translatable("cytrabackups.gui.area.dimension.tooltip"));
			options.add(dims);
		}
		Stepper radiusStepper = new Stepper(0, MAX_SELECT_RADIUS, 1, true, () -> radius, v -> radius = v.intValue());
		radiusStepper.tooltip(Component.translatable("cytrabackups.gui.area.radius.tooltip"));
		options.add(radiusStepper);
		options.add(button("cytrabackups.gui.area.around_me", "cytrabackups.gui.area.around_me.tooltip", this::selectRadius));
		options.add(button("cytrabackups.gui.area.region", "cytrabackups.gui.area.region.tooltip", this::selectRegion));
		Button restore = new Button(Component.translatable("cytrabackups.gui.area.restore"), this::restore).style(Button.Style.PRIMARY);
		restore.tooltip(Component.translatable("cytrabackups.gui.area.restore.tooltip", backupId));
		Button copy = new Button(Component.translatable("cytrabackups.gui.area.copy"), this::copyCommand);
		copy.tooltip(Component.translatable("cytrabackups.gui.area.copy.tooltip", backupId));
		controls = new Controls(
			new ControlRow(options),
			new ControlRow(List.of(x1Box, z1Box, x2Box, z2Box, button("cytrabackups.gui.area.select", "cytrabackups.gui.area.select.tooltip", this::fromBoxes))),
			new ControlRow(List.of(new Button(CommonComponents.GUI_BACK, this::onClose), previewButton(), copy, restore)));
		setRoot(controls);
		syncBoxes();
	}

	@Override
	protected boolean useUiScale() {
		return false; // the map works in GUI units, so the controls do too
	}

	private static Button button(String key, String tooltipKey, Runnable action) {
		Button b = new Button(Component.translatable(key), action);
		b.tooltip(Component.translatable(tooltipKey));
		return b;
	}

	private static TextField box(String hint) {
		return new TextField(() -> "", v -> {}).live().maxLength(8).placeholder(Component.literal(hint))
			.validator(t -> t.isEmpty() || t.matches("-?\\d+"), Component.translatable("cytrabackups.gui.area.bad_coords"));
	}

	private static Component dimensionName(String id) {
		return Component.literal(id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id);
	}

	private static String currentDimension(Minecraft mc) {
		return mc.level == null ? "" : mc.level.dimension().identifier().toString();
	}

	private boolean showingMap() {
		return dimension != null && dimension.equals(currentDimension(minecraft));
	}

	private void selectRadius() {
		if (minecraft.player == null) return;
		ChunkPos cp = minecraft.player.chunkPosition();
		dimension = currentDimension(minecraft);
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

	// ---- map area, in GUI units: under the title, above the two text lines and the controls panel ----

	private int gridLeft() {
		return MARGIN;
	}

	private int gridTop() {
		return 22;
	}

	private int gridBottom() {
		return (int) controls.panelTop() - 30;
	}

	private int cols() {
		return Math.max(0, width - 2 * MARGIN) / cell;
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
	protected boolean canvasClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() != 0 || !inGrid(event.x(), event.y())) return false;
		selX1 = selX2 = chunkAtX(event.x());
		selZ1 = selZ2 = chunkAtZ(event.y());
		dragging = true;
		syncBoxes();
		return true;
	}

	@Override
	protected boolean canvasDragged(MouseButtonEvent event, double dx, double dy) {
		if (!dragging) return false;
		selX2 = chunkAtX(Math.max(gridLeft(), Math.min(event.x(), gridLeft() + cols() * cell - 1)));
		selZ2 = chunkAtZ(Math.max(gridTop(), Math.min(event.y(), gridTop() + rows() * cell - 1)));
		syncBoxes();
		return true;
	}

	@Override
	protected boolean canvasReleased(MouseButtonEvent event) {
		dragging = false;
		return true;
	}

	@Override
	protected boolean canvasScrolled(double x, double y, double scrollX, double scrollY) {
		cell = Math.max(3, Math.min(24, cell + (int) Math.signum(scrollY)));
		return true;
	}

	@Override
	protected boolean canvasKeyPressed(KeyEvent event) {
		int step = 4;
		switch (event.key()) {
			case GLFW.GLFW_KEY_LEFT -> centerX -= step;
			case GLFW.GLFW_KEY_RIGHT -> centerX += step;
			case GLFW.GLFW_KEY_UP -> centerZ -= step;
			case GLFW.GLFW_KEY_DOWN -> centerZ += step;
			default -> {
				return false;
			}
		}
		return true;
	}

	private void syncBoxes() {
		x1Box.setText(selX1 == null ? "" : String.valueOf(Math.min(selX1, selX2)));
		z1Box.setText(selZ1 == null ? "" : String.valueOf(Math.min(selZ1, selZ2)));
		x2Box.setText(selX2 == null ? "" : String.valueOf(Math.max(selX1, selX2)));
		z2Box.setText(selZ2 == null ? "" : String.valueOf(Math.max(selZ1, selZ2)));
	}

	private void fromBoxes() {
		try {
			selX1 = Integer.parseInt(x1Box.text().trim());
			selZ1 = Integer.parseInt(z1Box.text().trim());
			selX2 = Integer.parseInt(x2Box.text().trim());
			selZ2 = Integer.parseInt(z2Box.text().trim());
			centerX = (selX1 + selX2) / 2;
			centerZ = (selZ1 + selZ2) / 2;
		} catch (NumberFormatException e) {
			toast(Toast.Kind.ERROR, Component.translatable("cytrabackups.gui.area.bad_coords"));
		}
	}

	/** The restore command for the selection (without the leading /cbackup), or null with a hint if nothing is selected. */
	private String restoreCommand() {
		if (selX1 == null || dimension == null || dimension.isEmpty()) {
			toast(Toast.Kind.ERROR, Component.translatable("cytrabackups.gui.area.nothing_selected"));
			return null;
		}
		int x1 = Math.min(selX1, selX2), x2 = Math.max(selX1, selX2), z1 = Math.min(selZ1, selZ2), z2 = Math.max(selZ1, selZ2);
		return "restore " + backupId + " chunks " + dimension + " " + x1 + " " + z1 + " " + x2 + " " + z2;
	}

	/** The server asks for confirmation, then restores the chunks live or queues them. */
	private void restore() {
		String command = restoreCommand();
		if (command != null) ClientState.run(command);
	}

	/** Chunks in the selection, or 0 when nothing is selected. */
	private long selectedChunks() {
		if (selX1 == null) return 0;
		return (long) (Math.abs(selX2 - selX1) + 1) * (Math.abs(selZ2 - selZ1) + 1);
	}

	/** Shows the selected chunks of the backup as a Litematica schematic (shared through Alloy Syncmatica), to compare before restoring. */
	private Button previewButton() {
		Button b = new Button(Component.translatable("cytrabackups.gui.area.preview"), this::preview);
		b.enabledWhen(() -> {
			long n = selectedChunks();
			return n > 0 && n <= PREVIEW_MAX_CHUNKS && ClientState.can(BackupListPayload.CAN_RESTORE);
		});
		b.tooltip(() -> List.of(selectedChunks() > PREVIEW_MAX_CHUNKS
			? Component.translatable("cytrabackups.gui.area.preview.too_big", selectedChunks(), PREVIEW_MAX_CHUNKS)
			: Component.translatable("cytrabackups.gui.area.preview.tooltip", backupId)));
		return b;
	}

	private void preview() {
		String command = restoreCommand();
		if (command != null) ClientState.run(command.replaceFirst("^restore ", "preview "));
	}

	/** Copies the restore command, to run later from chat (e.g. once nobody is near the area, so it can be restored live). */
	private void copyCommand() {
		String command = restoreCommand();
		if (command == null) return;
		minecraft.keyboardHandler.setClipboard("/" + BackupCommands.ROOT + " " + command);
		toast(Toast.Kind.SUCCESS, Component.translatable("cytrabackups.gui.area.copied"));
	}

	/** For tests: the centre of the widget labelled {@code label} in GUI units, or null. */
	public double @Nullable [] pointForTest(String label) {
		Widget w = Dialogs.find(root(), label);
		return w == null ? null : new double[] {w.x() + w.w() / 2, w.y() + w.h() / 2};
	}

	/** For tests: the centre of the map (the player's chunk when the screen opened), in GUI units. */
	public double[] mapCenterForTest() {
		return new double[] {gridLeft() + (cols() / 2) * cell + cell / 2.0, gridTop() + (rows() / 2) * cell + cell / 2.0};
	}

	/** For tests: the size of one chunk on the map, in GUI units. */
	public int cellForTest() {
		return cell;
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
		super.removed();
		if (map != null) {
			minecraft.getTextureManager().release(MAP_TEXTURE);
			map = null;
		}
	}

	@Override
	protected void renderCanvas(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		Theme t = ThemeManager.get();
		Draw.heading(g, title, MARGIN + 2, 8, t.color("text"));
		ensureMap();
		int cols = cols(), rows = rows();
		int left = gridLeft(), top = gridTop(), right = left + cols * cell, bottom = top + rows * cell;
		int startX = centerX - cols / 2, startZ = centerZ - rows / 2;
		// A handful of draw calls per frame: background, the map texture, region lines, selection and markers.
		Draw.panel(g, left - 1, top - 1, right - left + 2, bottom - top + 2, t.metricF("radiusSmall"), t.color("bg"), t.color("border"), t.metricF("borderWidth"));
		if (map != null && showingMap()) {
			int mx = left + (originX - startX) * cell, my = top + (originZ - startZ) * cell, size = span * cell, tex = span * SAMPLES;
			g.enableScissor(left, top, right, bottom);
			g.blit(RenderPipelines.GUI_TEXTURED, MAP_TEXTURE, mx, my, 0, 0, size, size, tex, tex, tex, tex);
			g.disableScissor();
		} else if (rows > 0) {
			Component noMap = Component.translatable("cytrabackups.gui.area.no_map", dimension == null ? "" : dimensionName(dimension));
			float w = Math.min(Draw.textWidth(noMap), right - left - 8);
			Draw.textClipped(g, noMap, left + (right - left - w) / 2, top + (bottom - top) / 2f - 4, right - left - 8, t.color("textMuted"));
		}
		int line = Draw.withAlpha(t.color("text"), 0.3f);
		for (int c = Math.floorMod(-startX, 32); c < cols; c += 32) g.fill(left + c * cell, top, left + c * cell + 1, bottom, line);
		for (int r = Math.floorMod(-startZ, 32); r < rows; r += 32) g.fill(left, top + r * cell, right, top + r * cell + 1, line);
		if (selX1 != null) {
			int x1 = Math.min(selX1, selX2), x2 = Math.max(selX1, selX2), z1 = Math.min(selZ1, selZ2), z2 = Math.max(selZ1, selZ2);
			int sx1 = Math.max(left, left + (x1 - startX) * cell), sz1 = Math.max(top, top + (z1 - startZ) * cell);
			int sx2 = Math.min(right, left + (x2 - startX + 1) * cell), sz2 = Math.min(bottom, top + (z2 - startZ + 1) * cell);
			if (sx2 > sx1 && sz2 > sz1) {
				g.fill(sx1, sz1, sx2, sz2, Draw.withAlpha(t.color("accent"), 0.3f));
				g.renderOutline(sx1, sz1, sx2 - sx1, sz2 - sz1, t.color("accent"));
			}
		}
		if (minecraft.player != null && showingMap()) {
			ChunkPos pc = minecraft.player.chunkPosition();
			int px = left + (pc.x - startX) * cell + cell / 2, py = top + (pc.z - startZ) * cell + cell / 2;
			if (px >= left && py >= top && px < right && py < bottom) {
				g.fill(px - 3, py - 3, px + 3, py + 3, t.color("bg"));
				g.fill(px - 2, py - 2, px + 2, py + 2, t.color("text"));
			}
		}
		int textW = width - 2 * MARGIN, ty = gridBottom() + 6;
		Draw.textClipped(g, selectionText(), MARGIN, ty, textW, t.color(selX1 == null ? "textMuted" : "text"));
		Component second = null;
		int color = t.color("textMuted");
		if (canvasHovered() && inGrid(mouseX, mouseY)) {
			int hx = chunkAtX(mouseX), hz = chunkAtZ(mouseY);
			second = Component.translatable("cytrabackups.gui.area.hover", hx, hz, hx * 16, hz * 16, Math.floorDiv(hx, 32), Math.floorDiv(hz, 32));
		} else {
			ClientState.LogLine last = ClientState.lastLog();
			if (last != null && System.currentTimeMillis() - last.at() < 30_000) {
				second = last.text();
				color = t.color(last.error() ? "bad" : "text");
			}
		}
		if (second != null) Draw.textClipped(g, second, MARGIN, ty + 11, textW, color);
	}

	private Component selectionText() {
		if (selX1 == null) return Component.translatable("cytrabackups.gui.area.hint");
		int x1 = Math.min(selX1, selX2), x2 = Math.max(selX1, selX2), z1 = Math.min(selZ1, selZ2), z2 = Math.max(selZ1, selZ2);
		long count = (long) (x2 - x1 + 1) * (z2 - z1 + 1);
		return Component.translatable(count == 1 ? "cytrabackups.gui.area.selection.one" : "cytrabackups.gui.area.selection", x1, z1, x2, z2, count, x1 * 16, z1 * 16, x2 * 16 + 15, z2 * 16 + 15);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	/** The controls panel at the bottom: three rows in a Nocturne panel. */
	private static final class Controls extends Container {
		private static final float PAD = 6, ROW = 16, GAP = 4;
		private final List<ControlRow> rows;
		private float px, py, pw, ph;

		Controls(ControlRow... rows) {
			this.rows = List.of(rows);
			for (ControlRow r : rows) add(r);
		}

		float panelTop() {
			return py;
		}

		@Override
		public float measure(float width) {
			return 0;
		}

		@Override
		protected void layout() {
			pw = Math.min(w - 2 * MARGIN, 520);
			ph = rows.size() * ROW + (rows.size() - 1) * GAP + 2 * PAD;
			px = x + (w - pw) / 2;
			py = y + h - MARGIN - ph;
			float cy = py + PAD;
			for (ControlRow r : rows) {
				r.setBounds(px + PAD, cy, pw - 2 * PAD, ROW);
				cy += ROW + GAP;
			}
		}

		@Override
		protected void renderWidget(UiContext ctx) {
			var t = ctx.theme;
			Draw.panel(ctx.g, px, py, pw, ph, t.metricF("radiusLarge"), t.color("panel"), t.color("border"), t.metricF("borderWidth"));
			super.renderWidget(ctx);
		}
	}

	/** Controls side by side, centred, narrowed evenly when the row is too small for them. */
	private static final class ControlRow extends Container {
		private static final float GAP = 4;

		ControlRow(List<? extends Widget> widgets) {
			for (Widget w : widgets) add(w);
		}

		private static float wanted(Widget w) {
			if (w instanceof Button b) return Math.max(60, b.preferredWidth());
			if (w instanceof TextField) return 48;
			if (w instanceof Stepper) return 64;
			return 110;
		}

		@Override
		public float measure(float width) {
			return 16;
		}

		@Override
		protected void layout() {
			float gaps = GAP * (children.size() - 1), total = 0;
			for (Widget c : children) total += wanted(c);
			float scale = Math.min(1, (w - gaps) / Math.max(1, total));
			float cx = x + (w - total * scale - gaps) / 2;
			for (Widget c : children) {
				float cw = wanted(c) * scale;
				c.setBounds(cx, y, cw, 16);
				cx += cw + GAP;
			}
		}
	}
}
