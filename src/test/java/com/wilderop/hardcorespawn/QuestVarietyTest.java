package com.wilderop.hardcorespawn;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Early-game quests must be varied, easy, and actually random. */
class QuestVarietyTest {

    private QuestGenerator generator() {
        return QuestGenerator.fromConfig(HardcoreConfig.fromDefaults());
    }

    private QuestGenerator.Band earlyBand() {
        return generator().bands().stream()
                .filter(b -> b.covers(1))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void earlyBandHasWideVariety() {
        assertTrue(earlyBand().templates.size() >= 20,
                "expected 20+ early templates, got " + earlyBand().templates.size());
    }

    @Test
    void woodTypesVaryInsteadOfAlwaysOak() {
        Set<String> logTargets = new HashSet<>();
        for (QuestTemplate t : earlyBand().templates) {
            if (t.type() == QuestType.BREAK && t.target().endsWith("_LOG")) {
                logTargets.add(t.target());
            }
        }
        assertTrue(logTargets.size() >= 5,
                "expected 5+ log species, got " + logTargets);
    }

    @Test
    void earlyQuestsAreEasy() {
        for (QuestTemplate t : earlyBand().templates) {
            if (t.type() == QuestType.TRAVEL) {
                continue; // walking is trivially easy regardless of distance
            }
            int amount = t.amountForLevel(1, 1.3);
            assertTrue(amount <= 48,
                    "early quest too grindy: " + t.id() + " wants " + amount);
        }
    }

    @Test
    void generationSpreadsAcrossTemplates() {
        QuestGenerator gen = generator();
        Random rng = new Random(1234);
        Set<String> seen = new HashSet<>();
        List<String> last = List.of();
        for (int i = 0; i < 200; i++) {
            Quest q = gen.generate(1, last, rng);
            seen.addAll(q.templateIds());
            last = q.templateIds();
        }
        assertTrue(seen.size() >= 15,
                "level-1 generation stuck on a few templates: " + seen);
    }

    @Test
    void handSizeIsFour() {
        assertEquals(4, Session.HAND_SIZE, "players choose from 4 quests");
    }
}
