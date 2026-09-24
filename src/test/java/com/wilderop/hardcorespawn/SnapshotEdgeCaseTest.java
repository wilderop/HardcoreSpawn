package com.wilderop.hardcorespawn;

import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for the snapshot edge-case fixes: the cursor item and the
 * crafting grid must be snapshotted/wiped/restored like everything else, a
 * dead player rejoining after a restart must get their restore at respawn
 * (not applied to the corpse on join), and a missing snapshot must never
 * cause an unrestorable inventory wipe.
 */
class SnapshotEdgeCaseTest extends PluginTestBase {

    @Test
    void cursorItemIsSnapshottedClearedAndRestored() {
        PlayerMock player = newPlayer();
        moveFarAway(player);
        player.setItemOnCursor(new ItemStack(Material.DIAMOND, 3));

        startRun(player);
        assertTrue(player.getItemOnCursor() == null || player.getItemOnCursor().getType().isAir(),
                "cursor must be cleared on run start");

        // Smuggle attempt: hold run gains on the cursor, then quit.
        player.setItemOnCursor(new ItemStack(Material.EMERALD, 9));
        player.performCommand("hardcore quit");

        ItemStack cursor = player.getItemOnCursor();
        assertNotNull(cursor, "pre-run cursor item must be restored");
        assertEquals(Material.DIAMOND, cursor.getType(), "cursor must hold the pre-run item, not run gains");
        assertEquals(3, cursor.getAmount());
    }

    @Test
    void deathDropsCursorItemAtDeathLocation() {
        PlayerMock player = newPlayer();
        moveFarAway(player);
        startRun(player);
        player.setItemOnCursor(new ItemStack(Material.EMERALD, 9));

        SessionListener listener = new SessionListener(sessions(), plugin.getSnapshotManager());
        listener.onDeath(new PlayerDeathEvent(player,
                DamageSource.builder(DamageType.GENERIC).build(), new ArrayList<>(), 0, "died"));

        boolean found = world.getEntities().stream()
                .filter(e -> e.getType() == org.bukkit.entity.EntityType.ITEM)
                .map(e -> (org.bukkit.entity.Item) e)
                .anyMatch(i -> i.getItemStack().getType() == Material.EMERALD);
        assertTrue(found, "cursor run gains must be dropped at the death location");
        assertTrue(player.getItemOnCursor() == null || player.getItemOnCursor().getType().isAir(),
                "cursor must be cleared after death");
    }

    @Test
    void deadPlayerRejoinAfterRestartDefersRestoreToRespawn() {
        PlayerMock player = newPlayer();
        moveFarAway(player);
        Location before = player.getLocation().clone();
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 5));
        startRun(player);

        // Die, then restart the server before respawning: shutdown converts
        // the pending respawn restore into an offline restore.
        SessionListener listener = new SessionListener(sessions(), plugin.getSnapshotManager());
        listener.onDeath(new PlayerDeathEvent(player,
                DamageSource.builder(DamageType.GENERIC).build(), new ArrayList<>(), 0, "died"));
        sessions().shutdown();

        // The player rejoins still dead (death screen).
        player.setHealth(0);
        assertTrue(player.isDead(), "test setup: player must be dead");
        server.getPluginManager().callEvent(new PlayerJoinEvent(player, ""));

        // The restore must NOT have been applied to the corpse on join...
        assertNull(player.getInventory().getItem(0),
                "restore must be deferred while the player is dead");

        // ...the deferred task notices the player is still dead and routes
        // the restore to the respawn queue...
        server.getScheduler().performTicks(150);

        // ...but it must still apply when they finally respawn.
        PlayerRespawnEvent respawnEvent =
                new PlayerRespawnEvent(player, new Location(world, 0, 70, 0), false);
        listener.onRespawn(respawnEvent);

        assertEquals(before.getX(), respawnEvent.getRespawnLocation().getX(), 0.001);
        assertNotNull(player.getInventory().getItem(0), "snapshot must be restored on respawn");
        assertEquals(Material.DIAMOND, player.getInventory().getItem(0).getType());
    }

    @Test
    void missingSnapshotNeverWipesInventory() {
        PlayerMock player = newPlayer();
        moveFarAway(player);
        startRun(player);

        // Corrupt state: the snapshot vanished mid-run.
        plugin.getSnapshotManager().forget(player.getUniqueId());
        player.getInventory().setItem(0, new ItemStack(Material.EMERALD, 12));

        // Quitting must fail open (keep everything) rather than destroy the
        // player's inventory with nothing to restore it from.
        player.performCommand("hardcore quit");

        assertFalse(sessions().hasSession(player.getUniqueId()), "run must still end");
        assertNotNull(player.getInventory().getItem(0),
                "inventory must not be wiped when no snapshot exists");
        assertEquals(Material.EMERALD, player.getInventory().getItem(0).getType());
    }
}
