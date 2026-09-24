package com.wilderop.hardcorespawn;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Keeps config.yml in sync with the plugin across updates.
 *
 * <p>Bukkit never overwrites an existing config.yml, so after an update the
 * server can be left with stale message formats whose placeholders the new
 * code no longer fills (showing literal text like {@code {quest}} to
 * players), or missing quest templates the new code expects. To prevent
 * that: if the on-disk config's {@code config-version} is older than
 * {@link #CURRENT_VERSION}, the old file is backed up to
 * {@code config.yml.bak}, a fresh default is written, and the user's
 * non-message, non-quest settings are carried over. The {@code messages}
 * and {@code bands} sections are always refreshed so placeholders match the
 * code and new quest types are available.
 */
public final class ConfigMigrator {
    /** Bump this whenever the bundled config changes incompatibly. */
    public static final int CURRENT_VERSION = 14;

    private ConfigMigrator() {}

    /**
     * Ensure a current config.yml exists in the data folder, migrating a
     * stale one if needed.
     *
     * @param dataFolder        the plugin's data folder
     * @param logger            for migration warnings
     * @param defaultConfigYaml supplies the bundled config.yml stream
     * @return true if a migration ran
     */
    public static boolean migrateIfNeeded(File dataFolder, Logger logger,
                                          Supplier<InputStream> defaultConfigYaml) {
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            logger.severe("[HardcoreSpawn] Could not create data folder: " + dataFolder);
            return false;
        }
        File configFile = new File(dataFolder, "config.yml");
        if (!configFile.exists()) {
            writeDefault(configFile, defaultConfigYaml, logger);
            return false;
        }

        YamlConfiguration current = YamlConfiguration.loadConfiguration(configFile);
        if (current.getInt("config-version", 0) >= CURRENT_VERSION) {
            return false;
        }

        logger.warning("[HardcoreSpawn] config.yml is from an older version; migrating. "
                + "The old file is backed up to config.yml.bak. Your settings carry over, "
                + "but customized messages and quest bands are reset to the new format.");

        File backup = new File(dataFolder, "config.yml.bak");
        try {
            Files.move(configFile.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            logger.severe("[HardcoreSpawn] Could not back up old config.yml, aborting migration: " + e);
            return false;
        }

        YamlConfiguration fresh = writeDefault(configFile, defaultConfigYaml, logger);
        if (fresh == null) {
            return false;
        }
        // Carry over the user's settings; messages, quest bands, and the
        // version stamp always come from the fresh default.
        for (String key : fresh.getKeys(false)) {
            if (key.equals("messages") || key.equals("bands") || key.equals("config-version")) {
                continue;
            }
            if (current.isSet(key)) {
                fresh.set(key, current.get(key));
            }
        }
        try {
            fresh.save(configFile);
        } catch (IOException e) {
            logger.severe("[HardcoreSpawn] Could not save migrated config.yml: " + e);
            return false;
        }
        return true;
    }

    /** Write the bundled default config to the file; returns the parsed config, or null on failure. */
    private static YamlConfiguration writeDefault(File configFile,
                                                   Supplier<InputStream> defaultConfigYaml,
                                                   Logger logger) {
        try (InputStream in = defaultConfigYaml.get()) {
            if (in == null) {
                logger.severe("[HardcoreSpawn] Bundled config.yml not found in the jar.");
                return null;
            }
            Files.copy(in, configFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            logger.severe("[HardcoreSpawn] Could not write default config.yml: " + e);
            return null;
        }
        return YamlConfiguration.loadConfiguration(configFile);
    }
}
