package dev.steelaspect.cytrabackups.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.steelaspect.cytrabackups.core.config.ConfigIO;
import dev.steelaspect.cytrabackups.core.config.CytraConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigTest {
	@TempDir
	Path dir;

	@Test
	void defaultsAreWrittenWithCommentsAndReadBack() throws Exception {
		Path f = dir.resolve("cytrabackups.json");
		CytraConfig c = ConfigIO.load(f);
		String text = Files.readString(f);
		assertTrue(text.contains("// Minutes between automatic backups"), "comments present");
		assertEquals(30, c.schedule.intervalMinutes);
		assertEquals(false, c.schedule.enabled, "automatic backups are off on a fresh install");
		// user edits with comments & trailing comma survive
		Files.writeString(f, text.replace("\"intervalMinutes\": 30", "\"intervalMinutes\": 45 /* edited */"));
		assertEquals(45, ConfigIO.load(f).schedule.intervalMinutes);
		// missing keys fall back to defaults
		Files.writeString(f, "{ // tiny\n \"schedule\": { \"enabled\": true }\n}");
		CytraConfig partial = ConfigIO.load(f);
		assertEquals(true, partial.schedule.enabled);
		assertEquals(30, partial.schedule.intervalMinutes);
		assertEquals(4, partial.permissions.level("restore"));
	}

	@Test
	void invalidValuesAreRejected() throws Exception {
		Path f = dir.resolve("bad.json");
		Files.writeString(f, "{ \"restore\": { \"applyMode\": \"sometimes\" }, \"schedule\": { \"timesOfDay\": [\"25:00\"] } }");
		var e = assertThrows(java.io.IOException.class, () -> ConfigIO.load(f));
		assertTrue(e.getMessage().contains("applyMode") && e.getMessage().contains("25:00"), e.getMessage());
	}
}
