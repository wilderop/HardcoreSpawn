package com.wilderop.hardcorespawn;

import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.bukkit.Location;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Solitude: while another runner is within the solitude radius, quest
 * progress freezes (the clock keeps burning). Separating resumes progress.
 */
class SolitudeTest extends PluginTestBase {

    private QuestListener listener;
    private PlayerMock alice;
    private PlayerMock bob;

    private void freshRuns() {
        alice = newPlayer();
        bob = newPlayer();
        listener = new QuestListener(sessions());
        startRun(alice);
        startRun(bob);
    }

    private QuestObjective forceBreakQuest(PlayerMock p, int amount) {
        Session s = sessions().getSession(p.getUniqueId());
        s.hand.clear();
        QuestObjective obj = new QuestObjective(QuestType.BREAK, "OAK_LOG", amount, "Chop oak logs");
        s.hand.add(new Quest(s.level + 1, List.of("test-template"), List.of(obj)));
        return obj;
    }

    private Session sessionOf(PlayerMock p) {
        return sessions().getSession(p.getUniqueId());
    }

    @Test
    void progressFreezesWhenRunnersAreClose() {
        freshRuns();
        alice.teleport(new Location(world, 100, 65, 100));
        bob.teleport(new Location(world, 150, 65, 100)); // 50 blocks: crowded

        sessions().tickSolitude(System.currentTimeMillis());

        assertTrue(sessionOf(alice).crowded, "alice should be crowded");
        assertTrue(sessionOf(bob).crowded, "bob should be crowded");

        QuestObjective obj = forceBreakQuest(alice, 2);
        listener.progressBreak(alice, "OAK_LOG", 1);
        assertEquals(0, obj.progress(), "crowded: break progress must be frozen");
    }

    @Test
    void progressResumesWhenRunnersSeparate() {
        freshRuns();
        alice.teleport(new Location(world, 100, 65, 100));
        bob.teleport(new Location(world, 150, 65, 100));
        sessions().tickSolitude(System.currentTimeMillis());
        assertTrue(sessionOf(alice).crowded);

        QuestObjective obj = forceBreakQuest(alice, 2);

        bob.teleport(new Location(world, 2500, 65, 2500)); // far away
        sessions().tickSolitude(System.currentTimeMillis());

        assertFalse(sessionOf(alice).crowded, "alice should be solitary again");
        listener.progressBreak(alice, "OAK_LOG", 1);
        assertEquals(1, obj.progress(), "solitary: progress must accrue");
    }

    @Test
    void distantRunnersAreNotCrowded() {
        freshRuns();
        alice.teleport(new Location(world, 100, 65, 100));
        bob.teleport(new Location(world, 2500, 65, 2500));

        sessions().tickSolitude(System.currentTimeMillis());

        assertFalse(sessionOf(alice).crowded);
        assertFalse(sessionOf(bob).crowded);
    }

    @Test
    void differentWorldsAreNotCrowded() {
        freshRuns();
        // Same XZ, but bob is in another world: no crowding.
        alice.teleport(new Location(world, 100, 65, 100));
        bob.teleport(new Location(world, 100, 65, 100));
        sessions().tickSolitude(System.currentTimeMillis());
        assertTrue(sessionOf(alice).crowded, "sanity: same spot in one world crowds");

        var nether = server.addSimpleWorld("world_nether");
        bob.teleport(new Location(nether, 100, 65, 100));
        sessions().tickSolitude(System.currentTimeMillis());
        assertFalse(sessionOf(alice).crowded, "different worlds must not crowd");
    }
}
