package com.wilderop.hardcorespawn;

import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;

import java.util.concurrent.atomic.AtomicInteger;

/** Shared MockBukkit bootstrap for all plugin tests. */
public abstract class PluginTestBase {
    protected static ServerMock server;
    protected static HardcoreSpawn plugin;
    protected static NoOpHudService hud;

    private static final AtomicInteger PLAYER_SEQ = new AtomicInteger();

    protected World world;

    @BeforeAll
    static void bootstrap() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(HardcoreSpawn.class);
        hud = new NoOpHudService();
        plugin.getSessionManager().setHudService(hud);
    }

    @AfterAll
    static void shutdown() {
        MockBukkit.unmock();
        server = null;
        plugin = null;
    }

    @BeforeEach
    void ensureWorld() {
        world = server.getWorld("world");
        if (world == null) {
            world = server.addSimpleWorld("world");
        }
        world.setSpawnLocation(0, 64, 0);
        // The MockBukkit server (and SessionManager) is shared per test class:
        // drop stale sessions so tests are hermetic.
        plugin.getSessionManager().clearSessionsForTests();
    }

    protected PlayerMock newPlayer() {
        PlayerMock player = server.addPlayer("Tester" + PLAYER_SEQ.incrementAndGet());
        player.teleport(new Location(world, 100, 65, 100));
        return player;
    }

    /** Teleport well beyond the default 2000-block start radius (dist ~3535). */
    protected void moveFarAway(PlayerMock player) {
        player.teleport(new Location(world, 2500, 65, 2500));
    }

    protected SessionManager sessions() {
        return plugin.getSessionManager();
    }

    /** Full /hardcore -> /hardcore confirm flow, fast-forwarding the start freeze. */
    protected void startRun(PlayerMock player) {
        assert player.performCommand("hardcore") : "hardcore command failed";
        assert player.performCommand("hardcore confirm") : "hardcore confirm failed";
        // /hardcore confirm starts a stand-still countdown; jump past it.
        sessions().tickStartCountdowns(System.currentTimeMillis()
                + sessions().getConfig().getStartFreezeSeconds() * 1000L + 5000);
        assert sessions().hasSession(player.getUniqueId()) : "no session after confirm";
    }
}
