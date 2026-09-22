package com.wilderop.hardcorespawn;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Owns every hardcore run: start/confirm flow, quest assignment and progress,
 * the unified exit path, disconnect handling, and persistence.
 */
public final class SessionManager {

    /** A snapshot restore waiting for a respawn or a rejoin. */
    public record PendingRestore(Snapshot snapshot, Location returnLocation, String messageKey, int level) {}

    private final HardcoreSpawn plugin;
    private HardcoreConfig config;
    private final SnapshotManager snapshots;
    private QuestGenerator quests;
    private final LeaderboardManager leaderboard;
    private HudService hud;

    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<UUID, Long> pendingConfirms = new HashMap<>();
    private final Map<UUID, PendingRestore> pendingRespawnRestores = new HashMap<>();
    private final Map<UUID, PendingRestore> pendingOfflineRestores = new HashMap<>();

    public SessionManager(HardcoreSpawn plugin, HardcoreConfig config, SnapshotManager snapshots,
                          QuestGenerator quests, LeaderboardManager leaderboard, HudService hud) {
        this.plugin = plugin;
        this.config = config;
        this.snapshots = snapshots;
        this.quests = quests;
        this.leaderboard = leaderboard;
        this.hud = hud;
    }

    public void setConfig(HardcoreConfig config, QuestGenerator quests) {
        this.config = config;
        this.quests = quests;
    }

    /** Test seam: swap the HUD (e.g. for a headless mock server). */
    public void setHudService(HudService hud) {
        this.hud = hud;
    }

    public HardcoreConfig getConfig() { return config; }
    public HudService getHud() { return hud; }
    public SnapshotManager getSnapshots() { return snapshots; }
    public LeaderboardManager getLeaderboard() { return leaderboard; }

    public boolean hasSession(UUID id) { return sessions.containsKey(id); }
    public Session getSession(UUID id) { return sessions.get(id); }

    /** Copy of active sessions for safe iteration. */
    public List<Session> sessionsSnapshot() { return new ArrayList<>(sessions.values()); }

    public PendingRestore consumeRespawnRestore(UUID id) { return pendingRespawnRestores.remove(id); }

    // ------------------------------------------------------------------
    // Start flow
    // ------------------------------------------------------------------

    public void requestStart(Player player) {
        UUID id = player.getUniqueId();
        if (sessions.containsKey(id)) {
            player.sendMessage(config.format("already-running", Map.of()));
            return;
        }
        pendingConfirms.put(id, System.currentTimeMillis());
        player.sendMessage(config.format("confirm-prompt", startPlaceholders()));
    }

    public boolean confirmStart(Player player) {
        UUID id = player.getUniqueId();
        Long requested = pendingConfirms.get(id);
        long now = System.currentTimeMillis();
        if (requested == null || now - requested > 60_000) {
            pendingConfirms.remove(id);
            player.sendMessage(config.format("confirm-expired", Map.of()));
            return false;
        }
        if (sessions.containsKey(id)) {
            player.sendMessage(config.format("already-running", Map.of()));
            return false;
        }
        if (!meetsStartRequirements(player)) {
            // Keep the pending confirmation so the player can meet the
            // requirement (travel out or empty up) and confirm again.
            player.sendMessage(config.format("start-denied-too-close", startPlaceholders()));
            return false;
        }
        pendingConfirms.remove(id);
        Snapshot snapshot;
        try {
            snapshot = snapshots.takeSnapshot(player);
        } catch (SnapshotManager.SnapshotException e) {
            plugin.getLogger().severe("Snapshot failed for " + player.getName() + ": " + e.getMessage());
            player.sendMessage(config.prefix() + "§cCould not safely store your inventory. Run cancelled.");
            return false;
        }

        Session session = new Session(id, player.getLocation().clone());
        sessions.put(id, session);
        snapshots.clearPlayer(player);

        Location spawn = spawnLocation();
        player.teleport(spawn);
        // No free healing: the run starts with whatever health/hunger the
        // player had. (Restoring never heals either, so quitting a run grants
        // no advantage.)

        leaderboard.runStarted(id);
        assignQuest(player, session, now);
        return true;
    }

    /**
     * Start precondition: the player's XZ distance from the configured center
     * <em>in the overworld</em> must be at least the configured minimum —
     * unless their inventory (storage, armor, offhand) AND ender chest are
     * both completely empty. Outside the overworld the distance rule cannot be
     * satisfied, so only a fully empty player may start.
     */
    boolean meetsStartRequirements(Player player) {
        Location loc = player.getLocation();
        World world = loc.getWorld();
        double min = config.getStartMinDistance();
        if (world != null && world.getEnvironment() == World.Environment.NORMAL) {
            double dx = loc.getX() - config.getStartCenterX();
            double dz = loc.getZ() - config.getStartCenterZ();
            if (dx * dx + dz * dz >= min * min) {
                return true;
            }
        }
        return isFullyEmpty(player);
    }

    private boolean isFullyEmpty(Player player) {
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item != null && !item.getType().isAir()) {
                return false;
            }
        }
        for (ItemStack item : player.getInventory().getArmorContents()) {
            if (item != null && !item.getType().isAir()) {
                return false;
            }
        }
        ItemStack offhand = player.getInventory().getItemInOffHand();
        if (offhand != null && !offhand.getType().isAir()) {
            return false;
        }
        for (ItemStack item : player.getEnderChest().getContents()) {
            if (item != null && !item.getType().isAir()) {
                return false;
            }
        }
        return true;
    }

    private Map<String, String> startPlaceholders() {
        return Map.of(
                "radius", formatCoord(config.getStartMinDistance()),
                "center-x", formatCoord(config.getStartCenterX()),
                "center-z", formatCoord(config.getStartCenterZ()));
    }

    private static String formatCoord(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    private Location spawnLocation() {
        World world = Bukkit.getWorld("world");
        if (world == null && !Bukkit.getWorlds().isEmpty()) {
            world = Bukkit.getWorlds().get(0);
        }
        if (world == null) {
            throw new IllegalStateException("No world loaded");
        }
        Location spawn = world.getSpawnLocation().clone();
        spawn.add(0.5, 0.5, 0.5);
        return spawn;
    }

    // ------------------------------------------------------------------
    // Quests
    // ------------------------------------------------------------------

    private void assignQuest(Player player, Session session, long now) {
        List<String> last = session.quest == null ? List.of() : session.quest.templateIds();
        Quest quest = quests.generate(session.level + 1, last);
        session.quest = quest;
        long questMs = config.getQuestTimeSeconds() * 1000L;
        session.questDeadlineMs = now + questMs;
        session.warned60 = false;
        session.warned30 = false;
        session.timeoutDamagePhase = false;
        hud.showRunHud(player, quest.level(), questMs);
        String key = session.level == 0 ? "run-started" : "new-quest";
        player.sendMessage(config.format(key, Map.of(
                "quest", quest.getDescription(),
                "time", config.formatTime(questMs),
                "level", String.valueOf(quest.level()))));
    }

    public void addProgress(Player player, QuestType type, String target, int amount) {
        Session s = sessions.get(player.getUniqueId());
        if (s == null || s.quest == null) {
            return;
        }
        if (s.quest.progress(type, target, amount)) {
            if (s.quest.isComplete()) {
                advanceQuest(player);
            } else {
                hud.updateHud(player, s.quest.level(), s.questDeadlineMs - System.currentTimeMillis());
            }
        }
    }

    /** Scan the inventory for OBTAIN objectives. Called every tick. */
    public void checkObtain(Player player) {
        Session s = sessions.get(player.getUniqueId());
        if (s == null || s.quest == null) {
            return;
        }
        boolean moved = false;
        for (QuestObjective o : s.quest.objectives()) {
            if (o.type() == QuestType.OBTAIN && !o.isComplete()) {
                int count = countMaterial(player, o.target());
                if (count != o.progress()) {
                    o.setProgress(count);
                    moved = true;
                }
            }
        }
        if (moved && s.quest.isComplete()) {
            advanceQuest(player);
        }
    }

    private int countMaterial(Player player, String materialName) {
        final Material material;
        try {
            material = Material.valueOf(materialName);
        } catch (IllegalArgumentException e) {
            return 0;
        }
        int count = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.getType() == material) {
                count += item.getAmount();
            }
        }
        return count;
    }

    public void advanceQuest(Player player) {
        Session s = sessions.get(player.getUniqueId());
        if (s == null) {
            return;
        }
        s.level++;
        s.questsCompleted++;
        player.sendMessage(config.format("quest-complete", Map.of()));
        assignQuest(player, s, System.currentTimeMillis());
    }

    // ------------------------------------------------------------------
    // Unified exit path
    // ------------------------------------------------------------------

    /**
     * End a run for any reason. Restores the pre-run snapshot, forfeits run
     * gains (dropped at the death site for in-world death, deleted otherwise),
     * returns the player to their pre-run location, and records the level.
     */
    public void endRun(UUID id, ExitCause cause) {
        Session s = sessions.remove(id);
        if (s == null) {
            return;
        }
        pendingConfirms.remove(id);
        Player player = Bukkit.getPlayer(id);
        Snapshot snapshot = snapshots.peek(id);
        int reached = reachedLevel(s);
        String messageKey = switch (cause) {
            case QUIT, ADMIN_RESET -> "quit";
            case DISCONNECT_TIMEOUT -> "disconnect-death";
            case IN_WORLD_DEATH -> "death";
        };

        if (player != null && player.isOnline()) {
            if (cause == ExitCause.IN_WORLD_DEATH) {
                // Drops were handled by the death listener; restore on respawn.
                pendingRespawnRestores.put(id, new PendingRestore(snapshot, s.returnLocation, messageKey, reached));
                saveRestores();
            } else {
                snapshots.clearPlayer(player);
                if (snapshot != null) {
                    snapshots.restore(player, snapshot);
                }
                player.teleport(s.returnLocation);
                player.sendMessage(config.format(messageKey, Map.of("level", String.valueOf(reached))));
            }
        } else if (snapshot != null) {
            // Offline: the snapshot was persisted at run start; restore on next join.
            pendingOfflineRestores.put(id, new PendingRestore(snapshot, s.returnLocation, messageKey, reached));
            saveRestores();
        }

        hud.hideHud(id);
        leaderboard.runFinished(id, reached, s.questsCompleted);
        saveSessions();
    }

    /**
     * The level the player actually reached (the current quest's level), used
     * for messages, the leaderboard, and restores. Dying on quest 1 counts as
     * reaching level 1, not level 0.
     */
    static int reachedLevel(Session s) {
        return s.quest != null ? s.quest.level() : s.level;
    }

    // ------------------------------------------------------------------
    // Disconnect handling
    // ------------------------------------------------------------------

    public void handleQuit(PlayerQuitEvent event) {
        Session s = sessions.get(event.getPlayer().getUniqueId());
        if (s != null) {
            s.offlineSinceMs = System.currentTimeMillis();
            hud.hideHud(event.getPlayer().getUniqueId());
            saveSessions();
        }
    }

    public void handleJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();

        PendingRestore restore = pendingOfflineRestores.remove(id);
        if (restore != null) {
            saveRestores();
            applyRestore(player, restore);
            return;
        }

        Session s = sessions.get(id);
        if (s == null) {
            return;
        }
        long now = System.currentTimeMillis();
        long graceMs = config.getDisconnectGraceSeconds() * 1000L;
        if (s.questDeadlineMs == 0) {
            // Paused by a restart: neither the quest clock nor the offline
            // clock counted the downtime. Resume (or time out on the frozen
            // offline time) now that the player is back.
            if (s.pausedOfflineElapsedMs > graceMs) {
                endRun(id, ExitCause.DISCONNECT_TIMEOUT);
            } else {
                s.questDeadlineMs = now + Math.max(0, s.pausedQuestRemainingMs);
                s.pausedQuestRemainingMs = 0;
                s.pausedOfflineElapsedMs = 0;
                s.offlineSinceMs = 0;
                if (s.timeoutDamagePhase) {
                    s.nextDamageMs = now; // don't punish the restart gap
                }
                if (s.quest != null) {
                    hud.showRunHud(player, s.quest.level(), s.questDeadlineMs - now);
                }
                player.sendMessage(config.format("welcome-back-paused", Map.of()));
            }
            saveSessions();
            return;
        }
        if (s.offlineSinceMs != 0 && now - s.offlineSinceMs > graceMs) {
            s.offlineSinceMs = 0; // online again so endRun takes the online path
            endRun(id, ExitCause.DISCONNECT_TIMEOUT);
        } else {
            s.offlineSinceMs = 0;
            if (s.quest != null) {
                hud.showRunHud(player, s.quest.level(), s.questDeadlineMs - now);
            }
            player.sendMessage(config.format("welcome-back", Map.of()));
            saveSessions();
        }
    }

    private void applyRestore(Player player, PendingRestore restore) {
        snapshots.clearPlayer(player);
        if (restore.snapshot() != null) {
            snapshots.restore(player, restore.snapshot());
        }
        player.teleport(restore.returnLocation());
        player.sendMessage(config.format(restore.messageKey(), Map.of("level", String.valueOf(restore.level()))));
    }

    /** Called by the timer for sessions whose player is offline. */
    public void checkOfflineTimeout(Session s, long now) {
        if (s.questDeadlineMs == 0) {
            return; // paused across a restart: frozen until the player rejoins
        }
        if (s.offlineSinceMs != 0 && now - s.offlineSinceMs > config.getDisconnectGraceSeconds() * 1000L) {
            endRun(s.playerId, ExitCause.DISCONNECT_TIMEOUT);
        }
    }

    // ------------------------------------------------------------------
    // Misc
    // ------------------------------------------------------------------

    /**
     * While a run is active, every command except the plugin's own is blocked.
     * A blocklist can never be complete; hardcore means no teleports, homes,
     * warps, or impostor commands, period.
     */
    public boolean isCommandBlocked(UUID id, String commandLine) {
        if (!sessions.containsKey(id)) {
            return false;
        }
        String label = commandLine.startsWith("/") ? commandLine.substring(1) : commandLine;
        int space = label.indexOf(' ');
        if (space >= 0) {
            label = label.substring(0, space);
        }
        int colon = label.indexOf(':');
        if (colon >= 0) {
            label = label.substring(colon + 1);
        }
        String root = label.toLowerCase();
        return !root.equals("hardcore") && !root.equals("hardcoreadmin");
    }

    public void sendStatus(Player player) {
        Session s = sessions.get(player.getUniqueId());
        if (s == null || s.quest == null) {
            player.sendMessage(config.format("no-session", Map.of()));
            return;
        }
        LeaderboardManager.Stats stats = leaderboard.stats(player.getUniqueId());
        player.sendMessage(config.format("status", Map.of(
                "level", String.valueOf(reachedLevel(s)),
                "quest", s.quest.getDescription() + " §7(" + s.quest.getProgressText() + ")",
                "time", config.formatTime(s.questDeadlineMs - System.currentTimeMillis()),
                "best", String.valueOf(stats.bestLevel),
                "runs", String.valueOf(stats.totalRuns))));
    }

    /** Mark all sessions offline and persist (called on disable). */
    public void shutdown() {
        long now = System.currentTimeMillis();
        for (Session s : sessions.values()) {
            if (s.questDeadlineMs != 0) {
                // Freeze the quest clock: restart downtime must not count.
                s.pausedQuestRemainingMs = Math.max(0, s.questDeadlineMs - now);
                s.questDeadlineMs = 0;
            }
            if (s.offlineSinceMs != 0) {
                // Freeze the disconnect clock too.
                s.pausedOfflineElapsedMs = now - s.offlineSinceMs;
                s.offlineSinceMs = 0;
            }
        }
        // After a restart nobody is mid-respawn; pending respawn restores
        // become offline restores applied on rejoin.
        pendingOfflineRestores.putAll(pendingRespawnRestores);
        pendingRespawnRestores.clear();
        saveSessions();
        saveRestores();
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    private File sessionsFile() {
        return new File(plugin.getDataFolder(), "sessions.yml");
    }

    private File restoresFile() {
        return new File(plugin.getDataFolder(), "restores.yml");
    }

    public void saveSessions() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Session s : sessions.values()) {
            String key = s.playerId.toString();
            yaml.set(key + ".level", s.level);
            yaml.set(key + ".questsCompleted", s.questsCompleted);
            yaml.set(key + ".offlineSinceMs", s.offlineSinceMs);
            yaml.set(key + ".deadlineMs", s.questDeadlineMs);
            yaml.set(key + ".pausedRemainingMs", s.pausedQuestRemainingMs);
            yaml.set(key + ".pausedOfflineMs", s.pausedOfflineElapsedMs);
            yaml.set(key + ".warned60", s.warned60);
            yaml.set(key + ".warned30", s.warned30);
            yaml.set(key + ".timeoutDamagePhase", s.timeoutDamagePhase);
            yaml.set(key + ".nextDamageMs", s.nextDamageMs);
            Location r = s.returnLocation;
            yaml.set(key + ".return.world", r.getWorld().getName());
            yaml.set(key + ".return.x", r.getX());
            yaml.set(key + ".return.y", r.getY());
            yaml.set(key + ".return.z", r.getZ());
            yaml.set(key + ".return.yaw", r.getYaw());
            yaml.set(key + ".return.pitch", r.getPitch());
            if (s.quest != null) {
                yaml.set(key + ".quest.level", s.quest.level());
                yaml.set(key + ".quest.templateIds", s.quest.templateIds());
                List<Map<String, Object>> objectives = new ArrayList<>();
                for (QuestObjective o : s.quest.objectives()) {
                    Map<String, Object> m = new HashMap<>();
                    m.put("type", o.type().name());
                    m.put("target", o.target());
                    m.put("amount", o.amount());
                    m.put("progress", o.progress());
                    m.put("description", o.description());
                    objectives.add(m);
                }
                yaml.set(key + ".quest.objectives", objectives);
            }
        }
        try {
            sessionsFile().getParentFile().mkdirs();
            yaml.save(sessionsFile());
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save sessions.yml: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    public void loadSessions() {
        File file = sessionsFile();
        if (!file.exists()) {
            return;
        }
        long now = System.currentTimeMillis();
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (String key : yaml.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                String worldName = yaml.getString(key + ".return.world");
                World world = Bukkit.getWorld(worldName);
                if (world == null) {
                    plugin.getLogger().warning("Skipping session for " + key + ": world " + worldName + " missing");
                    continue;
                }
                Location ret = new Location(world,
                        yaml.getDouble(key + ".return.x"), yaml.getDouble(key + ".return.y"), yaml.getDouble(key + ".return.z"),
                        (float) yaml.getDouble(key + ".return.yaw"), (float) yaml.getDouble(key + ".return.pitch"));
                Session s = new Session(id, ret);
                s.level = yaml.getInt(key + ".level");
                s.questsCompleted = yaml.getInt(key + ".questsCompleted");
                // Sessions are always loaded paused: restart downtime counts
                // against neither the quest clock nor the disconnect grace.
                // questDeadlineMs == 0 marks a paused session; the remaining
                // time is frozen in pausedQuestRemainingMs.
                long pausedRemaining = yaml.getLong(key + ".pausedRemainingMs", -1L);
                if (pausedRemaining < 0) {
                    long legacyDeadline = yaml.getLong(key + ".deadlineMs", 0L);
                    pausedRemaining = legacyDeadline > 0 ? Math.max(0, legacyDeadline - now) : 0L;
                }
                long pausedOffline = yaml.getLong(key + ".pausedOfflineMs", -1L);
                if (pausedOffline < 0) {
                    long legacyOffline = yaml.getLong(key + ".offlineSinceMs", 0L);
                    pausedOffline = legacyOffline > 0 ? Math.max(0, now - legacyOffline) : 0L;
                }
                s.questDeadlineMs = 0;
                s.offlineSinceMs = 0;
                s.pausedQuestRemainingMs = pausedRemaining;
                s.pausedOfflineElapsedMs = pausedOffline;
                s.warned60 = yaml.getBoolean(key + ".warned60");
                s.warned30 = yaml.getBoolean(key + ".warned30");
                s.timeoutDamagePhase = yaml.getBoolean(key + ".timeoutDamagePhase");
                s.nextDamageMs = yaml.getLong(key + ".nextDamageMs");
                if (yaml.contains(key + ".quest")) {
                    int qLevel = yaml.getInt(key + ".quest.level");
                    List<String> templateIds = yaml.getStringList(key + ".quest.templateIds");
                    List<QuestObjective> objectives = new ArrayList<>();
                    for (Map<?, ?> m : (List<Map<?, ?>>) (List<?>) yaml.getMapList(key + ".quest.objectives")) {
                        QuestObjective o = new QuestObjective(
                                QuestType.valueOf(String.valueOf(m.get("type"))),
                                String.valueOf(m.get("target")),
                                ((Number) m.get("amount")).intValue(),
                                String.valueOf(m.get("description")));
                        o.setProgress(((Number) m.get("progress")).intValue());
                        objectives.add(o);
                    }
                    s.quest = new Quest(qLevel, templateIds, objectives);
                }
                // Only restore if the snapshot still exists; otherwise the run is unrecoverable.
                if (snapshots.hasSnapshot(id)) {
                    sessions.put(id, s);
                } else {
                    plugin.getLogger().warning("Dropping session for " + key + ": snapshot missing");
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Skipping corrupt session entry " + key + ": " + e.getMessage());
            }
        }
        plugin.getLogger().info("Restored " + sessions.size() + " hardcore session(s) from disk.");
    }

    /** Package-private so listeners can persist after consuming a restore. */
    void saveRestores() {
        // Respawn restores and offline restores are persisted separately. A
        // respawn restore must survive until the respawn event fires; only a
        // shutdown converts them (see shutdown()).
        YamlConfiguration yaml = new YamlConfiguration();
        int i = 0;
        for (Map.Entry<UUID, PendingRestore> e : pendingRespawnRestores.entrySet()) {
            writeRestore(yaml, "respawn." + (i++), e.getKey(), e.getValue());
        }
        int j = 0;
        for (Map.Entry<UUID, PendingRestore> e : pendingOfflineRestores.entrySet()) {
            writeRestore(yaml, "offline." + (j++), e.getKey(), e.getValue());
        }
        try {
            restoresFile().getParentFile().mkdirs();
            yaml.save(restoresFile());
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save restores.yml: " + e.getMessage());
        }
    }

    private void writeRestore(YamlConfiguration yaml, String key, UUID id, PendingRestore r) {
        yaml.set(key + ".player", id.toString());
        yaml.set(key + ".messageKey", r.messageKey());
        yaml.set(key + ".level", r.level());
        Location loc = r.returnLocation();
        yaml.set(key + ".return.world", loc.getWorld().getName());
        yaml.set(key + ".return.x", loc.getX());
        yaml.set(key + ".return.y", loc.getY());
        yaml.set(key + ".return.z", loc.getZ());
        yaml.set(key + ".return.yaw", loc.getYaw());
        yaml.set(key + ".return.pitch", loc.getPitch());
        // The snapshot itself lives in snapshots.yml keyed by player UUID.
    }

    public void loadRestores() {
        File file = restoresFile();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        loadRestoreSection(yaml, "offline", pendingOfflineRestores);
        // Respawn restores saved by a clean shutdown were already converted to
        // offline restores. Any respawn entries still on disk come from a crash
        // mid-respawn: nobody is mid-respawn anymore, so apply them on rejoin.
        loadRestoreSection(yaml, "respawn", pendingOfflineRestores);
    }

    private void loadRestoreSection(YamlConfiguration yaml, String sectionName,
                                    Map<UUID, PendingRestore> target) {
        var section = yaml.getConfigurationSection(sectionName);
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            try {
                UUID id = UUID.fromString(section.getString(key + ".player"));
                Snapshot snapshot = snapshots.peek(id);
                if (snapshot == null) {
                    continue;
                }
                String worldName = section.getString(key + ".return.world");
                World world = Bukkit.getWorld(worldName);
                if (world == null) {
                    continue;
                }
                Location ret = new Location(world,
                        section.getDouble(key + ".return.x"), section.getDouble(key + ".return.y"),
                        section.getDouble(key + ".return.z"),
                        (float) section.getDouble(key + ".return.yaw"), (float) section.getDouble(key + ".return.pitch"));
                target.put(id, new PendingRestore(
                        snapshot, ret, section.getString(key + ".messageKey", "death"),
                        section.getInt(key + ".level")));
            } catch (Exception e) {
                plugin.getLogger().warning("Skipping corrupt restore entry " + key + ": " + e.getMessage());
            }
        }
    }
}
