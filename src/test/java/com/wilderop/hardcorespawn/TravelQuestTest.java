package com.wilderop.hardcorespawn;

import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.player.PlayerMoveEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The guaranteed easy starter quest: every run opens with "Travel 500
 * blocks on foot" alongside two random quests, because the land near spawn
 * on an anarchy server is stripped bare.
 */
class TravelQuestTest extends PluginTestBase {

    private static final Logger LOG = Logger.getLogger("TravelQuestTest");

    private QuestListener questListener() {
        return new QuestListener(sessions());
    }

    private Quest travelQuest(PlayerMock player) {
        Session s = sessions().getSession(player.getUniqueId());
        assertNotNull(s, "no session");
        return s.hand.stream()
                .filter(q -> q.objectives().stream().anyMatch(o -> o.type() == QuestType.TRAVEL))
                .findFirst()
                .orElse(null);
    }

    /** Fire a PlayerMoveEvent through the real listener. */
    private void move(PlayerMock player, Location from, Location to) {
        questListener().onPlayerMove(new PlayerMoveEvent(player, from, to));
    }

    @Test
    void openingHandAlwaysContainsExactlyOneTravelQuest() {
        PlayerMock player = newPlayer();
        moveFarAway(player);
        startRun(player);

        Session s = sessions().getSession(player.getUniqueId());
        assertNotNull(s);
        assertEquals(3, s.hand.size(), "opening hand must have three quests");

        long travelCount = s.hand.stream()
                .filter(q -> q.objectives().stream().anyMatch(o -> o.type() == QuestType.TRAVEL))
                .count();
        assertEquals(1, travelCount, "opening hand must contain exactly one travel quest");

        Quest travel = travelQuest(player);
        assertNotNull(travel);
        assertEquals("Travel 500 blocks on foot", travel.getDescription());
        assertEquals(500, travel.objectives().get(0).amount());
    }

    @Test
    void walking500BlocksCompletesTheTravelQuest() {
        PlayerMock player = newPlayer();
        moveFarAway(player);
        startRun(player);
        UUID id = player.getUniqueId();

        QuestListener listener = questListener();
        Location loc = player.getLocation().clone();
        for (int i = 0; i < 500; i++) {
            Location from = loc.clone();
            loc.add(1, 0, 0);
            listener.onPlayerMove(new PlayerMoveEvent(player, from, loc.clone()));
        }

        // Completing it deals a replacement: hand stays at three, travel quest gone.
        Session s = sessions().getSession(id);
        assertNotNull(s, "run must still be active");
        assertEquals(3, s.hand.size());
        assertNull(travelQuest(player), "travel quest must be completed and replaced");
    }

    @Test
    void fractionalStepsAccumulate() {
        PlayerMock player = newPlayer();
        moveFarAway(player);
        startRun(player);

        QuestListener listener = questListener();
        Location loc = player.getLocation().clone();
        // 2000 quarter-block steps = 500 blocks total.
        for (int i = 0; i < 2000; i++) {
            Location from = loc.clone();
            loc.add(0.25, 0, 0);
            listener.onPlayerMove(new PlayerMoveEvent(player, from, loc.clone()));
        }
        assertNull(travelQuest(player), "500 blocks of quarter steps must complete the quest");
    }

    @Test
    void teleportsAndWorldChangesGrantNoProgress() {
        PlayerMock player = newPlayer();
        moveFarAway(player);
        startRun(player);

        Quest travel = travelQuest(player);
        assertNotNull(travel);
        QuestObjective objective = travel.objectives().get(0);

        // Teleports fire PlayerTeleportEvent, not PlayerMoveEvent: simulate by
        // moving the player directly and asserting no progress leaked in.
        player.teleport(new Location(world, 2500, 65, 3000));
        assertEquals(0, objective.progress(), "teleporting 500 blocks must not count");

        // A cross-world move event resets the accumulator without credit.
        World other = server.addSimpleWorld("other");
        move(player,
                new Location(world, 2500, 65, 3000),
                new Location(other, 2500, 65, 3000));
        assertEquals(0, objective.progress(), "world change must not grant progress");
    }

    @Test
    void verticalMovementDoesNotCount() {
        PlayerMock player = newPlayer();
        moveFarAway(player);
        startRun(player);

        Quest travel = travelQuest(player);
        assertNotNull(travel);
        QuestObjective objective = travel.objectives().get(0);

        Location loc = player.getLocation().clone();
        for (int i = 0; i < 600; i++) {
            Location from = loc.clone();
            loc.add(0, 1, 0);
            move(player, from, loc.clone());
        }
        assertEquals(0, objective.progress(), "climbing 600 blocks up must not count as travel");
    }

    @Test
    void noTrackingWithoutATravelObjective() {
        PlayerMock player = newPlayer();
        moveFarAway(player);
        // No run started: no session, no travel objective. Must not throw and
        // must not create phantom progress anywhere.
        Location from = player.getLocation().clone();
        Location to = from.clone().add(10, 0, 0);
        assertDoesNotThrow(() -> move(player, from, to));
        assertFalse(sessions().hasTravelObjective(player.getUniqueId()));
    }

    // ------------------------------------------------------------------
    // Config migration: v1 -> v2 refreshes the quest bands so the bundled
    // travel template reaches existing servers, while user settings carry
    // over and the old file is backed up.
    // ------------------------------------------------------------------

    private Supplier<InputStream> bundled() {
        return () -> TravelQuestTest.class.getResourceAsStream("/config.yml");
    }

    /** A v1 config: old band set with no TRAVEL template, one custom setting. */
    private void writeV1Config(File dir) throws Exception {
        String v1 = "config-version: 1\n"
                + "quest-time-seconds: 600\n"
                + "bands:\n"
                + "  - min-level: 1\n"
                + "    max-level: 3\n"
                + "    quests:\n"
                + "      - { id: chop_oak, type: BREAK, target: OAK_LOG, base-amount: 8,"
                + " description: \"Break {amount} {target}\" }\n"
                + "messages:\n"
                + "  run-started: \"old format\"\n";
        Files.write(new File(dir, "config.yml").toPath(), v1.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void v1ToV2MigrationRefreshesBandsAndPreservesSettings(@TempDir File dir) throws Exception {
        writeV1Config(dir);

        assertTrue(ConfigMigrator.migrateIfNeeded(dir, LOG, bundled()), "migration should run");

        YamlConfiguration migrated = YamlConfiguration.loadConfiguration(new File(dir, "config.yml"));
        assertEquals(2, migrated.getInt("config-version"));
        assertEquals(600, migrated.getInt("quest-time-seconds"),
                "user settings must carry over");

        List<?> bands = migrated.getList("bands");
        assertNotNull(bands);
        boolean hasTravel = bands.stream()
                .flatMap(b -> ((List<?>) ((java.util.Map<?, ?>) b).get("quests")).stream())
                .map(q -> (java.util.Map<?, ?>) q)
                .anyMatch(q -> "TRAVEL".equals(String.valueOf(q.get("type"))));
        assertTrue(hasTravel, "migrated bands must include the bundled TRAVEL template");

        File backup = new File(dir, "config.yml.bak");
        assertTrue(backup.exists(), "old config must be backed up");
        YamlConfiguration old = YamlConfiguration.loadConfiguration(backup);
        assertEquals(1, old.getInt("config-version"), "backup must hold the original v1 config");

        assertFalse(ConfigMigrator.migrateIfNeeded(dir, LOG, bundled()),
                "a second run must be a no-op");
    }
}
