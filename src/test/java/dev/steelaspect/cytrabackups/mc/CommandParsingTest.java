package dev.steelaspect.cytrabackups.mc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import org.junit.jupiter.api.Test;

class CommandParsingTest {
	@Test
	void importPathAndComment() {
		assertArrayEquals(new String[]{"backups/old.zip", ""}, BackupCommands.splitPathAndComment("backups/old.zip"));
		assertArrayEquals(new String[]{"backups/old.zip", "season 1 final"}, BackupCommands.splitPathAndComment("backups/old.zip season 1 final"));
		assertArrayEquals(new String[]{"old worlds/s1.zip", "season 1"}, BackupCommands.splitPathAndComment("\"old worlds/s1.zip\" season 1"));
		assertArrayEquals(new String[]{"old worlds/s1.zip", ""}, BackupCommands.splitPathAndComment("  \"old worlds/s1.zip\"  "));
	}
}
