package dev.steelaspect.cytrabackups.core.manifest;

import dev.steelaspect.cytrabackups.core.Hash;
import dev.steelaspect.cytrabackups.core.store.BlobRef;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/**
 * A region file stored chunk-by-chunk, so a region where one chunk changed only adds that chunk's blob.
 * Restores rebuild a compact region file; its logical hash (over the chunk table) is what gets verified.
 */
public record RegionEntry(String path, long size, long mtime, Hash contentHash, List<ChunkRef> chunks) implements ManifestEntry {
	public RegionEntry {
		List<ChunkRef> sorted = new ArrayList<>(chunks);
		sorted.sort(Comparator.comparingInt(ChunkRef::index));
		chunks = List.copyOf(sorted);
	}

	public static RegionEntry create(String path, long size, long mtime, List<ChunkRef> chunks) {
		return new RegionEntry(path, size, mtime, logicalHash(chunks), chunks);
	}

	public ChunkRef chunk(int index) {
		for (ChunkRef c : chunks) if (c.index() == index) return c;
		return null;
	}

	@Override
	public void forEachBlob(Consumer<BlobRef> consumer) {
		for (ChunkRef c : chunks) consumer.accept(c.blob());
	}

	/** Hash over (index, timestamp, length, blob hash) of every chunk, in index order. */
	public static Hash logicalHash(List<ChunkRef> chunks) {
		List<ChunkRef> sorted = new ArrayList<>(chunks);
		sorted.sort(Comparator.comparingInt(ChunkRef::index));
		MessageDigest md = Hash.newDigest();
		md.update(new byte[]{'R', 'G', 'N', '1'});
		byte[] buf = new byte[12];
		for (ChunkRef c : sorted) {
			putInt(buf, 0, c.index());
			putInt(buf, 4, c.timestamp());
			putInt(buf, 8, c.blob().rawLength());
			md.update(buf);
			c.blob().hash().writeTo(md);
		}
		return Hash.finish(md);
	}

	private static void putInt(byte[] b, int off, int v) {
		b[off] = (byte) (v >>> 24);
		b[off + 1] = (byte) (v >>> 16);
		b[off + 2] = (byte) (v >>> 8);
		b[off + 3] = (byte) v;
	}
}
