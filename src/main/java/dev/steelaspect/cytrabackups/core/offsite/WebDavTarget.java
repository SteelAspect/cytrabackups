package dev.steelaspect.cytrabackups.core.offsite;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
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

	private void ensureParents(String key) throws IOException {
		String[] parts = key.split("/");
		StringBuilder dir = new StringBuilder();
		for (int i = 0; i < parts.length - 1; i++) {
			dir.append(parts[i]).append('/');
			String d = dir.toString();
			if (knownDirs.contains(d)) continue;
			HttpResponse<Void> res = send(req(d).method("MKCOL", HttpRequest.BodyPublishers.noBody()).build());
			// 201 created, 405 already exists
			if (res.statusCode() != 201 && res.statusCode() != 405 && res.statusCode() / 100 != 2) {
				throw new IOException("WebDAV MKCOL " + d + " failed: HTTP " + res.statusCode());
			}
			knownDirs.add(d);
		}
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
	public String describe() {
		return "webdav " + URI.create(base).getHost();
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
