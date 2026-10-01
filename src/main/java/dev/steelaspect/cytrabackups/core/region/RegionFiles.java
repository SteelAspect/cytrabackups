package dev.steelaspect.cytrabackups.core.region;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reader/writer for Anvil region files ({@code r.X.Z.mca}), independent of Minecraft classes.
 *
 * <p>Layout: 4 KiB location table (1024 x [3-byte sector offset, 1-byte sector count]), 4 KiB timestamp
 * table, then chunk records of {@code [int length][byte compression][length-1 bytes]} padded to 4 KiB sectors.
 * A chunk's "payload" here is the {@code length} bytes starting at the compression byte.
 */
public final class RegionFiles {
	public static final int SECTOR = 4096;
	public static final int HEADER = SECTOR * 2;
	public static final int CHUNKS = 1024;
	public static final int EXTERNAL_FLAG = 0x80;
	private static final Pattern REGION_NAME = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");

	private RegionFiles() {
	}

	/** One chunk slot inside a region file. */
	public record ChunkSlot(int index, int timestamp, byte[] payload) {
		public int compressionType() {
			return payload[0] & 0xFF;
		}

		/** Chunk payloads with gzip/zlib/lz4 compression are already compressed. */
		public boolean isCompressed() {
			int t = compressionType() & 0x7F;
			return t == 1 || t == 2 || t == 4;
		}
	}

	/** Compression types whose chunks can be unpacked without Minecraft classes: gzip, zlib, none. */
	public static boolean canUnpack(int compressionType) {
		return compressionType == 1 || compressionType == 2 || compressionType == 3;
	}

	/** The NBT inside a chunk payload of type 1, 2 or 3 (the compression byte is {@code payload[0]}). */
	public static byte[] unpack(byte[] payload) throws IOException {
		int type = payload[0] & 0xFF;
		java.io.InputStream raw = new java.io.ByteArrayInputStream(payload, 1, payload.length - 1);
		java.io.InputStream in = switch (type) {
			case 1 -> new java.util.zip.GZIPInputStream(raw, 1 << 16);
			case 2 -> new java.util.zip.InflaterInputStream(raw, new java.util.zip.Inflater(), 1 << 16);
			case 3 -> raw;
			default -> throw new IOException("chunk compression type " + type + " cannot be unpacked");
		};
		try (in) {
			return in.readAllBytes();
		}
	}

	/** A chunk payload ({@code type} byte then the NBT compressed the way Minecraft does it) for restoring. */
	public static byte[] pack(int type, byte[] nbt) throws IOException {
		java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(nbt.length / 4 + 16);
		bos.write(type);
		try (java.io.OutputStream out = switch (type) {
			case 1 -> new java.util.zip.GZIPOutputStream(bos, 1 << 16);
			case 2 -> new java.util.zip.DeflaterOutputStream(bos, new java.util.zip.Deflater(), 1 << 16);
			case 3 -> bos;
			default -> throw new IOException("chunk compression type " + type + " cannot be packed");
		}) {
			out.write(nbt);
		}
		return bos.toByteArray();
	}

	public static final class RegionFormatException extends IOException {
		public RegionFormatException(String message) {
			super(message);
		}
	}

	public interface ChunkVisitor {
		void visit(ChunkSlot slot) throws IOException;
	}

	public static boolean isRegionFileName(String fileName) {
		return REGION_NAME.matcher(fileName).matches();
	}

	/** Returns {rx, rz} for a region file name or null. */
	public static int[] regionCoords(String fileName) {
		Matcher m = REGION_NAME.matcher(fileName);
		if (!m.matches()) return null;
		return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
	}

	public static String regionFileName(int rx, int rz) {
		return "r." + rx + "." + rz + ".mca";
	}

	public static String externalChunkFileName(int chunkX, int chunkZ) {
		return "c." + chunkX + "." + chunkZ + ".mcc";
	}

	public static int index(int chunkX, int chunkZ) {
		return (chunkX & 31) + (chunkZ & 31) * 32;
	}

	public static int chunkX(int regionX, int index) {
		return regionX * 32 + (index & 31);
	}

	public static int chunkZ(int regionZ, int index) {
		return regionZ * 32 + (index >> 5);
	}

	/**
	 * Streams every present chunk of a region file. Reads are positional so memory stays bounded by the
	 * largest chunk (at most 1 MiB). Throws {@link RegionFormatException} for structurally invalid files.
	 */
	public static void read(FileChannel ch, ChunkVisitor visitor) throws IOException {
		long size = ch.size();
		// Like Minecraft, treat a missing/truncated header as zero-filled (0-byte region files are common).
		ByteBuffer header = ByteBuffer.allocate(HEADER);
		if (size > 0) {
			header.limit((int) Math.min(HEADER, size));
			readFully(ch, header, 0);
			header.limit(HEADER);
		}
		header.position(0);
		int[] locations = new int[CHUNKS];
		int[] timestamps = new int[CHUNKS];
		for (int i = 0; i < CHUNKS; i++) locations[i] = header.getInt();
		for (int i = 0; i < CHUNKS; i++) timestamps[i] = header.getInt();

		ByteBuffer lenBuf = ByteBuffer.allocate(4);
		for (int i = 0; i < CHUNKS; i++) {
			int loc = locations[i];
			if (loc == 0) continue;
			long sectorOffset = loc >>> 8;
			int sectorCount = loc & 0xFF;
			if (sectorOffset < 2 || sectorCount == 0) throw new RegionFormatException("chunk " + i + " has invalid location " + Integer.toHexString(loc));
			long start = sectorOffset * SECTOR;
			if (start + 5 > size) throw new RegionFormatException("chunk " + i + " points past end of file");
			lenBuf.clear();
			readFully(ch, lenBuf, start);
			lenBuf.flip();
			int length = lenBuf.getInt();
			if (length == 0) continue; // "allocated but stream missing": Minecraft treats it as absent
			if (length < 0 || length > sectorCount * SECTOR - 4 || start + 4 + length > size) {
				throw new RegionFormatException("chunk " + i + " has invalid length " + length);
			}
			ByteBuffer payload = ByteBuffer.allocate(length);
			readFully(ch, payload, start + 4);
			visitor.visit(new ChunkSlot(i, timestamps[i], payload.array()));
		}
	}

	/** Slot indices with a chunk, from the location table only (cheap; used for existence checks). */
	public static java.util.BitSet presentIndices(Path file) throws IOException {
		java.util.BitSet out = new java.util.BitSet(CHUNKS);
		if (!Files.isRegularFile(file)) return out;
		try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
			if (ch.size() < SECTOR) return out;
			ByteBuffer loc = ByteBuffer.allocate(SECTOR);
			readFully(ch, loc, 0);
			loc.flip();
			for (int i = 0; i < CHUNKS; i++) if (loc.getInt() != 0) out.set(i);
		}
		return out;
	}

	public static List<ChunkSlot> readAll(Path file) throws IOException {
		List<ChunkSlot> out = new ArrayList<>();
		try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
			read(ch, out::add);
		}
		return out;
	}

	private static void readFully(FileChannel ch, ByteBuffer buf, long pos) throws IOException {
		while (buf.hasRemaining()) {
			int n = ch.read(buf, pos + buf.position());
			if (n < 0) throw new RegionFormatException("unexpected end of region file");
		}
	}

	/** Supplies chunk payloads lazily so that large regions never need to be fully in memory. */
	public interface PayloadSource {
		byte[] payload(int index) throws IOException;
	}

	/** A chunk to write: index, timestamp and payload length (payload fetched lazily). */
	public record SlotInfo(int index, int timestamp, int length) {
	}

	/** Writes a compact, valid region file containing the given chunks in index order. */
	public static void write(OutputStream out, List<SlotInfo> slots, PayloadSource source) throws IOException {
		List<SlotInfo> sorted = new ArrayList<>(slots);
		sorted.sort(Comparator.comparingInt(SlotInfo::index));
		ByteBuffer header = ByteBuffer.allocate(HEADER);
		int[] locations = new int[CHUNKS];
		int[] timestamps = new int[CHUNKS];
		int sector = 2;
		for (SlotInfo s : sorted) {
			int sectors = (s.length() + 4 + SECTOR - 1) / SECTOR;
			if (sectors > 255) throw new RegionFormatException("chunk " + s.index() + " too large for inline storage: " + s.length());
			locations[s.index()] = (sector << 8) | sectors;
			timestamps[s.index()] = s.timestamp();
			sector += sectors;
		}
		for (int l : locations) header.putInt(l);
		for (int t : timestamps) header.putInt(t);
		out.write(header.array());
		byte[] padding = new byte[SECTOR];
		for (SlotInfo s : sorted) {
			byte[] payload = source.payload(s.index());
			if (payload.length != s.length()) throw new IOException("payload length changed for chunk " + s.index());
			out.write(new byte[]{(byte) (payload.length >>> 24), (byte) (payload.length >>> 16), (byte) (payload.length >>> 8), (byte) payload.length});
			out.write(payload);
			int used = (payload.length + 4) % SECTOR;
			if (used != 0) out.write(padding, 0, SECTOR - used);
		}
	}

	public static void write(Path file, List<ChunkSlot> chunks) throws IOException {
		List<SlotInfo> infos = new ArrayList<>();
		java.util.Map<Integer, byte[]> byIndex = new java.util.HashMap<>();
		for (ChunkSlot c : chunks) {
			infos.add(new SlotInfo(c.index(), c.timestamp(), c.payload().length));
			byIndex.put(c.index(), c.payload());
		}
		Files.createDirectories(file.toAbsolutePath().getParent());
		try (OutputStream out = new java.io.BufferedOutputStream(Files.newOutputStream(file), 1 << 16)) {
			write(out, infos, byIndex::get);
		}
	}
}
