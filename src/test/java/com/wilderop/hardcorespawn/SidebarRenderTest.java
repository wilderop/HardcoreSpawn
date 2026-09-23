package com.wilderop.hardcorespawn;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Pure unit tests for the sidebar line renderer (no Bukkit needed). */
class SidebarRenderTest {

    private static Quest quest(String description, String progressText) {
        String[] parts = progressText.split("/");
        int amount = Integer.parseInt(parts[1]);
        int progress = Integer.parseInt(parts[0]);
        QuestObjective objective = new QuestObjective(QuestType.BREAK, "oak_log", amount, description);
        objective.setProgress(progress);
        return new Quest(1, List.of("template"), List.of(objective));
    }

    @Test
    void eachQuestShowsDescriptionAndProgress() {
        List<Quest> hand = List.of(
                quest("Break 64 oak logs", "12/64"),
                quest("Travel 500 blocks on foot", "132/500"));

        List<String> lines = BukkitHudService.renderSidebarLines(hand, 4);

        assertTrue(lines.stream().anyMatch(l -> l.contains("Break 64 oak logs")),
                "quest description missing: " + lines);
        assertTrue(lines.stream().anyMatch(l -> l.contains("12/64")),
                "quest progress missing: " + lines);
        assertTrue(lines.stream().anyMatch(l -> l.contains("Travel 500 blocks on foot")),
                "second quest missing: " + lines);
        assertTrue(lines.stream().anyMatch(l -> l.contains("132/500")),
                "second progress missing: " + lines);
        assertTrue(lines.stream().anyMatch(l -> l.contains("Completed:") && l.contains("4")),
                "completed footer missing: " + lines);
    }

    @Test
    void longDescriptionsAreTruncated() {
        Quest q = quest("Break 32 oak logs + Break 16 birch logs + Smelt 8 iron ore", "0/32");
        List<String> lines = BukkitHudService.renderSidebarLines(List.of(q), 0);

        String header = lines.get(0);
        assertTrue(header.contains("…"), "long description should be truncated: " + header);
        assertTrue(header.length() <= BukkitHudService.SIDEBAR_DESC_MAX + 12,
                "header too long: " + header);
    }

    @Test
    void progressLinesStayUniqueWhenProgressIsIdentical() {
        List<Quest> hand = List.of(
                quest("Break 64 oak logs", "0/64"),
                quest("Break 64 birch logs", "0/64"));

        List<String> lines = BukkitHudService.renderSidebarLines(hand, 0);

        long distinct = lines.stream().distinct().count();
        assertEquals(lines.size(), distinct, "duplicate sidebar entries: " + lines);
    }

    @Test
    void emptyHandStillShowsFooter() {
        List<String> lines = BukkitHudService.renderSidebarLines(List.of(), 7);
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("7"));
    }
}
