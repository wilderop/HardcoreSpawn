package com.wilderop.hardcorespawn;

import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.bukkit.Material;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Disconnect clock: resume inside 60s, forced restore beyond it. */
class DisconnectTest extends PluginTestBase {

    private SessionListener listener() {
        return new SessionListener(sessions(), plugin.getSnapshotManager());
    }

    @Test
    void rejoinWithin60SecondsResumes() {
        PlayerMock player = newPlayer();
        moveFarAway(player); // runs carrying items must start >= 2000 blocks from 0,0
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 4));
        startRun(player);
        long deadline = sessions().getSession(player.getUniqueId()).questDeadlineMs;

        listener().onQuit(new PlayerQuitEvent(player, "quit"));
        Session s = sessions().getSession(player.getUniqueId());
        assertNotNull(s, "session survives the disconnect");
        assertNotEquals(0L, s.offlineSinceMs);

        listener().onJoin(new PlayerJoinEvent(player, "join"));
        server.getScheduler().performTicks(150);
        assertEquals(0L, s.offlineSinceMs, "clock cleared on resume");
        assertEquals(deadline, s.questDeadlineMs, "deadline preserved across a quick rejoin");
        assertTrue(sessions().hasSession(player.getUniqueId()));
    }

    @Test
    void rejoinAfter60SecondsEndsRunAndRestores() {
        PlayerMock player = newPlayer();
        moveFarAway(player); // runs carrying items must start >= 2000 blocks from 0,0
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 4));
        double beforeX = player.getLocation().getX();
        startRun(player);
        UUID id = player.getUniqueId();

        listener().onQuit(new PlayerQuitEvent(player, "quit"));
        Session s = sessions().getSession(id);
        s.offlineSinceMs = System.currentTimeMillis() - 61_000;

        listener().onJoin(new PlayerJoinEvent(player, "join"));
        server.getScheduler().performTicks(150);

        assertFalse(sessions().hasSession(id), "run must end after a long disconnect");
        assertEquals(beforeX, player.getLocation().getX(), 0.001, "restored at pre-run location");
        assertEquals(Material.DIAMOND, player.getInventory().getItem(0).getType());
        assertEquals(4, player.getInventory().getItem(0).getAmount());
    }

    @Test
    void timerTaskAlsoEndsLongDisconnects() {
        PlayerMock player = newPlayer();
        startRun(player);
        UUID id = player.getUniqueId();

        listener().onQuit(new PlayerQuitEvent(player, "quit"));
        Session s = sessions().getSession(id);
        s.offlineSinceMs = System.currentTimeMillis() - 61_000;

        new TimerTask(sessions()).tick(System.currentTimeMillis());

        assertFalse(sessions().hasSession(id), "timer should end stale offline sessions");
        assertNotNull(plugin.getSnapshotManager(), "snapshot manager still available");
    }
}
