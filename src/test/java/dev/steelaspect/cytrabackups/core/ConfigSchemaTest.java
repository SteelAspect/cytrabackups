package dev.steelaspect.cytrabackups.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.steelaspect.cytrabackups.core.config.ConfigIO;
import dev.steelaspect.cytrabackups.core.config.ConfigSchema;
import dev.steelaspect.cytrabackups.core.config.CytraConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The in-game settings editor: what it is shown, and how edits are applied and validated. */
class ConfigSchemaTest {
	@TempDir
	Path dir;

	private static Map<String, ConfigSchema.Entry> byPath(CytraConfig c) {
		return ConfigSchema.describe(c).stream().collect(Collectors.toMap(ConfigSchema.Entry::path, Function.identity()));
	}

	@Test
	void describesEverySettingWithTypeAndHelp() {
		CytraConfig c = new CytraConfig();
		List<ConfigSchema.Entry> entries = ConfigSchema.describe(c);
		Map<String, ConfigSchema.Entry> m = byPath(c);
		assertEquals(ConfigSchema.Type.INT, m.get("schedule.intervalMinutes").type());
		assertEquals("30", m.get("schedule.intervalMinutes").value());
		assertEquals(ConfigSchema.Type.BOOL, m.get("schedule.enabled").type());
		assertEquals(ConfigSchema.Type.CHOICE, m.get("restore.applyMode").type());
		assertEquals(List.of("startup", "shutdown"), m.get("restore.applyMode").choices());
		assertEquals(ConfigSchema.Type.LIST, m.get("exclude").type());
		assertTrue(m.get("exclude").value().startsWith("session.lock, logs/**"), m.get("exclude").value());
		assertEquals(ConfigSchema.Type.DOUBLE, m.get("prune.maxAgeDays").type());
		assertEquals("0", m.get("prune.maxAgeDays").value());
		assertEquals(ConfigSchema.Type.INT, m.get("permissions.defaultLevels.restore").type());
		assertEquals("general", m.get("storagePath").section());
		assertEquals("offsite", m.get("offsite.s3.bucket").section());
		List<String> undocumented = entries.stream().filter(e -> e.comment().isBlank()).map(ConfigSchema.Entry::path).toList();
		assertTrue(undocumented.isEmpty(), "every setting needs a comment for its GUI tooltip: " + undocumented);
	}

	@Test
	void secretsAreNeverSent() {
		CytraConfig c = new CytraConfig();
		c.offsite.s3.secretKey = "hunter2";
		c.discord.webhookUrl = "https://discord.com/api/webhooks/1/abc";
		Map<String, ConfigSchema.Entry> m = byPath(c);
		for (String p : List.of("offsite.s3.secretKey", "discord.webhookUrl", "offsite.sftp.password", "offsite.sftp.privateKeyPassphrase",
			"offsite.webdav.password")) {
			assertEquals(ConfigSchema.Type.SECRET, m.get(p).type(), p);
			assertEquals("", m.get(p).value(), p);
		}
		assertTrue(m.get("offsite.s3.secretKey").set());
		assertFalse(m.get("offsite.sftp.password").set());
		assertTrue(ConfigSchema.describe(c).stream().noneMatch(e -> e.value().contains("hunter2") || e.value().contains("webhooks")));
	}

	@Test
	void appliesEditsToACopyAndNormalizes() {
		CytraConfig base = new CytraConfig();
		base.offsite.s3.secretKey = "old";
		ConfigSchema.Result r = ConfigSchema.apply(base, Map.of(
			"schedule.intervalMinutes", "45",
			"schedule.enabled", "false",
			"schedule.timesOfDay", "04:00, 16:30",
			"restore.applyMode", "Shutdown",
			"prune.maxTotalSizeGiB", "12.5",
			"permissions.defaultLevels.list", "0",
			"offsite.s3.secretKey", ""));
		assertTrue(r.ok(), r.problems().toString());
		CytraConfig c = r.config();
		assertEquals(45, c.schedule.intervalMinutes);
		assertFalse(c.schedule.enabled);
		assertEquals(List.of("04:00", "16:30"), c.schedule.timesOfDay);
		assertEquals("shutdown", c.restore.applyMode);
		assertEquals(12.5, c.prune.maxTotalSizeGiB);
		assertEquals(0, c.permissions.level("list"));
		assertEquals("", c.offsite.s3.secretKey, "an empty secret edit clears it");
		assertEquals(30, base.schedule.intervalMinutes, "the live config is untouched");
		assertEquals("old", base.offsite.s3.secretKey);
	}

	@Test
	void rejectsBadValuesWithReadableProblems() {
		CytraConfig base = new CytraConfig();
		ConfigSchema.Result r = ConfigSchema.apply(base, Map.of("schedule.intervalMinutes", "soon", "nope.nothing", "1"));
		assertFalse(r.ok());
		assertTrue(r.problems().stream().anyMatch(p -> p.contains("schedule.intervalMinutes") && p.contains("whole number")), r.problems().toString());
		assertTrue(r.problems().stream().anyMatch(p -> p.contains("unknown setting nope.nothing")), r.problems().toString());

		r = ConfigSchema.apply(base, Map.of("schedule.intervalMinutes", "-5", "offsite.sftp.port", "70000", "compression.algorithm", "lz4",
			"permissions.defaultLevels.restore", "9", "schedule.timesOfDay", "25:00"));
		String all = String.join("\n", r.problems());
		assertTrue(all.contains("schedule.intervalMinutes must not be negative"), all);
		assertTrue(all.contains("offsite.sftp.port must be 1-65535"), all);
		assertTrue(all.contains("compression.algorithm must be zstd, deflate or none"), all);
		assertTrue(all.contains("permissions.defaultLevels.restore must be 0-4"), all);
		assertTrue(all.contains("25:00"), all);
	}

	@Test
	void savedEditsSurviveAReload() throws Exception {
		Path f = dir.resolve("cytrabackups.json");
		CytraConfig base = ConfigIO.load(f);
		ConfigSchema.Result r = ConfigSchema.apply(base, Map.of("schedule.intervalMinutes", "15", "offsite.webdav.password", "p@ss word",
			"exclude", "logs/**, *.tmp"));
		assertTrue(r.ok(), r.problems().toString());
		ConfigIO.save(f, r.config());
		CytraConfig loaded = ConfigIO.load(f);
		assertEquals(15, loaded.schedule.intervalMinutes);
		assertEquals("p@ss word", loaded.offsite.webdav.password);
		assertEquals(List.of("logs/**", "*.tmp"), loaded.exclude);
		assertTrue(Files.readString(f).contains("// Minutes between automatic backups"), "comments are kept");
	}
}
