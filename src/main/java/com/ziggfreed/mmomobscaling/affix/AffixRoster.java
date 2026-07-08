package com.ziggfreed.mmomobscaling.affix;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.mmomobscaling.rarity.Rarity;
import com.ziggfreed.mmomobscaling.variant.Variant;
import com.ziggfreed.common.util.SplitMix64;

/**
 * The affix pool, prepared once at asset load and rolled at spawn. Built from the folded {@link Affix} set
 * (dropping non-rollable {@code SpawnWeight <= 0}), sorted ascending by {@code minDifficulty}. A spawn picks
 * up to {@code slots} DISTINCT affixes by difficulty-gated, rarity-gated, weighted pick WITHOUT replacement,
 * enforcing at most one resistance-bearing affix per mob (the {@code MAX_DEFENSE_REDUCTION} convention).
 *
 * <p>Deterministic for a given {@link SplitMix64} seed. The pool is small, so the per-slot pass is cheap; the
 * per-pick working buffers are modest and the whole roll is off the per-tick path (spawn-only).
 */
public final class AffixRoster {

    private final Affix[] entries;

    private AffixRoster(@Nonnull Affix[] entries) {
        this.entries = entries;
    }

    /** Build from the folded affixes; non-rollable ({@code SpawnWeight <= 0}) affixes are dropped. */
    @Nonnull
    public static AffixRoster build(@Nonnull Collection<Affix> affixes) {
        List<Affix> list = new ArrayList<>();
        for (Affix a : affixes) {
            if (a != null && a.spawnWeight() > 0.0) {
                list.add(a);
            }
        }
        // Tie-break on id so pick order is a pure function of the asset SET (a total, content-determined
        // order), closing the ConcurrentHashMap-iteration channel that reshuffled equal-MinDifficulty entries.
        list.sort(Comparator.comparingDouble(Affix::minDifficulty).thenComparing(Affix::id));
        return new AffixRoster(list.toArray(new Affix[0]));
    }

    /**
     * Pick up to {@code slots} distinct affixes for a mob of {@code rarity} at {@code effDifficulty}. Gating:
     * {@code minDifficulty <= effDifficulty}, {@code rarity.allowsAffix(id)} AND {@code affix.allowsRarity}, no
     * duplicates, and at most one resistance-bearing affix. Returns an immutable list (possibly empty). Retained
     * for callers/tests that roll the RARITY axis alone; the spawn hook uses the combined
     * {@link #pick(double, Rarity, Variant, SplitMix64)}.
     */
    @Nonnull
    public List<Affix> pick(double effDifficulty, @Nonnull Rarity rarity, int slots, @Nonnull SplitMix64 rng) {
        if (slots <= 0 || entries.length == 0) {
            return List.of();
        }
        boolean[] used = new boolean[entries.length];
        boolean[] resistanceTaken = {false};
        List<Affix> out = new ArrayList<>(Math.min(slots, entries.length));
        rollInto(effDifficulty, slots, rng, out, used, resistanceTaken,
                a -> rarity.allowsAffix(a.id()) && a.allowsRarity(rarity.id()));
        return List.copyOf(out);
    }

    /**
     * The COMBINED roll with no per-world gate (world gate = allow-all, no extra slots). Retained for
     * callers/tests; the spawn hook uses
     * {@link #pick(double, Rarity, Variant, int, Predicate, SplitMix64)}.
     */
    @Nonnull
    public List<Affix> pick(double effDifficulty, @Nullable Rarity rarity, @Nullable Variant variant,
            @Nonnull SplitMix64 rng) {
        return pick(effDifficulty, rarity, variant, 0, a -> true, rng);
    }

    /**
     * The COMBINED roll: the {@code rarity}'s affixes THEN the {@code variant}'s affixes (either nullable),
     * THEN {@code extraSlots} per-world bonus slots (1.0.2 {@code Pool.Affixes.ExtraSlots}), into one
     * distinct list that shares the used-affix set + the single-resistance cap across ALL hosts (so a mob
     * never draws the same affix twice, nor two resistance-bearing affixes, whichever axis granted them).
     * Each host contributes its own {@code affixSlots()} and its own gate ({@code allowsAffix} + the
     * affix's reciprocal {@code allowsRarity}/{@code allowsVariant}); the extra slots use the UNION of the
     * two host gates (a plain, variant-less mob has no host to legitimize an affix, so extra slots are a
     * no-op there - documented). The {@code worldGate} (per-world {@code Pool.Affixes} allow/deny) ANDs
     * into every draw. The rarity axis draws first, then variant, then extras, so the rng sequence is
     * fixed for determinism. Returns an immutable list (possibly empty).
     */
    @Nonnull
    public List<Affix> pick(double effDifficulty, @Nullable Rarity rarity, @Nullable Variant variant,
            int extraSlots, @Nonnull Predicate<Affix> worldGate, @Nonnull SplitMix64 rng) {
        if (entries.length == 0) {
            return List.of();
        }
        boolean[] used = new boolean[entries.length];
        boolean[] resistanceTaken = {false};
        List<Affix> out = new ArrayList<>();
        Predicate<Affix> rarityGate = rarity == null ? null
                : a -> rarity.allowsAffix(a.id()) && a.allowsRarity(rarity.id());
        Predicate<Affix> variantGate = variant == null ? null
                : a -> variant.allowsAffix(a.id()) && a.allowsVariant(variant.id());
        if (rarity != null && rarity.affixSlots() > 0) {
            rollInto(effDifficulty, rarity.affixSlots(), rng, out, used, resistanceTaken,
                    a -> rarityGate.test(a) && worldGate.test(a));
        }
        if (variant != null && variant.affixSlots() > 0) {
            rollInto(effDifficulty, variant.affixSlots(), rng, out, used, resistanceTaken,
                    a -> variantGate.test(a) && worldGate.test(a));
        }
        if (extraSlots > 0 && (rarityGate != null || variantGate != null)) {
            rollInto(effDifficulty, extraSlots, rng, out, used, resistanceTaken,
                    a -> ((rarityGate != null && rarityGate.test(a))
                            || (variantGate != null && variantGate.test(a))) && worldGate.test(a));
        }
        return List.copyOf(out);
    }

    /**
     * Roll up to {@code slots} distinct affixes matching {@code hostGate}, appending to {@code out} and
     * threading the shared {@code used} set + {@code resistanceTaken[0]} cap (so a second host call never
     * re-picks or exceeds the single-resistance rule). Weighted pick WITHOUT replacement, difficulty-gated.
     */
    private void rollInto(double effDifficulty, int slots, @Nonnull SplitMix64 rng, @Nonnull List<Affix> out,
            @Nonnull boolean[] used, @Nonnull boolean[] resistanceTaken, @Nonnull Predicate<Affix> hostGate) {
        for (int s = 0; s < slots; s++) {
            double total = 0.0;
            for (int i = 0; i < entries.length; i++) {
                if (!used[i] && eligible(entries[i], effDifficulty, resistanceTaken[0], hostGate)) {
                    total += entries[i].spawnWeight();
                }
            }
            if (total <= 0.0) {
                break; // nothing left to pick for this host
            }
            double roll = rng.nextDouble() * total;
            double acc = 0.0;
            int chosen = -1;
            for (int i = 0; i < entries.length; i++) {
                if (used[i] || !eligible(entries[i], effDifficulty, resistanceTaken[0], hostGate)) {
                    continue;
                }
                acc += entries[i].spawnWeight();
                if (roll < acc) {
                    chosen = i;
                    break;
                }
            }
            if (chosen < 0) {
                break; // numerical guard
            }
            used[chosen] = true;
            Affix a = entries[chosen];
            out.add(a);
            if (a.resistanceBearing()) {
                resistanceTaken[0] = true;
            }
        }
    }

    private static boolean eligible(@Nonnull Affix a, double effDifficulty, boolean resistanceTaken,
            @Nonnull Predicate<Affix> hostGate) {
        if (a.minDifficulty() > effDifficulty) {
            return false;
        }
        if (resistanceTaken && a.resistanceBearing()) {
            return false;
        }
        return hostGate.test(a);
    }

    /** Number of rollable affixes (test/inspect hook). */
    public int size() {
        return entries.length;
    }
}
