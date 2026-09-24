package com.wilderop.hardcorespawn;

import org.bukkit.Location;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Scatter starts: runs begin at a random wilderness spot in a ring around
 * 0,0 — never at the world spawn — so gear cannot be pre-staged there.
 */
class ScatterStartTest extends PluginTestBase {

    @Test
    void scatterLandsInRingOnSafeGround() {
        long seed = 12345L;
        // Replicate the draw order (angle, then distance) to know where the
        // seeded draw lands, and build safe ground there.
        Random probe = new Random(seed);
        double angle = probe.nextDouble() * Math.PI * 2.0;
        double dist = 2000.0 + probe.nextDouble() * (6000.0 - 2000.0);
        int x = (int) Math.round(Math.cos(angle) * dist);
        int z = (int) Math.round(Math.sin(angle) * dist);
        // Build safe ground above the natural terrain at the drawn spot.
        int platformY = world.getHighestBlockYAt(x, z) + 10;
        world.getBlockAt(x, platformY, z).setType(Material.STONE);
        world.getBlockAt(x, platformY + 1, z).setType(Material.AIR);
        world.getBlockAt(x, platformY + 2, z).setType(Material.AIR);

        Location loc = sessions().scatterStartLocation(new Random(seed));

        assertEquals("world", loc.getWorld().getName());
        assertEquals(x + 0.5, loc.getX(), 0.001);
        assertEquals(platformY + 1.0, loc.getY(), 0.001, "must stand on top of the safe ground");
        assertEquals(z + 0.5, loc.getZ(), 0.001);
        double ringDist = Math.hypot(loc.getX(), loc.getZ());
        assertTrue(ringDist >= 2000.0 && ringDist <= 6000.0,
                "scatter must land inside the ring, got dist=" + ringDist);
    }

    @Test
    void scatterIsRandomAcrossSeeds() {
        Location a = sessions().scatterStartLocation(new Random(1L));
        Location b = sessions().scatterStartLocation(new Random(2L));
        assertFalse(Math.abs(a.getX() - b.getX()) < 1.0 && Math.abs(a.getZ() - b.getZ()) < 1.0,
                "different seeds should scatter to different spots");
    }

    @Test
    void everyRunStartsScattered() {
        // Ten starts: all inside the ring, none at the world spawn.
        for (int i = 0; i < 10; i++) {
            var player = newPlayer();
            startRun(player);
            Location loc = player.getLocation();
            double ringDist = Math.hypot(loc.getX(), loc.getZ());
            assertTrue(ringDist >= 2000.0,
                    "run " + i + " must not start near spawn, got dist=" + ringDist);
        }
    }
}
