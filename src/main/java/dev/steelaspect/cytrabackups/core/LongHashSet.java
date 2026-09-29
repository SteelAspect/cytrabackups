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
