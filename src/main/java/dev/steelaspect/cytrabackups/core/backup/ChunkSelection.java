package dev.steelaspect.cytrabackups.core.backup;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** A set of chunk rectangles (inclusive chunk coordinates) within one dimension. */
public final class ChunkSelection {
	public record Box(int minX, int minZ, int maxX, int maxZ) {
		public Box {
			if (minX > maxX) {
				int t = minX;
				minX = maxX;
				maxX = t;
			}
			if (minZ > maxZ) {
				int t = minZ;
				minZ = maxZ;
				maxZ = t;
			}
		}

		public boolean contains(int x, int z) {
			return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
		}

		public long chunkCount() {
			return (long) (maxX - minX + 1) * (maxZ - minZ + 1);
		}
	}

	public record RegionPos(int x, int z) {
	}

	private final List<Box> boxes;

	public ChunkSelection(List<Box> boxes) {
		this.boxes = List.copyOf(boxes);
	}

	public static ChunkSelection box(int x1, int z1, int x2, int z2) {
		return new ChunkSelection(List.of(new Box(x1, z1, x2, z2)));
	}

	public static ChunkSelection region(int rx, int rz) {
		return box(rx * 32, rz * 32, rx * 32 + 31, rz * 32 + 31);
	}

	/** Chunks whose centre lies within {@code radius} chunks of the given chunk (a square for simplicity). */
	public static ChunkSelection radius(int centerX, int centerZ, int radius) {
		return box(centerX - radius, centerZ - radius, centerX + radius, centerZ + radius);
	}

	public List<Box> boxes() {
		return boxes;
	}

	public boolean contains(int x, int z) {
		for (Box b : boxes) if (b.contains(x, z)) return true;
		return false;
	}

	public long chunkCount() {
		long n = 0;
		for (Box b : boxes) n += b.chunkCount();
		return n;
	}

	public Set<RegionPos> regions() {
		Set<RegionPos> out = new LinkedHashSet<>();
		for (Box b : boxes) {
			for (int rx = Math.floorDiv(b.minX(), 32); rx <= Math.floorDiv(b.maxX(), 32); rx++) {
				for (int rz = Math.floorDiv(b.minZ(), 32); rz <= Math.floorDiv(b.maxZ(), 32); rz++) {
					out.add(new RegionPos(rx, rz));
				}
			}
		}
		return out;
	}

	/** Slot indices within the given region that are selected. */
	public List<Integer> indicesIn(int rx, int rz) {
		List<Integer> out = new ArrayList<>();
		for (int i = 0; i < 1024; i++) {
			int cx = rx * 32 + (i & 31);
			int cz = rz * 32 + (i >> 5);
			if (contains(cx, cz)) out.add(i);
		}
		return out;
	}

	public String describe() {
		StringBuilder sb = new StringBuilder();
		for (Box b : boxes) {
			if (!sb.isEmpty()) sb.append(", ");
			sb.append("chunks ").append(b.minX()).append(',').append(b.minZ()).append(" to ").append(b.maxX()).append(',').append(b.maxZ());
		}
		return sb.toString();
	}
}
