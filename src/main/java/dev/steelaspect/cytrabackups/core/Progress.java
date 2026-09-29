package dev.steelaspect.cytrabackups.core;

import java.util.concurrent.atomic.AtomicLong;

/** Thread-safe progress counters for a running job, read by the UI once per second. */
public final class Progress {
	private volatile String phase = "Starting";
	private volatile String detail = "";
	private final AtomicLong done = new AtomicLong();
	private final AtomicLong total = new AtomicLong();
	private final AtomicLong bytesDone = new AtomicLong();
	private final AtomicLong bytesTotal = new AtomicLong();
	private final long startedAt = System.currentTimeMillis();

	public void phase(String phase) {
		this.phase = phase;
		this.detail = "";
		done.set(0);
		total.set(0);
		bytesDone.set(0);
		bytesTotal.set(0);
	}

	public void detail(String detail) {
		this.detail = detail;
	}

	public void addTotal(long items, long bytes) {
		total.addAndGet(items);
		bytesTotal.addAndGet(bytes);
	}

	public void addDone(long items, long bytes) {
		done.addAndGet(items);
		bytesDone.addAndGet(bytes);
	}

	public String phase() {
		return phase;
	}

	public String detail() {
		return detail;
	}

	public long done() {
		return done.get();
	}

	public long total() {
		return total.get();
	}

	public long bytesDone() {
		return bytesDone.get();
	}

	public long bytesTotal() {
		return bytesTotal.get();
	}

	public long startedAt() {
		return startedAt;
	}

	/** Fraction in [0,1]; prefers byte counts when known. */
	public float fraction() {
		long bt = bytesTotal.get();
		if (bt > 0) return (float) Math.min(1.0, (double) bytesDone.get() / bt);
		long t = total.get();
		if (t > 0) return (float) Math.min(1.0, (double) done.get() / t);
		return 0f;
	}
}
