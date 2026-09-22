package com.wilderop.hardcorespawn;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Generates endless escalating quests from config-defined level bands.
 * Never emits a quest built from the same template(s) as the previous one.
 */
public final class QuestGenerator {

    /** One level band from the config. */
    public static final class Band {
        final int minLevel;
        final int maxLevel;
        final List<QuestTemplate> templates;

        Band(int minLevel, int maxLevel, List<QuestTemplate> templates) {
            this.minLevel = minLevel;
            this.maxLevel = maxLevel;
            this.templates = List.copyOf(templates);
        }

        boolean covers(int level) {
            return level >= minLevel && level <= maxLevel;
        }
    }

    private final List<Band> bands;
    private final double growth;
    private final int multiObjectiveMinLevel;
    private final Random random = new Random();

    public QuestGenerator(List<Band> bands, double growth, int multiObjectiveMinLevel) {
        if (bands.isEmpty()) {
            throw new IllegalArgumentException("At least one quest band is required");
        }
        this.bands = List.copyOf(bands);
        this.growth = growth;
        this.multiObjectiveMinLevel = multiObjectiveMinLevel;
    }

    @SuppressWarnings("unchecked")
    public static QuestGenerator fromConfig(HardcoreConfig config) {
        List<Band> bands = new ArrayList<>();
        for (Map<?, ?> bandMap : config.getBandMaps()) {
            int min = ((Number) bandMap.get("min-level")).intValue();
            int max = ((Number) bandMap.get("max-level")).intValue();
            List<QuestTemplate> templates = new ArrayList<>();
            for (Map<?, ?> q : (List<Map<?, ?>>) bandMap.get("quests")) {
                String id = String.valueOf(q.get("id"));
                templates.add(QuestTemplate.fromConfig(id, q));
            }
            bands.add(new Band(min, max, templates));
        }
        return new QuestGenerator(bands, config.getDifficultyGrowth(), config.getMultiObjectiveMinLevel());
    }

    private Band bandFor(int level) {
        for (Band b : bands) {
            if (b.covers(level)) {
                return b;
            }
        }
        return bands.get(bands.size() - 1); // beyond the last band: keep scaling endgame
    }

    /**
     * Build a quest from the first template of the given type in the band for
     * this level. Used for the guaranteed easy starter quest. Returns null
     * when the band has no such template (the caller should fall back).
     */
    public Quest generateByType(int level, QuestType type) {
        Band band = bandFor(level);
        for (QuestTemplate t : band.templates) {
            if (t.type() == type) {
                int amount = t.amountForLevel(level, growth);
                return new Quest(level, List.of(t.id()),
                        List.of(new QuestObjective(t.type(), t.target(), amount, t.describe(amount))));
            }
        }
        return null;
    }

    /**
     * Generate the quest for the given 1-based level.
     *
     * @param level            quest level (quests completed + 1)
     * @param lastTemplateIds  template ids used by the previous quest (excluded)
     */
    public Quest generate(int level, List<String> lastTemplateIds) {
        Band band = bandFor(level);
        int objectiveCount = level >= multiObjectiveMinLevel ? 2 : 1;

        List<QuestTemplate> pool = new ArrayList<>(band.templates);
        // Exclude last quest's templates so quests never repeat back-to-back.
        if (pool.size() > objectiveCount) {
            pool.removeIf(t -> lastTemplateIds.contains(t.id()));
            if (pool.size() < objectiveCount) {
                pool = new ArrayList<>(band.templates);
            }
        }

        List<QuestTemplate> picked = new ArrayList<>();
        List<QuestTemplate> choosing = new ArrayList<>(pool);
        for (int i = 0; i < objectiveCount && !choosing.isEmpty(); i++) {
            picked.add(choosing.remove(random.nextInt(choosing.size())));
        }

        List<String> ids = new ArrayList<>();
        List<QuestObjective> objectives = new ArrayList<>();
        for (QuestTemplate t : picked) {
            int amount = t.amountForLevel(level, growth);
            ids.add(t.id());
            objectives.add(new QuestObjective(t.type(), t.target(), amount, t.describe(amount)));
        }
        return new Quest(level, ids, objectives);
    }

    /** Test seam: deterministic generation. */
    Quest generate(int level, List<String> lastTemplateIds, Random rng) {
        Band band = bandFor(level);
        int objectiveCount = level >= multiObjectiveMinLevel ? 2 : 1;
        List<QuestTemplate> pool = new ArrayList<>(band.templates);
        if (pool.size() > objectiveCount) {
            pool.removeIf(t -> lastTemplateIds.contains(t.id()));
            if (pool.size() < objectiveCount) {
                pool = new ArrayList<>(band.templates);
            }
        }
        List<QuestTemplate> picked = new ArrayList<>();
        List<QuestTemplate> choosing = new ArrayList<>(pool);
        for (int i = 0; i < objectiveCount && !choosing.isEmpty(); i++) {
            picked.add(choosing.remove(rng.nextInt(choosing.size())));
        }
        List<String> ids = new ArrayList<>();
        List<QuestObjective> objectives = new ArrayList<>();
        for (QuestTemplate t : picked) {
            int amount = t.amountForLevel(level, growth);
            ids.add(t.id());
            objectives.add(new QuestObjective(t.type(), t.target(), amount, t.describe(amount)));
        }
        return new Quest(level, ids, objectives);
    }

    public List<Band> bands() {
        return bands;
    }
}
