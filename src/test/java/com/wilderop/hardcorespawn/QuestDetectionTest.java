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
        s.quest = new Quest(s.level + 1, List.of("test-template"), List.of(objective));
    }

    @Test
    void breakProgressCompletesQuestAndAdvances() {
        freshRun();
        forceQuest(new QuestObjective(QuestType.BREAK, "OAK_LOG", 3, "Chop 3 oak logs"));

        listener.progressBreak(player, "OAK_LOG", 1);
        listener.progressBreak(player, "STONE", 10); // wrong target: ignored
        Session s = sessions().getSession(player.getUniqueId());
        assertEquals(1, s.quest.objectives().get(0).progress());

        listener.progressBreak(player, "OAK_LOG", 2);
        s = sessions().getSession(player.getUniqueId());
        assertEquals(1, s.level, "completing a quest should advance the level");
        assertEquals(2, s.quest.level(), "a fresh quest should be assigned");
        assertTrue(s.questDeadlineMs > System.currentTimeMillis(), "deadline should reset");
    }

    @Test
    void killRequiresExactEntityType() {
        freshRun();
        forceQuest(new QuestObjective(QuestType.KILL, "ZOMBIE", 2, "Slay 2 zombies"));

        listener.progressKill(player, "SKELETON", 2);
        assertEquals(0, sessions().getSession(player.getUniqueId()).quest.objectives().get(0).progress());

        listener.progressKill(player, "ZOMBIE", 2);
        assertEquals(1, sessions().getSession(player.getUniqueId()).level);
    }

    @Test
    void craftAndSmeltProgress() {
        freshRun();
        forceQuest(new QuestObjective(QuestType.CRAFT, "FURNACE", 2, "Craft 2 furnaces"));
        listener.progressCraft(player, "FURNACE", 1);
        assertEquals(1, sessions().getSession(player.getUniqueId()).quest.objectives().get(0).progress());
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
        assertEquals(3, sessions().getSession(player.getUniqueId()).quest.objectives().get(0).progress());

        player.getInventory().setItem(1, new ItemStack(Material.DIAMOND, 2));
        sessions().checkObtain(player);
        assertEquals(1, sessions().getSession(player.getUniqueId()).level, "holding 5 diamonds completes the quest");
    }

    @Test
    void multiObjectiveNeedsAllParts() {
        freshRun();
        Session s = sessions().getSession(player.getUniqueId());
        s.quest = new Quest(13, List.of("a", "b"), List.of(
                new QuestObjective(QuestType.BREAK, "OAK_LOG", 2, "Chop 2 oak logs"),
                new QuestObjective(QuestType.KILL, "ZOMBIE", 2, "Slay 2 zombies")));

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
}
