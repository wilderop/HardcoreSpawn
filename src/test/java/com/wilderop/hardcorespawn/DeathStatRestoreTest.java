package com.wilderop.hardcorespawn;

import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.bukkit.Location;
import org.bukkit.Statistic;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** A run death must not count against the player: TIME_SINCE_DEATH and DEATHS roll back. */
class DeathStatRestoreTest extends PluginTestBase {

    private SessionListener listener;

    private PlayerMock freshRunWithStats(int timeSinceDeath, int deaths) {
        PlayerMock player = newPlayer();
        player.setStatistic(Statistic.TIME_SINCE_DEATH, timeSinceDeath);
        player.setStatistic(Statistic.DEATHS, deaths);
        listener = new SessionListener(sessions(), plugin.getSnapshotManager());
        startRun(player);
        return player;
    }

    private void kill(PlayerMock player) {
        PlayerDeathEvent death = new PlayerDeathEvent(player,
                DamageSource.builder(DamageType.GENERIC).build(), new ArrayList<>(), 0, "died");
        listener.onDeath(death);
        // Vanilla applies these on death; MockBukkit does not, so simulate them.
        player.setStatistic(Statistic.TIME_SINCE_DEATH, 0);
        player.setStatistic(Statistic.DEATHS, player.getStatistic(Statistic.DEATHS) + 1);
    }

    private void respawn(PlayerMock player) {
        listener.onRespawn(new PlayerRespawnEvent(player, new Location(world, 0, 70, 0), false));
    }

    @Test
    void deathRollsBackDeathStats() {
        PlayerMock player = freshRunWithStats(72000, 5);
        kill(player);

        assertEquals(0, player.getStatistic(Statistic.TIME_SINCE_DEATH), "death resets the counter");
        assertEquals(6, player.getStatistic(Statistic.DEATHS), "death bumps the counter");

        respawn(player);

        assertEquals(72000, player.getStatistic(Statistic.TIME_SINCE_DEATH),
                "time since death must be restored to the pre-run value");
        assertEquals(5, player.getStatistic(Statistic.DEATHS),
                "total deaths must not increase from a run death");
    }

    @Test
    void quitDoesNotRewindStats() {
        PlayerMock player = freshRunWithStats(72000, 5);
        // Time passes during the run without any death.
        player.setStatistic(Statistic.TIME_SINCE_DEATH, 73000);

        assertTrue(player.performCommand("hardcore quit"));

        assertEquals(73000, player.getStatistic(Statistic.TIME_SINCE_DEATH),
                "quitting involves no death, so the counter must keep ticking");
        assertEquals(5, player.getStatistic(Statistic.DEATHS),
                "quitting must not touch the death counter");
    }
}
