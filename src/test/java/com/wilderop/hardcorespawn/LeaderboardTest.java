package com.wilderop.hardcorespawn;

import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** /hardcore top: the leaderboard is viewable, ordered by best level. */
class LeaderboardTest extends PluginTestBase {

    @BeforeEach
    void clearLeaderboard() {
        plugin.getLeaderboardManager().clear();
    }

    private void recordRun(UUID id, int level, int quests) {
        var lb = plugin.getLeaderboardManager();
        lb.runStarted(id);
        lb.runFinished(id, level, quests);
    }

    @Test
    void topOrdersByBestLevel() {
        PlayerMock alice = newPlayer();
        PlayerMock bob = newPlayer();
        PlayerMock cara = newPlayer();
        recordRun(alice.getUniqueId(), 5, 4);
        recordRun(bob.getUniqueId(), 12, 10);
        recordRun(cara.getUniqueId(), 8, 7);

        assert alice.performCommand("hardcore top");

        String header = alice.nextMessage();
        assertTrue(header.contains("Leaderboard"), header);
        String first = alice.nextMessage();
        assertTrue(first.contains("#1"), first);
        assertTrue(first.contains(bob.getName()), "best level 12 should rank first: " + first);
        String second = alice.nextMessage();
        assertTrue(second.contains(cara.getName()), "best level 8 should rank second: " + second);
        String third = alice.nextMessage();
        assertTrue(third.contains(alice.getName()), "best level 5 should rank third: " + third);
    }

    @Test
    void topBreaksTiesByTotalQuests() {
        PlayerMock alice = newPlayer();
        PlayerMock bob = newPlayer();
        recordRun(alice.getUniqueId(), 6, 20);
        recordRun(bob.getUniqueId(), 6, 5);

        assert alice.performCommand("hardcore top");
        alice.nextMessage(); // header

        String first = alice.nextMessage();
        assertTrue(first.contains(alice.getName()),
                "more total quests should win the tie: " + first);
    }

    @Test
    void topShowsEmptyMessageWhenNoRuns() {
        PlayerMock alice = newPlayer();

        assert alice.performCommand("hardcore top");

        String msg = alice.nextMessage();
        assertTrue(msg.contains("No hardcore runs recorded yet"), msg);
    }

    @Test
    void topLimitsToTen() {
        for (int i = 0; i < 12; i++) {
            PlayerMock p = newPlayer();
            recordRun(p.getUniqueId(), i + 1, i);
        }
        PlayerMock viewer = newPlayer();

        assert viewer.performCommand("hardcore top");
        viewer.nextMessage(); // header
        int entries = 0;
        while (viewer.nextMessage() != null) {
            entries++;
        }
        assertEquals(10, entries, "leaderboard should show at most 10 entries");
    }
}
