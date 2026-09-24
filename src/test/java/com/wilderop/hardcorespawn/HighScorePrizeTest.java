package com.wilderop.hardcorespawn;

import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * High-score prize: a new personal best level awards BOTH a mob spawner
 * block and a random mob spawn egg.
 */
class HighScorePrizeTest extends PluginTestBase {

    private QuestListener listener;
    private PlayerMock player;

    private void freshRun() {
        player = newPlayer();
        listener = new QuestListener(sessions());
        startRun(player);
    }

    private void setBest(int best) {
        sessions().getLeaderboard().stats(player.getUniqueId()).bestLevel = best;
    }

    private void forceQuest(QuestObjective objective) {
        Session s = sessions().getSession(player.getUniqueId());
        s.hand.clear();
        s.hand.add(new Quest(s.level + 1, List.of("test-template"), List.of(objective)));
    }

    private void completeOneBreakQuest() {
        forceQuest(new QuestObjective(QuestType.BREAK, "OAK_LOG", 1, "Chop 1 oak log"));
        listener.progressBreak(player, "OAK_LOG", 1);
    }

    private boolean hasItem(Material material) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.getType() == material) {
                return true;
            }
        }
        return false;
    }

    private boolean hasSpawnEgg() {
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.getType().name().endsWith("_SPAWN_EGG")) {
                return true;
            }
        }
        return false;
    }

    @Test
    void prizeOnNewBest() {
        freshRun(); // best starts at 0
        assertFalse(hasItem(Material.SPAWNER));
        assertFalse(hasSpawnEgg());

        completeOneBreakQuest(); // reached level 2 > 0

        assertTrue(hasItem(Material.SPAWNER), "a new best must award a spawner block");
        assertTrue(hasSpawnEgg(), "a new best must award a spawn egg");
    }

    @Test
    void noPrizeWithoutNewBest() {
        freshRun();
        setBest(5);

        completeOneBreakQuest(); // reached level 2, below the best of 5

        assertFalse(hasItem(Material.SPAWNER), "no spawner without a new best");
        assertFalse(hasSpawnEgg(), "no egg without a new best");
    }

    @Test
    void prizeReawardedOnEachNewBestLevel() {
        freshRun();
        setBest(2);

        completeOneBreakQuest(); // reached level 2: ties the best, no prize
        assertFalse(hasItem(Material.SPAWNER), "tying the best is not a new high score");

        completeOneBreakQuest(); // reached level 3 > 2: prize
        assertTrue(hasItem(Material.SPAWNER), "beating the best must award the spawner");
        assertTrue(hasSpawnEgg(), "beating the best must award the egg");
    }

    @Test
    void hardcoreCommandAnnouncesThePrize() {
        PlayerMock p = newPlayer();
        p.performCommand("hardcore");
        String prompt = p.nextMessage(); // confirm-prompt
        assertNotNull(prompt);
        String announce = p.nextMessage(); // highscore-prize-announce
        assertNotNull(announce, "/hardcore must announce the high-score prize");
        assertTrue(announce.toLowerCase(java.util.Locale.ROOT).contains("high score"),
                "the announce must mention the high score: " + announce);
        assertTrue(announce.contains("Spawner"), "the announce must mention the spawner: " + announce);
    }
}
