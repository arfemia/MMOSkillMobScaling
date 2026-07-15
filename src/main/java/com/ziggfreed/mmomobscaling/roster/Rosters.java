package com.ziggfreed.mmomobscaling.roster;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import javax.annotation.Nonnull;

import com.ziggfreed.mmomobscaling.affix.AffixRoster;
import com.ziggfreed.mmomobscaling.caster.CasterRoster;
import com.ziggfreed.mmomobscaling.config.AffixConfig;
import com.ziggfreed.mmomobscaling.config.CasterRosterConfig;
import com.ziggfreed.mmomobscaling.config.RarityConfig;
import com.ziggfreed.mmomobscaling.config.VariantConfig;
import com.ziggfreed.mmomobscaling.rarity.RarityRoster;
import com.ziggfreed.mmomobscaling.variant.VariantRoster;

/**
 * The prepared rarity/variant/affix rosters the spawn hook rolls, rebuilt from the folded configs after each
 * {@code LoadedAssetsEvent} (so a pack/owner override or a hot reload re-buckets the pools). Reads are
 * lock-free off {@code volatile} refs; the spawn hook never rebuilds on the hot path. Starts EMPTY so a
 * spawn before assets load simply rolls plain (no rarity/variant), never NPEs.
 *
 * <p>{@link #casterRosters()} is the same lock-free idiom for the {@link CasterRoster} set, but sorted
 * (by id) rather than weight-bucketed - {@code CasterRosterMatcher}'s "first" tie-break needs a stable,
 * deterministic input order, which the id sort gives it regardless of the folded map's iteration order.
 */
public final class Rosters {

    private static volatile RarityRoster rarity = RarityRoster.build(List.of());
    private static volatile VariantRoster variant = VariantRoster.build(List.of());
    private static volatile AffixRoster affix = AffixRoster.build(List.of());
    private static volatile List<CasterRoster> casterRosters = List.of();

    private Rosters() {
    }

    /** Rebuild all rosters from the current folded configs (called from the asset load listeners). */
    public static void rebuild() {
        rarity = RarityRoster.build(RarityConfig.getInstance().all().values());
        variant = VariantRoster.build(VariantConfig.getInstance().all().values());
        affix = AffixRoster.build(AffixConfig.getInstance().all().values());
        List<CasterRoster> cr = new ArrayList<>(CasterRosterConfig.getInstance().all().values());
        cr.sort(Comparator.comparing(CasterRoster::id));
        casterRosters = List.copyOf(cr);
    }

    @Nonnull
    public static RarityRoster rarity() {
        return rarity;
    }

    @Nonnull
    public static VariantRoster variant() {
        return variant;
    }

    @Nonnull
    public static AffixRoster affix() {
        return affix;
    }

    /** The folded caster rosters, id-sorted for a deterministic {@code CasterRosterMatcher} tie-break. */
    @Nonnull
    public static List<CasterRoster> casterRosters() {
        return casterRosters;
    }
}
