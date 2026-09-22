package com.wilderop.hardcorespawn;

import java.util.HashMap;
import java.util.Map;

/**
 * A player's pre-run state, fully serialized so it survives restarts.
 * All item arrays are Base64-encoded Java-serialized lists of
 * {@link org.bukkit.inventory.ItemStack#serialize()} maps.
 */
public final class Snapshot {
    public String inventoryB64;
    public String armorB64;
    public String offhandB64;
    public String cursorB64;
    public String enderChestB64;
    public int expLevel;
    public float expProgress;
    /** Total experience points; -1 when absent (snapshots predating this field). */
    public int expTotal = -1;
    public String worldName;
    public double x, y, z;
    public float yaw, pitch;

    public Map<String, Object> toMap() {
        Map<String, Object> m = new HashMap<>();
        m.put("inventory", inventoryB64);
        m.put("armor", armorB64);
        m.put("offhand", offhandB64);
        m.put("cursor", cursorB64);
        m.put("enderChest", enderChestB64);
        m.put("expLevel", expLevel);
        m.put("expProgress", expProgress);
        m.put("expTotal", expTotal);
        m.put("world", worldName);
        m.put("x", x);
        m.put("y", y);
        m.put("z", z);
        m.put("yaw", yaw);
        m.put("pitch", pitch);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static Snapshot fromMap(Map<String, Object> m) {
        Snapshot s = new Snapshot();
        s.inventoryB64 = (String) m.get("inventory");
        s.armorB64 = (String) m.get("armor");
        s.offhandB64 = (String) m.get("offhand");
        s.cursorB64 = (String) m.get("cursor"); // absent in snapshots predating the cursor fix
        s.enderChestB64 = (String) m.get("enderChest");
        s.expLevel = ((Number) m.getOrDefault("expLevel", 0)).intValue();
        Object ep = m.getOrDefault("expProgress", 0.0);
        s.expProgress = ep instanceof Number n ? n.floatValue() : 0f;
        Object et = m.getOrDefault("expTotal", -1);
        s.expTotal = et instanceof Number n ? n.intValue() : -1;
        s.worldName = (String) m.get("world");
        s.x = ((Number) m.getOrDefault("x", 0)).doubleValue();
        s.y = ((Number) m.getOrDefault("y", 0)).doubleValue();
        s.z = ((Number) m.getOrDefault("z", 0)).doubleValue();
        Object yaw = m.getOrDefault("yaw", 0f);
        s.yaw = yaw instanceof Number n ? n.floatValue() : 0f;
        Object pitch = m.getOrDefault("pitch", 0f);
        s.pitch = pitch instanceof Number n ? n.floatValue() : 0f;
        return s;
    }
}
