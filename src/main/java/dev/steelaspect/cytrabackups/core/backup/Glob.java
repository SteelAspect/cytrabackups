package dev.steelaspect.cytrabackups.core.backup;

import java.util.regex.Pattern;

/**
 * gitignore-style glob for forward-slash relative paths. {@code *} matches within a path segment,
 * {@code **} across segments, {@code ?} one character. A pattern without '/' matches the file name at any depth.
 */
public final class Glob {
	private final String pattern;
	private final Pattern regex;
	private final Pattern dirRegex;

	public Glob(String pattern) {
		String p = pattern.trim().replace('\\', '/');
		while (p.startsWith("./")) p = p.substring(2);
		if (p.startsWith("/")) p = p.substring(1);
		this.pattern = p;
		boolean anyDepth = !p.contains("/");
		String body = toRegex(p);
		this.regex = Pattern.compile(anyDepth ? "(?:.*/)?" + body : body);
		if (p.endsWith("/**")) {
			String dir = toRegex(p.substring(0, p.length() - 3));
			this.dirRegex = Pattern.compile(dir);
		} else {
			this.dirRegex = null;
		}
	}

	public String pattern() {
		return pattern;
	}

	public boolean matches(String relativePath) {
		return regex.matcher(relativePath).matches();
	}

	/** True if every file under this directory would match (lets the walker skip the whole subtree). */
	public boolean matchesWholeDirectory(String relativeDir) {
		return dirRegex != null && dirRegex.matcher(relativeDir).matches();
	}

	private static String toRegex(String glob) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < glob.length(); i++) {
			char c = glob.charAt(i);
			if (c == '*') {
				if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
					boolean slashAfter = i + 2 < glob.length() && glob.charAt(i + 2) == '/';
					sb.append(slashAfter ? "(?:.*/)?" : ".*");
					i += slashAfter ? 2 : 1;
				} else {
					sb.append("[^/]*");
				}
			} else if (c == '?') {
				sb.append("[^/]");
			} else if ("\\.[]{}()+-^$|".indexOf(c) >= 0) {
				sb.append('\\').append(c);
			} else {
				sb.append(c);
			}
		}
		return sb.toString();
	}

	@Override
	public String toString() {
		return pattern;
	}
}
