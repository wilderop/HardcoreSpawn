package com.wilderop.hardcorespawn;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Typed access to config.yml with sane defaults. */
public final class HardcoreConfig {
    private final FileConfiguration cfg;

    public HardcoreConfig(FileConfiguration cfg) {
        this.cfg = cfg;
    }

    /** Load the bundled config.yml defaults (used by tests and as a fallback). */
    public static HardcoreConfig fromDefaults() {
        InputStream in = HardcoreConfig.class.getResourceAsStream("/config.yml");
        if (in == null) {
            throw new IllegalStateException("Bundled config.yml not found");
        }
        return new HardcoreConfig(YamlConfiguration.loadConfiguration(
                new InputStreamReader(in, StandardCharsets.UTF_8)));
    }

    public int getQuestTimeSeconds() {
        return cfg.getInt("quest-time-seconds", 300);
    }

    public double getDifficultyGrowth() {
        return cfg.getDouble("difficulty-growth", 1.3);
    }

    public int getMultiObjectiveMinLevel() {
        return cfg.getInt("multi-objective-min-level", 13);
    }

    public int getDisconnectGraceSeconds() {
        return cfg.getInt("disconnect-grace-seconds", 60);
    }

    /** XZ blocks from the center a player must be to start a run while carrying items. */
    public double getStartMinDistance() {
        return cfg.getDouble("start.min-distance", 2000.0);
    }

    public double getStartCenterX() {
        return cfg.getDouble("start.center-x", 0.0);
    }

    public double getStartCenterZ() {
        return cfg.getDouble("start.center-z", 0.0);
    }

    public double getTimeoutDamage() {
        return cfg.getDouble("timeout.damage", 1.0);
    }

    public int getTimeoutDamageIntervalSeconds() {
        return cfg.getInt("timeout.interval-seconds", 10);
    }

    public int getTimeoutKillAfterSeconds() {
        return cfg.getInt("timeout.kill-after-seconds", 30);
    }

    @SuppressWarnings("unchecked")
    public List<Map<?, ?>> getBandMaps() {
        return (List<Map<?, ?>>) cfg.getMapList("bands");
    }

    public String message(String key) {
        return cfg.getString("messages." + key, key);
    }

    public String prefix() {
        return cfg.getString("messages.prefix", "");
    }

    /** Fill placeholders like {quest}, {level}, {time} in a message. */
    public String format(String key, Map<String, String> placeholders) {
        String msg = prefix() + message(key);
        for (Map.Entry<String, String> e : placeholders.entrySet()) {
            msg = msg.replace("{" + e.getKey() + "}", e.getValue());
        }
        return msg;
    }

    public String formatTime(long millis) {
        long total = Math.max(0, millis / 1000);
        return String.format("%d:%02d", total / 60, total % 60);
    }

    /** Validate that every quest template references a real Material/EntityType. Returns warnings. */
    public List<String> validate() {
        List<String> warnings = new ArrayList<>();
        for (Map<?, ?> bandMap : getBandMaps()) {
            for (Map<?, ?> q : (List<Map<?, ?>>) (List<?>) bandMap.get("quests")) {
                String type = String.valueOf(q.get("type")).toUpperCase();
                String target = String.valueOf(q.get("target")).toUpperCase();
                try {
                    QuestType qt = QuestType.valueOf(type);
                    if (qt == QuestType.KILL) {
                        EntityType.valueOf(target);
                    } else {
                        Material.valueOf(target);
                    }
                } catch (IllegalArgumentException e) {
                    warnings.add("Invalid quest template " + q.get("id") + ": " + type + "/" + target);
                }
            }
        }
        return warnings;
    }
}
