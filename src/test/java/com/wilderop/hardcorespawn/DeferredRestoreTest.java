package com.wilderop.hardcorespawn;

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
 * Regression tests for the Monticelli incident (v1.10.2 and earlier): a run
 * that ended while the player was offline queued a pre-run restore that was
 * applied synchronously inside PlayerJoinEvent. PlayerDataSyncReloaded syncs
 * the player's inventory on join AFTER our handler ran, silently overwriting
 * the restored pre-run inventory with the run-era state it had stored — no
 * error was logged anywhere and the player kept the empty run inventory.
 *
 * <p>The fix: the queued restore is applied on a short delay AFTER the join
 * settles (see {@code restore-join-delay-ticks}), and the queue entry is
 * consumed only when the deferred task actually succeeds.
 */
class DeferredRestoreTest extends PluginTestBase {

    private SessionListener listener() {
        return new SessionListener(sessions(), plugin.getSnapshotManager());
    }

    /** Full run -> quit -> disconnect -> offline-timeout -> queued offline restore. */
    private PlayerMock runThatTimesOutOffline() {
        PlayerMock player = newPlayer();
        moveFarAway(player);
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 4));
        startRun(player);
        UUID id = player.getUniqueId();

        listener().onQuit(new PlayerQuitEvent(player, "quit"));
        assertTrue(player.disconnect(), "mock disconnect failed");
        sessions().getSession(id).offlineSinceMs = System.currentTimeMillis() - 61_000;
        new TimerTask(sessions()).tick(System.currentTimeMillis());
        assertFalse(sessions().hasSession(id), "run must end after the disconnect timeout");
        assertTrue(sessions().hasPendingOfflineRestore(id), "restore must be queued");
        simulateRejoin(player);
        return player;
    }

    private void advanceJoinDelay() {
        server.getScheduler().performTicks(
                sessions().getConfig().getRestoreJoinDelayTicks() + 50);
    }

    @Test
    void restoreIsDeferredPastTheJoinEvent() {
        PlayerMock player = runThatTimesOutOffline();
        UUID id = player.getUniqueId();
        double beforeX = 2500; // moveFarAway put the pre-run snapshot here

        listener().onJoin(new PlayerJoinEvent(player, "join"));

        // Nothing restored yet: the join event itself must not touch items.
        assertNull(player.getInventory().getItem(0),
                "restore must NOT apply synchronously in the join event");
        assertTrue(sessions().hasPendingOfflineRestore(id),
                "restore must stay queued until the deferred task succeeds");

        advanceJoinDelay();

        assertEquals(Material.DIAMOND, player.getInventory().getItem(0).getType());
        assertEquals(4, player.getInventory().getItem(0).getAmount());
        assertEquals(beforeX, player.getLocation().getX(), 0.001);
        assertFalse(sessions().hasPendingOfflineRestore(id),
                "restore must be consumed once applied");
        assertFalse(plugin.getSnapshotManager().hasSnapshot(id),
                "snapshot must be consumed by the restore");
    }

    @Test
    void lateJoinSyncCannotOverwriteTheRestore() {
        // Simulates PlayerDataSyncReloaded: it applies the run-era inventory
        // it stored at quit time AFTER our join handler runs.
        PlayerMock player = runThatTimesOutOffline();

        listener().onJoin(new PlayerJoinEvent(player, "join"));
        // The "sync plugin" clobbers the inventory after our handler.
        player.getInventory().clear();
        player.getInventory().setItem(3, new ItemStack(Material.DIRT, 2));

        advanceJoinDelay();

        // Our deferred restore ran after the clobber and wins.
        assertEquals(Material.DIAMOND, player.getInventory().getItem(0).getType(),
                "deferred restore must beat a late join-time inventory sync");
        assertEquals(4, player.getInventory().getItem(0).getAmount());
        assertNull(player.getInventory().getItem(3),
                "run-era items must be gone after the restore");
    }

    @Test
    void disconnectDuringDelayKeepsRestoreQueuedForNextJoin() {
        PlayerMock player = runThatTimesOutOffline();
        UUID id = player.getUniqueId();

        listener().onJoin(new PlayerJoinEvent(player, "join"));
        assertTrue(player.disconnect(), "mock disconnect failed");
        assertNull(Bukkit.getPlayer(id));

        // The deferred task fires while offline: it must not consume the entry.
        advanceJoinDelay();
        assertTrue(sessions().hasPendingOfflineRestore(id),
                "restore must stay queued when the player is offline at fire time");
        assertNull(player.getInventory().getItem(0));

        // Next join re-schedules and the restore completes.
        simulateRejoin(player);
        listener().onJoin(new PlayerJoinEvent(player, "join"));
        advanceJoinDelay();
        assertEquals(Material.DIAMOND, player.getInventory().getItem(0).getType());
        assertFalse(sessions().hasPendingOfflineRestore(id));
    }

    @Test
    void doubleJoinDoesNotDoubleSchedule() {
        PlayerMock player = runThatTimesOutOffline();

        listener().onJoin(new PlayerJoinEvent(player, "join"));
        listener().onJoin(new PlayerJoinEvent(player, "join"));

        advanceJoinDelay();
        // Exactly one restore: items present once, no duplication, no error.
        assertEquals(Material.DIAMOND, player.getInventory().getItem(0).getType());
        assertEquals(4, player.getInventory().getItem(0).getAmount());
        assertFalse(sessions().hasPendingOfflineRestore(player.getUniqueId()));
    }

    @Test
    void newRunDuringDelaySkipsTheStaleRestore() {
        PlayerMock player = runThatTimesOutOffline();
        UUID id = player.getUniqueId();

        listener().onJoin(new PlayerJoinEvent(player, "join"));
        // Player immediately starts a NEW run before the delay elapses.
        moveFarAway(player);
        player.getInventory().setItem(0, new ItemStack(Material.EMERALD, 7));
        startRun(player);
        assertTrue(sessions().hasSession(id), "new run must be active");

        advanceJoinDelay();

        // The stale snapshot must never be applied over the live run.
        assertTrue(sessions().hasSession(id), "live run must survive");
        assertTrue(plugin.getSnapshotManager().hasSnapshot(id),
                "live run's snapshot must be untouched");
        assertFalse(sessions().hasPendingOfflineRestore(id),
                "stale restore must be dropped, not left queued");
    }

    @Test
    void joinTimeoutWithoutSweeperDefersRestoreToo() {
        // The offline sweeper (TimerTask) hasn't fired yet when the player
        // rejoins past the grace period: the join itself ends the run, and
        // that restore must be deferred as well.
        PlayerMock player = newPlayer();
        moveFarAway(player);
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 4));
        startRun(player);
        UUID id = player.getUniqueId();

        listener().onQuit(new PlayerQuitEvent(player, "quit"));
        sessions().getSession(id).offlineSinceMs = System.currentTimeMillis() - 61_000;

        listener().onJoin(new PlayerJoinEvent(player, "join"));
        assertFalse(sessions().hasSession(id), "run must end on the stale rejoin");
        assertNull(player.getInventory().getItem(0),
                "restore must be deferred, not applied in the join event");

        advanceJoinDelay();
        assertEquals(Material.DIAMOND, player.getInventory().getItem(0).getType());
        assertEquals(4, player.getInventory().getItem(0).getAmount());
    }
}
