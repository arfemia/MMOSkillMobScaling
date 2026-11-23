package com.ziggfreed.mmomobscaling.caster;

import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nonnull;

import com.ziggfreed.mmomobscaling.MobScalingPlugin;

/**
 * A session-wide latch for the {@code ABILITY}-cast half of the caster feature (ziggfreed-common's
 * {@code NpcAbilityCasts}, which the mod owning the abilities fills: MMO Skill Tree's NPC cast). On a server
 * where no mod fills that seam, or with a ziggfreed-common older than it (a {@link LinkageError}), the FIRST
 * armed {@code ABILITY} entry that tries to cast trips this latch once and logs ONE warning; every subsequent
 * tick then skips ability casts with a single {@code AtomicBoolean} read (near-zero cost), so the server
 * degrades gracefully instead of spamming the log or crashing. {@code NATIVE_CHAIN} entries are unaffected
 * (they never cast through the seam) and keep arming normally.
 */
public final class CasterFeatureState {

    private static final AtomicBoolean ABILITY_CASTING_DISABLED = new AtomicBoolean(false);

    private CasterFeatureState() {
    }

    /** True once {@link #disableAbilityCasting} has fired this session. */
    public static boolean isAbilityCastingDisabled() {
        return ABILITY_CASTING_DISABLED.get();
    }

    /**
     * Flip the latch (idempotent) and log exactly once (the {@code compareAndSet} guard, not a
     * warn-once set, since the latch only ever flips once).
     */
    public static void disableAbilityCasting(@Nonnull String reason) {
        if (ABILITY_CASTING_DISABLED.compareAndSet(false, true)) {
            try {
                MobScalingPlugin.LOGGER.atWarning().log(
                        "Mob-scaling caster: AbilityId caster entries cannot cast here, so they are skipped for "
                                + "this session (NativeChain entries are unaffected). A mod that has abilities, "
                                + "such as MMO Skill Tree, casts them for creatures. Cause: " + reason);
            } catch (Throwable ignored) {
                // log-manager-less JVMs
            }
        }
    }
}
