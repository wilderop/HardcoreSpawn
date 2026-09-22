package com.wilderop.hardcorespawn;

import org.bukkit.plugin.java.JavaPlugin;

/** HardcoreSpawn — endless escalating hardcore quest mode. */
public class HardcoreSpawn extends JavaPlugin {
    private HardcoreConfig hardcoreConfig;
    private SnapshotManager snapshotManager;
    private QuestGenerator questGenerator;
    private LeaderboardManager leaderboardManager;
    private SessionManager sessionManager;
    private TimerTask timerTask;
    private HudService hudService = new BukkitHudService();

    @Override
    public void onEnable() {
        // Migrates a stale config.yml (backup + settings preserved) instead of
        // leaving old message formats with placeholders the code no longer fills.
        ConfigMigrator.migrateIfNeeded(getDataFolder(), getLogger(), () -> getResource("config.yml"));
        reloadHardcoreConfig();

        snapshotManager = new SnapshotManager(getDataFolder(), getLogger());
        leaderboardManager = new LeaderboardManager(getDataFolder(), getLogger());
        sessionManager = new SessionManager(this, hardcoreConfig, snapshotManager,
                questGenerator, leaderboardManager, hudService);
        sessionManager.loadSessions();
        sessionManager.loadRestores();

        getServer().getPluginManager().registerEvents(new QuestListener(sessionManager), this);
        getServer().getPluginManager().registerEvents(
                new SessionListener(sessionManager, snapshotManager), this);
        getServer().getPluginManager().registerEvents(
                new StartFreezeListener(sessionManager), this);

        getCommand("hardcore").setExecutor(new HardcoreCommand(sessionManager));
        getCommand("hardcoreadmin").setExecutor(new HardcoreAdminCommand(this, sessionManager));

        timerTask = new TimerTask(sessionManager);
        timerTask.runTaskTimer(this, 20L, 20L);

        getLogger().info("HardcoreSpawn enabled: type /hardcore if you dare.");
    }

    @Override
    public void onDisable() {
        if (timerTask != null) {
            timerTask.cancel();
        }
        if (sessionManager != null) {
            sessionManager.shutdown();
        }
        if (leaderboardManager != null) {
            leaderboardManager.save();
        }
        getLogger().info("HardcoreSpawn disabled.");
    }

    /** Reload config.yml and rebuild the quest generator. */
    public void reloadHardcoreConfig() {
        reloadConfig();
        hardcoreConfig = new HardcoreConfig(getConfig());
        for (String warning : hardcoreConfig.validate()) {
            getLogger().warning(warning);
        }
        questGenerator = QuestGenerator.fromConfig(hardcoreConfig);
        if (sessionManager != null) {
            sessionManager.setConfig(hardcoreConfig, questGenerator);
        }
    }

    // ------------------------------------------------------------------
    // Accessors (getters used by tests)
    // ------------------------------------------------------------------

    public SessionManager getSessionManager() { return sessionManager; }
    public SnapshotManager getSnapshotManager() { return snapshotManager; }
    public LeaderboardManager getLeaderboardManager() { return leaderboardManager; }
    public QuestGenerator getQuestGenerator() { return questGenerator; }
    public HardcoreConfig getHardcoreConfig() { return hardcoreConfig; }

    /** Test seam: swap the HUD implementation (e.g. for a headless mock server). */
    void setHudService(HudService hudService) {
        this.hudService = hudService;
        if (sessionManager != null) {
            sessionManager.setHudService(hudService);
        }
    }
}
