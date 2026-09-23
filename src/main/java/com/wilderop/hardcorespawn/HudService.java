package com.wilderop.hardcorespawn;

import org.bukkit.entity.Player;

import java.util.UUID;

/** Run HUD operations, isolated so tests can run without a real client. */
public interface HudService {
    void showRunHud(Player player, int level, long questTimeMs);
    void updateHud(Player player, int level, long remainingMs, java.util.List<Quest> hand, int questsCompleted);
    void hideHud(Player player);
    void hideHud(UUID playerId);
    void warnActionBar(Player player, String message);

    /**
     * Optional sidebar configuration; implementations without a sidebar
     * (headless/test HUDs) ignore it.
     */
    default void configureSidebar(boolean enabled, String titleFormat) {}
}
