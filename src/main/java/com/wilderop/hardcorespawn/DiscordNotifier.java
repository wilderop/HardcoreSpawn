package com.wilderop.hardcorespawn;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.logging.Logger;

/**
 * Reports run lifecycle events to a Discord channel via webhook. All sends
 * are fire-and-forget on a background thread: Discord must never block or
 * break the game thread. An empty webhook URL disables everything.
 *
 * <p>Test seam: override {@link #postJson(String)} to capture payloads.
 */
public class DiscordNotifier {
    private static final int COLOR_GREEN = 0x57F287;
    private static final int COLOR_BLUE = 0x3498DB;
    private static final int COLOR_RED = 0xED4245;
    private static final int COLOR_ORANGE = 0xE67E22;
    private static final int COLOR_GREY = 0x95A5A6;

    private final String webhookUrl;
    private final Logger logger;
    private final HttpClient http;

    public DiscordNotifier(String webhookUrl, Logger logger) {
        this.webhookUrl = webhookUrl == null ? "" : webhookUrl.trim();
        this.logger = logger;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public boolean isEnabled() {
        return !webhookUrl.isEmpty();
    }

    /** A run actually began (after the start freeze countdown, if any). */
    public void sendRunStarted(String playerName, List<String> questDescriptions) {
        if (!isEnabled()) {
            return;
        }
        JsonObject embed = baseEmbed(COLOR_GREEN,
                "\uD83D\uDFE2 " + playerName + " started a hardcore run");
        StringBuilder desc = new StringBuilder("Opening quests:\n");
        for (String q : questDescriptions) {
            desc.append("\u2022 ").append(q).append('\n');
        }
        embed.addProperty("description", desc.toString().stripTrailing());
        postEmbed(embed);
    }

    /** One quest in the hand was completed; a replacement quest is dealt. */
    public void sendQuestCompleted(String playerName, String questDescription,
                                   String newQuestDescription,
                                   int questsCompleted, int newLevel) {
        if (!isEnabled()) {
            return;
        }
        JsonObject embed = baseEmbed(COLOR_BLUE,
                "\u2705 " + playerName + " completed a quest");
        embed.addProperty("description", questDescription);
        embed.add("fields", fields(
                field("New quest", newQuestDescription, false),
                field("Quests completed", String.valueOf(questsCompleted), true),
                field("Level", String.valueOf(newLevel), true)));
        postEmbed(embed);
    }

    /** A run ended for any reason. */
    public void sendRunEnded(String playerName, ExitCause cause,
                             int levelReached, int questsCompleted, long survivedMs) {
        if (!isEnabled()) {
            return;
        }
        String title;
        int color;
        switch (cause) {
            case IN_WORLD_DEATH -> {
                title = "\uD83D\uDC80 " + playerName + " died";
                color = COLOR_RED;
            }
            case QUIT -> {
                title = "\uD83C\uDFF3\uFE0F " + playerName + " forfeited their run";
                color = COLOR_ORANGE;
            }
            case DISCONNECT_TIMEOUT -> {
                title = "\uD83D\uDC80 " + playerName + " died (disconnected too long)";
                color = COLOR_RED;
            }
            case ADMIN_RESET -> {
                title = "\uD83D\uDD27 " + playerName + "'s run was reset by an admin";
                color = COLOR_GREY;
            }
            default -> {
                title = playerName + "'s run ended";
                color = COLOR_GREY;
            }
        }
        JsonObject embed = baseEmbed(color, title);
        embed.add("fields", fields(
                field("Level reached", String.valueOf(levelReached), true),
                field("Quests completed", String.valueOf(questsCompleted), true),
                field("Survived", formatDuration(survivedMs), true)));
        postEmbed(embed);
    }

    private JsonObject baseEmbed(int color, String title) {
        JsonObject embed = new JsonObject();
        embed.addProperty("title", title);
        embed.addProperty("color", color);
        embed.addProperty("timestamp", Instant.now().toString());
        return embed;
    }

    private JsonObject field(String name, String value, boolean inline) {
        JsonObject f = new JsonObject();
        f.addProperty("name", name);
        f.addProperty("value", value);
        f.addProperty("inline", inline);
        return f;
    }

    private JsonArray fields(JsonObject... fields) {
        JsonArray arr = new JsonArray();
        for (JsonObject f : fields) {
            arr.add(f);
        }
        return arr;
    }

    private static String formatDuration(long ms) {
        if (ms < 0) {
            ms = 0;
        }
        long totalSeconds = ms / 1000;
        long h = totalSeconds / 3600;
        long m = (totalSeconds % 3600) / 60;
        long s = totalSeconds % 60;
        if (h > 0) {
            return h + "h " + m + "m";
        }
        if (m > 0) {
            return m + "m " + s + "s";
        }
        return s + "s";
    }

    private void postEmbed(JsonObject embed) {
        JsonObject payload = new JsonObject();
        JsonArray embeds = new JsonArray();
        embeds.add(embed);
        payload.add("embeds", embeds);
        String json = payload.toString();
        try {
            postJson(json);
        } catch (Exception e) {
            // Never let Discord break the game.
            logger.warning("HardcoreSpawn: failed to send Discord webhook: " + e.getMessage());
        }
    }

    /**
     * Actually delivers the payload. Runs on the caller's thread — callers
     * must invoke the send* methods off the main thread or accept the async
     * handoff done here. The default implementation is async and never throws.
     */
    protected void postJson(String json) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(webhookUrl))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        http.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .exceptionally(e -> {
                    logger.warning("HardcoreSpawn: Discord webhook delivery failed: " + e.getMessage());
                    return null;
                });
    }
}
