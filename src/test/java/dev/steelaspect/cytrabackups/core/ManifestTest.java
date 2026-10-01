package dev.steelaspect.cytrabackups.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.steelaspect.cytrabackups.core.manifest.ChunkRef;
import dev.steelaspect.cytrabackups.core.manifest.FileEntry;
import dev.steelaspect.cytrabackups.core.manifest.Manifest;
import dev.steelaspect.cytrabackups.core.manifest.RegionEntry;
import dev.steelaspect.cytrabackups.core.store.BlobRef;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import org.junit.jupiter.api.Test;

class ManifestTest {
	private static BlobRef ref(String s) {
		return new BlobRef(Hash.compute(s.getBytes()), s.length(), s.length() + 9);
	}

	@Test
	void binaryRoundTrip() throws Exception {
		FileEntry f = new FileEntry("level.dat", 10, 123L, Hash.compute("x".getBytes()), List.of(ref("x")));
		RegionEntry r = RegionEntry.create("region/r.0.0.mca", 8192 + 4096, 456L, List.of(new ChunkRef(5, 99, ref("c5")), new ChunkRef(1, 98, ref("c1"))));
		Manifest m = new Manifest(List.of(r, f));
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		m.write(out);
		Manifest back = Manifest.read(new ByteArrayInputStream(out.toByteArray()));
		assertEquals(2, back.size());
		assertEquals(List.of("level.dat", "region/r.0.0.mca"), back.entries().stream().map(e -> e.path()).toList());
		RegionEntry rb = (RegionEntry) back.get("region/r.0.0.mca");
		assertEquals(List.of(1, 5), rb.chunks().stream().map(ChunkRef::index).toList(), "chunks sorted by slot");
		assertEquals(r.contentHash(), rb.contentHash());
		assertEquals(RegionEntry.logicalHash(rb.chunks()), rb.contentHash());
		assertEquals(f, back.get("level.dat"));
		assertTrue(m.sameContent(back, p -> false));
	}

	@Test
	void unpackedRegionsKeepFormatBytes() throws Exception {
		RegionEntry r = RegionEntry.create("region/r.1.1.mca", 8192, 1L, List.of(new ChunkRef(0, 1, ref("nbt0"), 2), new ChunkRef(7, 2, ref("raw7"), ChunkRef.RAW)), true);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		new Manifest(List.of(r)).write(out);
		RegionEntry back = (RegionEntry) Manifest.read(new ByteArrayInputStream(out.toByteArray())).get("region/r.1.1.mca");
		assertTrue(back.unpacked());
		assertEquals(2, back.chunks().get(0).format());
		assertTrue(back.chunks().get(0).unpacked());
		assertEquals(ChunkRef.RAW, back.chunks().get(1).format());
		assertFalse(back.chunks().get(1).unpacked());
		assertEquals(r, back);
	}

	@Test
	void sameContentHonoursIgnoreList() {
		FileEntry a = new FileEntry("level.dat", 1, 1, Hash.compute("a".getBytes()), List.of(ref("a")));
		FileEntry b = new FileEntry("level.dat", 1, 2, Hash.compute("b".getBytes()), List.of(ref("b")));
		Manifest m1 = new Manifest(List.of(a));
		Manifest m2 = new Manifest(List.of(b));
		assertFalse(m1.sameContent(m2, p -> false));
		assertTrue(m1.sameContent(m2, p -> p.equals("level.dat")));
	}
}
