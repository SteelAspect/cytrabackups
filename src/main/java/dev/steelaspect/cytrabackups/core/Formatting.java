package dev.steelaspect.cytrabackups.core;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public final class Formatting {
	private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT);

	private Formatting() {
	}

	public static String bytes(long bytes) {
		if (bytes < 0) return "-" + bytes(-bytes);
		if (bytes < 1024) return bytes + " B";
		String[] units = {"KiB", "MiB", "GiB", "TiB", "PiB"};
		double v = bytes;
		int u = -1;
		do {
			v /= 1024;
			u++;
		} while (v >= 1024 && u < units.length - 1);
		return String.format(Locale.ROOT, v >= 100 ? "%.0f %s" : v >= 10 ? "%.1f %s" : "%.2f %s", v, units[u]);
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

	public static String ago(long epochMillis, long now) {
		long s = Math.max(0, (now - epochMillis) / 1000);
		if (s < 60) return s + "s ago";
		if (s < 3600) return (s / 60) + "m ago";
		if (s < 86400) return (s / 3600) + "h ago";
		return (s / 86400) + "d ago";
	}

	public static String percent(double fraction) {
		return String.format(Locale.ROOT, "%.1f%%", fraction * 100);
	}
}
