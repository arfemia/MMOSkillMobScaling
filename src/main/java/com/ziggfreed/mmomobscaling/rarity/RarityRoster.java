package com.ziggfreed.mmomobscaling.rarity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * <p>The same strength ordering also gives {@link #tierOf} - each tier's position on the ladder, counting
 * up from a plain mob - which is what content outside this mod reads when it wants "elite or better"
 * rather than one named tier.
 *
 * <p>Deterministic for a given {@link SplitMix64} seed, so a chunk reload re-rolls identically. The tier set
 * is tiny (a handful), so the per-spawn pass over it is cheap; band-bucketing the ladder is a Phase-5
 * hot-path refinement if profiling ever shows pressure.
 */
public final class RarityRoster {

    private final Rarity[] entries;

    /** EVERY folded tier (rollable or not), strongest first - the {@link #forced} lookup space. */
    private final Rarity[] all;

    /** Lower-cased rarity id to its {@link #tierOf} ordinal, built once beside {@link #all}. */
    private final Map<String, Integer> tiers;

    private RarityRoster(@Nonnull Rarity[] entries, @Nonnull Rarity[] all,
            @Nonnull Map<String, Integer> tiers) {
        this.entries = entries;
        this.all = all;
        this.tiers = tiers;
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
        // The same ordering read from the other end gives each tier its LADDER POSITION: the weakest
        // authored tier is 1 and each stronger one is a step up, with 0 left for a plain mob. Deriving
        // it from the tiers themselves is what lets a pack insert a tier mid-ladder and have everything
        // above it move up, instead of an authored number that would then mean two different things.
        Map<String, Integer> tiers = new HashMap<>();
        for (int i = 0; i < everything.size(); i++) {
            tiers.put(everything.get(i).id().toLowerCase(Locale.ROOT), everything.size() - i);
        }
        return new RarityRoster(list.toArray(new Rarity[0]), everything.toArray(new Rarity[0]),
                Map.copyOf(tiers));
    }

    /**
     * Where {@code rarityId} sits on the ladder: {@code 0} for a plain mob (a blank or unknown id),
     * {@code 1} for the weakest authored tier, one more for each stronger one. Matched without regard
     * to case, like every other id in this mod.
     */
    public int tierOf(@Nullable String rarityId) {
        if (rarityId == null || rarityId.isEmpty()) {
            return 0;
        }
        Integer tier = tiers.get(rarityId.toLowerCase(Locale.ROOT));
        return tier == null ? 0 : tier;
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
