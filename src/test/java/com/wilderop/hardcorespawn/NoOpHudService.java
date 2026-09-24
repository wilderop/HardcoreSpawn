package com.wilderop.hardcorespawn;

import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

/** Headless HUD for unit tests. */
public final class NoOpHudService implements HudService {
    public int warnings;
    public int updates;
    public int lastHandSize = -1;
    public int lastQuestsCompleted = -1;
    public String lastWarning;

    @Override
    public void showRunHud(Player player, int level, long questTimeMs) {}

    @Override
    public void updateHud(Player player, int level, long remainingMs,
                          List<Quest> hand, int questsCompleted) {
        updates++;
        lastHandSize = hand.size();
        lastQuestsCompleted = questsCompleted;
    }

    @Override
    public void hideHud(Player player) {}

    @Override
    public void hideHud(UUID playerId) {}

    @Override
    public void warnActionBar(Player player, String message) {
        warnings++;
        lastWarning = message;
    }
}
