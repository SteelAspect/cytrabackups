package dev.steelaspect.cytrabackups.core.backup;

import java.util.Locale;

/** Maps dimension ids to their folder inside the world, following vanilla's layout. */
public final class DimensionPaths {
	private DimensionPaths() {
	}

	/** "" for the overworld, "DIM-1" / "DIM1" for vanilla nether/end, "dimensions/ns/path" otherwise. */
	public static String folder(String dimensionId) {
		String id = dimensionId.toLowerCase(Locale.ROOT);
		if (!id.contains(":")) id = "minecraft:" + id;
		return switch (id) {
			case "minecraft:overworld" -> "";
			case "minecraft:the_nether" -> "DIM-1";
			case "minecraft:the_end" -> "DIM1";
			default -> {
				String ns = id.substring(0, id.indexOf(':'));
				String path = id.substring(id.indexOf(':') + 1);
				yield "dimensions/" + ns + "/" + path;
			}
		};
	}

	/** Relative path of a region-type file ("region", "entities" or "poi") for a dimension. */
	public static String regionPath(String dimensionFolder, String kind, int rx, int rz) {
		String prefix = dimensionFolder.isEmpty() ? "" : dimensionFolder + "/";
		return prefix + kind + "/r." + rx + "." + rz + ".mca";
	}

	public static final String[] REGION_KINDS = {"region", "entities", "poi"};
}
