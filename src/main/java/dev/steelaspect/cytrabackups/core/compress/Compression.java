package dev.steelaspect.cytrabackups.core.compress;

import io.airlift.compress.zstd.ZstdCompressor;
import io.airlift.compress.zstd.ZstdDecompressor;
import java.io.IOException;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Compresses blobs with zstd (pure Java, aircompressor) or deflate. Data that is already compressed
 * (sniffed by magic bytes, or flagged by the caller) or that does not shrink enough is stored raw.
 */
public final class Compression {
	private final Codec codec;
	private final int level;
	private final double minSavings;

	/**
	 * @param minSavings minimum fraction saved (e.g. 0.03) for the compressed form to be kept
	 */
	public Compression(Codec codec, int level, double minSavings) {
		this.codec = codec;
		this.level = level;
		this.minSavings = minSavings;
	}

	public Codec codec() {
		return codec;
	}

	public static boolean nativeZstd() {
		return ZstdNative.available();
	}

	/**
	 * Encoder for chunk NBT: zstd with the shipped chunk dictionary at {@code level} (1-22) when the native zstd is
	 * available, otherwise this instance's normal encoding.
	 */
	public Compression.ChunkEncoder chunkEncoder(int level) {
		if (!ZstdNative.available()) return (raw, off, len) -> encode(raw, off, len, false);
		com.github.luben.zstd.ZstdDictCompress dict = ZstdNative.dictionaryCompressor(level);
		return (raw, off, len) -> {
			if (len < 64) return stored(raw, off, len);
			byte[] out = new byte[ZstdNative.maxCompressedLength(len)];
			int n = ZstdNative.compressWithDictionary(raw, off, len, out, dict);
			if (n >= len * (1.0 - minSavings)) return stored(raw, off, len);
			return new Encoded(Codec.ZSTD_DICT, out, n);
		};
	}

	public interface ChunkEncoder {
		Encoded encode(byte[] raw, int off, int len);
	}

	public int level() {
		return level;
	}

	public record Encoded(Codec codec, byte[] data, int length) {
	}

	public Encoded encode(byte[] raw, int off, int len, boolean knownCompressed) {
		if (codec == Codec.NONE || len < 64 || knownCompressed || looksCompressed(raw, off, len)) {
			return stored(raw, off, len);
		}
		byte[] out;
		int outLen;
		try {
			if (codec == Codec.ZSTD) {
				if (ZstdNative.available()) {
					out = new byte[ZstdNative.maxCompressedLength(len)];
					outLen = ZstdNative.compress(raw, off, len, out, level);
				} else {
					// aircompressor's pure-Java zstd implements one strategy (about zstd level 3); other levels are not available
					ZstdCompressor z = new ZstdCompressor();
					out = new byte[z.maxCompressedLength(len)];
					outLen = z.compress(raw, off, len, out, 0, out.length);
				}
			} else {
				Deflater deflater = new Deflater(Math.max(1, Math.min(9, level)));
				try {
					deflater.setInput(raw, off, len);
					deflater.finish();
					out = new byte[len + (len >> 3) + 64];
					outLen = 0;
					while (!deflater.finished()) {
						if (outLen == out.length) out = java.util.Arrays.copyOf(out, out.length * 2);
						outLen += deflater.deflate(out, outLen, out.length - outLen);
					}
				} finally {
					deflater.end();
				}
			}
		} catch (RuntimeException e) {
			return stored(raw, off, len);
		}
		if (outLen >= len * (1.0 - minSavings)) return stored(raw, off, len);
		return new Encoded(codec, out, outLen);
	}

	private static Encoded stored(byte[] raw, int off, int len) {
		if (off == 0) return new Encoded(Codec.NONE, raw, len);
		return new Encoded(Codec.NONE, java.util.Arrays.copyOfRange(raw, off, off + len), len);
	}

	public static byte[] decode(Codec codec, byte[] data, int off, int len, int rawLength) throws IOException {
		switch (codec) {
			case NONE -> {
				if (len != rawLength) throw new IOException("Stored blob length mismatch: " + len + " != " + rawLength);
				return java.util.Arrays.copyOfRange(data, off, off + len);
			}
			case ZSTD -> {
				byte[] out = new byte[rawLength];
				if (ZstdNative.available()) {
					ZstdNative.decompress(data, off, len, out, rawLength);
					return out;
				}
				int n;
				try {
					n = new ZstdDecompressor().decompress(data, off, len, out, 0, rawLength);
				} catch (RuntimeException e) {
					throw new IOException("Corrupt zstd data: " + e.getMessage(), e);
				}
				if (n != rawLength) throw new IOException("zstd length mismatch: " + n + " != " + rawLength);
				return out;
			}
			case ZSTD_DICT -> {
				if (!ZstdNative.available()) throw new IOException("This blob was compressed with the chunk dictionary, which needs the native zstd library that could not be loaded on this machine");
				byte[] out = new byte[rawLength];
				ZstdNative.decompressWithDictionary(data, off, len, out, rawLength);
				return out;
			}
			case DEFLATE -> {
				Inflater inflater = new Inflater();
				try {
					inflater.setInput(data, off, len);
					byte[] out = new byte[rawLength];
					int n = 0;
					while (n < rawLength) {
						int r = inflater.inflate(out, n, rawLength - n);
						if (r == 0 && (inflater.finished() || inflater.needsInput() || inflater.needsDictionary())) break;
						n += r;
					}
					if (n != rawLength || !inflater.finished()) throw new IOException("deflate length mismatch: " + n + " != " + rawLength);
					return out;
				} catch (DataFormatException e) {
					throw new IOException("Corrupt deflate data", e);
				} finally {
					inflater.end();
				}
			}
			default -> throw new IOException("Unknown codec " + codec);
		}
	}

	/** Magic-byte sniffing for formats that won't compress further. */
	public static boolean looksCompressed(byte[] d, int off, int len) {
		if (len < 4) return false;
		int b0 = d[off] & 0xFF, b1 = d[off + 1] & 0xFF, b2 = d[off + 2] & 0xFF, b3 = d[off + 3] & 0xFF;
		if (b0 == 0x1F && b1 == 0x8B) return true; // gzip (level.dat, playerdata, data/*.dat)
		if (b0 == 0x78 && (b1 == 0x01 || b1 == 0x5E || b1 == 0x9C || b1 == 0xDA)) return true; // zlib
		if (b0 == 0x50 && b1 == 0x4B && b2 == 0x03 && b3 == 0x04) return true; // zip / jar
		if (b0 == 0x89 && b1 == 0x50 && b2 == 0x4E && b3 == 0x47) return true; // png
		if (b0 == 0x28 && b1 == 0xB5 && b2 == 0x2F && b3 == 0xFD) return true; // zstd
		if (b0 == 0xFF && b1 == 0xD8 && b2 == 0xFF) return true; // jpeg
		if (b0 == 0xFD && b1 == 0x37 && b2 == 0x7A && b3 == 0x58) return true; // xz
		if (b0 == 0x42 && b1 == 0x5A && b2 == 0x68) return true; // bzip2
		if (b0 == 0x04 && b1 == 0x22 && b2 == 0x4D && b3 == 0x18) return true; // lz4 frame
		if (b0 == 0x4C && b1 == 0x5A && b2 == 0x34 && b3 == 0x42) return true; // lz4 block stream (LZ4Block)
		if (b0 == 0x37 && b1 == 0x7A && b2 == 0xBC && b3 == 0xAF) return true; // 7z
		if (b0 == 0x4F && b1 == 0x67 && b2 == 0x67 && b3 == 0x53) return true; // ogg
		if (b0 == 0x52 && b1 == 0x49 && b2 == 0x46 && b3 == 0x46 && len >= 12
			&& d[off + 8] == 'W' && d[off + 9] == 'E' && d[off + 10] == 'B' && d[off + 11] == 'P') return true; // webp
		return false;
	}
}
