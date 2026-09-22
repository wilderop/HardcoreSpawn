package com.wilderop.hardcorespawn;

import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Non-hardcore commands are blocked during a run; outsiders are unaffected. */
class BlockedCommandTest extends PluginTestBase {

    private boolean cancelled(PlayerMock player, String command) {
        SessionListener listener = new SessionListener(sessions(), plugin.getSnapshotManager());
        PlayerCommandPreprocessEvent event = new PlayerCommandPreprocessEvent(player, command);
        listener.onCommand(event);
        return event.isCancelled();
    }

    @Test
    void foreignCommandsBlockedDuringRun() {
        PlayerMock player = newPlayer();
        startRun(player);

        assertTrue(cancelled(player, "/tpa friend"), "/tpa must be blocked");
        assertTrue(cancelled(player, "/home"), "/home must be blocked");
        assertTrue(cancelled(player, "/hardcoreblah"), "hardcore-prefixed impostors must be blocked");

        assertFalse(cancelled(player, "/hardcore status"), "own commands must pass");
        assertFalse(cancelled(player, "/hardcore quit"), "quit must pass");
        assertFalse(cancelled(player, "/hardcoreadmin reset x"), "admin commands must pass");
    }

    @Test
    void outsidersAreNotBlocked() {
        PlayerMock player = newPlayer();
        assertFalse(cancelled(player, "/home"), "players outside a run keep their commands");
    }
}
