package dev.steelaspect.cytrabackups.core.backup;

import java.util.List;
import java.util.function.Predicate;

/** Include/exclude filter over relative world paths. Exclusions win over inclusions. */
public final class PathFilter implements Predicate<String> {
	public static final PathFilter ALL = new PathFilter(List.of(), List.of());

	private final List<Glob> include;
	private final List<Glob> exclude;

	public PathFilter(List<String> include, List<String> exclude) {
		this.include = include.stream().filter(s -> !s.isBlank()).map(Glob::new).toList();
		this.exclude = exclude.stream().filter(s -> !s.isBlank()).map(Glob::new).toList();
	}

	@Override
	public boolean test(String path) {
		for (Glob g : exclude) if (g.matches(path)) return false;
		if (include.isEmpty()) return true;
		for (Glob g : include) if (g.matches(path)) return true;
		return false;
	}

	public boolean skipDirectory(String relativeDir) {
		for (Glob g : exclude) if (g.matchesWholeDirectory(relativeDir) || g.matches(relativeDir)) return true;
		return false;
	}
}
