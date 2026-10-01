package dev.steelaspect.cytrabackups.core.offsite;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.time.Duration;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** WebDAV target (Nextcloud, ownCloud, most NAS boxes) using PUT/MKCOL/HEAD/DELETE with basic auth. */
public final class WebDavTarget implements OffsiteTarget {
	private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NORMAL).build();
	private final String base;
	private final String auth;
	private final Set<String> knownDirs = ConcurrentHashMap.newKeySet();

	public WebDavTarget(String url, String username, String password) {
		if (url.isBlank()) throw new IllegalArgumentException("offsite.webdav.url is required");
		this.base = url.endsWith("/") ? url : url + "/";
		this.auth = username.isBlank() ? null : "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
	}

	private HttpRequest.Builder req(String key) {
		HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + S3Target.encodePath(key))).timeout(Duration.ofMinutes(10));
		if (auth != null) b.header("Authorization", auth);
		return b;
	}

	@Override
	public void upload(String key, Path file) throws IOException {
		ensureParents(key);
		HttpResponse<Void> res = send(req(key).PUT(HttpRequest.BodyPublishers.ofFile(file)).build());
		if (res.statusCode() / 100 != 2) throw new IOException("WebDAV PUT " + key + " failed: HTTP " + res.statusCode());
	}

	private final ConcurrentHashMap<String, Object> dirLocks = new ConcurrentHashMap<>();

	private volatile boolean baseChecked;

	/** Creates the configured base folder itself (one level) if it does not exist yet. */
	private void ensureBase() throws IOException {
		if (baseChecked) return;
		synchronized (dirLocks.computeIfAbsent("", x -> new Object())) {
			if (baseChecked) return;
			if (!collectionExists("")) {
				HttpResponse<Void> res = send(req("").method("MKCOL", HttpRequest.BodyPublishers.noBody()).build());
				if (res.statusCode() != 201 && res.statusCode() != 405 && !collectionExists("")) {
					throw new IOException("WebDAV base folder " + base + " does not exist and could not be created: HTTP " + res.statusCode());
				}
			}
			baseChecked = true;
		}
	}

	private void ensureParents(String key) throws IOException {
		ensureBase();
		String[] parts = key.split("/");
		StringBuilder dir = new StringBuilder();
		for (int i = 0; i < parts.length - 1; i++) {
			dir.append(parts[i]).append('/');
			String d = dir.toString();
			if (knownDirs.contains(d)) continue;
			// parallel uploads share parent folders: create each folder once, and treat "someone else just made it" as success
			synchronized (dirLocks.computeIfAbsent(d, x -> new Object())) {
				if (knownDirs.contains(d)) continue;
				HttpResponse<Void> res = send(req(d).method("MKCOL", HttpRequest.BodyPublishers.noBody()).build());
				int code = res.statusCode();
				// 201 created, 405 already exists; 409 can mean a concurrent create or a missing parent, so re-check
				if (code != 201 && code != 405 && code / 100 != 2 && !collectionExists(d)) {
					throw new IOException("WebDAV MKCOL " + d + " failed: HTTP " + code);
				}
				knownDirs.add(d);
			}
		}
	}

	private boolean collectionExists(String dir) throws IOException {
		HttpResponse<Void> res = send(req(dir).header("Depth", "0").method("PROPFIND", HttpRequest.BodyPublishers.noBody()).build());
		return res.statusCode() == 207 || res.statusCode() == 200;
	}

	@Override
	public boolean exists(String key) throws IOException {
		HttpResponse<Void> res = send(req(key).method("HEAD", HttpRequest.BodyPublishers.noBody()).build());
		return res.statusCode() / 100 == 2;
	}

	@Override
	public void delete(String key) throws IOException {
		HttpResponse<Void> res = send(req(key).DELETE().build());
		if (res.statusCode() / 100 != 2 && res.statusCode() != 404) throw new IOException("WebDAV DELETE " + key + " failed: HTTP " + res.statusCode());
	}

	@Override
	public void download(String key, Path file) throws IOException {
		Path tmp = file.resolveSibling(file.getFileName() + ".part");
		HttpResponse<Path> res;
		try {
			res = client.send(req(key).GET().build(), HttpResponse.BodyHandlers.ofFile(tmp));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("interrupted", e);
		}
		if (res.statusCode() == 404) {
			Files.deleteIfExists(tmp);
			throw new NoSuchFileException(key);
		}
		if (res.statusCode() / 100 != 2) {
			Files.deleteIfExists(tmp);
			throw new IOException("WebDAV GET " + key + " failed: HTTP " + res.statusCode());
		}
		Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
	}

	@Override
	public List<String> list(String prefix) throws IOException {
		List<String> out = new ArrayList<>();
		String dir = prefix.isEmpty() || prefix.endsWith("/") ? prefix : prefix + "/";
		walk(dir, out);
		return out;
	}

	private static final Pattern HREF = Pattern.compile("<(?:[A-Za-z0-9_]+:)?href>([^<]*)</(?:[A-Za-z0-9_]+:)?href>");

	/** One PROPFIND per folder; hrefs come back as server paths, which are mapped back to keys under the base URL. */
	private void walk(String dir, List<String> out) throws IOException {
		HttpResponse<String> res;
		try {
			res = client.send(req(dir).header("Depth", "1").method("PROPFIND", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("interrupted", e);
		}
		if (res.statusCode() == 404) return;
		if (res.statusCode() != 207 && res.statusCode() != 200) throw new IOException("WebDAV PROPFIND " + dir + " failed: HTTP " + res.statusCode());
		String basePath = URI.create(base).getRawPath();
		Matcher m = HREF.matcher(res.body());
		List<String> subdirs = new ArrayList<>();
		while (m.find()) {
			String href = m.group(1).trim();
			String path = href.startsWith("http") ? URI.create(href).getRawPath() : href;
			if (!path.startsWith(basePath)) continue;
			String key = java.net.URLDecoder.decode(path.substring(basePath.length()).replace("+", "%2B"), StandardCharsets.UTF_8);
			if (key.equals(dir) || key.isEmpty()) continue;
			if (key.endsWith("/")) subdirs.add(key);
			else out.add(key);
		}
		for (String sub : subdirs) walk(sub, out);
	}

	@Override
	public String describe() {
		return "webdav " + URI.create(base).getHost();
	}

	@Override
	public String id() {
		return "webdav|" + base;
	}

	private HttpResponse<Void> send(HttpRequest r) throws IOException {
		try {
			return client.send(r, HttpResponse.BodyHandlers.discarding());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("interrupted", e);
		}
	}
}
