package com.ziggfreed.mmomobscaling.rarity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.util.SplitMix64;

/**
 * The rarity ladder, prepared once at asset load and rolled allocation-lightly at spawn. It carries TWO
 * views of the folded {@link Rarity} set: the ROLL ladder (rollable tiers only, i.e. {@code Weight > 0},
 * sorted ascending by {@code minDifficulty}) that {@link #pick} draws from, and the full tier set sorted
 * strongest-first that {@link #forced} scans (a forced tier is deliberately allowed to be off the weighted
 * ladder). A spawn does a two-draw deterministic roll: a {@code raritySpawnChance} gate (plain vs special),
 * then a difficulty-gated weighted pick among the eligible tiers; a forced tier is then folded in as a FLOOR
 * via {@link #strongerOf}.
 *
 * <p>Deterministic for a given {@link SplitMix64} seed, so a chunk reload re-rolls identically. The tier set
 * is tiny (a handful), so the per-spawn pass over it is cheap; band-bucketing the ladder is a Phase-5
 * hot-path refinement if profiling ever shows pressure.
 */
public final class RarityRoster {

    private final Rarity[] entries;

    /** EVERY folded tier (rollable or not), strongest first - the {@link #forced} lookup space. */
    private final Rarity[] all;

    private RarityRoster(@Nonnull Rarity[] entries, @Nonnull Rarity[] all) {
        this.entries = entries;
        this.all = all;
    }

    /**
     * Build from the folded rarities. The ROLL ladder drops non-rollable ({@code Weight <= 0}) tiers; the
     * FORCE lookup space keeps every tier, since a forced tier (a boss tier, say) is deliberately off the
     * weighted ladder.
     */
    @Nonnull
    public static RarityRoster build(@Nonnull Collection<Rarity> rarities) {
        List<Rarity> list = new ArrayList<>();
        List<Rarity> everything = new ArrayList<>();
        for (Rarity r : rarities) {
            if (r == null) {
                continue;
            }
            everything.add(r);
            if (r.weight() > 0.0) {
                list.add(r);
            }
        }
        // Tie-break on id so pick order is a pure function of the asset SET (a total, content-determined
        // order), closing the ConcurrentHashMap-iteration channel that reshuffled equal-MinDifficulty entries.
        list.sort(Comparator.comparingDouble(Rarity::minDifficulty).thenComparing(Rarity::id));
        // Strongest first, so the forced lookup returns on its first match.
        everything.sort((a, b) -> b.compareStrength(a));
        return new RarityRoster(list.toArray(new Rarity[0]), everything.toArray(new Rarity[0]));
    }

    /**
     * The strongest tier {@code forcedEligible} accepts, or {@code null} when nothing forces this mob. The
     * caller builds the predicate from the mob's identity (the tier's {@code ForceGroups}/{@code ForceRoles}
     * lists via {@code family/MobFamilyMatcher}, ANDed with any per-world pool gate). Consumes NO RNG and
     * spans EVERY tier including non-rollable ones, so a weight-0 tier can still be forced onto a family.
     */
    @Nullable
    public Rarity forced(@Nonnull Predicate<Rarity> forcedEligible) {
        for (Rarity r : all) {
            if (forcedEligible.test(r)) {
                return r; // sorted strongest-first
            }
        }
        return null;
    }

    /**
     * The stronger of two tiers (either may be {@code null} = plain), by
     * {@link Rarity#compareStrength(Rarity)}. This is what makes a FORCED tier a floor rather than a pin: a
     * normal roll that lands higher still wins.
     */
    @Nullable
    public static Rarity strongerOf(@Nullable Rarity a, @Nullable Rarity b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.compareStrength(b) >= 0 ? a : b;
    }

    /**
     * Roll a rarity for a mob at {@code effDifficulty}. Returns {@code null} for PLAIN (no special rarity):
     * the {@code raritySpawnChance} gate failed, no tier is eligible at this difficulty, or the mob-family
     * predicate filtered every otherwise-eligible tier out.
     *
     * <p>The {@code familyEligible} predicate narrows the eligible SET only; it consumes no RNG and is applied
     * AFTER the spawn-chance draw, so the seed-&gt;result mapping (hence per-mob determinism) is unchanged from
     * the un-filtered roll. The caller (the spawn hook) builds it from the mob's identity via
     * {@code family/MobFamilyMatcher}; a pure {@code r -> true} disables family gating (tests / callers with no
     * mob context).
     *
     * @param effDifficulty     the resolved effective difficulty (drives which tiers are eligible)
     * @param raritySpawnChance probability {@code [0,1]} of rolling a non-plain rarity at all
     * @param rng               a per-mob deterministic generator (its draw order is fixed for determinism)
     * @param familyEligible    per-mob family gate: {@code true} = this tier may roll on this mob
     */
    @Nullable
    public Rarity pick(double effDifficulty, double raritySpawnChance, @Nonnull SplitMix64 rng,
            @Nonnull Predicate<Rarity> familyEligible) {
        if (entries.length == 0) {
            return null;
        }
        if (rng.nextDouble() >= raritySpawnChance) {
            return null; // plain
        }
        double total = 0.0;
        for (Rarity r : entries) {
            if (r.minDifficulty() <= effDifficulty && familyEligible.test(r)) {
                total += r.weight();
            }
        }
        if (total <= 0.0) {
            return null; // no tier eligible at this difficulty for this mob family
        }
        double roll = rng.nextDouble() * total;
        double acc = 0.0;
        for (Rarity r : entries) {
            if (r.minDifficulty() > effDifficulty || !familyEligible.test(r)) {
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
