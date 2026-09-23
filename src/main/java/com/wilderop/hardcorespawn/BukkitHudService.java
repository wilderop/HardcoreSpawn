package com.wilderop.hardcorespawn;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** BossBar + action bar + sidebar scoreboard HUD backed by the real server. */
public final class BukkitHudService implements HudService {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();
    /** Max visible characters for a quest description on the sidebar. */
    static final int SIDEBAR_DESC_MAX = 26;

    private final Map<UUID, BossBar> bars = new HashMap<>();
    private final Map<UUID, Long> questTimes = new HashMap<>();
    private final Map<UUID, Scoreboard> sidebars = new HashMap<>();
    /** Last rendered sidebar content per player; skips no-op rebuilds (flicker). */
    private final Map<UUID, String> sidebarCache = new HashMap<>();
    /** Last rendered sidebar lines per player, so stale entries reset exactly. */
    private final Map<UUID, List<String>> sidebarLines = new HashMap<>();

    private boolean sidebarEnabled = true;
    private String sidebarTitle = "§6§lHARDCORE §r§7Lv {level}";

    private static String barTitle(int level, long remainingMs) {
        long total = Math.max(0, remainingMs / 1000);
        return "Hardcore — Level " + level + "  " + (total / 60) + ":" + String.format("%02d", total % 60);
    }

    @Override
    public void configureSidebar(boolean enabled, String titleFormat) {
        this.sidebarEnabled = enabled;
        this.sidebarTitle = titleFormat;
        if (!enabled) {
            for (UUID id : List.copyOf(sidebars.keySet())) {
                clearSidebar(id);
            }
        }
    }

    @Override
    public void showRunHud(Player player, int level, long questTimeMs) {
        hideHud(player.getUniqueId());
        BossBar bar = BossBar.bossBar(
                Component.text(barTitle(level, questTimeMs)),
                1.0f,
                BossBar.Color.RED,
                BossBar.Overlay.PROGRESS);
        bars.put(player.getUniqueId(), bar);
        questTimes.put(player.getUniqueId(), questTimeMs);
        player.showBossBar(bar);
        if (sidebarEnabled) {
            Scoreboard board = Bukkit.getScoreboardManager().getNewScoreboard();
            board.registerNewObjective("hardcore", Criteria.DUMMY,
                    LEGACY.deserialize(sidebarTitle(level)));
            Objective objective = board.getObjective("hardcore");
            if (objective != null) {
                objective.setDisplaySlot(DisplaySlot.SIDEBAR);
            }
            sidebars.put(player.getUniqueId(), board);
            player.setScoreboard(board);
        }
    }

    @Override
    public void updateHud(Player player, int level, long remainingMs,
                          List<Quest> hand, int questsCompleted) {
        BossBar bar = bars.get(player.getUniqueId());
        if (bar != null) {
            bar.name(Component.text(barTitle(level, remainingMs)));
            long total = questTimes.getOrDefault(player.getUniqueId(), 300_000L);
            bar.progress((float) Math.max(0.0, Math.min(1.0, (double) remainingMs / total)));
        }
        if (sidebarEnabled) {
            updateSidebar(player, hand, level, questsCompleted);
        }
    }

    @Override
    public void hideHud(Player player) {
        hideHud(player.getUniqueId());
    }

    @Override
    public void hideHud(UUID playerId) {
        BossBar bar = bars.remove(playerId);
        questTimes.remove(playerId);
        clearSidebar(playerId);
        if (bar != null) {
            Player p = Bukkit.getPlayer(playerId);
            if (p != null) {
                p.hideBossBar(bar);
            }
        }
    }

    @Override
    public void warnActionBar(Player player, String message) {
        // Config messages use legacy section codes; deserialize them so the
        // player sees colors instead of literal '§' characters.
        player.sendActionBar(LEGACY.deserialize(message));
    }

    private String sidebarTitle(int level) {
        return sidebarTitle.replace("{level}", String.valueOf(level));
    }

    private void updateSidebar(Player player, List<Quest> hand, int level, int questsCompleted) {
        Scoreboard board = sidebars.get(player.getUniqueId());
        if (board == null) {
            return;
        }
        Objective objective = board.getObjective("hardcore");
        if (objective == null) {
            return;
        }
        String title = sidebarTitle(level);
        List<String> lines = renderSidebarLines(hand, questsCompleted);
        String signature = title + "\n" + String.join("\n", lines);
        if (signature.equals(sidebarCache.get(player.getUniqueId()))) {
            return; // nothing changed; rebuilding every tick would flicker
        }
        sidebarCache.put(player.getUniqueId(), signature);
        objective.displayName(LEGACY.deserialize(title));
        for (String entry : sidebarLines.getOrDefault(player.getUniqueId(), List.of())) {
            board.resetScores(entry);
        }
        sidebarLines.put(player.getUniqueId(), lines);
        int score = lines.size();
        for (String line : lines) {
            objective.getScore(line).setScore(score--);
        }
    }

    private void clearSidebar(UUID playerId) {
        if (sidebars.remove(playerId) != null) {
            sidebarCache.remove(playerId);
            sidebarLines.remove(playerId);
            Player p = Bukkit.getPlayer(playerId);
            if (p != null) {
                p.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
            }
        }
    }

    /**
     * Render the sidebar body: one header + one progress line per quest,
     * then a completed-quests footer. Package-private for tests.
     */
    static List<String> renderSidebarLines(List<Quest> hand, int questsCompleted) {
        List<String> lines = new ArrayList<>();
        int n = 0;
        for (Quest quest : hand) {
            n++;
            lines.add("§e" + n + ". §f" + truncate(quest.getDescription(), SIDEBAR_DESC_MAX));
            // Trailing reset codes keep the entry unique when two quests share
            // the same progress text; they render invisibly.
            lines.add("§7   " + quest.getProgressText() + "§r".repeat(n));
        }
        if (!lines.isEmpty()) {
            lines.add("§8§m----------------");
        }
        lines.add("§7Completed: §a" + questsCompleted);
        return lines;
    }

    private static String truncate(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        return text.substring(0, Math.max(0, max - 1)) + "…";
    }
}
