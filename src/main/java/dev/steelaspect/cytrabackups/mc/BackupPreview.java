package dev.steelaspect.cytrabackups.mc;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns the chunks of a backup into a Litematica schematic ({@code .litematic}) placed exactly where they were, so the
 * backup can be looked at as a ghost overlay before restoring it. The schematic is trimmed to the lowest and highest
 * non-air block; chunks missing from the backup stay air.
 */
public final class BackupPreview {
	private BackupPreview() {
	}

	/** A built schematic: the file, the world position of its min corner and its size. */
	public record Result(Path file, BlockPos min, int sizeX, int sizeY, int sizeZ, long blocks) {
	}

	private record Loaded(ChunkPos pos, LevelChunkSection[] sections, List<CompoundTag> blockEntities) {
	}

	/**
	 * Builds the schematic from the region chunks of {@link LiveChunkRestore#prepare} (other kinds are ignored).
	 * {@code minChunk}/{@code maxChunk} are the selection's corners. Safe to call off the server thread.
	 */
	public static Result build(ServerLevel level, List<LiveChunkRestore.Write> writes, ChunkPos minChunk, ChunkPos maxChunk,
							   String name, String author, Path out) throws IOException {
		int minSection = level.getMinSectionY();
		int sectionCount = level.getSectionsCount();
		int current = SharedConstants.getCurrentVersion().dataVersion().version();
		List<Loaded> chunks = new ArrayList<>();
		int lowY = Integer.MAX_VALUE, highY = Integer.MIN_VALUE;
		for (LiveChunkRestore.Write w : writes) {
			if (w.kind() != LiveChunkRestore.Kind.REGION || w.tag() == null) continue;
			CompoundTag tag = w.tag().copy();
			int version = NbtUtils.getDataVersion(tag, -1);
			if (version >= 0 && version < current) {
				CompoundTag context = new CompoundTag();
				context.putString("dimension", level.dimension().identifier().toString());
				context.putString("generator", "minecraft:noise");
				tag.put("__context", context);
				tag = DataFixTypes.CHUNK.updateToCurrentVersion(level.getServer().getFixerUpper(), tag, version);
				tag.remove("__context");
			}
			SerializableChunkData data = SerializableChunkData.parse(level, level.palettedContainerFactory(), tag);
			if (data == null) continue;
			LevelChunkSection[] sections = new LevelChunkSection[sectionCount];
			for (SerializableChunkData.SectionData s : data.sectionData()) {
				int i = s.y() - minSection;
				if (i < 0 || i >= sectionCount || s.chunkSection() == null) continue;
				LevelChunkSection section = s.chunkSection();
				sections[i] = section;
				if (section.hasOnlyAir()) continue;
				int baseY = s.y() * 16;
				for (int y = 0; y < 16; y++) {
					if (baseY + y >= lowY && baseY + y <= highY) continue;
					if (layerHasBlocks(section, y)) {
						lowY = Math.min(lowY, baseY + y);
						highY = Math.max(highY, baseY + y);
					}
				}
			}
			chunks.add(new Loaded(w.pos(), sections, data.blockEntities()));
		}
		if (lowY > highY) {
			lowY = level.getMinY();
			highY = lowY;
		}
		int sizeX = (maxChunk.x - minChunk.x + 1) * 16, sizeZ = (maxChunk.z - minChunk.z + 1) * 16, sizeY = highY - lowY + 1;
		BlockPos min = new BlockPos(minChunk.getMinBlockX(), lowY, minChunk.getMinBlockZ());

		Map<BlockState, Integer> palette = new HashMap<>();
		List<BlockState> paletteList = new ArrayList<>();
		palette.put(Blocks.AIR.defaultBlockState(), 0);
		paletteList.add(Blocks.AIR.defaultBlockState());
		long volume = (long) sizeX * sizeY * sizeZ;
		if (volume > Integer.MAX_VALUE) throw new IOException("The preview area is too big");
		int[] indices = new int[(int) volume];
		long blocks = 0;
		ListTag tiles = new ListTag();
		for (Loaded c : chunks) {
			int ox = (c.pos().x - minChunk.x) * 16, oz = (c.pos().z - minChunk.z) * 16;
			for (int i = 0; i < c.sections().length; i++) {
				LevelChunkSection section = c.sections()[i];
				if (section == null || section.hasOnlyAir()) continue;
				int baseY = (minSection + i) * 16;
				for (int y = 0; y < 16; y++) {
					int ry = baseY + y - lowY;
					if (ry < 0 || ry >= sizeY) continue;
					for (int z = 0; z < 16; z++) {
						for (int x = 0; x < 16; x++) {
							BlockState state = section.getBlockState(x, y, z);
							if (state.isAir()) continue;
							Integer id = palette.get(state);
							if (id == null) {
								id = paletteList.size();
								palette.put(state, id);
								paletteList.add(state);
							}
							indices[(ry * sizeZ + oz + z) * sizeX + ox + x] = id;
							blocks++;
						}
					}
				}
			}
			for (CompoundTag be : c.blockEntities()) {
				int x = be.getIntOr("x", 0) - min.getX(), y = be.getIntOr("y", 0) - min.getY(), z = be.getIntOr("z", 0) - min.getZ();
				if (x < 0 || y < 0 || z < 0 || x >= sizeX || y >= sizeY || z >= sizeZ) continue;
				CompoundTag copy = be.copy();
				copy.putInt("x", x);
				copy.putInt("y", y);
				copy.putInt("z", z);
				tiles.add(copy);
			}
		}

		ListTag paletteTag = new ListTag();
		for (BlockState s : paletteList) paletteTag.add(NbtUtils.writeBlockState(s));
		CompoundTag region = new CompoundTag();
		region.put("Position", vec(0, 0, 0));
		region.put("Size", vec(sizeX, sizeY, sizeZ));
		region.put("BlockStatePalette", paletteTag);
		region.putLongArray("BlockStates", pack(indices, paletteList.size()));
		region.put("TileEntities", tiles);
		region.put("Entities", new ListTag());
		region.put("PendingBlockTicks", new ListTag());
		region.put("PendingFluidTicks", new ListTag());
		CompoundTag regions = new CompoundTag();
		regions.put(name, region);

		long now = System.currentTimeMillis();
		CompoundTag meta = new CompoundTag();
		meta.putString("Name", name);
		meta.putString("Author", author);
		meta.putString("Description", "CytraBackups preview at " + min.toShortString());
		meta.putInt("RegionCount", 1);
		meta.putLong("TotalVolume", volume);
		meta.putLong("TotalBlocks", blocks);
		meta.putLong("TimeCreated", now);
		meta.putLong("TimeModified", now);
		meta.put("EnclosingSize", vec(sizeX, sizeY, sizeZ));

		CompoundTag root = new CompoundTag();
		root.putInt("MinecraftDataVersion", current);
		root.putInt("Version", 7);
		root.putInt("SubVersion", 1);
		root.put("Metadata", meta);
		root.put("Regions", regions);
		Files.createDirectories(out.getParent());
		NbtIo.writeCompressed(root, out);
		return new Result(out, min, sizeX, sizeY, sizeZ, blocks);
	}

	private static boolean layerHasBlocks(LevelChunkSection section, int y) {
		for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) if (!section.getBlockState(x, y, z).isAir()) return true;
		return false;
	}

	private static CompoundTag vec(int x, int y, int z) {
		CompoundTag t = new CompoundTag();
		t.putInt("x", x);
		t.putInt("y", y);
		t.putInt("z", z);
		return t;
	}

	/** Litematica's bit array: entries packed back to back, crossing long boundaries, at least 2 bits each. */
	static long[] pack(int[] values, int paletteSize) {
		int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(paletteSize - 1));
		long mask = (1L << bits) - 1L;
		long[] out = new long[(int) (((long) values.length * bits + 63L) / 64L)];
		for (int i = 0; i < values.length; i++) {
			long v = values[i] & mask;
			if (v == 0) continue;
			long start = (long) i * bits;
			int startIndex = (int) (start >> 6);
			int endIndex = (int) (((long) (i + 1) * bits - 1L) >> 6);
			int offset = (int) (start & 63);
			out[startIndex] |= v << offset;
			if (startIndex != endIndex) out[endIndex] |= v >>> (64 - offset);
		}
		return out;
	}

	/** Reads one entry back (tests). */
	public static int unpack(long[] data, int bits, int i) {
		long mask = (1L << bits) - 1L;
		long start = (long) i * bits;
		int startIndex = (int) (start >> 6);
		int endIndex = (int) (((long) (i + 1) * bits - 1L) >> 6);
		int offset = (int) (start & 63);
		if (startIndex == endIndex) return (int) (data[startIndex] >>> offset & mask);
		return (int) ((data[startIndex] >>> offset | data[endIndex] << (64 - offset)) & mask);
	}

	/** Reads a written schematic back (tests). */
	static CompoundTag read(Path file) throws IOException {
		return NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
	}

}
