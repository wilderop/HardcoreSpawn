package com.wilderop.hardcorespawn;

import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Start precondition: at least 2000 XZ blocks from 0,0, or fully empty. */
class StartRequirementsTest extends PluginTestBase {

    @Test
    void allowedWhenFarAwayWithItems() {
        PlayerMock player = newPlayer();
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 5));
        player.getInventory().setChestplate(new ItemStack(Material.IRON_CHESTPLATE));
        player.getEnderChest().setItem(0, new ItemStack(Material.GOLD_INGOT, 3));
        player.teleport(new Location(world, 2500, 65, 0)); // XZ distance 2500 >= 2000

        assertTrue(sessions().meetsStartRequirements(player));
        startRun(player);
        assertTrue(sessions().hasSession(player.getUniqueId()), "run should start when far away");
    }

    @Test
    void deniedWhenCloseWithItems() {
        PlayerMock player = newPlayer(); // (100, 65, 100): XZ distance ~141 < 2000
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 5));
        Location before = player.getLocation().clone();

        assertFalse(sessions().meetsStartRequirements(player));
        player.performCommand("hardcore");
        player.performCommand("hardcore confirm");

        assertFalse(sessions().hasSession(player.getUniqueId()),
                "run must not start when close to 0,0 with items");
        assertEquals(Material.DIAMOND, player.getInventory().getItem(0).getType(),
                "inventory must be untouched after denial");
        assertEquals(before.getX(), player.getLocation().getX(), 0.001,
                "player must not be teleported after denial");
    }

    @Test
    void deniedWhenCloseWithOnlyArmorOrEnderChest() {
        PlayerMock armored = newPlayer();
        armored.getInventory().setChestplate(new ItemStack(Material.IRON_CHESTPLATE));
        assertFalse(sessions().meetsStartRequirements(armored), "armor counts as carrying items");

        PlayerMock ender = newPlayer();
        ender.getEnderChest().setItem(0, new ItemStack(Material.DIAMOND, 1));
        assertFalse(sessions().meetsStartRequirements(ender),
                "ender chest contents count as carrying items");
    }

    @Test
    void allowedWhenCloseButFullyEmpty() {
        PlayerMock player = newPlayer(); // close to 0,0 but empty
        assertTrue(sessions().meetsStartRequirements(player));
        startRun(player);
        assertTrue(sessions().hasSession(player.getUniqueId()),
                "run should start when fully empty");
    }

    @Test
    void exactlyAtMinDistanceIsAllowed() {
        PlayerMock player = newPlayer();
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 1));
        player.teleport(new Location(world, 2000, 65, 0));
        assertTrue(sessions().meetsStartRequirements(player),
                "distance exactly equal to the minimum must pass");
    }

    @Test
    void retryAfterDenialWorks() {
        PlayerMock player = newPlayer();
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 5));
        player.performCommand("hardcore");
        player.performCommand("hardcore confirm");
        assertFalse(sessions().hasSession(player.getUniqueId()));

        // Empty everything, then confirm again on the still-pending request.
        player.getInventory().clear();
        player.getEnderChest().clear();
        assertTrue(sessions().meetsStartRequirements(player), "player should be fully empty now");
        player.performCommand("hardcore confirm");
        assertTrue(sessions().hasSession(player.getUniqueId()),
                "confirm should succeed once the requirement is met");
    }

    /** The distance rule only applies in the overworld; elsewhere only empty players may start. */
    @Test
    void netherLocationNeverSatisfiesDistanceRule() {
        World nether = server.createWorld(new WorldCreator("world_nether_test")
                .environment(World.Environment.NETHER));
        PlayerMock player = newPlayer();
        player.teleport(new Location(nether, 5000, 65, 5000)); // far, but not the overworld
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 5));

        assertFalse(sessions().meetsStartRequirements(player),
                "nether coordinates must not satisfy the overworld distance rule");
    }

    @Test
    void fullyEmptyPlayerMayStartOutsideOverworld() {
        World end = server.createWorld(new WorldCreator("world_the_end_test")
                .environment(World.Environment.THE_END));
        PlayerMock player = newPlayer();
        player.teleport(new Location(end, 5000, 65, 5000));

        assertTrue(sessions().meetsStartRequirements(player),
                "a fully empty player may start anywhere, including other dimensions");
    }
}
