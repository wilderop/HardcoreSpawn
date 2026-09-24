package com.wilderop.hardcorespawn;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.FurnaceExtractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.entity.Projectile;
import org.bukkit.inventory.ItemStack;

/**
 * Feeds quest progress from game events into the SessionManager.
 * The public progress* methods are seams for unit tests.
 */
public final class QuestListener implements Listener {
    private final SessionManager sessions;

    public QuestListener(SessionManager sessions) {
        this.sessions = sessions;
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        progressBreak(event.getPlayer(), event.getBlock().getType().name(), 1);
    }

    public void progressBreak(Player player, String material, int amount) {
        sessions.addProgress(player, QuestType.BREAK, material, amount);
    }

    @EventHandler(ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            int amount = craftedAmount(event);
            if (amount > 0) {
                progressCraft(player, event.getRecipe().getResult().getType().name(), amount);
            }
        }
    }

    /**
     * How many result items this craft click actually produced. A shift-click
     * crafts every full set the matrix allows, so count those too.
     */
    private int craftedAmount(CraftItemEvent event) {
        int perCraft = event.getRecipe().getResult().getAmount();
        if (!event.isShiftClick()) {
            return perCraft;
        }
        int crafts = Integer.MAX_VALUE;
        for (ItemStack item : event.getInventory().getMatrix()) {
            if (item != null && !item.getType().isAir()) {
                crafts = Math.min(crafts, item.getAmount());
            }
        }
        return crafts == Integer.MAX_VALUE ? 0 : perCraft * crafts;
    }

    public void progressCraft(Player player, String material, int amount) {
        sessions.addProgress(player, QuestType.CRAFT, material, amount);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSmelt(FurnaceExtractEvent event) {
        progressSmelt(event.getPlayer(), event.getItemType().name(), event.getItemAmount());
    }

    public void progressSmelt(Player player, String material, int amount) {
        sessions.addProgress(player, QuestType.SMELT, material, amount);
    }

    @EventHandler(ignoreCancelled = true)
    public void onKill(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        UUID entityId = event.getEntity().getUniqueId();
        if (killer != null) {
            handleKill(killer, event.getEntityType().name(), entityId);
        } else {
            sessions.forgetEntityDamage(entityId);
        }
    }

    /** Kill-credit path; the event handler delegates here (test seam). */
    void handleKill(Player killer, String entityType, UUID entityId) {
        if (sessions.isSoloKill(entityId, killer.getUniqueId())) {
            progressKill(killer, entityType, 1);
        } else {
            // Say so loudly: a silent no-credit kill feels like a bug.
            killer.sendMessage(sessions.getConfig().format("solo-kill-denied",
                    Map.of("mob", prettyMobName(entityType))));
        }
        sessions.forgetEntityDamage(entityId);
    }

    private static String prettyMobName(String entityType) {
        String lower = entityType.toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    /**
     * Track player-dealt damage per entity so kill quests can require a
     * majority share (no kill carries). Projectiles count for their shooter.
     */
    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        Player player = null;
        if (event.getDamager() instanceof Player p) {
            player = p;
        } else if (event.getDamager() instanceof Projectile projectile
                && projectile.getShooter() instanceof Player p) {
            player = p;
        }
        if (player != null) {
            sessions.recordPlayerDamage(event.getEntity().getUniqueId(),
                    player.getUniqueId(), event.getFinalDamage(),
                    System.currentTimeMillis());
        }
    }

    public void progressKill(Player player, String entityType, int amount) {
        sessions.addProgress(player, QuestType.KILL, entityType, amount);
    }

    // ------------------------------------------------------------------
    // TRAVEL progress: credit whole blocks of horizontal distance walked.
    // Fractional distance accumulates per player so diagonal/short steps
    // still count; teleports and world changes grant nothing.
    // ------------------------------------------------------------------
    private final Map<UUID, Double> travelRemainder = new HashMap<>();

    @EventHandler(ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        if (!sessions.hasTravelObjective(id)) {
            travelRemainder.remove(id); // lazy cleanup; nothing to earn
            return;
        }
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getWorld() == null || !from.getWorld().equals(to.getWorld())) {
            travelRemainder.put(id, 0.0);
            return;
        }
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist <= 0.0 || dist > 16.0) {
            return; // standing still, or a glitch jump / teleport-like leap
        }
        double accumulated = travelRemainder.getOrDefault(id, 0.0) + dist;
        int whole = (int) accumulated;
        if (whole > 0) {
            travelRemainder.put(id, accumulated - whole);
            sessions.addProgress(player, QuestType.TRAVEL, "BLOCKS", whole);
        } else {
            travelRemainder.put(id, accumulated);
        }
    }
}
