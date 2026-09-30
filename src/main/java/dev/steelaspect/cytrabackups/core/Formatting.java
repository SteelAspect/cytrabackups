package dev.steelaspect.cytrabackups.core;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/** Human-readable sizes, durations and times for messages. */
public final class Formatting {
	private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
	private static final DateTimeFormatter DATE_TIME_SHORT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);
	private static final String[] UNITS = {"KB", "MB", "GB", "TB", "PB"};

	private Formatting() {
	}

	/** Binary sizes with the familiar short units: 950 B, 12 KB, 9.5 MB, 182 MB, 1.2 GB. */
	public static String bytes(long bytes) {
		if (bytes < 0) return "-" + bytes(-bytes);
		if (bytes < 1024) return bytes + " B";
		double v = bytes;
		int u = -1;
		do {
			v /= 1024;
			u++;
		} while (v >= 1024 && u < UNITS.length - 1);
		return String.format(Locale.ROOT, v >= 10 ? "%.0f %s" : "%.1f %s", v, UNITS[u]);
	}

	public static String duration(long millis) {
		Duration d = Duration.ofMillis(Math.max(0, millis));
		if (d.toHours() > 0) return String.format(Locale.ROOT, "%dh %02dm", d.toHours(), d.toMinutesPart());
		if (d.toMinutes() > 0) return String.format(Locale.ROOT, "%dm %02ds", d.toMinutes(), d.toSecondsPart());
		if (d.getSeconds() > 0) return String.format(Locale.ROOT, "%d.%ds", d.getSeconds(), d.toMillisPart() / 100);
		return d.toMillis() + "ms";
	}

	public static String dateTime(long epochMillis, ZoneId zone) {
		return DATE_TIME.format(Instant.ofEpochMilli(epochMillis).atZone(zone));
	}

	public static String dateTimeShort(long epochMillis, ZoneId zone) {
		return DATE_TIME_SHORT.format(Instant.ofEpochMilli(epochMillis).atZone(zone));
	}

	/** "just now", "5m ago", "2h ago", "3d ago". */
	public static String ago(long epochMillis, long now) {
		long s = Math.max(0, (now - epochMillis) / 1000);
		if (s < 60) return Lang.get("cytrabackups.time.just_now");
		if (s < 3600) return Lang.get("cytrabackups.time.minutes_ago", s / 60);
		if (s < 86400) return Lang.get("cytrabackups.time.hours_ago", s / 3600);
		return Lang.get("cytrabackups.time.days_ago", s / 86400);
	}

	public static String percent(double fraction) {
		return String.format(Locale.ROOT, "%.0f%%", fraction * 100);
	}

	/** 14 -> "14", 1.5 -> "1.5". */
	public static String number(double value) {
		return value == Math.rint(value) && Math.abs(value) < 1e15 ? String.valueOf((long) value) : String.format(Locale.ROOT, "%.1f", value);
	}
}
