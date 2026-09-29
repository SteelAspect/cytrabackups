package dev.steelaspect.cytrabackups.core.compress;

/** Per-blob compression codec. The id is persisted in every blob header. */
public enum Codec {
	NONE(0),
	ZSTD(1),
	DEFLATE(2);

	public final int id;

	Codec(int id) {
		this.id = id;
	}

	public static Codec byId(int id) {
		for (Codec c : values()) if (c.id == id) return c;
		throw new IllegalArgumentException("Unknown codec id " + id);
	}

	public static Codec parse(String name) {
		return switch (name.trim().toLowerCase(java.util.Locale.ROOT)) {
			case "zstd", "zstandard" -> ZSTD;
			case "deflate", "zlib", "gzip" -> DEFLATE;
			case "none", "off", "store" -> NONE;
			default -> throw new IllegalArgumentException("Unknown compression '" + name + "' (use zstd, deflate or none)");
		};
	}
}
