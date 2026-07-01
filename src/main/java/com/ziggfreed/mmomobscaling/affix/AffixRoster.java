package com.ziggfreed.mmomobscaling.affix;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

import javax.annotation.Nonnull;

import com.ziggfreed.mmomobscaling.rarity.Rarity;
import com.ziggfreed.mmomobscaling.util.SplitMix64;

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
        list.sort(Comparator.comparingDouble(Affix::minDifficulty));
        return new AffixRoster(list.toArray(new Affix[0]));
    }

    /**
     * Pick up to {@code slots} distinct affixes for a mob of {@code rarity} at {@code effDifficulty}. Gating:
     * {@code minDifficulty <= effDifficulty}, {@code rarity.allowsAffix(id)} AND {@code affix.allowsRarity}, no
     * duplicates, and at most one resistance-bearing affix. Returns an immutable list (possibly empty).
     */
    @Nonnull
    public List<Affix> pick(double effDifficulty, @Nonnull Rarity rarity, int slots, @Nonnull SplitMix64 rng) {
        if (slots <= 0 || entries.length == 0) {
            return List.of();
        }
        boolean[] used = new boolean[entries.length];
        List<Affix> out = new ArrayList<>(Math.min(slots, entries.length));
        boolean resistanceTaken = false;

        for (int s = 0; s < slots; s++) {
            double total = 0.0;
            for (int i = 0; i < entries.length; i++) {
                if (!used[i] && eligible(entries[i], effDifficulty, rarity, resistanceTaken)) {
                    total += entries[i].spawnWeight();
                }
            }
            if (total <= 0.0) {
                break; // nothing left to pick
            }
            double roll = rng.nextDouble() * total;
            double acc = 0.0;
            int chosen = -1;
            for (int i = 0; i < entries.length; i++) {
                if (used[i] || !eligible(entries[i], effDifficulty, rarity, resistanceTaken)) {
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
                resistanceTaken = true;
            }
        }
        return List.copyOf(out);
    }

    private static boolean eligible(@Nonnull Affix a, double effDifficulty, @Nonnull Rarity rarity,
            boolean resistanceTaken) {
        if (a.minDifficulty() > effDifficulty) {
            return false;
        }
        if (resistanceTaken && a.resistanceBearing()) {
            return false;
        }
        return rarity.allowsAffix(a.id()) && a.allowsRarity(rarity.id());
    }

    /** Number of rollable affixes (test/inspect hook). */
    public int size() {
        return entries.length;
    }
}
