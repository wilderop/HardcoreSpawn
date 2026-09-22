package com.wilderop.hardcorespawn;

import org.bukkit.Location;

import java.util.UUID;

/** Mutable state of one active hardcore run. */
public final class Session {
    public final UUID playerId;
    public final Location returnLocation;

    public int level;                 // quests completed
    public int questsCompleted;
    public Quest quest;               // current quest (quest.level() == level + 1)
    public long questDeadlineMs;      // epoch millis; 0 = paused across a restart
    public long offlineSinceMs;       // 0 = online
    public boolean warned60;
    public boolean warned30;
    public boolean timeoutDamagePhase;
    public long nextDamageMs;
    // Frozen at shutdown so restart downtime counts against neither the quest
    // clock nor the disconnect grace period. questDeadlineMs == 0 while paused.
    public long pausedQuestRemainingMs;
    public long pausedOfflineElapsedMs;

    public Session(UUID playerId, Location returnLocation) {
        this.playerId = playerId;
        this.returnLocation = returnLocation;
    }

    public long questTimeLeftMs(long now) {
        return questDeadlineMs - now;
    }

    public boolean isOnline(long now) {
        return offlineSinceMs == 0;
    }
}
