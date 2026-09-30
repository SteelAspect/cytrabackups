package dev.steelaspect.cytrabackups.core.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.steelaspect.cytrabackups.core.Lang;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Flat, typed view of {@link CytraConfig} ("schedule.intervalMinutes", "permissions.defaultLevels.restore", ...) used by
 * the in-game settings editor, plus applying edited values back onto a copy of the config with validation. Secrets are
 * never exposed: the editor only learns whether one is set.
 */
public final class ConfigSchema {
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

	private ConfigSchema() {
	}

	public enum Type {
		BOOL, INT, LONG, DOUBLE, STRING, CHOICE, LIST, SECRET
	}

	private static final String LEVELS = "permissions.defaultLevels.";

	/** One setting as shown in the editor. For {@link Type#SECRET} the value is always empty and {@code set} says whether one is stored. */
	public record Entry(String path, Type type, String value, List<String> choices, boolean set) {
		public String section() {
			int dot = path.indexOf('.');
			return dot < 0 ? "general" : path.substring(0, dot);
		}

		/** Lang key of the help text. All permission levels share one key, with the node name as argument. */
		public String helpKey() {
			return path.startsWith(LEVELS) ? "cytrabackups.config.permissions.level" : "cytrabackups.config." + path;
		}

		public String nameKey() {
			return helpKey() + ".name";
		}

		/** Argument for {@link #helpKey()} and {@link #nameKey()}: the permission node for permission levels, otherwise empty. */
		public String keyArgument() {
			return path.startsWith(LEVELS) ? path.substring(LEVELS.length()) : "";
		}
	}

	/** Result of applying edits: the new config when {@code problems} is empty. */
	public record Result(CytraConfig config, List<String> problems) {
		public boolean ok() {
			return problems.isEmpty();
		}
	}

	/** A leaf setting with accessors on one config instance. */
	record Leaf(String path, Class<?> javaType, Field field, Supplier<Object> get, Consumer<Object> set) {
		Type type() {
			if (field.isAnnotationPresent(Secret.class)) return Type.SECRET;
			if (field.isAnnotationPresent(Choices.class) && javaType == String.class) return Type.CHOICE;
			if (javaType == boolean.class) return Type.BOOL;
			if (javaType == int.class || javaType == Integer.class) return Type.INT;
			if (javaType == long.class) return Type.LONG;
			if (javaType == double.class) return Type.DOUBLE;
			if (javaType == List.class) return Type.LIST;
			return Type.STRING;
		}

		List<String> choices() {
			Choices c = field.getAnnotation(Choices.class);
			return c == null ? List.of() : List.of(c.value());
		}
	}

	static List<Leaf> leaves(CytraConfig cfg) {
		List<Leaf> out = new ArrayList<>();
		collect(cfg, "", out);
		return out;
	}

	private static void collect(Object obj, String prefix, List<Leaf> out) {
		for (Field f : obj.getClass().getDeclaredFields()) {
			int mod = f.getModifiers();
			if (Modifier.isStatic(mod) || Modifier.isTransient(mod)) continue;
			f.setAccessible(true);
			String path = prefix + f.getName();
			Class<?> t = f.getType();
			Object value = read(f, obj);
			if (t.isPrimitive() || t == String.class || t == List.class) {
				out.add(new Leaf(path, t, f, () -> read(f, obj), v -> write(f, obj, v)));
			} else if (value instanceof Map<?, ?> raw) {
				@SuppressWarnings("unchecked")
				Map<String, Integer> map = (Map<String, Integer>) raw;
				for (String key : new ArrayList<>(map.keySet())) {
					out.add(new Leaf(path + "." + key, Integer.class, f, () -> map.get(key), v -> map.put(key, (Integer) v)));
				}
			} else if (value != null) {
				collect(value, path + ".", out);
			}
		}
	}

	private static Object read(Field f, Object obj) {
		try {
			return f.get(obj);
		} catch (IllegalAccessException e) {
			throw new IllegalStateException(e);
		}
	}

	private static void write(Field f, Object obj, Object value) {
		try {
			f.set(obj, value);
		} catch (IllegalAccessException e) {
			throw new IllegalStateException(e);
		}
	}

	/** Every setting with its current value (secrets masked). */
	public static List<Entry> describe(CytraConfig cfg) {
		List<Entry> out = new ArrayList<>();
		for (Leaf l : leaves(cfg)) {
			Object v = l.get().get();
			Type type = l.type();
			String text = switch (type) {
				case SECRET -> "";
				case LIST -> joinList(listOf(v));
				case DOUBLE -> formatDouble((Double) v);
				default -> String.valueOf(v);
			};
			boolean set = type == Type.SECRET && v != null && !v.toString().isEmpty();
			out.add(new Entry(l.path(), type, text, l.choices(), set));
		}
		return out;
	}

	private static String formatDouble(double d) {
		return d == Math.rint(d) && Math.abs(d) < 1e15 ? String.valueOf((long) d) : String.valueOf(d);
	}

	private static List<String> listOf(Object v) {
		List<String> out = new ArrayList<>();
		if (v instanceof List<?> l) for (Object o : l) out.add(String.valueOf(o));
		return out;
	}

	/** Entries are separated by commas in the editor. */
	public static String joinList(List<String> list) {
		return String.join(", ", list);
	}

	public static List<String> splitList(String text) {
		List<String> out = new ArrayList<>();
		for (String part : text.split(",")) {
			String t = part.trim();
			if (!t.isEmpty()) out.add(t);
		}
		return out;
	}

	/** Deep copy through JSON, so edits never touch the live config until they are valid. */
	public static CytraConfig copy(CytraConfig cfg) {
		return GSON.fromJson(GSON.toJson(cfg), CytraConfig.class);
	}

	/** Applies {@code path -> text} edits to a copy of {@code base}, then normalizes and validates the result. */
	public static Result apply(CytraConfig base, Map<String, String> changes) {
		CytraConfig copy = copy(base);
		Map<String, Leaf> byPath = new LinkedHashMap<>();
		for (Leaf l : leaves(copy)) byPath.put(l.path(), l);
		List<String> problems = new ArrayList<>();
		for (Map.Entry<String, String> e : changes.entrySet()) {
			Leaf leaf = byPath.get(e.getKey());
			if (leaf == null) {
				problems.add(Lang.get("cytrabackups.config.problem.unknown", e.getKey()));
				continue;
			}
			String text = e.getValue() == null ? "" : e.getValue();
			try {
				leaf.set().accept(parse(leaf, text));
			} catch (IllegalArgumentException ex) {
				problems.add(Lang.get(ex.getMessage(), e.getKey(), text)); // the message is the lang key of the problem
			}
		}
		if (problems.isEmpty()) {
			normalize(copy);
			problems.addAll(ConfigIO.validate(copy));
		}
		return new Result(copy, problems);
	}

	private static Object parse(Leaf leaf, String text) {
		String t = text.trim();
		return switch (leaf.type()) {
			case BOOL -> {
				if (t.equalsIgnoreCase("true") || t.equalsIgnoreCase("on")) yield true;
				if (t.equalsIgnoreCase("false") || t.equalsIgnoreCase("off")) yield false;
				throw new IllegalArgumentException("cytrabackups.config.problem.not_bool");
			}
			case INT -> {
				try {
					yield Integer.parseInt(t);
				} catch (NumberFormatException ex) {
					throw new IllegalArgumentException("cytrabackups.config.problem.not_whole");
				}
			}
			case LONG -> {
				try {
					yield Long.parseLong(t);
				} catch (NumberFormatException ex) {
					throw new IllegalArgumentException("cytrabackups.config.problem.not_whole");
				}
			}
			case DOUBLE -> {
				try {
					double d = Double.parseDouble(t);
					if (!Double.isFinite(d)) throw new NumberFormatException();
					yield d;
				} catch (NumberFormatException ex) {
					throw new IllegalArgumentException("cytrabackups.config.problem.not_number");
				}
			}
			case LIST -> splitList(text);
			case SECRET -> text; // exact, not trimmed
			case CHOICE, STRING -> t;
		};
	}

	/** Canonical spelling for choice settings ("Startup" -> "startup"), so code comparing them stays simple. */
	public static void normalize(CytraConfig cfg) {
		for (Leaf l : leaves(cfg)) {
			if (l.type() != Type.CHOICE) continue;
			Object v = l.get().get();
			if (v == null) continue;
			for (String c : l.choices()) {
				if (c.equalsIgnoreCase(v.toString().trim())) {
					l.set().accept(c);
					break;
				}
			}
		}
	}

	/** Problems found by walking every setting: unknown choices and negative numbers. */
	static List<String> genericProblems(CytraConfig cfg) {
		List<String> p = new ArrayList<>();
		for (Leaf l : leaves(cfg)) {
			Object v = l.get().get();
			switch (l.type()) {
				case CHOICE -> {
					if (v == null || !l.choices().contains(v.toString().toLowerCase(Locale.ROOT))) {
						String choices = Lang.get("cytrabackups.list.or", String.join(", ", l.choices().subList(0, l.choices().size() - 1)), l.choices().getLast());
						p.add(Lang.get("cytrabackups.config.problem.choice", l.path(), choices, v));
					}
				}
				case INT, LONG, DOUBLE -> {
					if (v instanceof Number n && n.doubleValue() < 0) p.add(Lang.get("cytrabackups.config.problem.negative", l.path()));
				}
				default -> {
				}
			}
		}
		return p;
	}
}
