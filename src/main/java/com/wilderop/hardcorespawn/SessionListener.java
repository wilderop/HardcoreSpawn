package com.wilderop.hardcorespawn;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Map;

/**
 * Run lifecycle events: death (forfeit run gains at the death site),
 * respawn (restore the pre-run snapshot), joins/quits, blocked commands.
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

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        Session session = sessions.getSession(player.getUniqueId());
        if (session == null) {
            return;
        }
        // Forfeit everything gained during the run: drop it where the player died.
        // Storage + armor + offhand exactly once (getContents() already
        // includes armor and offhand, so it must not be combined with them).
        event.getDrops().clear();
        event.setDroppedExp(0);
        event.setNewExp(0);
        event.setNewLevel(0);
        event.setNewTotalExp(0);
        event.setKeepInventory(false);
        Location loc = player.getLocation();
        World world = loc.getWorld();
        dropAll(world, loc, player.getInventory().getStorageContents());
        dropAll(world, loc, player.getInventory().getArmorContents());
        ItemStack offhand = player.getInventory().getItemInOffHand();
        if (offhand != null && !offhand.getType().isAir()) {
            world.dropItemNaturally(loc, offhand);
        }
        // The cursor item is not reliably part of the event drops, so forfeit
        // it explicitly. This is dupe-safe: the vanilla drops were cleared
        // above, so exactly one copy is spawned however the event was built.
        ItemStack cursor = player.getItemOnCursor();
        if (cursor != null && !cursor.getType().isAir()) {
            world.dropItemNaturally(loc, cursor.clone());
            player.setItemOnCursor(null);
        }
        sessions.endRun(player.getUniqueId(), ExitCause.IN_WORLD_DEATH);
    }    private void dropAll(World world, Location loc, ItemStack[] items) {
        for (ItemStack item : items) {
            if (item != null && !item.getType().isAir()) {
                world.dropItemNaturally(loc, item.clone());
            }
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        SessionManager.PendingRestore restore = sessions.consumeRespawnRestore(player.getUniqueId());
        if (restore == null) {
            return;
        }
        event.setRespawnLocation(restore.returnLocation());
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
