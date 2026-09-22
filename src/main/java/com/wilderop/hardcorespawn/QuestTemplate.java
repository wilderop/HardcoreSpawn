package com.wilderop.hardcorespawn;

import java.util.Map;
import java.util.Objects;

/**
 * A quest template from the config: what to do, what to target, and how much
 * at level 1. Amounts scale with {@code difficulty-growth} per level.
 */
public final class QuestTemplate {
    private final String id;
    private final QuestType type;
    private final String target;
    private final int baseAmount;
    private final String description;

    public QuestTemplate(String id, QuestType type, String target, int baseAmount, String description) {
        this.id = Objects.requireNonNull(id);
        this.type = Objects.requireNonNull(type);
        this.target = Objects.requireNonNull(target);
        this.baseAmount = baseAmount;
        this.description = Objects.requireNonNull(description);
    }

    @SuppressWarnings("unchecked")
    public static QuestTemplate fromConfig(String id, Map<?, ?> map) {
        QuestType type = QuestType.valueOf(String.valueOf(map.get("type")).toUpperCase());
        String target = String.valueOf(map.get("target")).toUpperCase();
        int baseAmount = ((Number) map.get("base-amount")).intValue();
        String description = String.valueOf(map.get("description"));
        return new QuestTemplate(id, type, target, baseAmount, description);
    }

    public String id() { return id; }
    public QuestType type() { return type; }
    public String target() { return target; }
    public int baseAmount() { return baseAmount; }

    /** Amount for the given 1-based level. */
    public int amountForLevel(int level, double growth) {
        return Math.max(1, (int) Math.round(baseAmount * Math.pow(growth, level - 1)));
    }

    public String describe(int amount) {
        return description.replace("{amount}", String.valueOf(amount))
                .replace("{target}", target.toLowerCase().replace('_', ' '));
    }
}
