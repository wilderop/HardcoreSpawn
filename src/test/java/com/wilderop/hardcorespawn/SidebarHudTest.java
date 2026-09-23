package com.wilderop.hardcorespawn;

import org.bukkit.Bukkit;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The real BukkitHudService drives a sidebar scoreboard on the player's client. */
class SidebarHudTest extends PluginTestBase {

    private static Quest quest(String description, String progress) {
        String[] parts = progress.split("/");
        QuestObjective objective = new QuestObjective(QuestType.BREAK, "oak_log",
                Integer.parseInt(parts[1]), description);
        objective.setProgress(Integer.parseInt(parts[0]));
        return new Quest(1, List.of("template"), List.of(objective));
    }

    private static Objective sidebarObjective(PlayerMock player) {
        Objective objective = player.getScoreboard().getObjective(DisplaySlot.SIDEBAR);
        assertNotNull(objective, "expected a sidebar objective");
        return objective;
    }

    /** Every rendered line must be a set score on the sidebar objective, top-down. */
    private static void assertSidebarShows(PlayerMock player, List<Quest> hand, int completed) {
        Objective objective = sidebarObjective(player);
        List<String> expected = BukkitHudService.renderSidebarLines(hand, completed);
        int score = expected.size();
        for (String line : expected) {
            assertTrue(objective.getScore(line).isScoreSet(),
                    "sidebar line not set: " + line);
            assertEquals(score--, objective.getScore(line).getScore(),
                    "wrong sidebar order for: " + line);
        }
    }

    @Test
    void sidebarListsEachQuestWithProgress() {
        PlayerMock player = server.addPlayer("SidebarTest");
        BukkitHudService hud = new BukkitHudService();
        List<Quest> hand = List.of(
                quest("Break 64 oak logs", "12/64"),
                quest("Travel 500 blocks on foot", "132/500"));

        hud.showRunHud(player, 2, 300_000L);
        hud.updateHud(player, 2, 299_000L, hand, 5);

        assertSidebarShows(player, hand, 5);

        assertTrue(sidebarObjective(player).getDisplayName().contains("Lv 2"),
                "title should show the level: " + sidebarObjective(player).getDisplayName());
    }

    @Test
    void sidebarUpdatesWhenProgressMoves() {
        PlayerMock player = server.addPlayer("SidebarProgress");
        BukkitHudService hud = new BukkitHudService();
        List<Quest> before = List.of(quest("Break 64 oak logs", "12/64"));
        List<Quest> after = List.of(quest("Break 64 oak logs", "48/64"));

        hud.showRunHud(player, 1, 300_000L);
        hud.updateHud(player, 1, 299_000L, before, 0);
        assertSidebarShows(player, before, 0);

        hud.updateHud(player, 1, 298_000L, after, 0);
        assertSidebarShows(player, after, 0);

        // The mock zeroes reset scores instead of removing them; a zeroed
        // stale line proves the old progress was cleared.
        String staleLine = BukkitHudService.renderSidebarLines(before, 0).get(1);
        assertEquals(0, sidebarObjective(player).getScore(staleLine).getScore(),
                "stale progress line was not cleared: " + staleLine);
    }

    @Test
    void hideHudRestoresMainScoreboard() {
        PlayerMock player = server.addPlayer("SidebarHide");
        BukkitHudService hud = new BukkitHudService();

        hud.showRunHud(player, 1, 300_000L);
        hud.updateHud(player, 1, 299_000L, List.of(quest("Break 64 oak logs", "1/64")), 0);
        assertNotNull(player.getScoreboard().getObjective(DisplaySlot.SIDEBAR));

        hud.hideHud(player);

        assertNull(player.getScoreboard().getObjective(DisplaySlot.SIDEBAR),
                "sidebar should be gone after the run ends");
        assertEquals(Bukkit.getScoreboardManager().getMainScoreboard(), player.getScoreboard());
    }

    @Test
    void disabledSidebarShowsNothing() {
        PlayerMock player = server.addPlayer("SidebarOff");
        BukkitHudService hud = new BukkitHudService();
        hud.configureSidebar(false, "§6§lHARDCORE §r§7Lv {level}");

        hud.showRunHud(player, 1, 300_000L);
        hud.updateHud(player, 1, 299_000L, List.of(quest("Break 64 oak logs", "1/64")), 0);

        assertNull(player.getScoreboard().getObjective(DisplaySlot.SIDEBAR));
    }
}
