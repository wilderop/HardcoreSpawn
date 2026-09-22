package com.wilderop.hardcorespawn;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.Map;

/**
 * Single repeating task (every second) that ticks all sessions:
 * OBTAIN scanning, HUD countdown, warnings, and the timeout damage phase.
 */
public final class TimerTask extends BukkitRunnable {
    private final SessionManager sessions;

    public TimerTask(SessionManager sessions) {
        this.sessions = sessions;
    }

    @Override
    public void run() {
        tick(System.currentTimeMillis());
    }

    /** Package-private for tests. */
    void tick(long now) {
        HardcoreConfig config = sessions.getConfig();
        for (Session s : sessions.sessionsSnapshot()) {
            if (s.questDeadlineMs == 0) {
                continue; // paused across a restart: frozen until the player rejoins
            }
            Player player = Bukkit.getPlayer(s.playerId);
            // The session's own offline marker is authoritative: a quit event
            // was definitely seen. The Bukkit check is a safety net in case
            // the quit event was missed.
            boolean offline = s.offlineSinceMs != 0 || player == null || !player.isOnline();
            if (offline) {
                if (s.offlineSinceMs == 0) {
                    s.offlineSinceMs = now;
                }
                sessions.checkOfflineTimeout(s, now);
                continue;
            }
            sessions.checkObtain(player);

            Session current = sessions.getSession(s.playerId);
            if (current == null) {
                continue; // run ended during the obtain check
            }
            s = current;

            long remaining = s.questDeadlineMs - now;
            sessions.getHud().updateHud(player, s.quest.level(), remaining);

            if (!s.warned60 && remaining <= 60_000 && remaining > 0) {
                s.warned60 = true;
                sessions.getHud().warnActionBar(player, config.message("timeout-warning"));
            }
            if (!s.warned30 && remaining <= 30_000 && remaining > 0) {
                s.warned30 = true;
                sessions.getHud().warnActionBar(player, config.message("timeout-warning"));
            }

            if (remaining <= 0) {
                if (!s.timeoutDamagePhase) {
                    s.timeoutDamagePhase = true;
                    s.nextDamageMs = now;
                    sessions.getHud().warnActionBar(player, config.message("timeout-damage"));
                    player.sendMessage(config.format("timeout-damage", Map.of()));
                }
                if (now >= s.nextDamageMs && !player.isDead()) {
                    player.damage(config.getTimeoutDamage());
                    s.nextDamageMs += config.getTimeoutDamageIntervalSeconds() * 1000L;
                }
                if (!player.isDead()
                        && now - s.questDeadlineMs >= config.getTimeoutKillAfterSeconds() * 1000L) {
                    player.setHealth(0); // the death listener runs the exit path
                }
            }
        }
    }
}
