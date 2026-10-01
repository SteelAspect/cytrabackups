package dev.steelaspect.cytrabackups.core.compress;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdDictCompress;
import com.github.luben.zstd.ZstdDictDecompress;
import com.github.luben.zstd.ZstdException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * The native zstd (zstd-jni), used when its library loads on this machine: real compression levels and the chunk
 * dictionary. {@link #available()} is false on platforms without a bundled native library; the pure-Java zstd then
 * handles plain frames and dictionary blobs cannot be read.
 */
public final class ZstdNative {
	/** Dictionary trained on real 1.21 chunk NBT (regions, entities, POI). Never changes: blobs reference it by codec id. */
	public static final String DICTIONARY = "/assets/cytrabackups/zstd/chunks-1.zdict";
	private static final boolean AVAILABLE = probe();
	private static volatile byte[] dictionary;
	private static volatile ZstdDictDecompress dictDecompress;

	private ZstdNative() {
	}

	private static boolean probe() {
		try {
			return Zstd.compressBound(1) > 0;
		} catch (Throwable t) {
			return false;
		}
	}

	public static boolean available() {
		return AVAILABLE;
	}

	public static byte[] dictionary() {
		byte[] d = dictionary;
		if (d == null) {
			try (InputStream in = ZstdNative.class.getResourceAsStream(DICTIONARY)) {
				if (in == null) throw new IllegalStateException("chunk dictionary missing from the mod jar");
				d = in.readAllBytes();
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
			dictionary = d;
		}
		return d;
	}

	/** Compressed length of a plain zstd frame written at {@code level} (1-22). */
	public static int compress(byte[] in, int off, int len, byte[] out, int level) {
		try {
			long n = Zstd.compressByteArray(out, 0, out.length, in, off, len, Math.max(1, Math.min(22, level)));
			if (Zstd.isError(n)) throw new IllegalStateException(Zstd.getErrorName(n));
			return (int) n;
		} catch (ZstdException e) {
			throw new IllegalStateException("zstd compression failed: " + e.getMessage(), e);
		}
	}

	public static int compressWithDictionary(byte[] in, int off, int len, byte[] out, ZstdDictCompress dict) {
		try {
			long n = Zstd.compressFastDict(out, 0, in, off, len, dict);
			if (Zstd.isError(n)) throw new IllegalStateException(Zstd.getErrorName(n));
			return (int) n;
		} catch (ZstdException e) {
			throw new IllegalStateException("zstd compression failed: " + e.getMessage(), e);
		}
	}

	public static ZstdDictCompress dictionaryCompressor(int level) {
		return new ZstdDictCompress(dictionary(), Math.max(1, Math.min(22, level)));
	}

	public static void decompress(byte[] in, int off, int len, byte[] out, int rawLength) throws IOException {
		long n;
		try {
			n = Zstd.decompressByteArray(out, 0, rawLength, in, off, len);
		} catch (ZstdException e) {
			throw new IOException("Corrupt zstd data: " + e.getMessage(), e);
		}
		check(n, rawLength);
	}

	public static void decompressWithDictionary(byte[] in, int off, int len, byte[] out, int rawLength) throws IOException {
		ZstdDictDecompress d = dictDecompress;
		if (d == null) {
			d = new ZstdDictDecompress(dictionary());
			dictDecompress = d;
		}
		long n;
		try {
			n = Zstd.decompressFastDict(out, 0, in, off, len, d);
		} catch (ZstdException e) {
			throw new IOException("Corrupt zstd data: " + e.getMessage(), e);
		}
		check(n, rawLength);
	}

	private static void check(long n, int rawLength) throws IOException {
		if (Zstd.isError(n)) throw new IOException("Corrupt zstd data: " + Zstd.getErrorName(n));
		if (n != rawLength) throw new IOException("zstd length mismatch: " + n + " != " + rawLength);
	}

	public static int maxCompressedLength(int len) {
		return (int) Zstd.compressBound(len);
	}
}
