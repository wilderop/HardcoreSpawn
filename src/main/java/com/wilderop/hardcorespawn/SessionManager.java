package com.wilderop.hardcorespawn;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
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
import java.util.Random;
import java.util.UUID;

/**
 * Owns every hardcore run: start/confirm flow, quest assignment and progress,
 * the unified exit path, disconnect handling, and persistence.
 */
public final class SessionManager {

    /**
     * A snapshot restore waiting for a respawn or a rejoin. The return point
     * is stored as a world name plus coordinates, NOT a Bukkit Location: at
     * plugin enable time (STARTUP load order) no worlds are loaded yet, so
     * resolving the world eagerly would silently drop every pending restore
     * on every restart. The world is resolved when the restore is applied.
     */
    public record PendingRestore(Snapshot snapshot, String worldName, double x, double y, double z,
                                 float yaw, float pitch, String messageKey, int level) {
        /** Build from a live location (its world must be loaded). */
        public PendingRestore(Snapshot snapshot, Location returnLocation, String messageKey, int level) {
            this(snapshot, returnLocation.getWorld().getName(), returnLocation.getX(), returnLocation.getY(),
                    returnLocation.getZ(), returnLocation.getYaw(), returnLocation.getPitch(), messageKey, level);
        }

        /** Resolve the return location now that worlds are loaded; null if the world is gone. */
        public Location returnLocation() {
            World world = Bukkit.getWorld(worldName);
            if (world == null) {
                return null;
            }
            return new Location(world, x, y, z, yaw, pitch);
        }
    }

    /**
     * A run start counting down: the player must stand still and take no
     * damage until {@code endMs}, or the start is cancelled. The snapshot is
     * only taken when the countdown completes, so a cancelled start costs the
     * player nothing.
     */
    private static final class StartCountdown {
        final UUID playerId;
        final long endMs;
        int lastShown = -1;

        StartCountdown(UUID playerId, long endMs) {
            this.playerId = playerId;
            this.endMs = endMs;
        }
    }

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    /**
     * Fun, non-boss mobs a milestone spawn egg can become. Kept to
     * passive/neutral mobs so the prize can't be weaponized mid-run.
     */
    private static final List<Material> MILESTONE_EGGS = List.of(
            Material.PIG_SPAWN_EGG, Material.COW_SPAWN_EGG, Material.SHEEP_SPAWN_EGG,
            Material.CHICKEN_SPAWN_EGG, Material.HORSE_SPAWN_EGG, Material.WOLF_SPAWN_EGG,
            Material.CAT_SPAWN_EGG, Material.PARROT_SPAWN_EGG, Material.FOX_SPAWN_EGG,
            Material.PANDA_SPAWN_EGG, Material.BEE_SPAWN_EGG, Material.AXOLOTL_SPAWN_EGG,
            Material.GOAT_SPAWN_EGG, Material.FROG_SPAWN_EGG, Material.CAMEL_SPAWN_EGG,
            Material.SNIFFER_SPAWN_EGG, Material.ARMADILLO_SPAWN_EGG, Material.MOOSHROOM_SPAWN_EGG,
            Material.DONKEY_SPAWN_EGG, Material.LLAMA_SPAWN_EGG, Material.RABBIT_SPAWN_EGG,
            Material.TURTLE_SPAWN_EGG, Material.OCELOT_SPAWN_EGG, Material.DOLPHIN_SPAWN_EGG);

    private final HardcoreSpawn plugin;
    private HardcoreConfig config;
    private final SnapshotManager snapshots;
    private QuestGenerator quests;
    private final LeaderboardManager leaderboard;
    private HudService hud;
    private DiscordNotifier discord = new DiscordNotifier("", null) {
        @Override
        protected void postJson(String json) {
            // Disabled by default until HardcoreSpawn wires the real one.
        }
    };

    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<UUID, Long> pendingConfirms = new HashMap<>();
    private final Map<UUID, StartCountdown> pendingStarts = new HashMap<>();
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

    /** (Re)build the Discord notifier from the current config. */
    public void setDiscordNotifier(DiscordNotifier discord) {
        this.discord = discord;
    }

    public HardcoreConfig getConfig() { return config; }
    public HudService getHud() { return hud; }
    public SnapshotManager getSnapshots() { return snapshots; }
    public LeaderboardManager getLeaderboard() { return leaderboard; }

    /** Push the current sidebar config, then (re)show the run HUD. */
    private void showRunHud(org.bukkit.entity.Player player, Session s, long questMs) {
        hud.configureSidebar(config.isSidebarEnabled(), config.getSidebarTitle());
        hud.showRunHud(player, reachedLevel(s), questMs);
    }

    public boolean hasSession(UUID id) { return sessions.containsKey(id); }
    public Session getSession(UUID id) { return sessions.get(id); }

    /** Test-only: drop all sessions and transient maps (the MockBukkit server is shared per test class). */
    void clearSessionsForTests() {
        sessions.clear();
        pendingConfirms.clear();
        pendingStarts.clear();
        pendingRespawnRestores.clear();
        pendingOfflineRestores.clear();
        entityDamage.clear();
        entityDamageSeenMs.clear();
    }

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
        if (config.isHighScorePrizeEnabled()) {
            player.sendMessage(config.message("highscore-prize-announce"));
        }
    }

    public boolean confirmStart(Player player) {
        UUID id = player.getUniqueId();
        if (pendingStarts.containsKey(id)) {
            player.sendMessage(config.format("confirm-freeze-active", Map.of()));
            return false;
        }
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
        int freezeSeconds = config.getStartFreezeSeconds();
        if (freezeSeconds <= 0) {
            return finishStart(player, now);
        }
        // The committing action: stand still and take no damage for the whole
        // window, because starting teleports you to spawn. The snapshot is
        // only taken once the countdown completes, so cancelling costs nothing.
        StartCountdown countdown = new StartCountdown(id, now + freezeSeconds * 1000L);
        pendingStarts.put(id, countdown);
        player.sendMessage(config.format("confirm-freeze-start",
                Map.of("seconds", String.valueOf(freezeSeconds))));
        countdown.lastShown = freezeSeconds;
        showFreezeTitle(player, freezeSeconds);
        return true;
    }

    /**
     * The run actually begins: snapshot, clear, scatter into the wilds, quest 1.
     * Called either instantly (freeze disabled) or when a start countdown
     * completes cleanly.
     */
    private boolean finishStart(Player player, long now) {
        UUID id = player.getUniqueId();
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

        Location start = scatterStartLocation();
        if (!player.teleport(start)) {
            // The scatter teleport failed or was cancelled: roll the run back
            // entirely rather than leaving the player cleared at their old
            // location with an active session.
            sessions.remove(id);
            snapshots.restore(player, snapshot);
            plugin.getLogger().warning("Scatter teleport failed for " + player.getName() + "; run cancelled and inventory restored.");
            player.sendMessage(config.format("teleport-failed", Map.of()));
            return false;
        }
        // No free healing: the run starts with whatever health/hunger the
        // player had. (Restoring never heals either, so quitting a run grants
        // no advantage.)

        leaderboard.runStarted(id);
        session.runStartedMs = now;
        assignInitialHand(player, session, now);
        discord.sendRunStarted(player.getName(), questDescriptions(session));
        return true;
    }

    private List<String> questDescriptions(Session s) {
        List<String> descriptions = new ArrayList<>();
        for (Quest q : s.hand) {
            descriptions.add(q.getDescription());
        }
        return descriptions;
    }

    // ------------------------------------------------------------------
    // Start freeze (anti-combat-escape)
    // ------------------------------------------------------------------

    /** True while the player's run start is counting down (movement locked). */
    public boolean isStartFrozen(UUID id) {
        return pendingStarts.containsKey(id);
    }

    /**
     * Cancel a pending run start. The player keeps everything: no snapshot
     * was taken and no session was created.
     *
     * @param messageKey config message to send the player, or null for silent
     * @return true if a countdown was actually active
     */
    public boolean cancelStartCountdown(UUID id, String messageKey) {
        if (pendingStarts.remove(id) == null) {
            return false;
        }
        if (messageKey != null) {
            Player player = Bukkit.getPlayer(id);
            if (player != null && player.isOnline()) {
                player.sendMessage(config.format(messageKey, Map.of()));
            }
        }
        return true;
    }

    /** Tick every second: show the countdown title, or start the run. */
    public void tickStartCountdowns(long now) {
        if (pendingStarts.isEmpty()) {
            return;
        }
        var it = pendingStarts.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            UUID id = entry.getKey();
            StartCountdown countdown = entry.getValue();
            Player player = Bukkit.getPlayer(id);
            if (player == null || !player.isOnline()) {
                // Logout cancels via the freeze listener; this is the safety net.
                it.remove();
                continue;
            }
            long remainingMs = countdown.endMs - now;
            if (remainingMs <= 0) {
                it.remove();
                finishStart(player, now);
                continue;
            }
            int seconds = (int) ((remainingMs + 999) / 1000);
            if (seconds != countdown.lastShown) {
                countdown.lastShown = seconds;
                showFreezeTitle(player, seconds);
            }
        }
    }

    private void showFreezeTitle(Player player, int seconds) {
        String sec = String.valueOf(seconds);
        player.showTitle(Title.title(
                LEGACY.deserialize(config.message("confirm-freeze-title").replace("{seconds}", sec)),
                LEGACY.deserialize(config.message("confirm-freeze-subtitle").replace("{seconds}", sec))));
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
        // The cursor is not part of the inventory contents: items held there
        // must not slip through the "fully empty" exception to the start rule.
        ItemStack cursor = player.getItemOnCursor();
        if (cursor != null && !cursor.getType().isAir()) {
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

    /**
     * Pick the run start point. With scatter enabled (default) this is a
     * random wilderness spot in a ring around 0,0, so nobody can pre-stage
     * gear at the start point; with scatter disabled it is the world spawn.
     */
    private Location scatterStartLocation() {
        return scatterStartLocation(java.util.concurrent.ThreadLocalRandom.current());
    }

    /**
     * Test seam: inject the RNG. Draw order is fixed — angle first, then
     * distance — so tests can replicate the draw with the same seed.
     */
    Location scatterStartLocation(Random rng) {
        World world = Bukkit.getWorld("world");
        if (world == null && !Bukkit.getWorlds().isEmpty()) {
            world = Bukkit.getWorlds().get(0);
        }
        if (world == null) {
            throw new IllegalStateException("No world loaded");
        }
        if (!config.isScatterStartEnabled()) {
            return spawnCenter(world);
        }
        double min = config.getScatterMinDistance();
        double max = Math.max(min + 1.0, config.getScatterMaxDistance());
        for (int attempt = 0; attempt < 24; attempt++) {
            double angle = rng.nextDouble() * Math.PI * 2.0;
            double dist = min + rng.nextDouble() * (max - min);
            int x = (int) Math.round(Math.cos(angle) * dist);
            int z = (int) Math.round(Math.sin(angle) * dist);
            Location spot = safeGround(world, x, z);
            if (spot != null) {
                return spot;
            }
        }
        plugin.getLogger().warning("[HardcoreSpawn] No safe scatter ground found; starting at spawn.");
        return spawnCenter(world);
    }

    /** Highest safe standing spot at the given XZ, or null. Never throws. */
    private static Location safeGround(World world, int x, int z) {
        try {
            int topY = world.getHighestBlockYAt(x, z);
            Block ground = world.getBlockAt(x, topY, z);
            Material type = ground.getType();
            if (!type.isSolid() || type == Material.LAVA || type == Material.WATER) {
                return null;
            }
            Block feet = world.getBlockAt(x, topY + 1, z);
            Block head = world.getBlockAt(x, topY + 2, z);
            if (!feet.getType().isAir() || !head.getType().isAir()) {
                return null;
            }
            return new Location(world, x + 0.5, topY + 1.0, z + 0.5);
        } catch (Exception e) {
            return null;
        }
    }

    private static Location spawnCenter(World world) {
        Location spawn = world.getSpawnLocation().clone();
        spawn.add(0.5, 0.5, 0.5);
        return spawn;
    }

    // ------------------------------------------------------------------
    // Quests
    // ------------------------------------------------------------------

    /**
     * Deal the opening hand of HAND_SIZE quests (all level 1, no shared
     * templates) and start the quest clock.
     */
    private void assignInitialHand(Player player, Session session, long now) {
        List<String> excluded = new ArrayList<>();
        // One guaranteed easy starter: travel on foot. On anarchy servers the
        // land near spawn is stripped bare, so a gathering quest first is brutal.
        Quest starter = quests.generateByType(1, QuestType.TRAVEL);
        if (starter == null) {
            // Custom config without a travel template: keep the easy starter anyway.
            starter = new Quest(1, List.of("builtin-travel"),
                    List.of(new QuestObjective(QuestType.TRAVEL, "BLOCKS", 500,
                            "Travel 500 blocks on foot")));
        }
        session.hand.add(starter);
        excluded.addAll(starter.templateIds());
        for (int i = 1; i < Session.HAND_SIZE; i++) {
            Quest q = quests.generate(1, excluded);
            session.hand.add(q);
            excluded.addAll(q.templateIds());
        }
        resetQuestClock(session, now);
        showRunHud(player, session, config.getQuestTimeSeconds() * 1000L);
        player.sendMessage(config.format("run-started", Map.of(
                "quests", formatHand(session),
                "time", config.formatTime(config.getQuestTimeSeconds() * 1000L))));
    }

    /** Reset the shared quest deadline and clear timeout state. */
    private void resetQuestClock(Session session, long now) {
        session.questDeadlineMs = now + config.getQuestTimeSeconds() * 1000L;
        session.warned60 = false;
        session.warned30 = false;
        session.timeoutDamagePhase = false;
    }

    /**
     * Add bonus time to the shared quest deadline. If the clock was already in
     * the timeout damage phase, the bonus pulls the deadline back into the
     * future and the damage stops; warnings re-arm based on the new remaining
     * time.
     */
    private void extendQuestClock(Session session, long now, long bonusMs) {
        session.questDeadlineMs += bonusMs;
        long remaining = session.questDeadlineMs - now;
        if (remaining > 0) {
            session.timeoutDamagePhase = false;
            session.warned60 = remaining <= 60_000;
            session.warned30 = remaining <= 30_000;
        }
    }

    /** Numbered list of the active hand with progress, for chat messages. */
    private static String formatHand(Session s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.hand.size(); i++) {
            Quest q = s.hand.get(i);
            if (i > 0) {
                sb.append('\n');
            }
            sb.append("§e").append(i + 1).append(". §f").append(q.getDescription())
                    .append(" §7(").append(q.getProgressText()).append(')');
        }
        return sb.toString();
    }

    /** True when the player has an incomplete TRAVEL objective in their hand. */
    public boolean hasTravelObjective(UUID id) {
        Session s = sessions.get(id);
        if (s == null || s.hand.isEmpty()) {
            return false;
        }
        for (Quest q : s.hand) {
            for (QuestObjective o : q.objectives()) {
                if (o.type() == QuestType.TRAVEL && !o.isComplete()) {
                    return true;
                }
            }
        }
        return false;
    }

    public void addProgress(Player player, QuestType type, String target, int amount) {
        Session s = sessions.get(player.getUniqueId());
        if (s == null || s.hand.isEmpty() || s.crowded) {
            return; // crowded: the wilds freeze progress while the clock burns
        }
        boolean moved = false;
        List<Quest> completed = new ArrayList<>();
        for (Quest q : new ArrayList<>(s.hand)) {
            if (q.progress(type, target, amount)) {
                moved = true;
                if (q.isComplete()) {
                    completed.add(q);
                }
            }
        }
        if (!completed.isEmpty()) {
            for (Quest q : completed) {
                completeQuest(player, s, q);
            }
        } else if (moved) {
            hud.updateHud(player, reachedLevel(s), s.questDeadlineMs - System.currentTimeMillis(),
                s.hand, s.questsCompleted);
        }
    }

    /** Scan the inventory for OBTAIN objectives. Called every tick. */
    public void checkObtain(Player player) {
        Session s = sessions.get(player.getUniqueId());
        if (s == null || s.hand.isEmpty() || s.crowded) {
            return; // crowded: the wilds freeze progress while the clock burns
        }
        boolean moved = false;
        for (Quest q : s.hand) {
            for (QuestObjective o : q.objectives()) {
                if (o.type() == QuestType.OBTAIN && !o.isComplete()) {
                    int count = countMaterial(player, o.target());
                    if (count != o.progress()) {
                        o.setProgress(count);
                        moved = true;
                    }
                }
            }
        }
        if (!moved) {
            return;
        }
        for (Quest q : new ArrayList<>(s.hand)) {
            if (q.isComplete()) {
                completeQuest(player, s, q);
            }
        }
    }

    // ------------------------------------------------------------------
    // Solitude: quest progress freezes while another runner is nearby.
    // Pairwise distance checks over active runners only — cheap for any
    // realistic runner count. Runs every second from TimerTask.
    // ------------------------------------------------------------------

    /**
     * Recompute the crowded flag for every active runner: crowded while any
     * other runner is within the solitude radius in the same world. Sends a
     * notice on transitions. Also prunes stale solo-kill damage records.
     */
    public void tickSolitude(long now) {
        pruneDamageRecords(now);
        if (!config.isSolitudeEnabled()) {
            for (Session s : sessions.values()) {
                s.crowded = false;
            }
            return;
        }
        double r2 = config.getSolitudeRadiusBlocks();
        r2 *= r2;
        List<Session> active = new ArrayList<>();
        Map<UUID, Player> players = new HashMap<>();
        for (Session s : sessions.values()) {
            Player p = Bukkit.getPlayer(s.playerId);
            if (p == null || !p.isOnline()) {
                continue;
            }
            active.add(s);
            players.put(s.playerId, p);
        }
        for (Session s : active) {
            Player p = players.get(s.playerId);
            boolean crowded = false;
            for (Session o : active) {
                if (o == s) {
                    continue;
                }
                Player q = players.get(o.playerId);
                if (!p.getWorld().equals(q.getWorld())) {
                    continue;
                }
                if (p.getLocation().distanceSquared(q.getLocation()) <= r2) {
                    crowded = true;
                    break;
                }
            }
            if (crowded != s.crowded) {
                s.crowded = crowded;
                p.sendMessage(config.format(crowded ? "solitude-notice" : "solitude-clear", Map.of()));
            }
            hud.setCrowded(s.playerId, crowded);
            if (crowded) {
                // Persistent and unmissable: an action bar every second while
                // progress is frozen, so nobody mistakes it for a bug.
                hud.warnActionBar(p, config.message("solitude-actionbar"));
            }
        }
    }

    // ------------------------------------------------------------------
    // Solo kills: kill quests need a majority of the player-dealt damage.
    // ------------------------------------------------------------------

    /** Damage older than this is forgotten (bounds the damage map). */
    private static final long DAMAGE_MEMORY_MS = 5 * 60 * 1000L;

    /** Entity -> (player -> damage dealt). Small: only recent combat. */
    private final Map<UUID, Map<UUID, Double>> entityDamage = new HashMap<>();
    private final Map<UUID, Long> entityDamageSeenMs = new HashMap<>();

    /** Seam fed by the damage listener; test code can drive it directly. */
    public void recordPlayerDamage(UUID entityId, UUID playerId, double damage, long now) {
        if (!hasSession(playerId) || damage <= 0) {
            return;
        }
        entityDamage.computeIfAbsent(entityId, k -> new HashMap<>())
                .merge(playerId, damage, Double::sum);
        entityDamageSeenMs.put(entityId, now);
    }

    /**
     * True when the killer's share of the tracked player-dealt damage is a
     * strict majority — or when nothing was tracked (fail open for genuine
     * solo kills; a helper's damage would always be tracked).
     */
    public boolean isSoloKill(UUID entityId, UUID playerId) {        if (!config.isSoloKillsEnabled()) {
            return true;
        }
        Map<UUID, Double> byPlayer = entityDamage.get(entityId);
        if (byPlayer == null || byPlayer.isEmpty()) {
            return true;
        }
        double mine = byPlayer.getOrDefault(playerId, 0.0);
        double total = 0.0;
        for (double d : byPlayer.values()) {
            total += d;
        }
        return total > 0.0 && mine > total / 2.0;
    }

    /** Drop a dead entity's damage record. */
    public void forgetEntityDamage(UUID entityId) {
        entityDamage.remove(entityId);
        entityDamageSeenMs.remove(entityId);
    }

    private void pruneDamageRecords(long now) {
        if (entityDamageSeenMs.isEmpty()) {
            return;
        }
        var it = entityDamageSeenMs.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            if (now - e.getValue() > DAMAGE_MEMORY_MS) {
                it.remove();
                entityDamage.remove(e.getKey());
            }
        }
    }

    private int countMaterial(Player player, String materialName) {        final Material material;
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

    /**
     * One quest from the hand was completed: level up, add bonus time to the
     * shared quest clock, and deal a replacement so the hand stays full. The
     * new quest excludes the templates of the other active quests (and of
     * the one just completed) so it never duplicates an option in the hand.
     */
    public void completeQuest(Player player, Session s, Quest completed) {
        if (!s.hand.remove(completed)) {
            return;
        }
        s.level++;
        s.questsCompleted++;
        long bonusMs = config.getQuestCompleteBonusSeconds() * 1000L;
        player.sendMessage(config.format("quest-complete",
                Map.of("bonus", config.formatTime(bonusMs))));
        List<String> excluded = new ArrayList<>(completed.templateIds());
        for (Quest q : s.hand) {
            excluded.addAll(q.templateIds());
        }
        Quest replacement = quests.generate(s.level + 1, excluded);
        s.hand.add(replacement);
        // New personal best level (same level the leaderboard records) wins
        // the high-score prize: a spawner block plus a spawn egg.
        int reached = reachedLevel(s);
        if (config.isHighScorePrizeEnabled()) {
            LeaderboardManager.Stats stats = leaderboard.stats(player.getUniqueId());
            if (reached > stats.bestLevel) {
                // Record the new best immediately so each further level in
                // this record run awards again.
                stats.bestLevel = reached;
                leaderboard.save();
                grantHighScorePrize(player, reached);
            }
        }
        discord.sendQuestCompleted(player.getName(), completed.getDescription(),
                replacement.getDescription(), s.questsCompleted, reachedLevel(s));
        long now = System.currentTimeMillis();
        extendQuestClock(s, now, bonusMs);
        long remainingMs = Math.max(0L, s.questDeadlineMs - now);
        showRunHud(player, s, remainingMs);
        player.sendMessage(config.format("new-quest", Map.of(
                "quest", replacement.getDescription(),
                "quests", formatHand(s),
                "time", config.formatTime(remainingMs),
                "level", String.valueOf(replacement.level()))));
        saveSessions();
    }

    /**
     * High-score prize: a new personal best level awards BOTH an empty mob
     * spawner block and a random mob spawn egg. Right-clicking the spawner
     * with an egg sets what it spawns. Like all run loot they must be banked
     * in a world chest or they are lost on death; a full inventory drops
     * them at the player's feet.
     */
    private void grantHighScorePrize(Player player, int level) {
        Material egg = MILESTONE_EGGS.get(
                java.util.concurrent.ThreadLocalRandom.current().nextInt(MILESTONE_EGGS.size()));
        ItemStack spawner = new ItemStack(Material.SPAWNER, 1);
        ItemStack eggStack = new ItemStack(egg, 1);
        HashMap<Integer, ItemStack> leftover =
                player.getInventory().addItem(spawner, eggStack);
        String mobName = toMobName(egg);
        Map<String, String> ph = Map.of("level", String.valueOf(level), "mob", mobName);
        if (!leftover.isEmpty()) {
            for (ItemStack item : leftover.values()) {
                player.getWorld().dropItemNaturally(player.getLocation(), item);
            }
            player.sendMessage(config.format("highscore-prize-dropped", ph));
        } else {
            player.sendMessage(config.format("highscore-prize", ph));
        }
    }

    /** PIG_SPAWN_EGG -> "Pig", MOOSHROOM_SPAWN_EGG -> "Mooshroom". */
    private static String toMobName(Material egg) {
        String base = egg.name().replace("_SPAWN_EGG", "");
        String[] parts = base.toLowerCase(java.util.Locale.ROOT).split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (!part.isEmpty()) {
                sb.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1)).append(' ');
            }
        }
        return sb.toString().trim();
    }

    /**
     * Lethal damage was intercepted (or the quest timer expired): the player
     * never actually dies. Drop run gains where they "died", heal to full,
     * and end the run immediately with the death message.
     *
     * <p>Skipping the death/respawn round-trip keeps the pre-run XP intact:
     * Bukkit applies the death event's new-XP values at respawn, after
     * PlayerRespawnEvent, which used to clobber the restored levels.
     */
    public void avertDeath(Player player) {
        UUID id = player.getUniqueId();
        if (!hasSession(id)) {
            return;
        }
        dropRunGains(player);
        player.setHealth(player.getMaxHealth());
        player.setFireTicks(0);
        endRun(id, ExitCause.AVERTED_DEATH);
    }

    /**
     * Forfeit everything gained during the run: drop it where the player is.
     * Storage + armor + offhand exactly once (getContents() already includes
     * armor and offhand, so it must not be combined with them).
     */
    public void dropRunGains(Player player) {
        Location loc = player.getLocation();
        World world = loc.getWorld();
        dropAll(world, loc, player.getInventory().getStorageContents());
        dropAll(world, loc, player.getInventory().getArmorContents());
        ItemStack offhand = player.getInventory().getItemInOffHand();
        if (offhand != null && !offhand.getType().isAir()) {
            world.dropItemNaturally(loc, offhand);
        }
        // The cursor item is not reliably part of the death event drops, so
        // forfeit it explicitly. This is dupe-safe: the vanilla drops were
        // cleared (or never existed on the intercepted path), so exactly one
        // copy is spawned however the exit was triggered.
        ItemStack cursor = player.getItemOnCursor();
        if (cursor != null && !cursor.getType().isAir()) {
            world.dropItemNaturally(loc, cursor.clone());
            player.setItemOnCursor(null);
        }
    }

    private void dropAll(World world, Location loc, ItemStack[] items) {
        for (ItemStack item : items) {
            if (item != null && !item.getType().isAir()) {
                world.dropItemNaturally(loc, item.clone());
            }
        }
    }

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
            case IN_WORLD_DEATH, AVERTED_DEATH -> "death";
        };

        if (player != null && player.isOnline()) {
            if (cause == ExitCause.IN_WORLD_DEATH) {
                // A real death (e.g. another plugin killed without a damage
                // event, bypassing the interceptor): drops were handled by the
                // death listener; restore on respawn.
                pendingRespawnRestores.put(id, new PendingRestore(snapshot, s.returnLocation, messageKey, reached));
                saveRestores();
            } else {
                if (snapshot != null) {
                    snapshots.clearPlayer(player);
                    snapshots.restore(player, snapshot);
                } else {
                    // Never wipe a player's inventory when there is nothing to
                    // restore it from: fail open (they keep everything) rather
                    // than destroying the pre-run state irrecoverably.
                    plugin.getLogger().severe("No snapshot for " + player.getName()
                            + "; ending run WITHOUT wiping inventory to avoid item loss.");
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
        String playerName = player != null ? player.getName() : id.toString();
        discord.sendRunEnded(playerName, cause, reached, s.questsCompleted,
                System.currentTimeMillis() - s.runStartedMs);
        saveSessions();
    }

    /**
     * Admin reset for an OFFLINE player. Ends any active/paused session and
     * queues the pre-run snapshot for restore on their next join, so a reset
     * player always gets their pre-run state back — a reset never deletes
     * items, it only ends the run.
     *
     * <p>Unlike {@link #endRun(UUID, ExitCause)}, this deliberately does NOT
     * touch the leaderboard: an admin reset is maintenance, not a finished
     * run.
     *
     * @return true if the player had any hardcore data (session or snapshot)
     *         to reset; false when there was nothing (a queued restore alone
     *         means the run already ended and is left untouched)
     */
    public boolean resetOfflinePlayer(UUID id) {
        Session s = sessions.remove(id);
        pendingConfirms.remove(id);
        // A queued restore means the run already ended; leave it alone —
        // deleting it would destroy the items it is about to give back.
        boolean restoreQueued = pendingOfflineRestores.containsKey(id)
                || pendingRespawnRestores.containsKey(id);
        Snapshot snapshot = snapshots.peek(id);
        if (s == null && snapshot == null) {
            return false;
        }
        if (s != null) {
            if (snapshot != null) {
                pendingOfflineRestores.put(id,
                        new PendingRestore(snapshot, s.returnLocation, "quit", reachedLevel(s)));
            } else {
                // No snapshot to restore from: drop the run data and let the
                // player keep whatever their player file holds. Never
                // fabricate or delete items here.
                plugin.getLogger().warning("Admin reset for offline player " + id
                        + ": session existed but no snapshot; run data dropped without restore.");
            }
        } else if (!restoreQueued) {
            // Orphaned snapshot with no session (e.g. a crash between the
            // snapshot write and the session save): fail open and queue it so
            // the player gets their items back on next join instead of
            // losing them.
            Location loc = snapshotReturnLocation(snapshot);
            if (loc != null) {
                pendingOfflineRestores.put(id, new PendingRestore(snapshot, loc, "quit", 0));
            } else {
                plugin.getLogger().warning("Admin reset for offline player " + id
                        + ": snapshot world '" + snapshot.worldName
                        + "' is missing; snapshot left in place.");
            }
        }
        hud.hideHud(id);
        saveRestores();
        saveSessions();
        return true;
    }

    /** Rebuild the snapshot's pre-run location, or null if its world is gone. */
    private Location snapshotReturnLocation(Snapshot snapshot) {
        World world = Bukkit.getWorld(snapshot.worldName);
        if (world == null) {
            return null;
        }
        return new Location(world, snapshot.x, snapshot.y, snapshot.z, snapshot.yaw, snapshot.pitch);
    }

    /**
     * The level the player actually reached (the highest quest level in the
     * hand), used for messages, the leaderboard, and restores. A fresh run
     * with a hand of level-1 quests counts as reaching level 1, not level 0.
     */
    static int reachedLevel(Session s) {
        int max = 0;
        for (Quest q : s.hand) {
            max = Math.max(max, q.level());
        }
        return max > 0 ? max : s.level;
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
            if (player.isDead()) {
                // Died and the server restarted before respawning: the player
                // will still be dead on rejoin, so apply the restore at the
                // respawn event instead of on the corpse right now.
                pendingRespawnRestores.put(id, restore);
            } else {
                applyRestore(player, restore);
            }
            saveRestores();
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
                if (!s.hand.isEmpty()) {
                    showRunHud(player, s, s.questDeadlineMs - now);
                    player.sendMessage(config.format("your-quests", Map.of(
                            "quests", formatHand(s),
                            "time", config.formatTime(s.questDeadlineMs - now))));
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
            if (!s.hand.isEmpty()) {
                showRunHud(player, s, s.questDeadlineMs - now);
                player.sendMessage(config.format("your-quests", Map.of(
                        "quests", formatHand(s),
                        "time", config.formatTime(s.questDeadlineMs - now))));
            }
            player.sendMessage(config.format("welcome-back", Map.of()));
            saveSessions();
        }
    }

    private void applyRestore(Player player, PendingRestore restore) {
        snapshots.clearPlayer(player);
        if (restore.snapshot() != null) {
            snapshots.restore(player, restore.snapshot());
            if ("death".equals(restore.messageKey())) {
                // The run death must not reset TIME_SINCE_DEATH or bump DEATHS.
                snapshots.restoreDeathStats(player, restore.snapshot());
            }
        }
        Location loc = restore.returnLocation();
        if (loc != null) {
            player.teleport(loc);
        } else {
            // Fail open: the items and XP are already back; never strand the
            // restore just because the world is missing.
            plugin.getLogger().severe("Could not return " + player.getName() + " to world '"
                    + restore.worldName() + "' (not loaded); inventory was still restored.");
        }
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
        if (s == null || s.hand.isEmpty()) {
            player.sendMessage(config.format("no-session", Map.of()));
            return;
        }
        LeaderboardManager.Stats stats = leaderboard.stats(player.getUniqueId());
        String time = s.questDeadlineMs == 0
                ? "paused"
                : config.formatTime(s.questDeadlineMs - System.currentTimeMillis());
        player.sendMessage(config.format("status", Map.of(
                "level", String.valueOf(reachedLevel(s)),
                "quests", formatHand(s),
                "time", time,
                "best", String.valueOf(stats.bestLevel),
                "runs", String.valueOf(stats.totalRuns))));
    }

    /** Show the top players by best level. */
    public void sendLeaderboard(Player player) {
        List<Map.Entry<UUID, LeaderboardManager.Stats>> top =
                leaderboard.topByBestLevel(10);
        if (top.isEmpty()) {
            player.sendMessage(config.format("leaderboard-empty", Map.of()));
            return;
        }
        player.sendMessage(config.format("leaderboard-header", Map.of()));
        int rank = 1;
        for (Map.Entry<UUID, LeaderboardManager.Stats> e : top) {
            String name = Bukkit.getOfflinePlayer(e.getKey()).getName();
            if (name == null) {
                name = e.getKey().toString().substring(0, 8);
            }
            LeaderboardManager.Stats st = e.getValue();
            player.sendMessage(config.format("leaderboard-entry", Map.of(
                    "rank", String.valueOf(rank++),
                    "name", name,
                    "best", String.valueOf(st.bestLevel),
                    "quests", String.valueOf(st.totalQuests),
                    "runs", String.valueOf(st.totalRuns))));
        }
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
            yaml.set(key + ".runStartedMs", s.runStartedMs);
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
            if (!s.hand.isEmpty()) {
                List<Map<String, Object>> questList = new ArrayList<>();
                for (Quest q : s.hand) {
                    Map<String, Object> questMap = new HashMap<>();
                    questMap.put("level", q.level());
                    questMap.put("templateIds", q.templateIds());
                    List<Map<String, Object>> objectives = new ArrayList<>();
                    for (QuestObjective o : q.objectives()) {
                        Map<String, Object> m = new HashMap<>();
                        m.put("type", o.type().name());
                        m.put("target", o.target());
                        m.put("amount", o.amount());
                        m.put("progress", o.progress());
                        m.put("description", o.description());
                        objectives.add(m);
                    }
                    questMap.put("objectives", objectives);
                    questList.add(questMap);
                }
                yaml.set(key + ".quests", questList);
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
                s.runStartedMs = yaml.getLong(key + ".runStartedMs", now);
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
                if (yaml.contains(key + ".quests")) {
                    for (Map<?, ?> qm : (List<Map<?, ?>>) (List<?>) yaml.getMapList(key + ".quests")) {
                        s.hand.add(readQuest(qm));
                    }
                } else if (yaml.contains(key + ".quest")) {
                    // Legacy single-quest format (v1.0.0): read it, then deal
                    // the remaining quests to restore a full hand.
                    int qLevel = yaml.getInt(key + ".quest.level");
                    List<String> templateIds = yaml.getStringList(key + ".quest.templateIds");
                    List<QuestObjective> objectives = new ArrayList<>();
                    for (Map<?, ?> m : (List<Map<?, ?>>) (List<?>) yaml.getMapList(key + ".quest.objectives")) {
                        objectives.add(readObjective(m));
                    }
                    s.hand.add(new Quest(qLevel, templateIds, objectives));
                }
                // The hand must always be full: top up after a legacy load or
                // if a quest was somehow lost between save and load.
                while (s.hand.size() < Session.HAND_SIZE) {
                    List<String> excluded = new ArrayList<>();
                    for (Quest q : s.hand) {
                        excluded.addAll(q.templateIds());
                    }
                    s.hand.add(quests.generate(s.level + 1, excluded));
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

    private static Quest readQuest(Map<?, ?> qm) {
        int qLevel = ((Number) qm.get("level")).intValue();
        @SuppressWarnings("unchecked")
        List<String> templateIds = (List<String>) (List<?>) qm.get("templateIds");
        List<QuestObjective> objectives = new ArrayList<>();
        @SuppressWarnings("unchecked")
        List<Map<?, ?>> raw = (List<Map<?, ?>>) (List<?>) qm.get("objectives");
        for (Map<?, ?> m : raw) {
            objectives.add(readObjective(m));
        }
        return new Quest(qLevel, templateIds, objectives);
    }

    private static QuestObjective readObjective(Map<?, ?> m) {
        QuestObjective o = new QuestObjective(
                QuestType.valueOf(String.valueOf(m.get("type"))),
                String.valueOf(m.get("target")),
                ((Number) m.get("amount")).intValue(),
                String.valueOf(m.get("description")));
        o.setProgress(((Number) m.get("progress")).intValue());
        return o;
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
        yaml.set(key + ".return.world", r.worldName());
        yaml.set(key + ".return.x", r.x());
        yaml.set(key + ".return.y", r.y());
        yaml.set(key + ".return.z", r.z());
        yaml.set(key + ".return.yaw", r.yaw());
        yaml.set(key + ".return.pitch", r.pitch());
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

    /**
     * Recover snapshots orphaned by the pre-1.10.2 restart bug: pending
     * restores were dropped at enable time because no worlds were loaded yet,
     * and the next shutdown then wiped them from restores.yml. Any snapshot
     * with no live session and no queued restore is a player's pre-run state
     * that was never given back — queue it for their next join. Idempotent.
     * Call after {@link #loadSessions()} and {@link #loadRestores()}.
     */
    public void recoverOrphanedSnapshots() {
        boolean added = false;
        for (UUID id : snapshots.snapshotIds()) {
            if (sessions.containsKey(id)) {
                continue;
            }
            if (pendingOfflineRestores.containsKey(id) || pendingRespawnRestores.containsKey(id)) {
                continue;
            }
            Snapshot snap = snapshots.peek(id);
            if (snap == null || snap.worldName == null) {
                continue;
            }
            pendingOfflineRestores.put(id, new PendingRestore(snap, snap.worldName, snap.x, snap.y, snap.z,
                    snap.yaw, snap.pitch, "snapshot-recovered", 0));
            plugin.getLogger().warning("Recovered orphaned pre-run snapshot for " + id
                    + ": it will be restored on their next join.");
            added = true;
        }
        if (added) {
            saveRestores();
        }
    }

    /**
     * Admin backstop: restore a player's snapshot now (online) or queue it
     * for their next join (offline). Refuses while they have an active run —
     * the snapshot is their pre-run state, and restoring it mid-run would
     * destroy the run. Returns false when there is nothing to restore.
     */
    public boolean restoreSnapshot(UUID id) {
        if (sessions.containsKey(id)) {
            return false;
        }
        Snapshot snap = snapshots.peek(id);
        if (snap == null || snap.worldName == null) {
            return false;
        }
        PendingRestore restore = new PendingRestore(snap, snap.worldName, snap.x, snap.y, snap.z,
                snap.yaw, snap.pitch, "snapshot-recovered", 0);
        Player player = Bukkit.getPlayer(id);
        if (player != null && player.isOnline() && !player.isDead()) {
            applyRestore(player, restore);
        } else {
            pendingOfflineRestores.put(id, restore);
        }
        saveRestores();
        return true;
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
                    // Never silently drop: a skipped entry is a player's
                    // inventory that never comes back.
                    plugin.getLogger().warning("Skipping restore entry " + key + " for " + id
                            + ": no snapshot found in snapshots.yml.");
                    continue;
                }
                // The world is deliberately NOT resolved here. Plugins with the
                // default STARTUP load order enable before any world exists, so
                // resolving now would drop every pending restore on restart.
                // PendingRestore resolves it lazily when the restore is applied.
                target.put(id, new PendingRestore(
                        snapshot, section.getString(key + ".return.world"),
                        section.getDouble(key + ".return.x"), section.getDouble(key + ".return.y"),
                        section.getDouble(key + ".return.z"),
                        (float) section.getDouble(key + ".return.yaw"), (float) section.getDouble(key + ".return.pitch"),
                        section.getString(key + ".messageKey", "death"),
                        section.getInt(key + ".level")));
            } catch (Exception e) {
                plugin.getLogger().warning("Skipping corrupt restore entry " + key + ": " + e.getMessage());
            }
        }
    }
}
