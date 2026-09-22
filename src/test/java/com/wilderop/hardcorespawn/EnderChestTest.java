package com.wilderop.hardcorespawn;

import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The ender chest is wiped at run start and blocked mid-run; only world chests may bank gains. */
class EnderChestTest extends PluginTestBase {

    @Test
    void enderChestBlockedDuringRun() {
        PlayerMock player = newPlayer();
        moveFarAway(player);
        startRun(player);

        player.openInventory(player.getEnderChest());

        assertNotEquals(player.getEnderChest(), player.getOpenInventory().getTopInventory(),
                "ender chest must not open during a run");
    }

    @Test
    void enderChestOpenOutsideRun() {
        PlayerMock player = newPlayer();
        moveFarAway(player);

        player.openInventory(player.getEnderChest());

        assertEquals(player.getEnderChest(), player.getOpenInventory().getTopInventory(),
                "ender chest must open normally outside a run");
    }

    @Test
    void enderChestWipedAtStartAndRestoredAtEnd() {
        PlayerMock player = newPlayer();
        moveFarAway(player);
        player.getEnderChest().setItem(0, new ItemStack(Material.DIAMOND, 7));
        startRun(player);

        assertNull(player.getEnderChest().getItem(0), "ender chest must be wiped at run start");

        player.performCommand("hardcore quit");

        assertEquals(Material.DIAMOND, player.getEnderChest().getItem(0).getType(),
                "ender chest contents must be restored when the run ends");
        assertEquals(7, player.getEnderChest().getItem(0).getAmount());
    }

    @Test
    void quittingGrantsNoHealing() {
        PlayerMock player = newPlayer();
        moveFarAway(player);
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 1));
        startRun(player);

        player.setHealth(8.0);
        player.setFoodLevel(6);
        player.setSaturation(0.0f);
        player.performCommand("hardcore quit");

        assertEquals(8.0, player.getHealth(), 0.001, "quit must not heal");
        assertEquals(6, player.getFoodLevel(), "quit must not refill hunger");
        assertEquals(0.0f, player.getSaturation(), 0.001f, "quit must not restore saturation");
    }
}
