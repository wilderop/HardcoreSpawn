package com.wilderop.hardcorespawn;

import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Quest progress detection per type, via the listener seams. */
class QuestDetectionTest extends PluginTestBase {

    private QuestListener listener;
    private PlayerMock player;

    private void freshRun() {
        player = newPlayer();
        listener = new QuestListener(sessions());
        startRun(player);
    }

    private void forceQuest(QuestObjective objective) {
        Session s = sessions().getSession(player.getUniqueId());
        s.hand.clear();
        s.hand.add(new Quest(s.level + 1, List.of("test-template"), List.of(objective)));
    }

    @Test
    void breakProgressCompletesQuestAndAdvances() {
        freshRun();
        forceQuest(new QuestObjective(QuestType.BREAK, "OAK_LOG", 3, "Chop 3 oak logs"));

        listener.progressBreak(player, "OAK_LOG", 1);
        listener.progressBreak(player, "STONE", 10); // wrong target: ignored
        Session s = sessions().getSession(player.getUniqueId());
        assertEquals(1, s.hand.get(0).objectives().get(0).progress());

        listener.progressBreak(player, "OAK_LOG", 2);
        s = sessions().getSession(player.getUniqueId());
        assertEquals(1, s.level, "completing a quest should advance the level");
        assertEquals(2, s.hand.get(0).level(), "a fresh quest should be assigned");
        assertTrue(s.questDeadlineMs > System.currentTimeMillis(), "deadline should reset");
    }

    @Test
    void killRequiresExactEntityType() {
        freshRun();
        forceQuest(new QuestObjective(QuestType.KILL, "ZOMBIE", 2, "Slay 2 zombies"));

        listener.progressKill(player, "SKELETON", 2);
        assertEquals(0, sessions().getSession(player.getUniqueId()).hand.get(0).objectives().get(0).progress());

        listener.progressKill(player, "ZOMBIE", 2);
        assertEquals(1, sessions().getSession(player.getUniqueId()).level);
    }

    @Test
    void craftAndSmeltProgress() {
        freshRun();
        forceQuest(new QuestObjective(QuestType.CRAFT, "FURNACE", 2, "Craft 2 furnaces"));
        listener.progressCraft(player, "FURNACE", 1);
        assertEquals(1, sessions().getSession(player.getUniqueId()).hand.get(0).objectives().get(0).progress());
        listener.progressCraft(player, "FURNACE", 1);
        assertEquals(1, sessions().getSession(player.getUniqueId()).level);

        forceQuest(new QuestObjective(QuestType.SMELT, "IRON_INGOT", 4, "Smelt 4 iron ingots"));
        listener.progressSmelt(player, "IRON_INGOT", 4);
        assertEquals(2, sessions().getSession(player.getUniqueId()).level);
    }

    @Test
    void obtainCountsInventoryHoldings() {
        freshRun();
        forceQuest(new QuestObjective(QuestType.OBTAIN, "DIAMOND", 5, "Hold 5 diamonds at once"));

        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 3));
        sessions().checkObtain(player);
        assertEquals(3, sessions().getSession(player.getUniqueId()).hand.get(0).objectives().get(0).progress());

        player.getInventory().setItem(1, new ItemStack(Material.DIAMOND, 2));
        sessions().checkObtain(player);
        assertEquals(1, sessions().getSession(player.getUniqueId()).level, "holding 5 diamonds completes the quest");
    }

    @Test
    void multiObjectiveNeedsAllParts() {
        freshRun();
        Session s = sessions().getSession(player.getUniqueId());
        s.hand.clear();
        s.hand.add(new Quest(13, List.of("a", "b"), List.of(
                new QuestObjective(QuestType.BREAK, "OAK_LOG", 2, "Chop 2 oak logs"),
                new QuestObjective(QuestType.KILL, "ZOMBIE", 2, "Slay 2 zombies"))));

        listener.progressBreak(player, "OAK_LOG", 2);
        assertEquals(0, sessions().getSession(player.getUniqueId()).level, "one of two objectives is not enough");

        listener.progressKill(player, "ZOMBIE", 2);
        assertEquals(1, sessions().getSession(player.getUniqueId()).level);
    }

    @Test
    void progressIgnoredWithoutSession() {
        PlayerMock outsider = newPlayer();
        // Must not throw and must not create anything.
        listener = new QuestListener(sessions());
        listener.progressBreak(outsider, "OAK_LOG", 64);
        assertFalse(sessions().hasSession(outsider.getUniqueId()));
    }

    @Test
    void runStartsWithThreeQuests() {
        freshRun();
        Session s = sessions().getSession(player.getUniqueId());
        assertEquals(Session.HAND_SIZE, s.hand.size(), "run should deal a full hand of quests");
        for (Quest q : s.hand) {
            assertEquals(1, q.level());
        }
        // No two quests in the opening hand share a template.
        long distinct = s.hand.stream()
                .flatMap(q -> q.templateIds().stream())
                .distinct().count();
        int total = s.hand.stream().mapToInt(q -> q.templateIds().size()).sum();
        assertEquals(total, distinct, "opening hand quests must not share templates");
    }

    @Test
    void completingOneQuestResetsTimerAndDealsReplacement() {
        freshRun();
        listener = new QuestListener(sessions());
        Session s = sessions().getSession(player.getUniqueId());
        assertEquals(Session.HAND_SIZE, s.hand.size());

        // Deterministic hand with known targets.
        Quest q1 = new Quest(1, List.of("test-a"),
                List.of(new QuestObjective(QuestType.BREAK, "OAK_LOG", 3, "Chop 3 oak logs")));
        Quest q2 = new Quest(1, List.of("test-b"),
                List.of(new QuestObjective(QuestType.BREAK, "STONE", 5, "Mine 5 stone")));
        Quest q3 = new Quest(1, List.of("test-c"),
                List.of(new QuestObjective(QuestType.KILL, "ZOMBIE", 2, "Slay 2 zombies")));
        s.hand.clear();
        s.hand.add(q1);
        s.hand.add(q2);
        s.hand.add(q3);
        int sizeBefore = s.hand.size();

        // Shrink the deadline so we can prove the completion bonus moves it forward.
        s.questDeadlineMs = System.currentTimeMillis() + 5_000;
        long deadlineBefore = s.questDeadlineMs;

        // Partial progress on the second quest.
        listener.progressBreak(player, "STONE", 2);
        assertEquals(2, s.hand.get(1).objectives().get(0).progress());

        // Complete the first quest.
        listener.progressBreak(player, "OAK_LOG", 3);

        Session after = sessions().getSession(player.getUniqueId());
        assertEquals(1, after.level, "completing any one quest advances the level");
        assertEquals(sizeBefore, after.hand.size(), "a replacement is dealt for the completed quest");
        assertFalse(after.hand.contains(q1), "the completed quest leaves the hand");
        assertTrue(after.questDeadlineMs - deadlineBefore >= 295_000,
                "completing a quest adds ~5 minutes to the clock");

        // The untouched quest kept its partial progress.
        assertTrue(after.hand.contains(q2), "uncompleted quests stay in the hand");
        assertEquals(2, q2.objectives().get(0).progress(),
                "progress on the other quests must survive a completion");

        // The replacement is level 2 and shares no template with the rest of the hand.
        Quest replacement = after.hand.stream()
                .filter(q -> q != q2 && q != q3)
                .findFirst().orElseThrow();
        assertEquals(2, replacement.level(), "replacement quest scales with completed count");
        List<String> otherIds = List.of(q2, q3).stream()
                .flatMap(q -> q.templateIds().stream()).toList();
        for (String id : replacement.templateIds()) {
            assertFalse(otherIds.contains(id), "replacement must not duplicate the hand's templates");
        }
    }
}
