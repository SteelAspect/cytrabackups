package dev.steelaspect.cytrabackups.client;

import dev.steelaspect.cytrabackups.net.RequestPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import org.lwjgl.glfw.GLFW;

/**
 * Map-style chunk selector. Chunks the client has loaded are drawn from their top-block map colours; drag with
 * the left mouse button to select a rectangle, or type chunk coordinates. The selection is restored from the
 * chosen backup in the player's current dimension.
 */
public final class ChunkSelectorScreen extends Screen {
	private static final int SAMPLES = 4; // 4x4 colour samples per chunk

	private final Screen parent;
	private final int backupId;
	private int centerX, centerZ;
	private int cell = 8;
	private Integer selX1, selZ1, selX2, selZ2;
	private boolean dragging;
	private EditBox x1Box, z1Box, x2Box, z2Box;
	private final java.util.Map<Long, int[]> colorCache = new java.util.HashMap<>();

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
		int y = height - 28;
		int bw = 44;
		x1Box = box(10, y, "x1");
		z1Box = box(10 + bw + 4, y, "z1");
		x2Box = box(10 + 2 * (bw + 4), y, "x2");
		z2Box = box(10 + 3 * (bw + 4), y, "z2");
		addRenderableWidget(Button.builder(Component.literal("Use coords"), b -> fromBoxes()).bounds(10 + 4 * (bw + 4), y, 70, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Restore selection..."), b -> confirm()).bounds(width - 230, y, 130, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Back"), b -> onClose()).bounds(width - 95, y, 85, 20).build());
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
		return height - 24 - 40;
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
			ClientState.message = "Enter four chunk coordinates (x1 z1 x2 z2)";
			ClientState.messageError = true;
			ClientState.messageAt = System.currentTimeMillis();
		}
	}

	private void confirm() {
		if (selX1 == null || minecraft.level == null) return;
		int x1 = Math.min(selX1, selX2), x2 = Math.max(selX1, selX2), z1 = Math.min(selZ1, selZ2), z2 = Math.max(selZ1, selZ2);
		long count = (long) (x2 - x1 + 1) * (z2 - z1 + 1);
		String dim = minecraft.level.dimension().identifier().toString();
		minecraft.setScreen(new ConfirmScreen(yes -> {
			if (yes) {
				ClientState.send(new RequestPayload(RequestPayload.RESTORE_CHUNKS, backupId, "", dim, x1, z1, x2, z2));
				minecraft.setScreen(parent);
			} else {
				minecraft.setScreen(this);
			}
		}, Component.literal("Restore " + count + " chunk(s) from backup #" + backupId + "?"),
			Component.literal("Chunks " + x1 + "," + z1 + " to " + x2 + "," + z2 + " in " + dim + ". Terrain, entities and POI are restored. "
				+ "The server restores them live if nobody is nearby, otherwise it queues the restore for the next restart.")));
	}

	/** 4x4 map colours for a loaded chunk, or null. Cached per screen. */
	private int[] colors(ClientLevel level, int cx, int cz) {
		long key = ChunkPos.asLong(cx, cz);
		int[] cached = colorCache.get(key);
		if (cached != null || colorCache.containsKey(key)) return cached;
		LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
		int[] out = null;
		if (chunk != null) {
			out = new int[SAMPLES * SAMPLES];
			BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
			for (int sx = 0; sx < SAMPLES; sx++) {
				for (int sz = 0; sz < SAMPLES; sz++) {
					int lx = sx * 4 + 2, lz = sz * 4 + 2;
					int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, lx, lz) - 1;
					pos.set(cx * 16 + lx, y, cz * 16 + lz);
					BlockState state = chunk.getBlockState(pos);
					MapColor mc = state.getMapColor(level, pos);
					int rgb = mc == MapColor.NONE ? 0x202020 : mc.col;
					out[sx + sz * SAMPLES] = 0xFF000000 | rgb;
				}
			}
		}
		colorCache.put(key, out);
		return out;
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		super.render(g, mouseX, mouseY, partialTick);
		g.drawString(font, title, 10, 8, 0xFFFFAA00);
		ClientLevel level = minecraft.level;
		if (level == null) return;
		int cols = cols(), rows = rows();
		int left = gridLeft(), top = gridTop();
		g.fill(left, top, left + cols * cell, top + rows * cell, 0xFF101010);
		int startX = centerX - cols / 2, startZ = centerZ - rows / 2;
		int sub = Math.max(1, cell / SAMPLES);
		for (int c = 0; c < cols; c++) {
			for (int r = 0; r < rows; r++) {
				int cx = startX + c, cz = startZ + r;
				int px = left + c * cell, py = top + r * cell;
				int[] col = colors(level, cx, cz);
				if (col == null) {
					g.fill(px, py, px + cell, py + cell, ((cx + cz) & 1) == 0 ? 0xFF1C1C1C : 0xFF242424);
				} else if (cell >= SAMPLES * 2) {
					for (int sx = 0; sx < SAMPLES; sx++) {
						for (int sz = 0; sz < SAMPLES; sz++) {
							g.fill(px + sx * sub, py + sz * sub, px + (sx == SAMPLES - 1 ? cell : (sx + 1) * sub), py + (sz == SAMPLES - 1 ? cell : (sz + 1) * sub), col[sx + sz * SAMPLES]);
						}
					}
				} else {
					g.fill(px, py, px + cell, py + cell, col[5]);
				}
				if (cx % 32 == 0 || cz % 32 == 0) {
					if (Math.floorMod(cx, 32) == 0) g.fill(px, py, px + 1, py + cell, 0x60FFFFFF);
					if (Math.floorMod(cz, 32) == 0) g.fill(px, py, px + cell, py + 1, 0x60FFFFFF);
				}
			}
		}
		if (selX1 != null) {
			int x1 = Math.min(selX1, selX2), x2 = Math.max(selX1, selX2), z1 = Math.min(selZ1, selZ2), z2 = Math.max(selZ1, selZ2);
			int sx1 = Math.max(left, left + (x1 - startX) * cell), sz1 = Math.max(top, top + (z1 - startZ) * cell);
			int sx2 = Math.min(left + cols * cell, left + (x2 - startX + 1) * cell), sz2 = Math.min(top + rows * cell, top + (z2 - startZ + 1) * cell);
			if (sx2 > sx1 && sz2 > sz1) {
				g.fill(sx1, sz1, sx2, sz2, 0x60FF3030);
				g.renderOutline(sx1, sz1, sx2 - sx1, sz2 - sz1, 0xFFFF5050);
			}
			String info = "Selected: chunks " + x1 + "," + z1 + " to " + x2 + "," + z2 + " (" + ((long) (x2 - x1 + 1) * (z2 - z1 + 1)) + " chunks)";
			g.drawString(font, info, 10, height - 40, 0xFFFFFFFF);
		} else {
			g.drawString(font, "Drag to select chunks. Scroll = zoom, arrow keys = pan. Region borders are highlighted.", 10, height - 40, 0xFFA0A0A0);
		}
		if (minecraft.player != null) {
			ChunkPos pc = minecraft.player.chunkPosition();
			int px = left + (pc.x - startX) * cell + cell / 2, py = top + (pc.z - startZ) * cell + cell / 2;
			if (px >= left && py >= top && px < left + cols * cell && py < top + rows * cell) g.fill(px - 2, py - 2, px + 2, py + 2, 0xFFFFFFFF);
		}
		if (inGrid(mouseX, mouseY)) {
			String hover = "Chunk " + chunkAtX(mouseX) + ", " + chunkAtZ(mouseY) + "  (region " + Math.floorDiv(chunkAtX(mouseX), 32) + ", " + Math.floorDiv(chunkAtZ(mouseY), 32) + ")";
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
