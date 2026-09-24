package com.wilderop.hardcorespawn;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Anti-combat-escape start freeze: /hardcore confirm starts a stand-still
 * countdown (default 10s) instead of starting instantly. Movement and
 * teleports are blocked; any damage taken cancels the start with nothing
 * lost; a clean countdown starts the run exactly as before.
 */
class StartFreezeTest extends PluginTestBase {

    private StartFreezeListener freezeListener() {
        return new StartFreezeListener(sessions());
    }

    /** /hardcore -> /hardcore confirm, leaving the countdown active. */
    private void beginCountdown(PlayerMock player) {
        moveFarAway(player); // runs carrying items must start >= 2000 blocks from 0,0
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 4));
        assertTrue(player.performCommand("hardcore"), "hardcore command failed");
        assertTrue(player.performCommand("hardcore confirm"), "hardcore confirm failed");
        assertTrue(sessions().isStartFrozen(player.getUniqueId()), "countdown should be active");
        assertFalse(sessions().hasSession(player.getUniqueId()),
                "session must not exist while counting down");
    }

    private static String drainMessages(PlayerMock player) {
        StringBuilder sb = new StringBuilder();
        Component c;
        while ((c = player.nextComponentMessage()) != null) {
            sb.append(LegacyComponentSerializer.legacySection().serialize(c)).append('\n');
        }
        return sb.toString();
    }

    @Test
    void cleanCountdownStartsRun() {
        PlayerMock player = newPlayer();
        beginCountdown(player);
        UUID id = player.getUniqueId();

        // The snapshot is only taken when the countdown completes: the player
        // still holds everything while frozen.
        assertEquals(Material.DIAMOND, player.getInventory().getItem(0).getType(),
                "items must be untouched during the countdown");

        // Tick second by second through the real TimerTask (proves the wiring).
        TimerTask timer = new TimerTask(sessions());
        long t0 = System.currentTimeMillis();
        for (int i = 1; i <= 5; i++) {
            timer.tick(t0 + i * 1000L);
        }
        assertFalse(sessions().hasSession(id), "run must not start mid-countdown");
        assertTrue(sessions().isStartFrozen(id), "countdown must still be active");

        for (int i = 6; i <= 12; i++) {
            timer.tick(t0 + i * 1000L);
        }
        assertFalse(sessions().isStartFrozen(id), "countdown must be over");
        assertTrue(sessions().hasSession(id), "clean countdown must start the run");

        // The run starts exactly as before: snapshot taken, cleared, scattered
        // into the wilds, quest 1.
        assertNull(player.getInventory().getItem(0), "inventory must be cleared at run start");
        double scatterDist = Math.hypot(player.getLocation().getX(), player.getLocation().getZ());
        assertTrue(scatterDist >= 2000.0 && scatterDist <= 6000.0,
                "must be scattered into the wilds, got dist=" + scatterDist);
        Session session = sessions().getSession(id);
        assertEquals(Session.HAND_SIZE, session.hand.size(), "a full hand of quests must be dealt");
        for (Quest q : session.hand) {
            assertEquals(1, q.level());
        }
        assertEquals(1, sessions().getLeaderboard().stats(id).totalRuns);
    }

    @Test
    void damageCancelsCountdownAndKeepsEverything() {
        PlayerMock player = newPlayer();
        beginCountdown(player);
        UUID id = player.getUniqueId();

        EntityDamageEvent damage = new EntityDamageEvent(
                player, EntityDamageEvent.DamageCause.ENTITY_ATTACK, 2.0);
        freezeListener().onDamage(damage);

        assertFalse(damage.isCancelled(), "the damage itself must NOT be cancelled");
        assertFalse(sessions().isStartFrozen(id), "countdown must be cancelled");
        assertFalse(sessions().hasSession(id), "no session may start after a cancel");
        assertFalse(sessions().getSnapshots().hasSnapshot(id),
                "no snapshot may be taken when the start is cancelled");
        assertEquals(Material.DIAMOND, player.getInventory().getItem(0).getType(),
                "pre-run items must be untouched");
        assertEquals(4, player.getInventory().getItem(0).getAmount());

        String messages = drainMessages(player);
        assertTrue(messages.contains("took damage"),
                "player must be told the start was cancelled, got: " + messages);
    }

    @Test
    void movementBlockedDuringFreeze() {
        PlayerMock player = newPlayer();
        beginCountdown(player);
        StartFreezeListener listener = freezeListener();
        Location from = player.getLocation().clone();

        PlayerMoveEvent step = new PlayerMoveEvent(player, from, from.clone().add(1, 0, 0));
        listener.onMove(step);
        assertTrue(step.isCancelled(), "position change must be blocked");

        PlayerMoveEvent fall = new PlayerMoveEvent(player, from, from.clone().add(0, -2, 0));
        listener.onMove(fall);
        assertTrue(fall.isCancelled(), "vertical change must be blocked");

        Location lookOnly = from.clone();
        lookOnly.setYaw(from.getYaw() + 45);
        lookOnly.setPitch(from.getPitch() - 10);
        PlayerMoveEvent look = new PlayerMoveEvent(player, from, lookOnly);
        listener.onMove(look);
        assertFalse(look.isCancelled(), "head look must be allowed");
    }

    @Test
    void teleportBlockedDuringFreeze() {
        PlayerMock player = newPlayer();
        beginCountdown(player);

        PlayerTeleportEvent pearl = new PlayerTeleportEvent(player, player.getLocation(),
                new Location(world, 0, 70, 0), PlayerTeleportEvent.TeleportCause.ENDER_PEARL);
        freezeListener().onTeleport(pearl);
        assertTrue(pearl.isCancelled(), "ender pearl escape must be blocked");
    }

    @Test
    void logoutCancelsCountdown() {
        PlayerMock player = newPlayer();
        beginCountdown(player);
        UUID id = player.getUniqueId();

        freezeListener().onQuit(new PlayerQuitEvent(player, "quit"));
        assertFalse(sessions().isStartFrozen(id), "logout must cancel the countdown");
        assertFalse(sessions().hasSession(id));

        // Rejoin: no session, no leftover countdown — the player starts over.
        assertTrue(player.disconnect(), "mock disconnect failed");
        server.addPlayer(player);
        new SessionListener(sessions(), plugin.getSnapshotManager())
                .onJoin(new PlayerJoinEvent(player, "join"));
        assertFalse(sessions().hasSession(id));
        assertFalse(sessions().isStartFrozen(id));

        // The old /hardcore request was consumed by the first confirm.
        player.performCommand("hardcore confirm");
        assertFalse(sessions().isStartFrozen(id), "confirm without a fresh request must not count down");
        assertFalse(sessions().hasSession(id));
    }

    @Test
    void doubleConfirmIsGuarded() {
        PlayerMock player = newPlayer();
        beginCountdown(player);
        UUID id = player.getUniqueId();

        // Re-confirming mid-countdown must not stack another countdown.
        assertTrue(player.performCommand("hardcore confirm"));
        assertTrue(sessions().isStartFrozen(id), "original countdown must survive");

        String messages = drainMessages(player);
        assertTrue(messages.contains("already counting down"),
                "player must be told a countdown is active, got: " + messages);

        // Fast-forward: exactly one run starts.
        sessions().tickStartCountdowns(System.currentTimeMillis() + 15_000);
        assertTrue(sessions().hasSession(id), "the single countdown must start the run");
    }

    @Test
    void zeroFreezeStartsInstantly() {
        HardcoreConfig originalConfig = sessions().getConfig();
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new InputStreamReader(
                HardcoreConfig.class.getResourceAsStream("/config.yml"), StandardCharsets.UTF_8));
        yaml.set("start.freeze-seconds", 0);
        HardcoreConfig noFreeze = new HardcoreConfig(yaml);
        sessions().setConfig(noFreeze, QuestGenerator.fromConfig(noFreeze));
        try {
            PlayerMock player = newPlayer();
            moveFarAway(player);
            player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 2));
            assertTrue(player.performCommand("hardcore"));
            assertTrue(player.performCommand("hardcore confirm"));
            assertTrue(sessions().hasSession(player.getUniqueId()),
                    "freeze-seconds: 0 must start the run instantly");
            assertFalse(sessions().isStartFrozen(player.getUniqueId()));
        } finally {
            HardcoreConfig defaults = HardcoreConfig.fromDefaults();
            sessions().setConfig(defaults, QuestGenerator.fromConfig(defaults));
        }
    }
}
