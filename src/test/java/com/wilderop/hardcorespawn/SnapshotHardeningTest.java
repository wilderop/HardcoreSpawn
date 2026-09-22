package com.wilderop.hardcorespawn;

import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for the second hardening pass:
 * <ul>
 *   <li>the cursor counts toward the "fully empty" start-rule exception,</li>
 *   <li>total experience points survive the snapshot round-trip,</li>
 *   <li>old snapshots without the expTotal field still restore level/progress,</li>
 *   <li>a snapshot persistence failure fails the run start loudly instead of
 *       silently risking item loss.</li>
 * </ul>
 */
class SnapshotHardeningTest extends PluginTestBase {

    @Test
    void cursorItemDefeatsFullyEmptyStartException() {
        PlayerMock player = newPlayer(); // (100,65,100): inside the 2000-block radius
        assertTrue(sessions().meetsStartRequirements(player), "sanity: truly empty player may start near spawn");

        player.setItemOnCursor(new ItemStack(Material.DIAMOND, 2));
        assertFalse(sessions().meetsStartRequirements(player),
                "an item on the cursor means the player is not fully empty");
    }

    @Test
    void deniedStartNearSpawnWithCursorItem() {
        PlayerMock player = newPlayer();
        player.setItemOnCursor(new ItemStack(Material.DIAMOND, 2));
        player.performCommand("hardcore");
        player.performCommand("hardcore confirm");
        assertFalse(sessions().hasSession(player.getUniqueId()),
                "near-spawn start with a cursor item must be denied");
    }

    @Test
    void totalExperienceSurvivesSnapshotRoundTrip() {
        PlayerMock player = newPlayer();
        moveFarAway(player);
        player.setTotalExperience(150);
        int levelBefore = player.getLevel();

        startRun(player);
        // Gain some XP mid-run so the restore has something to overwrite.
        player.setTotalExperience(5);
        player.performCommand("hardcore quit");

        assertEquals(150, player.getTotalExperience(), "total XP must be restored");
        assertEquals(levelBefore, player.getLevel(), "XP level must be restored");
    }

    @Test
    void legacySnapshotWithoutExpTotalRestoresLevelAndProgress() {
        PlayerMock player = newPlayer();
        moveFarAway(player);
        SnapshotManager mgr = plugin.getSnapshotManager();

        Snapshot s = mgr.takeSnapshot(player);
        s.expTotal = -1; // simulate a snapshot written before the expTotal field
        s.expLevel = 7;
        s.expProgress = 0.25f;

        player.setTotalExperience(0);
        mgr.restore(player, s);

        assertEquals(7, player.getLevel(), "legacy snapshot must restore level");
        assertEquals(0.25f, player.getExp(), 0.001f, "legacy snapshot must restore progress");
    }

    @Test
    void snapshotPersistenceFailureThrowsInsteadOfSilentlyContinuing() throws Exception {
        // A data "folder" that is actually a file: no snapshot file can ever be
        // written beneath it.
        File fakeDir = File.createTempFile("hs-broken", ".tmp");
        try {
            SnapshotManager broken = new SnapshotManager(fakeDir, plugin.getLogger());
            PlayerMock player = newPlayer();
            moveFarAway(player);
            assertThrows(SnapshotManager.SnapshotException.class,
                    () -> broken.takeSnapshot(player),
                    "takeSnapshot must fail loudly when the snapshot cannot be persisted");
        } finally {
            fakeDir.delete();
        }
    }
}
