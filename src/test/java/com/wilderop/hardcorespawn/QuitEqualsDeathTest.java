package com.wilderop.hardcorespawn;

import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * quit == death: both funnel through the unified exit path —
 * snapshot restored, run gains forfeited, back at the pre-run location.
 */
class QuitEqualsDeathTest extends PluginTestBase {

    private void gearUp(PlayerMock player) {
        moveFarAway(player); // runs carrying items must start >= 2000 blocks from 0,0
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 5)); // pre-run wealth
        player.getInventory().setChestplate(new ItemStack(Material.DIAMOND_CHESTPLATE));
        player.setLevel(9);
    }

    @Test
    void quitRestoresSnapshotAndForfeitsRunGains() {
        PlayerMock player = newPlayer();
        gearUp(player);
        Location before = player.getLocation().clone();
        startRun(player);

        // Earn something during the run, then quit.
        player.getInventory().setItem(0, new ItemStack(Material.EMERALD, 12));
        player.performCommand("hardcore quit");

        assertFalse(sessions().hasSession(player.getUniqueId()));
        assertEquals(Material.DIAMOND, player.getInventory().getItem(0).getType(),
                "snapshot item must be back");
        assertEquals(5, player.getInventory().getItem(0).getAmount());
        assertNotNull(player.getInventory().getChestplate(), "armor must be restored");
        assertEquals(9, player.getLevel(), "XP must be restored");
        assertEquals(before.getX(), player.getLocation().getX(), 0.001, "must return to pre-run location");
        assertEquals(1, sessions().getLeaderboard().stats(player.getUniqueId()).totalRuns);
    }

    @Test
    void deathDropsRunGainsAndRestoresOnRespawn() {
        PlayerMock player = newPlayer();
        gearUp(player);
        Location before = player.getLocation().clone();
        startRun(player);

        // Earn something during the run, then die.
        player.getInventory().setItem(3, new ItemStack(Material.EMERALD, 12));
        SessionListener listener = new SessionListener(sessions(), plugin.getSnapshotManager());
        PlayerDeathEvent death = new PlayerDeathEvent(player,
                DamageSource.builder(DamageType.GENERIC).build(), new ArrayList<>(), 0, "died");
        listener.onDeath(death);

        assertTrue(death.getDrops().isEmpty(), "vanilla drops must be cleared");
        assertFalse(sessions().hasSession(player.getUniqueId()), "session must end on death");

        // Respawn restores the snapshot at the pre-run location.
        Location respawn = new Location(world, 0, 70, 0);
        PlayerRespawnEvent respawnEvent = new PlayerRespawnEvent(player, respawn, false);
        listener.onRespawn(respawnEvent);

        assertEquals(before.getX(), respawnEvent.getRespawnLocation().getX(), 0.001);
        assertEquals(Material.DIAMOND, player.getInventory().getItem(0).getType(),
                "snapshot item must be back after respawn");
        assertNull(player.getInventory().getItem(3), "run gains must be gone");
        assertEquals(9, player.getLevel());
    }

    @Test
    void runGainsAreDroppedAtDeathLocation() {
        PlayerMock player = newPlayer();
        gearUp(player);
        startRun(player);
        player.getInventory().setItem(3, new ItemStack(Material.EMERALD, 12));

        SessionListener listener = new SessionListener(sessions(), plugin.getSnapshotManager());
        listener.onDeath(new PlayerDeathEvent(player,
                DamageSource.builder(DamageType.GENERIC).build(), new ArrayList<>(), 0, "died"));

        // The emerald gains were dropped into the world at the death spot.
        boolean foundEmeralds = world.getEntities().stream()
                .filter(e -> e.getType() == org.bukkit.entity.EntityType.ITEM)
                .map(e -> (org.bukkit.entity.Item) e)
                .anyMatch(i -> i.getItemStack().getType() == Material.EMERALD);
        assertTrue(foundEmeralds, "run gains should be dropped as items at the death location");
    }
}
