package dev.steelaspect.cytrabackups.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.steelaspect.cytrabackups.core.region.RegionFiles;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RegionFilesTest {
	@TempDir
	Path dir;

	@Test
	void writeThenReadIsLossless() throws Exception {
		Path f = dir.resolve("r.0.0.mca");
		TestWorlds.writeRegion(f, Map.of(0, 1L, 31, 2L, 32, 3L, 1023, 4L));
		List<RegionFiles.ChunkSlot> slots = RegionFiles.readAll(f);
		assertEquals(4, slots.size());
		assertEquals(List.of(0, 31, 32, 1023), slots.stream().map(RegionFiles.ChunkSlot::index).toList());
		assertArrayEquals(TestWorlds.chunkPayload(4L, 3000), slots.get(3).payload());
		assertEquals(0, Files.size(f) % RegionFiles.SECTOR, "region files are sector aligned");
		assertTrue(slots.get(0).isCompressed());
	}

	@Test
	void coordinatesAndNames() {
		assertEquals(33, RegionFiles.index(-31, 1)); // -31 & 31 = 1, 1*32
		assertEquals(-32, RegionFiles.chunkX(-1, 0));
		assertArrayEquals(new int[]{-3, 12}, RegionFiles.regionCoords("r.-3.12.mca"));
		assertEquals("c.-5.7.mcc", RegionFiles.externalChunkFileName(-5, 7));
	}

	@Test
	void emptyAndTruncatedRegionFilesHaveNoChunks() throws Exception {
		Path empty = dir.resolve("r.2.2.mca");
		Files.write(empty, new byte[0]);
		assertEquals(0, RegionFiles.readAll(empty).size());
		Path shortFile = dir.resolve("r.3.3.mca");
		Files.write(shortFile, new byte[100]);
		assertEquals(0, RegionFiles.readAll(shortFile).size());
	}

	@Test
	void invalidRegionIsRejected() throws Exception {
		Path f = dir.resolve("r.1.1.mca");
		ByteBuffer b = ByteBuffer.allocate(RegionFiles.HEADER);
		b.putInt(0, (500 << 8) | 1); // points far past EOF
		Files.write(f, b.array());
		assertThrows(RegionFiles.RegionFormatException.class, () -> RegionFiles.readAll(f));
	}
}
