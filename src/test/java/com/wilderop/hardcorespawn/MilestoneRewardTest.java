package com.wilderop.hardcorespawn;

import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Milestone reward: every N completed quests grants a random mob spawn egg. */
class MilestoneRewardTest extends PluginTestBase {

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

    private boolean hasSpawnEgg() {
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.getType().name().endsWith("_SPAWN_EGG")) {
                return true;
            }
        }
        return false;
    }

    private void completeOneBreakQuest() {
        forceQuest(new QuestObjective(QuestType.BREAK, "OAK_LOG", 1, "Chop 1 oak log"));
        listener.progressBreak(player, "OAK_LOG", 1);
    }

    @Test
    void eggGrantedOnMilestoneQuest() {
        freshRun();
        Session s = sessions().getSession(player.getUniqueId());
        s.questsCompleted = 29; // next completion is #30
        assertFalse(hasSpawnEgg(), "no egg before the milestone");

        completeOneBreakQuest();

        assertEquals(30, sessions().getSession(player.getUniqueId()).questsCompleted);
        assertTrue(hasSpawnEgg(), "a random mob spawn egg should be granted at 30 quests");
    }

    @Test
    void noEggBeforeMilestone() {
        freshRun();
        Session s = sessions().getSession(player.getUniqueId());
        s.questsCompleted = 27; // next completion is #28

        completeOneBreakQuest();

        assertEquals(28, sessions().getSession(player.getUniqueId()).questsCompleted);
        assertFalse(hasSpawnEgg(), "no egg should be granted before the milestone");
    }

    @Test
    void eggGrantedAgainAtSixty() {
        freshRun();
        Session s = sessions().getSession(player.getUniqueId());
        s.questsCompleted = 59; // next completion is #60

        completeOneBreakQuest();

        assertEquals(60, sessions().getSession(player.getUniqueId()).questsCompleted);
        assertTrue(hasSpawnEgg(), "the milestone should repeat every 30 quests");
    }
}
