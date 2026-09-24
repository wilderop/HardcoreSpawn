package com.wilderop.hardcorespawn;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.util.Map;

/**
 * Run lifecycle events: lethal damage is intercepted (heal + end run instead
 * of a real death, so XP survives), real deaths forfeit run gains at the
 * death site, respawn restores the pre-run snapshot, plus joins/quits and
 * blocked commands.
 */
public final class SessionListener implements Listener {
    private final SessionManager sessions;
    private final SnapshotManager snapshots;

    public SessionListener(SessionManager sessions, SnapshotManager snapshots) {
        this.sessions = sessions;
        this.snapshots = snapshots;
    }

    private HardcoreConfig config() {
        return sessions.getConfig();
    }

    /**
     * Intercept lethal damage: instead of a real death + respawn round-trip,
     * cancel the damage, heal to full, and end the run immediately. The
     * round-trip used to wipe the player's XP levels (Bukkit applies the
     * death event's zeroed new-XP values after PlayerRespawnEvent, clobbering
     * the restored snapshot), while /hardcore quit kept them.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onLethalDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        if (!sessions.hasSession(player.getUniqueId())) {
            return;
        }
        double after = player.getHealth() + player.getAbsorptionAmount() - event.getFinalDamage();
        if (after > 0) {
            return;
        }
        event.setCancelled(true);
        sessions.avertDeath(player);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        Session session = sessions.getSession(player.getUniqueId());
        if (session == null) {
            return;
        }
        // Forfeit everything gained during the run: drop it where the player died.
        event.getDrops().clear();
        event.setDroppedExp(0);
        event.setNewExp(0);
        event.setNewLevel(0);
        event.setNewTotalExp(0);
        event.setKeepInventory(false);
        sessions.dropRunGains(player);
        sessions.endRun(player.getUniqueId(), ExitCause.IN_WORLD_DEATH);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        SessionManager.PendingRestore restore = sessions.consumeRespawnRestore(player.getUniqueId());
        if (restore == null) {
            return;
        }
        Location respawnAt = restore.returnLocation();
        if (respawnAt != null) {
            event.setRespawnLocation(respawnAt);
        }
        snapshots.clearPlayer(player);
        if (restore.snapshot() != null) {
            snapshots.restore(player, restore.snapshot());
            // Respawn restores are death-only: the run death must not count.
            snapshots.restoreDeathStats(player, restore.snapshot());
        }
        player.sendMessage(config().format(restore.messageKey(), Map.of("level", String.valueOf(restore.level()))));
        sessions.saveRestores();
    }

    /**
     * The ender chest is wiped at run start and restored at run end, so it
     * must stay inaccessible mid-run: only physical world chests may bank
     * run gains.
     */
    @EventHandler(ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        if (!sessions.hasSession(player.getUniqueId())) {
            return;
        }
        if (event.getInventory().getType() == InventoryType.ENDER_CHEST) {
            event.setCancelled(true);
            player.sendMessage(config().format("enderchest-blocked", Map.of()));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        sessions.handleQuit(event);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        sessions.handleJoin(event);
    }

    @EventHandler(ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (sessions.isCommandBlocked(event.getPlayer().getUniqueId(), event.getMessage())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(config().format("blocked-command", Map.of()));
        }
    }
}
