package com.wilderop.hardcorespawn;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** BossBar + action bar HUD backed by the real server. */
public final class BukkitHudService implements HudService {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();
    private final Map<UUID, BossBar> bars = new HashMap<>();
    private final Map<UUID, Long> questTimes = new HashMap<>();

    private static String barTitle(int level, long remainingMs) {
        long total = Math.max(0, remainingMs / 1000);
        return "Hardcore — Level " + level + "  " + (total / 60) + ":" + String.format("%02d", total % 60);
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
    }

    @Override
    public void updateHud(Player player, int level, long remainingMs) {
        BossBar bar = bars.get(player.getUniqueId());
        if (bar == null) {
            return;
        }
        bar.name(Component.text(barTitle(level, remainingMs)));
        long total = questTimes.getOrDefault(player.getUniqueId(), 300_000L);
        bar.progress((float) Math.max(0.0, Math.min(1.0, (double) remainingMs / total)));
    }

    @Override
    public void hideHud(Player player) {
        hideHud(player.getUniqueId());
    }

    @Override
    public void hideHud(UUID playerId) {
        BossBar bar = bars.remove(playerId);
        questTimes.remove(playerId);
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
}
