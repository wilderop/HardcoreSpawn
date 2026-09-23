package com.wilderop.hardcorespawn;

import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Lethal damage never actually kills a hardcore runner: the hit is cancelled,
 * the player is healed to full, run gains drop where they "died", and the run
 * ends immediately. Skipping the death/respawn round-trip keeps pre-run XP
 * levels intact (Bukkit applies the death event's zeroed new-XP values after
 * PlayerRespawnEvent, which used to clobber the restored levels while
 * /hardcore quit kept them).
 */
class AvertedDeathTest extends PluginTestBase {

    private SessionListener listener;
    private PlayerMock player;

    private void startRunWithXp() {
        player = newPlayer();
        player.setTotalExperience(200);
        player.setLevel(7);
        listener = new SessionListener(sessions(), plugin.getSnapshotManager());
        startRun(player);
        // Simulate XP and loot earned during the run.
        player.setLevel(30);
        player.getInventory().setItem(3, new ItemStack(Material.EMERALD, 12));
        player.setHealth(10.0);
    }

    private EntityDamageEvent lethalHit() {
        return new EntityDamageEvent(player, EntityDamageEvent.DamageCause.ENTITY_ATTACK,
                DamageSource.builder(DamageType.GENERIC).build(), 1000.0);
    }

    @Test
    void lethalDamageIsCancelledAndRunEnds() {
        startRunWithXp();
        Location deathSpot = player.getLocation().clone();
        Location home = sessions().getSession(player.getUniqueId()).returnLocation;

        EntityDamageEvent hit = lethalHit();
        listener.onLethalDamage(hit);

        assertTrue(hit.isCancelled(), "the lethal hit must be cancelled");
        assertFalse(sessions().hasSession(player.getUniqueId()), "the run must end");
        assertFalse(player.isDead(), "the player must never actually die");
    }

    @Test
    void avertedDeathHealsAndKeepsPreRunXp() {
        startRunWithXp();
        Location home = sessions().getSession(player.getUniqueId()).returnLocation;
        double maxHealth = player.getMaxHealth();

        listener.onLethalDamage(lethalHit());

        assertEquals(maxHealth, player.getHealth(), 0.001, "player must be healed to full");
        assertEquals(7, player.getLevel(), "pre-run XP level must survive");
        assertEquals(200, player.getTotalExperience(), "pre-run XP total must survive");
        assertEquals(home.getX(), player.getLocation().getX(), 0.001,
                "player must be back at the pre-run location");
        assertEquals(home.getWorld().getName(), player.getLocation().getWorld().getName());
    }

    @Test
    void avertedDeathDropsRunGainsAtTheLocation() {
        startRunWithXp();

        listener.onLethalDamage(lethalHit());

        assertNull(player.getInventory().getItem(3), "run gains must leave the inventory");
        boolean foundEmeralds = world.getEntities().stream()
                .filter(e -> e.getType() == org.bukkit.entity.EntityType.ITEM)
                .map(e -> (org.bukkit.entity.Item) e)
                .anyMatch(i -> i.getItemStack().getType() == Material.EMERALD);
        assertTrue(foundEmeralds, "run gains should be dropped as items where the player 'died'");
    }

    @Test
    void nonLethalDamageIsUntouched() {
        startRunWithXp();

        EntityDamageEvent scratch = new EntityDamageEvent(player,
                EntityDamageEvent.DamageCause.ENTITY_ATTACK,
                DamageSource.builder(DamageType.GENERIC).build(), 2.0);
        listener.onLethalDamage(scratch);

        assertFalse(scratch.isCancelled(), "non-lethal damage must not be cancelled");
        assertTrue(sessions().hasSession(player.getUniqueId()), "the run must continue");
    }

    @Test
    void damageWithoutSessionIsIgnored() {
        player = newPlayer();
        listener = new SessionListener(sessions(), plugin.getSnapshotManager());

        EntityDamageEvent hit = new EntityDamageEvent(player,
                EntityDamageEvent.DamageCause.ENTITY_ATTACK,
                DamageSource.builder(DamageType.GENERIC).build(), 1000.0);
        listener.onLethalDamage(hit);

        assertFalse(hit.isCancelled(), "players outside a run must take damage normally");
    }
}
