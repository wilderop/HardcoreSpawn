package com.wilderop.hardcorespawn;

import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** /hardcore -> /hardcore confirm flow: snapshot, clear, scatter into the wilds. */
class SessionFlowTest extends PluginTestBase {

    @Test
    void confirmWithoutRequestExpires() {
        PlayerMock player = newPlayer();
        assertFalse(sessions().hasSession(player.getUniqueId()));
        player.performCommand("hardcore confirm");
        assertFalse(sessions().hasSession(player.getUniqueId()), "confirm without request must not start a run");
    }

    @Test
    void fullStartFlowSnapshotsClearsAndTeleports() {
        PlayerMock player = newPlayer();
        moveFarAway(player); // runs carrying items must start >= 2000 blocks from 0,0
        Location before = player.getLocation().clone();

        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 5));
        player.getInventory().setChestplate(new ItemStack(Material.DIAMOND_CHESTPLATE));
        player.getInventory().setItemInOffHand(new ItemStack(Material.SHIELD));
        player.getEnderChest().setItem(0, new ItemStack(Material.GOLD_INGOT, 7));
        player.setLevel(11);

        startRun(player);

        Session session = sessions().getSession(player.getUniqueId());
        assertNotNull(session);
        assertEquals(Session.HAND_SIZE, session.hand.size(), "a full hand of quests should be dealt");
        for (Quest q : session.hand) {
            assertEquals(1, q.level());
        }
        assertTrue(session.questDeadlineMs > System.currentTimeMillis());

        // Inventory cleared, armor + offhand cleared, XP cleared.
        assertNull(player.getInventory().getItem(0));
        assertNull(player.getInventory().getChestplate());
        assertTrue(player.getInventory().getItemInOffHand() == null
                || player.getInventory().getItemInOffHand().getType().isAir());
        assertEquals(0, player.getLevel());

        // Ender chest wiped too (it is snapshotted and restored at run end).
        assertNull(player.getEnderChest().getItem(0), "ender chest must be wiped on run start");

        // Scattered into the wilds: inside the scatter ring, far from spawn.
        assertEquals("world", player.getLocation().getWorld().getName());
        double scatterDist = Math.hypot(player.getLocation().getX(), player.getLocation().getZ());
        assertTrue(scatterDist >= 2000.0 && scatterDist <= 6000.0,
                "run must start inside the scatter ring, got dist=" + scatterDist);

        // Return location recorded.
        assertEquals(before.getWorld().getName(), session.returnLocation.getWorld().getName());
        assertEquals(before.getX(), session.returnLocation.getX(), 0.001);

        // Leaderboard recorded the run.
        assertEquals(1, sessions().getLeaderboard().stats(player.getUniqueId()).totalRuns);

        // Double-start is refused.
        player.performCommand("hardcore");
        assertNotNull(sessions().getSession(player.getUniqueId()));
    }

    @Test
    void statusShowsRunInfo() {
        PlayerMock player = newPlayer();
        startRun(player);
        assertTrue(player.performCommand("hardcore status"));
    }
}
