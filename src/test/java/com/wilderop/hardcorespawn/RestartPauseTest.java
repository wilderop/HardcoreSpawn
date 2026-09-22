package com.wilderop.hardcorespawn;

import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.bukkit.Material;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Restart semantics: a shutdown freezes the quest clock and the disconnect
 * clock; downtime counts against neither. On rejoin the run resumes with the
 * remaining time intact.
 */
class RestartPauseTest extends PluginTestBase {

    private SessionListener listener() {
        return new SessionListener(sessions(), plugin.getSnapshotManager());
    }

    @Test
    void shutdownPausesAndRejoinResumesWithRemainingTime() {
        PlayerMock player = newPlayer();
        moveFarAway(player);
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 2));
        startRun(player);
        Session s = sessions().getSession(player.getUniqueId());
        long remainingBefore = s.questDeadlineMs - System.currentTimeMillis();
        assertTrue(remainingBefore > 0);

        // Simulate a restart: disable freezes the clocks, enable reloads them.
        sessions().shutdown();
        assertEquals(0L, s.questDeadlineMs, "deadline must be frozen (0) after shutdown");
        assertTrue(s.pausedQuestRemainingMs > 0, "remaining time must be frozen");

        sessions().loadSessions();
        Session reloaded = sessions().getSession(player.getUniqueId());
        assertNotNull(reloaded, "session must survive the restart");
        assertEquals(0L, reloaded.questDeadlineMs, "session must load paused");

        listener().onJoin(new PlayerJoinEvent(player, "join"));
        assertNotEquals(0L, reloaded.questDeadlineMs, "clock must resume on rejoin");
        long remainingAfter = reloaded.questDeadlineMs - System.currentTimeMillis();
        assertTrue(Math.abs(remainingAfter - s.pausedQuestRemainingMs) < 60_000,
                "resumed deadline must reflect the frozen remaining time");
    }

    @Test
    void offlineDuringRestartStillTimesOutOnFrozenOfflineTime() {
        PlayerMock player = newPlayer();
        moveFarAway(player);
        startRun(player);

        sessions().shutdown();
        sessions().loadSessions();
        Session reloaded = sessions().getSession(player.getUniqueId());
        // Offline long before the restart: the frozen offline time exceeds the grace period.
        reloaded.pausedOfflineElapsedMs = 120_000;

        listener().onJoin(new PlayerJoinEvent(player, "join"));
        assertFalse(sessions().hasSession(player.getUniqueId()),
                "a disconnect that was already stale at shutdown must end the run on rejoin");
    }
}
