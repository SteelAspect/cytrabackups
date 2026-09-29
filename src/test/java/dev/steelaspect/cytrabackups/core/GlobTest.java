package dev.steelaspect.cytrabackups.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.steelaspect.cytrabackups.core.backup.Glob;
import dev.steelaspect.cytrabackups.core.backup.PathFilter;
import java.util.List;
import org.junit.jupiter.api.Test;

class GlobTest {
	@Test
	void patterns() {
		assertTrue(new Glob("session.lock").matches("session.lock"));
		assertTrue(new Glob("session.lock").matches("sub/dir/session.lock"), "no slash = any depth");
		assertTrue(new Glob("dynmap/**").matches("dynmap/web/tiles/a.png"));
		assertFalse(new Glob("dynmap/**").matches("region/r.0.0.mca"));
		assertTrue(new Glob("**/*.tmp").matches("a/b/c.tmp"));
		assertTrue(new Glob("**/*.tmp").matches("c.tmp"));
		assertTrue(new Glob("DIM?/region/*.mca").matches("DIM1/region/r.0.0.mca"));
		assertFalse(new Glob("region/*.mca").matches("region/sub/r.0.0.mca"));
		assertTrue(new Glob("dynmap/**").matchesWholeDirectory("dynmap"));
	}

	@Test
	void excludeWinsOverInclude() {
		PathFilter f = new PathFilter(List.of("region/**", "level.dat"), List.of("region/r.9.9.mca"));
		assertTrue(f.test("region/r.0.0.mca"));
		assertTrue(f.test("level.dat"));
		assertFalse(f.test("region/r.9.9.mca"));
		assertFalse(f.test("playerdata/x.dat"), "not included");
		assertTrue(new PathFilter(List.of(), List.of("logs/**")).skipDirectory("logs"));
	}
}
