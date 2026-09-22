package com.wilderop.hardcorespawn;

import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Takes, persists, and restores pre-run player snapshots.
 * ItemStacks are encoded as Base64 Java-serialized lists of their
 * {@link org.bukkit.inventory.ItemStack#serialize()} maps (the 1.21
 * data-component form), so item metadata survives the round trip.
 */
public final class SnapshotManager {
    private final File file;
    private final Logger logger;
    private final Map<UUID, Snapshot> memory = new HashMap<>();

    public SnapshotManager(File dataFolder, Logger logger) {
        this.file = new File(dataFolder, "snapshots.yml");
        this.logger = logger;
    }

    /** Thrown when an inventory cannot be safely serialized (fail-fast: never lose items silently). */
    public static final class SnapshotException extends RuntimeException {
        SnapshotException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    // ------------------------------------------------------------------
    // Codec
    // ------------------------------------------------------------------

    /**
     * Encode an item array as Base64. Each non-empty slot is stored as its
     * {@link ItemStack#serialize()} map (the 1.21 data-component form, which
     * preserves metadata such as names, lore, and enchantments) inside a
     * plain Java-serialized list. The deprecated Bukkit object streams are
     * deliberately NOT used: since 1.21 they rely on the legacy {@code ==}
     * type key that {@code ItemStack.serialize()} no longer writes, so they
     * cannot deserialize items at all — on a real server or under MockBukkit.
     */
    static String encodeItems(ItemStack[] items) {
        List<Map<String, Object>> list = new ArrayList<>(items.length);
        for (ItemStack item : items) {
            if (item == null || item.getType().isAir()) {
                list.add(null);
            } else {
                try {
                    list.add(new HashMap<>(item.serialize()));
                } catch (Exception e) {
                    throw new SnapshotException("Could not serialize item " + item.getType(), e);
                }
            }
        }
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(list);
            out.flush();
            return Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (IOException e) {
            throw new SnapshotException("Could not encode inventory", e);
        }
    }

    @SuppressWarnings("unchecked")
    static ItemStack[] decodeItems(String base64, int size) {
        byte[] bytes = Base64.getDecoder().decode(base64);
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            List<Map<String, Object>> list = (List<Map<String, Object>>) in.readObject();
            ItemStack[] items = new ItemStack[size];
            for (int i = 0; i < Math.min(size, list.size()); i++) {
                Map<String, Object> map = list.get(i);
                items[i] = map == null ? null : ItemStack.deserialize(map);
            }
            return items;
        } catch (IOException | ClassNotFoundException e) {
            throw new SnapshotException("Could not decode inventory", e);
        }
    }

    // ------------------------------------------------------------------
    // Snapshot lifecycle
    // ------------------------------------------------------------------

    /** Capture the player's full state and persist it. Throws SnapshotException on failure. */
    public Snapshot takeSnapshot(Player player) {
        Snapshot s = new Snapshot();
        // Capture the cursor before closing the inventory below: closing must
        // not be allowed to eat it (MockBukkit's closeInventory nulls it,
        // though real servers keep it).
        s.cursorB64 = encodeItems(new ItemStack[]{player.getItemOnCursor()});
        // Close any open inventory first: the 2x2 crafting grid is not part of
        // the player inventory contents, so its items must be returned to the
        // inventory before the snapshot or they would bypass it.
        player.closeInventory();
        s.inventoryB64 = encodeItems(player.getInventory().getContents());
        s.armorB64 = encodeItems(player.getInventory().getArmorContents());
        s.offhandB64 = encodeItems(new ItemStack[]{player.getInventory().getItemInOffHand()});
        s.enderChestB64 = encodeItems(player.getEnderChest().getContents());
        s.expLevel = player.getLevel();
        s.expProgress = player.getExp();
        s.expTotal = player.getTotalExperience();
        Location loc = player.getLocation();
        s.worldName = loc.getWorld().getName();
        s.x = loc.getX();
        s.y = loc.getY();
        s.z = loc.getZ();
        s.yaw = loc.getYaw();
        s.pitch = loc.getPitch();

        memory.put(player.getUniqueId(), s);
        persist();
        return s;
    }

    /**
     * Restore a snapshot onto the player and forget it.
     * Health, food, and saturation are deliberately left untouched: restoring
     * must never heal the player, so quitting a run grants no advantage.
     * (Respawn already resets health through vanilla mechanics.)
     */
    public void restore(Player player, Snapshot s) {
        player.getInventory().setContents(decodeItems(s.inventoryB64, player.getInventory().getContents().length));
        player.getInventory().setArmorContents(decodeItems(s.armorB64, 4));
        ItemStack[] offhand = decodeItems(s.offhandB64, 1);
        player.getInventory().setItemInOffHand(offhand[0]);
        if (s.cursorB64 != null) { // snapshots predating the cursor fix have no entry
            ItemStack[] cursor = decodeItems(s.cursorB64, 1);
            player.setItemOnCursor(cursor[0]);
        }
        player.getEnderChest().setContents(decodeItems(s.enderChestB64, player.getEnderChest().getContents().length));
        if (s.expTotal >= 0) {
            // Restore the total first: setTotalExperience recalculates level
            // and progress from it, keeping all three consistent.
            player.setTotalExperience(s.expTotal);
        }
        // Re-assert the snapshotted level/progress on top: the total and the
        // level can disagree (e.g. setLevel without giveExp), and the visible
        // XP bar is level + progress, which is what the player expects back.
        // (For legacy snapshots without expTotal this is the whole restore.)
        player.setLevel(s.expLevel);
        player.setExp(s.expProgress);
        forget(player.getUniqueId());
    }

    /** Wipe inventory, armor, offhand, cursor, ender chest, and XP so the run starts with nothing. */
    public void clearPlayer(Player player) {
        player.closeInventory(); // return crafting-grid items to the inventory first
        player.getInventory().clear();
        player.getInventory().setArmorContents(new ItemStack[4]);
        player.getInventory().setItemInOffHand(null);
        player.setItemOnCursor(null);
        player.getEnderChest().clear();
        player.setLevel(0);
        player.setExp(0);
    }

    public boolean hasSnapshot(UUID id) {
        if (memory.containsKey(id)) {
            return true;
        }
        return loadAll().containsKey(id.toString());
    }

    /** Load the persisted snapshot without removing it. */
    public Snapshot peek(UUID id) {
        Snapshot s = memory.get(id);
        if (s != null) {
            return s;
        }
        Map<String, Object> raw = toStringMap(loadAll().get(id.toString()));
        if (raw == null) {
            return null;
        }
        Snapshot loaded = Snapshot.fromMap(raw);
        memory.put(id, loaded);
        return loaded;
    }

    /** Remove a snapshot from memory and disk. */
    public void forget(UUID id) {
        memory.remove(id);
        Map<String, Object> all = loadAll();
        if (all.remove(id.toString()) != null) {
            try {
                saveAll(all);
            } catch (SnapshotException e) {
                // Memory is already updated; a stale disk entry is only
                // resurrected on restart and is fail-open (never wipes).
                logger.severe("Could not update snapshots.yml: " + e.getMessage());
            }
        }
    }

    private void persist() {
        Map<String, Object> all = loadAll();
        for (Map.Entry<UUID, Snapshot> e : memory.entrySet()) {
            all.put(e.getKey().toString(), e.getValue().toMap());
        }
        saveAll(all);
    }

    private Map<String, Object> loadAll() {
        Map<String, Object> all = new HashMap<>();
        if (!file.exists()) {
            return all;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (String key : yaml.getKeys(false)) {
            ConfigurationSection section = yaml.getConfigurationSection(key);
            Map<String, Object> map = section != null ? sectionToMap(section) : toStringMap(yaml.get(key));
            if (map != null) {
                all.put(key, map);
            }
        }
        return all;
    }

    /**
     * Normalize whatever YAML handed back for one snapshot — a plain Map or a
     * ConfigurationSection — into a {@code Map<String, Object>}, or null if it
     * is neither.
     */
    private static Map<String, Object> toStringMap(Object raw) {
        if (raw instanceof Map<?, ?> m) {
            Map<String, Object> out = new HashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() != null) {
                    out.put(String.valueOf(e.getKey()), e.getValue());
                }
            }
            return out;
        }
        if (raw instanceof ConfigurationSection section) {
            return sectionToMap(section);
        }
        return null;
    }

    private static Map<String, Object> sectionToMap(ConfigurationSection section) {
        Map<String, Object> map = new HashMap<>();
        for (String key : section.getKeys(false)) {
            Object value = section.get(key);
            map.put(key, value instanceof ConfigurationSection cs ? sectionToMap(cs) : value);
        }
        return map;
    }

    private void saveAll(Map<String, Object> all) {
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            for (Map.Entry<String, Object> e : all.entrySet()) {
                yaml.set(e.getKey(), e.getValue());
            }
            file.getParentFile().mkdirs();
            yaml.save(file);
        } catch (IOException e) {
            // Fail loudly: takeSnapshot() propagates this so a run is never
            // started when its snapshot could not be persisted — otherwise a
            // crash before the next save would lose the only durable copy.
            throw new SnapshotException("Could not save snapshots.yml", e);
        }
    }
}
