package com.wilderop.hardcorespawn;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/** Pure unit tests for quest generation: no-repeat rule and difficulty scaling. */
class QuestGeneratorTest {

    private QuestGenerator generator() {
        return QuestGenerator.fromConfig(HardcoreConfig.fromDefaults());
    }

    @Test
    void neverRepeatsTemplateTwiceInARow() {
        QuestGenerator gen = generator();
        Random rng = new Random(42);
        List<String> last = List.of();
        for (int i = 0; i < 500; i++) {
            int level = 1 + rng.nextInt(30);
            Quest q = gen.generate(level, last, rng);
            if (!last.isEmpty()) {
                for (String id : q.templateIds()) {
                    assertFalse(last.contains(id),
                            "level " + level + " repeated template " + id + " back-to-back");
                }
            }
            last = q.templateIds();
        }
    }

    @Test
    void amountsScaleWithLevel() {
        QuestGenerator gen = generator();
        Random rng = new Random(7);
        double low = 0, high = 0;
        int n = 200;
        for (int i = 0; i < n; i++) {
            Quest q1 = gen.generate(1, List.of(), rng);
            Quest q25 = gen.generate(25, List.of(), rng);
            low += q1.objectives().stream().mapToInt(QuestObjective::amount).sum();
            high += q25.objectives().stream().mapToInt(QuestObjective::amount).sum();
        }
        assertTrue(high / n > (low / n) * 5,
                "expected level-25 amounts to dwarf level-1 amounts, got avg1=" + low / n + " avg25=" + high / n);
    }

    @Test
    void endgameQuestsAreMultiObjective() {
        QuestGenerator gen = generator();
        Random rng = new Random(13);
        for (int i = 0; i < 100; i++) {
            Quest q = gen.generate(13 + rng.nextInt(10), List.of(), rng);
            assertEquals(2, q.objectives().size(), "level 13+ quests must combine two objectives");
            assertEquals(2, q.templateIds().size());
        }
    }

    @Test
    void earlyQuestsAreSingleObjective() {
        QuestGenerator gen = generator();
        Random rng = new Random(3);
        for (int level = 1; level <= 12; level++) {
            Quest q = gen.generate(level, List.of(), rng);
            assertEquals(1, q.objectives().size(), "level " + level + " should be single-objective");
            assertEquals(level, q.level());
        }
    }

    @Test
    void everyBandCoversItsLevels() {
        QuestGenerator gen = generator();
        Random rng = new Random(99);
        // Bands 1-3, 4-6, 7-9, 10-12, 13+: every level 1..40 must generate.
        for (int level = 1; level <= 40; level++) {
            Quest q = gen.generate(level, List.of(), rng);
            assertNotNull(q);
            assertFalse(q.objectives().isEmpty());
            assertFalse(q.getDescription().isBlank());
        }
    }
}
