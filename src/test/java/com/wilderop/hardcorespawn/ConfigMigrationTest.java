package com.wilderop.hardcorespawn;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.function.Supplier;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/** Stale configs must migrate: backup, settings preserved, messages refreshed. */
class ConfigMigrationTest extends PluginTestBase {

    private static final Logger LOG = Logger.getLogger("ConfigMigrationTest");

    private Supplier<InputStream> bundled() {
        return () -> ConfigMigrationTest.class.getResourceAsStream("/config.yml");
    }

    /** A v1.0.0-era config: no version stamp, custom setting, old message format. */
    private void writeStaleConfig(File dir) throws Exception {
        String stale = "quest-time-seconds: 600\n"
                + "messages:\n"
                + "  run-started: \"Hardcore run started! Quest 1: {quest} ({time} left)\"\n";
        Files.write(new File(dir, "config.yml").toPath(), stale.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void staleConfigMigrates(@TempDir File dir) throws Exception {
        writeStaleConfig(dir);

        assertTrue(ConfigMigrator.migrateIfNeeded(dir, LOG, bundled()), "migration should run");

        YamlConfiguration migrated = YamlConfiguration.loadConfiguration(new File(dir, "config.yml"));
        assertEquals(ConfigMigrator.CURRENT_VERSION, migrated.getInt("config-version"),
                "migrated config must carry the current version");
        assertEquals(600, migrated.getInt("quest-time-seconds"),
                "user settings must carry over");
        String runStarted = migrated.getString("messages.run-started");
        assertNotNull(runStarted);
        assertTrue(runStarted.contains("{quests}"),
                "messages must be refreshed to the new placeholder format, was: " + runStarted);

        File backup = new File(dir, "config.yml.bak");
        assertTrue(backup.exists(), "old config must be backed up");
        YamlConfiguration old = YamlConfiguration.loadConfiguration(backup);
        assertTrue(old.getString("messages.run-started").contains("{quest}"),
                "backup must hold the original stale config");

        assertFalse(ConfigMigrator.migrateIfNeeded(dir, LOG, bundled()),
                "a second run must be a no-op");
    }

    @Test
    void missingConfigIsCreated(@TempDir File dir) {
        assertFalse(ConfigMigrator.migrateIfNeeded(dir, LOG, bundled()),
                "fresh creation is not a migration");
        File config = new File(dir, "config.yml");
        assertTrue(config.exists(), "default config must be written");
        assertEquals(ConfigMigrator.CURRENT_VERSION,
                YamlConfiguration.loadConfiguration(config).getInt("config-version"));
    }

    @Test
    void currentConfigIsUntouched(@TempDir File dir) throws Exception {
        Files.copy(bundled().get(), new File(dir, "config.yml").toPath());
        assertFalse(ConfigMigrator.migrateIfNeeded(dir, LOG, bundled()),
                "a current config must not be migrated");
        assertFalse(new File(dir, "config.yml.bak").exists(), "no backup should be made");
    }

    @Test
    void v2ConfigGainsDiscordWebhookKey(@TempDir File dir) throws Exception {
        // A v2 config (pre-webhook, like a 1.4.0 server's) migrates to v3 and
        // gains the discord-webhook-url key with the empty default.
        String v2 = "config-version: 2\n"
                + "quest-time-seconds: 600\n"
                + "messages:\n"
                + "  run-started: \"old\"\n";
        Files.write(new File(dir, "config.yml").toPath(), v2.getBytes(StandardCharsets.UTF_8));

        assertTrue(ConfigMigrator.migrateIfNeeded(dir, LOG, bundled()), "migration should run");

        YamlConfiguration migrated = YamlConfiguration.loadConfiguration(new File(dir, "config.yml"));
        assertEquals(ConfigMigrator.CURRENT_VERSION, migrated.getInt("config-version"));
        assertEquals(600, migrated.getInt("quest-time-seconds"), "user settings must carry over");
        assertTrue(migrated.isSet("discord-webhook-url"),
                "migrated config must contain the discord-webhook-url key");
        assertEquals("", migrated.getString("discord-webhook-url"));
    }

    @Test
    void v3ConfigKeepsWebhookUrlOnMigration(@TempDir File dir) throws Exception {
        // A v3 config with a real webhook URL migrates to v4 without losing it.
        String v3 = "config-version: 3\n"
                + "discord-webhook-url: \"https://example.com/real-hook\"\n"
                + "messages:\n"
                + "  run-started: \"old\"\n";
        Files.write(new File(dir, "config.yml").toPath(), v3.getBytes(StandardCharsets.UTF_8));

        assertTrue(ConfigMigrator.migrateIfNeeded(dir, LOG, bundled()), "migration should run");

        YamlConfiguration migrated = YamlConfiguration.loadConfiguration(new File(dir, "config.yml"));
        assertEquals(ConfigMigrator.CURRENT_VERSION, migrated.getInt("config-version"));
        assertEquals("https://example.com/real-hook", migrated.getString("discord-webhook-url"),
                "webhook URL must survive migration");
        assertTrue(migrated.isSet("milestone-spawner-every"),
                "migrated config must contain the spawner milestone key");
    }

    @Test
    void manuallySetWebhookUrlSurvivesMigration(@TempDir File dir) throws Exception {
        String v2 = "config-version: 2\n"
                + "discord-webhook-url: \"https://example.com/manual-hook\"\n"
                + "messages:\n"
                + "  run-started: \"old\"\n";
        Files.write(new File(dir, "config.yml").toPath(), v2.getBytes(StandardCharsets.UTF_8));

        assertTrue(ConfigMigrator.migrateIfNeeded(dir, LOG, bundled()), "migration should run");

        YamlConfiguration migrated = YamlConfiguration.loadConfiguration(new File(dir, "config.yml"));
        assertEquals("https://example.com/manual-hook", migrated.getString("discord-webhook-url"),
                "a manually added webhook URL must carry over");
    }
}
