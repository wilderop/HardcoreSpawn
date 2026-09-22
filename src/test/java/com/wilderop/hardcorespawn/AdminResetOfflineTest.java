package com.wilderop.hardcorespawn;

import org.mockbukkit.mockbukkit.command.ConsoleCommandSenderMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * /hardcoreadmin reset for offline players: the run ends, the pre-run
 * snapshot is queued for restore on next join, leaderboard stats stay
 * untouched, and resets with nothing to reset (or unknown players) fail
 * cleanly. Also guards that the online reset path is unchanged.
 */
class AdminResetOfflineTest extends PluginTestBase {

    private SessionListener listener() {
        return new SessionListener(sessions(), plugin.getSnapshotManager());
    }

    private ConsoleCommandSenderMock console() {
        return (ConsoleCommandSenderMock) server.getConsoleSender();
    }

    /** Dispatch the reset as console and return the reply message. */
    private String reset(String playerName) {
        assertTrue(server.dispatchCommand(console(), "hardcoreadmin reset " + playerName),
                "dispatch of hardcoreadmin reset failed");
        String msg = console().nextMessage();
        assertNotNull(msg, "expected a reply to /hardcoreadmin reset");
        return msg;
    }

    @Test
    void offlineResetWithActiveSessionQueuesRestoreForRejoin() {
        PlayerMock player = newPlayer();
        moveFarAway(player); // runs carrying items must start >= 2000 blocks from 0,0
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 4));
        startRun(player);
        UUID id = player.getUniqueId();
        String name = player.getName();

        // Disconnect mid-run: the session survives with its persisted snapshot.
        listener().onQuit(new PlayerQuitEvent(player, "quit"));
        assertTrue(player.disconnect(), "mock disconnect failed");
        assertNull(Bukkit.getPlayer(id), "player must read as offline after disconnect");

        String reply = reset(name);
        assertTrue(reply.contains("Reset hardcore state for " + name),
                "unexpected reply: " + reply);

        assertFalse(sessions().hasSession(id), "session must be gone after offline reset");
        assertTrue(sessions().getSnapshots().hasSnapshot(id),
                "snapshot must survive so the rejoin can restore it");

        // Leaderboard untouched: runStarted() counted the run, but an admin
        // reset must not record it as finished.
        LeaderboardManager.Stats stats = sessions().getLeaderboard().stats(id);
        assertEquals(1, stats.totalRuns, "runStarted still counts the run");
        assertEquals(0, stats.bestLevel, "admin reset must not record best level");
        assertEquals(0, stats.totalQuests, "admin reset must not record quests");

        // Rejoin: the queued restore gives the pre-run state back.
        server.addPlayer(player);
        listener().onJoin(new PlayerJoinEvent(player, "join"));
        assertEquals(Material.DIAMOND, player.getInventory().getItem(0).getType());
        assertEquals(4, player.getInventory().getItem(0).getAmount());
        assertEquals(2500, player.getLocation().getX(), 0.001, "restored at pre-run location");
        assertFalse(sessions().getSnapshots().hasSnapshot(id), "snapshot consumed by the restore");
    }

    @Test
    void offlineResetWithNothingToResetReportsCleanly() {
        PlayerMock player = newPlayer();
        String name = player.getName();
        UUID id = player.getUniqueId();
        assertTrue(player.disconnect(), "mock disconnect failed");

        String reply = reset(name);
        assertTrue(reply.contains("no hardcore data to reset"),
                "unexpected reply: " + reply);

        assertFalse(sessions().hasSession(id));
        assertFalse(sessions().getSnapshots().hasSnapshot(id));
        // Data files must still be valid: a fresh snapshot round-trips fine.
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 1));
        Snapshot s = sessions().getSnapshots().takeSnapshot(player);
        sessions().getSnapshots().restore(player, s);
        assertEquals(Material.DIAMOND, player.getInventory().getItem(0).getType());
    }

    @Test
    void resetUnknownPlayerFailsWithNotFound() {
        String reply = reset("NobodyHasEverJoinedZZZ");
        assertTrue(reply.contains("has ever joined"),
                "unexpected reply: " + reply);
    }

    @Test
    void onlineResetBehaviorUnchanged() {
        PlayerMock player = newPlayer();
        moveFarAway(player);
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 4));
        startRun(player);
        UUID id = player.getUniqueId();

        // Online target with a session: the classic path — immediate restore.
        String reply = reset(player.getName());
        assertTrue(reply.contains("Reset hardcore state for " + player.getName()),
                "unexpected reply: " + reply);

        assertFalse(sessions().hasSession(id), "session must end");
        assertEquals(Material.DIAMOND, player.getInventory().getItem(0).getType(),
                "online reset restores the snapshot immediately");
        assertEquals(4, player.getInventory().getItem(0).getAmount());
        assertEquals(2500, player.getLocation().getX(), 0.001, "online reset returns pre-run location");

        // Online behavior keeps its leaderboard recording (unchanged).
        LeaderboardManager.Stats stats = sessions().getLeaderboard().stats(id);
        assertEquals(1, stats.totalRuns);
        assertEquals(1, stats.bestLevel, "online reset still records the reached level");
    }

    @Test
    void onlineResetWithNoSessionStillSaysNotInRun() {
        PlayerMock player = newPlayer(); // online, never started a run
        String reply = reset(player.getName());
        assertTrue(reply.contains("is not in a hardcore run"),
                "unexpected reply: " + reply);
    }
}
