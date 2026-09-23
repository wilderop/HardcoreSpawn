package com.wilderop.hardcorespawn;

import org.bukkit.Location;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Mutable state of one active hardcore run. */
public final class Session {
    /** How many quests are offered at once. Complete any one to reset the timer. */
    public static final int HAND_SIZE = 4;

    public final UUID playerId;
    public final Location returnLocation;

    public int level;                 // quests completed
    public int questsCompleted;
    /** Epoch millis when the run actually started (after the start freeze). */
    public long runStartedMs;
    /** The active quest hand: up to HAND_SIZE quests. Completing any one
     *  resets the quest clock and replaces the completed quest with a new one,
     *  so the player always has options. */
    public final List<Quest> hand = new ArrayList<>();
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
