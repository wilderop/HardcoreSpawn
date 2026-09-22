package com.wilderop.hardcorespawn;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/** Per-player hardcore records, persisted to leaderboard.yml. */
public final class LeaderboardManager {

    public static final class Stats {
        public int bestLevel;
        public long totalRuns;
        public long totalQuests;
    }

    private final File file;
    private final Logger logger;
    private final Map<UUID, Stats> stats = new HashMap<>();

    public LeaderboardManager(File dataFolder, Logger logger) {
        this.file = new File(dataFolder, "leaderboard.yml");
        this.logger = logger;
        load();
    }

    public Stats stats(UUID id) {
        return stats.computeIfAbsent(id, k -> new Stats());
    }

    public void runStarted(UUID id) {
        stats(id).totalRuns++;
        save();
    }

    public void runFinished(UUID id, int level, int questsCompleted) {
        Stats s = stats(id);
        s.bestLevel = Math.max(s.bestLevel, level);
        s.totalQuests += questsCompleted;
        save();
    }

    private void load() {
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (String key : yaml.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                Stats s = new Stats();
                s.bestLevel = yaml.getInt(key + ".bestLevel");
                s.totalRuns = yaml.getLong(key + ".totalRuns");
                s.totalQuests = yaml.getLong(key + ".totalQuests");
                stats.put(id, s);
            } catch (IllegalArgumentException e) {
                logger.warning("Skipping invalid leaderboard entry: " + key);
            }
        }
    }

    public void save() {
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            for (Map.Entry<UUID, Stats> e : stats.entrySet()) {
                String key = e.getKey().toString();
                yaml.set(key + ".bestLevel", e.getValue().bestLevel);
                yaml.set(key + ".totalRuns", e.getValue().totalRuns);
                yaml.set(key + ".totalQuests", e.getValue().totalQuests);
            }
            file.getParentFile().mkdirs();
            yaml.save(file);
        } catch (IOException e) {
            logger.severe("Could not save leaderboard.yml: " + e.getMessage());
        }
    }
}
