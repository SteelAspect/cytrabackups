package dev.steelaspect.cytrabackups.core.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Exercises the webhook client against a local server that behaves like Discord's execute-webhook endpoint. */
class DiscordWebhookTest {
	HttpServer server;
	final List<String> bodies = new CopyOnWriteArrayList<>();
	final List<String> contentTypes = new CopyOnWriteArrayList<>();
	final AtomicInteger rateLimitsLeft = new AtomicInteger();
	volatile int status = 204;

	@BeforeEach
	void start() throws Exception {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/api/webhooks/1/token", ex -> {
			bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			contentTypes.add(ex.getRequestHeaders().getFirst("Content-Type"));
			if (rateLimitsLeft.getAndDecrement() > 0) {
				ex.getResponseHeaders().add("Retry-After", "0.05");
				byte[] b = "{\"message\":\"You are being rate limited.\",\"retry_after\":0.05,\"global\":false}".getBytes();
				ex.sendResponseHeaders(429, b.length);
				ex.getResponseBody().write(b);
			} else if (status == 204) {
				ex.sendResponseHeaders(204, -1);
			} else {
				byte[] b = "{\"message\":\"Invalid Webhook Token\",\"code\":50027}".getBytes();
				ex.sendResponseHeaders(status, b.length);
				ex.getResponseBody().write(b);
			}
			ex.close();
		});
		server.start();
	}

	@AfterEach
	void stop() {
		server.stop(0);
	}

	private String url() {
		return "http://127.0.0.1:" + server.getAddress().getPort() + "/api/webhooks/1/token";
	}

	@Test
	void sendsDiscordEmbedPayload() {
		List<String> errors = new CopyOnWriteArrayList<>();
		try (DiscordWebhook hook = new DiscordWebhook(url(), "CytraBackups", errors::add)) {
			hook.send(DiscordWebhook.Level.SUCCESS, "Backup #7 created", "**Size:** 1.2 GiB");
		}
		assertEquals(1, bodies.size());
		assertEquals("application/json", contentTypes.get(0));
		JsonObject body = JsonParser.parseString(bodies.get(0)).getAsJsonObject();
		assertEquals("CytraBackups", body.get("username").getAsString());
		JsonObject embed = body.getAsJsonArray("embeds").get(0).getAsJsonObject();
		assertEquals("Backup #7 created", embed.get("title").getAsString());
		assertEquals("**Size:** 1.2 GiB", embed.get("description").getAsString());
		assertEquals(0x2ECC71, embed.get("color").getAsInt());
		assertTrue(embed.get("timestamp").getAsString().endsWith("Z"), "ISO-8601 UTC timestamp");
		assertTrue(body.getAsJsonObject("allowed_mentions").getAsJsonArray("parse").size() == 2);
		assertTrue(errors.isEmpty(), errors.toString());
	}

	@Test
	void retriesAfterRateLimit() {
		rateLimitsLeft.set(2);
		List<String> errors = new CopyOnWriteArrayList<>();
		try (DiscordWebhook hook = new DiscordWebhook(url(), "x", errors::add)) {
			hook.send(DiscordWebhook.Level.FAILURE, "Backup failed", "disk full");
		}
		assertEquals(3, bodies.size(), "two 429s then success");
		assertTrue(errors.isEmpty(), errors.toString());
	}

	@Test
	void reportsHttpErrorsAndTruncatesLongText() {
		status = 401;
		List<String> errors = new CopyOnWriteArrayList<>();
		try (DiscordWebhook hook = new DiscordWebhook(url(), "x", errors::add)) {
			hook.send(DiscordWebhook.Level.WARNING, "t".repeat(400), "d".repeat(5000));
		}
		assertEquals(1, errors.size());
		assertTrue(errors.get(0).contains("401") && errors.get(0).contains("Invalid Webhook Token"), errors.get(0));
		JsonObject embed = JsonParser.parseString(bodies.get(0)).getAsJsonObject().getAsJsonArray("embeds").get(0).getAsJsonObject();
		assertTrue(embed.get("title").getAsString().length() <= 256, "Discord title limit");
		assertTrue(embed.get("description").getAsString().length() <= 4096, "Discord description limit");
	}

	@Test
	void blankUrlSendsNothing() {
		try (DiscordWebhook hook = new DiscordWebhook("", "x", e -> {
		})) {
			hook.send(DiscordWebhook.Level.INFO, "a", "b");
			hook.sendText("hi");
		}
		assertEquals(0, bodies.size());
	}
}
