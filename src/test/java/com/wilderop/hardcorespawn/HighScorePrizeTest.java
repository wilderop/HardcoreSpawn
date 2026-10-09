package com.wilderop.hardcorespawn;

import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Prize tracks, evaluated on every level-up against the live server record:
 *
 * <ul>
 *   <li>Mob spawner: 100% when beating the record, otherwise decaying 10x per
 *   level short of it (tie 10%, one short 1%, two short 0.1%, ...). There is no
 *   once-per-run cap: every new record level wins a spawner.</li>
 *   <li>Spawn egg: every 5 completed levels, one 10% roll for a random egg
 *   from the passive-mob pool.</li>
 * </ul>
 *
 * Personal bests are still recorded for the leaderboard even when they win
 * nothing.
 */
class HighScorePrizeTest extends PluginTestBase {

    private QuestListener listener;
    private PlayerMock player;

    @BeforeEach
    void clearLeaderboard() {
        plugin.getLeaderboardManager().clear();
        // The SessionManager is shared across test methods: reset the prize
        // RNG so a scripted Random from one test can't leak into the next.
        sessions().setPrizeRandom(new Random());
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

    /** Jump the current run to the given completed-level count. */
    private void setLevel(int level) {
        sessions().getSession(player.getUniqueId()).level = level;
    }

    /** RNG that always returns the same double (and egg index 0). */
    private static Random fixedRandom(double value) {
        return new Random() {
            @Override
            public double nextDouble() {
                return value;
            }

            @Override
            public int nextInt(int bound) {
                return 0;
            }
        };
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
    void spawnerChanceFormula() {
        assertEquals(1.0, SessionManager.spawnerChance(51, 50), 0.0);
        assertEquals(1.0, SessionManager.spawnerChance(100, 50), 0.0);
        assertEquals(1.0, SessionManager.spawnerChance(2, 0), 0.0);
        assertEquals(0.1, SessionManager.spawnerChance(50, 50), 1e-12);
        assertEquals(0.01, SessionManager.spawnerChance(49, 50), 1e-12);
        assertEquals(0.001, SessionManager.spawnerChance(48, 50), 1e-12);
    }

    @Test
    void formatChanceFormats() {
        assertEquals("100%", SessionManager.formatChance(1.0));
        assertEquals("10%", SessionManager.formatChance(0.1));
        assertEquals("1%", SessionManager.formatChance(0.01));
        assertEquals("0.1%", SessionManager.formatChance(0.001));
        assertEquals("0.01%", SessionManager.formatChance(0.0001));
    }

    @Test
    void spawnerOnNewServerRecord() {
        freshRun(); // best 0, server record 0

        completeOneBreakQuest(); // reached level 2 > record 0: 100% spawner

        assertTrue(hasItem(Material.SPAWNER), "beating the server record must award a spawner block");
        assertFalse(hasSpawnEgg(), "record breaks no longer bundle a spawn egg");
    }

    @Test
    void recordClimbEarnsSpawnerPerLevel() {
        freshRun(); // best 0, server record 0

        completeOneBreakQuest(); // reached 2 > 0: spawner, record -> 2
        completeOneBreakQuest(); // reached 3 > 2: spawner, record -> 3
        completeOneBreakQuest(); // reached 4 > 3: spawner, record -> 4

        assertEquals(3, countItem(Material.SPAWNER),
                "every new server record level must award a spawner (no once-per-run cap)");
        assertEquals(4, sessions().getLeaderboard().serverRecord(),
                "the record must ratchet up live as it is broken");
    }

    @Test
    void noSpawnerBelowRecordWhenUnlucky() {
        PlayerMock other = newPlayer();
        setBest(other.getUniqueId(), 5); // server record held by someone else
        sessions().setPrizeRandom(fixedRandom(1.0)); // lose every roll
        freshRun();

        completeOneBreakQuest(); // reached level 2: personal best, below the record

        assertFalse(hasItem(Material.SPAWNER), "a lost sub-record roll pays no spawner");
        assertFalse(hasSpawnEgg(), "no egg outside a 5-level milestone");
        assertEquals(2, sessions().getLeaderboard().stats(player.getUniqueId()).bestLevel,
                "the personal best must still be recorded for the leaderboard");
    }

    @Test
    void tieRollsTenPercent() {
        PlayerMock other = newPlayer();
        setBest(other.getUniqueId(), 2); // server record 2
        sessions().setPrizeRandom(fixedRandom(0.05)); // win the 10% roll
        freshRun();

        completeOneBreakQuest(); // reached level 2 ties the record: 10% roll

        assertTrue(hasItem(Material.SPAWNER), "tying the record rolls 10% for a spawner");
        assertFalse(hasSpawnEgg(), "no egg outside a 5-level milestone");

        sessions().setPrizeRandom(fixedRandom(0.5)); // lose the 10% roll
        restartRun();

        completeOneBreakQuest(); // reached level 2 ties the record again

        assertFalse(hasItem(Material.SPAWNER), "a lost 10% roll pays no spawner");
    }

    @Test
    void oneShortRollsOnePercent() {
        PlayerMock other = newPlayer();
        setBest(other.getUniqueId(), 10); // server record 10
        sessions().setPrizeRandom(fixedRandom(0.005)); // win the 1% roll
        freshRun();
        setLevel(7);

        completeOneBreakQuest(); // reached level 9: one short of the record

        assertTrue(hasItem(Material.SPAWNER), "one short of the record rolls 1% for a spawner");
        assertFalse(hasSpawnEgg(), "no egg outside a 5-level milestone");

        sessions().setPrizeRandom(fixedRandom(0.05)); // lose the 1% roll
        restartRun();
        setLevel(7);

        completeOneBreakQuest(); // reached level 9 again

        assertFalse(hasItem(Material.SPAWNER), "a lost 1% roll pays no spawner");
    }

    @Test
    void milestoneEggEveryFiveLevels() {
        sessions().setPrizeRandom(fixedRandom(0.0)); // win every roll
        freshRun();
        setLevel(4);

        completeOneBreakQuest(); // 5th completed level: 10% egg roll

        assertTrue(hasSpawnEgg(), "the 5th completed level must roll for a spawn egg");

        sessions().setPrizeRandom(fixedRandom(0.5)); // lose the 10% roll
        restartRun();
        setLevel(4);

        completeOneBreakQuest(); // 5th completed level again

        assertFalse(hasSpawnEgg(), "a lost 10% roll pays no egg");
    }

    @Test
    void milestoneEggRepeatsWithinRun() {
        sessions().setPrizeRandom(fixedRandom(0.0)); // win every roll
        freshRun();

        for (int i = 0; i < 10; i++) {
            completeOneBreakQuest();
        }

        assertEquals(2, countSpawnEggs(),
                "each 5-level milestone must roll independently within one run");
    }

    @Test
    void newRunCanWinAgain() {
        sessions().setPrizeRandom(fixedRandom(0.0)); // win every roll
        freshRun();

        completeOneBreakQuest(); // reached level 2 > record 0: spawner
        assertTrue(hasItem(Material.SPAWNER));

        restartRun(); // same player, fresh run; inventory restored

        completeOneBreakQuest(); // reached level 2 ties record 2: 10% roll wins
        completeOneBreakQuest(); // reached level 3 > 2: spawner again

        assertEquals(2, countItem(Material.SPAWNER),
                "a new run rolls again: tie 10% plus the new record level");
    }

    @Test
    void prizeMessagesWarnToBank() {
        for (String key : List.of("prize-spawner-won", "prize-spawner-dropped")) {
            String msg = sessions().getConfig().format(key,
                    Map.of("level", "2", "chance", "100%"));
            assertTrue(msg.toLowerCase(java.util.Locale.ROOT).contains("chest"),
                    "the spawner prize message must tell the winner to bank it in a chest: " + msg);
        }
        for (String key : List.of("prize-egg-won", "prize-egg-dropped")) {
            String msg = sessions().getConfig().format(key,
                    Map.of("level", "6", "mob", "Pig"));
            assertTrue(msg.toLowerCase(java.util.Locale.ROOT).contains("chest"),
                    "the egg prize message must tell the winner to bank it in a chest: " + msg);
        }
        String lost = sessions().getConfig().format("prize-egg-lost", Map.of("level", "6"));
        assertTrue(lost.contains("6"), "the egg loss message must name the milestone level: " + lost);
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
