package dev.steelaspect.cytrabackups.core.manifest;

import dev.steelaspect.cytrabackups.core.Hash;
import dev.steelaspect.cytrabackups.core.store.BlobRef;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/** The list of files making up one backup. Serialized in a compact, deflate-compressed binary format. */
public final class Manifest {
	private static final int MAGIC = 0x4359424D; // "CYBM"
	private static final int VERSION = 2; // 2: per-chunk format byte and per-region unpacked flag
	private static final byte TYPE_FILE = 0;
	private static final byte TYPE_REGION = 1;

	private final List<ManifestEntry> entries;
	private final Map<String, ManifestEntry> byPath;

	public Manifest(Collection<? extends ManifestEntry> entries) {
		List<ManifestEntry> sorted = new ArrayList<>(entries);
		sorted.sort(Comparator.comparing(ManifestEntry::path));
		this.entries = Collections.unmodifiableList(sorted);
		Map<String, ManifestEntry> map = new LinkedHashMap<>();
		for (ManifestEntry e : sorted) {
			if (map.put(e.path(), e) != null) throw new IllegalArgumentException("Duplicate path in manifest: " + e.path());
		}
		this.byPath = Collections.unmodifiableMap(map);
	}

	public static Manifest empty() {
		return new Manifest(List.of());
	}

	public List<ManifestEntry> entries() {
		return entries;
	}

	public ManifestEntry get(String path) {
		return byPath.get(path);
	}

	public int size() {
		return entries.size();
	}

	public long totalSize() {
		long s = 0;
		for (ManifestEntry e : entries) s += e.size();
		return s;
	}

	public long chunkCount() {
		long n = 0;
		for (ManifestEntry e : entries) if (e instanceof RegionEntry r) n += r.chunks().size();
		return n;
	}

	public void forEachBlob(Consumer<BlobRef> consumer) {
		for (ManifestEntry e : entries) e.forEachBlob(consumer);
	}

	/** True if both manifests describe identical content, ignoring paths matched by {@code ignore}. */
	public boolean sameContent(Manifest other, Predicate<String> ignore) {
		for (ManifestEntry e : entries) {
			if (ignore.test(e.path())) continue;
			ManifestEntry o = other.get(e.path());
			if (o == null || !o.contentHash().equals(e.contentHash())) return false;
		}
		for (ManifestEntry o : other.entries) {
			if (ignore.test(o.path())) continue;
			if (get(o.path()) == null) return false;
		}
		return true;
	}

	public void write(OutputStream rawOut) throws IOException {
		DeflaterOutputStream deflate = new DeflaterOutputStream(rawOut, new java.util.zip.Deflater(6), 1 << 16);
		DataOutputStream out = new DataOutputStream(deflate);
		out.writeInt(MAGIC);
		out.writeInt(VERSION);
		out.writeInt(entries.size());
		for (ManifestEntry e : entries) {
			if (e instanceof FileEntry f) {
				out.writeByte(TYPE_FILE);
				writeCommon(out, e);
				out.writeInt(f.pieces().size());
				for (BlobRef b : f.pieces()) writeBlob(out, b);
			} else if (e instanceof RegionEntry r) {
				out.writeByte(TYPE_REGION);
				writeCommon(out, e);
				out.writeByte(r.unpacked() ? 1 : 0);
				out.writeShort(r.chunks().size());
				for (ChunkRef c : r.chunks()) {
					out.writeShort(c.index());
					out.writeInt(c.timestamp());
					out.writeByte(c.format());
					writeBlob(out, c.blob());
				}
			}
		}
		out.flush();
		deflate.finish();
	}

	private static void writeCommon(DataOutputStream out, ManifestEntry e) throws IOException {
		out.writeUTF(e.path());
		out.writeLong(e.size());
		out.writeLong(e.mtime());
		e.contentHash().writeTo(out);
	}

	private static void writeBlob(DataOutputStream out, BlobRef b) throws IOException {
		b.hash().writeTo(out);
		out.writeInt(b.rawLength());
		out.writeLong(b.storedLength());
	}

	private static BlobRef readBlob(DataInputStream in) throws IOException {
		Hash h = Hash.readFrom(in);
		int raw = in.readInt();
		long stored = in.readLong();
		return new BlobRef(h, raw, stored);
	}

	public static Manifest read(InputStream rawIn) throws IOException {
		DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(new InflaterInputStream(rawIn), 1 << 16));
		if (in.readInt() != MAGIC) throw new IOException("Not a CytraBackups manifest");
		int version = in.readInt();
		if (version < 1 || version > VERSION) throw new IOException("Unsupported manifest version " + version + " (made by a newer CytraBackups)");
		int count = in.readInt();
		if (count < 0) throw new IOException("Corrupt manifest entry count");
		List<ManifestEntry> list = new ArrayList<>(Math.min(count, 1 << 20));
		for (int i = 0; i < count; i++) {
			byte type = in.readByte();
			String path = in.readUTF();
			long size = in.readLong();
			long mtime = in.readLong();
			Hash content = Hash.readFrom(in);
			if (type == TYPE_FILE) {
				int n = in.readInt();
				if (n < 0) throw new IOException("Corrupt piece count");
				List<BlobRef> pieces = new ArrayList<>(n);
				for (int j = 0; j < n; j++) pieces.add(readBlob(in));
				list.add(new FileEntry(path, size, mtime, content, pieces));
			} else if (type == TYPE_REGION) {
				boolean unpacked = version >= 2 && in.readByte() != 0;
				int n = in.readUnsignedShort();
				List<ChunkRef> chunks = new ArrayList<>(n);
				for (int j = 0; j < n; j++) {
					int index = in.readUnsignedShort();
					int ts = in.readInt();
					int format = version >= 2 ? in.readUnsignedByte() : ChunkRef.RAW;
					chunks.add(new ChunkRef(index, ts, readBlob(in), format));
				}
				list.add(new RegionEntry(path, size, mtime, content, chunks, unpacked));
			} else {
				throw new IOException("Unknown manifest entry type " + type);
			}
		}
		return new Manifest(list);
	}
}
