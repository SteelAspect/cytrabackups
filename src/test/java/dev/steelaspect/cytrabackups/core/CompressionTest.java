package dev.steelaspect.cytrabackups.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.steelaspect.cytrabackups.core.compress.Codec;
import dev.steelaspect.cytrabackups.core.compress.Compression;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class CompressionTest {
	private static byte[] text(int n) {
		StringBuilder sb = new StringBuilder();
		while (sb.length() < n) sb.append("minecraft:stone minecraft:dirt minecraft:grass_block ");
		return sb.substring(0, n).getBytes(StandardCharsets.UTF_8);
	}

	@ParameterizedTest
	@ValueSource(ints = {1, 3, 6, 9, 19})
	void zstdRoundTripAtEveryLevel(int level) throws IOException {
		byte[] raw = text(200_000);
		Compression c = new Compression(Codec.ZSTD, level, 0.03);
		Compression.Encoded enc = c.encode(raw, 0, raw.length, false);
		assertEquals(Codec.ZSTD, enc.codec());
		assertTrue(enc.length() < raw.length / 5, "text should compress well at level " + level);
		assertArrayEquals(raw, Compression.decode(enc.codec(), enc.data(), 0, enc.length(), raw.length));
	}

	@Test
	void deflateRoundTrip() throws IOException {
		byte[] raw = text(100_000);
		Compression c = new Compression(Codec.DEFLATE, 6, 0.03);
		Compression.Encoded enc = c.encode(raw, 0, raw.length, false);
		assertEquals(Codec.DEFLATE, enc.codec());
		assertArrayEquals(raw, Compression.decode(enc.codec(), enc.data(), 0, enc.length(), raw.length));
	}

	@Test
	void incompressibleDataIsStoredRaw() throws IOException {
		byte[] raw = new byte[64_000];
		new Random(1).nextBytes(raw);
		Compression.Encoded enc = new Compression(Codec.ZSTD, 3, 0.03).encode(raw, 0, raw.length, false);
		assertEquals(Codec.NONE, enc.codec());
		assertArrayEquals(raw, Compression.decode(enc.codec(), enc.data(), 0, enc.length(), raw.length));
	}

	@Test
	void alreadyCompressedFormatsAreSkipped() {
		byte[] gzip = text(10_000);
		gzip[0] = 0x1F;
		gzip[1] = (byte) 0x8B;
		assertTrue(Compression.looksCompressed(gzip, 0, gzip.length));
		assertEquals(Codec.NONE, new Compression(Codec.ZSTD, 3, 0.03).encode(gzip, 0, gzip.length, false).codec());
		byte[] plain = text(10_000);
		assertEquals(Codec.NONE, new Compression(Codec.ZSTD, 3, 0.03).encode(plain, 0, plain.length, true).codec(), "caller hint wins");
	}

	@Test
	void nativeZstdHonoursLevels() throws IOException {
		assumeTrue(Compression.nativeZstd(), "native zstd not available on this platform");
		byte[] raw = text(300_000);
		Compression.Encoded fast = new Compression(Codec.ZSTD, 1, 0.03).encode(raw, 0, raw.length, false);
		Compression.Encoded best = new Compression(Codec.ZSTD, 19, 0.03).encode(raw, 0, raw.length, false);
		assertTrue(best.length() <= fast.length(), "level 19 should not be larger than level 1");
		assertArrayEquals(raw, Compression.decode(Codec.ZSTD, best.data(), 0, best.length(), raw.length));
	}

	@Test
	void chunkEncoderUsesDictionaryAndRoundTrips() throws IOException {
		assumeTrue(Compression.nativeZstd(), "native zstd not available on this platform");
		byte[] nbt = TestWorlds.chunkNbt(42L, 20_000);
		Compression.ChunkEncoder enc = new Compression(Codec.ZSTD, 3, 0.03).chunkEncoder(15);
		Compression.Encoded e = enc.encode(nbt, 0, nbt.length);
		assertEquals(Codec.ZSTD_DICT, e.codec());
		assertTrue(e.length() < nbt.length / 2, "chunk-like data should compress");
		assertArrayEquals(nbt, Compression.decode(Codec.ZSTD_DICT, e.data(), 0, e.length(), nbt.length));
		Compression.Encoded plain = new Compression(Codec.ZSTD, 15, 0.03).encode(nbt, 0, nbt.length, false);
		assertThrows(IOException.class, () -> Compression.decode(Codec.ZSTD, e.data(), 0, e.length(), nbt.length), "dictionary frames are not plain frames");
		assertArrayEquals(nbt, Compression.decode(Codec.ZSTD, plain.data(), 0, plain.length(), nbt.length));
	}

	@Test
	void corruptZstdIsDetected() {
		byte[] raw = text(50_000);
		Compression.Encoded enc = new Compression(Codec.ZSTD, 3, 0.03).encode(raw, 0, raw.length, false);
		byte[] broken = java.util.Arrays.copyOf(enc.data(), enc.length());
		for (int i = 10; i < broken.length; i += 7) broken[i] ^= 0x5A;
		assertThrows(IOException.class, () -> {
			byte[] out = Compression.decode(Codec.ZSTD, broken, 0, broken.length, raw.length);
			if (!java.util.Arrays.equals(out, raw)) throw new IOException("mismatch");
		});
	}

}
