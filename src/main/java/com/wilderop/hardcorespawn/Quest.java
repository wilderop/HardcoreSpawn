package com.wilderop.hardcorespawn;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/** A quest: one or more objectives that must all be completed. */
public final class Quest {
    private final int level;
    private final List<String> templateIds;
    private final List<QuestObjective> objectives;

    public Quest(int level, List<String> templateIds, List<QuestObjective> objectives) {
        this.level = level;
        this.templateIds = List.copyOf(templateIds);
        this.objectives = new ArrayList<>(objectives);
    }

    public int level() { return level; }
    public List<String> templateIds() { return templateIds; }
    public List<QuestObjective> objectives() { return Collections.unmodifiableList(objectives); }

    public boolean isComplete() {
        return objectives.stream().allMatch(QuestObjective::isComplete);
    }

    /** Add progress to every incomplete objective matching the action. Returns true if anything moved. */
    public boolean progress(QuestType type, String target, int amount) {
        boolean moved = false;
        for (QuestObjective o : objectives) {
            if (!o.isComplete() && o.matches(type, target)) {
                o.addProgress(amount);
                moved = true;
            }
        }
        return moved;
    }

    public String getDescription() {
        return objectives.stream().map(QuestObjective::description).collect(Collectors.joining(" + "));
    }

    /** Human-readable progress, e.g. "12/32 oak logs". */
    public String getProgressText() {
        return objectives.stream()
                .map(o -> o.progress() + "/" + o.amount())
                .collect(Collectors.joining(" + "));
    }
}
