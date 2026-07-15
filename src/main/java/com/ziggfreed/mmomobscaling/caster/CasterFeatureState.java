package com.ziggfreed.mmomobscaling.caster;

import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nonnull;

import com.ziggfreed.mmomobscaling.MobScalingPlugin;

/**
 * A session-wide latch for the {@code ABILITY}-cast half of the caster feature
 * ({@code MMOSkillTreeAPI.castNpcAbility(Store, Ref, String)}, added on the MMO's 1.6.0-cycle jar).
 * Running this mod against an OLDER MMO jar (pre-1.6.0-cycle, per the version story in
 * {@code gradle.properties}) throws a {@link LinkageError} (typically {@code NoSuchMethodError}) the
 * FIRST time an armed {@code ABILITY} entry tries to cast - the cadence ticking system catches that
 * once, flips this latch, and logs ONE warning; every subsequent tick then skips ability casts with a
 * single {@code AtomicBoolean} read (near-zero cost), so a mismatched jar pair degrades gracefully
 * instead of spamming the log or crashing. {@code NATIVE_CHAIN} entries are unaffected (they never
 * call the MMO API) and keep arming normally.
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
     * warn-once set, since there is only ever one reason this fires).
     */
    public static void disableAbilityCasting(@Nonnull String reason) {
        if (ABILITY_CASTING_DISABLED.compareAndSet(false, true)) {
            try {
                MobScalingPlugin.LOGGER.atWarning().log(
                        "Mob-scaling caster: MMOSkillTreeAPI.castNpcAbility is unavailable on this MMO jar "
                                + "(needs the 1.6.0-cycle build) - disabling ability-cast rosters for this "
                                + "session (NativeChain entries are unaffected). Cause: " + reason);
            } catch (Throwable ignored) {
                // log-manager-less JVMs
            }
        }
    }
}
