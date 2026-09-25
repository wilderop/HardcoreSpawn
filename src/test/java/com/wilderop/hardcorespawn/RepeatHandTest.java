package com.wilderop.hardcorespawn;

import org.bukkit.event.player.PlayerRespawnEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * No free re-rolls: ending a run with zero quests completed deals the same
 * hand on the player's next run.
 */
class RepeatHandTest extends PluginTestBase {

    private PlayerMock player;
    private QuestListener listener;

    @BeforeEach
    void clearState() {
        plugin.getLeaderboardManager().clear();
        sessions().getRepeatHands().clear();
    }

    private void freshRun() {
        player = newPlayer();
        listener = new QuestListener(sessions());
        startRun(player);
    }

    private void nextRun() {
        listener = new QuestListener(sessions());
        startRun(player);
    }

    private List<String> handDescriptions() {
        List<String> out = new ArrayList<>();
        for (Quest q : sessions().getSession(player.getUniqueId()).hand) {
            out.add(q.getDescription());
        }
        return out;
    }

    private void completeOneBreakQuest() {
        Session s = sessions().getSession(player.getUniqueId());
        s.hand.clear();
        s.hand.add(new Quest(s.level + 1, List.of("test-template"),
                List.of(new QuestObjective(QuestType.BREAK, "OAK_LOG", 1, "Chop 1 oak log"))));
        listener.progressBreak(player, "OAK_LOG", 1);
    }

    @Test
    void quitWithZeroCompletedRepeatsHand() {
        freshRun();
        List<String> first = handDescriptions();
        assertEquals(4, first.size(), "a run should deal 4 quests");

        player.performCommand("hardcore quit");
        assertFalse(sessions().hasSession(player.getUniqueId()), "quit should end the run");

        nextRun();

        assertEquals(first, handDescriptions(),
                "bailing with zero completions must deal the same hand next run");
    }

    @Test
    void deathWithZeroCompletedRepeatsHand() {
        freshRun();
        List<String> first = handDescriptions();

        sessions().endRun(player.getUniqueId(), ExitCause.IN_WORLD_DEATH);
        assertFalse(sessions().hasSession(player.getUniqueId()), "death should end the run");

        // Consume the pending respawn restore the way a real respawn would.
        SessionListener sessionListener = new SessionListener(sessions(), plugin.getSnapshotManager());
        sessionListener.onRespawn(new PlayerRespawnEvent(player, player.getLocation(), false));

        nextRun();

        assertEquals(first, handDescriptions(),
                "dying with zero completions must deal the same hand next run");
    }

    @Test
    void completingAQuestClearsTheDebt() {
        freshRun();
        completeOneBreakQuest(); // one completion: the hand is now "used"

        player.performCommand("hardcore quit");

        assertTrue(sessions().getRepeatHands().isEmpty(),
                "completing a quest must clear any repeat-hand debt");
    }

    @Test
    void quittingTwiceInARowKeepsTheSameHand() {
        freshRun();
        List<String> first = handDescriptions();

        player.performCommand("hardcore quit");
        nextRun();
        assertEquals(first, handDescriptions(), "first repeat must match");

        // Bail again with zero completions: the debt survives.
        player.performCommand("hardcore quit");
        nextRun();
        assertEquals(first, handDescriptions(),
                "bailing again must still deal the original hand");
    }

    @Test
    void adminResetIsAFreshStart() {
        freshRun();

        sessions().endRun(player.getUniqueId(), ExitCause.ADMIN_RESET);

        assertTrue(sessions().getRepeatHands().isEmpty(),
                "an admin reset must not owe the player the old hand");
    }

    @Test
    void owedHandSurvivesRestart() {
        freshRun();
        List<String> first = handDescriptions();

        player.performCommand("hardcore quit");
        assertEquals(1, sessions().getRepeatHands().size(), "the quit should stash the hand");

        // Simulate a restart: drop in-memory state, reload from disk.
        sessions().getRepeatHands().clear();
        sessions().loadRepeatHands();

        nextRun();

        assertEquals(first, handDescriptions(),
                "the owed hand must survive a server restart");
    }

    @Test
    void repeatHandNoticeMentionsSameQuests() {
        String notice = sessions().getConfig().format("repeat-hand-notice", Map.of());
        assertFalse(notice.isBlank(), "repeat-hand-notice must be configured");
    }
}
