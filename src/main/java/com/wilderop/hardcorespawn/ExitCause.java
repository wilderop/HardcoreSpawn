package com.wilderop.hardcorespawn;

/** Why a run ended. Every cause funnels through the same exit path. */
public enum ExitCause {
    /** Died in the world (mobs, timer, lava, anything). Run gains drop at the death location. */
    IN_WORLD_DEATH,
    /** /hardcore quit. Run gains are deleted. */
    QUIT,
    /** Offline longer than the disconnect grace period. Run gains are deleted. */
    DISCONNECT_TIMEOUT,
    /** An admin reset the run. Run gains are deleted. */
    ADMIN_RESET
}
