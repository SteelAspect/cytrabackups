package dev.steelaspect.cytrabackups.core.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import dev.steelaspect.cytrabackups.core.FileUtil;
import java.io.IOException;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
			throw new IOException("Invalid config " + file.getFileName() + ": " + e.getMessage(), e);
		}
		if (cfg == null) cfg = new CytraConfig();
		List<String> problems = validate(cfg);
		if (!problems.isEmpty()) throw new IOException("Invalid config: " + String.join("; ", problems));
		save(file, cfg); // adds new options and refreshes comments
		return cfg;
	}

	public static List<String> validate(CytraConfig c) {
		List<String> p = new ArrayList<>();
		String alg = c.compression.algorithm.toLowerCase(java.util.Locale.ROOT);
		if (!List.of("zstd", "deflate", "none").contains(alg)) p.add("compression.algorithm must be zstd, deflate or none");
		if (!List.of("startup", "shutdown").contains(c.restore.applyMode)) p.add("restore.applyMode must be \"startup\" or \"shutdown\"");
		if (!List.of("permitted", "everyone", "nobody").contains(c.progress.showTo)) p.add("progress.showTo must be permitted, everyone or nobody");
		if (!List.of("s3", "sftp", "webdav").contains(c.offsite.type)) p.add("offsite.type must be s3, sftp or webdav");
		if (!List.of("tofu", "yes", "no").contains(c.offsite.sftp.hostKeyChecking)) p.add("offsite.sftp.hostKeyChecking must be tofu, yes or no");
		for (String t : c.schedule.timesOfDay) {
			if (!t.matches("([01]?\\d|2[0-3]):[0-5]\\d")) p.add("schedule.timesOfDay entry '" + t + "' is not HH:mm");
		}
		if (!c.timeZone.equals("system")) {
			try {
				java.time.ZoneId.of(c.timeZone);
			} catch (Exception e) {
				p.add("timeZone '" + c.timeZone + "' is not a valid zone id");
			}
		}
		if (c.restore.countdownSeconds < 0 || c.restore.countdownSeconds > 600) p.add("restore.countdownSeconds must be 0-600");
		if (c.restore.confirmTimeoutSeconds < 5) p.add("restore.confirmTimeoutSeconds must be at least 5");
		return p;
	}

	public static void save(Path file, CytraConfig cfg) throws IOException {
		StringBuilder sb = new StringBuilder();
		sb.append("// CytraBackups configuration. Reload with /backup reload. Comments are regenerated on save.\n");
		writeObject(sb, cfg, 0);
		sb.append('\n');
		FileUtil.writeAtomic(file, sb.toString());
	}

	private static void writeObject(StringBuilder sb, Object obj, int indent) {
		sb.append("{\n");
		List<Field> fields = new ArrayList<>();
		for (Field f : obj.getClass().getDeclaredFields()) {
			int mod = f.getModifiers();
			if (Modifier.isStatic(mod) || Modifier.isTransient(mod)) continue;
			fields.add(f);
		}
		for (int i = 0; i < fields.size(); i++) {
			Field f = fields.get(i);
			Comment c = f.getAnnotation(Comment.class);
			if (c != null) {
				for (String line : c.value()) indent(sb, indent + 1).append("// ").append(line).append('\n');
			}
			indent(sb, indent + 1).append(GSON.toJson(f.getName())).append(": ");
			Object value;
			try {
				f.setAccessible(true);
				value = f.get(obj);
			} catch (IllegalAccessException e) {
				throw new IllegalStateException(e);
			}
			writeValue(sb, value, indent + 1);
			if (i < fields.size() - 1) sb.append(',');
			sb.append('\n');
		}
		indent(sb, indent).append('}');
	}

	private static void writeValue(StringBuilder sb, Object value, int indent) {
		if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean || value instanceof Enum<?>) {
			sb.append(GSON.toJson(value));
		} else if (value instanceof List<?> list) {
			sb.append(GSON.toJson(list));
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
			writeObject(sb, value, indent);
		}
	}

	private static StringBuilder indent(StringBuilder sb, int level) {
		for (int i = 0; i < level; i++) sb.append("  ");
		return sb;
	}
}
