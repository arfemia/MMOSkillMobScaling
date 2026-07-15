package com.ziggfreed.mmomobscaling.caster;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Shared scheduling math for an armed {@link CasterEntry}'s due-timer, used identically at ARM time
 * (the first schedule) and by the cadence ticking system (every re-schedule after a fire), so the two
 * call sites can never drift apart.
 *
 * <p><b>Defense-in-depth cadence floor:</b> {@link #MIN_CADENCE_MS} mirrors
 * {@code ScalingContentValidator}'s "CadenceSeconds must be &gt;= 2" content check, but is enforced
 * HERE too (at consumption, not just authoring) - an absent {@code CadenceSeconds} (folds to 0) or a
 * pack that ships a too-low value can never make a mob cast in a tight per-tick loop, even before an
 * admin notices the validator warning.
 *
 * <p>Jitter is intentionally NON-deterministic ({@link ThreadLocalRandom}, not the mod's seeded
 * {@code SplitMix64}): unlike the spawn ROLL (which must reproduce identically across a chunk reload),
 * a live cadence timer's jitter only needs to desync same-roster mobs cosmetically - reproducibility
 * across restarts buys nothing here and would need per-mob seed bookkeeping this doesn't otherwise need.
 */
public final class CasterCadence {

    /** Hard floor for the effective cast interval, regardless of authored/absent {@code CadenceSeconds}. */
    public static final long MIN_CADENCE_MS = 2000L;

    private CasterCadence() {
    }

    /** Clamp a raw (possibly absent-as-zero or too-low) cadence to the safety floor. */
    public static long clampCadenceMs(long cadenceMs) {
        return Math.max(cadenceMs, MIN_CADENCE_MS);
    }

    /**
     * The next due epoch-millis: {@code now + clamped cadence + a uniform [0, jitterMs] extra delay}.
     * A non-positive {@code jitterMs} adds no jitter.
     */
    public static long nextDueAt(long now, long cadenceMs, long jitterMs) {
        long jitter = jitterMs > 0 ? ThreadLocalRandom.current().nextLong(jitterMs + 1) : 0L;
        return now + clampCadenceMs(cadenceMs) + jitter;
    }
}
