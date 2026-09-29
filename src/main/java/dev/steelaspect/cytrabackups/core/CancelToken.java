package dev.steelaspect.cytrabackups.core;

import java.util.concurrent.CancellationException;

/** Cooperative cancellation flag shared between a job and its workers. */
public final class CancelToken {
	public static final CancelToken NONE = new CancelToken();

	private volatile boolean cancelled;
	private volatile String reason = "cancelled";

	public void cancel(String reason) {
		if (this == NONE) return;
		this.reason = reason;
		this.cancelled = true;
	}

	public boolean isCancelled() {
		return cancelled;
	}

	public void check() {
		if (cancelled) throw new CancellationException(reason);
	}
}
