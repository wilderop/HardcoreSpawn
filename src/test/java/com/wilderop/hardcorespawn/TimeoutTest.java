package com.wilderop.hardcorespawn;

import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Timeout -> escalating damage -> averted death, driven by the TimerTask. */
class TimeoutTest extends PluginTestBase {

    @Test
    void timeoutEntersDamagePhaseThenKills() {
        PlayerMock player = newPlayer();
        startRun(player);
        Session s = sessions().getSession(player.getUniqueId());
        // Force an OBTAIN quest so checkObtain can't accidentally complete it.
        s.hand.clear();
        s.hand.add(new Quest(1, List.of("t"), List.of(
                new QuestObjective(QuestType.OBTAIN, "NETHERITE_BLOCK", 64, "impossible"))));

        double fullHealth = player.getHealth();
        long now = System.currentTimeMillis();
        TimerTask task = new TimerTask(sessions());

        // 1s past the deadline: damage phase starts, first damage tick lands.
        s.questDeadlineMs = now - 1_000;
        task.tick(now);
        assertTrue(s.timeoutDamagePhase, "timeout should enter the damage phase");
        assertTrue(player.getHealth() < fullHealth, "damage phase should hurt the player");

        // 31s past the deadline: the run ends without a real death.
        long later = now + 31_000;
        s.nextDamageMs = later + 60_000; // suppress further damage ticks; we test the kill
        task.tick(later);
        assertFalse(sessions().hasSession(player.getUniqueId()), "run should end after the kill window");
        assertFalse(player.isDead(), "the player must never actually die");
        assertEquals(player.getMaxHealth(), player.getHealth(), 0.001,
                "the player should be healed to full");
    }

    @Test
    void completingQuestDuringDamagePhaseExtendsClockAndStopsDamage() {
        PlayerMock player = newPlayer();
        startRun(player);
        Session s = sessions().getSession(player.getUniqueId());
        Quest q = new Quest(1, List.of("t"), List.of(
                new QuestObjective(QuestType.BREAK, "OAK_LOG", 1, "chop")));
        s.hand.clear();
        s.hand.add(q);

        long now = System.currentTimeMillis();
        TimerTask task = new TimerTask(sessions());

        // Enter the damage phase.
        s.questDeadlineMs = now - 1_000;
        task.tick(now);
        assertTrue(s.timeoutDamagePhase, "precondition: damage phase active");

        // Completing a quest adds the bonus and pulls the clock back to safety.
        sessions().completeQuest(player, s, q);
        Session after = sessions().getSession(player.getUniqueId());
        assertFalse(after.timeoutDamagePhase,
                "damage phase should stop once the clock is extended");
        assertTrue(after.questDeadlineMs > System.currentTimeMillis() + 240_000,
                "deadline should be ~5 minutes out after the bonus");

        // The clock keeps ticking with no further damage.
        double health = player.getHealth();
        task.tick(System.currentTimeMillis());
        assertEquals(health, player.getHealth(), 0.001,
                "no damage once the clock is back in the future");
    }

    @Test
    void warningsFireOnce() {
        PlayerMock player = newPlayer();
        startRun(player);
        Session s = sessions().getSession(player.getUniqueId());
        long now = System.currentTimeMillis();
        TimerTask task = new TimerTask(sessions());

        int before = hud.warnings;
        s.questDeadlineMs = now + 45_000;
        task.tick(now);
        assertTrue(s.warned60 && !s.warned30);
        assertTrue(hud.warnings > before);

        int mid = hud.warnings;
        s.questDeadlineMs = now + 20_000;
        task.tick(now);
        assertTrue(s.warned30);
        assertTrue(hud.warnings > mid);

        int after = hud.warnings;
        task.tick(now + 1_000);
        assertEquals(after, hud.warnings, "warnings must not repeat");
    }

    @Test
    void completingQuestBeforeDeadlineAvoidsDamage() {
        PlayerMock player = newPlayer();
        startRun(player);
        Session s = sessions().getSession(player.getUniqueId());
        s.hand.clear();
        s.hand.add(new Quest(1, List.of("t"), List.of(
                new QuestObjective(QuestType.OBTAIN, "DIAMOND", 1, "Hold 1 diamond"))));
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 1));

        TimerTask task = new TimerTask(sessions());
        task.tick(System.currentTimeMillis());

        Session after = sessions().getSession(player.getUniqueId());
        assertNotNull(after);
        assertEquals(1, after.level, "obtain check in the timer should complete the quest");
        assertFalse(after.timeoutDamagePhase);
    }
}
