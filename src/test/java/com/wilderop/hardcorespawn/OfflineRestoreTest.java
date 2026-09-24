package com.wilderop.hardcorespawn;

import org.mockbukkit.mockbukkit.command.ConsoleCommandSenderMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The offline-restore restart bug (v1.10.1 and earlier): plugins with the
 * default STARTUP load order enable before any world is loaded, so
 * loadRestores() resolved Bukkit.getWorld() to null and silently dropped
 * every pending offline restore. The next shutdown then wiped the entries
 * from restores.yml, and players rejoining after a restart (e.g. a
 * disconnect-timeout across the nightly reboot) never got their pre-run
 * inventory or location back.
 */
class OfflineRestoreTest extends PluginTestBase {

    private SessionListener listener() {
        return new SessionListener(sessions(), plugin.getSnapshotManager());
    }

    private ConsoleCommandSenderMock console() {
        return (ConsoleCommandSenderMock) server.getConsoleSender();
    }

    /**
     * Write a restores.yml entry by hand, pointing at a world Bukkit does not
     * know — exactly what the loader sees at STARTUP enable time, when no
     * world is loaded yet.
     */
    private void writeRestoresEntry(UUID id, String worldName, Location loc) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("offline.0.player", id.toString());
        yaml.set("offline.0.messageKey", "disconnect-death");
        yaml.set("offline.0.level", 2);
        yaml.set("offline.0.return.world", worldName);
        yaml.set("offline.0.return.x", loc.getX());
        yaml.set("offline.0.return.y", loc.getY());
        yaml.set("offline.0.return.z", loc.getZ());
        yaml.set("offline.0.return.yaw", loc.getYaw());
        yaml.set("offline.0.return.pitch", loc.getPitch());
        yaml.save(new File(plugin.getDataFolder(), "restores.yml"));
    }

    @Test
    void restoreEntrySurvivesLoadWhenWorldNotYetLoaded() throws Exception {
        PlayerMock player = newPlayer();
        moveFarAway(player);
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 4));
        UUID id = player.getUniqueId();
        // Persist the pre-run snapshot, then wipe the player to prove the
        // restore brings the items back.
        plugin.getSnapshotManager().takeSnapshot(player);
        player.getInventory().clear();

        writeRestoresEntry(id, "world_that_is_not_loaded_yet", player.getLocation());

        // Fresh JVM: empty in-memory state, disk intact.
        sessions().clearSessionsForTests();
        sessions().loadRestores();

        // The entry must be retained (world resolved lazily), not dropped.
        listener().onJoin(new PlayerJoinEvent(player, "join"));
        assertNotNull(player.getInventory().getItem(0), "inventory must be restored");
        assertEquals(Material.DIAMOND, player.getInventory().getItem(0).getType());
        assertEquals(4, player.getInventory().getItem(0).getAmount());
    }

    @Test
    void disconnectTimeoutAcrossRestartRestoresInventoryAndLocation() {
        PlayerMock player = newPlayer();
        moveFarAway(player);
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 4));
        double beforeX = player.getLocation().getX();
        double beforeZ = player.getLocation().getZ();
        startRun(player);
        UUID id = player.getUniqueId();

        // Disconnect mid-run and stay away past the grace period.
        listener().onQuit(new PlayerQuitEvent(player, "quit"));
        assertTrue(player.disconnect(), "mock disconnect failed");
        assertNull(Bukkit.getPlayer(id), "player must read as offline after disconnect");
        sessions().getSession(id).offlineSinceMs = System.currentTimeMillis() - 61_000;
        new TimerTask(sessions()).tick(System.currentTimeMillis());
        assertFalse(sessions().hasSession(id), "run must end after the disconnect timeout");

        // Restart: shutdown persists, fresh state reloads, orphans recover.
        sessions().shutdown();
        sessions().clearSessionsForTests();
        sessions().loadSessions();
        sessions().loadRestores();
        sessions().recoverOrphanedSnapshots();

        // Rejoin: the pre-run state must come back.
        listener().onJoin(new PlayerJoinEvent(player, "join"));
        assertNotNull(player.getInventory().getItem(0), "inventory must be restored after rejoin");
        assertEquals(Material.DIAMOND, player.getInventory().getItem(0).getType());
        assertEquals(4, player.getInventory().getItem(0).getAmount());
        assertEquals(beforeX, player.getLocation().getX(), 0.001, "pre-run location must be restored");
        assertEquals(beforeZ, player.getLocation().getZ(), 0.001, "pre-run location must be restored");
    }

    @Test
    void orphanedSnapshotIsRecoveredForNextJoin() {
        PlayerMock player = newPlayer();
        moveFarAway(player);
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 4));
        double beforeX = player.getLocation().getX();
        UUID id = player.getUniqueId();
        // Snapshot on disk, no session, no restore entry: exactly what the
        // restart bug left behind.
        plugin.getSnapshotManager().takeSnapshot(player);
        player.getInventory().clear();

        sessions().recoverOrphanedSnapshots();

        listener().onJoin(new PlayerJoinEvent(player, "join"));
        assertNotNull(player.getInventory().getItem(0), "orphaned snapshot must be restored");
        assertEquals(Material.DIAMOND, player.getInventory().getItem(0).getType());
        assertEquals(beforeX, player.getLocation().getX(), 0.001, "pre-run location must be restored");
        assertFalse(plugin.getSnapshotManager().hasSnapshot(id),
                "snapshot must be consumed by the recovery restore");
    }

    @Test
    void orphanRecoverySkipsActiveSessions() {
        PlayerMock player = newPlayer();
        moveFarAway(player);
        startRun(player);
        UUID id = player.getUniqueId();

        sessions().recoverOrphanedSnapshots();

        assertTrue(sessions().hasSession(id), "active run must be untouched by recovery");
        assertTrue(plugin.getSnapshotManager().hasSnapshot(id),
                "active run's snapshot must not be queued for restore");
    }

    @Test
    void adminRestoreAppliesImmediatelyForOnlinePlayer() {
        PlayerMock player = newPlayer();
        moveFarAway(player);
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 4));
        double beforeX = player.getLocation().getX();
        UUID id = player.getUniqueId();
        plugin.getSnapshotManager().takeSnapshot(player);
        player.getInventory().clear();

        assertTrue(sessions().restoreSnapshot(id), "restoreSnapshot must succeed with a stored snapshot");
        assertEquals(Material.DIAMOND, player.getInventory().getItem(0).getType());
        assertEquals(beforeX, player.getLocation().getX(), 0.001);
    }

    @Test
    void adminRestoreRefusesDuringActiveRun() {
        PlayerMock player = newPlayer();
        moveFarAway(player);
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 4));
        startRun(player);
        UUID id = player.getUniqueId();

        assertFalse(sessions().restoreSnapshot(id), "must refuse while a run is active");
        assertTrue(sessions().hasSession(id), "active run must survive the refused restore");
    }

    @Test
    void adminRestoreReportsNothingWhenNoSnapshot() {
        PlayerMock player = newPlayer();
        assertFalse(sessions().restoreSnapshot(player.getUniqueId()),
                "must report nothing to restore without a snapshot");
    }

    @Test
    void adminRestoreCommandQueuesOfflinePlayer() {
        PlayerMock player = newPlayer();
        moveFarAway(player);
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 4));
        double beforeX = player.getLocation().getX();
        UUID id = player.getUniqueId();
        String name = player.getName();
        plugin.getSnapshotManager().takeSnapshot(player);
        player.getInventory().clear();
        assertTrue(player.disconnect(), "mock disconnect failed");

        assertTrue(server.dispatchCommand(console(), "hardcoreadmin restore " + name),
                "dispatch of hardcoreadmin restore failed");
        String msg = console().nextMessage();
        assertNotNull(msg, "expected a reply to /hardcoreadmin restore");
        assertTrue(msg.contains("Restored pre-run snapshot for " + name), "unexpected reply: " + msg);

        listener().onJoin(new PlayerJoinEvent(player, "join"));
        assertEquals(Material.DIAMOND, player.getInventory().getItem(0).getType());
        assertEquals(beforeX, player.getLocation().getX(), 0.001);
    }

    @Test
    void adminRestoreCommandRejectsUnknownPlayer() {
        assertTrue(server.dispatchCommand(console(), "hardcoreadmin restore NobodyHereEver"),
                "dispatch of hardcoreadmin restore failed");
        String msg = console().nextMessage();
        assertNotNull(msg, "expected a reply to /hardcoreadmin restore");
        assertTrue(msg.contains("No player named"), "unexpected reply: " + msg);
    }
}
