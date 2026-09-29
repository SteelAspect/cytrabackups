package dev.steelaspect.cytrabackups.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

public final class Json {
	public static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	private Json() {
	}

	public static <T> T read(Path file, Class<T> type) throws IOException {
		try {
			String s = Files.readString(file, StandardCharsets.UTF_8);
			T value = GSON.fromJson(s, type);
			if (value == null) throw new IOException("Empty JSON file " + file);
			return value;
		} catch (JsonParseException e) {
			throw new IOException("Invalid JSON in " + file + ": " + e.getMessage(), e);
		}
	}

	public static <T> T readOrNull(Path file, Class<T> type) throws IOException {
		try {
			return read(file, type);
		} catch (NoSuchFileException e) {
			return null;
		}
	}

	public static void write(Path file, Object value) throws IOException {
		FileUtil.writeAtomic(file, GSON.toJson(value));
	}
}
