package com.wilderop.hardcorespawn;

import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * High-score prize: a new SERVER record level awards BOTH a mob spawner
 * block and a random mob spawn egg. Personal bests below the server record
 * win nothing (but are still recorded for the leaderboard).
 */
class HighScorePrizeTest extends PluginTestBase {

    private QuestListener listener;
    private PlayerMock player;

    @BeforeEach
    void clearLeaderboard() {
        plugin.getLeaderboardManager().clear();
    }

    private void freshRun() {
        player = newPlayer();
        listener = new QuestListener(sessions());
        startRun(player);
    }

    /** End the current run and start a new one with the same player. */
    private void restartRun() {
        player.performCommand("hardcore quit");
        assertFalse(sessions().hasSession(player.getUniqueId()), "run should be over after quit");
        listener = new QuestListener(sessions());
        startRun(player);
    }

    private void setBest(UUID id, int best) {
        sessions().getLeaderboard().stats(id).bestLevel = best;
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

    private int countItem(Material material) {
        int count = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.getType() == material) {
                count += item.getAmount();
            }
        }
        return count;
    }

    private int countSpawnEggs() {
        int count = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.getType().name().endsWith("_SPAWN_EGG")) {
                count += item.getAmount();
            }
        }
        return count;
    }

    @Test
    void prizeOnNewServerRecord() {
        freshRun(); // best 0, server record 0
        assertFalse(hasItem(Material.SPAWNER));
        assertFalse(hasSpawnEgg());

        completeOneBreakQuest(); // reached level 2 > record 0

        assertTrue(hasItem(Material.SPAWNER), "a new server record must award a spawner block");
        assertTrue(hasSpawnEgg(), "a new server record must award a spawn egg");
    }

    @Test
    void noPrizeForPersonalBestBelowServerRecord() {
        PlayerMock other = newPlayer();
        setBest(other.getUniqueId(), 5); // server record held by someone else
        freshRun(); // this player's best starts at 0

        completeOneBreakQuest(); // reached level 2: a personal best, but below the record

        assertFalse(hasItem(Material.SPAWNER), "no spawner for a personal best below the server record");
        assertFalse(hasSpawnEgg(), "no egg for a personal best below the server record");
        assertEquals(2, sessions().getLeaderboard().stats(player.getUniqueId()).bestLevel,
                "the personal best must still be recorded for the leaderboard");
    }

    @Test
    void tieDoesNotWin() {
        PlayerMock other = newPlayer();
        setBest(other.getUniqueId(), 2);
        freshRun();

        completeOneBreakQuest(); // reached level 2: ties the record, does not beat it

        assertFalse(hasItem(Material.SPAWNER), "tying the server record is not a new high score");
        assertFalse(hasSpawnEgg(), "tying the server record is not a new high score");
    }

    @Test
    void recordHolderWinsAgainOnNewRecord() {
        freshRun();
        setBest(player.getUniqueId(), 1); // this player holds the record at 1

        completeOneBreakQuest(); // reached level 2 > record 1

        assertTrue(hasItem(Material.SPAWNER), "beating your own server record must award the spawner");
        assertTrue(hasSpawnEgg(), "beating your own server record must award the egg");
    }

    @Test
    void onlyOnePrizePerRun() {
        freshRun(); // best 0, server record 0

        completeOneBreakQuest(); // reached level 2 > record 0: wins the run's prize
        assertTrue(hasItem(Material.SPAWNER), "the record level must award the spawner");
        assertTrue(hasSpawnEgg(), "the record level must award the egg");

        completeOneBreakQuest(); // reached level 3 > 2: no second prize
        completeOneBreakQuest(); // reached level 4 > 3: no third prize

        assertEquals(1, countItem(Material.SPAWNER), "a run must award at most one spawner");
        assertEquals(1, countSpawnEggs(), "a run must award at most one spawn egg");
    }

    @Test
    void newRunCanWinAgain() {
        freshRun();

        completeOneBreakQuest(); // reached level 2 > record 0: wins
        assertTrue(hasItem(Material.SPAWNER));

        restartRun(); // same player, fresh run; inventory restored, flag reset

        completeOneBreakQuest(); // reached level 2: ties the record, no prize
        assertFalse(hasItem(Material.SPAWNER), "tying the record wins nothing");

        completeOneBreakQuest(); // reached level 3 > 2: wins again
        assertTrue(hasItem(Material.SPAWNER), "a new run that sets a new record must win");
        assertTrue(hasSpawnEgg(), "a new run that sets a new record must win the egg");
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
