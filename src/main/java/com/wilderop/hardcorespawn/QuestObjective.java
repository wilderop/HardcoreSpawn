package com.wilderop.hardcorespawn;

/** One measurable objective inside a quest (quests may combine several at high levels). */
public final class QuestObjective {
    private final QuestType type;
    private final String target;
    private final int amount;
    private final String description;
    private int progress;

    public QuestObjective(QuestType type, String target, int amount, String description) {
        this.type = type;
        this.target = target;
        this.amount = amount;
        this.description = description;
    }

    public QuestType type() { return type; }
    public String target() { return target; }
    public int amount() { return amount; }
    public int progress() { return progress; }
    public String description() { return description; }

    public void addProgress(int n) {
        if (!isComplete()) {
            progress = Math.min(amount, progress + Math.max(0, n));
        }
    }

    public void setProgress(int n) {
        progress = Math.max(0, Math.min(amount, n));
    }

    public boolean isComplete() {
        return progress >= amount;
    }

    /** Does this objective care about the given action? */
    public boolean matches(QuestType type, String target) {
        return this.type == type && this.target.equalsIgnoreCase(target);
    }
}
