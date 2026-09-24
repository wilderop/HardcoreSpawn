package com.wilderop.hardcorespawn;

import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.bukkit.Location;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Solo kills: a kill quest only counts when the runner dealt the majority
 * of the mob's player-dealt damage themselves.
 */
class SoloKillTest extends PluginTestBase {

    private QuestListener listener;
    private PlayerMock alice;
    private PlayerMock bob;

    private void freshRuns() {
        alice = newPlayer();
        bob = newPlayer();
        listener = new QuestListener(sessions());
        startRun(alice);
        startRun(bob);
        // Keep the runners far apart so solitude never freezes progress.
        alice.teleport(new Location(world, 100, 65, 100));
        bob.teleport(new Location(world, 2500, 65, 2500));
        sessions().tickSolitude(System.currentTimeMillis());
    }

    private QuestObjective forceKillQuest(PlayerMock p) {
        Session s = sessions().getSession(p.getUniqueId());
        s.hand.clear();
        QuestObjective obj = new QuestObjective(QuestType.KILL, "ZOMBIE", 1, "Slay 1 zombie");
        s.hand.add(new Quest(s.level + 1, List.of("test-template"), List.of(obj)));
        return obj;
    }

    private void damage(UUID mob, PlayerMock p, double amount) {
        sessions().recordPlayerDamage(mob, p.getUniqueId(), amount, System.currentTimeMillis());
    }

    /** Drains queued chat messages, tolerating both null and throw-on-empty. */
    private static void drainMessages(PlayerMock p) {
        for (int i = 0; i < 100; i++) {
            String m;
            try {
                m = p.nextMessage();
            } catch (RuntimeException e) {
                break;
            }
            if (m == null) {
                break;
            }
        }
    }

    @Test
    void untrackedKillCountsAsSolo() {
        freshRuns();
        assertTrue(sessions().isSoloKill(UUID.randomUUID(), alice.getUniqueId()),
                "no tracked damage: fail open for genuine solo kills");
    }

    @Test
    void soleDamagerCountsAsSolo() {
        freshRuns();
        UUID mob = UUID.randomUUID();
        damage(mob, alice, 20.0);
        assertTrue(sessions().isSoloKill(mob, alice.getUniqueId()));
    }

    @Test
    void majorityDamagerCountsHelperDoesNot() {
        freshRuns();
        UUID mob = UUID.randomUUID();
        damage(mob, alice, 12.0);
        damage(mob, bob, 8.0);
        assertTrue(sessions().isSoloKill(mob, alice.getUniqueId()),
                "12 of 20 is a majority");
        assertFalse(sessions().isSoloKill(mob, bob.getUniqueId()),
                "8 of 20 is not a majority");
    }

    @Test
    void exactHalfIsNotAMajority() {
        freshRuns();
        UUID mob = UUID.randomUUID();
        damage(mob, alice, 10.0);
        damage(mob, bob, 10.0);
        assertFalse(sessions().isSoloKill(mob, alice.getUniqueId()),
                "exactly half must not count");
    }

    @Test
    void carriedKillGrantsNoProgress() {
        freshRuns();
        QuestObjective obj = forceKillQuest(alice);
        UUID mob = UUID.randomUUID();
        damage(mob, bob, 18.0);   // helper does the work
        damage(mob, alice, 2.0);  // alice taps last

        listener.handleKill(alice, "ZOMBIE", mob);

        assertEquals(0, obj.progress(), "a carried kill must not count");
    }

    @Test
    void soloKillGrantsProgress() {
        freshRuns();
        QuestObjective obj = forceKillQuest(alice);
        UUID mob = UUID.randomUUID();
        damage(mob, alice, 20.0);

        listener.handleKill(alice, "ZOMBIE", mob);

        assertEquals(1, obj.progress(), "a genuine solo kill must count");
    }

    @Test
    void deniedKillTellsThePlayerWhy() {
        freshRuns();
        forceKillQuest(alice);
        drainMessages(alice);
        UUID mob = UUID.randomUUID();
        damage(mob, bob, 18.0);  // helper does the work
        damage(mob, alice, 2.0); // alice taps last

        listener.handleKill(alice, "ZOMBIE", mob);

        String denial = alice.nextMessage();
        assertNotNull(denial, "a denied kill must tell the player why");
        assertTrue(denial.contains("didn't count"),
                "denial must say the kill didn't count: " + denial);
        assertTrue(denial.contains("Zombie"),
                "denial must name the mob: " + denial);
    }

    @Test
    void damageRecordForgottenAfterKill() {
        freshRuns();
        UUID mob = UUID.randomUUID();
        damage(mob, bob, 18.0);
        damage(mob, alice, 2.0);
        assertFalse(sessions().isSoloKill(mob, alice.getUniqueId()));

        sessions().forgetEntityDamage(mob);

        assertTrue(sessions().isSoloKill(mob, alice.getUniqueId()),
                "forgetting must clear the record");
    }
}
