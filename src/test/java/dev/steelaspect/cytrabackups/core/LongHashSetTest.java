package dev.steelaspect.cytrabackups.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LongHashSetTest {
	@Test
	void behavesLikeASet() {
		LongHashSet s = new LongHashSet(4);
		Set<Long> ref = new HashSet<>();
		Random r = new Random(9);
		for (int i = 0; i < 200_000; i++) {
			long v = r.nextInt(50_000) - 25_000L; // includes 0 and collisions
			if (r.nextInt(3) == 0) {
				assertEquals(ref.remove(v), s.remove(v));
			} else {
				assertEquals(ref.add(v), s.add(v));
			}
		}
		assertEquals(ref.size(), s.size());
		for (long v = -25_000; v < 25_000; v++) assertEquals(ref.contains(v), s.contains(v), "value " + v);
		assertFalse(s.contains(Long.MAX_VALUE));
		long[] count = {0};
		s.forEach(v -> count[0]++);
		assertEquals(ref.size(), count[0]);
		assertTrue(s.size() > 0);
	}
}
