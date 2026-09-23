package com.wilderop.hardcorespawn;

/** Why a run ended. Every cause funnels through the same exit path. */
public enum ExitCause {
    /** Died in the world (mobs, timer, lava, anything). Run gains drop at the death location. */
    IN_WORLD_DEATH,
    /**
     * Lethal damage was intercepted (or the quest timer expired): instead of
     * a real death + respawn, the player is healed to full and the run ends
     * immediately. Run gains drop at the location, exactly like a death, but
     * the player never dies — so pre-run XP restores cleanly.
     */
    AVERTED_DEATH,
    /** /hardcore quit. Run gains are deleted. */
    QUIT,
    /** Offline longer than the disconnect grace period. Run gains are deleted. */
    DISCONNECT_TIMEOUT,
    /** An admin reset the run. Run gains are deleted. */
    ADMIN_RESET
}
