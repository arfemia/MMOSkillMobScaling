package com.ziggfreed.mmomobscaling.util;

/**
 * A deterministic SplitMix64 pseudo-random generator - the ONLY randomness on the spawn roll path (the plan
 * forbids {@code java.util.Random} there, whose seeding + stream are not stable across the guarantees we
 * need). Given the same seed it yields the identical stream, so a chunk reload re-rolls a mob's rarity +
 * affixes IDENTICALLY. Seed a mob from stable inputs (role name hash ^ world seed ^ chunk/position) via
 * {@link #mix} so the roll survives a restart.
 *
 * <p>Not thread-safe (each roll builds its own instance); pure arithmetic, zero engine coupling.
 */
public final class SplitMix64 {

    private static final long GOLDEN = 0x9E3779B97F4A7C15L;
    private static final long MIX1 = 0xBF58476D1CE4E5B9L;
    private static final long MIX2 = 0x94D049BB133111EBL;

    private long state;

    public SplitMix64(long seed) {
        this.state = seed;
    }

    /** The next 64-bit value in the deterministic stream. */
    public long nextLong() {
        long z = (state += GOLDEN);
        z = (z ^ (z >>> 30)) * MIX1;
        z = (z ^ (z >>> 27)) * MIX2;
        return z ^ (z >>> 31);
    }

    /** A double in {@code [0, 1)} (53-bit mantissa). */
    public double nextDouble() {
        return (nextLong() >>> 11) * 0x1.0p-53;
    }

    /** Deterministically fold two longs into one seed (stable across runs, unlike {@code Objects.hash}). */
    public static long mix(long a, long b) {
        long z = a * GOLDEN + b;
        z = (z ^ (z >>> 30)) * MIX1;
        z = (z ^ (z >>> 27)) * MIX2;
        return z ^ (z >>> 31);
    }
}
