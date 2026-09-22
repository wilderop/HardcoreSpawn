package com.wilderop.hardcorespawn;

import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Discord webhook reporting: payload shape, disabled-by-default, and that
 * the session manager fires notifications on run start, quest completion,
 * and run end.
 */
class DiscordNotifierTest extends PluginTestBase {

    /** Captures payloads instead of hitting the network. */
    static final class RecordingNotifier extends DiscordNotifier {
        final List<String> payloads = new ArrayList<>();

        RecordingNotifier(String url) {
            super(url, Logger.getLogger("test"));
        }

        @Override
        protected void postJson(String json) {
            payloads.add(json);
        }
    }

    @Test
    void disabledWhenUrlEmpty() {
        RecordingNotifier n = new RecordingNotifier("");
        assertFalse(n.isEnabled());
        n.sendRunStarted("Steve", List.of("Travel 500 blocks"));
        n.sendQuestCompleted("Steve", "Break 10 oak logs", "Smelt 5 iron ingots", 1, 2);
        n.sendRunEnded("Steve", ExitCause.IN_WORLD_DEATH, 3, 2, 60_000);
        assertTrue(n.payloads.isEmpty(), "disabled notifier must send nothing");
    }

    @Test
    void runStartedPayload() {
        RecordingNotifier n = new RecordingNotifier("https://example.com/hook");
        n.sendRunStarted("Steve", List.of("Travel 500 blocks on foot", "Break 8 oak logs"));
        assertEquals(1, n.payloads.size());
        String json = n.payloads.get(0);
        assertTrue(json.contains("Steve started a hardcore run"), json);
        assertTrue(json.contains("Travel 500 blocks on foot"), json);
        assertTrue(json.contains("\"embeds\""), json);
    }

    @Test
    void questCompletedPayload() {
        RecordingNotifier n = new RecordingNotifier("https://example.com/hook");
        n.sendQuestCompleted("Alex", "Smelt 5 iron ingots", "Tame a horse", 4, 5);
        assertEquals(1, n.payloads.size());
        String json = n.payloads.get(0);
        assertTrue(json.contains("Alex completed a quest"), json);
        assertTrue(json.contains("Smelt 5 iron ingots"), json);
        assertTrue(json.contains("Tame a horse"), json);
        assertTrue(json.contains("New quest"), json);
    }

    @Test
    void runEndedPayloadsPerCause() {
        RecordingNotifier n = new RecordingNotifier("https://example.com/hook");
        n.sendRunEnded("Steve", ExitCause.IN_WORLD_DEATH, 6, 5, 3_700_000);
        n.sendRunEnded("Alex", ExitCause.QUIT, 2, 1, 90_000);
        n.sendRunEnded("Bo", ExitCause.DISCONNECT_TIMEOUT, 1, 0, 61_000);
        assertEquals(3, n.payloads.size());
        assertTrue(n.payloads.get(0).contains("died"), n.payloads.get(0));
        assertTrue(n.payloads.get(0).contains("1h 1m"), n.payloads.get(0));
        assertTrue(n.payloads.get(1).contains("forfeited"), n.payloads.get(1));
        assertTrue(n.payloads.get(2).contains("disconnected too long"), n.payloads.get(2));
    }

    @Test
    void sessionManagerFiresNotifications() {
        PlayerMock player = newPlayer();
        moveFarAway(player); // satisfy the 2000-block start rule
        RecordingNotifier rec = new RecordingNotifier("https://example.com/hook");
        sessions().setDiscordNotifier(rec);

        startRun(player);
        assertFalse(rec.payloads.isEmpty(), "run start should notify Discord");
        assertTrue(rec.payloads.get(0).contains("NotifySteve started a hardcore run")
                || rec.payloads.get(0).contains("started a hardcore run"),
                rec.payloads.get(0));

        Session s = sessions().getSession(player.getUniqueId());
        Quest travel = null;
        for (Quest q : s.hand) {
            for (QuestObjective o : q.objectives()) {
                if (o.type() == QuestType.TRAVEL) {
                    travel = q;
                }
            }
        }
        assertNotNull(travel, "opening hand must contain the travel starter");
        sessions().completeQuest(player, s, travel);
        assertEquals(2, rec.payloads.size(), "quest completion should notify Discord");
        assertTrue(rec.payloads.get(1).contains("completed a quest"), rec.payloads.get(1));
        assertTrue(rec.payloads.get(1).contains("New quest"), rec.payloads.get(1));

        sessions().endRun(player.getUniqueId(), ExitCause.IN_WORLD_DEATH);
        assertEquals(3, rec.payloads.size(), "run end should notify Discord");
        assertTrue(rec.payloads.get(2).contains("died"), rec.payloads.get(2));
    }
}
