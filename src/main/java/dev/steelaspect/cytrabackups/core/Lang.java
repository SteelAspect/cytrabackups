package dev.steelaspect.cytrabackups.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * English texts from assets/cytrabackups/lang/en_us.json. The server and the pure-Java core use these directly (chat
 * fallbacks, boss bar, Discord, config comments); clients with the mod translate the same keys themselves.
 */
public final class Lang {
	private static final Map<String, String> EN = load();

	private Lang() {
	}

	private static Map<String, String> load() {
		try (InputStream in = Lang.class.getResourceAsStream("/assets/cytrabackups/lang/en_us.json")) {
			if (in == null) throw new IllegalStateException("en_us.json is missing from the mod jar");
			JsonObject json = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
			Map<String, String> map = new HashMap<>();
			json.entrySet().forEach(e -> map.put(e.getKey(), e.getValue().getAsString()));
			return map;
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	public static boolean has(String key) {
		return EN.containsKey(key);
	}

	/** The unformatted English text, or the key itself if it is missing. */
	public static String raw(String key) {
		return EN.getOrDefault(key, key);
	}

	public static String get(String key, Object... args) {
		String text = raw(key);
		return args.length == 0 ? text : String.format(Locale.ROOT, text, args);
	}

	/** {@code key + ".one"} when {@code count} is 1 and that key exists, so messages can say "1 chunk" instead of "1 chunks". */
	public static String plural(String key, long count) {
		return count == 1 && has(key + ".one") ? key + ".one" : key;
	}

	public static Map<String, String> all() {
		return Map.copyOf(EN);
	}
}
