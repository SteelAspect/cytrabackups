package dev.steelaspect.cytrabackups.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ReloadGateTest {
	@Test
	void reloadWithoutSyncRunsRightAway() {
		ReloadGate<String> g = new ReloadGate<>();
		assertTrue(g.requestReload("a"));
		assertFalse(g.tryStartSync(), "no sync while reloading");
		assertNull(g.reloadDone());
		assertTrue(g.tryStartSync());
	}

	@Test
	void reloadDuringSyncWaitsAndRunsWhenItEnds() {
		ReloadGate<String> g = new ReloadGate<>();
		assertTrue(g.tryStartSync());
		assertFalse(g.tryStartSync(), "one sync at a time");
		assertFalse(g.requestReload("a"));
		assertFalse(g.requestReload("b"));
		assertTrue(g.reloadWaiting());
		assertEquals("b", g.syncDone(), "the newest request runs when the sync ends");
		assertFalse(g.tryStartSync(), "no sync while that reload runs");
		assertNull(g.reloadDone());
		assertFalse(g.reloadWaiting());
		assertTrue(g.tryStartSync());
		assertNull(g.syncDone());
	}

	@Test
	void reloadRequestedDuringAReloadRunsAfterIt() {
		ReloadGate<String> g = new ReloadGate<>();
		assertTrue(g.requestReload("a"));
		assertFalse(g.requestReload("b"));
		assertEquals("b", g.reloadDone());
		assertNull(g.reloadDone());
	}
}
