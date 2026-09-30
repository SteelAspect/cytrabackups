package dev.steelaspect.cytrabackups.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** All user-facing text lives in en_us.json: every key the code names must exist, and every text must format. */
class LangTest {
	private static final Pattern KEY = Pattern.compile("\"(cytrabackups\\.[a-z0-9_.]*[a-z0-9_])\"");

	@Test
	void everyKeyUsedInCodeExists() throws IOException {
		TreeSet<String> missing = new TreeSet<>();
		for (String dir : List.of("src/main/java", "src/client/java")) {
			try (Stream<Path> files = Files.walk(Path.of(dir))) {
				for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
					Matcher m = KEY.matcher(Files.readString(f));
					while (m.find()) {
						String key = m.group(1);
						if (!Lang.has(key) && !key.endsWith(".json")) missing.add(key + " (" + f.getFileName() + ")");
					}
				}
			}
		}
		assertTrue(missing.isEmpty(), "keys missing from en_us.json: " + missing);
	}

	@Test
	void everyTextFormatsAndIsPlain() {
		List<String> bad = new ArrayList<>();
		Object[] args = {"a", "b", "c", "d", "e", "f", "g", "h", "i", "j"};
		for (Map.Entry<String, String> e : Lang.all().entrySet()) {
			try {
				String.format(e.getValue(), args);
			} catch (RuntimeException ex) {
				bad.add(e.getKey() + ": " + ex);
			}
			// plain ASCII: no emoji, decorative symbols or typographic dashes
			if (!e.getValue().chars().allMatch(c -> c == '\n' || (c >= 0x20 && c < 0x7F))) bad.add(e.getKey() + ": non-ASCII text");
			if (e.getValue().contains("!") && e.getKey().startsWith("cytrabackups.") && !e.getKey().startsWith("cytrabackups.config.")) {
				bad.add(e.getKey() + ": exclamation mark");
			}
		}
		assertTrue(bad.isEmpty(), String.join("\n", bad));
	}

	@Test
	void formatsArgumentsAndPlurals() {
		assertEquals("Unknown setting x.y.", Lang.get("cytrabackups.config.problem.unknown", "x.y"));
		assertEquals("cytrabackups.gui.status.count.one", Lang.plural("cytrabackups.gui.status.count", 1));
		assertEquals("cytrabackups.gui.status.count", Lang.plural("cytrabackups.gui.status.count", 2));
		assertEquals("cytrabackups.gui.title", Lang.plural("cytrabackups.gui.title", 1), "keys without a singular form stay as they are");
		assertEquals("no.such.key", Lang.get("no.such.key"));
	}
}
