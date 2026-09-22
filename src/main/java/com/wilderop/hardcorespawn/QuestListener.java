package com.wilderop.hardcorespawn;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.FurnaceExtractEvent;
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
        if (killer != null) {
            progressKill(killer, event.getEntityType().name(), 1);
        }
    }

    public void progressKill(Player player, String entityType, int amount) {
        sessions.addProgress(player, QuestType.KILL, entityType, amount);
    }
}
