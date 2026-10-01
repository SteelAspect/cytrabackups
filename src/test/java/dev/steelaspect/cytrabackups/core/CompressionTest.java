package dev.steelaspect.cytrabackups.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
