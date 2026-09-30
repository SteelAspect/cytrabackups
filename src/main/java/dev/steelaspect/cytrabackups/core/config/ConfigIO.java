package dev.steelaspect.cytrabackups.core.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import dev.steelaspect.cytrabackups.core.FileUtil;
import dev.steelaspect.cytrabackups.core.Lang;
import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Reads JSON-with-comments and writes the config back with a comment above every documented field. */
public final class ConfigIO {
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

	private ConfigIO() {
	}

	public static CytraConfig load(Path file) throws IOException {
		if (!Files.exists(file)) {
			CytraConfig cfg = new CytraConfig();
			save(file, cfg);
			return cfg;
		}
		String text = Files.readString(file, StandardCharsets.UTF_8);
		CytraConfig cfg;
		try {
			JsonReader reader = new JsonReader(new StringReader(text));
			reader.setStrictness(Strictness.LENIENT); // accepts // and /* */ comments and trailing commas
			cfg = GSON.fromJson(reader, CytraConfig.class);
		} catch (JsonParseException | IllegalStateException e) {
			throw new IOException(Lang.get("cytrabackups.config.problem.parse", file.getFileName(), e.getMessage()), e);
		}
		if (cfg == null) cfg = new CytraConfig();
		ConfigSchema.normalize(cfg);
		List<String> problems = validate(cfg);
		if (!problems.isEmpty()) throw new IOException(Lang.get("cytrabackups.config.problem.invalid", String.join("; ", problems)));
		save(file, cfg); // adds options introduced by newer versions
		return cfg;
	}

	public static List<String> validate(CytraConfig c) {
		List<String> p = new ArrayList<>(ConfigSchema.genericProblems(c));
		if (c.storagePath == null || c.storagePath.isBlank()) p.add(Lang.get("cytrabackups.config.problem.empty", "storagePath"));
		String alg = c.compression.algorithm.toLowerCase(Locale.ROOT);
		if (alg.equals("zstd") && (c.compression.level < 1 || c.compression.level > 19)) p.add(Lang.get("cytrabackups.config.problem.range_for", "compression.level", 1, 19, "zstd"));
		if (alg.equals("deflate") && (c.compression.level < 1 || c.compression.level > 9)) p.add(Lang.get("cytrabackups.config.problem.range_for", "compression.level", 1, 9, "deflate"));
		if (c.compression.minSavingsPercent > 100) p.add(Lang.get("cytrabackups.config.problem.range", "compression.minSavingsPercent", 0, 100));
		if (c.offsite.sftp.port < 1 || c.offsite.sftp.port > 65535) p.add(Lang.get("cytrabackups.config.problem.range", "offsite.sftp.port", 1, 65535));
		if (c.restore.maxChunks < 1) p.add(Lang.get("cytrabackups.config.problem.at_least", "restore.maxChunks", 1));
		for (Map.Entry<String, Integer> e : c.permissions.defaultLevels.entrySet()) {
			if (e.getValue() == null || e.getValue() < 0 || e.getValue() > 4) p.add(Lang.get("cytrabackups.config.problem.range", "permissions.defaultLevels." + e.getKey(), 0, 4));
		}
		for (String t : c.schedule.timesOfDay) {
			if (!t.matches("([01]?\\d|2[0-3]):[0-5]\\d")) p.add(Lang.get("cytrabackups.config.problem.time", t));
		}
		if (!c.timeZone.equals("system")) {
			try {
				ZoneId.of(c.timeZone);
			} catch (Exception e) {
				p.add(Lang.get("cytrabackups.config.problem.zone", c.timeZone));
			}
		}
		if (c.restore.countdownSeconds < 0 || c.restore.countdownSeconds > 600) p.add(Lang.get("cytrabackups.config.problem.range", "restore.countdownSeconds", 0, 600));
		if (c.restore.confirmTimeoutSeconds < 5) p.add(Lang.get("cytrabackups.config.problem.at_least", "restore.confirmTimeoutSeconds", 5));
		return p;
	}

	public static void save(Path file, CytraConfig cfg) throws IOException {
		StringBuilder sb = new StringBuilder();
		sb.append("// ").append(Lang.get("cytrabackups.config.file_header")).append('\n');
		writeObject(sb, cfg, "", 0);
		sb.append('\n');
		FileUtil.writeAtomic(file, sb.toString());
	}

	private static void writeObject(StringBuilder sb, Object obj, String prefix, int indent) {
		sb.append("{\n");
		List<Field> fields = new ArrayList<>();
		for (Field f : obj.getClass().getDeclaredFields()) {
			int mod = f.getModifiers();
			if (Modifier.isStatic(mod) || Modifier.isTransient(mod)) continue;
			fields.add(f);
		}
		for (int i = 0; i < fields.size(); i++) {
			Field f = fields.get(i);
			String key = "cytrabackups.config." + prefix + f.getName();
			if (Lang.has(key)) {
				for (String line : Lang.raw(key).split("\n")) indent(sb, indent + 1).append("// ").append(line).append('\n');
			}
			indent(sb, indent + 1).append(GSON.toJson(f.getName())).append(": ");
			Object value;
			try {
				f.setAccessible(true);
				value = f.get(obj);
			} catch (IllegalAccessException e) {
				throw new IllegalStateException(e);
			}
			writeValue(sb, value, prefix + f.getName() + ".", indent + 1);
			if (i < fields.size() - 1) sb.append(',');
			sb.append('\n');
		}
		indent(sb, indent).append('}');
	}

	private static void writeValue(StringBuilder sb, Object value, String prefix, int indent) {
		if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean || value instanceof Enum<?>) {
			sb.append(GSON.toJson(value));
		} else if (value instanceof List<?> list) {
			sb.append('[');
			for (int i = 0; i < list.size(); i++) {
				if (i > 0) sb.append(", ");
				sb.append(GSON.toJson(list.get(i)));
			}
			sb.append(']');
		} else if (value instanceof Map<?, ?> map) {
			sb.append("{\n");
			int i = 0;
			for (Map.Entry<?, ?> e : map.entrySet()) {
				indent(sb, indent + 1).append(GSON.toJson(String.valueOf(e.getKey()))).append(": ").append(GSON.toJson(e.getValue()));
				if (++i < map.size()) sb.append(',');
				sb.append('\n');
			}
			indent(sb, indent).append('}');
		} else {
			writeObject(sb, value, prefix, indent);
		}
	}

	private static StringBuilder indent(StringBuilder sb, int level) {
		for (int i = 0; i < level; i++) sb.append("  ");
		return sb;
	}
}
