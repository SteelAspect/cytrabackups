package dev.steelaspect.cytrabackups.core;

/**
 * Keeps settings reloads and background off-site syncs apart: a reload never runs while a sync does (both use the
 * repository), and a reload requested during a sync runs as soon as the sync ends instead of being refused.
 * No new sync starts while a reload is waiting or running. A newer waiting request replaces an older one.
 */
public final class ReloadGate<T> {
	private boolean syncRunning;
	private boolean reloading;
	private T waiting;

	/** True if a sync may start now; the caller must call {@link #syncDone()} when it ends. */
	public synchronized boolean tryStartSync() {
		if (syncRunning || reloading || waiting != null) return false;
		syncRunning = true;
		return true;
	}

	/** Ends a sync. Returns a waiting reload the caller must run now (then {@link #reloadDone()}), or null. */
	public synchronized T syncDone() {
		syncRunning = false;
		return takeWaiting();
	}

	/** True if the reload may run now (then call {@link #reloadDone()}); false if it waits for the running sync or reload. */
	public synchronized boolean requestReload(T request) {
		if (!syncRunning && !reloading) {
			reloading = true;
			return true;
		}
		waiting = request;
		return false;
	}

	/** Ends a reload. Returns a reload that was requested meanwhile, which the caller must run now, or null. */
	public synchronized T reloadDone() {
		reloading = false;
		return syncRunning ? null : takeWaiting();
	}

	public synchronized boolean reloadWaiting() {
		return waiting != null;
	}

	private T takeWaiting() {
		T w = waiting;
		waiting = null;
		if (w != null) reloading = true;
		return w;
	}
}
