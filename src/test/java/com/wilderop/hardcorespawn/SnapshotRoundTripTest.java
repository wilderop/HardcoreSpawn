package com.wilderop.hardcorespawn;

import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Snapshot take -> clear -> restore round-trip, including the ender chest. */
class SnapshotRoundTripTest extends PluginTestBase {

    private void assertStacksEqual(ItemStack expected, ItemStack actual, String slot) {
        if (expected == null || expected.getType().isAir()) {
            assertTrue(actual == null || actual.getType().isAir(), "slot " + slot + " should be empty");
            return;
        }
        assertNotNull(actual, "slot " + slot + " should hold " + expected.getType());
        assertEquals(expected.getType(), actual.getType(), "slot " + slot + " type");
        assertEquals(expected.getAmount(), actual.getAmount(), "slot " + slot + " amount");
    }

    @Test
    void snapshotClearRestoreRoundTrip() {
        PlayerMock player = newPlayer();

        ItemStack sword = new ItemStack(Material.DIAMOND_SWORD);
        ItemMeta meta = sword.getItemMeta();
        meta.setDisplayName("Run Ender");
        meta.addEnchant(Enchantment.SHARPNESS, 3, true);
        sword.setItemMeta(meta);

        player.getInventory().setItem(0, sword);
        player.getInventory().setItem(5, new ItemStack(Material.OAK_LOG, 32));
        player.getInventory().setChestplate(new ItemStack(Material.IRON_CHESTPLATE));
        player.getInventory().setItemInOffHand(new ItemStack(Material.TORCH, 16));
        player.getEnderChest().setItem(3, new ItemStack(Material.ENDER_PEARL, 8));
        player.setLevel(17);
        player.setExp(0.5f);

        SnapshotManager manager = plugin.getSnapshotManager();
        Snapshot snapshot = manager.takeSnapshot(player);
        assertNotNull(snapshot);
        assertTrue(manager.hasSnapshot(player.getUniqueId()), "snapshot should be persisted");

        manager.clearPlayer(player);
        assertNull(player.getInventory().getItem(0));
        assertEquals(0, player.getLevel());
        // Ender chest is wiped by clearPlayer (only world chests may bank run gains).
        assertNull(player.getEnderChest().getItem(3), "ender chest must be wiped by clearPlayer");

        manager.restore(player, snapshot);

        assertStacksEqual(sword, player.getInventory().getItem(0), "0");
        assertStacksEqual(new ItemStack(Material.OAK_LOG, 32), player.getInventory().getItem(5), "5");
        assertStacksEqual(new ItemStack(Material.IRON_CHESTPLATE),
                player.getInventory().getChestplate(), "chestplate");
        assertStacksEqual(new ItemStack(Material.TORCH, 16),
                player.getInventory().getItemInOffHand(), "offhand");
        assertStacksEqual(new ItemStack(Material.ENDER_PEARL, 8),
                player.getEnderChest().getItem(3), "ender-3");
        assertEquals(17, player.getLevel());
        assertFalse(manager.hasSnapshot(player.getUniqueId()), "restore should forget the snapshot");

        // Meta survived the round trip.
        ItemMeta restoredMeta = player.getInventory().getItem(0).getItemMeta();
        assertEquals("Run Ender", restoredMeta.getDisplayName());
        assertEquals(3, restoredMeta.getEnchantLevel(Enchantment.SHARPNESS));
    }

    @Test
    void codecHandlesEmptyAndNullSlots() {
        ItemStack[] items = new ItemStack[41];
        items[0] = new ItemStack(Material.STONE, 64);
        String encoded = SnapshotManager.encodeItems(items);
        ItemStack[] decoded = SnapshotManager.decodeItems(encoded, 41);
        assertEquals(41, decoded.length);
        assertEquals(Material.STONE, decoded[0].getType());
        assertEquals(64, decoded[0].getAmount());
        for (int i = 1; i < 41; i++) {
            assertNull(decoded[i], "slot " + i + " should be null");
        }
    }

    @Test
    void locationSurvivesInSnapshot() {
        PlayerMock player = newPlayer();
        Snapshot snapshot = plugin.getSnapshotManager().takeSnapshot(player);
        assertEquals("world", snapshot.worldName);
        assertEquals(100.0, snapshot.x, 0.001);
        plugin.getSnapshotManager().forget(player.getUniqueId());
        assertFalse(plugin.getSnapshotManager().hasSnapshot(player.getUniqueId()));
    }

    @Test
    void snapshotMapRoundTrip() {
        PlayerMock player = newPlayer();
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 3));
        SnapshotManager manager = plugin.getSnapshotManager();
        Snapshot original = manager.takeSnapshot(player);
        Snapshot copy = Snapshot.fromMap(original.toMap());
        assertEquals(original.inventoryB64, copy.inventoryB64);
        assertEquals(original.worldName, copy.worldName);
        assertEquals(original.x, copy.x, 0.0001);
        manager.forget(player.getUniqueId());
    }
}
