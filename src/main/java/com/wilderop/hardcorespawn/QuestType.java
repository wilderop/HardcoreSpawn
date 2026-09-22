package com.wilderop.hardcorespawn;

/** How a quest objective is satisfied. */
public enum QuestType {
    /** Break blocks of the target material. */
    BREAK,
    /** Craft items of the target material. */
    CRAFT,
    /** Take smelted items of the target material from a furnace. */
    SMELT,
    /** Kill entities of the target type (killer must be the runner). */
    KILL,
    /** Hold the target amount of the material in your inventory at once. */
    OBTAIN
}
