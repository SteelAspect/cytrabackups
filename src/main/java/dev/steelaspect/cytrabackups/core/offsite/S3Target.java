package dev.steelaspect.cytrabackups.core.offsite;

import dev.steelaspect.cytrabackups.core.Hash;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Minimal S3-compatible client (PUT/HEAD/DELETE) with AWS Signature Version 4, using only the JDK. */
public final class S3Target implements OffsiteTarget {
	private static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");
	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

	private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
	private final URI endpoint;
	private final String region;
	private final String bucket;
	private final String prefix;
	private final String accessKey;
	private final String secretKey;
	private final boolean pathStyle;

	public S3Target(String endpoint, String region, String bucket, String prefix, String accessKey, String secretKey, boolean pathStyle) {
		if (endpoint.isBlank() || bucket.isBlank()) throw new IllegalArgumentException("offsite.s3.endpoint and bucket are required");
		this.endpoint = URI.create(endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint);
		this.region = region.isBlank() ? "us-east-1" : region;
		this.bucket = bucket;
		String p = prefix == null ? "" : prefix.replace('\\', '/');
		while (p.startsWith("/")) p = p.substring(1);
		if (!p.isEmpty() && !p.endsWith("/")) p += "/";
		this.prefix = p;
		this.accessKey = accessKey;
		this.secretKey = secretKey;
		this.pathStyle = pathStyle;
	}

	URI uriFor(String key) {
		String objectPath = encodePath(prefix + key);
		String scheme = endpoint.getScheme();
		String host = endpoint.getHost();
		int port = endpoint.getPort();
		String hostPort = port > 0 ? host + ":" + port : host;
		if (pathStyle) return URI.create(scheme + "://" + hostPort + "/" + encodeSegment(bucket) + "/" + objectPath);
		return URI.create(scheme + "://" + bucket + "." + hostPort + "/" + objectPath);
	}

	@Override
	public void upload(String key, Path file) throws IOException {
		String payloadHash = sha256Hex(file);
		HttpRequest.Builder b = signed("PUT", uriFor(key), payloadHash).PUT(HttpRequest.BodyPublishers.ofFile(file));
		HttpResponse<String> res = send(b.build());
		if (res.statusCode() / 100 != 2) throw new IOException("S3 PUT " + key + " failed: HTTP " + res.statusCode() + " " + trim(res.body()));
	}

	@Override
	public boolean exists(String key) throws IOException {
		HttpResponse<String> res = send(signed("HEAD", uriFor(key), emptyHash()).method("HEAD", HttpRequest.BodyPublishers.noBody()).build());
		if (res.statusCode() == 404) return false;
		if (res.statusCode() / 100 != 2) throw new IOException("S3 HEAD " + key + " failed: HTTP " + res.statusCode());
		return true;
	}

	@Override
	public void delete(String key) throws IOException {
		HttpResponse<String> res = send(signed("DELETE", uriFor(key), emptyHash()).DELETE().build());
		if (res.statusCode() / 100 != 2 && res.statusCode() != 404) throw new IOException("S3 DELETE " + key + " failed: HTTP " + res.statusCode());
	}

	@Override
	public String describe() {
		return "s3://" + bucket + "/" + prefix + " @ " + endpoint.getHost();
	}

	private HttpResponse<String> send(HttpRequest req) throws IOException {
		try {
			return client.send(req, HttpResponse.BodyHandlers.ofString());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("interrupted", e);
		}
	}

	/** Builds a request carrying a SigV4 Authorization header (headers signed: host, x-amz-content-sha256, x-amz-date). */
	HttpRequest.Builder signed(String method, URI uri, String payloadHash) {
		ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
		String amzDate = AMZ_DATE.format(now);
		String date = DATE.format(now);
		String host = uri.getPort() > 0 ? uri.getHost() + ":" + uri.getPort() : uri.getHost();
		String canonicalHeaders = "host:" + host + "\n" + "x-amz-content-sha256:" + payloadHash + "\n" + "x-amz-date:" + amzDate + "\n";
		String signedHeaders = "host;x-amz-content-sha256;x-amz-date";
		String canonicalRequest = method + "\n" + uri.getRawPath() + "\n" + "" + "\n" + canonicalHeaders + "\n" + signedHeaders + "\n" + payloadHash;
		String scope = date + "/" + region + "/s3/aws4_request";
		String stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n" + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
		byte[] kDate = hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), date);
		byte[] kRegion = hmac(kDate, region);
		byte[] kService = hmac(kRegion, "s3");
		byte[] kSigning = hmac(kService, "aws4_request");
		String signature = HexFormat.of().formatHex(hmac(kSigning, stringToSign));
		String auth = "AWS4-HMAC-SHA256 Credential=" + accessKey + "/" + scope + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature;
		return HttpRequest.newBuilder(uri)
			.timeout(Duration.ofMinutes(10))
			.header("x-amz-date", amzDate)
			.header("x-amz-content-sha256", payloadHash)
			.header("Authorization", auth);
	}

	static byte[] hmac(byte[] key, String data) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(key, "HmacSHA256"));
			return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	static String sha256Hex(byte[] data) {
		return Hash.compute(data).hex();
	}

	static String sha256Hex(Path file) throws IOException {
		MessageDigest md = Hash.newDigest();
		byte[] buf = new byte[1 << 16];
		try (InputStream in = Files.newInputStream(file)) {
			int n;
			while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
		}
		return Hash.finish(md).hex();
	}

	private static String emptyHash() {
		return sha256Hex(new byte[0]);
	}

	/** RFC 3986 encoding of each path segment, keeping '/' separators (as S3 SigV4 expects). */
	static String encodePath(String path) {
		StringBuilder sb = new StringBuilder();
		for (String seg : path.split("/", -1)) {
			if (!sb.isEmpty() || path.startsWith("/")) sb.append('/');
			sb.append(encodeSegment(seg));
		}
		return sb.toString();
	}

	static String encodeSegment(String s) {
		StringBuilder sb = new StringBuilder();
		for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
			int c = b & 0xFF;
			if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' || c == '~') {
				sb.append((char) c);
			} else {
				sb.append('%').append(String.format("%02X", c));
			}
		}
		return sb.toString();
	}

	private static String trim(String s) {
		return s == null ? "" : s.length() > 300 ? s.substring(0, 300) : s;
	}
}
