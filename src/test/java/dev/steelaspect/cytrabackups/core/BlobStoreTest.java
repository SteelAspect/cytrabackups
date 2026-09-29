package dev.steelaspect.cytrabackups.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.steelaspect.cytrabackups.core.compress.Codec;
import dev.steelaspect.cytrabackups.core.compress.Compression;
import dev.steelaspect.cytrabackups.core.store.BlobStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BlobStoreTest {
	@TempDir
	Path dir;

	private BlobStore store() throws Exception {
		return new BlobStore(dir.resolve("blobs"), dir.resolve("tmp"), new Compression(Codec.ZSTD, 3, 0.03), false);
	}

	@Test
	void identicalContentIsStoredOnce() throws Exception {
		BlobStore s = store();
		byte[] data = "hello hello hello hello hello hello hello hello hello hello hello".repeat(50).getBytes(StandardCharsets.UTF_8);
		BlobStore.PutResult a = s.put(data, 0, data.length, false);
		BlobStore.PutResult b = s.put(data.clone(), 0, data.length, false);
		assertTrue(a.isNew());
		assertFalse(b.isNew(), "second put of same content must dedup");
		assertEquals(a.ref().hash(), b.ref().hash());
		assertEquals(Hash.compute(data), a.ref().hash(), "blobs are addressed by SHA-256 of raw content");
		assertTrue(a.ref().storedLength() < data.length, "compressible data is compressed");
		try (var files = Files.walk(dir.resolve("blobs"))) {
			assertEquals(1, files.filter(Files::isRegularFile).count());
		}
		assertArrayEquals(data, s.read(a.ref().hash()));
	}

	@Test
	void corruptionIsDetectedOnRead() throws Exception {
		BlobStore s = store();
		byte[] data = new byte[5000];
		new java.util.Random(4).nextBytes(data);
		Hash h = s.put(data, 0, data.length, false).ref().hash();
		Path file = s.pathFor(h);
		byte[] raw = Files.readAllBytes(file);
		raw[raw.length - 10] ^= 1;
		Files.write(file, raw);
		assertFalse(s.verify(h));
		assertThrows(BlobStore.CorruptBlobException.class, () -> s.read(h));
	}

	@Test
	void missingBlobIsReported() throws Exception {
		BlobStore s = store();
		Hash h = Hash.compute(new byte[]{1, 2, 3});
		assertThrows(BlobStore.CorruptBlobException.class, () -> s.read(h));
	}
}
