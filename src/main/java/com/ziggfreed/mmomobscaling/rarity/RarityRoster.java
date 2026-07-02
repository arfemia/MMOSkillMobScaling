package com.ziggfreed.mmomobscaling.rarity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.util.SplitMix64;

/**
 * The rollable rarity ladder, prepared once at asset load and rolled allocation-lightly at spawn. Built from
 * the folded {@link Rarity} set (excluding non-rollable "plain" entries, i.e. {@code Weight <= 0}), sorted
 * ascending by {@code minDifficulty}. A spawn does a two-draw deterministic roll: a {@code raritySpawnChance}
 * gate (plain vs special), then a difficulty-gated weighted pick among the eligible tiers.
 *
 * <p>Deterministic for a given {@link SplitMix64} seed, so a chunk reload re-rolls identically. The tier set
 * is tiny (a handful), so the per-spawn pass over it is cheap; band-bucketing the ladder is a Phase-5
 * hot-path refinement if profiling ever shows pressure.
 */
public final class RarityRoster {

    private final Rarity[] entries;

    private RarityRoster(@Nonnull Rarity[] entries) {
        this.entries = entries;
    }

    /** Build from the folded rarities; non-rollable ({@code Weight <= 0}) tiers are dropped. */
    @Nonnull
    public static RarityRoster build(@Nonnull Collection<Rarity> rarities) {
        List<Rarity> list = new ArrayList<>();
        for (Rarity r : rarities) {
            if (r != null && r.weight() > 0.0) {
                list.add(r);
            }
        }
        // Tie-break on id so pick order is a pure function of the asset SET (a total, content-determined
        // order), closing the ConcurrentHashMap-iteration channel that reshuffled equal-MinDifficulty entries.
        list.sort(Comparator.comparingDouble(Rarity::minDifficulty).thenComparing(Rarity::id));
        return new RarityRoster(list.toArray(new Rarity[0]));
    }

    /**
     * Roll a rarity for a mob at {@code effDifficulty}. Returns {@code null} for PLAIN (no special rarity):
     * either the {@code raritySpawnChance} gate failed or no tier is eligible at this difficulty.
     *
     * @param effDifficulty     the resolved effective difficulty (drives which tiers are eligible)
     * @param raritySpawnChance probability {@code [0,1]} of rolling a non-plain rarity at all
     * @param rng               a per-mob deterministic generator (its draw order is fixed for determinism)
     */
    @Nullable
    public Rarity pick(double effDifficulty, double raritySpawnChance, @Nonnull SplitMix64 rng) {
        if (entries.length == 0) {
            return null;
        }
        if (rng.nextDouble() >= raritySpawnChance) {
            return null; // plain
        }
        double total = 0.0;
        for (Rarity r : entries) {
            if (r.minDifficulty() <= effDifficulty) {
                total += r.weight();
            }
        }
        if (total <= 0.0) {
            return null;
        }
        double roll = rng.nextDouble() * total;
        double acc = 0.0;
        for (Rarity r : entries) {
            if (r.minDifficulty() > effDifficulty) {
                continue;
            }
            acc += r.weight();
            if (roll < acc) {
                return r;
            }
        }
        return null; // numerical guard (rounding); treat as plain
    }

    /** Number of rollable tiers (test/inspect hook). */
    public int size() {
        return entries.length;
    }
}
