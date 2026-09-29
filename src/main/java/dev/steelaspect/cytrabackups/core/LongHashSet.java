package dev.steelaspect.cytrabackups.core;

/** Minimal open-addressing set of longs (~16 bytes per entry) for marking millions of blob hashes during GC. */
public final class LongHashSet {
	private static final long EMPTY = 0L;
	private long[] table;
	private int size;
	private boolean containsZero;

	public LongHashSet(int expected) {
		int cap = Integer.highestOneBit(Math.max(16, expected * 2 - 1)) << 1;
		table = new long[cap];
	}

	public LongHashSet() {
		this(1024);
	}

	private static int mix(long v) {
		v ^= v >>> 33;
		v *= 0xff51afd7ed558ccdL;
		v ^= v >>> 33;
		return (int) v;
	}

	public boolean add(long v) {
		if (v == EMPTY) {
			boolean added = !containsZero;
			containsZero = true;
			if (added) size++;
			return added;
		}
		if ((size + 1) * 2 > table.length) rehash(table.length * 2);
		int mask = table.length - 1;
		int i = mix(v) & mask;
		while (true) {
			long cur = table[i];
			if (cur == EMPTY) {
				table[i] = v;
				size++;
				return true;
			}
			if (cur == v) return false;
			i = (i + 1) & mask;
		}
	}

	public boolean contains(long v) {
		if (v == EMPTY) return containsZero;
		int mask = table.length - 1;
		int i = mix(v) & mask;
		while (true) {
			long cur = table[i];
			if (cur == EMPTY) return false;
			if (cur == v) return true;
			i = (i + 1) & mask;
		}
	}

	public int size() {
		return size;
	}

	/** Removes a value using backward-shift deletion (keeps linear probing chains intact). */
	public boolean remove(long v) {
		if (v == EMPTY) {
			boolean had = containsZero;
			if (had) size--;
			containsZero = false;
			return had;
		}
		int mask = table.length - 1;
		int i = mix(v) & mask;
		while (true) {
			long cur = table[i];
			if (cur == EMPTY) return false;
			if (cur == v) break;
			i = (i + 1) & mask;
		}
		int hole = i;
		int j = hole;
		while (true) {
			j = (j + 1) & mask;
			long cur = table[j];
			if (cur == EMPTY) break;
			int home = mix(cur) & mask;
			// move cur into the hole if its home slot is not in (hole, j]
			boolean between = hole <= j ? (home > hole && home <= j) : (home > hole || home <= j);
			if (!between) {
				table[hole] = cur;
				hole = j;
			}
		}
		table[hole] = EMPTY;
		size--;
		return true;
	}

	public void forEach(java.util.function.LongConsumer consumer) {
		if (containsZero) consumer.accept(EMPTY);
		for (long v : table) if (v != EMPTY) consumer.accept(v);
	}

	private void rehash(int newCap) {
		long[] old = table;
		table = new long[newCap];
		size = containsZero ? 1 : 0;
		for (long v : old) if (v != EMPTY) add(v);
	}

	@Override
	public String toString() {
		return "LongHashSet[size=" + size + ", capacity=" + table.length + "]";
	}
}
