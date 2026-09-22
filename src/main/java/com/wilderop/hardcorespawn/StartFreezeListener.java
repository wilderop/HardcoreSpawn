package com.wilderop.hardcorespawn;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/**
 * Enforces the stand-still freeze while a run start is counting down
 * (anti-combat-escape: starting a run teleports you to spawn).
 *
 * <p>Position changes and teleports are blocked; any damage the player
 * actually takes cancels the start (the damage itself is NOT cancelled).
 * Logging out drops the pending start and the normal disconnect path takes
 * over. The snapshot is only taken when the countdown completes, so a
 * cancelled start costs the player nothing.
 */
public final class StartFreezeListener implements Listener {
    private final SessionManager sessions;

    public StartFreezeListener(SessionManager sessions) {
        this.sessions = sessions;
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (!sessions.isStartFrozen(player.getUniqueId())) {
            return;
        }
        if (event.getTo() == null) {
            return;
        }
        // Block X/Y/Z changes; head look (yaw/pitch) is allowed.
        if (event.getFrom().getX() != event.getTo().getX()
                || event.getFrom().getY() != event.getTo().getY()
                || event.getFrom().getZ() != event.getTo().getZ()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (sessions.isStartFrozen(event.getPlayer().getUniqueId())) {
            // No ender pearls, chorus fruit, or any other escape.
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (!sessions.isStartFrozen(player.getUniqueId())) {
            return;
        }
        // The hit lands — only the pending start is cancelled. Damage the
        // player actually takes (not cancelled by another plugin) is what
        // counts, per ignoreCancelled above.
        sessions.cancelStartCountdown(player.getUniqueId(), "confirm-freeze-cancelled");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        // Drop the pending start silently; the normal disconnect path
        // (SessionListener) takes over from here.
        sessions.cancelStartCountdown(event.getPlayer().getUniqueId(), null);
    }
}
