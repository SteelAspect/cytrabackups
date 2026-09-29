package dev.steelaspect.cytrabackups.core;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;

/** An immutable SHA-256 digest used as a content address. */
public final class Hash implements Comparable<Hash> {
	public static final int LENGTH = 32;
	private static final HexFormat HEX = HexFormat.of();

	private final byte[] bytes;
	private final int hashCode;

	private Hash(byte[] bytes) {
		this.bytes = bytes;
		this.hashCode = Arrays.hashCode(bytes);
	}

	public static Hash of(byte[] digest) {
		if (digest.length != LENGTH) throw new IllegalArgumentException("SHA-256 digest must be 32 bytes, got " + digest.length);
		return new Hash(digest.clone());
	}

	public static Hash fromHex(String hex) {
		if (hex.length() != LENGTH * 2) throw new IllegalArgumentException("Bad hash length: " + hex);
		return new Hash(HEX.parseHex(hex));
	}

	public static boolean isHex(String s) {
		if (s.length() != LENGTH * 2) return false;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
		}
		return true;
	}

	public static MessageDigest newDigest() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
	}

	public static Hash compute(byte[] data, int off, int len) {
		MessageDigest md = newDigest();
		md.update(data, off, len);
		return new Hash(md.digest());
	}

	public static Hash compute(byte[] data) {
		return compute(data, 0, data.length);
	}

	public static Hash finish(MessageDigest md) {
		return new Hash(md.digest());
	}

	public byte[] toBytes() {
		return bytes.clone();
	}

	/** Writes the raw digest without copying. */
	public void writeTo(MessageDigest md) {
		md.update(bytes);
	}

	public void writeTo(java.io.DataOutput out) throws java.io.IOException {
		out.write(bytes);
	}

	public static Hash readFrom(java.io.DataInput in) throws java.io.IOException {
		byte[] b = new byte[LENGTH];
		in.readFully(b);
		return new Hash(b);
	}

	/** First 64 bits of the digest; used for compact in-memory sets. */
	public long prefix64() {
		long v = 0;
		for (int i = 0; i < 8; i++) v = (v << 8) | (bytes[i] & 0xFFL);
		return v;
	}

	public String hex() {
		return HEX.formatHex(bytes);
	}

	public String shortHex() {
		return hex().substring(0, 12);
	}

	@Override
	public boolean equals(Object o) {
		return this == o || (o instanceof Hash h && Arrays.equals(bytes, h.bytes));
	}

	@Override
	public int hashCode() {
		return hashCode;
	}

	@Override
	public int compareTo(Hash o) {
		return Arrays.compareUnsigned(bytes, o.bytes);
	}

	@Override
	public String toString() {
		return hex();
	}
}
