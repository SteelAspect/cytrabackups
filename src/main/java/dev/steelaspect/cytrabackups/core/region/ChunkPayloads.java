package dev.steelaspect.cytrabackups.core.region;

import dev.steelaspect.cytrabackups.core.manifest.ChunkRef;
import dev.steelaspect.cytrabackups.core.store.BlobStore;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Turns the chunks of a manifest region entry back into region file payloads, packing unpacked NBT again. */
public final class ChunkPayloads {
	private ChunkPayloads() {
	}

	/** The payload for one chunk reference. */
	public static byte[] payload(BlobStore blobs, ChunkRef c) throws IOException {
		byte[] blob = blobs.read(c.blob().hash());
		return c.unpacked() ? RegionFiles.pack(c.format(), blob) : blob;
	}

	/** Payloads of every chunk, by slot index (chunks only stored off-site are downloaded together). */
	public static Map<Integer, byte[]> payloads(BlobStore blobs, List<ChunkRef> chunks) throws IOException {
		List<byte[]> data = blobs.readAll(chunks.stream().map(c -> c.blob().hash()).toList());
		Map<Integer, byte[]> out = new HashMap<>();
		for (int i = 0; i < chunks.size(); i++) {
			ChunkRef c = chunks.get(i);
			out.put(c.index(), c.unpacked() ? RegionFiles.pack(c.format(), data.get(i)) : data.get(i));
		}
		return out;
	}
}
