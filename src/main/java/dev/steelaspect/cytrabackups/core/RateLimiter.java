package dev.steelaspect.cytrabackups.core;

/** Simple token bucket limiting read throughput so backups don't starve the server's own disk I/O. */
public final class RateLimiter {
	public static final RateLimiter UNLIMITED = new RateLimiter(0);

	private final long bytesPerSecond;
	private long available;
	private long lastRefill = System.nanoTime();

	public RateLimiter(long bytesPerSecond) {
		this.bytesPerSecond = bytesPerSecond;
		this.available = bytesPerSecond;
	}

	public void acquire(long bytes) {
		if (bytesPerSecond <= 0 || bytes <= 0) return;
		long sleepNanos;
		synchronized (this) {
			long now = System.nanoTime();
			available = Math.min(bytesPerSecond, available + (now - lastRefill) * bytesPerSecond / 1_000_000_000L);
			lastRefill = now;
			available -= bytes;
			sleepNanos = available >= 0 ? 0 : (-available) * 1_000_000_000L / bytesPerSecond;
		}
		if (sleepNanos > 0) {
			try {
				Thread.sleep(sleepNanos / 1_000_000L, (int) (sleepNanos % 1_000_000L));
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
	}
}
