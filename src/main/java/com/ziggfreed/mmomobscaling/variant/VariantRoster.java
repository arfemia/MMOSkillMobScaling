package com.ziggfreed.mmomobscaling.variant;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.util.SplitMix64;

/**
 * The rollable variant pool, prepared once at asset load and rolled at spawn - the SECOND (overlay) axis
 * beside {@link com.ziggfreed.mmomobscaling.rarity.RarityRoster}. Built from the folded {@link Variant} set
 * (dropping non-rollable {@code Chance <= 0}), sorted ascending by {@code minDifficulty} then id (a total,
 * content-determined order, so the roll is a pure function of the asset SET).
 *
 * <p>The roll is a SINGLE deterministic draw whose outcome the eligible variants' ABSOLUTE chances partition:
 * an eligible variant occupies its {@code chance} slice of {@code [0,1)}, and the leftover {@code 1 - sum} is
 * "no variant" (so at most one variant lands, per the design). Eligibility = {@code minDifficulty <=
 * effDifficulty} AND the per-mob family gate. Consuming exactly one draw (regardless of how many variants are
 * eligible) keeps the seed-&gt;result mapping stable, so a chunk reload re-rolls the same variant. Authors
 * should keep the chances of co-occurring variants summing to at most 1.0 (a larger sum saturates: the tail
 * variants and the no-variant outcome get squeezed).
 */
public final class VariantRoster {

    private final Variant[] entries;

    private VariantRoster(@Nonnull Variant[] entries) {
        this.entries = entries;
    }

    /** Build from the folded variants; non-rollable ({@code Chance <= 0}) variants are dropped. */
    @Nonnull
    public static VariantRoster build(@Nonnull Collection<Variant> variants) {
        List<Variant> list = new ArrayList<>();
        for (Variant v : variants) {
            if (v != null && v.chance() > 0.0) {
                list.add(v);
            }
        }
        list.sort(Comparator.comparingDouble(Variant::minDifficulty).thenComparing(Variant::id));
        return new VariantRoster(list.toArray(new Variant[0]));
    }

    /**
     * Roll at most one variant for a mob at {@code effDifficulty} whose base rarity is {@code baseRarityId}.
     * Returns {@code null} for NO variant (the common case): no variant is eligible, or the single draw landed
     * in the leftover "no variant" slice.
     *
     * @param effDifficulty  the resolved effective difficulty (gates which variants are eligible)
     * @param baseRarityId   the rolled base rarity id ({@code ""} for a plain mob), for the requires-rarity gate
     * @param rng            a per-mob deterministic generator (draws EXACTLY once here, for determinism)
     * @param familyEligible per-mob family gate: {@code true} = this variant may apply to this mob
     */
    @Nullable
    public Variant pick(double effDifficulty, @Nonnull String baseRarityId, @Nonnull SplitMix64 rng,
            @Nonnull Predicate<Variant> familyEligible) {
        if (entries.length == 0) {
            return null;
        }
        // Draw ONCE up front (before any eligibility branching) so the draw count is a constant - the mob's
        // seed maps to the same variant across reloads regardless of content/eligibility changes elsewhere.
        double u = rng.nextDouble();
        double acc = 0.0;
        for (Variant v : entries) {
            if (v.minDifficulty() > effDifficulty || !v.allowsRarity(baseRarityId) || !familyEligible.test(v)) {
                continue;
            }
            acc += v.chance();
            if (u < acc) {
                return v;
            }
        }
        return null; // u landed in the leftover "no variant" slice (or nothing was eligible)
    }

    /** Number of rollable variants (test/inspect hook). */
    public int size() {
        return entries.length;
    }
}
