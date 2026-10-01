package dev.steelaspect.cytrabackups.core.notify;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Fire-and-forget Discord webhook poster running on its own daemon thread (never blocks the game). */
public final class DiscordWebhook implements AutoCloseable {
	public enum Level {
		SUCCESS(0x2ECC71), INFO(0x3498DB), WARNING(0xF1C40F), FAILURE(0xE74C3C);

		final int color;

		Level(int color) {
			this.color = color;
		}
	}

	private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "CytraBackups-Discord");
		t.setDaemon(true);
		return t;
	});
	private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	private volatile String url;
	private volatile String username;
	private final Consumer<String> errorLog;

	public DiscordWebhook(String url, String username, Consumer<String> errorLog) {
		this.url = url;
		this.username = username;
		this.errorLog = errorLog;
	}

	public void configure(String url, String username) {
		this.url = url;
		this.username = username;
	}

	public void send(Level level, String title, String description) {
		String target = url;
		if (target == null || target.isBlank()) return;
		JsonObject embed = new JsonObject();
		embed.addProperty("title", truncate(title, 256));
		embed.addProperty("description", truncate(description, 4000));
		embed.addProperty("color", level.color);
		embed.addProperty("timestamp", Instant.now().toString());
		JsonObject footer = new JsonObject();
		footer.addProperty("text", "CytraBackups");
		embed.add("footer", footer);
		JsonArray embeds = new JsonArray();
		embeds.add(embed);
		JsonObject body = new JsonObject();
		body.addProperty("username", username == null || username.isBlank() ? "CytraBackups" : username);
		body.add("embeds", embeds);
		JsonObject mentions = new JsonObject();
		JsonArray parse = new JsonArray();
		parse.add("roles");
		parse.add("users");
		mentions.add("parse", parse);
		body.add("allowed_mentions", mentions);
		post(target, body.toString());
	}

	/** Plain-content message (used for mentions on failures). */
	public void sendText(String text) {
		String target = url;
		if (target == null || target.isBlank() || text.isBlank()) return;
		JsonObject body = new JsonObject();
		body.addProperty("username", username == null || username.isBlank() ? "CytraBackups" : username);
		body.addProperty("content", truncate(text, 1900));
		post(target, body.toString());
	}

	private void post(String target, String json) {
		executor.execute(() -> {
			for (int attempt = 0; attempt < 3; attempt++) {
				try {
					HttpRequest req = HttpRequest.newBuilder(URI.create(target))
						.timeout(Duration.ofSeconds(15))
						.header("Content-Type", "application/json")
						.header("User-Agent", "CytraBackups (Minecraft mod)")
						.POST(HttpRequest.BodyPublishers.ofString(json))
						.build();
					HttpResponse<String> res = client.send(req, HttpResponse.BodyHandlers.ofString());
					if (res.statusCode() == 429) {
						long wait = res.headers().firstValue("Retry-After").map(v -> (long) (Double.parseDouble(v) * 1000)).orElse(2000L);
						Thread.sleep(Math.min(wait, 30_000));
						continue;
					}
					if (res.statusCode() >= 400) errorLog.accept("Discord webhook returned HTTP " + res.statusCode() + ": " + truncate(res.body(), 200));
					return;
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return;
				} catch (Exception e) {
					if (attempt == 2) errorLog.accept("Discord webhook failed: " + e.getMessage());
				}
			}
		});
	}

	private static String truncate(String s, int max) {
		if (s == null) return "";
		return s.length() <= max ? s : s.substring(0, max - 3) + "...";
	}

	@Override
	public void close() {
		executor.shutdown();
		try {
			executor.awaitTermination(5, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
